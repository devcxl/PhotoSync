package cn.devcxl.photosync.editor.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [QuadMesh] vertical orientation and [PreviewDrawRequests] FBO vs screen flags.
 *
 * Every offscreen FBO pass must preserve top-down row order (`y = -1 -> v = 0`, `y = +1 -> v = 1`),
 * and only the single final draw into the default screen framebuffer (`FBO 0`) flips `v` once.
 * Violating this rule flips the preview upside-down whenever an FBO pass (Pass A, Pass C or Pass D)
 * turns on or off.
 */
class QuadMeshOrientationTest {

    @Test
    fun shouldKeepTopDownRowsWithoutFlipping_whenDrawingToOffscreenFbo() {
        val vertices = QuadMesh.verticesFor(QuadOrientation.OFFSCREEN)

        // Vertex 0: bottom-left NDC (-1, -1) = FBO row 0 -> image top (u=0, v=0)
        assertEquals(0.0f, vertices[3], 1e-6f)
        // Vertex 1: bottom-right NDC (+1, -1) = FBO row 0 -> image top (u=1, v=0)
        assertEquals(0.0f, vertices[7], 1e-6f)
        // Vertex 2: top-left NDC (-1, +1) = FBO row H-1 -> image bottom (u=0, v=1)
        assertEquals(1.0f, vertices[11], 1e-6f)
        // Vertex 3: top-right NDC (+1, +1) = FBO row H-1 -> image bottom (u=1, v=1)
        assertEquals(1.0f, vertices[15], 1e-6f)
    }

    @Test
    fun shouldFlipVerticalOnce_whenPresentingToDefaultScreenFramebuffer() {
        val vertices = QuadMesh.verticesFor(QuadOrientation.SCREEN)

        // Screen bottom (y = -1) must sample image bottom (v = 1)
        assertEquals(1.0f, vertices[3], 1e-6f)
        assertEquals(1.0f, vertices[7], 1e-6f)
        // Screen top (y = +1) must sample image top (v = 0)
        assertEquals(0.0f, vertices[11], 1e-6f)
        assertEquals(0.0f, vertices[15], 1e-6f)
    }

    @Test
    fun shouldSetScreenFlipOnlyOnViewportPassB_whenBuildingPreviewDrawRequests() {
        val request = PreviewFrameRequest(
            sourceTextureId = 1,
            sourceWidth = 1200,
            sourceHeight = 800,
            fullResolutionShortEdge = 800,
            sourceAspect = 1.5f,
            isLinearInput = false,
            autoBrightGain = 1.0f,
            viewportWidth = 1080,
            viewportHeight = 1920,
            canvas = CanvasViewportTransform(),
            state = RenderFrameState(),
            compareOriginal = false,
            graphRevision = 1
        )
        val frame = PreviewFrameInput(
            textureId = 1,
            isLinear = false,
            aspect = 1.5f,
            texelWidth = 1200,
            texelHeight = 800
        )

        val viewportDraw = PreviewDrawRequests.viewportPassB(request, frame, 0, 0.0f)
        val offscreenDraw = PreviewDrawRequests.offscreenPassB(request, frame, 0, 0.0f)
        val effectsDraw = PreviewDrawRequests.effects(request, frame, 0)

        assertTrue(viewportDraw.flipVerticalForScreen)
        assertFalse(offscreenDraw.flipVerticalForScreen)
        assertFalse(effectsDraw.passBDraw.flipVerticalForScreen)
    }

    @Test
    fun shouldProduceUprightImageForEveryPassCombination_whenSimulatingMultiPassVMapping() {
        fun applyQuad(inputTopIsZero: Boolean, orientation: QuadOrientation): Boolean =
            when (orientation) {
                QuadOrientation.OFFSCREEN -> inputTopIsZero
                QuadOrientation.SCREEN -> !inputTopIsZero
            }

        for (passA in listOf(false, true)) {
            for (passCStages in 0..5) {
                for (passD in listOf(false, true)) {
                    var topIsZero = true // Source texture uploaded top-down (v=0 is image top)
                    if (passA) topIsZero = applyQuad(topIsZero, QuadOrientation.OFFSCREEN)
                    val screenTopIsImageTop = when {
                        passCStages > 0 && passD -> {
                            topIsZero = applyQuad(topIsZero, QuadOrientation.OFFSCREEN) // Pass B offscreen
                            repeat(passCStages) {
                                topIsZero = applyQuad(topIsZero, QuadOrientation.OFFSCREEN)
                            }
                            !applyQuad(topIsZero, QuadOrientation.SCREEN) // SCREEN flips once -> upright
                        }
                        passCStages > 0 -> {
                            topIsZero = applyQuad(topIsZero, QuadOrientation.OFFSCREEN)
                            repeat(passCStages) {
                                topIsZero = applyQuad(topIsZero, QuadOrientation.OFFSCREEN)
                            }
                            !applyQuad(topIsZero, QuadOrientation.SCREEN)
                        }
                        passD -> {
                            topIsZero = applyQuad(topIsZero, QuadOrientation.OFFSCREEN) // Pass B staging
                            !applyQuad(topIsZero, QuadOrientation.SCREEN) // Pass D to screen
                        }
                        else -> !applyQuad(topIsZero, QuadOrientation.SCREEN) // Pass B to screen
                    }
                    assertTrue(
                        "Expected upright preview for passA=$passA, passCStages=$passCStages, passD=$passD",
                        screenTopIsImageTop
                    )
                }
            }
        }
    }
}
