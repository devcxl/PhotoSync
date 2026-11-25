package cn.devcxl.photosync.editor.render

import java.nio.ByteBuffer
import kotlin.math.roundToInt

import android.graphics.Bitmap
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.InpaintBBox
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.InpaintPixelRect
import cn.devcxl.photosync.editor.vision.BitmapInpaintPatchCodec
import cn.devcxl.photosync.editor.vision.InpaintDecodedPatch
import cn.devcxl.photosync.editor.vision.InpaintPatchDecoder

/** Colour domain of a 16-bit source buffer the compositor can write into. */
enum class Inpaint16BitDomain {

    /** Display-referred sRGB encoded values, 8-bit samples scaled by 257. */
    DISPLAY,

    /** Scene-linear light; 8-bit patch samples are sRGB-decoded before blending. */
    LINEAR
}

/**
 * Result of one Pre-Pass A composition pass.
 *
 * @property appliedPatches Patches whose payload verified and was blended.
 * @property skippedPatches Patches dropped because their file, digest or bbox was unusable.
 * @property blendedPixels Pixels whose alpha was greater than zero. Overlapping patches count once
 *   per application, so this is a work counter, not a coverage figure.
 */
data class InpaintComposeReport(
    val appliedPatches: Int,
    val skippedPatches: Int,
    val blendedPixels: Long
) {
    companion object {
        val EMPTY = InpaintComposeReport(0, 0, 0)
    }
}

/**
 * Deterministic Pre-Pass A overlay of the `EditGraph.inpaint` patches on the original source.
 *
 * ## Placement
 * A patch's normalized [InpaintBBox] is resolved against the **pre-crop original image**, so a
 * later crop, straighten, rotate, liquify, colour edit or preset transforms the repaired pixels
 * together with everything else, and a patch can never be copied onto another photo.
 *
 * ## Cost
 * - An empty inpaint list returns [InpaintComposeReport.EMPTY] without touching the source.
 * - Only the bbox rectangle is read, blended and written; no full-image scratch copy is allocated.
 * - The patch and mask are read through [AiAssetStore], so a truncated or tampered file is skipped
 *   instead of being pasted.
 *
 * ## Formats
 * [applyToBitmap] and [applyToArgb] work in display-referred `ARGB_8888` preview space.
 * [applyToLinear16Rgb] writes interleaved RGB `ShortArray` buffers used by the RAW/bake path; the
 * patch is converted from its sRGB 8-bit form into [Inpaint16BitDomain] before blending.
 */
class InpaintSourceCompositor(
    private val assetStore: AiAssetStore,
    private val decoder: InpaintPatchDecoder = BitmapInpaintPatchCodec
) {

    private val blender = InpaintPatchBlender()

    /**
     * Blends [patches] into [source] in place.
     *
     * @param source Mutable `ARGB_8888` bitmap; later patches paint over earlier ones.
     * @return What was applied and skipped.
     */
    fun applyToBitmap(source: Bitmap, patches: List<InpaintPatchRef>): InpaintComposeReport {
        if (patches.isEmpty()) return InpaintComposeReport.EMPTY
        var applied = 0
        var skipped = 0
        var blended = 0L
        for (patch in patches) {
            val rect = patch.bbox.toPixelRect(source.width, source.height)
            val decoded = if (rect == null) null else decode(patch)
            if (rect == null || decoded == null) {
                skipped++
                continue
            }
            val region = IntArray(rect.width * rect.height)
            source.getPixels(region, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
            blended += blender.blendArgb(region, rect.width, 0, 0, rect, decoded)
            source.setPixels(region, 0, rect.width, rect.left, rect.top, rect.width, rect.height)
            applied++
        }
        return InpaintComposeReport(applied, skipped, blended)
    }

    /**
     * Blends [patches] into a packed `ARGB_8888` [pixels] buffer in place.
     *
     * @param width Source width; `pixels.size` must be `width * height`.
     */
    fun applyToArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        patches: List<InpaintPatchRef>
    ): InpaintComposeReport {
        if (patches.isEmpty()) return InpaintComposeReport.EMPTY
        require(width > 0 && height > 0) { "Source dimensions must be positive" }
        require(pixels.size == width * height) { "Source buffer does not match its size" }
        var applied = 0
        var skipped = 0
        var blended = 0L
        for (patch in patches) {
            val rect = patch.bbox.toPixelRect(width, height)
            val decoded = if (rect == null) null else decode(patch)
            if (rect == null || decoded == null) {
                skipped++
                continue
            }
            blended += blender.blendArgb(pixels, width, rect.left, rect.top, rect, decoded)
            applied++
        }
        return InpaintComposeReport(applied, skipped, blended)
    }

    /**
     * Blends [patches] into an interleaved RGB 16-bit buffer in place.
     *
     * @param pixels `width * height * 3` unsigned shorts; the caller keeps the same channel order
     *   the bake path already uses.
     * @param domain Colour domain of [pixels]; the 8-bit sRGB patch is converted before blending.
     */
    fun applyToLinear16Rgb(
        pixels: ShortArray,
        width: Int,
        height: Int,
        patches: List<InpaintPatchRef>,
        domain: Inpaint16BitDomain = Inpaint16BitDomain.LINEAR
    ): InpaintComposeReport {
        if (patches.isEmpty()) return InpaintComposeReport.EMPTY
        require(width > 0 && height > 0) { "Source dimensions must be positive" }
        require(pixels.size == width * height * RGB_CHANNELS) { "Source buffer does not match its size" }
        var applied = 0
        var skipped = 0
        var blended = 0L
        for (patch in patches) {
            val rect = patch.bbox.toPixelRect(width, height)
            val decoded = if (rect == null) null else decode(patch)
            if (rect == null || decoded == null) {
                skipped++
                continue
            }
            blended += blender.blendLinear16(pixels, width, rect, decoded, domain)
            applied++
        }
        return InpaintComposeReport(applied, skipped, blended)
    }

    /**
     * Blends [patches] into an interleaved `RGBA` half-float (`GL_RGBA16F`) buffer in place.
     *
     * This is the Pre-Pass A overlay of the 16-bit paths that keep their pixels as half-float
     * texels: the linear RAW preview, the downscaled Tier 2 proxy and the Tier 2 bake slices.
     * Patch boxes are normalized against the **pre-crop source**, so a slice passes its own origin
     * and the full source size and only the pixels inside the slice are touched.
     *
     * @param buffer Little-endian half-float texels covering at least [width] x [height].
     * @param width Width of the buffer being composed.
     * @param height Height of the buffer being composed.
     * @param originX Left edge of the buffer inside the source, in source pixels.
     * @param originY Top edge of the buffer inside the source, in source pixels.
     * @param sourceWidth Pre-crop source width the normalized boxes resolve against.
     * @param sourceHeight Pre-crop source height the normalized boxes resolve against.
     */
    fun applyToLinearRgba16f(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        patches: List<InpaintPatchRef>,
        originX: Int = 0,
        originY: Int = 0,
        sourceWidth: Int = width,
        sourceHeight: Int = height
    ): InpaintComposeReport {
        if (patches.isEmpty()) return InpaintComposeReport.EMPTY
        require(width > 0 && height > 0) { "Source dimensions must be positive" }
        require(sourceWidth > 0 && sourceHeight > 0) { "Patch space must be positive" }
        require(buffer.capacity() >= width * height * RGBA16F_BYTES) {
            "Source buffer does not match its size"
        }
        val window = InpaintPixelRect(0, 0, width, height)
        var applied = 0
        var skipped = 0
        var blended = 0L
        for (patch in patches) {
            val rect = patch.bbox.toPixelRect(sourceWidth, sourceHeight)
            val decoded = if (rect == null) null else decode(patch)
            if (rect == null || decoded == null) {
                skipped++
                continue
            }
            val local = translate(rect, originX, originY)
            if (!local.intersects(window)) {
                // The patch lives entirely outside this slice: nothing to blend, nothing skipped.
                applied++
                continue
            }
            blended += blender.blendLinearHalfFloat(buffer, width, height, local, decoded)
            applied++
        }
        return InpaintComposeReport(applied, skipped, blended)
    }

    private fun translate(rect: InpaintPixelRect, originX: Int, originY: Int): InpaintPixelRect =
        InpaintPixelRect(
            left = rect.left - originX,
            top = rect.top - originY,
            right = rect.right - originX,
            bottom = rect.bottom - originY
        )

    private fun decode(patch: InpaintPatchRef): InpaintDecodedPatch? {
        val patchDigest = patch.sha256.takeIf(::isSha256Hex) ?: digestFromPath(patch.patchRelPath)
        if (patchDigest == null) {
            Timber.w("Inpaint patch skipped: no verifiable digest")
            return null
        }
        val maskDigest = digestFromPath(patch.maskRelPath)
        if (maskDigest == null) {
            Timber.w("Inpaint mask skipped: path is not content-addressed")
            return null
        }
        val patchPng = assetStore.read(patch.patchRelPath, patchDigest)
        if (patchPng == null) {
            Timber.w("Inpaint patch skipped: payload failed verification")
            return null
        }
        val maskPng = assetStore.read(patch.maskRelPath, maskDigest)
        if (maskPng == null) {
            Timber.w("Inpaint mask skipped: payload failed verification")
            return null
        }
        val decoded = decoder.decode(patchPng, maskPng)
        if (decoded == null) Timber.w("Inpaint patch skipped: PNG pair could not be decoded")
        return decoded
    }

    private fun InpaintBBox.toPixelRect(width: Int, height: Int): InpaintPixelRect? {
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) {
            return null
        }
        val leftPx = (left.coerceIn(0.0f, 1.0f) * width).roundToInt().coerceIn(0, width)
        val topPx = (top.coerceIn(0.0f, 1.0f) * height).roundToInt().coerceIn(0, height)
        val rightPx = (right.coerceIn(0.0f, 1.0f) * width).roundToInt().coerceIn(leftPx, width)
        val bottomPx = (bottom.coerceIn(0.0f, 1.0f) * height).roundToInt().coerceIn(topPx, height)
        if (rightPx <= leftPx || bottomPx <= topPx) return null
        return InpaintPixelRect(leftPx, topPx, rightPx, bottomPx)
    }

    private fun digestFromPath(relativePath: String): String? {
        val name = relativePath.substringAfterLast('/').substringBeforeLast('.')
        val digest = name.substringAfterLast('_')
        return digest.takeIf(::isSha256Hex)
    }

    private fun isSha256Hex(value: String): Boolean =
        value.length == SHA256_HEX_LENGTH &&
            value.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    private companion object {
        const val RGB_CHANNELS = 3
        const val RGBA16F_BYTES = 8
        const val SHA256_HEX_LENGTH = 64
    }
}
