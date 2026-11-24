package cn.devcxl.photosync.editor.export

import java.util.concurrent.atomic.AtomicBoolean

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20

import timber.log.Timber

import cn.devcxl.photosync.editor.render.SharedEglDisplay

/**
 * Headless EGL context for the bake exporter and the offscreen preview renderer.
 *
 * Prefers `EGL_KHR_surfaceless_context` (no window, no pbuffer) and falls back to a `1 x 1` pbuffer,
 * so a headless render never needs a `GLSurfaceView` or a visible window. All GL work must run on
 * the thread that calls [makeCurrent]; [close] tears this context down again.
 *
 * ## Coexistence with the editor canvas
 * The context lives on the process default display, which the editor's `GLSurfaceView` also uses and
 * may terminate at any time. [close] therefore destroys only this context and its surface — it never
 * calls `eglTerminate`, which would invalidate the live canvas — and the loss of the shared display
 * shows up as a failed [makeCurrent] or an unclean GL error queue, which every caller treats as
 * "GPU unavailable" and answers with the CPU renderer.
 *
 * [eglReleaseThread] is called on [close]: it releases only the calling thread's EGL state and does
 * not affect the display's initialization.
 */
internal class SurfacelessEglContext private constructor(
    private val display: EGLDisplay,
    private val context: EGLContext,
    private val surface: EGLSurface
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    /** `GL_MAX_TEXTURE_SIZE` of the export context. */
    var glMaxTextureSize: Int = 0
        private set

    /** True when the context came up without any EGL surface. */
    var isSurfaceless: Boolean = false
        private set

    /**
     * Makes the context current on the calling thread and caches driver limits.
     *
     * @return True when the context is usable.
     */
    fun makeCurrent(): Boolean {
        if (closed.get()) return false
        val applied = EGL14.eglMakeCurrent(display, surface, surface, context)
        if (!applied) {
            Timber.w("eglMakeCurrent failed: 0x%x", EGL14.eglGetError())
            return false
        }
        val size = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, size, 0)
        glMaxTextureSize = size[0]
        return glMaxTextureSize > 0
    }

    /**
     * True while this exact context is current on the calling thread.
     *
     * Checked around every offscreen pass: a `GLSurfaceView` that terminates the shared display
     * invalidates the context even though the handle is still returned by [EGL14.eglGetCurrentContext].
     * A `false` answer means "do not publish anything".
     */
    fun isCurrentOnThisThread(): Boolean =
        !closed.get() && EGL14.eglGetCurrentContext() == context && context != EGL14.EGL_NO_CONTEXT

    /**
     * Destroys this context and its surface; the shared display stays alive.
     *
     * Idempotent, and safe without a current context.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT
        )
        if (surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, surface)
        }
        EGL14.eglDestroyContext(display, context)
        // Per-thread EGL state only; the display connection and the canvas context are untouched.
        EGL14.eglReleaseThread()
    }

    companion object {

        /** `EGL_OPENGL_ES3_BIT_KHR`; not exposed by the framework's `EGL14` class. */
        private const val EGL_OPENGL_ES3_BIT = 0x40

        private const val EGL_PBUFFER_WIDTH = 16
        private const val EGL_PBUFFER_HEIGHT = 16

        /**
         * Creates a headless GLES 3 context (GLES 2 when the driver has no ES 3 config).
         *
         * @return The created context with `eglMakeCurrent` already applied, or `null` when EGL
         *   cannot provide a usable context on this thread.
         */
        fun create(): SurfacelessEglContext? {
            val display = SharedEglDisplay.acquire() ?: return null
            val config = chooseConfig(display, EGL_OPENGL_ES3_BIT)
                ?: chooseConfig(display, EGL14.EGL_OPENGL_ES2_BIT)
            if (config == null) {
                Timber.w("No EGL config supports an ES 2/3 pbuffer")
                return null
            }
            val context = createContext(display, config) ?: return null
            createSurfaceless(display, context)?.let { return it }
            // The surfaceless attempt left the context intact, so the same context is retried on a
            // pbuffer instead of creating a second one.
            return createWithPbuffer(display, config, context)
        }

        private fun chooseConfig(display: EGLDisplay, renderableType: Int): EGLConfig? {
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE,
                renderableType,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_DEPTH_SIZE,
                0,
                EGL14.EGL_STENCIL_SIZE,
                0,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val found = EGL14.eglChooseConfig(
                display, attributes, 0, configs, 0, 1, count, 0
            )
            return if (found && count[0] > 0) configs[0] else null
        }

        private fun createContext(display: EGLDisplay, config: EGLConfig): EGLContext? {
            val attributes = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION,
                3,
                EGL14.EGL_NONE
            )
            val context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, attributes, 0
            )
            if (context == EGL14.EGL_NO_CONTEXT) {
                Timber.w(
                    "ES 3 context creation failed (0x%x); retrying with ES 2",
                    EGL14.eglGetError()
                )
                val fallback = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                val es2 = EGL14.eglCreateContext(
                    display, config, EGL14.EGL_NO_CONTEXT, fallback, 0
                )
                if (es2 == EGL14.EGL_NO_CONTEXT) {
                    Timber.w("No headless EGL context could be created")
                    return null
                }
                return es2
            }
            return context
        }

        /**
         * Binds [context] without any surface.
         *
         * @return The usable context, or `null` when the extension is missing or binding failed.
         *   On `null` [context] is left **alive** so `create` can retry it on a pbuffer.
         */
        private fun createSurfaceless(
            display: EGLDisplay,
            context: EGLContext
        ): SurfacelessEglContext? {
            val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
            if (!extensions.contains("EGL_KHR_surfaceless_context")) {
                return null
            }
            val created = SurfacelessEglContext(display, context, EGL14.EGL_NO_SURFACE)
            created.isSurfaceless = true
            if (!created.makeCurrent()) {
                // Do not close: the context is still valid and the caller retries it on a pbuffer.
                Timber.w("Surfaceless binding failed; retrying on a pbuffer")
                return null
            }
            return created
        }

        private fun createWithPbuffer(
            display: EGLDisplay,
            config: EGLConfig,
            context: EGLContext
        ): SurfacelessEglContext? {
            val attributes = intArrayOf(
                EGL14.EGL_WIDTH,
                EGL_PBUFFER_WIDTH,
                EGL14.EGL_HEIGHT,
                EGL_PBUFFER_HEIGHT,
                EGL14.EGL_NONE
            )
            val surface = EGL14.eglCreatePbufferSurface(display, config, attributes, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                Timber.w("eglCreatePbufferSurface failed: 0x%x", EGL14.eglGetError())
                EGL14.eglDestroyContext(display, context)
                return null
            }
            val created = SurfacelessEglContext(display, context, surface)
            if (!created.makeCurrent()) {
                created.close()
                return null
            }
            return created
        }
    }
}
