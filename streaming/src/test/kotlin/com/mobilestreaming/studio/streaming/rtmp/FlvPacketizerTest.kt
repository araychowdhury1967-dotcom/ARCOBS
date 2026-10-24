package com.mobilestreaming.studio.streaming.rtmp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FlvPacketizerTest {
    private val sps = byteArrayOf(0x67, 0x64, 0, 0x1F)
    private val pps = byteArrayOf(0x68, 0x11, 0x22)
    private val idr = byteArrayOf(0x65, 1, 2, 3, 4)

    @Test fun annexBParameterSetsCreateAvcConfiguration() {
        val csd = startCode(sps) + startCode(pps)
        val avcc = AvcDecoderConfiguration.fromCsd(csd, null)
        assertEquals(1, avcc[0].toInt())
        assertEquals(0x64, avcc[1].toInt())
        assertEquals(3, avcc[4].toInt() and 0x03)
        assertEquals(1, avcc[5].toInt() and 0x1F)
    }

    @Test fun annexBFrameBecomesLengthPrefixedFlvTag() {
        val tag = FlvPacketizer.videoFrame(startCode(sps) + startCode(idr), keyFrame = true)
        assertEquals(0x17, tag[0].toInt())
        assertEquals(1, tag[1].toInt())
        assertEquals(idr.size, tag[8].toInt())
        assertArrayEquals(idr, tag.copyOfRange(9, tag.size))
    }

    private fun startCode(data: ByteArray) = byteArrayOf(0, 0, 0, 1) + data
}
