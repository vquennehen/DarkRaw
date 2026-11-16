// File: app/src/main/java/com/darkroom/raw/gl/RawGlSurfaceView.kt
package com.darkroom.raw.gl

import android.content.Context
import android.opengl.GLSurfaceView
import com.darkroom.raw.model.DevelopParams
import java.nio.ByteBuffer

class RawGlSurfaceView(context: Context) : GLSurfaceView(context) {
    private val renderer: RawGlRenderer

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        renderer = RawGlRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setRawBuffer(buffer: ByteBuffer, width: Int, height: Int) {
        renderer.setRawImageBuffer(buffer, width, height)
        requestRender()
    }

    fun updateParams(params: DevelopParams) {
        renderer.updateParams(params)
        requestRender()
    }
}
