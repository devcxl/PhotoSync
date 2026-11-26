package cn.devcxl.photosync.editor.render

import android.opengl.GLES20

import timber.log.Timber

/**
 * Owns the GL error queue contract of one offscreen render.
 *
 * Every chain starts by draining stale errors, so a failure reported later belongs to this render
 * instead of a previous context owner, and every stage ends by asking for a clean queue. The queue is
 * the only portable signal that a draw silently did nothing.
 */
internal object GlErrorGuard {

    /** `GL_CONTEXT_LOST`; `GLES20` does not expose it and the value is context-loss specific. */
    private const val GL_CONTEXT_LOST_ERROR = 0x0505

    /** Drains every pending error, typically once before the first draw of a render. */
    fun reset() {
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            // A previous owner's errors are not ours to report.
        }
    }

    /**
     * Returns true when the error queue is clean; logs and returns false otherwise.
     *
     * @param stage Human-readable stage name used in the log line and the caller's failure reason.
     */
    fun clear(stage: String): Boolean {
        val error = GLES20.glGetError()
        if (error == GLES20.GL_NO_ERROR) return true
        if (error == GL_CONTEXT_LOST_ERROR) {
            return previewUnavailable("the GL context was lost during $stage")
        }
        Timber.w("Offscreen preview: GL error 0x%x after %s", error, stage)
        return false
    }
}
