package com.mobilestreaming.studio.streaming.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.mobilestreaming.studio.core.model.AudioMixSettings
import com.mobilestreaming.studio.core.model.StreamConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

data class AudioLevel(
    val peak: Float,
    val rms: Float,
    val clipped: Boolean,
    val timestampMs: Long,
)

internal class AudioEncoder(
    private val context: Context,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onFormat(format: MediaFormat)
        fun onFrame(data: ByteArray, timestampUs: Long, flags: Int)
        fun onLevel(level: AudioLevel)
        fun onError(error: Throwable)
    }

    @Volatile private var running = false
    @Volatile private var mixSettings = AudioMixSettings()
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null
    private var codec: MediaCodec? = null

    fun updateMix(settings: AudioMixSettings) {
        mixSettings = settings
    }

    @SuppressLint("MissingPermission")
    fun start(config: StreamConfig, settings: AudioMixSettings) {
        stop()
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required"
        }
        mixSettings = settings
        val channelConfig = if (config.audioChannels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minimum = AudioRecord.getMinBufferSize(config.audioSampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "The selected audio format is not supported" }
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            config.audioSampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum * 2, 8192),
        )
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
        val format = MediaFormat.createAudioFormat(MIME_TYPE, config.audioSampleRate, config.audioChannels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, config.audioBitrateBps)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(minimum * 2, 8192))
        }
        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            codec = encoder
            recorder = audioRecord
            running = true
            thread = Thread({ captureLoop(audioRecord, encoder, config) }, "aac-audio-capture").also { it.start() }
        } catch (error: Throwable) {
            running = false
            audioRecord.release()
            encoder.release()
            codec = null
            recorder = null
            throw error
        }
    }

    fun stop() {
        running = false
        runCatching { recorder?.stop() }
        thread?.join(3_000)
        thread = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { recorder?.release() }
        codec = null
        recorder = null
    }

    override fun close() = stop()

    private fun captureLoop(audioRecord: AudioRecord, encoder: MediaCodec, config: StreamConfig) {
        var noiseSuppressor: NoiseSuppressor? = null
        var gainControl: AutomaticGainControl? = null
        val bufferInfo = MediaCodec.BufferInfo()
        var nextTimestampUs = System.nanoTime() / 1_000
        var pending: ByteBuffer? = null
        var pendingTimestampUs = nextTimestampUs
        var lastLevelUpdate = 0L
        try {
            if (mixSettings.noiseSuppression && NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)?.apply { enabled = true }
            }
            if (mixSettings.automaticGainControl && AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(audioRecord.audioSessionId)?.apply { enabled = true }
            }
            audioRecord.startRecording()
            check(audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone capture did not start" }
            val samples = ShortArray(1024 * config.audioChannels)
            while (running) {
                val desiredMix = mixSettings
                if (!desiredMix.noiseSuppression && noiseSuppressor != null) {
                    noiseSuppressor?.release()
                    noiseSuppressor = null
                } else if (desiredMix.noiseSuppression && noiseSuppressor == null && NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = runCatching {
                        NoiseSuppressor.create(audioRecord.audioSessionId)?.apply { enabled = true }
                    }.getOrNull()
                }
                if (!desiredMix.automaticGainControl && gainControl != null) {
                    gainControl?.release()
                    gainControl = null
                } else if (desiredMix.automaticGainControl && gainControl == null && AutomaticGainControl.isAvailable()) {
                    gainControl = runCatching {
                        AutomaticGainControl.create(audioRecord.audioSessionId)?.apply { enabled = true }
                    }.getOrNull()
                }
                if (pending == null || !pending.hasRemaining()) {
                    val count = audioRecord.read(samples, 0, samples.size)
                    if (count < 0) throw IllegalStateException("AudioRecord read error $count")
                    if (count == 0) continue
                    val captureEndUs = System.nanoTime() / 1_000
                    val durationUs = count.toLong() * 1_000_000 / (config.audioSampleRate * config.audioChannels)
                    if (abs(captureEndUs - nextTimestampUs) > 250_000) nextTimestampUs = captureEndUs - durationUs
                    val pcm = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN)
                    var peak = 0
                    var sumSquares = 0.0
                    var clipped = false
                    val mix = mixSettings
                    for (index in 0 until count) {
                        val input = samples[index].toInt()
                        val amplified = if (mix.microphoneMuted) 0 else (input * mix.microphoneVolume).toInt()
                        val output = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        clipped = clipped || abs(amplified) >= Short.MAX_VALUE.toInt()
                        pcm.putShort(output.toShort())
                        val absolute = abs(output)
                        if (absolute > peak) peak = absolute
                        sumSquares += output.toDouble() * output
                    }
                    pcm.flip()
                    pending = pcm
                    pendingTimestampUs = nextTimestampUs
                    nextTimestampUs += durationUs
                    val now = System.currentTimeMillis()
                    if (now - lastLevelUpdate >= 100) {
                        listener.onLevel(
                            AudioLevel(
                                peak = peak / 32768f,
                                rms = sqrt(sumSquares / count).toFloat() / 32768f,
                                clipped = clipped,
                                timestampMs = now,
                            ),
                        )
                        lastLevelUpdate = now
                    }
                }

                val inputIndex = encoder.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex) ?: continue
                    val source = pending ?: continue
                    val bytesToWrite = minOf(inputBuffer.remaining(), source.remaining())
                    val oldLimit = source.limit()
                    source.limit(source.position() + bytesToWrite)
                    inputBuffer.put(source)
                    source.limit(oldLimit)
                    val framesWritten = bytesToWrite / 2 / config.audioChannels
                    encoder.queueInputBuffer(inputIndex, 0, bytesToWrite, pendingTimestampUs, 0)
                    pendingTimestampUs += framesWritten * 1_000_000L / config.audioSampleRate
                    if (!source.hasRemaining()) pending = null
                }
                drainEncoder(encoder, bufferInfo, endOfStream = false)
            }
            val endIndex = encoder.dequeueInputBuffer(10_000)
            if (endIndex >= 0) encoder.queueInputBuffer(
                endIndex, 0, 0, nextTimestampUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
            val deadline = System.nanoTime() + 2_000_000_000L
            while (System.nanoTime() < deadline) {
                if (drainEncoder(encoder, bufferInfo, endOfStream = true)) break
                Thread.sleep(5)
            }
        } catch (error: Throwable) {
            if (running) listener.onError(error)
        } finally {
            running = false
            noiseSuppressor?.release()
            gainControl?.release()
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        endOfStream: Boolean,
    ): Boolean {
        while (true) {
            when (val index = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> listener.onFormat(encoder.outputFormat)
                else -> if (index >= 0) {
                    val bytes = if (info.size > 0) {
                        val buffer = encoder.getOutputBuffer(index) ?: continue
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        ByteArray(info.size).also { buffer.get(it) }
                    } else null
                    encoder.releaseOutputBuffer(index, false)
                    if (bytes != null) listener.onFrame(bytes, info.presentationTimeUs, info.flags)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }
            }
        }
    }

    private companion object { const val MIME_TYPE = "audio/mp4a-latm" }
}
