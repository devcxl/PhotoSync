package cn.devcxl.photosync.editor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.domain.InpaintStroke
import cn.devcxl.photosync.editor.domain.InpaintStrokePoint
import cn.devcxl.photosync.editor.domain.InpaintWorkRegion
import cn.devcxl.photosync.editor.domain.Uv

/**
 * Seam under test: projecting a visible inpainting stroke onto the pre-crop source.
 *
 * A crop + rotate + straighten graph is the case that breaks a naive implementation: the stroke
 * would land on another object and the 256 px advice would measure the cropped frame instead of the
 * full-resolution source.
 */
class InpaintStrokeProjectionTest {

    private val geometry = GeometryOps(
        rotate90 = 1,
        straighten = 3.0f,
        crop = CropRect(left = 0.2f, top = 0.1f, right = 0.8f, bottom = 0.9f)
    )

    @Test
    fun shouldMapTheVisibleCentreOntoTheInversePassACoordinate_whenCropRotateAndStraightenAreActive() {
        val projection = InpaintStrokeProjection.of(geometry = geometry, srcAspect = 1.5f)
        val expected = GeometryMath.buildPassAInverseMatrix(geometry, srcAspect = 1.5f)
            .map(0.5f, 0.5f)

        val mapped = projection.toSource(Uv(0.5f, 0.5f))

        assertEquals(expected.u, mapped.u, 1e-4f)
        assertEquals(expected.v, mapped.v, 1e-4f)
    }

    @Test
    fun shouldScaleTheBrushRadiusByTheLocalCropDerivative_whenTheProjectionIsNotIdentity() {
        val projection = InpaintStrokeProjection.of(geometry = geometry, srcAspect = 1.5f)
        val inverse = GeometryMath.buildPassAInverseMatrix(geometry, srcAspect = 1.5f)
        val uv = Uv(0.5f, 0.5f)
        val origin = inverse.map(uv.u, uv.v)
        val shifted = inverse.map(uv.u + 0.01f, uv.v)
        val expected = kotlin.math.hypot(
            (shifted.u - origin.u).toDouble(),
            ((shifted.v - origin.v) * 1.5).toDouble()
        ).toFloat() / 0.01f

        assertEquals(expected, projection.radiusScaleAt(uv), 1e-3f)
    }

    @Test
    fun shouldKeepThePaintedCapsuleInTrueSourcePixels_whenTheStrokeIsProjected() {
        val projection = InpaintStrokeProjection.of(geometry = geometry, srcAspect = 1.5f)
        val sourceWidth = 3000
        val sourceHeight = 2000
        val stroke = InpaintStroke(
            erase = false,
            radiusNorm = 0.02f,
            points = listOf(InpaintStrokePoint(0.5f, 0.5f))
        )

        val projected = projection.projectStrokes(listOf(stroke))
        val bounds = checkNotNull(InpaintWorkRegion.boundsOf(projected, sourceWidth, sourceHeight))
        val centre = projection.toSource(Uv(0.5f, 0.5f))
        val radiusPx = stroke.radiusNorm * sourceWidth * projection.radiusScaleAt(Uv(0.5f, 0.5f))

        assertEquals(1, projected.size)
        assertEquals(centre.u * sourceWidth, (bounds.left + bounds.right) / 2.0f, 1.0f)
        assertEquals(centre.v * sourceHeight, (bounds.top + bounds.bottom) / 2.0f, 1.0f)
        assertEquals(2.0f * radiusPx, bounds.width.toFloat(), 2.0f)
    }
}
