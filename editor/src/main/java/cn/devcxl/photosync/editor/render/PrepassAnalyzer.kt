package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

import android.opengl.GLES20
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.PrepassMath

/**
 * Owns the 1024px full-image Pre-pass of physical Pass C.
 *
 * The renderer renders the Post-`Pass A` + `Pass B` frame into the analyzer's `GL_RGBA8` target, and
 * this class then reads it back once per graph revision, runs [PrepassMath] on the CPU and publishes
 * the packed guide texture plus the global atmospheric light. Doing the analysis on the CPU keeps the
 * percentile selection (brightest `0.1%` of the dark channel) exact instead of approximating it in a
 * fragment shader.
 *
 * Because every offscreen FBO pass renders with [QuadOrientation.OFFSCREEN], row `0` of
 * [sourceTarget] is already the image top, so `glReadPixels` yields top-down rows directly without
 * needing a vertical flip.
 */
internal class PrepassAnalyzer(capabilities: GlCapabilities) {

    private val sourceTarget = OffscreenColorTarget(capabilities)
    private val texture = PrepassTexture()
    private var readbackBuffer: ByteBuffer? = null

    @Volatile
    private var analysedRevision: Int = Int.MIN_VALUE

    /** Global atmospheric light `A` of the analysed frame, each channel in `[0.1, 1]`. */
    var atmosphericLight: FloatArray = floatArrayOf(1.0f, 1.0f, 1.0f)
        private set

    /** True once a guide texture for the current graph revision is uploaded. */
    val isReady: Boolean
        get() = texture.isReady

    /** `uPrepassTex` texture name, or `0` before the first analysis. */
    val textureId: Int
        get() = texture.textureId

    /** Side of the analysis grid in the uploaded texture. */
    val gridSize: Int
        get() = texture.gridSize

    /** Forces the next [ensureAnalysis] call to re-run the Pre-pass. */
    fun invalidate() {
        analysedRevision = Int.MIN_VALUE
    }

    /**
     * Analyses the frame produced by [renderFrame] when [revision] differs from the analysed one.
     *
     * @param revision Monotonic graph revision; a new value means the graded frame changed.
     * @param aspect Post-crop aspect ratio of the frame to analyse.
     * @param allowHalfFloat Whether the driver accepts `GL_RGBA16F` uploads.
     * @param renderFrame Renders the framed image into the supplied target and returns success.
     * @return True when a guide texture for [revision] is available.
     */
    fun ensureAnalysis(
        revision: Int,
        aspect: Float,
        allowHalfFloat: Boolean,
        renderFrame: (OffscreenColorTarget) -> Boolean
    ): Boolean {
        if (revision == analysedRevision && texture.isReady) return true
        val (width, height) = fitWithin(MAX_EDGE_PX, aspect)
        sourceTarget.ensureSize(width, height, allowHalfFloat = false)
        if (!sourceTarget.isReady) return false
        if (!renderFrame(sourceTarget)) return false
        val display = readDisplayFrame(sourceTarget) ?: return false
        val analysis = PrepassMath.analyze(display, sourceTarget.width, sourceTarget.height)
        texture.upload(analysis, allowHalfFloat)
        if (!texture.isReady) return false
        atmosphericLight = analysis.atmosphericLight
        analysedRevision = revision
        return true
    }

    /** Releases the offscreen target and the guide texture. */
    fun release() {
        sourceTarget.release()
        texture.release()
        readbackBuffer = null
        invalidate()
    }

    private fun readDisplayFrame(target: OffscreenColorTarget): FloatArray? {
        val width = target.width
        val height = target.height
        if (width <= 0 || height <= 0) return null
        val requiredBytes = width * height * BYTES_PER_PIXEL
        val buffer = readbackBuffer?.takeIf { it.capacity() >= requiredBytes }
            ?: ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.nativeOrder())
                .also { readbackBuffer = it }
        buffer.rewind()
        target.bind()
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glReadPixels(
            0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
        )
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, DEFAULT_PACK_ALIGNMENT)
        target.unbind()
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Timber.w("Pre-pass read-back failed (glError=%d); Pass C keeps the previous guides", error)
            return null
        }
        return toTopDownRgb(buffer, width, height)
    }

    private fun toTopDownRgb(buffer: ByteBuffer, width: Int, height: Int): FloatArray {
        val display = FloatArray(width * height * CHANNELS)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val target = (row * width + column) * CHANNELS
                val source = (row * width + column) * BYTES_PER_PIXEL
                display[target] = byteAt(buffer, source)
                display[target + 1] = byteAt(buffer, source + 1)
                display[target + 2] = byteAt(buffer, source + 2)
            }
        }
        return display
    }

    private fun byteAt(buffer: ByteBuffer, index: Int): Float =
        (buffer.get(index).toInt() and BYTE_MASK) / BYTE_SCALE

    private companion object {
        private const val MAX_EDGE_PX = 1024
        private const val CHANNELS = 3
        private const val BYTES_PER_PIXEL = 4
        private const val DEFAULT_PACK_ALIGNMENT = 4
        private const val BYTE_MASK = 0xFF
        private const val BYTE_SCALE = 255.0f

        private fun fitWithin(maxEdge: Int, aspect: Float): Pair<Int, Int> {
            val safeAspect = if (aspect.isFinite() && aspect > 0.0f) aspect else 1.0f
            return if (safeAspect >= 1.0f) {
                maxEdge to (maxEdge / safeAspect).roundToInt().coerceAtLeast(1)
            } else {
                (maxEdge * safeAspect).roundToInt().coerceAtLeast(1) to maxEdge
            }
        }
    }
}
