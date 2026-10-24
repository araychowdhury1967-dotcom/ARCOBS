package com.mobilestreaming.studio.streaming.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class RtmpChunkingTest {
    @Test fun fragmentedMessageRoundTrip() {
        val payload = ByteArray(10_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val writer = RtmpChunkWriter(output, chunkSize = 128)
        writer.write(RtmpMessage(1234, 9, 1, payload, 6))
        val reader = RtmpChunkReader(DataInputStream(ByteArrayInputStream(output.toByteArray())))
        val decoded = reader.readMessage()
        assertEquals(1234, decoded.timestamp)
        assertEquals(9, decoded.typeId)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test fun extendedTimestampRoundTrip() {
        val output = ByteArrayOutputStream()
        RtmpChunkWriter(output, chunkSize = 64).write(
            RtmpMessage(0x1_0000_00L, 8, 1, ByteArray(200) { 7 }, 4),
        )
        val decoded = RtmpChunkReader(
            DataInputStream(ByteArrayInputStream(output.toByteArray())), initialChunkSize = 64,
        ).readMessage()
        assertEquals(0x1_0000_00L, decoded.timestamp)
        assertEquals(200, decoded.payload.size)
    }
}
