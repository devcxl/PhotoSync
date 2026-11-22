package cn.devcxl.photosync.editor.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent

import cn.devcxl.photosync.editor.render.EditorGlRenderer

/**
 * Configures the GLES preview surface: context version, render mode, and the press-and-hold
 * `Before` comparison gesture.
 */
internal object EditorCanvasSetup {

    private const val MIN_GLES_VERSION_ES3 = 0x30000

    /** True when the device exposes a GLES 3.0 context, which the linear RAW path needs. */
    fun supportsEs3(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return (manager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= MIN_GLES_VERSION_ES3
    }

    /**
     * Attaches [renderer] to [surfaceView] and routes long presses on the canvas to
     * [onCompareHold] (`true` while the finger is held down, `false` on release).
     */
    fun configure(
        context: Context,
        surfaceView: GLSurfaceView,
        renderer: EditorGlRenderer,
        supportsEs3: Boolean,
        onCompareHold: (Boolean) -> Unit
    ) {
        renderer.preferEs3 = supportsEs3
        surfaceView.setEGLContextClientVersion(if (supportsEs3) 3 else 2)
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        attachCompareGesture(context, surfaceView, renderer, onCompareHold)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachCompareGesture(
        context: Context,
        surfaceView: GLSurfaceView,
        renderer: EditorGlRenderer,
        onCompareHold: (Boolean) -> Unit
    ) {
        val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onLongPress(e: MotionEvent) {
                    onCompareHold(true)
                }
            }
        )
        surfaceView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            val released = event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            if (released && renderer.compareOriginal) {
                onCompareHold(false)
            }
            true
        }
    }
}
