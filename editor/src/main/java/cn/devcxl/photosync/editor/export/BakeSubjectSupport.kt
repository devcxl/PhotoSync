package cn.devcxl.photosync.editor.export

import kotlin.math.min

import timber.log.Timber

import cn.devcxl.photosync.editor.domain.BandGuidedFilter
import cn.devcxl.photosync.editor.domain.ColumnBand
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.ForegroundColorEstimator
import cn.devcxl.photosync.editor.domain.ForegroundEstimate
import cn.devcxl.photosync.editor.domain.ImageFilters
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.Uv
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.render.SubjectComposite
import cn.devcxl.photosync.editor.vision.SubjectMask

/**
 * Per-band subject compositor of the full-resolution bake.
 *
 * A 24MP frame cannot hold the guided-filter or FMGE planes, so the bake reuses the preview's
 * low-frequency estimate and recomputes only one `<= 2048 x 640` band at a time:
 * 1. the source-space mask is sampled through the band's `uTileUvTransform` and the Pass A inverse
 *    homography (including the liquify/portrait warp), exactly like the shader would;
 * 2. [BandGuidedFilter] refines that alpha against the band luma;
 * 3. [ForegroundColorEstimator.refineBand] runs two local FMGE rounds inside the transition band,
 *    seeded by the proxy `F/B` estimate;
 * 4. the straight foreground and refined alpha become the band's `RGBA8` subject texture.
 *
 * An `OutOfMemoryError` at any step degrades to a feathered bilinear alpha with `F = I`, which keeps
 * the export running instead of failing the whole bake.
 */
internal class BakeSubjectSupport(
    private val graph: EditGraph,
    private val mask: SubjectMask,
    private val initial: SubjectComposite?,
    private val warpField: WarpDisplacementField?,
    private val inverseMatrix: Mat3
) {

    /** True when the band compositor should run. */
    val isActive: Boolean
        get() = graph.subject.requiresMaskProcessing()

    /**
     * Composes one band.
     *
     * @param pixels Opaque `ARGB` pixels of the band in band-local coordinates, including its halo.
     * @param band Band geometry whose [ColumnBand.tileUv] maps local UV to post-crop global UV.
     * @return The band-aligned composite, or `null` when the mode is neutral.
     */
    fun composeBand(
        pixels: IntArray,
        width: Int,
        height: Int,
        band: ColumnBand
    ): SubjectComposite? {
        if (!isActive) return null
        val image = try {
            rgbFromArgb(pixels, width, height)
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while unpacking a subject bake band")
            return null
        }
        val alpha = try {
            mapBandAlpha(width, height, band)
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while mapping the subject mask into a bake band")
            mapLowResolutionAlpha(width, height, band)
        }
        try {
            val guide = luma(image, width, height)
            val refined = BandGuidedFilter.refine(guide, alpha, width, height)
            val estimate = if (initial != null) {
                cn.devcxl.photosync.editor.domain.ForegroundColorEstimator.refineBand(
                    image,
                    refined,
                    ForegroundEstimate(
                        initial.width,
                        initial.height,
                        initial.foreground,
                        initial.background
                    ),
                    width,
                    height
                )
            } else {
                ForegroundColorEstimator.initialize(image, refined, width, height)
            }
            return SubjectComposite(width, height, refined, estimate.foreground, estimate.background)
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM during band guided-filter/FMGE; falling back to feathered alpha")
            return SubjectCompositeFactory.degradedForBand(image, alpha, width, height)
        }
    }

    /**
     * Builds a coarse fallback composite when the band pixels cannot be read back.
     *
     * Only the mask and the low-frequency proxy estimate are used: the alpha is bilinearly
     * upsampled and feathered, and the proxy foreground is resampled onto the band. This keeps a
     * transparent export valid instead of silently writing an opaque frame.
     */
    fun composeFallbackBand(width: Int, height: Int, band: ColumnBand): SubjectComposite? {
        if (!isActive) return null
        return try {
            val alpha = BandGuidedFilter.feather(
                mapBandAlpha(width, height, band),
                width,
                height,
                BandGuidedFilter.scaledRadius(width)
            )
            val foreground = if (initial != null) {
                ImageFilters.resampleRgb(
                    initial.foreground,
                    initial.width,
                    initial.height,
                    width,
                    height
                )
            } else {
                FloatArray(width * height * CHANNELS) { alpha[it / CHANNELS] }
            }
            val background = if (initial != null) {
                ImageFilters.resampleRgb(
                    initial.background,
                    initial.width,
                    initial.height,
                    width,
                    height
                )
            } else {
                FloatArray(foreground.size)
            }
            SubjectComposite(width, height, alpha, foreground, background)
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while building a fallback subject band")
            null
        }
    }

    /**
     * Coarse band-aligned alpha used by Pass B relight and the Pass C bokeh, before the band pixels
     * exist. The refined composite used by Pass D is built later by [composeBand].
     *
     * @return One alpha per band pixel, or `null` when the subject group is neutral or the buffer
     *   cannot be allocated.
     */
    fun bandAlpha(width: Int, height: Int, band: ColumnBand): FloatArray? {
        if (!isActive) return null
        return try {
            mapBandAlpha(width, height, band)
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while mapping the subject mask into a bake band")
            null
        }
    }

    private fun mapBandAlpha(width: Int, height: Int, band: ColumnBand): FloatArray {        val alpha = FloatArray(width * height)
        for (row in 0 until height) {
            val localV = (row + 0.5f) / height
            val globalV = band.tileUv.mapV(localV)
            for (column in 0 until width) {
                val localU = (column + 0.5f) / width
                val globalU = band.tileUv.mapU(localU)
                val preCrop = inverseMatrix.map(globalU, globalV)
                val warped = warpField?.let { field ->
                    val displacement = field.displacementAt(preCrop.u, preCrop.v)
                    Uv(preCrop.u + displacement.u, preCrop.v + displacement.v)
                } ?: preCrop
                alpha[row * width + column] = mask.sample(warped.u, warped.v)
            }
        }
        return alpha
    }

    /**
     * Quarter-resolution alpha used when the full band allocation fails.
     *
     * Sampling the model mask at `1/4` and upsampling bilinearly costs 1/16 of the memory and still
     * produces a smooth cut-out.
     */
    private fun mapLowResolutionAlpha(width: Int, height: Int, band: ColumnBand): FloatArray {
        val lowWidth = (width / 4).coerceAtLeast(2)
        val lowHeight = (height / 4).coerceAtLeast(2)
        val low = try {
            FloatArray(lowWidth * lowHeight)
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "No memory for even a quarter-resolution subject band")
            return try {
                FloatArray(width * height) { 1.0f }
            } catch (second: OutOfMemoryError) {
                FloatArray(0)
            }
        }
        for (row in 0 until lowHeight) {
            val localV = (row + 0.5f) / lowHeight
            val globalV = band.tileUv.mapV(localV)
            for (column in 0 until lowWidth) {
                val globalU = band.tileUv.mapU((column + 0.5f) / lowWidth)
                val preCrop = inverseMatrix.map(globalU, globalV)
                val warped = warpField?.let { field ->
                    val displacement = field.displacementAt(preCrop.u, preCrop.v)
                    Uv(preCrop.u + displacement.u, preCrop.v + displacement.v)
                } ?: preCrop
                low[row * lowWidth + column] = mask.sample(warped.u, warped.v)
            }
        }
        return try {
            ImageFilters.resamplePlane(
                low, lowWidth, lowHeight, width, height
            )
        } catch (oom: OutOfMemoryError) {
            low
        }
    }

    private fun rgbFromArgb(pixels: IntArray, width: Int, height: Int): FloatArray {
        val rgb = FloatArray(width * height * CHANNELS)
        val count = min(pixels.size, width * height)
        for (index in 0 until count) {
            val pixel = pixels[index]
            rgb[index * CHANNELS] = ((pixel ushr 16) and 0xFF) / 255.0f
            rgb[index * CHANNELS + 1] = ((pixel ushr 8) and 0xFF) / 255.0f
            rgb[index * CHANNELS + 2] = (pixel and 0xFF) / 255.0f
        }
        return rgb
    }

    private fun luma(rgb: FloatArray, width: Int, height: Int): FloatArray =
        FloatArray(width * height) { index ->
            LUMA_R * rgb[index * CHANNELS] +
                LUMA_G * rgb[index * CHANNELS + 1] +
                LUMA_B * rgb[index * CHANNELS + 2]
        }

    private companion object {
        const val CHANNELS = 3
        const val LUMA_R = 0.2126f
        const val LUMA_G = 0.7152f
        const val LUMA_B = 0.0722f
    }
}

/** Band-space degradation shared by [BakeSubjectSupport] and the CPU fallback. */
private object SubjectCompositeFactory {

    /** Feathered bilinear alpha with the original band pixels as foreground. */
    fun degradedForBand(
        image: FloatArray,
        alpha: FloatArray,
        width: Int,
        height: Int
    ): SubjectComposite = SubjectComposite(
        width = width,
        height = height,
        alpha = BandGuidedFilter.feather(
            alpha,
            width,
            height,
            BandGuidedFilter.scaledRadius(width)
        ),
        foreground = try {
            image.copyOf()
        } catch (oom: OutOfMemoryError) {
            FloatArray(image.size)
        },
        background = FloatArray(image.size)
    )
}
