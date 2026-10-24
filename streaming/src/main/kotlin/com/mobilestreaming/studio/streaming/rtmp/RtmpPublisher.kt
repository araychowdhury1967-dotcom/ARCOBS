package com.mobilestreaming.studio.streaming.rtmp

import com.mobilestreaming.studio.core.model.StreamConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Small RTMP/RTMPS publisher. The class is deliberately independent of Android so its protocol
 * can be tested on the JVM. It does not disable certificate or hostname verification.
 */
internal class RtmpPublisher(
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onPacketSent(typeId: Int, bytes: Int)
        fun onPacketDropped(typeId: Int)
        fun onDisconnected(cause: Throwable)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var writer: RtmpChunkWriter? = null
    @Volatile private var reader: RtmpChunkReader? = null
    @Volatile private var input: DataInputStream? = null
    @Volatile private var connected = false
    @Volatile private var closed = false
    private val terminalNotified = AtomicBoolean(false)
    private val originTimestampUs = AtomicLong(Long.MIN_VALUE)
    private val queue = ArrayBlockingQueue<RtmpMessage>(256)
    @Volatile private var writerThread: Thread? = null
    @Volatile private var readerThread: Thread? = null

    val isConnected: Boolean get() = connected && !closed
    val queuedPackets: Int get() = queue.size

    fun connect(config: StreamConfig) {
        check(socket == null) { "This publisher instance has already been used" }
        val endpoint = RtmpEndpoint.from(config)
        val newSocket = Socket()
        socket = newSocket
        try {
            newSocket.tcpNoDelay = true
            newSocket.keepAlive = true
            newSocket.soTimeout = config.networkTimeoutMs
            newSocket.connect(InetSocketAddress(endpoint.host, endpoint.port), config.networkTimeoutMs)
            val transport = if (endpoint.secure) {
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(
                    newSocket, endpoint.host, endpoint.port, true,
                ) as SSLSocket
                val parameters = tls.sslParameters
                parameters.endpointIdentificationAlgorithm = "HTTPS"
                tls.sslParameters = parameters
                tls.soTimeout = config.networkTimeoutMs
                tls.startHandshake()
                socket = tls
                tls
            } else newSocket

            val rawInput = DataInputStream(BufferedInputStream(transport.getInputStream()))
            val rawOutput = DataOutputStream(BufferedOutputStream(transport.getOutputStream()))
            input = rawInput
            performHandshake(rawInput, rawOutput)

            val chunkWriter = RtmpChunkWriter(rawOutput, chunkSize = 128)
            val chunkReader = RtmpChunkReader(rawInput)
            writer = chunkWriter
            reader = chunkReader

            // Protocol controls precede the connect command. Chunk size applies after this message.
            chunkWriter.write(RtmpMessage(0, TYPE_SET_CHUNK_SIZE, 0, intPayload(OUTBOUND_CHUNK_SIZE), 2))
            chunkWriter.chunkSize = OUTBOUND_CHUNK_SIZE
            chunkWriter.write(RtmpMessage(0, TYPE_WINDOW_ACK_SIZE, 0, intPayload(2_500_000), 2))
            sendCommand(
                streamId = 0,
                name = "connect",
                transactionId = 1.0,
                commandObject = mapOf(
                    "app" to endpoint.app,
                    "type" to "nonprivate",
                    "flashVer" to "FMLE/3.0 (compatible; MobileStreamingStudio/1.0)",
                    "tcUrl" to endpoint.tcUrl,
                    "audioCodecs" to 1024.0, // AAC
                    "videoCodecs" to 128.0, // H.264
                    "videoFunction" to 1.0,
                ),
            )
            awaitResult(1.0)
            sendCommand(0, "createStream", 2.0, null)
            val createResult = awaitResult(2.0)
            val streamId = (createResult.getOrNull(1) as? Number)?.toInt()
                ?: throw IOException("RTMP createStream returned no stream ID")
            sendCommand(streamId, "publish", 3.0, null, endpoint.streamKey, "live")
            awaitPublishStart()
            if (closed) throw IOException("RTMP connection closed during publish")

            connected = true
            transport.soTimeout = 0
            writerThread = Thread(::writeLoop, "rtmp-writer").apply { isDaemon = true; start() }
            readerThread = Thread(::readLoop, "rtmp-reader").apply { isDaemon = true; start() }
            sendMetadata(config)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun sendAudioConfig(audioSpecificConfig: ByteArray, timestampUs: Long) {
        enqueue(8, FlvPacketizer.audioConfig(audioSpecificConfig), timestampUs, chunkStreamId = 4)
    }

    fun sendAudio(rawAac: ByteArray, timestampUs: Long) {
        enqueue(8, FlvPacketizer.audioFrame(rawAac), timestampUs, chunkStreamId = 4)
    }

    fun sendVideoConfig(avcConfiguration: ByteArray, timestampUs: Long) {
        enqueue(9, FlvPacketizer.videoConfig(avcConfiguration), timestampUs, chunkStreamId = 6)
    }

    fun sendVideo(avcSample: ByteArray, timestampUs: Long, keyFrame: Boolean) {
        enqueue(9, FlvPacketizer.videoFrame(avcSample, keyFrame), timestampUs, chunkStreamId = 6)
    }

    private fun sendMetadata(config: StreamConfig) {
        val payload = Amf0.encode(
            "@setDataFrame", "onMetaData",
            mapOf(
                "width" to config.width,
                "height" to config.height,
                "framerate" to config.frameRate,
                "videodatarate" to config.videoBitrateBps / 1000.0,
                "audiodatarate" to config.audioBitrateBps / 1000.0,
                "audiosamplerate" to config.audioSampleRate,
                "audiochannels" to config.audioChannels,
                "videocodecid" to 7.0,
                "audiocodecid" to 10.0,
                "encoder" to "Mobile Streaming Studio",
            ),
        )
        enqueueMessage(RtmpMessage(0, 18, 1, payload, 5))
    }

    private fun enqueue(typeId: Int, payload: ByteArray, timestampUs: Long, chunkStreamId: Int) {
        if (!isConnected) return
        val origin = originTimestampUs.updateAndGet { current ->
            if (current == Long.MIN_VALUE) timestampUs else minOf(current, timestampUs)
        }
        val timestampMs = ((timestampUs - origin).coerceAtLeast(0) / 1_000)
            .coerceAtMost(0xFFFFFFFFL)
        enqueueMessage(RtmpMessage(timestampMs, typeId, 1, payload, chunkStreamId))
    }

    private fun enqueueMessage(message: RtmpMessage) {
        if (!isConnected && message.typeId != 18) return
        if (!queue.offer(message)) listener.onPacketDropped(message.typeId)
    }

    private fun writeLoop() {
        try {
            while (!closed) {
                val message = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (!connected) continue
                writer?.write(message)
                listener.onPacketSent(message.typeId, message.payload.size)
            }
        } catch (error: Throwable) {
            if (!closed) fail(error)
        }
    }

    private fun readLoop() {
        try {
            while (!closed) {
                val message = reader?.readMessage() ?: throw EOFException("RTMP reader unavailable")
                when (message.typeId) {
                    TYPE_SET_CHUNK_SIZE -> if (message.payload.size >= 4) {
                        reader?.chunkSize = message.payload.readInt().also {
                            if (it <= 0 || it > 0x7FFFFF) throw IOException("Invalid server RTMP chunk size")
                        }
                    }
                    TYPE_USER_CONTROL -> handleUserControl(message)
                    TYPE_COMMAND_AMF0, TYPE_COMMAND_AMF3 -> handleServerCommand(message)
                }
            }
        } catch (error: Throwable) {
            if (!closed) fail(error)
        }
    }

    private fun performHandshake(input: DataInputStream, output: DataOutputStream) {
        val c1 = ByteArray(HANDSHAKE_SIZE)
        SecureRandom().nextBytes(c1)
        val now = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()
        c1[0] = (now ushr 24).toByte()
        c1[1] = (now ushr 16).toByte()
        c1[2] = (now ushr 8).toByte()
        c1[3] = now.toByte()
        c1[4] = 0; c1[5] = 0; c1[6] = 0; c1[7] = 0
        output.writeByte(RTMP_VERSION)
        output.write(c1)
        output.flush()
        val version = input.readUnsignedByte()
        if (version != RTMP_VERSION) throw IOException("Unsupported RTMP handshake version $version")
        val s1 = ByteArray(HANDSHAKE_SIZE)
        input.readFully(s1)
        output.write(s1) // C2 echoes S1 time and random bytes.
        output.flush()
        val s2 = ByteArray(HANDSHAKE_SIZE)
        input.readFully(s2)
        for (index in 8 until HANDSHAKE_SIZE) {
            if (s2[index] != c1[index]) throw IOException("RTMP handshake echo validation failed")
        }
    }

    private fun sendCommand(
        streamId: Int,
        name: String,
        transactionId: Double,
        commandObject: Any?,
        vararg arguments: Any?,
    ) {
        val payload = Amf0.encode(name, transactionId, commandObject, *arguments)
        writer?.write(RtmpMessage(0, TYPE_COMMAND_AMF0, streamId, payload, 3))
            ?: throw IOException("RTMP writer unavailable")
    }

    private fun awaitResult(transactionId: Double): List<Any?> {
        while (true) {
            val message = readDuringConnect()
            if (message.typeId != TYPE_COMMAND_AMF0 && message.typeId != TYPE_COMMAND_AMF3) continue
            val values = Amf0.decodeAll(message.payload)
            val command = values.firstOrNull() as? String
            if (command == "_error") throw IOException(commandError(values))
            if (command == "_result" && (values.getOrNull(1) as? Number)?.toDouble() == transactionId) return values
        }
    }

    private fun awaitPublishStart() {
        while (true) {
            val message = readDuringConnect()
            if (message.typeId != TYPE_COMMAND_AMF0 && message.typeId != TYPE_COMMAND_AMF3) continue
            val values = Amf0.decodeAll(message.payload)
            when (values.firstOrNull() as? String) {
                "_error" -> throw IOException(commandError(values))
                "onStatus" -> {
                    val info = values.firstNotNullOfOrNull { it as? Map<*, *> }
                    val code = info?.get("code") as? String
                    if (code == "NetStream.Publish.Start") return
                    if (code?.contains("Failed", ignoreCase = true) == true ||
                        code?.contains("Rejected", ignoreCase = true) == true ||
                        info?.get("level") == "error"
                    ) throw IOException("RTMP publish rejected: ${info?.get("description") ?: code ?: "unknown error"}")
                }
            }
        }
    }

    private fun readDuringConnect(): RtmpMessage {
        val message = reader?.readMessage() ?: throw EOFException("RTMP connection closed")
        when (message.typeId) {
            TYPE_SET_CHUNK_SIZE -> if (message.payload.size >= 4) {
                val size = message.payload.readInt()
                if (size <= 0 || size > 0x7FFFFF) throw IOException("Invalid server RTMP chunk size")
                reader?.chunkSize = size
            }
            TYPE_USER_CONTROL -> handleUserControl(message)
        }
        return message
    }

    private fun handleServerCommand(message: RtmpMessage) {
        val values = Amf0.decodeAll(message.payload)
        val command = values.firstOrNull() as? String
        if (command == "_error" || command == "close") throw IOException(commandError(values))
        if (command == "onStatus") {
            val info = values.firstNotNullOfOrNull { it as? Map<*, *> }
            if (info?.get("level") == "error") {
                throw IOException("RTMP server error: ${info["description"] ?: info["code"] ?: "unknown"}")
            }
        }
    }

    private fun handleUserControl(message: RtmpMessage) {
        if (message.payload.size < 2) return
        val event = (message.payload[0].toInt() and 0xFF shl 8) or (message.payload[1].toInt() and 0xFF)
        if (event == PING_REQUEST && message.payload.size >= 6) {
            val response = ByteArray(6)
            response[0] = 0; response[1] = PING_RESPONSE.toByte()
            System.arraycopy(message.payload, 2, response, 2, 4)
            writer?.write(RtmpMessage(0, TYPE_USER_CONTROL, 0, response, 2))
        }
    }

    private fun commandError(values: List<Any?>): String {
        val info = values.firstNotNullOfOrNull { it as? Map<*, *> }
        return "RTMP command failed: ${info?.get("description") ?: info?.get("code") ?: "unknown error"}"
    }

    private fun fail(cause: Throwable) {
        connected = false
        closeTransport()
        if (terminalNotified.compareAndSet(false, true)) listener.onDisconnected(cause)
    }

    override fun close() {
        if (closed) return
        closed = true
        connected = false
        queue.clear()
        closeTransport()
        writerThread?.interrupt()
        readerThread?.interrupt()
    }

    private fun closeTransport() {
        runCatching { socket?.close() }
    }

    private fun intPayload(value: Int) = byteArrayOf(
        (value ushr 24).toByte(), (value ushr 16).toByte(),
        (value ushr 8).toByte(), value.toByte(),
    )

    private fun ByteArray.readInt(): Int =
        (this[0].toInt() and 0xFF shl 24) or (this[1].toInt() and 0xFF shl 16) or
            (this[2].toInt() and 0xFF shl 8) or (this[3].toInt() and 0xFF)

    companion object {
        private const val RTMP_VERSION = 3
        private const val HANDSHAKE_SIZE = 1536
        private const val OUTBOUND_CHUNK_SIZE = 4096
        private const val TYPE_SET_CHUNK_SIZE = 1
        private const val TYPE_WINDOW_ACK_SIZE = 5
        private const val TYPE_USER_CONTROL = 4
        private const val TYPE_COMMAND_AMF0 = 20
        private const val TYPE_COMMAND_AMF3 = 17
        private const val PING_REQUEST = 6
        private const val PING_RESPONSE = 7
    }
}
