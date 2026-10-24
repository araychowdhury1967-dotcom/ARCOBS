package com.mobilestreaming.studio.streaming.rtmp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.charset.StandardCharsets

/** A bounded AMF0 codec for the subset used by RTMP command messages. */
internal object Amf0 {
    private const val MAX_DEPTH = 16
    private const val MAX_ITEMS = 4_096
    private const val MAX_STRING_BYTES = 1_048_576

    fun encode(vararg values: Any?): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output -> values.forEach { write(output, it, 0) } }
        return bytes.toByteArray()
    }

    fun decodeAll(payload: ByteArray): List<Any?> {
        val input = DataInputStream(ByteArrayInputStream(payload))
        // AMF0 over RTMP type 17 prefixes the AMF0 values with a one-byte AMF3 marker.
        if (payload.isNotEmpty() && payload[0].toInt() == 0) input.readByte()
        val result = mutableListOf<Any?>()
        while (input.available() > 0 && result.size < MAX_ITEMS) result += read(input, 0)
        return result
    }

    private fun write(output: DataOutputStream, value: Any?, depth: Int) {
        check(depth <= MAX_DEPTH) { "AMF0 nesting limit exceeded" }
        when (value) {
            null -> output.writeByte(5)
            is Number -> {
                output.writeByte(0)
                output.writeDouble(value.toDouble())
            }
            is Boolean -> {
                output.writeByte(1)
                output.writeByte(if (value) 1 else 0)
            }
            is String -> {
                val encoded = value.toByteArray(StandardCharsets.UTF_8)
                if (encoded.size <= 0xFFFF) {
                    output.writeByte(2)
                    output.writeShort(encoded.size)
                } else {
                    require(encoded.size <= MAX_STRING_BYTES) { "AMF0 string is too large" }
                    output.writeByte(12)
                    output.writeInt(encoded.size)
                }
                output.write(encoded)
            }
            is Map<*, *> -> {
                output.writeByte(3)
                writeProperties(output, value, depth)
            }
            is List<*> -> {
                require(value.size <= MAX_ITEMS) { "AMF0 array is too large" }
                output.writeByte(10)
                output.writeInt(value.size)
                value.forEach { write(output, it, depth + 1) }
            }
            else -> throw IllegalArgumentException("Unsupported AMF0 value: ${value::class.java.simpleName}")
        }
    }

    private fun writeProperties(output: DataOutputStream, map: Map<*, *>, depth: Int) {
        require(map.size <= MAX_ITEMS) { "AMF0 object is too large" }
        map.forEach { (rawKey, value) ->
            val key = rawKey as? String ?: throw IllegalArgumentException("AMF0 object keys must be strings")
            val encoded = key.toByteArray(StandardCharsets.UTF_8)
            require(encoded.size <= 0xFFFF) { "AMF0 property name is too long" }
            output.writeShort(encoded.size)
            output.write(encoded)
            write(output, value, depth + 1)
        }
        output.writeShort(0)
        output.writeByte(9)
    }

    private fun read(input: DataInputStream, depth: Int): Any? {
        check(depth <= MAX_DEPTH) { "AMF0 nesting limit exceeded" }
        return when (val marker = input.readUnsignedByte()) {
            0 -> input.readDouble()
            1 -> input.readUnsignedByte() != 0
            2 -> readString(input, input.readUnsignedShort())
            3 -> readObject(input, depth)
            5, 6 -> null
            7 -> input.readUnsignedShort() // ECMA object reference; preserve only its ID.
            8 -> {
                input.readInt() // ECMA array count is advisory.
                readObject(input, depth)
            }
            10 -> {
                val count = input.readInt()
                require(count in 0..MAX_ITEMS) { "Invalid AMF0 array size" }
                List(count) { read(input, depth + 1) }
            }
            11 -> {
                val milliseconds = input.readDouble()
                input.readShort()
                milliseconds
            }
            12 -> {
                val length = input.readInt()
                require(length in 0..MAX_STRING_BYTES) { "Invalid AMF0 long string size" }
                readString(input, length)
            }
            15, 18 -> { // XML document / typed object: retain a bounded string representation where possible.
                val length = input.readInt()
                require(length in 0..MAX_STRING_BYTES) { "Invalid AMF0 document size" }
                readString(input, length)
            }
            else -> throw IllegalArgumentException("Unsupported AMF0 marker $marker")
        }
    }

    private fun readObject(input: DataInputStream, depth: Int): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        var items = 0
        while (true) {
            if (input.available() < 2) throw EOFException("Unterminated AMF0 object")
            val keyLength = input.readUnsignedShort()
            if (keyLength == 0) {
                if (input.readUnsignedByte() == 9) break
                throw IllegalArgumentException("Invalid AMF0 empty property")
            }
            require(++items <= MAX_ITEMS) { "AMF0 object item limit exceeded" }
            val key = readString(input, keyLength)
            result[key] = read(input, depth + 1)
        }
        return result
    }

    private fun readString(input: DataInputStream, length: Int): String {
        require(length in 0..MAX_STRING_BYTES) { "Invalid AMF0 string size" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
