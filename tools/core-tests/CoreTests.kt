package com.mobilestreaming.studio.streaming.rtmp

import com.mobilestreaming.studio.core.model.QualityPreset
import com.mobilestreaming.studio.core.model.Scene
import com.mobilestreaming.studio.core.model.SceneLibrary
import com.mobilestreaming.studio.core.model.StreamConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

private var passed = 0

private fun test(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("PASS: $name")
    } catch (error: Throwable) {
        System.err.println("FAIL: $name — ${error.message}")
        throw error
    }
}

private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
    var thrown = false
    try { block() } catch (error: Throwable) {
        if (error is T) thrown = true else throw error
    }
    check(thrown) { "Expected ${T::class.java.simpleName}" }
}

fun main() {
    test("RTMPS configuration is normalized") {
        val config = StreamConfig(serverUrl = "rtmps://live.example.com/live/", streamKey = " secret ").validated()
        check(config.serverUrl == "rtmps://live.example.com/live")
        check(config.streamKey == "secret")
    }
    test("Non-RTMP protocols and embedded credentials are rejected") {
        expectFailure<IllegalArgumentException> {
            StreamConfig(serverUrl = "http://example.com/live", streamKey = "key").validated()
        }
        expectFailure<IllegalArgumentException> {
            StreamConfig(serverUrl = "rtmp://user:pass@example.com/live", streamKey = "key").validated()
        }
    }
    test("Quality presets set bounded output profiles") {
        val low = StreamConfig.forPreset(QualityPreset.LOW, StreamConfig(streamKey = "key"))
        check(low.width == 854 && low.height == 480 && low.videoBitrateBps == 1_200_000)
        val high = StreamConfig.forPreset(QualityPreset.HIGH, low)
        check(high.width == 1920 && high.frameRate == 30)
    }
    test("Six required starter scenes are present") {
        val names = SceneLibrary.starters().scenes.map { it.name }.toSet()
        check(names == setOf("Starting Soon", "Main Camera", "Interview", "Screen Presentation", "BRB", "Ending Soon"))
    }
    test("Scene duplication creates independent source IDs") {
        val original = SceneLibrary.starters()
        val duplicated = original.duplicateScene(original.activeSceneId)
        check(original.activeScene.sources.first().id != duplicated.activeScene.sources.first().id)
    }
    test("Last scene deletion is prevented") {
        val scene = Scene(name = "Only")
        val library = SceneLibrary(listOf(scene), scene.id)
        expectFailure<IllegalArgumentException> { library.deleteScene(scene.id) }
    }
    test("Scene switching and bounded source reordering") {
        val base = SceneLibrary.starters()
        val next = base.scenes[1]
        val switched = base.switchScene(next.id)
        check(switched.activeScene.name == "Starting Soon")
        val sourceId = switched.activeScene.sources.first().id
        check(switched.updateActiveScene { it.moveSource(sourceId, -10) }.activeScene.sources == next.sources)
    }
    test("AMF0 command round trip") {
        val command = mapOf<String, Any?>("app" to "live", "flashVer" to "FMLE/3.0", "secure" to true)
        val decoded = Amf0.decodeAll(Amf0.encode("connect", 1.0, command, null))
        check(decoded[0] == "connect" && decoded[1] == 1.0 && decoded[3] == null)
        check((decoded[2] as Map<*, *>)["app"] == "live")
        check((decoded[2] as Map<*, *>)["secure"] == true)
    }
    test("AMF0 strict array round trip") {
        val decoded = Amf0.decodeAll(Amf0.encode(listOf("one", 2.0, false)))
        check(decoded.single() == listOf("one", 2.0, false))
    }
    test("Fragmented RTMP message round trip") {
        val payload = ByteArray(10_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        RtmpChunkWriter(output, chunkSize = 128).write(RtmpMessage(1234, 9, 1, payload, 6))
        val decoded = RtmpChunkReader(DataInputStream(ByteArrayInputStream(output.toByteArray()))).readMessage()
        check(decoded.timestamp == 1234L && decoded.typeId == 9 && decoded.payload.contentEquals(payload))
    }
    test("Extended RTMP timestamp round trip") {
        val output = ByteArrayOutputStream()
        RtmpChunkWriter(output, chunkSize = 64).write(RtmpMessage(0x1_0000_00L, 8, 1, ByteArray(200) { 7 }, 4))
        val decoded = RtmpChunkReader(DataInputStream(ByteArrayInputStream(output.toByteArray())), initialChunkSize = 64).readMessage()
        check(decoded.timestamp == 0x1_0000_00L && decoded.payload.size == 200)
    }
    test("Interleaved RTMP messages retain their chunk streams") {
        val output = ByteArrayOutputStream()
        val writer = RtmpChunkWriter(output, chunkSize = 32)
        val audio = ByteArray(100) { 1 }
        val video = ByteArray(100) { 2 }
        writer.write(RtmpMessage(10, 8, 1, audio, 4))
        writer.write(RtmpMessage(11, 9, 1, video, 6))
        val reader = RtmpChunkReader(DataInputStream(ByteArrayInputStream(output.toByteArray())), initialChunkSize = 32)
        check(reader.readMessage().payload.contentEquals(audio))
        check(reader.readMessage().payload.contentEquals(video))
    }
    test("H.264 SPS/PPS create AVCDecoderConfigurationRecord") {
        val sps = byteArrayOf(0x67, 0x64, 0, 0x1F)
        val pps = byteArrayOf(0x68, 0x11, 0x22)
        val avcc = AvcDecoderConfiguration.fromCsd(startCode(sps) + startCode(pps), null)
        check(avcc[0].toInt() == 1 && avcc[1].toInt() == 0x64) { avcc.joinToString() }
        check((avcc[4].toInt() and 3) == 3 && (avcc[5].toInt() and 0x1F) == 1) { avcc.joinToString() }
    }
    test("Annex-B H.264 frame becomes length-prefixed FLV tag") {
        val sps = byteArrayOf(0x67, 0x64, 0, 0x1F)
        val idr = byteArrayOf(0x65, 1, 2, 3, 4)
        val tag = FlvPacketizer.videoFrame(startCode(sps) + startCode(idr), true)
        check(tag[0].toInt() == 0x17 && tag[1].toInt() == 1)
        check(tag.copyOfRange(9, tag.size).contentEquals(idr))
    }
    test("AAC sequence header and raw frame are distinguished") {
        val csd = byteArrayOf(0x12, 0x10)
        check(FlvPacketizer.audioConfig(csd)[1].toInt() == 0)
        check(FlvPacketizer.audioFrame(byteArrayOf(1, 2))[1].toInt() == 1)
    }
    test("Endpoint diagnostics redact query credentials and omit stream key") {
        val endpoint = RtmpEndpoint.from(StreamConfig(
            serverUrl = "rtmps://example.com/live?token=query-secret",
            streamKey = "stream-secret",
        ))
        check("stream-secret" !in endpoint.diagnosticUrl)
        check("query-secret" !in endpoint.diagnosticUrl)
        check(endpoint.tcUrl.contains("token=query-secret"))
    }
    println("\n$passed core tests passed")
}

private fun startCode(data: ByteArray) = byteArrayOf(0, 0, 0, 1) + data
