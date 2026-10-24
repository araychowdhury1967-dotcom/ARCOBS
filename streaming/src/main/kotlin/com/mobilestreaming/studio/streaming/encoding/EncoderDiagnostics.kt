package com.mobilestreaming.studio.streaming.encoding

import android.media.MediaCodecList
import com.mobilestreaming.studio.core.model.EncoderProfile
import com.mobilestreaming.studio.core.model.StreamConfig

object EncoderDiagnostics {
    fun h264Encoders(): List<String> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals("video/avc", true) } }
        .map { info ->
            val acceleration = if (android.os.Build.VERSION.SDK_INT >= 29) {
                if (info.isHardwareAccelerated) "hardware" else "software"
            } else "device"
            "${info.name} ($acceleration)"
        }

    fun supports(config: StreamConfig): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals("video/avc", true) } }
        .any { info ->
            runCatching {
                val capabilities = info.getCapabilitiesForType("video/avc")
                val wantedProfile = when (config.profile) {
                    EncoderProfile.BASELINE -> android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                    EncoderProfile.MAIN -> android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                    EncoderProfile.HIGH -> android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                }
                capabilities.profileLevels.any { it.profile == wantedProfile } &&
                    capabilities.videoCapabilities.areSizeAndRateSupported(
                        config.width, config.height, config.frameRate.toDouble(),
                    )
            }.getOrDefault(false)
        }
}
