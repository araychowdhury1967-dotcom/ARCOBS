package com.mobilestreaming.studio.streaming.rtmp

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** FLV video/audio tag payloads carried by RTMP messages 9 and 8. */
internal object FlvPacketizer {
    fun audioConfig(audioSpecificConfig: ByteArray): ByteArray =
        byteArrayOf(0xAF.toByte(), 0) + audioSpecificConfig

    fun audioFrame(rawAac: ByteArray): ByteArray = byteArrayOf(0xAF.toByte(), 1) + rawAac

    fun videoConfig(avcDecoderConfigurationRecord: ByteArray): ByteArray =
        byteArrayOf(0x17, 0, 0, 0, 0) + avcDecoderConfigurationRecord

    fun videoFrame(avcSample: ByteArray, keyFrame: Boolean): ByteArray {
        val lengthPrefixed = AvcNalUnits.toLengthPrefixed(avcSample)
        return byteArrayOf(
            ((if (keyFrame) 1 else 2) shl 4 or 7).toByte(),
            1, 0, 0, 0, // AVC NALU packet, zero composition time (Android encoders do not emit B frames here).
        ) + lengthPrefixed
    }
}

internal object AvcDecoderConfiguration {
    fun fromCsd(csd0: ByteArray, csd1: ByteArray?): ByteArray {
        if (csd0.isNotEmpty() && csd0[0].toInt() == 1 && csd0.size >= 11) {
            // Some devices expose AVCDecoderConfigurationRecord directly.
            return csd0
        }
        val nalUnits = AvcNalUnits.splitAnnexB(csd0) +
            (csd1?.takeIf { it.isNotEmpty() }?.let(AvcNalUnits::splitAnnexB).orEmpty())
        val sps = nalUnits.firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1F == 7 }
            ?: throw IllegalArgumentException("H.264 codec configuration has no SPS")
        val pps = nalUnits.firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1F == 8 }
            ?: throw IllegalArgumentException("H.264 codec configuration has no PPS")
        require(sps.size >= 4) { "Invalid H.264 SPS" }
        require(sps.size <= 0xFFFF && pps.size <= 0xFFFF) { "H.264 parameter set is too large" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeByte(1)
            output.writeByte(sps[1].toInt())
            output.writeByte(sps[2].toInt())
            output.writeByte(sps[3].toInt())
            output.writeByte(0xFF) // Four-byte NALU lengths.
            output.writeByte(0xE1)
            output.writeShort(sps.size)
            output.write(sps)
            output.writeByte(1)
            output.writeShort(pps.size)
            output.write(pps)
        }
        return bytes.toByteArray()
    }
}

internal object AvcNalUnits {
    fun splitAnnexB(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return emptyList()
        val starts = mutableListOf<Pair<Int, Int>>() // start-code offset to NALU offset
        var index = 0
        while (index + 3 <= data.size) {
            val three = index + 3 <= data.size && data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() && data[index + 2] == 1.toByte()
            val four = index + 4 <= data.size && data[index] == 0.toByte() &&
                data[index + 1] == 0.toByte() && data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte()
            when {
                four -> { starts += index to index + 4; index += 4 }
                three -> { starts += index to index + 3; index += 3 }
                else -> index++
            }
        }
        if (starts.isEmpty()) return listOf(data)
        return starts.mapIndexed { i, (_, naluOffset) ->
            val end = if (i + 1 < starts.size) starts[i + 1].first else data.size
            data.copyOfRange(naluOffset, end).dropTrailingZeroes()
        }.filter { it.isNotEmpty() }
    }

    fun toLengthPrefixed(sample: ByteArray): ByteArray {
        val annexB = splitAnnexB(sample)
        val hasStartCode = annexB.size != 1 || annexB.firstOrNull()?.contentEquals(sample) == false
        val units = if (hasStartCode) annexB else listOf(sample)
        val output = ByteArrayOutputStream(sample.size + units.size * 4)
        if (!hasStartCode && looksLengthPrefixed(sample)) return sample
        units.forEach { unit ->
            if (unit.isEmpty()) return@forEach
            // Parameter sets belong in the sequence header, not in every FLV frame.
            val naluType = unit[0].toInt() and 0x1F
            if (naluType == 7 || naluType == 8) return@forEach
            require(unit.size <= 0x7FFFFFFF) { "H.264 NALU is too large" }
            output.write((unit.size ushr 24) and 0xFF)
            output.write((unit.size ushr 16) and 0xFF)
            output.write((unit.size ushr 8) and 0xFF)
            output.write(unit.size and 0xFF)
            output.write(unit)
        }
        return output.toByteArray()
    }

    private fun looksLengthPrefixed(data: ByteArray): Boolean {
        var offset = 0
        var units = 0
        while (offset + 4 <= data.size && units < 1_024) {
            val size = (data[offset].toInt() and 0xFF shl 24) or
                (data[offset + 1].toInt() and 0xFF shl 16) or
                (data[offset + 2].toInt() and 0xFF shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            if (size <= 0 || offset + 4 + size > data.size) return false
            offset += 4 + size
            units++
        }
        return units > 0 && offset == data.size
    }

    private fun ByteArray.dropTrailingZeroes(): ByteArray {
        var end = size
        while (end > 1 && this[end - 1] == 0.toByte()) end--
        return if (end == size) this else copyOf(end)
    }
}
