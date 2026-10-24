package com.mobilestreaming.studio.streaming.rtmp

import org.junit.Assert.assertEquals
import org.junit.Test

class Amf0Test {
    @Test fun commandRoundTrip() {
        val command = mapOf<String, Any?>("app" to "live", "flashVer" to "FMLE/3.0", "secure" to true)
        val encoded = Amf0.encode("connect", 1.0, command, null)
        val decoded = Amf0.decodeAll(encoded)
        assertEquals("connect", decoded[0])
        assertEquals(1.0, decoded[1])
        assertEquals("live", (decoded[2] as Map<*, *>)["app"])
        assertEquals(true, (decoded[2] as Map<*, *>)["secure"])
    }

    @Test fun strictArrayRoundTrip() {
        val decoded = Amf0.decodeAll(Amf0.encode(listOf("one", 2.0, false)))
        assertEquals(listOf("one", 2.0, false), decoded.single())
    }
}
