package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: the four-point homography solver and the compiled Pass A inverse matrix that
 * every preview and export pixel is sampled through.
 */
class PassAGeometryTest {

    // ------------------------------------------------------------- homography and Pass A matrix

    @Test
    fun shouldReturnIdentityHomography_whenSolvingFromUnitSquareToItself() {
        val identity = GeometryMath.solveHomography3x3(UNIT_SQUARE, UNIT_SQUARE)
        assertNotNull(identity)
        assertMapsTo(identity!!, Uv(0f, 0f), Uv(0f, 0f))
        assertMapsTo(identity, Uv(1f, 1f), Uv(1f, 1f))
        assertMapsTo(identity, Uv(0.37f, 0.62f), Uv(0.37f, 0.62f))
    }

    @Test
    fun shouldMapEverySourceCornerOntoItsDestinationCorner_whenSolvingHomography() {
        val destination = floatArrayOf(0.08f, 0.12f, 0.94f, 0.02f, 0.99f, 0.91f, 0.03f, 0.87f)
        val homography = GeometryMath.solveHomography3x3(destination, UNIT_SQUARE)
        assertNotNull(homography)
        val corners = listOf(
            Uv(0f, 0f), Uv(1f, 0f), Uv(1f, 1f), Uv(0f, 1f)
        )
        corners.forEachIndexed { index, source ->
            val expected = Uv(destination[index * 2], destination[index * 2 + 1])
            assertMapsTo(homography!!, source, expected)
        }
    }

    @Test
    fun shouldReturnToTheOriginalCorner_whenMultiplyingHomographyByItsInverse() {
        val destination = floatArrayOf(0.11f, 0.05f, 0.9f, 0.13f, 0.96f, 0.88f, 0.04f, 0.94f)
        val forward = GeometryMath.solveHomography3x3(destination, UNIT_SQUARE)
        assertNotNull(forward)
        val inverse = forward!!.invertOrNull()
        assertNotNull(inverse)
        UNIT_SQUARE_CORNERS.forEach { corner ->
            val warped = forward.map(corner.u, corner.v)
            val restored = inverse!!.map(warped.u, warped.v)
            assertEquals(corner.u, restored.u, 1e-3f)
            assertEquals(corner.v, restored.v, 1e-3f)
        }
    }

    @Test
    fun shouldReturnNull_whenTheQuadIsDegenerate() {
        val collinear = floatArrayOf(0.0f, 0.0f, 0.5f, 0.5f, 1.0f, 1.0f, 1.0f, 1.0f)
        assertNull(GeometryMath.solveHomography3x3(collinear, UNIT_SQUARE))
        assertNull(GeometryMath.solveHomography3x3(floatArrayOf(0f, 0f), UNIT_SQUARE))
    }

    @Test
    fun shouldPackMatricesInColumnMajorOrder_whenExposingUniformFloats() {
        val destination = floatArrayOf(0.0f, 0.0f, 1.2f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f)
        val homography = GeometryMath.solveHomography3x3(destination, UNIT_SQUARE)
        assertNotNull(homography)
        val packed = homography!!.toColumnMajorFloatArray()
        assertEquals(9, packed.size)
        for (row in 0..2) {
            for (column in 0..2) {
                assertEquals(
                    "row=$row column=$column",
                    homography[row, column].toFloat(),
                    packed[column * 3 + row],
                    1e-6f
                )
            }
        }
    }

    @Test
    fun shouldMapEveryOutputPixelOntoItself_whenGeometryIsNeutral() {
        val graph = EditGraph()
        listOf(1.0f, 3.0f / 2.0f, 2.0f / 3.0f, 16.0f / 9.0f).forEach { aspect ->
            val inverse = GeometryMath.buildPassAInverseMatrix(graph.geometry, aspect)
            listOf(Uv(0f, 0f), Uv(1f, 0f), Uv(1f, 1f), Uv(0f, 1f), Uv(0.31f, 0.77f))
                .forEach { assertMapsTo(inverse, it, it) }
            assertEquals("aspect=$aspect", aspect, GeometryMath.outputAspect(graph.geometry, aspect), 1e-4f)
        }
    }

    @Test
    fun shouldMapCropCornersOntoFullOutputFrame_whenCropRectIsReduced() {
        val geometry = GeometryOps(crop = CropRect(left = 0.25f, top = 0.1f, right = 0.75f, bottom = 0.9f))
        val inverse = GeometryMath.buildPassAInverseMatrix(geometry, srcAspect = 1.5f)
        assertMapsTo(inverse, Uv(0f, 0f), Uv(0.25f, 0.1f))
        assertMapsTo(inverse, Uv(1f, 1f), Uv(0.75f, 0.9f))
        assertMapsTo(inverse, Uv(0.5f, 0.5f), Uv(0.5f, 0.5f))
        val expectedAspect = 1.5f * 0.5f / 0.8f
        assertEquals(expectedAspect, GeometryMath.outputAspect(geometry, 1.5f), 1e-4f)
    }

    @Test
    fun shouldMapOutputCentreOntoSourceCentre_whenOnlyStraightenIsApplied() {
        listOf(-45.0f, -12.5f, 0.0f, 7.5f, 45.0f).forEach { angle ->
            val geometry = GeometryOps(straighten = angle)
            val inverse = GeometryMath.buildPassAInverseMatrix(geometry, srcAspect = 1.5f)
            assertMapsTo(inverse, Uv(0.5f, 0.5f), Uv(0.5f, 0.5f))
            assertEquals(1.5f, GeometryMath.outputAspect(geometry, 1.5f), 1e-4f)
        }
    }

    @Test
    fun shouldSwapOutputAspect_whenRotate90IsOdd() {
        val geometry = GeometryOps(rotate90 = 1)
        val inverse = GeometryMath.buildPassAInverseMatrix(geometry, srcAspect = 1.5f)
        assertEquals(1.0f / 1.5f, GeometryMath.outputAspect(geometry, 1.5f), 1e-4f)
        assertNoCornerLeak(inverse)
        assertEquals(-1.0f, inverse[1, 0].toFloat(), 1e-4f)

        val doubleTurn = GeometryOps(rotate90 = 2)
        val doubleInverse = GeometryMath.buildPassAInverseMatrix(doubleTurn, srcAspect = 1.5f)
        assertEquals(1.5f, GeometryMath.outputAspect(doubleTurn, 1.5f), 1e-4f)
        assertNoCornerLeak(doubleInverse)
    }

    @Test
    fun shouldMirrorAcrossTheCentre_whenFlipHOrFlipVIsApplied() {
        val flipped = GeometryOps(flipH = true)
        val inverse = GeometryMath.buildPassAInverseMatrix(flipped, srcAspect = 1.0f)
        assertMapsTo(inverse, Uv(0f, 0.25f), Uv(1f, 0.25f))
        assertMapsTo(inverse, Uv(1f, 0.75f), Uv(0f, 0.75f))

        val flippedV = GeometryOps(flipV = true)
        val inverseV = GeometryMath.buildPassAInverseMatrix(flippedV, srcAspect = 1.0f)
        assertMapsTo(inverseV, Uv(0.25f, 0f), Uv(0.25f, 1f))
        assertMapsTo(inverseV, Uv(0.75f, 1f), Uv(0.75f, 0f))
    }

    @Test
    fun shouldNeverSampleOutsideTheSourceImage_acrossTheWholeGeometrySurface() {
        val sourceAspects = listOf(0.6f, 1.0f, 3.0f / 2.0f, 16.0f / 9.0f)
        val quads = listOf(
            GeometryOps.DEFAULT_PERSPECTIVE_QUAD,
            listOf(0.02f, 0.07f, 0.97f, 0.0f, 0.99f, 0.96f, 0.01f, 0.99f),
            listOf(0.12f, 0.16f, 0.88f, 0.09f, 0.95f, 0.9f, 0.05f, 0.84f),
            listOf(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f)
        )
        val crops = listOf(
            CropRect(),
            CropRect(0.05f, 0.1f, 0.95f, 0.9f),
            CropRect(0.3f, 0.2f, 0.6f, 0.55f)
        )
        var leaked = 0
        var checked = 0
        for (aspect in sourceAspects) {
            for (rotate90 in 0..3) {
                for (flipH in listOf(false, true)) {
                    for (flipV in listOf(false, true)) {
                        for (angle in listOf(-45.0f, -30.0f, -5.5f, 0.0f, 12.0f, 45.0f)) {
                            for (quad in quads) {
                                for (crop in crops) {
                                    val geometry = GeometryOps(
                                        rotate90 = rotate90,
                                        flipH = flipH,
                                        flipV = flipV,
                                        straighten = angle,
                                        crop = crop,
                                        perspective = quad
                                    )
                                    val inverse = GeometryMath.buildPassAInverseMatrix(geometry, aspect)
                                    checked++
                                    if (hasCornerLeak(inverse)) leaked++
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue("checked=$checked", checked > 500)
        assertEquals("output corners must stay inside source UV", 0, leaked)
    }

    @Test
    fun shouldClampInwardDraggedPerspectiveQuad_intoSamplingBounds() {
        val outOfBounds = GeometryOps(
            perspective = listOf(-0.2f, -0.1f, 1.3f, -0.05f, 1.2f, 1.4f, -0.15f, 1.1f)
        )
        val inverse = GeometryMath.buildPassAInverseMatrix(outOfBounds, srcAspect = 1.0f)
        assertNoCornerLeak(inverse)
    }

    @Test
    fun shouldFallBackToIdentityPerspective_whenTheDraggedQuadIsConcave() {
        // A concave (bow-tie) drag must not be honoured, otherwise the warp folds the image.
        val concave = GeometryOps(
            perspective = listOf(0.2f, 0.2f, 0.8f, 0.8f, 0.8f, 0.2f, 0.2f, 0.8f)
        )
        val inverse = GeometryMath.buildPassAInverseMatrix(concave, srcAspect = 1.0f)
        assertMapsTo(inverse, Uv(0f, 0f), Uv(0f, 0f))
        assertMapsTo(inverse, Uv(1f, 1f), Uv(1f, 1f))
        assertEquals(1.0f, GeometryMath.outputAspect(concave, 1.0f), 1e-4f)
    }

    private fun assertNoCornerLeak(inverse: Mat3) {
        assertFalse("output corners escaped the source image", hasCornerLeak(inverse))
    }

    private fun hasCornerLeak(inverse: Mat3): Boolean = UNIT_SQUARE_CORNERS.any { corner ->
        val sampled = inverse.map(corner.u, corner.v)
        sampled.u < -1e-4f || sampled.u > 1.0f + 1e-4f ||
            sampled.v < -1e-4f || sampled.v > 1.0f + 1e-4f
    }

    private fun assertMapsTo(matrix: Mat3, input: Uv, expected: Uv) {
        val actual = matrix.map(input.u, input.v)
        assertEquals("u of $input", expected.u, actual.u, 1e-3f)
        assertEquals("v of $input", expected.v, actual.v, 1e-3f)
    }

    companion object {
        private const val TOL = 1e-4f
        private const val CORNER_TOL = 1e-3
        private val UNIT_SQUARE = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        private val UNIT_SQUARE_CORNERS = listOf(Uv(0f, 0f), Uv(1f, 0f), Uv(1f, 1f), Uv(0f, 1f))
    }
}
