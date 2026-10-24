package com.mobilestreaming.studio.streaming.compositor

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView

/** A lightweight preview surface; rendering is owned by [SceneCompositor] on its GL thread. */
class ScenePreviewView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private var compositor: SceneCompositor? = null

    init {
        holder.addCallback(this)
        setZOrderOnTop(false)
    }

    fun bind(compositor: SceneCompositor) {
        if (this.compositor === compositor) return
        this.compositor?.detachPreview()
        this.compositor = compositor
        if (holder.surface?.isValid == true) {
            compositor.attachPreview(holder.surface, width, height)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        compositor?.attachPreview(holder.surface, width, height)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        compositor?.attachPreview(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        compositor?.detachPreview()
    }
}
