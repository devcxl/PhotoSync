package cn.devcxl.photosync.editor.domain

import kotlin.math.abs
import kotlin.math.hypot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [FaceMlsSolver] — the 478-point multi-face MLS reshaping contract.
 *
 * The grid is judged the way Pass A consumes it: as a displacement field sampled at normalized
 * coordinates, never as internal solver state.
 */
class FaceMlsSolverTest {

    @Test
    fun shouldReturnAnAllZeroGrid_whenEveryReshapeSliderIsNeutral() {
        val grid = FaceMlsSolver.solve(listOf(face()), FaceReshapeWeights())
        val solved = FaceMlsSolver.solveGrid(listOf(face()), FaceReshapeWeights())

        assertEquals(FaceMlsSolver.OUTPUT_GRID * FaceMlsSolver.OUTPUT_GRID * 2, grid.size)
        assertTrue(grid.all { it == 0.0f })
        assertTrue(solved.all { it == 0.0f })
    }

    @Test
    fun shouldKeepEveryBorderPinFixed_whenTheFaceIsReshaped() {
        val weights = FaceReshapeWeights(face = 1.0f, eyes = 1.0f, nose = 1.0f, chin = 1.0f)

        val grid = FaceMlsSolver.solveGrid(listOf(face()), weights)

        for (pin in FaceMlsSolver.boundaryPins()) {
            val displacement = FaceMlsSolver.displacementAt(grid, FaceMlsSolver.SOLVE_GRID, pin.u, pin.v)
            assertTrue(
                "border pin $pin moved by $displacement",
                hypot(displacement.u, displacement.v) < 1e-4f
            )
        }
    }

    @Test
    fun shouldKeepEveryHaloPinFixed_whenTheFaceIsReshaped() {
        val landmarks = face()
        val weights = FaceReshapeWeights(face = 1.0f, eyes = 1.0f, smile = 1.0f)

        val grid = FaceMlsSolver.solveGrid(listOf(landmarks), weights)

        for (pin in FaceMlsSolver.haloPins(landmarks)) {
            val displacement = FaceMlsSolver.displacementAt(grid, FaceMlsSolver.SOLVE_GRID, pin.u, pin.v)
            assertTrue(
                "halo pin $pin moved by $displacement",
                hypot(displacement.u, displacement.v) < 1e-4f
            )
        }
    }

    @Test
    fun shouldMoveTheJawInwards_whenTheFaceIsSlimmed() {
        val landmarks = face()
        val frame = FaceMlsSolver.faceFrame(landmarks)
        val weights = FaceReshapeWeights(face = 1.0f)

        val grid = FaceMlsSolver.solveGrid(listOf(landmarks), weights)

        val gonion = landmarks.uv(172)
        val displacement =
            FaceMlsSolver.displacementAt(grid, FaceMlsSolver.SOLVE_GRID, gonion.u, gonion.v)
        val towardsAxis = -(displacement.u * frame.u.u + displacement.v * frame.u.v)
        assertTrue("gonion must move towards the nose axis", towardsAxis > 1e-4f)
    }

    @Test
    fun shouldIsolateEachFaceFromItsNeighbours_whenAGroupPhotoIsReshaped() {
        val left = FaceMeshFixtures.face(centreU = 0.22f, centreV = 0.45f, width = 0.16f, height = 0.20f)
        val right = FaceMeshFixtures.face(centreU = 0.78f, centreV = 0.45f, width = 0.16f, height = 0.20f)
        val weights = FaceReshapeWeights(face = 1.0f, eyes = 1.0f)

        val solo = FaceMlsSolver.solveGrid(listOf(left), weights)
        val pair = FaceMlsSolver.solveGrid(listOf(left, right), weights)

        val probe = left.uv(172)
        val soloDisplacement =
            FaceMlsSolver.displacementAt(solo, FaceMlsSolver.SOLVE_GRID, probe.u, probe.v)
        val pairDisplacement =
            FaceMlsSolver.displacementAt(pair, FaceMlsSolver.SOLVE_GRID, probe.u, probe.v)
        val gap = FaceMlsSolver.displacementAt(pair, FaceMlsSolver.SOLVE_GRID, 0.5f, 0.45f)

        assertTrue(hypot(soloDisplacement.u, soloDisplacement.v) > 0.0f)
        assertTrue(
            "a neighbouring face must not add warp to an already-solved face",
            hypot(
                pairDisplacement.u - soloDisplacement.u,
                pairDisplacement.v - soloDisplacement.v
            ) < 1e-4f
        )
        assertTrue(
            "the gap between two faces must stay untouched",
            hypot(gap.u, gap.v) < 1e-4f
        )
    }

    @Test
    fun shouldReshapeAlongTheFaceAxes_whenTheHeadIsTilted() {
        val landmarks = face(rotationDegrees = 30.0f)
        val frame = FaceMlsSolver.faceFrame(landmarks)

        val grid = FaceMlsSolver.solveGrid(listOf(landmarks), FaceReshapeWeights(face = 1.0f))

        val gonion = landmarks.uv(172)
        val displacement =
            FaceMlsSolver.displacementAt(grid, FaceMlsSolver.SOLVE_GRID, gonion.u, gonion.v)
        val alongU = -(displacement.u * frame.u.u + displacement.v * frame.u.v)
        val alongV = abs(displacement.u * frame.v.u + displacement.v * frame.v.v)
        assertTrue("the slimming must follow the tilted horizontal axis", alongU > 1e-4f)
        assertTrue("horizontal slimming must not leak into a vertical shift", alongV < 0.35f * alongU)
    }

    @Test
    fun shouldDropBackgroundFaces_whenTheirBoundingBoxIsTiny() {
        val tiny = FaceMeshFixtures.face(
            centreU = 0.5f,
            centreV = 0.5f,
            width = 0.03f,
            height = 0.04f
        )
        val large = face()

        val selected = FaceMlsSolver.selectFaces(listOf(tiny, large))

        assertTrue(tiny.boundingBoxArea() < FaceMlsSolver.MIN_FACE_AREA_FRACTION)
        assertEquals(listOf(large), selected)
    }

    @Test
    fun shouldCapTheNumberOfReshapedFaces_whenAGroupPhotoHasMoreThanFive() {
        val faces = (0 until 7).map { index ->
            FaceMeshFixtures.face(
                centreU = 0.1f + 0.12f * index,
                centreV = 0.5f,
                width = 0.09f,
                height = 0.12f
            )
        }

        assertEquals(FaceMlsSolver.MAX_FACES, FaceMlsSolver.selectFaces(faces).size)
    }

    @Test
    fun shouldKeepTheSolvedGridBounded_whenEveryAxisIsPushedToTheLimit() {
        val weights = FaceReshapeWeights(
            face = 1.0f,
            eyes = 1.0f,
            nose = 1.0f,
            chin = 1.0f,
            forehead = 1.0f,
            smile = 1.0f
        )

        val grid = FaceMlsSolver.solveGrid(listOf(face()), weights)

        assertTrue(grid.isNotEmpty())
        for (index in grid.indices) {
            assertTrue(
                "cell $index exceeded the displacement contract",
                abs(grid[index]) <= FaceMlsSolver.MAX_DISPLACEMENT
            )
        }
    }

    private fun face(rotationDegrees: Float = 0.0f): FaceLandmarks = FaceMeshFixtures.face(
        centreU = 0.5f,
        centreV = 0.45f,
        width = 0.16f,
        height = 0.20f,
        rotationDegrees = rotationDegrees
    )
}
