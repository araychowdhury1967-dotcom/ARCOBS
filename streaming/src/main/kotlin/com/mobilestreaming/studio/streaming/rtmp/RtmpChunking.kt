package com.mobilestreaming.studio.streaming.rtmp

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.OutputStream

internal data class RtmpMessage(
    val timestamp: Long,
    val typeId: Int,
    val streamId: Int,
    val payload: ByteArray,
    val chunkStreamId: Int,
)

internal class RtmpChunkWriter(
    output: OutputStream,
    @Volatile var chunkSize: Int = 128,
) {
    private val output = DataOutputStream(output)

    init { require(chunkSize in 1..0x7FFFFF) { "Invalid outbound RTMP chunk size" } }

    @Synchronized
    fun write(message: RtmpMessage) {
        require(message.payload.size <= 0xFFFFFF) { "RTMP message exceeds the 24-bit size field" }
        require(message.timestamp in 0..0xFFFFFFFFL) { "Invalid RTMP timestamp" }
        require(message.chunkStreamId in 2..65535) { "Invalid RTMP chunk stream" }
        val extended = message.timestamp >= 0xFFFFFF
        val timestampField = if (extended) 0xFFFFFF else message.timestamp.toInt()
        writeBasicHeader(0, message.chunkStreamId)
        output.writeThreeBytes(timestampField)
        output.writeThreeBytes(message.payload.size)
        output.writeByte(message.typeId)
        output.writeIntLittleEndian(message.streamId)
        if (extended) output.writeInt(message.timestamp.toInt())
        var offset = 0
        while (offset < message.payload.size) {
            val count = minOf(chunkSize, message.payload.size - offset)
            output.write(message.payload, offset, count)
            offset += count
            if (offset < message.payload.size) {
                writeBasicHeader(3, message.chunkStreamId)
                if (extended) output.writeInt(message.timestamp.toInt())
            }
        }
        output.flush()
    }

    private fun writeBasicHeader(format: Int, chunkStreamId: Int) {
        require(format in 0..3)
        when (chunkStreamId) {
            in 2..63 -> output.writeByte((format shl 6) or chunkStreamId)
            in 64..319 -> {
                output.writeByte(format shl 6)
                output.writeByte(chunkStreamId - 64)
            }
            else -> {
                output.writeByte((format shl 6) or 1)
                val encoded = chunkStreamId - 64
                output.writeByte(encoded and 0xFF)
                output.writeByte((encoded ushr 8) and 0xFF)
            }
        }
    }
}

internal class RtmpChunkReader(
    private val input: DataInputStream,
    initialChunkSize: Int = 128,
) {
    @Volatile var chunkSize: Int = initialChunkSize
        set(value) {
            require(value in 1..0x7FFFFF) { "Invalid inbound RTMP chunk size" }
            field = value
        }

    private val chunks = HashMap<Int, Chunk>()

    fun readMessage(): RtmpMessage {
        while (true) {
            val first = input.readUnsignedByte()
            val format = first ushr 6
            var chunkStreamId = first and 0x3F
            if (chunkStreamId == 0) {
                chunkStreamId = input.readUnsignedByte() + 64
            } else if (chunkStreamId == 1) {
                val low = input.readUnsignedByte()
                val high = input.readUnsignedByte()
                chunkStreamId = low + high * 256 + 64
            }

            var chunk = chunks[chunkStreamId]
            val continuing = format == 3 && chunk?.buffer != null &&
                chunk.buffer.size() < chunk.messageLength
            if (format != 3 || !continuing) {
                chunk = readNewHeader(format, chunkStreamId, chunk)
                chunks[chunkStreamId] = chunk
            } else if (chunk?.extendedTimestamp == true) {
                // A fmt3 continuation repeats the extended timestamp of the original header.
                input.readInt()
            }

            val current = chunk ?: throw EOFException("RTMP chunk header is missing")
            val remaining = current.messageLength - current.buffer.size()
            if (remaining <= 0) throw IllegalArgumentException("Invalid RTMP chunk state")
            val count = minOf(chunkSize, remaining)
            val bytes = ByteArray(count)
            input.readFully(bytes)
            current.buffer.write(bytes)
            if (current.buffer.size() == current.messageLength) {
                return RtmpMessage(
                    timestamp = current.timestamp,
                    typeId = current.typeId,
                    streamId = current.streamId,
                    payload = current.buffer.toByteArray(),
                    chunkStreamId = chunkStreamId,
                )
            }
        }
    }

    private fun readNewHeader(format: Int, chunkStreamId: Int, previous: Chunk?): Chunk {
        if (format != 0 && previous == null) throw EOFException("RTMP compressed header has no history")
        val timestampField: Int
        val messageLength: Int
        val typeId: Int
        val streamId: Int
        val timestamp: Long
        val delta: Long
        when (format) {
            0 -> {
                timestampField = input.readThreeBytes()
                messageLength = input.readThreeBytes()
                typeId = input.readUnsignedByte()
                streamId = input.readIntLittleEndian()
                timestamp = timestampField.toLong()
                delta = 0
            }
            1 -> {
                timestampField = input.readThreeBytes()
                messageLength = input.readThreeBytes()
                typeId = input.readUnsignedByte()
                streamId = previous!!.streamId
                delta = timestampField.toLong()
                timestamp = previous.timestamp + delta
            }
            2 -> {
                timestampField = input.readThreeBytes()
                messageLength = previous!!.messageLength
                typeId = previous.typeId
                streamId = previous.streamId
                delta = timestampField.toLong()
                timestamp = previous.timestamp + delta
            }
            3 -> {
                val prior = previous!!
                timestampField = if (prior.extendedTimestamp) 0xFFFFFF else 0
                messageLength = prior.messageLength
                typeId = prior.typeId
                streamId = prior.streamId
                delta = prior.timestampDelta
                timestamp = prior.timestamp + delta
            }
            else -> throw IllegalArgumentException("Invalid RTMP chunk format $format")
        }
        require(messageLength in 0..MAX_MESSAGE_SIZE) { "RTMP message is too large" }
        val extended = timestampField == 0xFFFFFF
        val actual = if (extended) input.readUnsignedInt() else timestamp
        return Chunk(
            timestamp = actual,
            timestampDelta = if (format == 2 || format == 1) actual - previous!!.timestamp else delta,
            messageLength = messageLength,
            typeId = typeId,
            streamId = streamId,
            extendedTimestamp = extended,
        ).also {
            if (chunkStreamId !in 2..65535) throw IllegalArgumentException("Invalid RTMP chunk stream")
        }
    }

    private data class Chunk(
        val timestamp: Long,
        val timestampDelta: Long,
        val messageLength: Int,
        val typeId: Int,
        val streamId: Int,
        val extendedTimestamp: Boolean,
        val buffer: ByteArrayOutputStream = ByteArrayOutputStream(),
    )

    companion object { private const val MAX_MESSAGE_SIZE = 8 * 1024 * 1024 }
}

private fun DataOutputStream.writeThreeBytes(value: Int) {
    writeByte((value ushr 16) and 0xFF)
    writeByte((value ushr 8) and 0xFF)
    writeByte(value and 0xFF)
}

private fun DataInputStream.readThreeBytes(): Int =
    (readUnsignedByte() shl 16) or (readUnsignedByte() shl 8) or readUnsignedByte()

private fun DataOutputStream.writeIntLittleEndian(value: Int) {
    writeByte(value and 0xFF)
    writeByte((value ushr 8) and 0xFF)
    writeByte((value ushr 16) and 0xFF)
    writeByte((value ushr 24) and 0xFF)
}

private fun DataInputStream.readIntLittleEndian(): Int =
    readUnsignedByte() or (readUnsignedByte() shl 8) or
        (readUnsignedByte() shl 16) or (readUnsignedByte() shl 24)

private fun DataInputStream.readUnsignedInt(): Long =
    (readUnsignedByte().toLong() shl 24) or (readUnsignedByte().toLong() shl 16) or
        (readUnsignedByte().toLong() shl 8) or readUnsignedByte().toLong()
