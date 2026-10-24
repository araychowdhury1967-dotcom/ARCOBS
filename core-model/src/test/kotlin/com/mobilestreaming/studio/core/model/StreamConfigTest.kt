package com.mobilestreaming.studio.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamConfigTest {
    @Test fun validRtmpsConfigurationIsNormalized() {
        val config = StreamConfig(serverUrl = "rtmps://live.example.com/live/", streamKey = " secret ").validated()
        assertEquals("rtmps://live.example.com/live", config.serverUrl)
        assertEquals("secret", config.streamKey)
    }

    @Test fun insecureOtherProtocolsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamConfig(serverUrl = "http://example.com/live", streamKey = "key").validated()
        }
    }

    @Test fun embeddedCredentialsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamConfig(serverUrl = "rtmp://user:pass@example.com/live", streamKey = "key").validated()
        }
    }

    @Test fun invalidDimensionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            StreamConfig(serverUrl = "rtmp://example.com/live", streamKey = "key", width = 1281).validated()
        }
    }
}
