package com.mobilestreaming.studio.data

import android.content.Context
import com.mobilestreaming.studio.core.model.AppSettings
import com.mobilestreaming.studio.core.model.AudioMixSettings
import com.mobilestreaming.studio.core.model.EncoderProfile
import com.mobilestreaming.studio.core.model.QualityPreset
import com.mobilestreaming.studio.core.model.StreamConfig
import com.mobilestreaming.studio.core.model.StreamInformation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(
    context: Context,
    private val secureStreamKeyStore: SecureStreamKeyStore = SecureStreamKeyStore(context),
) {
    private val preferences = context.applicationContext.getSharedPreferences("studio_settings", Context.MODE_PRIVATE)
    private val defaults = StreamConfig()

    private val _streamConfig = MutableStateFlow(loadStreamConfig())
    val streamConfig: StateFlow<StreamConfig> = _streamConfig.asStateFlow()
    private val _appSettings = MutableStateFlow(loadAppSettings())
    val appSettings: StateFlow<AppSettings> = _appSettings.asStateFlow()
    private val _streamInformation = MutableStateFlow(loadStreamInformation())
    val streamInformation: StateFlow<StreamInformation> = _streamInformation.asStateFlow()

    @Synchronized
    fun saveStreamConfig(config: StreamConfig) {
        val normalized = config.validated(requireStreamKey = false)
        secureStreamKeyStore.write(normalized.streamKey)
        preferences.edit()
            .putString("serverUrl", normalized.serverUrl)
            .putInt("width", normalized.width)
            .putInt("height", normalized.height)
            .putInt("frameRate", normalized.frameRate)
            .putInt("videoBitrate", normalized.videoBitrateBps)
            .putInt("audioBitrate", normalized.audioBitrateBps)
            .putInt("audioSampleRate", normalized.audioSampleRate)
            .putInt("audioChannels", normalized.audioChannels)
            .putInt("keyframe", normalized.keyframeIntervalSeconds)
            .putString("profile", normalized.profile.name)
            .putInt("timeout", normalized.networkTimeoutMs)
            .putInt("reconnectAttempts", normalized.reconnectAttempts)
            .putLong("reconnectDelay", normalized.reconnectDelayMs)
            .putBoolean("adaptiveBitrate", normalized.adaptiveBitrate)
            .putInt("minimumBitrate", normalized.minimumVideoBitrateBps)
            .putString("quality", normalized.qualityPreset.name)
            .remove("streamKey") // Remove legacy plaintext if present.
            .apply()
        _streamConfig.value = normalized
    }

    @Synchronized
    fun saveAppSettings(settings: AppSettings) {
        preferences.edit()
            .putBoolean("darkTheme", settings.darkTheme)
            .putBoolean("followSystemTheme", settings.followSystemTheme)
            .putString("defaultSceneId", settings.defaultSceneId)
            .putString("defaultOverlayPreset", settings.defaultOverlayPreset)
            .putFloat("micVolume", settings.audio.microphoneVolume)
            .putBoolean("micMuted", settings.audio.microphoneMuted)
            .putBoolean("noiseSuppression", settings.audio.noiseSuppression)
            .putBoolean("agc", settings.audio.automaticGainControl)
            .putInt("recordingBitrate", settings.recordingBitrateBps ?: -1)
            .putBoolean("mirrorFrontCamera", settings.mirrorFrontCamera)
            .apply()
        _appSettings.value = settings
    }

    @Synchronized
    fun saveStreamInformation(information: StreamInformation) {
        preferences.edit()
            .putString("title", information.title.take(200))
            .putString("description", information.description.take(2_000))
            .putString("category", information.category.take(100))
            .putString("destinationName", information.destinationName.take(100))
            .apply()
        _streamInformation.value = information
    }

    @Synchronized
    fun reset() {
        secureStreamKeyStore.clear()
        preferences.edit().clear().apply()
        _streamConfig.value = defaults
        _appSettings.value = AppSettings()
        _streamInformation.value = StreamInformation()
    }

    private fun loadStreamConfig(): StreamConfig {
        val legacyKey = preferences.getString("streamKey", null)
        if (!legacyKey.isNullOrEmpty()) {
            secureStreamKeyStore.write(legacyKey)
            preferences.edit().remove("streamKey").apply()
        }
        return StreamConfig(
            serverUrl = preferences.getString("serverUrl", defaults.serverUrl) ?: defaults.serverUrl,
            streamKey = secureStreamKeyStore.read(),
            width = preferences.getInt("width", defaults.width),
            height = preferences.getInt("height", defaults.height),
            frameRate = preferences.getInt("frameRate", defaults.frameRate),
            videoBitrateBps = preferences.getInt("videoBitrate", defaults.videoBitrateBps),
            audioBitrateBps = preferences.getInt("audioBitrate", defaults.audioBitrateBps),
            audioSampleRate = preferences.getInt("audioSampleRate", defaults.audioSampleRate),
            audioChannels = preferences.getInt("audioChannels", defaults.audioChannels),
            keyframeIntervalSeconds = preferences.getInt("keyframe", defaults.keyframeIntervalSeconds),
            profile = enumValue("profile", defaults.profile),
            networkTimeoutMs = preferences.getInt("timeout", defaults.networkTimeoutMs),
            reconnectAttempts = preferences.getInt("reconnectAttempts", defaults.reconnectAttempts),
            reconnectDelayMs = preferences.getLong("reconnectDelay", defaults.reconnectDelayMs),
            adaptiveBitrate = preferences.getBoolean("adaptiveBitrate", defaults.adaptiveBitrate),
            minimumVideoBitrateBps = preferences.getInt("minimumBitrate", defaults.minimumVideoBitrateBps),
            qualityPreset = enumValue("quality", defaults.qualityPreset),
        )
    }

    private fun loadAppSettings() = AppSettings(
        darkTheme = preferences.getBoolean("darkTheme", true),
        followSystemTheme = preferences.getBoolean("followSystemTheme", true),
        defaultSceneId = preferences.getString("defaultSceneId", null),
        defaultOverlayPreset = preferences.getString("defaultOverlayPreset", "None") ?: "None",
        audio = AudioMixSettings(
            microphoneVolume = preferences.getFloat("micVolume", 1f).coerceIn(0f, 2f),
            microphoneMuted = preferences.getBoolean("micMuted", false),
            noiseSuppression = preferences.getBoolean("noiseSuppression", true),
            automaticGainControl = preferences.getBoolean("agc", false),
        ),
        recordingBitrateBps = preferences.getInt("recordingBitrate", -1).takeIf { it > 0 },
        mirrorFrontCamera = preferences.getBoolean("mirrorFrontCamera", true),
    )

    private fun loadStreamInformation() = StreamInformation(
        title = preferences.getString("title", "") ?: "",
        description = preferences.getString("description", "") ?: "",
        category = preferences.getString("category", "") ?: "",
        destinationName = preferences.getString("destinationName", "Generic RTMP") ?: "Generic RTMP",
    )

    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T =
        preferences.getString(key, null)?.let { value -> runCatching { enumValueOf<T>(value) }.getOrNull() } ?: default
}
