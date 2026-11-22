package cn.devcxl.photosync.editor.export

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

import android.graphics.Bitmap
import android.graphics.BitmapFactory

import androidx.exifinterface.media.ExifInterface
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.SourceResidencyPlan
import cn.devcxl.photosync.editor.domain.SourceResidencyTier
import cn.devcxl.photosync.editor.domain.SourceSliceRequest
import cn.devcxl.photosync.editor.domain.SourceSliceRect
import cn.devcxl.photosync.editor.render.EditorBitmapLoader
import cn.devcxl.photosync.editor.render.EditorTextureUploader
import cn.devcxl.photosync.wrapper.LinearRawBuffer
import cn.devcxl.photosync.wrapper.NativeRgb16Image
import cn.devcxl.photosync.wrapper.RawWrapper

/**
 * GPU-side source supply of a bake, implementing the three residency tiers.
 *
 * - **Tier 1 / Tier 3**: one `GL_RGBA16F` (or `GL_RGBA`) full-frame texture plus a `1024px` proxy.
 * - **Tier 2**: the pixels stay in [NativeRgb16Image] and each band uploads only its inverse-mapped
 *   sub-slice, which is how a 61MP export stays inside both `GL_MAX_TEXTURE_SIZE` and 512MB.
 *
 * Every texture is created with `GL_CLAMP_TO_EDGE` sampling; all uploads are top-down like the
 * editor's source path, so `vTexCoord` stays a top-down image coordinate in every pass.
 */
internal class BakeSourceTextures private constructor(
    val width: Int,
    val height: Int,
    val isLinear: Boolean,
    val autoBrightGain: Float,
    private val native: NativeRgb16Image?,
    private val plan: SourceResidencyPlan,
    private var fullTextureId: Int,
    private var proxyTextureId: Int
) : AutoCloseable {

    private var sliceTextureId: Int = 0
    private var reducedTextureId: Int = 0
    private var sliceBuffer: ByteBuffer? = null

    /** True when bands must upload per-band sub-slices instead of sampling one full texture. */
    val usesSliceUploads: Boolean
        get() = native != null && plan.tier == SourceResidencyTier.NATIVE_SLICE

    /** Texture covering the whole source, or `0` when the source is slice-uploaded. */
    val fullTexture: Int
        get() = fullTextureId

    /** `1024px` proxy texture used by the Pre-pass. */
    val proxyTexture: Int
        get() = proxyTextureId

    /** Longest slice edge this device can upload, from the residency plan. */
    val maxSliceEdgePx: Int
        get() = plan.maxSliceEdgePx

    /**
     * Extracts and uploads the source sub-rectangle of [request].
     *
     * @return The slice texture, or `0` when extraction or upload failed.
     */
    fun uploadSlice(request: SourceSliceRequest): Int {
        val image = native ?: return 0
        val bounds = request.bounds
        val buffer = image.extractRgba16fSlice(
            x0 = bounds.left,
            y0 = bounds.top,
            width = bounds.width,
            height = bounds.height,
            reuse = sliceBuffer
        ) ?: return 0
        sliceBuffer = buffer
        val uploaded = EditorTextureUploader.uploadLinearHalfFloat(
            LinearRawBuffer(
                width = bounds.width,
                height = bounds.height,
                autoBrightGain = autoBrightGain,
                effectiveIso = image.effectiveIso,
                rgba16fBuffer = buffer
            ),
            existingTextureId = sliceTextureId
        )
        sliceTextureId = uploaded
        return uploaded
    }

    /**
     * Uploads one downscaled full-frame texture for extreme crops whose true slice would exceed the
     * `2560px` upload budget.
     *
     * @return The reduced texture, or `0` on failure.
     */
    fun uploadReducedFullFrame(maxEdge: Int): Int {
        if (reducedTextureId != 0) return reducedTextureId
        val image = native ?: return 0
        val proxy = image.extractRgba16fScaled(maxEdge) ?: return 0
        reducedTextureId = EditorTextureUploader.uploadLinearHalfFloat(proxy, 0)
        return reducedTextureId
    }

    /** Releases every texture and the native source. Safe to call twice. */
    override fun close() {
        EditorTextureUploader.releaseTexture(fullTextureId)
        EditorTextureUploader.releaseTexture(proxyTextureId)
        EditorTextureUploader.releaseTexture(sliceTextureId)
        EditorTextureUploader.releaseTexture(reducedTextureId)
        fullTextureId = 0
        proxyTextureId = 0
        sliceTextureId = 0
        reducedTextureId = 0
        sliceBuffer = null
        native?.close()
    }

    companion object {

        /** Longest edge of the Pre-pass proxy handed to the analyzer. */
        const val PROXY_MAX_EDGE_PX = 1024

        /**
         * Opens [sourcePath] and uploads everything its residency tier needs.
         *
         * @param sourceLongEdge Longest edge of the upright frame, used to trigger the half-size
         *   LibRaw decode for Tier 3.
         * @return The staged textures, or `null` when decoding failed.
         */
        fun open(
            sourcePath: String,
            isRaw: Boolean,
            plan: SourceResidencyPlan,
            sourceLongEdge: Int
        ): BakeSourceTextures? {
            if (!File(sourcePath).isFile) {
                Timber.e("Bake source is missing")
                return null
            }
            return if (isRaw) {
                openRaw(sourcePath, plan, sourceLongEdge)
            } else {
                openBitmap(sourcePath, plan)
            }
        }

        private fun openRaw(
            sourcePath: String,
            plan: SourceResidencyPlan,
            sourceLongEdge: Int
        ): BakeSourceTextures? {
            if (plan.tier == SourceResidencyTier.NATIVE_SLICE) {
                return openNativeSlice(sourcePath, plan)
            }
            val maxEdge = if (plan.decodeHalfSize) {
                // Half size keeps a 100MP sensor inside the budget; the texture cap keeps the
                // result uploadable even on a device whose GL_MAX_TEXTURE_SIZE is below 4096.
                minOf(maxOf(1, sourceLongEdge / 2), plan.textureEdgeCapPx)
            } else {
                0
            }
            val linear = try {
                RawWrapper.decodeToLinearRgba16F(sourcePath, maxEdge)
            } catch (oom: OutOfMemoryError) {
                Timber.e(oom, "OOM while decoding the bake RAW source")
                null
            } ?: return null
            val proxyBuffer = linear.downscaledTo(PROXY_MAX_EDGE_PX)
            val full = EditorTextureUploader.uploadLinearHalfFloat(linear, 0)
            if (full == 0) return null
            val proxy = EditorTextureUploader.uploadLinearHalfFloat(proxyBuffer, 0)
            if (proxy == 0) {
                EditorTextureUploader.releaseTexture(full)
                return null
            }
            return BakeSourceTextures(
                width = linear.width,
                height = linear.height,
                isLinear = true,
                autoBrightGain = linear.autoBrightGain,
                native = null,
                plan = plan,
                fullTextureId = full,
                proxyTextureId = proxy
            )
        }

        private fun openNativeSlice(
            sourcePath: String,
            plan: SourceResidencyPlan
        ): BakeSourceTextures? {
            val native = try {
                cn.devcxl.photosync.wrapper.NativeRgb16.decode(sourcePath, halfSize = false)
            } catch (oom: OutOfMemoryError) {
                Timber.e(oom, "OOM while retaining the Tier 2 native RGB16 source")
                null
            } ?: return null
            val proxyBuffer = native.extractRgba16fScaled(PROXY_MAX_EDGE_PX)
            if (proxyBuffer == null) {
                native.close()
                return null
            }
            val proxy = EditorTextureUploader.uploadLinearHalfFloat(proxyBuffer, 0)
            if (proxy == 0) {
                native.close()
                return null
            }
            return BakeSourceTextures(
                width = native.width,
                height = native.height,
                isLinear = true,
                autoBrightGain = native.autoBrightGain,
                native = native,
                plan = plan,
                fullTextureId = 0,
                proxyTextureId = proxy
            )
        }

        private fun openBitmap(sourcePath: String, plan: SourceResidencyPlan): BakeSourceTextures? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourcePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sourceEdge = maxOf(bounds.outWidth, bounds.outHeight)
            val maxEdge = if (sourceEdge <= plan.textureEdgeCapPx) 0 else plan.textureEdgeCapPx
            val bitmap = decodeUpright(sourcePath, maxEdge) ?: return null
            val bitmapWidth = bitmap.width
            val bitmapHeight = bitmap.height
            val proxy = EditorBitmapLoader.createProxyBitmap(bitmap)
            val full = EditorTextureUploader.uploadBitmap(bitmap, 0)
            val proxyTexture = EditorTextureUploader.uploadBitmap(proxy, 0)
            if (proxy !== bitmap) {
                bitmap.recycle()
            }
            proxy.recycle()
            if (full == 0 || proxyTexture == 0) {
                EditorTextureUploader.releaseTexture(full)
                EditorTextureUploader.releaseTexture(proxyTexture)
                return null
            }
            return BakeSourceTextures(
                width = bitmapWidth,
                height = bitmapHeight,
                isLinear = false,
                autoBrightGain = 1.0f,
                native = null,
                plan = plan,
                fullTextureId = full,
                proxyTextureId = proxyTexture
            )
        }

        /** Decodes a JPEG upright, optionally sampled down so its long edge fits [maxLongEdge]. */
        private fun decodeUpright(sourcePath: String, maxLongEdge: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(sourcePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = jpegSampleSizeFor(
                    bounds.outWidth,
                    bounds.outHeight,
                    maxLongEdge
                )
            }
            val decoded = BitmapFactory.decodeFile(sourcePath, options) ?: return null
            val orientation = try {
                ExifInterface(sourcePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } catch (io: IOException) {
                Timber.w(io, "Failed to read the bake source orientation")
                ExifInterface.ORIENTATION_NORMAL
            }
            return EditorBitmapLoader.applyExifOrientation(decoded, orientation)
        }
    }
}

