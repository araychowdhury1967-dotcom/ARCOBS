package com.mobilestreaming.studio.streaming

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import com.mobilestreaming.studio.core.model.AudioMixSettings
import com.mobilestreaming.studio.core.model.RecordingEntry
import com.mobilestreaming.studio.core.model.StreamConfig
import com.mobilestreaming.studio.streaming.audio.AudioEncoder
import com.mobilestreaming.studio.streaming.audio.AudioLevel
import com.mobilestreaming.studio.streaming.compositor.SceneCompositor
import com.mobilestreaming.studio.streaming.encoding.VideoEncoder
import com.mobilestreaming.studio.streaming.recording.RecordingMuxer
import com.mobilestreaming.studio.streaming.rtmp.AvcDecoderConfiguration
import com.mobilestreaming.studio.streaming.rtmp.RtmpPublisher

internal class MediaCoordinator(
    private val context: Context,
    private val compositor: SceneCompositor,
    private val onAudioLevel: (AudioLevel) -> Unit,
    private val onError: (Throwable) -> Unit,
) : AutoCloseable {
    private val publisherLock = Object()
    private val recordingLock = Object()
    private var config: StreamConfig? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var publisher: RtmpPublisher? = null
    private var recorder: RecordingMuxer? = null
    @Volatile private var videoFormat: MediaFormat? = null
    @Volatile private var audioFormat: MediaFormat? = null
    @Volatile private var avcConfiguration: ByteArray? = null
    @Volatile private var audioConfiguration: ByteArray? = null
    @Volatile private var lastVideoTimestampUs = 0L
    @Volatile private var lastAudioTimestampUs = 0L
    @Volatile private var currentVideoBitrate = 0

    val isRunning: Boolean get() = videoEncoder != null
    val activeConfig: StreamConfig? get() = config
    val recording: RecordingMuxer? get() = synchronized(recordingLock) { recorder }

    private val videoListener = object : VideoEncoder.Listener {
        override fun onFormat(format: MediaFormat) {
            videoFormat = format
            synchronized(recordingLock) { recorder?.onVideoFormat(format) }
            val csd0 = format.getByteBuffer("csd-0")?.toByteArray()
            if (csd0 != null) {
                val csd1 = format.getByteBuffer("csd-1")?.toByteArray()
                runCatching { AvcDecoderConfiguration.fromCsd(csd0, csd1) }.onSuccess { config ->
                    avcConfiguration = config
                    synchronized(publisherLock) { publisher?.sendVideoConfig(config, lastVideoTimestampUs) }
                }
            }
        }

        override fun onFrame(data: ByteArray, timestampUs: Long, flags: Int) {
            lastVideoTimestampUs = timestampUs
            if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                runCatching { AvcDecoderConfiguration.fromCsd(data, null) }.onSuccess { config ->
                    avcConfiguration = config
                    synchronized(publisherLock) { publisher?.sendVideoConfig(config, timestampUs) }
                }
                return
            }
            synchronized(publisherLock) { publisher?.sendVideo(data, timestampUs, flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) }
            synchronized(recordingLock) { recorder?.writeVideo(data, timestampUs, flags) }
        }

        override fun onError(error: Throwable) {
            this@MediaCoordinator.onError(IllegalStateException("H.264 encoder failed", error))
        }
    }

    private val audioListener = object : AudioEncoder.Listener {
        override fun onFormat(format: MediaFormat) {
            audioFormat = format
            synchronized(recordingLock) { recorder?.onAudioFormat(format) }
            format.getByteBuffer("csd-0")?.toByteArray()?.let { csd ->
                audioConfiguration = csd
                synchronized(publisherLock) { publisher?.sendAudioConfig(csd, lastAudioTimestampUs) }
            }
        }

        override fun onFrame(data: ByteArray, timestampUs: Long, flags: Int) {
            lastAudioTimestampUs = timestampUs
            if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                audioConfiguration = data
                synchronized(publisherLock) { publisher?.sendAudioConfig(data, timestampUs) }
                return
            }
            synchronized(publisherLock) { publisher?.sendAudio(data, timestampUs) }
            synchronized(recordingLock) { recorder?.writeAudio(data, timestampUs, flags) }
        }

        override fun onLevel(level: AudioLevel) = onAudioLevel(level)

        override fun onError(error: Throwable) {
            this@MediaCoordinator.onError(IllegalStateException("Audio capture/AAC encoder failed", error))
        }
    }

    fun start(config: StreamConfig, mixSettings: AudioMixSettings) {
        check(!isRunning) { "Encoding session is already active" }
        this.config = config
        currentVideoBitrate = config.videoBitrateBps
        videoFormat = null
        audioFormat = null
        avcConfiguration = null
        audioConfiguration = null
        try {
            val video = VideoEncoder(videoListener)
            videoEncoder = video
            val inputSurface = video.start(config)
            compositor.attachEncoder(inputSurface)
            val audio = AudioEncoder(context, audioListener)
            audioEncoder = audio
            audio.start(config, mixSettings)
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    fun attachPublisher(newPublisher: RtmpPublisher) {
        val videoConfig = avcConfiguration
        val audioConfig = audioConfiguration
        if (videoConfig != null) newPublisher.sendVideoConfig(videoConfig, lastVideoTimestampUs)
        if (audioConfig != null) newPublisher.sendAudioConfig(audioConfig, lastAudioTimestampUs)
        synchronized(publisherLock) { publisher = newPublisher }
        videoEncoder?.requestSyncFrame()
    }

    fun detachPublisherIfAny() {
        synchronized(publisherLock) { publisher = null }
    }

    fun detachPublisher(oldPublisher: RtmpPublisher) {
        synchronized(publisherLock) {
            if (publisher === oldPublisher) publisher = null
        }
    }

    fun startRecording(): RecordingMuxer {
        val activeConfig = config ?: throw IllegalStateException("Encoding is not active")
        synchronized(recordingLock) {
            check(recorder == null) { "A recording is already active" }
            val newRecorder = RecordingMuxer(context, activeConfig)
            newRecorder.start()
            recorder = newRecorder
            videoFormat?.let(newRecorder::onVideoFormat)
            audioFormat?.let(newRecorder::onAudioFormat)
            videoEncoder?.requestSyncFrame()
            return newRecorder
        }
    }

    fun stopRecording(): RecordingEntry {
        val old = synchronized(recordingLock) {
            val current = recorder ?: throw IllegalStateException("No recording is active")
            recorder = null
            current
        }
        return old.finish()
    }

    fun abortRecording() {
        val old = synchronized(recordingLock) {
            val current = recorder
            recorder = null
            current
        }
        old?.abort()
    }

    fun updateMix(settings: AudioMixSettings) = audioEncoder?.updateMix(settings) ?: Unit

    fun changeBitrate(factor: Float): Int {
        val active = config ?: return 0
        val target = (currentVideoBitrate * factor).toInt()
            .coerceIn(active.minimumVideoBitrateBps, active.videoBitrateBps)
        if (target != currentVideoBitrate) {
            runCatching { videoEncoder?.changeBitrate(target) }.onSuccess { currentVideoBitrate = target }
        }
        return currentVideoBitrate
    }

    fun stop() {
        compositor.detachEncoder()
        runCatching { audioEncoder?.stop() }
        runCatching { videoEncoder?.stop() }
        audioEncoder = null
        videoEncoder = null
        synchronized(recordingLock) {
            recorder?.abort()
            recorder = null
        }
        synchronized(publisherLock) { publisher = null }
        config = null
    }

    override fun close() = stop()
}

private fun java.nio.ByteBuffer.toByteArray(): ByteArray {
    val copy = duplicate()
    copy.rewind()
    return ByteArray(copy.remaining()).also { copy.get(it) }
}
