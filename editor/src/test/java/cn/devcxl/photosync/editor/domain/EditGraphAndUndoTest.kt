package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditGraphAndUndoTest {

    @Test
    fun shouldReturnTrueForIsIdentity_whenAllParametersAreDefault() {
        val graph = EditGraph()
        assertTrue(graph.isIdentity())
    }

    @Test
    fun shouldReturnFalseForIsIdentity_whenExposureOrBaseLookIsModified() {
        val modifiedExposure = EditGraph(
            tone = ToneOps(exposure = 0.5f)
        )
        assertFalse(modifiedExposure.isIdentity())

        val modifiedBaseLook = EditGraph(
            baseLook = BaseLook.LINEAR_NEUTRAL
        )
        assertFalse(modifiedBaseLook.isIdentity())
    }

    @Test
    fun shouldClampOutOfBoundsAndResetNaNOrInfinity_whenSanitizingEditGraph() {
        val dirty = EditGraph(
            tone = ToneOps(
                exposure = Float.NaN,
                brightness = 250,
                contrast = -180,
                highlights = 40
            ),
            geometry = GeometryOps(
                rotate90 = 7,
                straighten = Float.POSITIVE_INFINITY,
                crop = CropRect(left = -0.5f, top = 0.2f, right = 1.5f, bottom = 0.1f)
            ),
            detail = DetailOps(
                sharpen = SharpenOps(amount = 300, radius = 9.0f, threshold = -10, masking = 120)
            )
        )

        val clean = EditValidator.sanitize(dirty)

        assertEquals(0.0f, clean.tone.exposure, 0.0001f)
        assertEquals(100, clean.tone.brightness)
        assertEquals(-100, clean.tone.contrast)
        assertEquals(40, clean.tone.highlights)
        assertEquals(3, clean.geometry.rotate90)
        assertEquals(0.0f, clean.geometry.straighten, 0.0001f)
        assertTrue(clean.geometry.crop.left >= 0.0f)
        assertTrue(clean.geometry.crop.right <= 1.0f)
        assertTrue(clean.geometry.crop.bottom > clean.geometry.crop.top)
        assertEquals(150, clean.detail.sharpen.amount)
        assertEquals(3.0f, clean.detail.sharpen.radius, 0.0001f)
        assertEquals(0, clean.detail.sharpen.threshold)
        assertEquals(100, clean.detail.sharpen.masking)
    }

    @Test
    fun shouldRoundTripJsonSerializationPreservingAllGroups_whenSerializingAndParsing() {
        val original = EditGraph(
            schema = 1,
            baseLook = BaseLook.LINEAR_NEUTRAL,
            tone = ToneOps(
                exposure = -1.25f,
                brightness = 15,
                contrast = -20,
                glow = 35,
                highlights = -45,
                shadows = 25,
                whites = 10,
                blacks = -8
            ),
            color = ColorOps(
                temp = -12,
                tint = 6,
                saturation = 10,
                vibrance = 22,
                fade = 15,
                lut = LutRef(id = "kodak_2383", strength = 65)
            ),
            detail = DetailOps(
                sharpen = SharpenOps(amount = 50, radius = 1.5f, threshold = 5, masking = 25),
                clarity = 18,
                texture = 12,
                dehaze = 20,
                nr = NoiseReductionOps(luma = 15, chroma = 30, detail = 60)
            ),
            effects = EffectsOps(
                grain = GrainOps(amount = 20, size = 30, roughness = 45, seed = 777123),
                vignette = VignetteOps(amount = -25, midpoint = 45, feather = 60, roundness = 10),
                ca = ChromaticAberrationOps(redCyan = -15, blueYellow = 10, defringe = 30)
            ),
            portrait = PortraitOps(
                skin = SkinOps(smooth = 40, texture = 55, evenTone = 20),
                reshape = ReshapeOps(face = 25, eyes = 15, nose = -10, chin = 5, forehead = 0, smile = 12),
                body = BodyOps(waist = 20, legs = 30, waistY = 0.48f, hipY = 0.58f)
            )
        )

        val json = EditGraphJsonCodec.encode(original)
        val decoded = EditGraphJsonCodec.decode(json)

        assertEquals(original, decoded)
    }

    @Test
    fun shouldReturnNull_whenDecodingBlankOrMalformedJson() {
        org.junit.Assert.assertNull(EditGraphJsonCodec.decode("   "))
        org.junit.Assert.assertNull(EditGraphJsonCodec.decode("not_json_object"))
    }

    @Test
    fun shouldCoalesceSliderChangesWithin300msWindowIntoSingleUndoStep_whenDraggingContinuously() {
        val controller = EditorUndoController(maxDepth = 50, coalesceWindowMs = 300L)

        controller.onParameterDrag(
            newGraph = EditGraph(tone = ToneOps(exposure = 0.5f)),
            parameterKey = EditParameterKey.TONE_EXPOSURE,
            timestampMs = 1000L
        )
        controller.onParameterDrag(
            newGraph = EditGraph(tone = ToneOps(exposure = 1.0f)),
            parameterKey = EditParameterKey.TONE_EXPOSURE,
            timestampMs = 1120L
        )
        controller.onParameterDrag(
            newGraph = EditGraph(tone = ToneOps(exposure = 1.5f)),
            parameterKey = EditParameterKey.TONE_EXPOSURE,
            timestampMs = 1280L
        )
        controller.onParameterRelease()

        assertEquals(1, controller.undoDepth)
        assertEquals(1.5f, controller.currentGraph.tone.exposure, 0.0001f)

        val afterUndo = controller.undo()
        assertEquals(0.0f, afterUndo.tone.exposure, 0.0001f)
        assertEquals(0, controller.undoDepth)
        assertEquals(1, controller.redoDepth)

        val afterRedo = controller.redo()
        assertEquals(1.5f, afterRedo.tone.exposure, 0.0001f)
    }

    @Test
    fun shouldCreateSeparateUndoStepsAndCapAtMaxDepth_whenSeparateStrokesOccur() {
        val controller = EditorUndoController(maxDepth = 3, coalesceWindowMs = 300L)

        controller.onParameterDrag(
            newGraph = EditGraph(tone = ToneOps(exposure = 0.5f)),
            parameterKey = EditParameterKey.TONE_EXPOSURE,
            timestampMs = 1000L
        )
        controller.onParameterRelease()

        controller.onParameterDrag(
            newGraph = EditGraph(tone = ToneOps(exposure = 1.0f)),
            parameterKey = EditParameterKey.TONE_EXPOSURE,
            timestampMs = 1050L
        )
        controller.onParameterRelease()

        controller.onParameterDrag(
            newGraph = EditGraph(tone = ToneOps(exposure = 1.5f)),
            parameterKey = EditParameterKey.TONE_EXPOSURE,
            timestampMs = 1100L
        )
        controller.onParameterRelease()

        controller.onParameterDrag(
            newGraph = EditGraph(tone = ToneOps(exposure = 2.0f)),
            parameterKey = EditParameterKey.TONE_EXPOSURE,
            timestampMs = 1150L
        )
        controller.onParameterRelease()

        assertEquals(3, controller.undoDepth)
        assertEquals(1.5f, controller.undo().tone.exposure, 0.0001f)
        assertEquals(1.0f, controller.undo().tone.exposure, 0.0001f)
        assertEquals(0.5f, controller.undo().tone.exposure, 0.0001f)
        assertEquals(0, controller.undoDepth)
    }

    @Test
    fun shouldBeIdentityAtZeroEvAndScaleLinearLight_whenApplyingExposureShaderMath() {
        val neutral8Bit = 128
        assertEquals(
            neutral8Bit,
            ExposureShaderMath.applyExposureToSrgb8(neutral8Bit, exposureEv = 0.0f)
        )

        val brighter = ExposureShaderMath.applyExposureToSrgb8(neutral8Bit, exposureEv = 1.0f)
        val darker = ExposureShaderMath.applyExposureToSrgb8(neutral8Bit, exposureEv = -1.0f)
        assertTrue("Positive EV must brighten pixel", brighter > neutral8Bit)
        assertTrue("Negative EV must darken pixel", darker < neutral8Bit)
        assertEquals(0, ExposureShaderMath.applyExposureToSrgb8(0, exposureEv = 2.5f))
        assertTrue(ExposureShaderMath.applyExposureToSrgb8(255, exposureEv = 5.0f) <= 255)
    }
}
