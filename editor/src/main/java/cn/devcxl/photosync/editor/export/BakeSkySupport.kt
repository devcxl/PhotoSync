package cn.devcxl.photosync.editor.export

import timber.log.Timber

import cn.devcxl.photosync.editor.domain.BandGuidedFilter
import cn.devcxl.photosync.editor.domain.ColumnBand
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.SkyBlendMath
import cn.devcxl.photosync.editor.domain.Uv
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.render.SkyLayerPixels

/**
 * Per-band sky mask refiner of the full-resolution bake.
 *
 * The editor refines its `1024px` mask once, but a 24MP export cannot hold that probability plane at
 * full resolution. Mirroring [BakeSubjectSupport], the bake therefore:
 * 1. maps the stored source-space mask into the current `<= 2048 x 640` band through the band's
 *    global UV and the Pass A inverse homography (including the liquify/portrait warp and the
 *    `edgeShift` erosion in source-short-edge units);
 * 2. runs [BandGuidedFilter] against the band luma with the P3-frozen `radius = 8`, `eps = 1e-3`;
 * 3. degrades to the bilinearly upsampled mask when any allocation fails, so an export never stops
 *    because one band could not be refined.
 */
internal class BakeSkySupport(private val layer: SkyLayerPixels) {

    /**
     * Builds one band-aligned probability plane from the band's display pixels.
     *
     * @param pixels Band pixels including the halo, or `null` when the read-back failed.
     * @param referenceShortEdge Full-resolution source short edge that defines the `edgeShift` unit.
     * @return One probability per band pixel, or `null` when even the bilinear map cannot allocate.
     */
    fun refineBandMask(
        pixels: IntArray?,
        width: Int,
        height: Int,
        band: ColumnBand,
        inverseMatrix: Mat3,
        warpField: WarpDisplacementField?,
        referenceShortEdge: Int
    ): FloatArray? = refineWithGuide(
        guide = pixels?.let(::lumaOf),
        width = width,
        height = height,
        band = band,
        inverseMatrix = inverseMatrix,
        warpField = warpField,
        referenceShortEdge = referenceShortEdge
    )

    private fun refineWithGuide(
        guide: FloatArray?,
        width: Int,
        height: Int,
        band: ColumnBand,
        inverseMatrix: Mat3,
        warpField: WarpDisplacementField?,
        referenceShortEdge: Int
    ): FloatArray? {
        val alpha = try {
            mapBandMask(width, height, band, inverseMatrix, warpField, referenceShortEdge)
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while mapping the sky mask into a bake band")
            return null
        }
        if (guide == null || guide.size != alpha.size) return alpha
        return try {
            BandGuidedFilter.refine(
                guide = guide,
                alpha = alpha,
                width = width,
                height = height,
                radius = GUIDED_RADIUS,
                epsilon = GUIDED_EPSILON
            )
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM during the sky band guided filter; using the bilinear mask")
            alpha
        }
    }

    private fun lumaOf(pixels: IntArray): FloatArray = FloatArray(pixels.size) { index ->
        val pixel = pixels[index]
        LUMA_R * ((pixel ushr 16) and 0xFF) / 255.0f +
            LUMA_G * ((pixel ushr 8) and 0xFF) / 255.0f +
            LUMA_B * (pixel and 0xFF) / 255.0f
    }

    private fun mapBandMask(
        width: Int,
        height: Int,
        band: ColumnBand,
        inverseMatrix: Mat3,
        warpField: WarpDisplacementField?,
        referenceShortEdge: Int
    ): FloatArray {
        val alpha = FloatArray(width * height)
        val edgeScale = SkyBlendMath.edgeScale(layer.blend.edgeShift, referenceShortEdge)
        for (row in 0 until height) {
            val localV = (row + 0.5f) / height
            val globalV = band.tileUv.mapV(localV)
            val shiftedV = 0.5f + (globalV - 0.5f) * edgeScale
            for (column in 0 until width) {
                val globalU = band.tileUv.mapU((column + 0.5f) / width)
                val shiftedU = 0.5f + (globalU - 0.5f) * edgeScale
                val preCrop = inverseMatrix.map(shiftedU, shiftedV)
                val warped = warpField?.let { field ->
                    val displacement = field.displacementAt(preCrop.u, preCrop.v)
                    Uv(preCrop.u + displacement.u, preCrop.v + displacement.v)
                } ?: preCrop
                alpha[row * width + column] = layer.mask.sample(warped.u, warped.v)
            }
        }
        return alpha
    }

    private companion object {
        /** Guided-filter radius frozen by the P3 specification. */
        const val GUIDED_RADIUS = 8

        /** Guided-filter regularisation frozen by the P3 specification. */
        const val GUIDED_EPSILON = 1e-3f

        const val LUMA_R = 0.2126f
        const val LUMA_G = 0.7152f
        const val LUMA_B = 0.0722f
    }
}
