package com.mobilestreaming.studio.streaming.encoding

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.mobilestreaming.studio.core.model.EncoderProfile
import com.mobilestreaming.studio.core.model.StreamConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class VideoEncoder(
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onFormat(format: MediaFormat)
        fun onFrame(data: ByteArray, timestampUs: Long, flags: Int)
        fun onError(error: Throwable)
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var thread: HandlerThread? = null
    private val stopping = AtomicBoolean(false)

    fun start(config: StreamConfig, bitrateBps: Int = config.videoBitrateBps): Surface {
        stop()
        stopping.set(false)
        val codecInfo = chooseVideoCodec(config)
        val capabilities = codecInfo.getCapabilitiesForType(MIME_TYPE)
        val video = capabilities.videoCapabilities
        require(video.isSizeSupported(config.width, config.height)) {
            "${codecInfo.name} does not support ${config.width}×${config.height}"
        }
        require(video.areSizeAndRateSupported(config.width, config.height, config.frameRate.toDouble())) {
            "${codecInfo.name} does not support ${config.width}×${config.height} at ${config.frameRate} FPS"
        }
        require(video.bitrateRange.contains(bitrateBps)) { "Video bitrate is outside the encoder range" }
        val profile = config.profile.toCodecProfile()
        require(capabilities.profileLevels.any { it.profile == profile }) {
            "${config.profile} H.264 profile is not supported by ${codecInfo.name}"
        }
        val format = MediaFormat.createVideoFormat(MIME_TYPE, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, config.keyframeIntervalSeconds)
            setInteger(MediaFormat.KEY_PROFILE, profile)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        val encoder = MediaCodec.createByCodecName(codecInfo.name)
        codec = encoder
        val callbackThread = HandlerThread("h264-encoder").apply { start() }
        thread = callbackThread
        val eos = CountDownLatch(1)
        endOfStreamField = eos
        encoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val bytes = if (info.size > 0) {
                        val buffer = codec.getOutputBuffer(index)
                            ?: throw IllegalStateException("Missing H.264 output buffer")
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        ByteArray(info.size).also { buffer.get(it) }
                    } else null
                    codec.releaseOutputBuffer(index, false)
                    if (bytes != null) listener.onFrame(bytes, info.presentationTimeUs, info.flags)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos.countDown()
                } catch (error: Throwable) {
                    if (!stopping.get()) listener.onError(error)
                    eos.countDown()
                }
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = listener.onFormat(format)

            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                if (!stopping.get()) listener.onError(error)
                eos.countDown()
            }
        }, Handler(callbackThread.looper))
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        inputSurface = surface
        encoder.start()
        return surface
    }

    fun requestSyncFrame() {
        runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    fun changeBitrate(bitrateBps: Int) {
        codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps) })
    }

    fun stop() {
        val encoder = codec ?: return
        stopping.set(true)
        runCatching { encoder.signalEndOfInputStream() }
        runCatching { endOfStreamField?.await(2, TimeUnit.SECONDS) }
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        inputSurface?.release()
        inputSurface = null
        codec = null
        thread?.quitSafely()
        thread = null
    }

    override fun close() = stop()

    private var endOfStreamField: CountDownLatch? = null

    private fun chooseVideoCodec(config: StreamConfig): MediaCodecInfo {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        return infos.filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MIME_TYPE, true) } }
            .sortedBy { if (it.isHardwareAcceleratedCompat) 0 else 1 }
            .firstOrNull { info ->
                runCatching {
                    val capabilities = info.getCapabilitiesForType(MIME_TYPE)
                    val profile = config.profile.toCodecProfile()
                    capabilities.profileLevels.any { it.profile == profile } &&
                        capabilities.videoCapabilities.isSizeSupported(config.width, config.height) &&
                        capabilities.videoCapabilities.areSizeAndRateSupported(
                            config.width, config.height, config.frameRate.toDouble(),
                        )
                }.getOrDefault(false)
            }
            ?: throw IllegalArgumentException("No H.264 encoder supports the selected profile, resolution and frame rate")
    }

    private fun EncoderProfile.toCodecProfile() = when (this) {
        EncoderProfile.BASELINE -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        EncoderProfile.MAIN -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
        EncoderProfile.HIGH -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
    }

    private val MediaCodecInfo.isHardwareAcceleratedCompat: Boolean
        get() = if (android.os.Build.VERSION.SDK_INT >= 29) isHardwareAccelerated
        else !name.startsWith("OMX.google.", true) && !name.startsWith("c2.android.", true)

    companion object { private const val MIME_TYPE = "video/avc" }
}
