package com.mobilestreaming.studio

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobilestreaming.studio.core.model.AppSettings
import com.mobilestreaming.studio.core.model.SceneLibrary
import com.mobilestreaming.studio.core.model.SourceKind
import com.mobilestreaming.studio.core.model.StreamConfig
import com.mobilestreaming.studio.core.model.StreamInformation
import com.mobilestreaming.studio.core.model.VisualSource
import com.mobilestreaming.studio.streaming.EngineEvent
import com.mobilestreaming.studio.streaming.encoding.EncoderDiagnostics
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

class StudioViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as StudioApplication).container
    val streamConfig = container.settings.streamConfig
    val appSettings = container.settings.appSettings
    val streamInformation = container.settings.streamInformation
    val sceneLibrary = container.scenes.library
    val recordings = container.recordings.recordings
    val streamState = container.engine.streamState
    val recordingState = container.engine.recordingState
    val audioLevel = container.engine.audioLevel
    val cameraError = container.engine.cameraError
    val activeEncoderConfig = container.engine.activeEncoderConfig
    val previewCompositor = container.engine.previewCompositor

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        viewModelScope.launch {
            container.engine.events.collect { event ->
                when (event) {
                    is EngineEvent.RecordingCompleted -> _messages.emit("Recording saved: ${event.entry.displayName}")
                    is EngineEvent.RecordingFailed -> _messages.emit(event.message)
                    is EngineEvent.Message -> _messages.emit(event.text)
                }
            }
        }
    }

    fun saveStreamConfig(config: StreamConfig) = run("Stream settings saved") {
        container.settings.saveStreamConfig(config.validated(requireStreamKey = false))
    }

    fun saveAppSettings(settings: AppSettings) = run("Settings saved") {
        container.settings.saveAppSettings(settings)
    }

    fun saveStreamInformation(information: StreamInformation) = run("Stream information saved") {
        container.settings.saveStreamInformation(information)
    }

    fun startPreview() = container.engine.startPreview(
        streamConfig.value,
        mirrorFrontCamera = appSettings.value.mirrorFrontCamera,
    )

    fun stopPreview() = container.engine.stopPreview()
    fun switchCamera() = container.engine.switchCamera()
    fun setZoom(value: Float) = container.engine.setZoom(value)
    fun setExposure(value: Int) = container.engine.setExposure(value)
    fun setTorch(enabled: Boolean) = runCatchingMessage { container.engine.setTorch(enabled) }
    fun focusAt(x: Float, y: Float) = container.engine.focusAt(x, y)
    fun setMirror(mirror: Boolean) {
        container.engine.setFrontMirror(mirror)
        saveAppSettings(appSettings.value.copy(mirrorFrontCamera = mirror))
    }

    fun startStream() {
        runCatchingMessage {
            streamConfig.value.validated()
            getApplication<Application>().startForegroundService(
                BroadcastService.command(getApplication(), BroadcastService.ACTION_START_STREAM),
            )
        }
    }

    fun stopStream() = command(BroadcastService.ACTION_STOP_STREAM)
    fun startRecording() = runCatchingMessage {
        getApplication<Application>().startForegroundService(
            BroadcastService.command(getApplication(), BroadcastService.ACTION_START_RECORDING),
        )
    }
    fun stopRecording() = command(BroadcastService.ACTION_STOP_RECORDING)
    fun stopAll() = command(BroadcastService.ACTION_STOP_ALL)
    fun stopScreenCapture() = command(BroadcastService.ACTION_STOP_SCREEN)

    fun startScreenCapture(resultCode: Int, consent: Intent) {
        getApplication<Application>().startForegroundService(
            BroadcastService.screenConsent(getApplication(), resultCode, consent),
        )
    }

    private fun command(action: String) = runCatchingMessage {
        getApplication<Application>().startService(BroadcastService.command(getApplication(), action))
    }

    fun addScene(name: String) = updateScenes { library ->
        val added = library.addScene(name)
        val preset = OverlayPreset.entries.firstOrNull { it.label == appSettings.value.defaultOverlayPreset }
        if (preset == null) added else added.updateActiveScene { it.withSource(preset.create(it.sources.size + 1)) }
    }
    fun renameScene(id: String, name: String) = updateScenes { it.renameScene(id, name) }
    fun duplicateScene(id: String) = updateScenes { it.duplicateScene(id) }
    fun deleteScene(id: String) = updateScenes { it.deleteScene(id) }
    fun moveScene(id: String, delta: Int) = updateScenes { it.moveScene(id, delta) }
    fun switchScene(id: String) = updateScenes { it.switchScene(id) }

    fun selectSource(id: String?) = container.scenes.updateLocal { it.selectSource(id) }

    fun updateSource(id: String, transform: (VisualSource) -> VisualSource) = updateScenes { library ->
        library.updateActiveScene { scene -> scene.updateSource(id, transform) }
    }

    fun transformSelectedSource(dx: Float, dy: Float, scale: Float, rotation: Float) {
        val selected = sceneLibrary.value.selectedSourceId ?: return
        container.scenes.updateLocal { library ->
            library.updateActiveScene { scene ->
                scene.updateSource(selected) { source ->
                    if (source.locked) source else source.copy(
                        transform = source.transform
                            .translated(dx, dy)
                            .scaled(scale)
                            .let { it.copy(rotationDegrees = it.rotationDegrees + rotation).constrained() }
                            .let {
                                it.copy(
                                    centerX = if (kotlin.math.abs(it.centerX - 0.5f) < 0.015f) 0.5f else it.centerX,
                                    centerY = if (kotlin.math.abs(it.centerY - 0.5f) < 0.015f) 0.5f else it.centerY,
                                )
                            },
                    )
                }
            }
        }
    }

    fun finishSourceGesture() {
        viewModelScope.launch { runCatching { container.scenes.persistCurrent() } }
    }

    fun deleteSource(id: String) = updateScenes { library ->
        library.updateActiveScene { it.removeSource(id) }.selectSource(null)
    }

    fun moveSource(id: String, delta: Int) = updateScenes { library ->
        library.updateActiveScene { it.moveSource(id, delta) }
    }

    fun addBasicSource(kind: SourceKind) = updateScenes { library ->
        library.updateActiveScene { scene ->
            scene.withSource(
                VisualSource(
                    name = "${kind.name.lowercase().replaceFirstChar { it.uppercase() }} ${scene.sources.size + 1}",
                    kind = kind,
                    backgroundColor = if (kind == SourceKind.SOLID_COLOR) 0xFF111827 else 0,
                ),
            )
        }
    }

    fun addTextSource() = updateScenes { library ->
        library.updateActiveScene {
            it.withSource(
                VisualSource(
                    name = "Text ${it.sources.size + 1}",
                    kind = SourceKind.TEXT,
                    text = "YOUR TEXT",
                    backgroundColor = 0x00000000,
                    transform = com.mobilestreaming.studio.core.model.SourceTransform(
                        centerY = 0.8f, width = 0.8f, height = 0.16f,
                    ),
                ),
            )
        }
    }

    fun addImageSource(uri: Uri, logo: Boolean = false) {
        viewModelScope.launch {
            runCatching {
                val imported = container.assets.importImage(uri)
                container.scenes.update { library ->
                    library.updateActiveScene { scene ->
                        scene.withSource(
                            VisualSource(
                                name = if (logo) "Logo ${scene.sources.size + 1}" else "Image ${scene.sources.size + 1}",
                                kind = if (logo) SourceKind.LOGO else SourceKind.IMAGE,
                                imageUri = imported.toString(),
                                backgroundColor = 0,
                                transform = com.mobilestreaming.studio.core.model.SourceTransform(
                                    centerX = 0.82f, centerY = 0.18f, width = 0.25f, height = 0.25f,
                                ),
                            ),
                        )
                    }
                }
            }.onSuccess { _messages.emit("Image added to the current scene") }
                .onFailure { _messages.emit(it.message ?: "Image import failed") }
        }
    }

    fun addBrowserSource(url: String, javaScript: Boolean) = updateScenes { library ->
        val source = VisualSource(
            name = "Browser overlay",
            kind = SourceKind.BROWSER,
            browserUrl = url,
            browserJavaScript = javaScript,
            backgroundColor = 0,
        )
        source.browserUrlValidated()
        library.updateActiveScene { it.withSource(source) }
    }

    fun addOverlay(preset: OverlayPreset) = updateScenes { library ->
        library.updateActiveScene { it.withSource(preset.create(it.sources.size + 1)) }
    }

    fun deleteRecording(id: String) {
        viewModelScope.launch {
            runCatching {
                val entry = recordings.value.firstOrNull { it.id == id } ?: return@launch
                getApplication<Application>().contentResolver.delete(Uri.parse(entry.contentUri), null, null)
                container.recordings.remove(id)
            }.onFailure { _messages.emit(it.message ?: "Unable to delete recording") }
        }
    }

    fun exportScenes(output: OutputStream) = run("Scene profile exported") { container.scenes.exportTo(output) }
    fun importScenes(input: InputStream) = run("Scene profile imported") {
        input.use { container.scenes.importFrom(it) }
    }
    fun exportDiagnostics(output: OutputStream) = run("Diagnostics exported") { container.diagnosticLog.exportTo(output) }

    fun resetSettings() = run("Settings reset") { container.settings.reset() }
    fun resetScenes() = run("Starter scenes restored") { container.scenes.reset() }

    fun encoderDiagnostics(): List<String> = EncoderDiagnostics.h264Encoders()

    private fun updateScenes(transform: (SceneLibrary) -> SceneLibrary) = run("Scene updated") {
        container.scenes.update(transform)
    }

    private fun <T> run(successMessage: String, block: suspend () -> T) {
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { _messages.emit(successMessage) }
                .onFailure { _messages.emit(it.message ?: "Operation failed") }
        }
    }

    private fun runCatchingMessage(block: () -> Unit) {
        runCatching(block).onFailure {
            viewModelScope.launch { _messages.emit(it.message ?: "Operation failed") }
        }
    }
}

enum class OverlayPreset(val label: String) {
    LOWER_THIRD("Lower third"),
    BREAKING_NEWS("Breaking News"),
    TICKER("Scrolling ticker"),
    CLOCK("Clock"),
    DATE("Date"),
    LOGO("Logo / watermark");

    fun create(index: Int): VisualSource = when (this) {
        LOWER_THIRD -> VisualSource(
            name = "Lower third $index", kind = SourceKind.LOWER_THIRD, text = "NAME  •  TITLE",
            backgroundColor = 0xE60F766E,
            transform = com.mobilestreaming.studio.core.model.SourceTransform(0.3f, 0.82f, 0.56f, 0.14f),
        )
        BREAKING_NEWS -> VisualSource(
            name = "Breaking News $index", kind = SourceKind.BREAKING_NEWS, text = "BREAKING NEWS",
            backgroundColor = 0xE6B91C1C,
            transform = com.mobilestreaming.studio.core.model.SourceTransform(0.5f, 0.12f, 0.9f, 0.12f),
        )
        TICKER -> VisualSource(
            name = "Ticker $index", kind = SourceKind.TICKER,
            text = "Add your scrolling headline here  •  Mobile Streaming Studio",
            backgroundColor = 0xE6111827,
            transform = com.mobilestreaming.studio.core.model.SourceTransform(0.5f, 0.94f, 1f, 0.09f),
        )
        CLOCK -> VisualSource(
            name = "Clock $index", kind = SourceKind.CLOCK, backgroundColor = 0x99101820,
            transform = com.mobilestreaming.studio.core.model.SourceTransform(0.87f, 0.1f, 0.22f, 0.1f),
        )
        DATE -> VisualSource(
            name = "Date $index", kind = SourceKind.DATE, backgroundColor = 0x99101820,
            transform = com.mobilestreaming.studio.core.model.SourceTransform(0.82f, 0.2f, 0.32f, 0.1f),
        )
        LOGO -> VisualSource(
            name = "Logo placeholder $index", kind = SourceKind.TEXT, text = "YOUR LOGO",
            backgroundColor = 0x80101820,
            transform = com.mobilestreaming.studio.core.model.SourceTransform(0.85f, 0.12f, 0.22f, 0.12f),
        )
    }
}
