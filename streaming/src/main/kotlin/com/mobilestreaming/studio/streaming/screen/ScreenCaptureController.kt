package com.mobilestreaming.studio.streaming.screen

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import com.mobilestreaming.studio.streaming.compositor.SceneCompositor
import java.nio.ByteBuffer

/** Consent-gated MediaProjection input. Frames are bounded to 15 FPS and two ImageReader images. */
class ScreenCaptureController(
    private val context: Context,
    private val compositor: SceneCompositor,
    private val onStopped: (Throwable?) -> Unit,
) : AutoCloseable {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var callback: MediaProjection.Callback? = null
    @Volatile private var stopping = false

    val isActive: Boolean get() = projection != null && !stopping

    fun start(resultCode: Int, consent: Intent, width: Int = 1280, height: Int = 720) {
        check(!isActive) { "Screen capture is already active" }
        require(width in 320..1920 && height in 320..1920 && width % 2 == 0 && height % 2 == 0) {
            "Unsupported screen capture dimensions"
        }
        stopping = false
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, consent)
            ?: throw SecurityException("Screen capture consent was not granted")
        this.projection = projection
        val captureThread = HandlerThread("media-projection-frames").apply { start() }
        thread = captureThread
        val handler = Handler(captureThread.looper)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        var lastFrameAt = 0L
        imageReader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastFrameAt < 66) return@setOnImageAvailableListener
                lastFrameAt = now
                val plane = image.planes.firstOrNull() ?: return@setOnImageAvailableListener
                val rowBytes = width * 4
                val pixels = ByteArray(rowBytes * height)
                val buffer = plane.buffer
                for (row in 0 until height) {
                    buffer.position(row * plane.rowStride)
                    buffer.get(pixels, row * rowBytes, rowBytes)
                }
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
                compositor.updateScreenFrame(bitmap)
            } finally {
                image.close()
            }
        }, handler)
        val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stopping) {
                    onStopped(null)
                    stop()
                }
            }
        }
        callback = projectionCallback
        projection.registerCallback(projectionCallback, handler)
        try {
            val metrics = DisplayMetrics()
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            display = projection.createVirtualDisplay(
                "Mobile Streaming Studio",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler,
            )
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    fun stop() {
        if (stopping) return
        stopping = true
        runCatching { display?.release() }
        display = null
        runCatching { reader?.close() }
        reader = null
        val current = projection
        projection = null
        if (current != null) {
            callback?.let { runCatching { current.unregisterCallback(it) } }
            runCatching { current.stop() }
        }
        callback = null
        thread?.quitSafely()
        thread = null
    }

    override fun close() = stop()
}
