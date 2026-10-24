package com.mobilestreaming.studio.streaming

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.mobilestreaming.studio.core.model.AudioMixSettings
import com.mobilestreaming.studio.core.model.RecordingEntry
import com.mobilestreaming.studio.core.model.RecordingState
import com.mobilestreaming.studio.core.model.Scene
import com.mobilestreaming.studio.core.model.StreamConfig
import com.mobilestreaming.studio.core.model.StreamPhase
import com.mobilestreaming.studio.core.model.StreamState
import com.mobilestreaming.studio.streaming.audio.AudioLevel
import com.mobilestreaming.studio.streaming.camera.Camera2Controller
import com.mobilestreaming.studio.streaming.compositor.SceneCompositor
import com.mobilestreaming.studio.streaming.rtmp.RtmpEndpoint
import com.mobilestreaming.studio.streaming.rtmp.RtmpPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.max

sealed interface EngineEvent {
    data class RecordingCompleted(val entry: RecordingEntry) : EngineEvent
    data class RecordingFailed(val message: String) : EngineEvent
    data class Message(val text: String) : EngineEvent
}

/** Application-scoped owner of capture, encoding, publishing and recording resources. */
class BroadcastEngine(
    private val context: Context,
    initialConfig: StreamConfig,
    initialScene: Scene,
    initialAudioMix: AudioMixSettings,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _streamState = MutableStateFlow(StreamState())
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()
    private val _recordingState = MutableStateFlow(RecordingState())
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()
    private val _audioLevel = MutableStateFlow(AudioLevel(0f, 0f, false, 0L))
    val audioLevel: StateFlow<AudioLevel> = _audioLevel.asStateFlow()
    private val _events = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<EngineEvent> = _events.asSharedFlow()
    private val _cameraError = MutableStateFlow<String?>(null)
    val cameraError: StateFlow<String?> = _cameraError.asStateFlow()
    private val _activeEncoderConfig = MutableStateFlow<StreamConfig?>(null)
    val activeEncoderConfig: StateFlow<StreamConfig?> = _activeEncoderConfig.asStateFlow()

    private val compositor = SceneCompositor(
        context = context,
        initialScene = initialScene,
        initialWidth = initialConfig.width,
        initialHeight = initialConfig.height,
        initialFrameRate = initialConfig.frameRate,
        onError = ::onCompositorError,
    )
    val previewCompositor: SceneCompositor get() = compositor
    private val camera = Camera2Controller(context, compositor, ::onCameraError)
    private val media = MediaCoordinator(
        context, compositor,
        onAudioLevel = { level -> _audioLevel.value = level },
        onError = ::onMediaError,
    )
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val thermalExecutor = Executors.newSingleThreadExecutor()
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        thermalStatus = status
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            scope.launch {
                val changed = media.changeBitrate(0.75f)
                if (changed > 0) _events.tryEmit(EngineEvent.Message("Thermal pressure: video bitrate reduced to ${changed / 1000} kbps"))
            }
        }
    }

    @Volatile private var networkAvailable = true
    @Volatile private var thermalStatus = 0
    private var streamJob: Job? = null
    private var publisher: RtmpPublisher? = null
    private var disconnected: CompletableDeferred<Throwable>? = null
    @Volatile private var stopRequested = false
    private var streamConfig = initialConfig
    private var audioMix = initialAudioMix
    private var cameraStarted = false
    private var previewConfig = initialConfig
    private var mirrorFront = true
    private var sessionStartedAt = 0L
    private var recordingStartedAt = 0L
    private var lastStatsAt = 0L
    private var lastStatsBytes = 0L
    private val sentBytes = AtomicLong()
    private val sentVideoBytes = AtomicLong()
    private val sentAudioBytes = AtomicLong()
    private val sentVideoPackets = AtomicLong()
    private val sentAudioPackets = AtomicLong()
    private val droppedNetworkFrames = AtomicLong()
    private var lastAdaptiveChangeAt = 0L

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { networkAvailable = true }
        override fun onLost(network: Network) { networkAvailable = hasNetwork() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            networkAvailable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    init {
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback) }
        networkAvailable = hasNetwork()
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { powerManager.addThermalStatusListener(thermalExecutor, thermalListener) }
        }
        scope.launch { monitorLoop() }
    }

    fun updateScene(scene: Scene) = compositor.updateScene(scene)

    fun startPreview(config: StreamConfig, front: Boolean = false, mirrorFrontCamera: Boolean = true) {
        if (streamState.value.isSessionActive && config != media.activeConfig) return
        scope.launch {
            runCatching {
                checkPermission(Manifest.permission.CAMERA, "Camera permission is required")
                withContext(Dispatchers.IO) {
                    if (!media.isRunning && previewConfig != config) compositor.reconfigure(config.width, config.height, config.frameRate)
                    mirrorFront = mirrorFrontCamera
                    camera.start(config, front, mirrorFrontCamera)
                    cameraStarted = true
                    previewConfig = config
                    streamConfig = config
                    _cameraError.value = null
                }
            }.onFailure { error -> _cameraError.value = error.displayMessage(config) }
        }
    }

    fun stopPreview() {
        if (streamState.value.isSessionActive || recordingState.value.isActive || media.isRunning) return
        camera.stop()
        cameraStarted = false
    }

    fun switchCamera() {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { camera.switchCamera() } }
                .onFailure { _cameraError.value = it.displayMessage(streamConfig) }
        }
    }

    fun setZoom(ratio: Float) = camera.setZoomRatio(ratio)
    fun setExposure(value: Int) = camera.setExposureCompensation(value)
    fun setTorch(enabled: Boolean) = camera.setTorch(enabled)
    fun focusAt(x: Float, y: Float) = camera.focusAt(x, y)
    fun setFrontMirror(mirror: Boolean) {
        mirrorFront = mirror
        camera.setMirrorFrontCamera(mirror)
    }
    fun updateAudioMix(settings: AudioMixSettings) {
        audioMix = settings
        media.updateMix(settings)
    }

    fun startStream(config: StreamConfig) {
        if (streamState.value.isSessionActive) return
        val validated = config.validated()
        checkPermission(Manifest.permission.CAMERA, "Camera permission is required before streaming")
        checkPermission(Manifest.permission.RECORD_AUDIO, "Microphone permission is required before streaming")
        check(media.activeConfig == null || media.activeConfig!!.sameEncoderSettings(validated)) {
            "Stop the active recording before changing resolution, frame rate or encoder settings"
        }
        streamConfig = validated
        stopRequested = false
        sentBytes.set(0)
        sentVideoBytes.set(0)
        sentAudioBytes.set(0)
        sentVideoPackets.set(0)
        sentAudioPackets.set(0)
        droppedNetworkFrames.set(0)
        sessionStartedAt = SystemClock.elapsedRealtime()
        lastStatsAt = sessionStartedAt
        lastStatsBytes = 0
        _streamState.value = StreamState(phase = StreamPhase.CONNECTING)
        streamJob = scope.launch {
            var attempt = 0
            var lastFailure: Throwable? = null
            try {
                ensureMedia(validated)
                while (isActive && !stopRequested) {
                    val newPublisher = RtmpPublisher(publisherListener)
                    publisher = newPublisher
                    disconnected = CompletableDeferred()
                    try {
                        _streamState.value = _streamState.value.copy(
                            phase = if (attempt == 0) StreamPhase.CONNECTING else StreamPhase.RECONNECTING,
                            reconnectAttempt = attempt,
                            errorMessage = null,
                        )
                        withContext(Dispatchers.IO) { newPublisher.connect(validated) }
                        if (stopRequested) break
                        media.attachPublisher(newPublisher)
                        _streamState.value = _streamState.value.copy(phase = StreamPhase.LIVE, errorMessage = null)
                        _events.tryEmit(EngineEvent.Message("RTMP server accepted the publish command"))
                        val failure = disconnected?.await() ?: IOException("RTMP connection ended")
                        throw failure
                    } catch (error: Throwable) {
                        if (stopRequested || !isActive) break
                        lastFailure = error
                    } finally {
                        media.detachPublisher(newPublisher)
                        newPublisher.close()
                        if (publisher === newPublisher) publisher = null
                    }
                    if (attempt >= validated.reconnectAttempts) break
                    attempt++
                    _streamState.value = _streamState.value.copy(
                        phase = StreamPhase.RECONNECTING,
                        reconnectAttempt = attempt,
                        errorMessage = lastFailure.displayMessage(validated),
                    )
                    delay((validated.reconnectDelayMs * attempt).coerceAtMost(30_000))
                }
            } catch (error: Throwable) {
                if (!stopRequested) lastFailure = error
            } finally {
                media.detachPublisherIfAny()
                publisher?.close()
                publisher = null
                disconnected = null
                val failure = lastFailure
                _streamState.value = if (stopRequested || failure == null) {
                    StreamState(statistics = _streamState.value.statistics)
                } else {
                    StreamState(
                        phase = StreamPhase.ERROR,
                        statistics = _streamState.value.statistics,
                        errorMessage = failure.displayMessage(validated),
                        reconnectAttempt = attempt,
                    )
                }
                stopMediaIfIdle()
                streamJob = null
            }
        }
    }

    fun stopStream() {
        if (!streamState.value.isSessionActive) return
        stopRequested = true
        _streamState.value = _streamState.value.copy(phase = StreamPhase.STOPPING)
        disconnected?.complete(IOException("Stream stopped"))
        publisher?.close()
    }

    fun startRecording() {
        if (recordingState.value.isActive || recordingState.value.isFinalizing) return
        checkPermission(Manifest.permission.CAMERA, "Camera permission is required before recording")
        checkPermission(Manifest.permission.RECORD_AUDIO, "Microphone permission is required before recording")
        if (Build.VERSION.SDK_INT < 29) {
            checkPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, "Storage permission is required on this Android version")
        }
        scope.launch {
            try {
                ensureMedia(streamConfig)
                val recorder = media.startRecording()
                recordingStartedAt = SystemClock.elapsedRealtime()
                _recordingState.value = RecordingState(isActive = true, displayName = recorder.name)
                _events.tryEmit(EngineEvent.Message("Recording started"))
            } catch (error: Throwable) {
                val message = error.displayMessage(streamConfig)
                _recordingState.value = RecordingState(errorMessage = message)
                _events.tryEmit(EngineEvent.RecordingFailed(message))
                stopMediaIfIdle()
            }
        }
    }

    fun stopRecording() {
        if (!recordingState.value.isActive) return
        _recordingState.value = _recordingState.value.copy(isActive = false, isFinalizing = true)
        scope.launch {
            try {
                val entry = withContext(Dispatchers.IO) { media.stopRecording() }
                _recordingState.value = RecordingState(
                    displayName = entry.displayName,
                    contentUri = entry.contentUri,
                    durationMs = entry.durationMs,
                    sizeBytes = entry.sizeBytes,
                )
                _events.tryEmit(EngineEvent.RecordingCompleted(entry))
            } catch (error: Throwable) {
                val message = error.displayMessage(streamConfig)
                _recordingState.value = RecordingState(errorMessage = message)
                _events.tryEmit(EngineEvent.RecordingFailed(message))
            } finally {
                stopMediaIfIdle()
            }
        }
    }

    fun stopAll() {
        stopRecording()
        stopStream()
        scope.launch {
            // Wait briefly for asynchronous finalization and stream teardown before releasing capture.
            var waited = 0
            while ((recordingState.value.isFinalizing || streamState.value.isSessionActive) && waited < 5_000) {
                delay(50)
                waited += 50
            }
            media.stop()
            _activeEncoderConfig.value = null
            camera.stop()
            cameraStarted = false
        }
    }

    private suspend fun ensureMedia(config: StreamConfig) {
        if (media.isRunning) {
            check(media.activeConfig!!.sameEncoderSettings(config)) { "Active encoder settings do not match the requested profile" }
            _activeEncoderConfig.value = media.activeConfig
            return
        }
        if (!cameraStarted) {
            withContext(Dispatchers.IO) {
                compositor.reconfigure(config.width, config.height, config.frameRate)
                camera.start(config, front = camera.isFrontCamera, mirrorFront = mirrorFront)
                cameraStarted = true
                previewConfig = config
            }
        }
        withContext(Dispatchers.IO) { media.start(config, audioMix) }
        _activeEncoderConfig.value = config
    }

    private fun stopMediaIfIdle() {
        if (!streamState.value.isSessionActive && media.recording == null) {
            media.stop()
            _activeEncoderConfig.value = null
        }
    }

    private val publisherListener = object : RtmpPublisher.Listener {
        override fun onPacketSent(typeId: Int, bytes: Int) {
            sentBytes.addAndGet(bytes.toLong())
            when (typeId) {
                8 -> { sentAudioBytes.addAndGet(bytes.toLong()); sentAudioPackets.incrementAndGet() }
                9 -> { sentVideoBytes.addAndGet(bytes.toLong()); sentVideoPackets.incrementAndGet() }
            }
        }

        override fun onPacketDropped(typeId: Int) {
            if (typeId == 9) droppedNetworkFrames.incrementAndGet()
        }

        override fun onDisconnected(cause: Throwable) {
            disconnected?.complete(cause)
        }
    }

    private suspend fun monitorLoop() {
        while (scope.isActive) {
            delay(1_000)
            val now = SystemClock.elapsedRealtime()
            val elapsed = (now - lastStatsAt).coerceAtLeast(1)
            val totalBytes = sentBytes.get()
            val deltaBytes = totalBytes - lastStatsBytes
            val factor = 8_000.0 / elapsed
            val current = _streamState.value
            if (current.isSessionActive || current.phase == StreamPhase.ERROR) {
                val videoBytes = sentVideoBytes.get()
                val audioBytes = sentAudioBytes.get()
                val total = (videoBytes + audioBytes).coerceAtLeast(1)
                val compositorStats = compositor.stats()
                _streamState.value = current.copy(
                    statistics = current.statistics.copy(
                        durationMs = if (sessionStartedAt > 0) now - sessionStartedAt else 0,
                        bitrateBps = (deltaBytes * factor).toLong().coerceAtLeast(0),
                        videoBitrateBps = (deltaBytes * factor * videoBytes / total).toLong().coerceAtLeast(0),
                        audioBitrateBps = (deltaBytes * factor * audioBytes / total).toLong().coerceAtLeast(0),
                        renderedFrames = compositorStats.renderedFrames,
                        droppedFrames = compositorStats.droppedFrames + droppedNetworkFrames.get(),
                        sentAudioPackets = sentAudioPackets.get(),
                        sentVideoPackets = sentVideoPackets.get(),
                        networkAvailable = networkAvailable,
                        thermalStatus = thermalStatus,
                    ),
                )
            }
            if (recordingState.value.isActive) {
                _recordingState.value = _recordingState.value.copy(
                    durationMs = now - recordingStartedAt,
                    sizeBytes = media.recording?.currentSizeBytes ?: 0,
                )
            }
            if (current.phase == StreamPhase.LIVE && streamConfig.adaptiveBitrate && now - lastAdaptiveChangeAt > 4_000) {
                val pressure = publisher?.queuedPackets ?: 0
                when {
                    pressure > 180 -> media.changeBitrate(0.85f)
                    pressure < 30 -> media.changeBitrate(1.05f)
                    else -> 0
                }.also { if (it != 0) lastAdaptiveChangeAt = now }
            }
            lastStatsAt = now
            lastStatsBytes = totalBytes
        }
    }

    private fun hasNetwork(): Boolean = runCatching {
        val network = connectivity.activeNetwork ?: return@runCatching false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    private fun checkPermission(permission: String, message: String) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw SecurityException(message)
    }

    private fun onCameraError(error: Throwable) {
        _cameraError.value = error.displayMessage(streamConfig)
        onMediaError(error)
    }

    private fun onCompositorError(error: Throwable) {
        Log.e("BroadcastEngine", "Compositor failure", error)
        onMediaError(error)
    }

    private fun onMediaError(error: Throwable) {
        scope.launch {
            _events.tryEmit(EngineEvent.Message(error.displayMessage(streamConfig)))
            if (recordingState.value.isActive) {
                runCatching { media.abortRecording() }
                _recordingState.value = RecordingState(errorMessage = error.displayMessage(streamConfig))
                _events.tryEmit(EngineEvent.RecordingFailed(error.displayMessage(streamConfig)))
            }
            if (streamState.value.isSessionActive) disconnected?.complete(error)
        }
    }

    private fun Throwable?.displayMessage(config: StreamConfig): String {
        val raw = this?.message?.takeIf { it.isNotBlank() } ?: this?.let { it::class.java.simpleName } ?: "Unknown error"
        val redactedEndpoint = runCatching { RtmpEndpoint.from(config).diagnosticUrl }.getOrDefault("configured RTMP endpoint")
        return raw.replace(config.serverUrl, redactedEndpoint)
            .replace(config.streamKey.takeIf { it.isNotBlank() } ?: "\u0000", "<redacted>")
            .take(500)
    }

    private fun StreamConfig.sameEncoderSettings(other: StreamConfig): Boolean =
        copy(serverUrl = "", streamKey = "") == other.copy(serverUrl = "", streamKey = "")

    override fun close() {
        stopRequested = true
        publisher?.close()
        media.stop()
        camera.close()
        compositor.close()
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        if (Build.VERSION.SDK_INT >= 29) runCatching { powerManager.removeThermalStatusListener(thermalListener) }
        thermalExecutor.shutdown()
        scope.cancel()
    }
}
