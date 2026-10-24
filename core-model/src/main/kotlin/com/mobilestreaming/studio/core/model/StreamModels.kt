package com.mobilestreaming.studio.core.model

import java.net.URI
import java.util.Locale

enum class StreamPhase { OFFLINE, CONNECTING, LIVE, RECONNECTING, STOPPING, ERROR }

enum class EncoderProfile { BASELINE, MAIN, HIGH }

enum class QualityPreset { LOW, BALANCED, HIGH, CUSTOM }

enum class CanvasAspect(val ratio: Float) {
    LANDSCAPE_16_9(16f / 9f),
    PORTRAIT_9_16(9f / 16f),
    SQUARE_1_1(1f),
}

data class StreamStatistics(
    val durationMs: Long = 0,
    val bitrateBps: Long = 0,
    val videoBitrateBps: Long = 0,
    val audioBitrateBps: Long = 0,
    val renderedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val sentAudioPackets: Long = 0,
    val sentVideoPackets: Long = 0,
    val networkAvailable: Boolean = true,
    val thermalStatus: Int = 0,
)

data class StreamState(
    val phase: StreamPhase = StreamPhase.OFFLINE,
    val statistics: StreamStatistics = StreamStatistics(),
    val errorMessage: String? = null,
    val reconnectAttempt: Int = 0,
) {
    val isSessionActive: Boolean
        get() = phase == StreamPhase.CONNECTING || phase == StreamPhase.LIVE ||
            phase == StreamPhase.RECONNECTING || phase == StreamPhase.STOPPING
}

data class RecordingState(
    val isActive: Boolean = false,
    val isFinalizing: Boolean = false,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val displayName: String? = null,
    val contentUri: String? = null,
    val errorMessage: String? = null,
)

data class AudioMixSettings(
    val microphoneVolume: Float = 1f,
    val microphoneMuted: Boolean = false,
    val noiseSuppression: Boolean = true,
    val automaticGainControl: Boolean = false,
    val monitoringEnabled: Boolean = false,
) {
    init {
        require(microphoneVolume in 0f..2f) { "Microphone volume must be between 0 and 2" }
    }
}

data class StreamConfig(
    val serverUrl: String = "rtmp://192.168.1.100/live",
    val streamKey: String = "",
    val width: Int = 1280,
    val height: Int = 720,
    val frameRate: Int = 30,
    val videoBitrateBps: Int = 2_500_000,
    val audioBitrateBps: Int = 128_000,
    val audioSampleRate: Int = 44_100,
    val audioChannels: Int = 1,
    val keyframeIntervalSeconds: Int = 2,
    val profile: EncoderProfile = EncoderProfile.HIGH,
    val networkTimeoutMs: Int = 10_000,
    val reconnectAttempts: Int = 3,
    val reconnectDelayMs: Long = 2_000,
    val adaptiveBitrate: Boolean = false,
    val minimumVideoBitrateBps: Int = 500_000,
    val qualityPreset: QualityPreset = QualityPreset.BALANCED,
) {
    val aspect: CanvasAspect
        get() = when {
            width == height -> CanvasAspect.SQUARE_1_1
            height > width -> CanvasAspect.PORTRAIT_9_16
            else -> CanvasAspect.LANDSCAPE_16_9
        }

    fun validated(requireStreamKey: Boolean = true): StreamConfig {
        validateEndpoint(serverUrl)
        require(!requireStreamKey || streamKey.isNotBlank()) { "A stream key is required" }
        require(streamKey.none { it.isISOControl() }) { "The stream key contains control characters" }
        require(width in 256..3840 && height in 256..3840) { "Resolution must be between 256p and 3840p per axis" }
        require(width % 2 == 0 && height % 2 == 0) { "Video dimensions must be even" }
        require(frameRate in 1..60) { "Frame rate must be between 1 and 60 FPS" }
        require(videoBitrateBps in 100_000..30_000_000) { "Video bitrate must be 0.1–30 Mbps" }
        require(audioBitrateBps in 32_000..320_000) { "Audio bitrate must be 32–320 kbps" }
        require(audioSampleRate in listOf(44_100, 48_000)) { "Use 44.1 or 48 kHz audio" }
        require(audioChannels in 1..2) { "Audio must be mono or stereo" }
        require(keyframeIntervalSeconds in 1..10) { "Keyframe interval must be 1–10 seconds" }
        require(networkTimeoutMs in 2_000..60_000) { "Network timeout must be 2–60 seconds" }
        require(reconnectAttempts in 0..10) { "Reconnect attempts must be 0–10" }
        require(reconnectDelayMs in 500..30_000) { "Reconnect delay must be 0.5–30 seconds" }
        require(minimumVideoBitrateBps in 100_000..videoBitrateBps) {
            "Adaptive minimum bitrate must not exceed the configured bitrate"
        }
        return copy(serverUrl = serverUrl.trim().trimEnd('/'), streamKey = streamKey.trim())
    }

    companion object {
        fun forPreset(preset: QualityPreset, current: StreamConfig): StreamConfig = when (preset) {
            QualityPreset.LOW -> current.copy(
                width = 854, height = 480, frameRate = 30,
                videoBitrateBps = 1_200_000, minimumVideoBitrateBps = 400_000,
                qualityPreset = preset,
            )
            QualityPreset.BALANCED -> current.copy(
                width = 1280, height = 720, frameRate = 30,
                videoBitrateBps = 2_500_000, minimumVideoBitrateBps = 500_000,
                qualityPreset = preset,
            )
            QualityPreset.HIGH -> current.copy(
                width = 1920, height = 1080, frameRate = 30,
                videoBitrateBps = 4_500_000, minimumVideoBitrateBps = 800_000,
                qualityPreset = preset,
            )
            QualityPreset.CUSTOM -> current.copy(qualityPreset = preset)
        }

        fun validateEndpoint(rawUrl: String) {
            require(rawUrl.isNotBlank()) { "An RTMP server URL is required" }
            require(!rawUrl.any { it.isWhitespace() || it.isISOControl() }) { "The server URL contains invalid characters" }
            val uri = runCatching { URI(rawUrl.trim()) }.getOrElse { throw IllegalArgumentException("Invalid server URL") }
            val scheme = uri.scheme?.lowercase(Locale.US)
            require(scheme == "rtmp" || scheme == "rtmps") { "Only rtmp:// and rtmps:// endpoints are supported" }
            require(!uri.host.isNullOrBlank()) { "The server URL must include a valid host" }
            require(uri.userInfo == null) { "Put credentials in the stream key, not in the URL" }
            require(uri.fragment == null) { "URL fragments are not valid for RTMP" }
            require(uri.port == -1 || uri.port in 1..65535) { "Invalid server port" }
            val path = uri.path.orEmpty().trim('/')
            require(path.isNotEmpty()) { "The server URL must include an application path, for example /live" }
        }
    }
}

data class RecordingEntry(
    val id: String,
    val displayName: String,
    val contentUri: String,
    val createdAtEpochMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
)

data class StreamInformation(
    val title: String = "",
    val description: String = "",
    val category: String = "",
    val destinationName: String = "Generic RTMP",
)

data class AppSettings(
    val darkTheme: Boolean = true,
    val followSystemTheme: Boolean = true,
    val defaultSceneId: String? = null,
    val defaultOverlayPreset: String = "None",
    val audio: AudioMixSettings = AudioMixSettings(),
    val recordingBitrateBps: Int? = null,
    val mirrorFrontCamera: Boolean = true,
    val adaptiveBitrate: Boolean = false,
)
