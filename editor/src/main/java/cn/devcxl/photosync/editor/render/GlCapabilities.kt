package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import timber.log.Timber

/**
 * The driver strings sampled from exactly one EGL context.
 *
 * GL capability answers are a property of the context that produced them: the preview's
 * `GLSurfaceView` context, a headless bake context and a context recreated after surface loss can
 * report different extension lists, limits and shader compilers even inside one process.
 */
internal data class GlDriverInfo(
    val renderer: String,
    val version: String,
    val extensions: String,
    val maxTextureSize: Int
) {

    /** True when this driver can use `GL_RGBA16F` colour attachments. */
    val supportsHalfFloatColorBuffer: Boolean
        get() = extensions.contains(EXT_HALF_FLOAT_COLOR_BUFFER) ||
            extensions.contains(EXT_FLOAT_COLOR_BUFFER) ||
            version.contains(GLES_31_PREFIX) ||
            version.contains(GLES_32_PREFIX)

    companion object {
        private const val EXT_HALF_FLOAT_COLOR_BUFFER = "GL_EXT_color_buffer_half_float"
        private const val EXT_FLOAT_COLOR_BUFFER = "GL_EXT_color_buffer_float"
        private const val GLES_31_PREFIX = "OpenGL ES 3.1"
        private const val GLES_32_PREFIX = "OpenGL ES 3.2"

        /**
         * Reads the driver of the EGL context that is current on the calling thread.
         *
         * Must run on the thread that owns the context, right after it was made current.
         */
        fun readCurrent(): GlDriverInfo {
            val maxTextureSize = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
            return GlDriverInfo(
                renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty(),
                version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty(),
                extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty(),
                maxTextureSize = maxTextureSize[0]
            )
        }
    }
}

/**
 * Capability probe and fallback tier of **one** EGL context.
 *
 * Fallback matrix (Tier 4, the MediaPipe delegate, belongs to the portrait pipeline and is not part
 * of the GL preview):
 * - Tier 1 `HALF_FLOAT_TARGETS`: `GL_RGBA16F` attachments are colour-renderable.
 * - Tier 2 `RGBA8_DITHERED_TARGETS`: no half-float attachment support, so intermediate targets fall
 *   back to `GL_RGBA8` and every pass dithers before quantization.
 * - Tier 3 `MINIMAL_FALLBACK`: a shader failed to compile or link, so Pass D is bypassed and the
 *   renderer keeps the geometry + basic tone pipeline on screen.
 *
 * Every object graph that owns GL objects creates exactly one instance and keeps it for the whole
 * life of its context: [PreviewPassPipeline] for the on-screen preview and `BakePassChain` for the
 * headless export context. Sharing a single process-wide state is wrong in both directions:
 *
 * - a shader that fails to compile on one context would raise Tier 3 for every other context, and
 * - a re-probe on a second context would silently clear that Tier 3 again, so the preview kept
 *   drawing with a program that never linked.
 *
 * The same applies to the FBO format: `GL_RGBA16F` attachments are probed per context, and a driver
 * that rejects one gets downgraded to `GL_RGBA8` targets for that context only.
 *
 * ## Threading and lifecycle
 * The state is written only from the owning GL thread inside `init`/`refresh`/draw calls, so the
 * public getters are safe to read from that thread. [refresh] must be called once per context
 * creation, **before** any shader is compiled, because a new context recompiles every program and
 * therefore starts from a clean tier. [reset] drops every fact so a recreated context re-detects its
 * own capabilities instead of inheriting the previous ones.
 */
internal class GlCapabilities {

    /** Rendering tier chosen at EGL context creation. */
    enum class Tier {
        HALF_FLOAT_TARGETS,
        RGBA8_DITHERED_TARGETS,
        MINIMAL_FALLBACK
    }

    /** Driver of the last [refresh]; `null` while the context has not been probed yet. */
    private var driver: GlDriverInfo? = null

    /**
     * `null` until [refresh] probes the driver; `false` afterwards also means "this context already
     * rejected a `GL_RGBA16F` attachment".
     */
    @Volatile
    private var halfFloatColorBufferSupported: Boolean? = null

    /** Stage that failed to compile, or `null` while every pass is healthy. */
    @Volatile
    var minimalFallbackStage: String? = null
        private set

    /** Tier of the owning context; unprobed contexts are treated as the safe 8-bit tier. */
    val tier: Tier
        get() = when {
            minimalFallbackStage != null -> Tier.MINIMAL_FALLBACK
            halfFloatColorBufferSupported == true -> Tier.HALF_FLOAT_TARGETS
            else -> Tier.RGBA8_DITHERED_TARGETS
        }

    /** True only after this context proved `GL_RGBA16F` attachments are colour-renderable. */
    val supportsHalfFloatTargets: Boolean
        get() = halfFloatColorBufferSupported == true

    /** True once a shader failed and the pipeline was reduced to geometry + basic tone. */
    val isMinimalFallback: Boolean
        get() = minimalFallbackStage != null

    /**
     * True when this context may run Pass D.
     *
     * Pass D is the only stage that carries the AI composites (subject cut-out, background bokeh
     * mix, sky replacement) together with the 3D LUT, grain and vignette. The Tier 3 fallback
     * therefore drops all of them at once and keeps the basic geometry + tone pipeline, which is
     * the documented degradation row of the AI matrix: a missing shader must never leave a
     * half-composited frame on screen.
     */
    val allowsPassDEffects: Boolean
        get() = !isMinimalFallback

    /**
     * Re-probes the driver and logs the selected tier.
     *
     * Must run on the GL thread right after the EGL context is created and before any program is
     * compiled, so [markMinimalFallback] records failures on top of a fresh tier.
     *
     * @param driver Driver strings of the current context; tests pass a recorded snapshot.
     */
    fun refresh(driver: GlDriverInfo = GlDriverInfo.readCurrent()) {
        this.driver = driver
        halfFloatColorBufferSupported = driver.supportsHalfFloatColorBuffer
        minimalFallbackStage = null
        if (supportsHalfFloatTargets) {
            Timber.i(
                "GlCapabilities: Tier 1 RGBA16F active, maxTex=%d, renderer=%s",
                driver.maxTextureSize,
                driver.renderer
            )
        } else {
            Timber.w(
                "GlCapabilities: Tier 2 RGBA8 FBO fallback (no half_float FBO), renderer=%s",
                driver.renderer
            )
        }
    }

    /**
     * Downgrades this context to `GL_RGBA8` targets after a `GL_RGBA16F` attachment came back
     * incomplete, so the next allocation of this context uses a format the driver really supports.
     *
     * @param framebufferStatus The `glCheckFramebufferStatus` value that rejected the attachment.
     */
    fun markHalfFloatTargetsUnavailable(framebufferStatus: Int) {
        if (halfFloatColorBufferSupported == false) return
        halfFloatColorBufferSupported = false
        val current = driver
        Timber.w(
            "GlCapabilities: RGBA16F attachment rejected (status=%d) on renderer=%s version=%s; " +
                "switching this context to Tier 2 RGBA8 targets",
            framebufferStatus,
            current?.renderer ?: UNKNOWN_DRIVER,
            current?.version ?: UNKNOWN_DRIVER
        )
    }

    /**
     * Records a Tier 3 shader failure with the driver diagnostics the spec asks for, and disables
     * the optional effect passes so the preview never goes black.
     *
     * The first failure wins: it is the root cause, later stages usually fail because of it.
     */
    fun markMinimalFallback(stage: String) {
        if (minimalFallbackStage != null) return
        minimalFallbackStage = stage
        val current = driver
        Timber.e(
            "GlCapabilities: Tier 3 minimal fallback, %s failed on renderer=%s version=%s maxTex=%d",
            stage,
            current?.renderer ?: UNKNOWN_DRIVER,
            current?.version ?: UNKNOWN_DRIVER,
            current?.maxTextureSize ?: 0
        )
    }

    /** Clears every fact so a recreated EGL context re-detects its capabilities. */
    fun reset() {
        driver = null
        halfFloatColorBufferSupported = null
        minimalFallbackStage = null
    }

    private companion object {
        /** Placeholder used when a failure is recorded before the context was probed. */
        const val UNKNOWN_DRIVER = "unknown"
    }
}
