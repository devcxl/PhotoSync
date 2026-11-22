package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [PortraitWarpComposer] — the single `uWarpTex` mesh Pass A samples, built by
 * adding the face MLS grid, the body grid and the liquify brush grid.
 */
class PortraitWarpComposerTest {

    @Test
    fun shouldBeZero_whenEveryWarpSourceIsMissing() {
        val field = PortraitWarpComposer.compose(null, null, null)

        assertEquals(PortraitWarpComposer.GRID_SIZE, field.gridSize)
        assertTrue(field.isZero())
        assertEquals(Uv(0.0f, 0.0f), field.displacementAt(0.33f, 0.66f))
    }

    @Test
    fun shouldAddFaceBodyAndLiquifyCellByCell_whenAllThreeAreActive() {
        val face = constantGrid(0.004f, 0.0f)
        val body = constantGrid(0.0f, -0.002f)
        val liquify = LiquifyMesh.fromDisplacements(
            FloatArray(LiquifyMesh.COMPONENT_COUNT) { index ->
                if (index % 2 == 0) -0.001f else 0.003f
            }
        )

        val components = PortraitWarpComposer.composeComponents(face, body, liquify)

        assertEquals(PortraitWarpComposer.COMPONENT_COUNT, components.size)
        for (cell in 0 until PortraitWarpComposer.COMPONENT_COUNT / 2) {
            assertEquals(0.004f - 0.001f, components[cell * 2], 1e-6f)
            assertEquals(-0.002f + 0.003f, components[cell * 2 + 1], 1e-6f)
        }
    }

    @Test
    fun shouldSkipAZeroLiquifyField_whenOnlyThePortraitSolversMoved() {
        val face = constantGrid(0.01f, 0.0f)
        val field = PortraitWarpComposer.compose(face, null, LiquifyMesh.empty())

        assertFalse(field.isZero())
        val sampled = field.displacementAt(0.5f, 0.5f)
        assertEquals(0.01f, sampled.u, 1e-6f)
        assertEquals(0.0f, sampled.v, 1e-6f)
    }

    @Test
    fun shouldExposeTheComposedFieldThroughTheHalfFloatUploadContract() {
        val face = constantGrid(0.004f, -0.002f)

        val field = PortraitWarpComposer.compose(face, null, null)
        val bits = ShortArray(PortraitWarpComposer.COMPONENT_COUNT)
        field.readHalfFloatBitsInto(bits)
        val decoded = HalfFloatBits.toFloats(bits)

        assertEquals(LiquifyMesh.CELL_COUNT * 2, field.gridSize * field.gridSize * 2)
        assertEquals(0.004f, decoded[0], 5e-6f)
        assertEquals(-0.002f, decoded[1], 5e-6f)
        assertEquals(-0.002f, field.displacementRaw(1), 1e-6f)
    }

    private fun constantGrid(du: Float, dv: Float): FloatArray =
        FloatArray(PortraitWarpComposer.COMPONENT_COUNT) { index ->
            if (index % 2 == 0) du else dv
        }
}
