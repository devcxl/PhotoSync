package cn.devcxl.photosync.editor.render

import android.opengl.EGL14
import android.opengl.EGLDisplay

import timber.log.Timber

/**
 * Acquisition of the default EGL display shared with the editor's `GLSurfaceView`.
 *
 * ## Why nothing is cached
 * `eglInitialize` on an already initialized display connection simply returns its version numbers
 * (Khronos EGL reference: "Initializing an already initialized EGL display connection has no effect
 * besides returning version numbers"), so there is no reference count to keep. A `GLSurfaceView` may
 * call `eglTerminate` on the same default display at any time — destroying every context created on
 * it — so a cached "already initialized" flag would be a stale assumption. [acquire] therefore
 * re-validates the display on every call, and a failure is reported as "no headless context" so the
 * caller falls back to the CPU renderer.
 *
 * ## Why this never terminates the display
 * `eglTerminate` marks every context and surface on the display for deletion, including the live
 * editor canvas. Headless consumers must only destroy what they created (their own context and
 * surface) and leave the display connection alone.
 *
 * ## Threading
 * Safe to call from any thread. A `GLSurfaceView` teardown racing an acquisition can still terminate
 * the display between [acquire] and `eglCreateContext`; that surfaces as a failed creation, never as
 * a silently broken context, and is treated as "GPU unavailable" by every caller.
 */
internal object SharedEglDisplay {

    /**
     * Returns the default display with a validated connection, or `null` when EGL cannot provide one.
     *
     * The returned handle is not exclusive: other components of the process hold the same display.
     */
    fun acquire(): EGLDisplay? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            Timber.e("eglGetDisplay returned no display")
            return null
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            // Expected when the canvas just terminated the shared display; the caller falls back.
            Timber.w("eglInitialize failed (0x%x); no headless GL context", EGL14.eglGetError())
            return null
        }
        return display
    }
}
