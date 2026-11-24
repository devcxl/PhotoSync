package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [SpatialDetailPlanner] — the physical Pass C draw plan, i.e. the "0 Draw Call"
 * short-circuit contract of the spatial-detail group.
 */
class SpatialDetailPlannerTest {

    @Test
    fun shouldPlanZeroDrawCalls_whenEverySpatialParameterAndGlowIsNeutral() {
        val params = SpatialDetailParams.from(EditGraph())

        val plan = SpatialDetailPlanner.plan(params, prepassAvailable = true)

        assertTrue(params.isZeroDrawCallShortCircuit)
        assertTrue(plan.isZeroDrawCallShortCircuit)
        assertEquals(0, plan.drawCalls)
        assertTrue(plan.stages.isEmpty())
        assertFalse(plan.prepassRequired)
    }

    @Test
    fun shouldPlanOneDrawCallPerActiveStage_whenEverySpatialSliderIsRaised() {
        val graph = EditGraph(
            detail = DetailOps(
                clarity = 25,
                texture = 20,
                dehaze = 30,
                sharpen = SharpenOps(amount = 40),
                nr = NoiseReductionOps(luma = 15)
            )
        )

        val plan = SpatialDetailPlanner.plan(
            SpatialDetailParams.from(graph),
            prepassAvailable = true
        )

        assertEquals(
            listOf(
                SpatialDetailStage.NOISE_REDUCTION,
                SpatialDetailStage.DEHAZE,
                SpatialDetailStage.CLARITY_TEXTURE,
                SpatialDetailStage.SHARPEN
            ),
            plan.stages
        )
        assertEquals(4, plan.drawCalls)
        assertTrue(plan.prepassRequired)
        assertTrue(plan.skippedStages.isEmpty())
    }

    @Test
    fun shouldDropPrepassStages_whenThePrepassIsNotReady() {
        val graph = EditGraph(
            detail = DetailOps(
                clarity = 25,
                dehaze = 30,
                sharpen = SharpenOps(amount = 40),
                nr = NoiseReductionOps(chroma = 20)
            )
        )

        val plan = SpatialDetailPlanner.plan(
            SpatialDetailParams.from(graph),
            prepassAvailable = false
        )

        assertEquals(
            listOf(SpatialDetailStage.NOISE_REDUCTION, SpatialDetailStage.SHARPEN),
            plan.stages
        )
        assertEquals(
            listOf(SpatialDetailStage.DEHAZE, SpatialDetailStage.CLARITY_TEXTURE),
            plan.skippedStages
        )
        assertEquals(2, plan.drawCalls)
    }

    @Test
    fun shouldTriggerClarityThroughGlowAlone_whenNoSpatialSliderMoved() {
        val params = SpatialDetailParams.from(EditGraph(tone = ToneOps(glow = 20)))

        val plan = SpatialDetailPlanner.plan(params, prepassAvailable = true)

        assertFalse(params.isZeroDrawCallShortCircuit)
        assertEquals(listOf(SpatialDetailStage.CLARITY_TEXTURE), plan.stages)
        assertEquals(1, plan.drawCalls)
    }

    @Test
    fun shouldIgnoreTheNoiseReductionDetailSlider_whenNothingElseIsRaised() {
        val params = SpatialDetailParams.from(
            EditGraph(detail = DetailOps(nr = NoiseReductionOps(detail = 85)))
        )

        assertTrue(params.isZeroDrawCallShortCircuit)
        assertEquals(0, SpatialDetailPlanner.plan(params, prepassAvailable = true).drawCalls)
    }

    @Test
    fun shouldNotShortCircuit_whenPortraitSkinSmoothingNeedsTheChain() {
        val params = SpatialDetailParams.from(
            EditGraph(portrait = PortraitOps(skin = SkinOps(smooth = 30)))
        )

        assertFalse(params.isZeroDrawCallShortCircuit)
        assertTrue(params.hasSkinSmoothing)
    }

    @Test
    fun shouldPlanSkinSmoothingAsStageZero_whenTheSkinMaskIsAvailable() {
        val graph = EditGraph(
            portrait = PortraitOps(skin = SkinOps(smooth = 40, evenTone = 20)),
            detail = DetailOps(sharpen = SharpenOps(amount = 30))
        )

        val plan = SpatialDetailPlanner.plan(
            SpatialDetailParams.from(graph),
            prepassAvailable = true,
            skinMaskAvailable = true
        )

        assertEquals(
            listOf(SpatialDetailStage.SKIN_SMOOTHING, SpatialDetailStage.SHARPEN),
            plan.stages
        )
        assertTrue(plan.skippedStages.isEmpty())
    }

    @Test
    fun shouldDropSkinSmoothing_whenNoAnalysisMaskIsBound() {
        val params = SpatialDetailParams.from(
            EditGraph(portrait = PortraitOps(skin = SkinOps(smooth = 55)))
        )

        val plan = SpatialDetailPlanner.plan(
            params,
            prepassAvailable = true,
            skinMaskAvailable = false
        )

        assertTrue(plan.isZeroDrawCallShortCircuit)
        assertEquals(listOf(SpatialDetailStage.SKIN_SMOOTHING), plan.skippedStages)
    }

    @Test
    fun shouldIgnoreSkinTextureAlone_whenSmoothingAndEvenToneAreNeutral() {
        val params = SpatialDetailParams.from(
            EditGraph(portrait = PortraitOps(skin = SkinOps(texture = 90)))
        )

        assertFalse(params.hasSkinFrequencySeparation)
        assertTrue(params.isZeroDrawCallShortCircuit)
    }
}
