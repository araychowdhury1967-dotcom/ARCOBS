package com.mobilestreaming.studio.streaming.overlay

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.mobilestreaming.studio.core.model.VisualSource
import com.mobilestreaming.studio.streaming.compositor.SceneCompositor

/** A restricted, low-frame-rate browser texture. No JavaScript interfaces or secrets are exposed. */
class BrowserOverlayController(
    private val compositor: SceneCompositor,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var sourceId: String? = null
    private var allowedOrigin: String? = null
    private var javaScriptEnabled = false
    private var capturing = false

    @SuppressLint("SetJavaScriptEnabled")
    fun update(source: VisualSource?) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Browser overlays must be updated on the main thread" }
        val validUrl = source?.browserUrlValidated()
        if (source == null || validUrl == null) {
            stopCapture()
            return
        }
        if (sourceId == source.id && allowedOrigin == originOf(validUrl) && javaScriptEnabled == source.browserJavaScript) return
        stopCapture()
        sourceId = source.id
        allowedOrigin = originOf(validUrl)
        javaScriptEnabled = source.browserJavaScript
        val view = WebView(compositorContext()).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(WebView.LAYER_TYPE_SOFTWARE, null)
            settings.apply {
                javaScriptEnabled = source.browserJavaScript
                domStorageEnabled = source.browserJavaScript
                allowContentAccess = false
                allowFileAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                cacheMode = WebSettings.LOAD_DEFAULT
                safeBrowsingEnabled = true
                setSupportZoom(false)
                builtInZoomControls = false
            }
            CookieManager.getInstance().setAcceptCookie(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.toOrigin() != allowedOrigin

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError,
                ) {
                    if (request.isForMainFrame) onError("Browser overlay failed to load (${error.errorCode})")
                }
            }
        }
        webView = view
        view.layout(0, 0, CAPTURE_WIDTH, CAPTURE_HEIGHT)
        view.loadUrl(validUrl)
        capturing = true
        handler.postDelayed(captureRunnable, CAPTURE_INTERVAL_MS)
    }

    private val captureRunnable = object : Runnable {
        override fun run() {
            val view = webView
            val id = sourceId
            if (!capturing || view == null || id == null) return
            runCatching {
                val bitmap = Bitmap.createBitmap(CAPTURE_WIDTH, CAPTURE_HEIGHT, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.TRANSPARENT)
                view.draw(Canvas(bitmap))
                compositor.updateBrowserFrame(id, bitmap)
            }.onFailure { onError("Browser overlay capture failed") }
            handler.postDelayed(this, CAPTURE_INTERVAL_MS)
        }
    }

    fun stopCapture() {
        capturing = false
        handler.removeCallbacks(captureRunnable)
        webView?.let { view ->
            view.stopLoading()
            view.clearHistory()
            view.removeAllViews()
            view.destroy()
        }
        webView = null
        sourceId = null
        allowedOrigin = null
        javaScriptEnabled = false
    }

    override fun close() = stopCapture()

    // The compositor owns an application context; this context is supplied through its preview surface's context.
    private fun compositorContext() = compositor.previewContext

    private fun originOf(url: String): String = Uri.parse(url).toOrigin()

    private fun Uri.toOrigin(): String = buildString {
        append(scheme).append("://").append(host)
        if (port > 0) append(':').append(port)
    }

    companion object {
        private const val CAPTURE_WIDTH = 960
        private const val CAPTURE_HEIGHT = 540
        private const val CAPTURE_INTERVAL_MS = 500L
    }
}
