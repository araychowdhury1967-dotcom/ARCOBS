package com.mobilestreaming.studio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.mobilestreaming.studio.core.model.StreamPhase
import com.mobilestreaming.studio.streaming.screen.ScreenCaptureController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BroadcastService : Service() {
    private val container by lazy { (application as StudioApplication).container }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var foreground = false
    private var foregroundTypes = 0
    private var observedActivity = false
    private var screenCapture: ScreenCaptureController? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.broadcast_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.broadcast_notification_description) },
        )
        serviceScope.launch {
            container.engine.streamState.collectLatest { state ->
                if (state.isSessionActive) observedActivity = true
                updateNotification()
                if (observedActivity) stopIfIdle()
            }
        }
        serviceScope.launch {
            container.engine.recordingState.collectLatest { state ->
                if (state.isActive || state.isFinalizing) observedActivity = true
                updateNotification()
                if (observedActivity) stopIfIdle()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAM -> {
                ensureForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                observedActivity = true
                runCatching { container.engine.startStream(container.settings.streamConfig.value) }
                    .onFailure { container.diagnosticLog.error("Service", "Unable to start stream", it) }
            }
            ACTION_STOP_STREAM -> container.engine.stopStream()
            ACTION_START_RECORDING -> {
                ensureForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                observedActivity = true
                runCatching { container.engine.startRecording() }
                    .onFailure { container.diagnosticLog.error("Service", "Unable to start recording", it) }
            }
            ACTION_STOP_RECORDING -> container.engine.stopRecording()
            ACTION_START_SCREEN -> {
                ensureForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                observedActivity = true
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val consent = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_CONSENT)
                }
                if (consent == null) {
                    container.diagnosticLog.error("Service", "Missing MediaProjection consent")
                } else {
                    runCatching {
                        val controller = ScreenCaptureController(this, container.engine.previewCompositor) { error ->
                            mainHandler.post {
                                screenCapture?.stop()
                                screenCapture = null
                                if (error != null) container.diagnosticLog.error("ScreenCapture", "Projection revoked", error)
                                stopIfIdle(force = true)
                            }
                        }
                        screenCapture = controller
                        controller.start(resultCode, consent)
                    }.onFailure {
                        screenCapture = null
                        container.diagnosticLog.error("ScreenCapture", "Unable to start screen capture", it)
                        stopIfIdle(force = true)
                    }
                }
            }
            ACTION_STOP_SCREEN -> {
                screenCapture?.stop()
                screenCapture = null
                stopIfIdle(force = true)
            }
            ACTION_STOP_ALL -> {
                screenCapture?.stop()
                screenCapture = null
                container.engine.stopAll()
                observedActivity = true
                mainHandler.postDelayed({ stopIfIdle(force = true) }, 5_500)
            }
        }
        updateNotification()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        screenCapture?.stop()
        screenCapture = null
        if (container.engine.streamState.value.isSessionActive ||
            container.engine.recordingState.value.isActive ||
            container.engine.recordingState.value.isFinalizing
        ) container.engine.stopAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureForeground(additionalTypes: Int) {
        foregroundTypes = foregroundTypes or additionalTypes
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, foregroundTypes)
        else startForeground(NOTIFICATION_ID, notification)
        foreground = true
    }

    private fun updateNotification() {
        if (foreground) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, BroadcastService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stream = container.engine.streamState.value
        val recording = container.engine.recordingState.value
        val status = when {
            recording.isFinalizing -> "Finalizing recording"
            recording.isActive -> "Recording locally"
            stream.phase == StreamPhase.LIVE -> "Live now"
            stream.phase == StreamPhase.CONNECTING -> "Connecting to stream server"
            stream.phase == StreamPhase.RECONNECTING -> "Reconnecting (attempt ${stream.reconnectAttempt})"
            stream.phase == StreamPhase.STOPPING -> "Stopping broadcast"
            screenCapture?.isActive == true -> "Screen capture active"
            else -> "Broadcast session stopped"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop all", stop).build())
            .build()
    }

    private fun stopIfIdle(force: Boolean = false) {
        if (!foreground) return
        val idle = !container.engine.streamState.value.isSessionActive &&
            !container.engine.recordingState.value.isActive &&
            !container.engine.recordingState.value.isFinalizing &&
            screenCapture?.isActive != true
        if (idle && (force || observedActivity)) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
            foreground = false
            foregroundTypes = 0
            observedActivity = false
            stopSelf()
        }
    }

    companion object {
        private const val CHANNEL_ID = "broadcast_session"
        private const val NOTIFICATION_ID = 41
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_CONSENT = "consent"
        const val ACTION_START_STREAM = "com.mobilestreaming.studio.action.START_STREAM"
        const val ACTION_STOP_STREAM = "com.mobilestreaming.studio.action.STOP_STREAM"
        const val ACTION_START_RECORDING = "com.mobilestreaming.studio.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.mobilestreaming.studio.action.STOP_RECORDING"
        const val ACTION_START_SCREEN = "com.mobilestreaming.studio.action.START_SCREEN"
        const val ACTION_STOP_SCREEN = "com.mobilestreaming.studio.action.STOP_SCREEN"
        const val ACTION_STOP_ALL = "com.mobilestreaming.studio.action.STOP_ALL"

        fun command(context: Context, action: String): Intent =
            Intent(context, BroadcastService::class.java).setAction(action)

        fun screenConsent(context: Context, resultCode: Int, consent: Intent): Intent =
            command(context, ACTION_START_SCREEN)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_CONSENT, consent)
    }
}
