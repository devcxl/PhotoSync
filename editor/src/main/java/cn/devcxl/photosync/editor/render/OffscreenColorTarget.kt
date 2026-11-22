package cn.devcxl.photosync.editor.render

import kotlin.math.abs
import kotlin.math.roundToInt

import android.opengl.GLES20
import android.opengl.GLES30
import timber.log.Timber

/**
 * Reusable offscreen colour target used by every intermediate preview pass (Pass A geometry and the
 * Pass B staging target Pass D reads).
 *
 * Picks the highest available tier from the fallback matrix:
 * - Tier 1: `GL_RGBA16F` when `EXT_color_buffer_half_float` is complete.
 * - Tier 2: `GL_RGBA8` otherwise, where each pass dithers its own output.
 *
 * The attachment is only reallocated when the requested size drifts by more than
 * [SIZE_TOLERANCE_PX], so a live crop or straighten drag does not thrash GPU memory.
 */
internal class OffscreenColorTarget {

    var textureId: Int = 0
        private set

    var width: Int = 0
        private set

    var height: Int = 0
        private set

    var isHalfFloatTarget: Boolean = false
        private set

    private var framebufferId: Int = 0
    private var isDisabled: Boolean = false

    val isReady: Boolean
        get() = framebufferId != 0 && textureId != 0

    /**
     * Ensures an attachment of the requested pixel size exists.
     *
     * @param allowHalfFloat Whether Tier 1 `GL_RGBA16F` rendering is supported by the driver.
     * @return True when the target was (re)allocated, so the caller can redraw unconditionally.
     */
    fun ensureSize(width: Int, height: Int, allowHalfFloat: Boolean): Boolean {
        if (isDisabled) return false
        val targetWidth = width.coerceAtLeast(1)
        val targetHeight = height.coerceAtLeast(1)
        val sizeUnchanged = isReady &&
            abs(targetWidth - this.width) <= SIZE_TOLERANCE_PX &&
            abs(targetHeight - this.height) <= SIZE_TOLERANCE_PX
        if (sizeUnchanged && allowHalfFloat == isHalfFloatTarget) return false
        release()
        allocate(targetWidth, targetHeight, allowHalfFloat)
        return true
    }

    /**
     * Binds the framebuffer and points the viewport at the attachment.
     */
    fun bind() {
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES20.glViewport(0, 0, width, height)
    }

    /**
     * Restores the default framebuffer.
     */
    fun unbind() {
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * Releases every GL object owned by this target. Safe to call without a live context.
     */
    fun release() {
        if (framebufferId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
            framebufferId = 0
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        width = 0
        height = 0
    }

    /**
     * Re-arms the target after an EGL context recreation.
     */
    fun resetDisableFlag() {
        isDisabled = false
    }

    private fun allocate(targetWidth: Int, targetHeight: Int, allowHalfFloat: Boolean) {
        val useHalfFloat = allowHalfFloat && GlCapabilities.supportsHalfFloatTargets
        width = targetWidth
        height = targetHeight
        isHalfFloatTarget = useHalfFloat

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            if (useHalfFloat) GLES30.GL_RGBA16F else GLES30.GL_RGBA8,
            targetWidth,
            targetHeight,
            0,
            GLES20.GL_RGBA,
            if (useHalfFloat) GLES30.GL_HALF_FLOAT else GLES20.GL_UNSIGNED_BYTE,
            null
        )

        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        framebufferId = framebuffers[0]
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
        GLES20.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            textureId,
            0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Timber.e("Offscreen target framebuffer incomplete (status=%d); disabling the pass", status)
            isDisabled = true
            release()
        }
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    companion object {
        /** Longest edge of any preview working texture; keeps every device's FBO inside limits. */
        const val MAX_PREVIEW_EDGE_PX = 2048

        private const val SIZE_TOLERANCE_PX = 2

        /**
         * Computes the attachment size for a letterboxed image rect inside a viewport.
         */
        fun targetSizeFor(
            viewportWidth: Int,
            viewportHeight: Int,
            scaleX: Float,
            scaleY: Float,
            maxEdgePx: Int = MAX_PREVIEW_EDGE_PX
        ): Pair<Int, Int> {
            val rawWidth = (viewportWidth * scaleX).roundToInt().coerceAtLeast(1)
            val rawHeight = (viewportHeight * scaleY).roundToInt().coerceAtLeast(1)
            val longEdge = maxOf(rawWidth, rawHeight)
            if (longEdge <= maxEdgePx) return rawWidth to rawHeight
            val shrink = maxEdgePx.toFloat() / longEdge.toFloat()
            return ((rawWidth * shrink).roundToInt().coerceAtLeast(1)) to
                ((rawHeight * shrink).roundToInt().coerceAtLeast(1))
        }
    }
}
