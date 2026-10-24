package com.mobilestreaming.studio.streaming.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import com.mobilestreaming.studio.core.model.StreamConfig
import com.mobilestreaming.studio.streaming.compositor.SceneCompositor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Camera2Controller(
    private val context: Context,
    private val compositor: SceneCompositor,
    private val onError: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("camera2-controller")
    private val handler: Handler
    private val device = AtomicReference<CameraDevice?>()
    private val session = AtomicReference<CameraCaptureSession?>()
    private var requestBuilder: CaptureRequest.Builder? = null
    private var characteristics: CameraCharacteristics? = null
    private var activeConfig: StreamConfig? = null
    private var mirrorFrontCamera = true
    @Volatile private var stopping = false

    @Volatile var isFrontCamera: Boolean = false
        private set
    @Volatile var cameraId: String? = null
        private set

    init {
        thread.start()
        handler = Handler(thread.looper)
    }

    /** Blocking camera open; call from a worker coroutine, never the main thread. */
    @SuppressLint("MissingPermission")
    fun start(config: StreamConfig, front: Boolean, mirrorFront: Boolean = true) {
        stop()
        check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Camera permission is required"
        }
        activeConfig = config
        mirrorFrontCamera = mirrorFront
        stopping = false
        val selection = selectCamera(config, front)
        isFrontCamera = selection.isFront
        cameraId = selection.id
        characteristics = selection.characteristics
        compositor.setCameraInfo(isFrontCamera, selection.displayRotation, mirrorFrontCamera)
        compositor.setCameraBufferSize(selection.captureSize.width, selection.captureSize.height)
        compositor.awaitReady()

        val opened = CountDownLatch(1)
        val openError = AtomicReference<Throwable?>()
        manager.openCamera(selection.id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device.set(camera)
                opened.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                device.compareAndSet(camera, null)
                openError.compareAndSet(null, IllegalStateException("Camera disconnected"))
                opened.countDown()
                if (!stopping) onError(IllegalStateException("Camera disconnected"))
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                device.compareAndSet(camera, null)
                val failure = IllegalStateException("Camera error $error")
                openError.compareAndSet(null, failure)
                opened.countDown()
                if (!stopping) onError(failure)
            }
        }, handler)
        check(opened.await(8, TimeUnit.SECONDS)) { "Camera open timed out" }
        openError.get()?.let { throw it }
        val camera = device.get() ?: throw IllegalStateException("Camera did not open")
        try {
            createSession(camera, selection.fpsRange)
        } catch (error: Throwable) {
            stop()
            throw error
        }
    }

    fun switchCamera() {
        val config = activeConfig ?: return
        start(config, !isFrontCamera, mirrorFrontCamera)
    }

    fun setMirrorFrontCamera(mirror: Boolean) {
        mirrorFrontCamera = mirror
        val sensor = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        compositor.setCameraInfo(isFrontCamera, sensorRotation(sensor, isFrontCamera), mirror)
    }

    fun setZoomRatio(ratio: Float) {
        val current = characteristics ?: return
        val maxZoom = current.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val active = current.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val zoom = ratio.coerceIn(1f, max(1f, maxZoom))
        val cropWidth = (active.width() / zoom).toInt().coerceAtLeast(1)
        val cropHeight = (active.height() / zoom).toInt().coerceAtLeast(1)
        val left = active.centerX() - cropWidth / 2
        val top = active.centerY() - cropHeight / 2
        updateRequest { it.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + cropWidth, top + cropHeight)) }
    }

    fun setExposureCompensation(value: Int) {
        val range = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return
        updateRequest { it.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, value.coerceIn(range.lower, range.upper)) }
    }

    fun setTorch(enabled: Boolean) {
        val hasFlash = characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        require(!enabled || hasFlash) { "Torch is not available on this camera" }
        updateRequest {
            it.set(CaptureRequest.FLASH_MODE, if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        }
    }

    fun focusAt(normalizedX: Float, normalizedY: Float) {
        val active = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val x = active.left + (active.width() * normalizedX.coerceIn(0f, 1f)).toInt()
        val y = active.top + (active.height() * normalizedY.coerceIn(0f, 1f)).toInt()
        val half = min(active.width(), active.height()) / 20
        val region = Rect(
            (x - half).coerceIn(active.left, active.right),
            (y - half).coerceIn(active.top, active.bottom),
            (x + half).coerceIn(active.left, active.right),
            (y + half).coerceIn(active.top, active.bottom),
        )
        val metering = MeteringRectangle(region, MeteringRectangle.METERING_WEIGHT_MAX)
        updateRequest {
            it.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            it.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(metering))
            it.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(metering))
            it.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        }
        handler.postDelayed({
            updateRequest {
                it.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                it.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
        }, 1_500)
    }

    fun supports(config: StreamConfig, front: Boolean): Boolean = runCatching { selectCamera(config, front) }.isSuccess

    fun stop() {
        stopping = true
        runCatching { session.getAndSet(null)?.close() }
        runCatching { device.getAndSet(null)?.close() }
        requestBuilder = null
    }

    override fun close() {
        stop()
        thread.quitSafely()
    }

    private fun createSession(camera: CameraDevice, fpsRange: Range<Int>) {
        val target = compositor.cameraSurface
        val sessionReady = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        camera.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                session.set(captureSession)
                sessionReady.countDown()
            }

            override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                failure.set(IllegalStateException("Camera capture session configuration failed"))
                sessionReady.countDown()
            }
        }, handler)
        check(sessionReady.await(6, TimeUnit.SECONDS)) { "Camera session timed out" }
        failure.get()?.let { throw it }
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(target)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
            set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            )
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        }
        requestBuilder = builder
        session.get()?.setRepeatingRequest(builder.build(), null, handler)
            ?: throw IllegalStateException("Camera session unavailable")
    }

    private fun updateRequest(update: (CaptureRequest.Builder) -> Unit) {
        val builder = requestBuilder ?: return
        val captureSession = session.get() ?: return
        update(builder)
        runCatching { captureSession.setRepeatingRequest(builder.build(), null, handler) }.onFailure(onError)
    }

    private fun selectCamera(config: StreamConfig, front: Boolean): CameraSelection {
        val ids = manager.cameraIdList
        require(ids.isNotEmpty()) { "No camera is available" }
        val preferred = ids.filter {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        }
        val candidates = (preferred + ids).distinct()
        var lastFailure: Throwable? = null
        for (id in candidates) {
            try {
                val current = manager.getCameraCharacteristics(id)
                val map = current.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: throw IllegalStateException("Camera has no stream configuration")
                val sensor = current.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                val isFront = current.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
                val rotation = sensorRotation(sensor, isFront)
                val swapped = rotation % 180 != 0
                val targetWidth = if (swapped) config.height else config.width
                val targetHeight = if (swapped) config.width else config.height
                val size = chooseSize(map, targetWidth, targetHeight)
                val fps = chooseFpsRange(current, config.frameRate)
                val minimumDuration = map.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
                if (minimumDuration > 0) {
                    require(1_000_000_000.0 / minimumDuration >= config.frameRate - 0.1) {
                        "Selected camera resolution cannot sustain ${config.frameRate} FPS"
                    }
                }
                return CameraSelection(id, current, isFront, size, fps, rotation)
            } catch (error: Throwable) { lastFailure = error }
        }
        throw IllegalStateException("No camera supports ${config.width}×${config.height} at ${config.frameRate} FPS", lastFailure)
    }

    private fun chooseSize(map: StreamConfigurationMap, targetWidth: Int, targetHeight: Int): Size {
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        require(sizes.isNotEmpty()) { "Camera does not support SurfaceTexture output" }
        val targetAspect = targetWidth.toDouble() / targetHeight
        val matchingAspect = sizes.filter { abs(it.width.toDouble() / it.height - targetAspect) < 0.025 }
        val candidates = matchingAspect.ifEmpty { sizes }
        return candidates.filter { it.width >= targetWidth && it.height >= targetHeight }
            .minByOrNull { it.width.toLong() * it.height }
            ?: candidates.minByOrNull {
                abs(it.width.toLong() * it.height - targetWidth.toLong() * targetHeight)
            } ?: throw IllegalStateException("No usable camera resolution")
    }

    private fun chooseFpsRange(current: CameraCharacteristics, targetFps: Int): Range<Int> {
        val ranges = current.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        require(ranges.isNotEmpty()) { "Camera has no supported frame-rate ranges" }
        return ranges.filter { it.upper >= targetFps && it.lower <= targetFps }
            .minWithOrNull(compareBy<Range<Int>> { it.upper - it.lower }.thenBy { abs(it.upper - targetFps) })
            ?: throw IllegalStateException("Camera cannot sustain $targetFps FPS")
    }

    private fun sensorRotation(sensorOrientation: Int, front: Boolean): Int {
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.rotation
        val deviceDegrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (front) (sensorOrientation + deviceDegrees) % 360
        else (sensorOrientation - deviceDegrees + 360) % 360
    }

    private data class CameraSelection(
        val id: String,
        val characteristics: CameraCharacteristics,
        val isFront: Boolean,
        val captureSize: Size,
        val fpsRange: Range<Int>,
        val displayRotation: Int,
    )
}
