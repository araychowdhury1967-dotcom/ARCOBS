package com.mobilestreaming.studio.streaming.compositor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.mobilestreaming.studio.core.model.Scene
import com.mobilestreaming.studio.core.model.SourceKind
import com.mobilestreaming.studio.core.model.VisualSource
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.sin

data class CompositorStats(
    val renderedFrames: Long,
    val droppedFrames: Long,
)

/** Shared GPU scene renderer for preview and the encoder input surface. */
class SceneCompositor(
    context: Context,
    initialScene: Scene,
    initialWidth: Int = 1280,
    initialHeight: Int = 720,
    initialFrameRate: Int = 30,
    private val onError: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val bitmapRenderer = BitmapSourceRenderer(appContext)
    private val thread = HandlerThread("scene-compositor")
    private val handler: Handler
    private val ready = CompletableFuture<Unit>()
    private val frameSequence = AtomicInteger()

    @Volatile private var scene = initialScene
    @Volatile private var outputWidth = initialWidth
    @Volatile private var outputHeight = initialHeight
    @Volatile private var frameRate = initialFrameRate
    @Volatile private var released = false
    @Volatile private var framePending = false
    @Volatile private var cameraSurfaceInternal: Surface? = null
    @Volatile private var encoderFrameCallback: (() -> Unit)? = null
    @Volatile private var screenFrame: Frame? = null
    @Volatile private var frontCamera = false
    @Volatile private var mirrorFrontCamera = true
    @Volatile private var cameraRotation = 0f
    @Volatile private var cameraBufferWidth = initialWidth
    @Volatile private var cameraBufferHeight = initialHeight

    @Volatile var renderedFrames: Long = 0
        private set
    @Volatile var droppedFrames: Long = 0
        private set

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var dummySurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraTextureId = 0
    private var solidTextureId = 0
    private var externalProgram = 0
    private var textureProgram = 0
    private val outputs = mutableMapOf<String, Output>()
    private val textures = mutableMapOf<String, GlTexture>()
    private val browserFrames = java.util.concurrent.ConcurrentHashMap<String, Frame>()
    private val uploadedBrowserFrames = mutableMapOf<String, Int>()
    private var uploadedScreenFrame = -1
    private val vertexBuffer = floatBuffer(8)
    private val textureBuffer = floatBuffer(8)
    private val surfaceTextureMatrix = FloatArray(16)
    private val orientationMatrix = FloatArray(16)
    private val cameraTextureMatrix = FloatArray(16)
    private val identityMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    val previewContext: Context get() = appContext

    val cameraSurface: Surface
        get() = cameraSurfaceInternal ?: throw IllegalStateException("Scene compositor is not ready")

    init {
        thread.start()
        handler = Handler(thread.looper)
        handler.post(::initialize)
        handler.post(::renderLoop)
    }

    fun awaitReady(timeoutMs: Long = 5_000) = ready.get(timeoutMs, TimeUnit.MILLISECONDS)

    fun updateScene(scene: Scene) {
        this.scene = scene
        if (!released) handler.post { pruneTextures() }
    }

    fun reconfigure(width: Int, height: Int, fps: Int) {
        handler.post {
            check(outputs.keys.none { it.startsWith("encoder") }) { "Stop encoding before changing output format" }
            outputWidth = width
            outputHeight = height
            frameRate = fps
            surfaceTexture?.setDefaultBufferSize(width, height)
        }
    }

    fun setCameraInfo(isFront: Boolean, rotationDegrees: Int, mirror: Boolean) {
        frontCamera = isFront
        cameraRotation = rotationDegrees.toFloat()
        mirrorFrontCamera = mirror
    }

    fun setCameraBufferSize(width: Int, height: Int) {
        post {
            cameraBufferWidth = width
            cameraBufferHeight = height
            surfaceTexture?.setDefaultBufferSize(width, height)
        }
    }

    fun attachPreview(surface: Surface, width: Int, height: Int) {
        if (released || width <= 0 || height <= 0) return
        post {
            replaceOutput("preview", surface, width, height, false)
        }
    }

    fun detachPreview() = post { removeOutput("preview") }

    fun attachEncoder(surface: Surface, key: String = "encoder") {
        check(surface.isValid) { "Encoder surface is invalid" }
        post {
            replaceOutput(key, surface, outputWidth, outputHeight, true)
        }
    }

    fun detachEncoder(key: String = "encoder") = post { removeOutput(key) }

    fun setEncoderFrameCallback(callback: (() -> Unit)?) {
        encoderFrameCallback = callback
    }

    fun updateScreenFrame(bitmap: Bitmap) {
        screenFrame = Frame(bitmap, frameSequence.incrementAndGet())
    }

    fun updateBrowserFrame(sourceId: String, bitmap: Bitmap) {
        browserFrames[sourceId] = Frame(bitmap, frameSequence.incrementAndGet())
    }

    fun stats() = CompositorStats(renderedFrames, droppedFrames)

    private fun initialize() {
        try {
            setupEgl()
            externalProgram = createProgram(VERTEX_SHADER, EXTERNAL_FRAGMENT_SHADER)
            textureProgram = createProgram(VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            cameraTextureId = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            solidTextureId = createTexture(GLES20.GL_TEXTURE_2D)
            val white = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, solidTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, white, 0)
            white.recycle()
            surfaceTexture = SurfaceTexture(cameraTextureId).also { texture ->
                texture.setDefaultBufferSize(outputWidth, outputHeight)
                texture.setOnFrameAvailableListener({
                    if (framePending) droppedFrames++ else framePending = true
                }, handler)
            }
            cameraSurfaceInternal = Surface(surfaceTexture)
            ready.complete(Unit)
        } catch (error: Throwable) {
            ready.completeExceptionally(error)
            onError(error)
        }
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "EGL display unavailable" }
        val versions = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, versions, 0, versions, 1)) { "EGL initialization failed" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val counts = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, counts, 0) && counts[0] > 0) {
            "No recordable OpenGL ES configuration"
        }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "EGL context creation failed" }
        dummySurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        makeCurrent(dummySurface)
    }

    private fun renderLoop() {
        if (released) return
        try {
            if (ready.isDone && !ready.isCompletedExceptionally && outputs.isNotEmpty()) render()
        } catch (error: Throwable) {
            onError(error)
        }
        handler.postDelayed(::renderLoop, (1000L / frameRate.coerceIn(1, 60)).coerceAtLeast(8L))
    }

    private fun render() {
        val texture = surfaceTexture ?: return
        if (framePending) {
            texture.updateTexImage()
            texture.getTransformMatrix(surfaceTextureMatrix)
            framePending = false
        }
        val timestampNs = System.nanoTime()
        val snapshot = scene
        val hasEncoderOutput = outputs.values.any { it.encoder }
        outputs.values.toList().forEach { output ->
            if (!makeCurrent(output.eglSurface)) {
                removeOutput(output.key)
                return@forEach
            }
            GLES20.glViewport(0, 0, output.width, output.height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            snapshot.sources.filter { it.visible }.forEach { source -> drawSource(source, timestampNs / 1_000_000) }
            if (output.encoder) EGLExt.eglPresentationTimeANDROID(eglDisplay, output.eglSurface, timestampNs)
            if (!EGL14.eglSwapBuffers(eglDisplay, output.eglSurface)) {
                removeOutput(output.key)
            } else {
                if (output.encoder || !hasEncoderOutput) renderedFrames++
                if (output.encoder) encoderFrameCallback?.invoke()
            }
        }
    }

    private fun drawSource(source: VisualSource, nowMs: Long) {
        when (source.kind) {
            SourceKind.CAMERA -> draw(
                source, cameraTextureId, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalProgram,
                cameraMatrixFor(source), Color.WHITE, externalCoordinates = true,
            )
            SourceKind.SOLID_COLOR -> draw(
                source, solidTextureId, GLES20.GL_TEXTURE_2D, textureProgram,
                identityMatrix, source.backgroundColor.toInt(), externalCoordinates = false,
            )
            SourceKind.SCREEN -> {
                uploadScreenFrame()
                val texture = textures[SCREEN_KEY] ?: return
                draw(source, texture.id, GLES20.GL_TEXTURE_2D, textureProgram, identityMatrix, Color.WHITE, false)
            }
            SourceKind.BROWSER -> {
                uploadBrowserFrame(source.id)
                val texture = textures[source.id] ?: return
                draw(source, texture.id, GLES20.GL_TEXTURE_2D, textureProgram, identityMatrix, Color.WHITE, false)
            }
            SourceKind.IMAGE, SourceKind.LOGO -> {
                val texture = textures.getOrPut(source.id) { GlTexture(createTexture(GLES20.GL_TEXTURE_2D)) }
                val uri = source.imageUri ?: return
                if (texture.version != uri.hashCode()) {
                    val bitmap = bitmapRenderer.decodeImage(uri) ?: return
                    uploadBitmap(texture.id, bitmap)
                    bitmap.recycle()
                    texture.version = uri.hashCode()
                }
                draw(source, texture.id, GLES20.GL_TEXTURE_2D, textureProgram, identityMatrix, Color.WHITE, false)
            }
            else -> {
                val texture = textures.getOrPut(source.id) { GlTexture(createTexture(GLES20.GL_TEXTURE_2D)) }
                val bucket = when (source.kind) {
                    SourceKind.TICKER -> nowMs / 40
                    SourceKind.CLOCK -> nowMs / 1_000
                    SourceKind.DATE -> nowMs / 60_000
                    else -> 0L
                }
                val version = 31 * source.hashCode() + bucket.hashCode()
                if (texture.version != version) {
                    val (width, height) = bitmapRenderer.bitmapSize(source, outputWidth, outputHeight)
                    val bitmap = bitmapRenderer.render(source, width, height, nowMs)
                    uploadBitmap(texture.id, bitmap)
                    bitmap.recycle()
                    texture.version = version
                }
                draw(source, texture.id, GLES20.GL_TEXTURE_2D, textureProgram, identityMatrix, Color.WHITE, false)
            }
        }
    }

    private fun cameraMatrixFor(source: VisualSource): FloatArray {
        Matrix.setIdentityM(orientationMatrix, 0)
        Matrix.translateM(orientationMatrix, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(orientationMatrix, 0, cameraRotation, 0f, 0f, 1f)
        val rotated = cameraRotation.toInt().let { (it % 180 + 180) % 180 != 0 }
        val bufferWidth = if (rotated) cameraBufferHeight else cameraBufferWidth
        val bufferHeight = if (rotated) cameraBufferWidth else cameraBufferHeight
        val sourceAspect = bufferWidth.toFloat() / bufferHeight
        val targetAspect = (source.transform.width * outputWidth) /
            (source.transform.height * outputHeight)
        if (sourceAspect > targetAspect) {
            Matrix.scaleM(orientationMatrix, 0, targetAspect / sourceAspect, 1f, 1f)
        } else if (sourceAspect < targetAspect) {
            Matrix.scaleM(orientationMatrix, 0, 1f, sourceAspect / targetAspect, 1f)
        }
        Matrix.translateM(orientationMatrix, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(cameraTextureMatrix, 0, surfaceTextureMatrix, 0, orientationMatrix, 0)
        return cameraTextureMatrix
    }

    private fun uploadScreenFrame() {
        val frame = screenFrame ?: return
        if (uploadedScreenFrame == frame.version) return
        val texture = textures.getOrPut(SCREEN_KEY) { GlTexture(createTexture(GLES20.GL_TEXTURE_2D)) }
        uploadBitmap(texture.id, frame.bitmap)
        uploadedScreenFrame = frame.version
    }

    private fun uploadBrowserFrame(sourceId: String) {
        val frame = browserFrames[sourceId] ?: return
        if (uploadedBrowserFrames[sourceId] == frame.version) return
        val texture = textures.getOrPut(sourceId) { GlTexture(createTexture(GLES20.GL_TEXTURE_2D)) }
        uploadBitmap(texture.id, frame.bitmap)
        uploadedBrowserFrames[sourceId] = frame.version
    }

    private fun draw(
        source: VisualSource,
        textureId: Int,
        textureTarget: Int,
        program: Int,
        textureMatrix: FloatArray,
        tint: Int,
        externalCoordinates: Boolean,
    ) {
        val transform = source.transform.constrained()
        val positions = floatArrayOf(-1f, 1f, -1f, -1f, 1f, 1f, 1f, -1f) // BL, TL, BR, TR in local canvas space.
        val angle = Math.toRadians(transform.rotationDegrees.toDouble())
        val cosine = cos(angle).toFloat()
        val sine = sin(angle).toFloat()
        val aspect = outputWidth.toFloat() / outputHeight
        for (index in 0 until 4) {
            val localX = positions[index * 2] * transform.width / 2f
            val localY = positions[index * 2 + 1] * transform.height / 2f
            val aspectX = localX * aspect
            val rotatedX = (aspectX * cosine - localY * sine) / aspect
            val rotatedY = aspectX * sine + localY * cosine
            vertexBuffer.put(index * 2, (transform.centerX + rotatedX) * 2f - 1f)
            vertexBuffer.put(index * 2 + 1, 1f - (transform.centerY + rotatedY) * 2f)
        }
        val crop = source.crop.constrained()
        val left = crop.left
        val right = 1f - crop.right
        val top = crop.top
        val bottom = 1f - crop.bottom
        val mirror = source.mirrored xor (source.kind == SourceKind.CAMERA && frontCamera && mirrorFrontCamera)
        val uLeft = if (mirror) right else left
        val uRight = if (mirror) left else right
        val coordinates = if (externalCoordinates) floatArrayOf(
            uLeft, top, uLeft, bottom, uRight, top, uRight, bottom,
        ) else floatArrayOf(
            uLeft, bottom, uLeft, top, uRight, bottom, uRight, top,
        )
        textureBuffer.clear()
        textureBuffer.put(coordinates).position(0)
        vertexBuffer.position(0)

        GLES20.glUseProgram(program)
        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val coordinateHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(coordinateHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glVertexAttribPointer(coordinateHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTextureMatrix"), 1, false, textureMatrix, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        val opacity = source.opacity.coerceIn(0f, 1f)
        if (source.kind == SourceKind.SOLID_COLOR) {
            val alpha = Color.alpha(tint) / 255f * opacity
            GLES20.glUniform4f(
                GLES20.glGetUniformLocation(program, "uColor"),
                Color.red(tint) / 255f * alpha,
                Color.green(tint) / 255f * alpha,
                Color.blue(tint) / 255f * alpha,
                alpha,
            )
        } else {
            GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uColor"), opacity, opacity, opacity, opacity)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(textureTarget, textureId)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(coordinateHandle)
    }

    private fun replaceOutput(key: String, surface: Surface, width: Int, height: Int, encoder: Boolean) {
        removeOutput(key)
        val config = chooseCurrentConfig()
        val eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, surface, intArrayOf(EGL14.EGL_NONE), 0,
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            if (encoder) onError(IllegalStateException("Unable to attach encoder surface"))
            return
        }
        outputs[key] = Output(key, eglSurface, width, height, encoder)
    }

    private fun chooseCurrentConfig(): EGLConfig = eglConfig ?: throw IllegalStateException("EGL is not initialized")

    private fun removeOutput(key: String) {
        val output = outputs.remove(key) ?: return
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglDestroySurface(eglDisplay, output.eglSurface)
        if (outputs.isEmpty() && dummySurface != EGL14.EGL_NO_SURFACE) makeCurrent(dummySurface)
    }

    private fun makeCurrent(surface: EGLSurface): Boolean = EGL14.eglMakeCurrent(
        eglDisplay, surface, surface, eglContext,
    )

    private fun pruneTextures() {
        val activeIds = scene.sources.map { it.id }.toSet()
        val iterator = textures.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key != SCREEN_KEY && entry.key !in activeIds) {
                GLES20.glDeleteTextures(1, intArrayOf(entry.value.id), 0)
                iterator.remove()
            }
        }
        browserFrames.keys.retainAll(activeIds)
    }

    private fun createTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun uploadBitmap(textureId: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        check(status[0] != 0) { "OpenGL program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("OpenGL shader compilation failed: $log")
        }
        return shader
    }

    private fun post(block: () -> Unit) {
        if (!released) handler.post {
            if (!released) runCatching(block).onFailure(onError)
        }
    }

    override fun close() {
        if (released) return
        released = true
        val latch = java.util.concurrent.CountDownLatch(1)
        handler.post {
            textures.values.forEach { GLES20.glDeleteTextures(1, intArrayOf(it.id), 0) }
            textures.clear()
            outputs.keys.toList().forEach(::removeOutput)
            cameraSurfaceInternal?.release()
            cameraSurfaceInternal = null
            surfaceTexture?.release()
            surfaceTexture = null
            if (externalProgram != 0) GLES20.glDeleteProgram(externalProgram)
            if (textureProgram != 0) GLES20.glDeleteProgram(textureProgram)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                if (dummySurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, dummySurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            latch.countDown()
        }
        runCatching { latch.await(3, TimeUnit.SECONDS) }
        thread.quitSafely()
    }

    private data class Output(
        val key: String,
        val eglSurface: EGLSurface,
        val width: Int,
        val height: Int,
        val encoder: Boolean,
    )

    private data class GlTexture(val id: Int, var version: Int = 0)
    private data class Frame(val bitmap: Bitmap, val version: Int)

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val SCREEN_KEY = "__screen"
        private fun floatBuffer(size: Int): FloatBuffer = ByteBuffer.allocateDirect(size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTextureMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTextureMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """
        private const val EXTERNAL_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform vec4 uColor;
            varying vec2 vTexCoord;
            void main() { gl_FragColor = texture2D(uTexture, vTexCoord) * uColor; }
        """
        private const val TEXTURE_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec4 uColor;
            varying vec2 vTexCoord;
            void main() { gl_FragColor = texture2D(uTexture, vTexCoord) * uColor; }
        """
    }
}
