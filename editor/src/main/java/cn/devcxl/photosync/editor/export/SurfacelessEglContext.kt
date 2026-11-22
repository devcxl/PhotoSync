package cn.devcxl.photosync.editor.export

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20

import timber.log.Timber

/**
 * Headless EGL display/context for the bake exporter.
 *
 * Prefers `EGL_KHR_surfaceless_context` (no window, no pbuffer) and falls back to a `1 x 1` pbuffer,
 * so a bake never needs a `GLSurfaceView` or a visible window. All GL work must run on the thread
 * that calls [makeCurrent]; [close] tears the context down again.
 */
internal class SurfacelessEglContext private constructor(
    private val display: EGLDisplay,
    private val context: EGLContext,
    private val surface: EGLSurface
) : AutoCloseable {

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
        val applied = EGL14.eglMakeCurrent(display, surface, surface, context)
        if (!applied) {
            Timber.e("eglMakeCurrent failed: 0x%x", EGL14.eglGetError())
            return false
        }
        val size = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, size, 0)
        glMaxTextureSize = size[0]
        return glMaxTextureSize > 0
    }

    /** Releases the context, surface and display. Safe to call without a current context. */
    override fun close() {
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
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    companion object {

        /** `EGL_OPENGL_ES3_BIT_KHR`; not exposed by the framework's `EGL14` class. */
        private const val EGL_OPENGL_ES3_BIT = 0x40

        private const val EGL_PBUFFER_WIDTH = 16
        private const val EGL_PBUFFER_HEIGHT = 16

        /**
         * Creates a headless GLES 3 context (GLES 2 when the driver has no ES 3 config).
         *
         * @return The created context with `eglMakeCurrent` already applied, or `null`.
         */
        fun create(): SurfacelessEglContext? {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                Timber.e("eglGetDisplay returned no display")
                return null
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                Timber.e("eglInitialize failed: 0x%x", EGL14.eglGetError())
                return null
            }
            val config = chooseConfig(display, EGL_OPENGL_ES3_BIT)
                ?: chooseConfig(display, EGL14.EGL_OPENGL_ES2_BIT)
            if (config == null) {
                Timber.e("No EGL config supports an ES 2/3 pbuffer")
                EGL14.eglTerminate(display)
                return null
            }
            val context = createContext(display, config) ?: run {
                EGL14.eglTerminate(display)
                return null
            }
            val surfaceless = createSurfaceless(display, context)
            if (surfaceless != null) {
                return surfaceless
            }
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
                Timber.w("ES 3 context creation failed (0x%x); retrying with ES 2",
                    EGL14.eglGetError())
                val fallback = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                return EGL14.eglCreateContext(
                    display, config, EGL14.EGL_NO_CONTEXT, fallback, 0
                ).takeIf { it != EGL14.EGL_NO_CONTEXT }
            }
            return context
        }

        private fun createSurfaceless(
            display: EGLDisplay,
            context: EGLContext
        ): SurfacelessEglContext? {
            val extensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS).orEmpty()
            if (!extensions.contains("EGL_KHR_surfaceless_context")) {
                return null
            }
            val applied = EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, context
            )
            if (!applied) {
                Timber.w("Surfaceless makeCurrent failed: 0x%x", EGL14.eglGetError())
                return null
            }
            val created = SurfacelessEglContext(display, context, EGL14.EGL_NO_SURFACE)
            created.isSurfaceless = true
            if (!created.makeCurrent()) {
                created.close()
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
                Timber.e("eglCreatePbufferSurface failed: 0x%x", EGL14.eglGetError())
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
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
