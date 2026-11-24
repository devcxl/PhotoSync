package cn.devcxl.photosync.editor.render

import android.graphics.Bitmap
import android.opengl.GLES20

import timber.log.Timber

import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Uploads the preview source into a texture and proves the upload actually happened.
 *
 * `GLUtils.texImage2D` reports nothing, and every later texture upload in the chain drains the GL
 * error queue before it starts, so a rejected source upload would otherwise stay invisible and the
 * frame would be rendered from an empty texture. This uploader therefore clears the queue before the
 * upload, checks it immediately afterwards, and deletes the half-created texture on any doubt.
 *
 * The GL entry points are injectable so the accept/reject contract is unit-testable on the JVM.
 */
internal class PreviewSourceUploader(
    private val uploadBitmap: (Bitmap, Int) -> Int = EditorTextureUploader::uploadBitmap,
    private val uploadLinearHalfFloat: (LinearRawBuffer, Int) -> Int =
        EditorTextureUploader::uploadLinearHalfFloat,
    private val releaseTexture: (Int) -> Unit = EditorTextureUploader::releaseTexture,
    private val readGlError: () -> Int = { GLES20.glGetError() },
    private val clearGlErrors: () -> Unit = {
        @Suppress("ControlFlowWithEmptyBody")
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            // Drain stale errors so the next check belongs to this upload.
        }
    }
) {

    /**
     * Uploads [source].
     *
     * @param fullFrame True for the full-preview texture, false for the 1024px Pre-pass proxy.
     * @return The texture id, or `null` when the driver rejected the upload (nothing leaks).
     */
    fun upload(source: EditorSource, fullFrame: Boolean): Int? {
        clearGlErrors()
        val textureId = when (source) {
            is EditorSource.BitmapSource -> {
                val bitmap = if (fullFrame) source.full else source.proxy
                uploadBitmap(bitmap, 0)
            }
            is EditorSource.LinearRawSource -> {
                // Only the half-float upload is accepted: the RGBA8 fallback quantizes scene-linear
                // light without the sRGB transfer the CPU renderer applies, which would change the
                // preview instead of merely lowering its precision.
                val buffer = if (fullFrame) source.full else source.proxy
                uploadLinearHalfFloat(buffer, 0)
            }
        }
        if (textureId == 0) {
            previewUnavailable("the ${if (fullFrame) "full" else "proxy"} source could not be uploaded")
            return null
        }
        val error = readGlError()
        if (error != GLES20.GL_NO_ERROR) {
            Timber.w(
                "Offscreen preview: source upload raised GL error 0x%x; using the CPU renderer",
                error
            )
            releaseTexture(textureId)
            return null
        }
        return textureId
    }
}
