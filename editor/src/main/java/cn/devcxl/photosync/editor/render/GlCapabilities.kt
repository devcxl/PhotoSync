package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import timber.log.Timber

/**
 * Runtime GPU capability probe and the 4-tier fallback matrix used by the editor preview.
 *
 * - Tier 1 `HALF_FLOAT_TARGETS`: `GL_RGBA16F` attachments are colour-renderable.
 * - Tier 2 `RGBA8_DITHERED_TARGETS`: no half-float attachment support, so intermediate targets fall
 *   back to `GL_RGBA8` and every pass dithers before quantization.
 * - Tier 3 `MINIMAL_FALLBACK`: a shader failed to compile or link, so Pass D is bypassed and the
 *   renderer keeps the geometry + basic tone pipeline on screen.
 *
 * Tier 4 (MediaPipe delegate) belongs to the portrait pipeline and is not part of the GL preview.
 */
internal object GlCapabilities {

    /** Rendering tier chosen at EGL context creation. */
    enum class Tier {
        HALF_FLOAT_TARGETS,
        RGBA8_DITHERED_TARGETS,
        MINIMAL_FALLBACK
    }

    private const val EXT_HALF_FLOAT_COLOR_BUFFER = "GL_EXT_color_buffer_half_float"
    private const val EXT_FLOAT_COLOR_BUFFER = "GL_EXT_color_buffer_float"
    private const val GLES_31_PREFIX = "OpenGL ES 3.1"
    private const val GLES_32_PREFIX = "OpenGL ES 3.2"

    private var halfFloatTargets: Boolean? = null

    @Volatile
    var tier: Tier = Tier.HALF_FLOAT_TARGETS
        private set

    /** True until [refresh] proves the driver cannot render into `GL_RGBA16F` attachments. */
    val supportsHalfFloatTargets: Boolean
        get() = halfFloatTargets ?: queryHalfFloatColorBufferSupport()

    /** True once a shader failed and the pipeline was reduced to geometry + basic tone. */
    val isMinimalFallback: Boolean
        get() = tier == Tier.MINIMAL_FALLBACK

    /**
     * Re-queries the driver and logs the selected tier. Must run on the GL thread right after the
     * EGL context is created.
     */
    fun refresh() {
        halfFloatTargets = queryHalfFloatColorBufferSupport()
        tier = if (halfFloatTargets == true) Tier.HALF_FLOAT_TARGETS else Tier.RGBA8_DITHERED_TARGETS
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
        val maxTextureSize = maxTextureSize()
        if (tier == Tier.HALF_FLOAT_TARGETS) {
            Timber.i("GlCapabilities: Tier 1 RGBA16F active, maxTex=%d, renderer=%s", maxTextureSize, renderer)
        } else {
            Timber.w("GlCapabilities: Tier 2 RGBA8 FBO fallback (no half_float FBO), renderer=%s", renderer)
        }
    }

    /**
     * Records a Tier 3 shader failure with the driver diagnostics the spec asks for, and disables
     * the optional effect passes so the preview never goes black.
     */
    fun markMinimalFallback(stage: String) {
        if (tier == Tier.MINIMAL_FALLBACK) return
        tier = Tier.MINIMAL_FALLBACK
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER).orEmpty()
        val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        Timber.e(
            "GlCapabilities: Tier 3 minimal fallback, %s failed on renderer=%s version=%s maxTex=%d",
            stage,
            renderer,
            version,
            maxTextureSize()
        )
    }

    /** Clears the cached probe so a recreated EGL context re-detects its capabilities. */
    fun invalidate() {
        halfFloatTargets = null
        tier = Tier.HALF_FLOAT_TARGETS
    }

    private fun maxTextureSize(): Int {
        val size = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, size, 0)
        return size[0]
    }

    private fun queryHalfFloatColorBufferSupport(): Boolean {
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        return extensions.contains(EXT_HALF_FLOAT_COLOR_BUFFER) ||
            extensions.contains(EXT_FLOAT_COLOR_BUFFER) ||
            version.contains(GLES_31_PREFIX) ||
            version.contains(GLES_32_PREFIX)
    }
}
