package com.mobilestreaming.studio

import android.app.Application
import com.mobilestreaming.studio.core.model.SourceKind
import com.mobilestreaming.studio.data.AssetRepository
import com.mobilestreaming.studio.data.DiagnosticLog
import com.mobilestreaming.studio.data.RecordingRepository
import com.mobilestreaming.studio.data.SceneRepository
import com.mobilestreaming.studio.data.SettingsRepository
import com.mobilestreaming.studio.streaming.BroadcastEngine
import com.mobilestreaming.studio.streaming.EngineEvent
import com.mobilestreaming.studio.streaming.overlay.BrowserOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class StudioApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(val context: StudioApplication) {
    val diagnosticLog = DiagnosticLog(context)
    val settings = SettingsRepository(context)
    val scenes = SceneRepository(context)
    val recordings = RecordingRepository(context)
    val assets = AssetRepository(context)
    val engine = BroadcastEngine(
        context = context,
        initialConfig = settings.streamConfig.value,
        initialScene = scenes.library.value.activeScene,
        initialAudioMix = settings.appSettings.value.audio,
    )
    val browserOverlays = BrowserOverlayController(engine.previewCompositor) { message ->
        diagnosticLog.warning("BrowserOverlay", message)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        settings.appSettings.value.defaultSceneId?.let { defaultId ->
            if (scenes.library.value.scenes.any { it.id == defaultId }) {
                scenes.updateLocal { it.switchScene(defaultId) }
            }
        }
        diagnosticLog.info("Studio", "Application container initialized")
        scope.launch {
            scenes.library.collectLatest { library ->
                engine.updateScene(library.activeScene)
                browserOverlays.update(
                    library.activeScene.sources.firstOrNull {
                        it.kind == SourceKind.BROWSER && it.visible && it.browserUrl != null
                    },
                )
            }
        }
        scope.launch {
            settings.appSettings.collectLatest { engine.updateAudioMix(it.audio) }
        }
        scope.launch {
            engine.events.collect { event ->
                when (event) {
                    is EngineEvent.RecordingCompleted -> {
                        recordings.add(event.entry)
                        diagnosticLog.info("Recording", "Completed ${event.entry.displayName}")
                    }
                    is EngineEvent.RecordingFailed -> diagnosticLog.error("Recording", event.message)
                    is EngineEvent.Message -> diagnosticLog.info("Broadcast", event.text)
                }
            }
        }
    }
}
