package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: the liquify displacement field contract — brush accumulation, restore decay,
 * the `128 x 128` half-float persistence format with its SHA-256 check, and the bounded undo
 * history. All arithmetic lives in the pure domain layer so it can be verified on the JVM.
 */
class LiquifyMeshTest {

    @Test
    fun shouldMoveContentInDragDirection_whenApplyingPushStroke() {
        val mesh = LiquifyMesh.empty()

        mesh.applyPushStroke(
            prevUv = Uv(0.5f, 0.5f),
            nextUv = Uv(0.6f, 0.5f),
            radiusNorm = 0.2f,
            strength = 1.0f
        )

        // The stamp is centred on the current touch point, so the full delta lands there.
        val stampCentre = mesh.displacementAt(0.6f, 0.5f)
        // Inverse-mapping convention: to move pixels right, the sample coordinate moves left.
        assertEquals(-0.1f, stampCentre.u, 0.002f)
        assertEquals(0.0f, stampCentre.v, 0.001f)

        val previousTouch = mesh.displacementAt(0.5f, 0.5f)
        assertTrue("falloff must reduce the displacement away from the touch", abs(previousTouch.u) < 0.1f)
    }

    @Test
    fun shouldFallOffSmoothlyAndStopAtTheBrushRadius_whenApplyingPushStroke() {
        val mesh = LiquifyMesh.empty()
        mesh.applyPushStroke(
            prevUv = Uv(0.5f, 0.5f),
            nextUv = Uv(0.55f, 0.5f),
            radiusNorm = 0.1f,
            strength = 1.0f
        )

        val centre = abs(mesh.displacementAt(0.55f, 0.5f).u)
        val midway = abs(mesh.displacementAt(0.60f, 0.5f).u)
        val nearEdge = abs(mesh.displacementAt(0.64f, 0.5f).u)
        val outside = abs(mesh.displacementAt(0.66f, 0.5f).u)

        assertTrue("centre must carry the full delta", centre > 0.048f)
        assertEquals("the half-radius stamp must follow (1 - r^2)^2", 0.05f * 0.5625f, midway, 0.004f)
        assertTrue("falloff must decay with radius", nearEdge < midway)
        assertEquals("displacement must vanish outside the radius", 0.0f, outside, 0.0f)
    }

    @Test
    fun shouldDecayDisplacementTowardZero_whenApplyingRestoreStroke() {
        val mesh = LiquifyMesh.empty()
        mesh.applyPushStroke(
            prevUv = Uv(0.5f, 0.5f),
            nextUv = Uv(0.6f, 0.5f),
            radiusNorm = 0.2f,
            strength = 1.0f
        )
        val pushed = mesh.displacementAt(0.6f, 0.5f).u

        mesh.applyRestoreStroke(uv = Uv(0.6f, 0.5f), radiusNorm = 0.2f, strength = 0.5f)
        val halfRestored = mesh.displacementAt(0.6f, 0.5f).u
        mesh.applyRestoreStroke(uv = Uv(0.6f, 0.5f), radiusNorm = 0.2f, strength = 0.5f)
        val quarterLeft = mesh.displacementAt(0.6f, 0.5f).u

        assertEquals(pushed * 0.5f, halfRestored, 0.002f)
        assertEquals(pushed * 0.25f, quarterLeft, 0.002f)
        assertTrue("restore must never overshoot zero", abs(quarterLeft) < abs(pushed))
    }

    @Test
    fun shouldReportZero_whenEveryDisplacementIsBelowTheTolerance() {
        assertTrue(LiquifyMesh.empty().isZero())

        val tiny = LiquifyMesh.fromDisplacements(FloatArray(LiquifyMesh.COMPONENT_COUNT) { 1e-6f })
        assertTrue(tiny.isZero())

        val visible = LiquifyMesh.empty()
        visible.applyPushStroke(Uv(0.5f, 0.5f), Uv(0.51f, 0.5f), radiusNorm = 0.1f, strength = 1.0f)
        assertFalse(visible.isZero())
    }

    @Test
    fun shouldInterpolateLikeALinearTextureFetch_whenSamplingDisplacement() {
        val cells = FloatArray(LiquifyMesh.COMPONENT_COUNT)
        // Horizontal ramp: cell column x carries u = x / 127 scaled to 0.001 units per column.
        for (y in 0 until LiquifyMesh.GRID_SIZE) {
            for (x in 0 until LiquifyMesh.GRID_SIZE) {
                val index = (y * LiquifyMesh.GRID_SIZE + x) * 2
                cells[index] = x * 0.001f
                cells[index + 1] = -x * 0.002f
            }
        }
        val mesh = LiquifyMesh.fromDisplacements(cells)

        // A texel centre must read back that texel exactly (GL_NEAREST semantics at the centre).
        val texelCentre = 32.5f / LiquifyMesh.GRID_SIZE
        assertEquals(32 * 0.001f, mesh.displacementAt(texelCentre, 0.5f).u, 1e-4f)
        assertEquals(-32 * 0.002f, mesh.displacementAt(texelCentre, 0.5f).v, 1e-4f)

        // Halfway between two texel centres interpolates linearly, as GL_LINEAR does.
        val halfway = 33.0f / LiquifyMesh.GRID_SIZE
        assertEquals(32.5f * 0.001f, mesh.displacementAt(halfway, 0.5f).u, 1e-4f)
    }

    @Test
    fun shouldSurviveDeflateAndSha256_whenPersistingAndReloadingMesh() {
        val mesh = LiquifyMesh.empty()
        mesh.applyPushStroke(Uv(0.40f, 0.45f), Uv(0.46f, 0.47f), radiusNorm = 0.15f, strength = 0.8f)
        mesh.applyRestoreStroke(Uv(0.60f, 0.55f), radiusNorm = 0.08f, strength = 0.3f)

        val raw = LiquifyMeshCodec.encodeRaw(mesh)
        assertEquals(LiquifyMeshCodec.HALF_FLOAT_BYTE_COUNT, raw.size)
        val compressed = LiquifyMeshCodec.deflate(raw)
        assertTrue("smooth displacement grids must deflate well", compressed.size < raw.size)
        val digest = LiquifyMeshCodec.sha256Hex(compressed)

        val reloaded = LiquifyMeshCodec.decodePersisted(compressed, digest)

        assertNotNull(reloaded)
        assertMeshesMatch(mesh, reloaded!!)
    }

    @Test
    fun shouldRejectPayload_whenSha256DigestDoesNotMatch() {
        val mesh = LiquifyMesh.empty()
        mesh.applyPushStroke(Uv(0.5f, 0.5f), Uv(0.55f, 0.5f), radiusNorm = 0.1f, strength = 1.0f)
        val compressed = LiquifyMeshCodec.deflate(LiquifyMeshCodec.encodeRaw(mesh))
        val digest = LiquifyMeshCodec.sha256Hex(compressed)

        val corrupted = compressed.copyOf()
        corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2] + 1).toByte()

        assertNull(LiquifyMeshCodec.decodePersisted(corrupted, digest))
    }

    @Test
    fun shouldRejectPayload_whenTheByteStreamIsTruncated() {        val mesh = LiquifyMesh.empty()
        mesh.applyPushStroke(Uv(0.5f, 0.5f), Uv(0.55f, 0.5f), radiusNorm = 0.1f, strength = 1.0f)
        val compressed = LiquifyMeshCodec.deflate(LiquifyMeshCodec.encodeRaw(mesh))
        val truncated = compressed.copyOf(compressed.size / 2)
        // The digest is recomputed over the truncated bytes, so only the length check can reject it.
        val truncatedDigest = LiquifyMeshCodec.sha256Hex(truncated)

        assertNull(LiquifyMeshCodec.decodePersisted(truncated, truncatedDigest))
    }

    @Test
    fun shouldPreserveDisplacementWithinHalfFloatTolerance_whenConvertingToHalfFloatBits() {
        val cells = FloatArray(LiquifyMesh.COMPONENT_COUNT) { index -> ((index % 251) - 125) * 0.0008f }
        val mesh = LiquifyMesh.fromDisplacements(cells)

        val restored = LiquifyMesh.fromHalfFloatBits(mesh.toHalfFloatBits())

        for (index in cells.indices) {
            assertEquals(cells[index], restored.displacementRaw(index), 5e-4f)
        }
    }

    @Test
    fun shouldCapHistoryAt50StepsAndThreePointOneTwoFiveMebibytes_whenRecordingStrokes() {
        val history = LiquifyHistory()
        val mesh = LiquifyMesh.empty()
        repeat(60) { step ->
            history.record(mesh)
            mesh.applyPushStroke(
                prevUv = Uv(0.1f + step * 0.001f, 0.5f),
                nextUv = Uv(0.11f + step * 0.001f, 0.5f),
                radiusNorm = 0.05f,
                strength = 1.0f
            )
        }

        assertEquals(50, history.undoDepth)
        assertEquals(50 * LiquifyMeshCodec.HALF_FLOAT_BYTE_COUNT, history.memoryFootprintBytes())
        assertTrue(history.memoryFootprintBytes() <= THREE_POINT_ONE_TWO_FIVE_MEBIBYTES)
    }

    @Test
    fun shouldRestoreTheExactPreStrokeMesh_whenUndoingAndRedoingAStroke() {
        val history = LiquifyHistory()
        val mesh = LiquifyMesh.empty()
        mesh.applyPushStroke(Uv(0.5f, 0.5f), Uv(0.54f, 0.5f), radiusNorm = 0.1f, strength = 1.0f)
        val before = mesh.displacementAt(0.5f, 0.5f).u

        history.record(mesh)
        mesh.applyPushStroke(Uv(0.5f, 0.5f), Uv(0.58f, 0.5f), radiusNorm = 0.1f, strength = 1.0f)
        val after = mesh.displacementAt(0.5f, 0.5f).u
        assertTrue(abs(after) > abs(before))

        val undone = history.undo(mesh)
        assertNotNull(undone)
        assertEquals(before, undone!!.displacementAt(0.5f, 0.5f).u, 5e-4f)

        val redone = history.redo(undone)
        assertNotNull(redone)
        assertEquals(after, redone!!.displacementAt(0.5f, 0.5f).u, 5e-4f)
        assertFalse(history.canRedo)
    }

    @Test
    fun shouldDropTheLiquifyReference_whenThePersistedMetadataIsUnsafeOrMismatched() {
        val traversing = EditGraph(
            geometry = GeometryOps(
                liquify = LiquifyRef(meshRelPath = "../../secrets/liquify_128x128.bin")
            )
        )
        assertNull(EditValidator.sanitize(traversing).geometry.liquify)

        val absolute = EditGraph(
            geometry = GeometryOps(liquify = LiquifyRef(meshRelPath = "/data/liquify_128x128.bin"))
        )
        assertNull(EditValidator.sanitize(absolute).geometry.liquify)

        val wrongGrid = EditGraph(
            geometry = GeometryOps(
                liquify = LiquifyRef(
                    meshRelPath = "edits/7/liquify_128x128.bin",
                    gridSize = 64
                )
            )
        )
        assertNull(EditValidator.sanitize(wrongGrid).geometry.liquify)

        val valid = EditGraph(
            geometry = GeometryOps(
                liquify = LiquifyRef(
                    meshRelPath = "edits/7/liquify_128x128.bin",
                    gridSize = LiquifyMesh.GRID_SIZE,
                    sha256 = "a".repeat(64)
                )
            )
        )
        assertEquals(
            "edits/7/liquify_128x128.bin",
            EditValidator.sanitize(valid).geometry.liquify?.meshRelPath
        )
    }

    private fun assertMeshesMatch(expected: LiquifyMesh, actual: LiquifyMesh) {
        assertEquals(expected.gridSize, actual.gridSize)
        for (index in 0 until LiquifyMesh.CELL_COUNT) {
            assertEquals(expected.displacementRaw(index * 2), actual.displacementRaw(index * 2), 5e-4f)
            assertEquals(expected.displacementRaw(index * 2 + 1), actual.displacementRaw(index * 2 + 1), 5e-4f)
        }
    }

    private companion object {
        const val THREE_POINT_ONE_TWO_FIVE_MEBIBYTES = 3_276_800
    }
}
