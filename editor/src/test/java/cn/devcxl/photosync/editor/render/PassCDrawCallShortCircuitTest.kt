package cn.devcxl.photosync.editor.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.DetailOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.NoiseReductionOps
import cn.devcxl.photosync.editor.domain.PortraitOps
import cn.devcxl.photosync.editor.domain.SharpenOps
import cn.devcxl.photosync.editor.domain.SkinOps
import cn.devcxl.photosync.editor.domain.ToneOps

/**
 * Seam under test: [EditorGlRenderer] — the observable Pass C activation behind the "0 Draw Call"
 * contract. `isPassCActive` must stay false whenever the spatial group and glow are neutral, and a
 * proxy drag must never run the spatial chain.
 */
class PassCDrawCallShortCircuitTest {

    @Test
    fun shouldReportZeroPassCDrawCalls_whenEverySpatialParameterIsNeutral() {
        val renderer = EditorGlRenderer()

        renderer.applyState(EditGraph(), useFastProxy = false)

        assertFalse(renderer.isPassCActive)
        assertEquals(0, renderer.lastPassCDrawCalls)
    }

    @Test
    fun shouldNotActivatePassC_whenOnlyTheNoiseReductionDetailSliderMoved() {
        val renderer = EditorGlRenderer()

        renderer.applyState(
            EditGraph(detail = DetailOps(nr = NoiseReductionOps(detail = 90))),
            useFastProxy = false
        )

        assertFalse(renderer.isPassCActive)
    }

    @Test
    fun shouldActivatePassC_whenClarityIsRaised() {
        val renderer = EditorGlRenderer()

        renderer.applyState(EditGraph(detail = DetailOps(clarity = 30)), useFastProxy = false)

        assertTrue(renderer.isPassCActive)
    }

    @Test
    fun shouldActivatePassC_whenOnlyGlowCouplesClarity() {
        val renderer = EditorGlRenderer()

        renderer.applyState(EditGraph(tone = ToneOps(glow = 35)), useFastProxy = false)

        assertTrue(renderer.isPassCActive)
    }

    @Test
    fun shouldSkipPassC_whenDraggingTheFastProxy() {
        val renderer = EditorGlRenderer()

        renderer.applyState(
            EditGraph(
                detail = DetailOps(
                    clarity = 40,
                    dehaze = 30,
                    sharpen = SharpenOps(amount = 50),
                    nr = NoiseReductionOps(luma = 20)
                )
            ),
            useFastProxy = true
        )

        assertFalse(renderer.isPassCActive)
    }

    @Test
    fun shouldActivatePassC_whenPortraitSkinSmoothingOwnsTheChain() {
        val renderer = EditorGlRenderer()

        renderer.applyState(
            EditGraph(portrait = PortraitOps(skin = SkinOps(smooth = 25))),
            useFastProxy = false
        )

        assertTrue(renderer.isPassCActive)
    }
}
