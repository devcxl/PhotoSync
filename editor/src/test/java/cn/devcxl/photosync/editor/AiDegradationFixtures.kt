package cn.devcxl.photosync.editor

import cn.devcxl.photosync.editor.domain.ColumnBand
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.ProceduralSkyPreset
import cn.devcxl.photosync.editor.domain.SkyAssetType
import cn.devcxl.photosync.editor.domain.SkyBlendParams
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.domain.SkyRelightStats
import cn.devcxl.photosync.editor.export.BakeSkySupport
import cn.devcxl.photosync.editor.render.SkyLayerPixels
import cn.devcxl.photosync.editor.vision.SkyMask
import cn.devcxl.photosync.editor.vision.SubjectMask

/**
 * Pure fixtures of the AI degradation matrix.
 *
 * The masks are soft-edged on purpose: a hard 0/1 edge would hide a guided-filter fallback, while a
 * ramp shows whether the alpha was actually feathered.
 */
internal object AiDegradationFixtures {

    /** Alpha with a four-pixel transition band around the middle column. */
    fun softEdgeAlpha(width: Int, height: Int): FloatArray = FloatArray(width * height) { index ->
        val column = index % width
        when {
            column < width / 2 - 2 -> 1.0f
            column > width / 2 + 1 -> 0.0f
            else -> ((width / 2 + 1 - column) / 4.0f).coerceIn(0.0f, 1.0f)
        }
    }

    /** Binary left-half subject mask at model resolution. */
    fun subjectMask(width: Int, height: Int): SubjectMask = SubjectMask(
        width = width,
        height = height,
        alpha = FloatArray(width * height) { index ->
            if (index % width < width / 2) 1.0f else 0.0f
        }
    )

    /** Procedural sunset sky with a horizontal probability ramp across the whole frame. */
    fun proceduralSkyLayer(): SkyLayerPixels {
        val maskWidth = 192
        val maskHeight = 8
        val probability = FloatArray(maskWidth * maskHeight) { index ->
            (index % maskWidth) / (maskWidth - 1).toFloat()
        }
        return SkyLayerPixels(
            mask = SkyMask(maskWidth, maskHeight, probability, coverageRatio = 0.5f),
            asset = null,
            procedural = ProceduralSkyPreset.SUNSET_GLOW,
            blend = SkyBlendParams(procedural = ProceduralSkyPreset.SUNSET_GLOW),
            stats = SkyRelightStats.NEUTRAL,
            ops = SkyOps(
                maskRef = "edits/1/sky_mask.png",
                assetType = SkyAssetType.PROCEDURAL,
                assetIdOrRelPath = ProceduralSkyPreset.SUNSET_GLOW.wireId
            )
        )
    }

    /**
     * Refines [band] against [guide].
     *
     * @param guide Opaque `ARGB` pixels of the whole crop, addressed with global coordinates.
     */
    fun refineSkyBand(
        support: BakeSkySupport,
        guide: IntArray,
        band: ColumnBand,
        cropWidth: Int,
        cropHeight: Int
    ): FloatArray {
        val pixels = IntArray(band.fboWidth * band.fboHeight) { index ->
            val column = (band.x0 - band.halo + index % band.fboWidth).coerceIn(0, cropWidth - 1)
            val row = (band.y0 - band.halo + index / band.fboWidth).coerceIn(0, cropHeight - 1)
            guide[row * cropWidth + column]
        }
        return checkNotNull(
            support.refineBandMask(
                pixels = pixels,
                width = band.fboWidth,
                height = band.fboHeight,
                band = band,
                inverseMatrix = Mat3.identity(),
                warpField = null,
                referenceShortEdge = cropHeight
            )
        )
    }
}
