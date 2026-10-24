package com.mobilestreaming.studio.streaming.rtmp

import com.mobilestreaming.studio.core.model.StreamConfig
import java.net.URI

internal data class RtmpEndpoint(
    val secure: Boolean,
    val host: String,
    val port: Int,
    val app: String,
    val streamKey: String,
    val tcUrl: String,
) {
    val diagnosticUrl: String
        get() = "${if (secure) "rtmps" else "rtmp"}://$host:$port/${app.substringBefore('?')}" +
            if (tcUrl.contains('?')) "?<redacted>" else ""

    companion object {
        fun from(config: StreamConfig): RtmpEndpoint {
            val validated = config.validated()
            val uri = URI(validated.serverUrl)
            val secure = uri.scheme.equals("rtmps", ignoreCase = true)
            val port = if (uri.port >= 0) uri.port else if (secure) 443 else 1935
            val appPath = uri.rawPath.trim('/')
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            val authority = buildString {
                append(uri.host)
                if ((secure && port != 443) || (!secure && port != 1935)) append(":$port")
            }
            return RtmpEndpoint(
                secure = secure,
                host = uri.host,
                port = port,
                app = appPath + query,
                streamKey = validated.streamKey,
                tcUrl = "${uri.scheme}://$authority/$appPath$query",
            )
        }
    }
}
