package cn.devcxl.photosync.editor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.domain.Uv

/**
 * Seam under test: the mapping between the visible canvas coordinate the finger touches and the
 * pre-crop coordinate the `128 x 128` displacement grid is defined in.
 *
 * Getting this wrong is invisible on an un-cropped photo and very visible on a cropped one, where
 * strokes would land on the wrong part of the source.
 */
class LiquifyStrokeProjectionTest {

    @Test
    fun shouldMapTheVisibleFrameOntoTheCroppedSourceRegion_whenACropIsActive() {
        val projection = LiquifyStrokeProjection.of(
            geometry = GeometryOps(crop = CropRect(left = 0.25f, top = 0.0f, right = 0.75f, bottom = 1.0f)),
            srcAspect = 2.0f
        )

        // The visible frame is the middle half of the source, so 0 -> 0.25 and 1 -> 0.75.
        assertEquals(0.25f, projection.toPreCrop(Uv(0.0f, 0.5f)).u, 1e-3f)
        assertEquals(0.75f, projection.toPreCrop(Uv(1.0f, 0.5f)).u, 1e-3f)
        assertEquals(0.5f, projection.toPreCrop(Uv(0.5f, 0.5f)).u, 1e-3f)
    }

    @Test
    fun shouldScaleTheBrushRadiusByTheCropSpan_whenACropIsActive() {
        val projection = LiquifyStrokeProjection.of(
            geometry = GeometryOps(crop = CropRect(left = 0.25f, top = 0.0f, right = 0.75f, bottom = 1.0f)),
            srcAspect = 2.0f
        )

        assertEquals(0.5f, projection.radiusScaleAt(Uv(0.5f, 0.5f)), 1e-3f)
    }

    @Test
    fun shouldLeaveUvUntouched_whenTheGeometryIsNeutral() {
        val projection = LiquifyStrokeProjection.of(geometry = GeometryOps(), srcAspect = 1.5f)

        val uv = Uv(0.37f, 0.62f)
        val preCrop = projection.toPreCrop(uv)

        assertEquals(uv.u, preCrop.u, 1e-4f)
        assertEquals(uv.v, preCrop.v, 1e-4f)
        assertEquals(1.0f, projection.radiusScaleAt(uv), 1e-3f)
        assertEquals(1.5f, projection.frameAspect, 1e-4f)
    }
}
