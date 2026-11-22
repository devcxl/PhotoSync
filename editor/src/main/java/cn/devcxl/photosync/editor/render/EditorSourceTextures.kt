package cn.devcxl.photosync.editor.render

import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * Owns the editor's full-preview and drag-proxy source textures.
 *
 * Linear RAW buffers are uploaded straight from their off-heap `RGBA16F` bytes so no JVM heap copy
 * of the image is ever produced; drivers that reject `GL_RGBA16F` fall back to 8-bit `GL_RGBA`
 * texels (Tier 2), which costs shadow precision but keeps the editor usable. Uploads are deferred to
 * the GL thread through [uploadPending] so publishing a new source from the UI thread stays cheap.
 */
internal class EditorSourceTextures {

    private var staged: EditorSource? = null
    private var fullTextureId: Int = 0
    private var proxyTextureId: Int = 0
    private var needsFull: Boolean = false
    private var needsProxy: Boolean = false

    /** Texture the renderer should sample for this frame, or `0` when nothing is uploaded yet. */
    fun activeTexture(useFastProxy: Boolean): Int =
        if (useFastProxy && proxyTextureId != 0) proxyTextureId else fullTextureId

    /** Pixel dimensions of the texture selected by [activeTexture]. */
    fun activeDimensions(useFastProxy: Boolean): Pair<Int, Int> {
        val source = staged ?: return 1 to 1
        return when (source) {
            is EditorSource.BitmapSource -> {
                val bmp = if (useFastProxy && proxyTextureId != 0) source.proxy else source.full
                bmp.width.coerceAtLeast(1) to bmp.height.coerceAtLeast(1)
            }
            is EditorSource.LinearRawSource -> {
                val buf = if (useFastProxy && proxyTextureId != 0) source.proxy else source.full
                buf.width.coerceAtLeast(1) to buf.height.coerceAtLeast(1)
            }
        }
    }

    /** Stages [source] for upload on the next frame. */
    fun stage(source: EditorSource) {
        staged = source
        needsFull = true
        needsProxy = true
    }

    /** Drops the staged source references without touching GL. */
    fun clear() {
        staged = null
        needsFull = false
        needsProxy = false
    }

    /** Re-arms both uploads after an EGL context recreation invalidated the texture names. */
    fun invalidateContext() {
        fullTextureId = 0
        proxyTextureId = 0
        needsFull = staged != null
        needsProxy = staged != null
    }

    /** Uploads whatever was staged. Must run on the GL thread. */
    fun uploadPending() {
        val source = staged ?: return
        if (needsFull) {
            needsFull = false
            fullTextureId = upload(source, fullFrame = true, existing = fullTextureId)
        }
        if (needsProxy) {
            needsProxy = false
            proxyTextureId = upload(source, fullFrame = false, existing = proxyTextureId)
        }
    }

    private fun upload(source: EditorSource, fullFrame: Boolean, existing: Int): Int =
        when (source) {
            is EditorSource.BitmapSource -> EditorTextureUploader.uploadBitmap(
                bitmap = if (fullFrame) source.full else source.proxy,
                existingTextureId = existing
            )
            is EditorSource.LinearRawSource -> uploadLinear(
                buffer = if (fullFrame) source.full else source.proxy,
                existing = existing
            )
        }

    private fun uploadLinear(buffer: LinearRawBuffer, existing: Int): Int {
        val halfFloatTexture = EditorTextureUploader.uploadLinearHalfFloat(buffer, existing)
        if (halfFloatTexture != 0) return halfFloatTexture
        return EditorTextureUploader.uploadLinearAsBytes(buffer, existingTextureId = 0)
    }
}
