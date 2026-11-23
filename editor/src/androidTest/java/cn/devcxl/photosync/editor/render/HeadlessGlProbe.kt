package cn.devcxl.photosync.editor.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20

/**
 * Independent capability probe for the offscreen GPU preview tests.
 *
 * The probe deliberately does **not** touch [SurfacelessEglContext], [OffscreenPreviewChain] or any
 * production shader. It answers one question only: does this device provide a headless EGL context
 * with OpenGL ES 3.x and a texture large enough for the preview frame?
 *
 * That separation is the point: shader compilation, FBO completeness, read-back and every other
 * stage of the production chain are *not* capability signals. Once this probe succeeds, a `null`
 * from the renderer is a regression the tests must report, never a reason to skip.
 *
 * The suite therefore requires a higher device class than the app itself: headless EGL (surfaceless
 * or a pbuffer fallback) plus GLES 3.x. Devices below that requirement skip explicitly; devices at
 * or above it must render every tested graph.
 */
internal object HeadlessGlProbe {

    /** Driver facts the preview tests need before they may assert a GPU render. */
    data class Capabilities(
        val glVersion: String,
        val maxTextureSize: Int,
        val surfaceless: Boolean
    ) {
        /** Diagnostic string used in failure messages. */
        val summary: String
            get() = "GL_VERSION=$glVersion, GL_MAX_TEXTURE_SIZE=$maxTextureSize, " +
                "surfaceless=$surfaceless"
    }

    /** `EGL_OPENGL_ES3_BIT_KHR`; not exposed by the framework's `EGL14` class. */
    private const val EGL_OPENGL_ES3_BIT = 0x40

    private const val PBUFFER_SIZE = 16

    private val cached: Capabilities? by lazy { probe() }

    /**
     * Returns the headless GLES 3 capabilities of this device, or `null` when the device does not
     * meet the suite requirement. Probed once per process.
     */
    fun detect(): Capabilities? = cached

    private fun probe(): Capabilities? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        // The display is intentionally never terminated: the production renderer and the editor's
        // GLSurfaceView share this default display, and terminating it would invalidate their
        // contexts. Only the objects created below are destroyed.
        // Prefer an ES3 renderable config, but also accept an ES2 config: some drivers advertise
        // only the ES2 bit yet create a GLES 3 context. The GL_VERSION check below is what actually
        // proves ES3 support.
        val config = chooseConfig(display, EGL_OPENGL_ES3_BIT)
            ?: chooseConfig(display, EGL14.EGL_OPENGL_ES2_BIT)
            ?: return null
        val context = createEs3Context(display, config) ?: return null
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            val surfaceless = supportsSurfacelessContext(display) &&
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    context
                )
            if (!surfaceless) {
                surface = createPbuffer(display, config) ?: return null
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            }
            val glVersion = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
            if (!glVersion.startsWith("OpenGL ES 3")) return null
            val maxTextureSize = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
            if (maxTextureSize[0] < OffscreenPreviewFrames.MAX_EDGE_PX) return null
            return Capabilities(
                glVersion = glVersion,
                maxTextureSize = maxTextureSize[0],
                surfaceless = surfaceless
            )
        } finally {
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
        }
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
        val found = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
        return if (found && count[0] > 0) configs[0] else null
    }

    private fun createEs3Context(display: EGLDisplay, config: EGLConfig): EGLContext? {
        val attributes = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION,
            3,
            EGL14.EGL_NONE
        )
        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            attributes,
            0
        )
        return context.takeIf { it != EGL14.EGL_NO_CONTEXT }
    }

    private fun createPbuffer(display: EGLDisplay, config: EGLConfig): EGLSurface? {
        val attributes = intArrayOf(
            EGL14.EGL_WIDTH,
            PBUFFER_SIZE,
            EGL14.EGL_HEIGHT,
            PBUFFER_SIZE,
            EGL14.EGL_NONE
        )
        val surface = EGL14.eglCreatePbufferSurface(display, config, attributes, 0)
        return surface.takeIf { it != EGL14.EGL_NO_SURFACE }
    }

    private fun supportsSurfacelessContext(display: EGLDisplay): Boolean =
        EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)
            .orEmpty()
            .contains("EGL_KHR_surfaceless_context")
}
