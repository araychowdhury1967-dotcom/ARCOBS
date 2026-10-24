package com.mobilestreaming.studio.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.mobilestreaming.studio.OverlayPreset
import com.mobilestreaming.studio.StudioViewModel
import com.mobilestreaming.studio.core.model.EncoderProfile
import com.mobilestreaming.studio.core.model.QualityPreset
import com.mobilestreaming.studio.core.model.StreamConfig
import com.mobilestreaming.studio.streaming.encoding.EncoderDiagnostics

@Composable
fun AudioMixerScreen(viewModel: StudioViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.appSettings.collectAsState()
    val config by viewModel.streamConfig.collectAsState()
    val activeConfig by viewModel.activeEncoderConfig.collectAsState()
    val level by viewModel.audioLevel.collectAsState()
    var volume by remember(settings.audio.microphoneVolume) { mutableFloatStateOf(settings.audio.microphoneVolume) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionHeader("Audio Mixer", "Microphone capture • AAC-LC • synchronized with video")
            AudioMeter(level.peak, level.rms, level.clipped)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Microphone gain: ${(volume * 100).toInt()}%", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = volume,
                        onValueChange = { volume = it },
                        onValueChangeFinished = {
                            viewModel.saveAppSettings(settings.copy(audio = settings.audio.copy(microphoneVolume = volume)))
                        },
                        valueRange = 0f..2f,
                    )
                    SettingSwitch("Mute microphone", settings.audio.microphoneMuted) { muted ->
                        viewModel.saveAppSettings(settings.copy(audio = settings.audio.copy(microphoneMuted = muted)))
                    }
                    SettingSwitch("Noise suppression (when supported)", settings.audio.noiseSuppression) { enabled ->
                        viewModel.saveAppSettings(settings.copy(audio = settings.audio.copy(noiseSuppression = enabled)))
                    }
                    SettingSwitch("Automatic gain control (when supported)", settings.audio.automaticGainControl) { enabled ->
                        viewModel.saveAppSettings(settings.copy(audio = settings.audio.copy(automaticGainControl = enabled)))
                    }
                    SettingSwitch("Audio monitoring (disabled to prevent feedback)", false, enabled = false) {}
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AAC output", style = MaterialTheme.typography.titleMedium)
                    if (activeConfig != null) Text("Stop encoding to apply a different audio format.", color = StudioMuted)
                    Text("Bitrate")
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf(64, 96, 128, 192, 256).forEach { kbps ->
                            FilterChip(
                                selected = config.audioBitrateBps == kbps * 1000,
                                enabled = activeConfig == null,
                                onClick = { viewModel.saveStreamConfig(config.copy(audioBitrateBps = kbps * 1000)) },
                                label = { Text("$kbps kbps") },
                            )
                        }
                    }
                    Text("Sample rate")
                    Row {
                        listOf(44_100, 48_000).forEach { rate ->
                            FilterChip(
                                selected = config.audioSampleRate == rate,
                                enabled = activeConfig == null,
                                onClick = { viewModel.saveStreamConfig(config.copy(audioSampleRate = rate)) },
                                label = { Text("${rate / 1000f} kHz") },
                            )
                        }
                    }
                    Text("Channels")
                    Row {
                        listOf(1 to "Mono", 2 to "Stereo").forEach { (channels, label) ->
                            FilterChip(
                                selected = config.audioChannels == channels,
                                enabled = activeConfig == null,
                                onClick = { viewModel.saveStreamConfig(config.copy(audioChannels = channels)) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        "Android internal-audio capture is restricted by version, app policy and protected content. This build captures microphone narration only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StudioMuted,
                    )
                }
            }
        }
    }
}

@Composable
fun StreamSettingsScreen(viewModel: StudioViewModel, modifier: Modifier = Modifier) {
    val config by viewModel.streamConfig.collectAsState()
    val activeConfig by viewModel.activeEncoderConfig.collectAsState()
    var server by remember(config) { mutableStateOf(config.serverUrl) }
    var key by remember(config) { mutableStateOf(config.streamKey) }
    var showKey by remember { mutableStateOf(false) }
    var width by remember(config) { mutableIntStateOf(config.width) }
    var height by remember(config) { mutableIntStateOf(config.height) }
    var fps by remember(config) { mutableIntStateOf(config.frameRate) }
    var videoMbps by remember(config) { mutableFloatStateOf(config.videoBitrateBps / 1_000_000f) }
    var audioKbps by remember(config) { mutableIntStateOf(config.audioBitrateBps / 1000) }
    var keyframe by remember(config) { mutableIntStateOf(config.keyframeIntervalSeconds) }
    var profile by remember(config) { mutableStateOf(config.profile) }
    var timeoutSeconds by remember(config) { mutableIntStateOf(config.networkTimeoutMs / 1000) }
    var reconnects by remember(config) { mutableIntStateOf(config.reconnectAttempts) }
    var reconnectDelay by remember(config) { mutableFloatStateOf(config.reconnectDelayMs / 1000f) }
    var adaptive by remember(config) { mutableStateOf(config.adaptiveBitrate) }
    var preset by remember(config) { mutableStateOf(config.qualityPreset) }
    val candidate = config.copy(width = width, height = height, frameRate = fps, profile = profile)
    val supported = remember(width, height, fps, profile) { EncoderDiagnostics.supports(candidate) }

    fun editedConfig(): StreamConfig = config.copy(
        serverUrl = server,
        streamKey = key,
        width = width,
        height = height,
        frameRate = fps,
        videoBitrateBps = (videoMbps * 1_000_000).toInt(),
        audioBitrateBps = audioKbps * 1000,
        keyframeIntervalSeconds = keyframe,
        profile = profile,
        networkTimeoutMs = timeoutSeconds * 1000,
        reconnectAttempts = reconnects,
        reconnectDelayMs = (reconnectDelay * 1000).toLong(),
        adaptiveBitrate = adaptive,
        minimumVideoBitrateBps = (videoMbps * 200_000).toInt().coerceIn(100_000, (videoMbps * 1_000_000).toInt()),
        qualityPreset = preset,
    )

    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionHeader("Stream Settings", "Generic RTMP/RTMPS publishing • secrets use Android Keystore")
            if (activeConfig != null) {
                ErrorCard("An encoder session is active. Saved format changes apply after it stops; server credentials apply on the next connection.")
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = server, onValueChange = { server = it }, label = { Text("RTMP/RTMPS server URL") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("Stream key") },
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Never share the stream key. It is not included in scene exports or diagnostic logs.", style = MaterialTheme.typography.bodySmall, color = StudioMuted)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Quality preset", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        QualityPreset.entries.forEach { option ->
                            FilterChip(selected = preset == option, onClick = {
                                preset = option
                                val updated = StreamConfig.forPreset(option, editedConfig())
                                width = updated.width; height = updated.height; fps = updated.frameRate
                                videoMbps = updated.videoBitrateBps / 1_000_000f
                            }, label = { Text(option.name) })
                        }
                    }
                    Text("Resolution")
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf(854 to 480, 1280 to 720, 1920 to 1080, 720 to 1280, 960 to 960).forEach { (w, h) ->
                            FilterChip(selected = width == w && height == h, onClick = {
                                width = w; height = h; preset = QualityPreset.CUSTOM
                            }, label = { Text("$w×$h") })
                        }
                    }
                    Text("Frame rate")
                    Row {
                        listOf(30, 60).forEach { rate ->
                            FilterChip(selected = fps == rate, onClick = { fps = rate; preset = QualityPreset.CUSTOM }, label = { Text("$rate FPS") })
                        }
                    }
                    Text("H.264 profile")
                    Row {
                        EncoderProfile.entries.forEach { option ->
                            FilterChip(selected = profile == option, onClick = { profile = option }, label = { Text(option.name) })
                        }
                    }
                    Text(
                        if (supported) "A device H.264 encoder advertises support for this format. Camera support is checked again at capture start."
                        else "The selected encoder format is not advertised as supported on this device.",
                        color = if (supported) StudioMuted else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Video bitrate: ${"%.1f".format(videoMbps)} Mbps")
                    Slider(value = videoMbps, onValueChange = { videoMbps = it; preset = QualityPreset.CUSTOM }, valueRange = 0.5f..12f)
                    Text("Audio bitrate")
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf(64, 96, 128, 192, 256).forEach { kbps ->
                            FilterChip(selected = audioKbps == kbps, onClick = { audioKbps = kbps }, label = { Text("$kbps kbps") })
                        }
                    }
                    Text("Keyframe interval: $keyframe seconds")
                    Slider(value = keyframe.toFloat(), onValueChange = { keyframe = it.toInt() }, valueRange = 1f..10f)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connection and recovery", style = MaterialTheme.typography.titleMedium)
                    Text("Network timeout: $timeoutSeconds s")
                    Slider(value = timeoutSeconds.toFloat(), onValueChange = { timeoutSeconds = it.toInt() }, valueRange = 2f..30f)
                    Text("Reconnect attempts: $reconnects")
                    Slider(value = reconnects.toFloat(), onValueChange = { reconnects = it.toInt() }, valueRange = 0f..10f)
                    Text("Reconnect delay: ${"%.1f".format(reconnectDelay)} s")
                    Slider(value = reconnectDelay, onValueChange = { reconnectDelay = it }, valueRange = 0.5f..10f)
                    SettingSwitch("Adaptive bitrate based on publisher queue pressure", adaptive) { adaptive = it }
                }
            }
        }
        item {
            Button(onClick = { viewModel.saveStreamConfig(editedConfig().validated(requireStreamKey = false)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save stream settings")
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Platform configuration", fontWeight = FontWeight.SemiBold)
                    Text("YouTube Live: use the ingest URL and key from YouTube Studio. Twitch: use an ingest endpoint and your stream key. Facebook Live: use the URL/key from Live Producer, subject to account permissions.")
                    Text("Platform requirements change; confirm current official documentation. No platform credentials or APIs are hardcoded.", color = StudioMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: StudioViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.appSettings.collectAsState()
    val library by viewModel.sceneLibrary.collectAsState()
    var resetSettings by remember { mutableStateOf(false) }
    var resetScenes by remember { mutableStateOf(false) }
    val diagnostics = remember { viewModel.encoderDiagnostics() }
    val exportProfile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            viewModel.getApplication<android.app.Application>().contentResolver.openOutputStream(uri)?.let(viewModel::exportScenes)
        }
    }
    val importProfile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.getApplication<android.app.Application>().contentResolver.openInputStream(uri)?.let(viewModel::importScenes)
        }
    }
    val exportLogs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            viewModel.getApplication<android.app.Application>().contentResolver.openOutputStream(uri)?.let(viewModel::exportDiagnostics)
        }
    }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeader("Settings", "Preferences, profiles, diagnostics and secure storage") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    SettingSwitch("Follow system theme", settings.followSystemTheme) { checked ->
                        viewModel.saveAppSettings(settings.copy(followSystemTheme = checked))
                    }
                    SettingSwitch("Dark theme", settings.darkTheme, enabled = !settings.followSystemTheme) { checked ->
                        viewModel.saveAppSettings(settings.copy(darkTheme = checked))
                    }
                    SettingSwitch("Mirror front camera by default", settings.mirrorFrontCamera) { checked ->
                        viewModel.setMirror(checked)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Default scene on launch")
                    LazyRowOfChips(library.scenes.map { it.name to it.id }, settings.defaultSceneId) { id ->
                        viewModel.saveAppSettings(settings.copy(defaultSceneId = id))
                    }
                    Text("Default overlay for new scenes: ${settings.defaultOverlayPreset}")
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        FilterChip(selected = settings.defaultOverlayPreset == "None", onClick = {
                            viewModel.saveAppSettings(settings.copy(defaultOverlayPreset = "None"))
                        }, label = { Text("None") })
                        OverlayPreset.entries.filter { it != OverlayPreset.LOGO }.forEach { preset ->
                            FilterChip(selected = settings.defaultOverlayPreset == preset.label, onClick = {
                                viewModel.saveAppSettings(settings.copy(defaultOverlayPreset = preset.label))
                            }, label = { Text(preset.label) })
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Profiles and diagnostics", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { exportProfile.launch("mobile-streaming-scenes.json") }) { Text("Export scene profile") }
                    Button(onClick = { importProfile.launch(arrayOf("application/json", "text/plain")) }) { Text("Import scene profile") }
                    Button(onClick = { exportLogs.launch("mobile-streaming-diagnostics.txt") }) { Text("Export redacted logs") }
                    Text("Scene exports never include the RTMP endpoint key or encrypted preferences.", color = StudioMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Hardware H.264 encoders", style = MaterialTheme.typography.titleMedium)
                    diagnostics.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = StudioMuted) }
                    Text("Actual support is checked again for resolution, profile and frame rate before encoding.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            HorizontalDivider()
            TextButton(onClick = { resetSettings = true }) { Text("Reset settings") }
            TextButton(onClick = { resetScenes = true }) { Text("Restore starter scenes") }
            Text("Mobile Streaming Studio 0.1.0 • Original interface and implementation", color = StudioMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (resetSettings) {
        AlertDialog(
            onDismissRequest = { resetSettings = false },
            title = { Text("Reset all settings?") },
            text = { Text("This removes the saved stream key and restores default preferences. Scenes and recordings remain.") },
            confirmButton = { TextButton(onClick = { viewModel.resetSettings(); resetSettings = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { resetSettings = false }) { Text("Cancel") } },
        )
    }
    if (resetScenes) {
        AlertDialog(
            onDismissRequest = { resetScenes = false },
            title = { Text("Restore starter scenes?") },
            text = { Text("This replaces all saved scenes and overlays. Recordings remain.") },
            confirmButton = { TextButton(onClick = { viewModel.resetScenes(); resetScenes = false }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { resetScenes = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked = checked, enabled = enabled, onCheckedChange = onChecked)
    }
}

@Composable
private fun LazyRowOfChips(items: List<Pair<String, String>>, selected: String?, onSelected: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        items.forEach { (label, id) ->
            FilterChip(selected = selected == id, onClick = { onSelected(id) }, label = { Text(label) })
        }
    }
}
