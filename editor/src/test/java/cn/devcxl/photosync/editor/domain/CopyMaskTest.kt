package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [CopyMask] and [PresetGraphMerger] — the cross-photo parameter transfer contract.
 */
class CopyMaskTest {

    private fun busyGraph(): EditGraph = EditGraph(
        baseLook = BaseLook.LINEAR_NEUTRAL,
        geometry = GeometryOps(
            rotate90 = 2,
            flipH = true,
            straighten = 4.5f,
            crop = CropRect(0.1f, 0.2f, 0.9f, 0.8f),
            perspective = listOf(0.1f, 0.0f, 0.95f, 0.0f, 0.95f, 0.95f, 0.0f, 0.95f),
            liquify = LiquifyRef(meshRelPath = "edits/7/liquify_128x128.bin", sha256 = "")
        ),
        tone = ToneOps(
            exposure = 0.9f,
            brightness = 12,
            contrast = -8,
            glow = 5,
            highlights = -20,
            shadows = 30,
            whites = 4,
            blacks = -6
        ),
        color = ColorOps(
            temp = 22,
            tint = -11,
            saturation = 9,
            vibrance = 14,
            split = SplitToningOps(hiHue = 30, hiSat = 12, shHue = 200, shSat = 8, balance = 5),
            fade = 17
        ),
        detail = DetailOps(
            sharpen = SharpenOps(amount = 40, radius = 1.4f, threshold = 10, masking = 30),
            clarity = 11,
            texture = -7,
            dehaze = 13,
            nr = NoiseReductionOps(luma = 25, chroma = 15, detail = 70)
        ),
        effects = EffectsOps(
            grain = GrainOps(amount = 20, size = 40, roughness = 55, seed = 1234),
            vignette = VignetteOps(amount = -18, midpoint = 44, feather = 60, roundness = 7),
            ca = ChromaticAberrationOps(redCyan = 3, blueYellow = -2, defringe = 12)
        ),
        portrait = PortraitOps(
            skin = SkinOps(smooth = 35, texture = 42, evenTone = 18),
            reshape = ReshapeOps(face = 22, eyes = 10, nose = -5, chin = 8, forehead = -3, smile = 15),
            body = BodyOps(waist = 18, legs = 12, waistY = 0.45f, hipY = 0.7f)
        )
    )

    @Test
    fun shouldCopyOnlyLookAxesByDefault_whenMaskIsUntouched() {
        val source = busyGraph()
        val target = EditGraph()

        val pasted = CopyMask().apply(source, target)

        assertEquals(source.baseLook, pasted.baseLook)
        assertEquals(source.tone, pasted.tone)
        assertEquals(source.color, pasted.color)
        assertEquals(source.detail, pasted.detail)
        assertEquals(source.effects, pasted.effects)
        assertEquals(source.portrait.skin, pasted.portrait.skin)
    }

    @Test
    fun shouldExcludeGeometryLiquifyAndPortraitWarpByDefault_whenMaskIsUntouched() {
        val pasted = CopyMask().apply(busyGraph(), EditGraph())

        assertTrue(pasted.geometry.isIdentity())
        assertNull(pasted.geometry.liquify)
        assertTrue(pasted.portrait.reshape.isIdentity())
        assertTrue(pasted.portrait.body.isIdentity())
        assertNull(pasted.portrait.body.waistY)
        assertNull(pasted.portrait.body.hipY)
    }

    @Test
    fun shouldKeepTargetComposition_whenSourceCarriesItsOwnCropAndWarp() {
        val target = EditGraph(
            geometry = GeometryOps(
                rotate90 = 1,
                crop = CropRect(0.05f, 0.05f, 0.95f, 0.95f),
                liquify = LiquifyRef(meshRelPath = "edits/9/liquify_128x128.bin", sha256 = "")
            ),
            portrait = PortraitOps(
                reshape = ReshapeOps(face = -9),
                body = BodyOps(waist = -4, waistY = 0.32f, hipY = 0.66f)
            )
        )

        val pasted = CopyMask().apply(busyGraph(), target)

        assertEquals(1, pasted.geometry.rotate90)
        assertEquals(0.05f, pasted.geometry.crop.left, 1e-4f)
        assertEquals("edits/9/liquify_128x128.bin", pasted.geometry.liquify?.meshRelPath)
        assertEquals(-9, pasted.portrait.reshape.face)
        assertEquals(-4, pasted.portrait.body.waist)
        assertEquals(0.32f, pasted.portrait.body.waistY ?: 0f, 1e-4f)
        assertEquals(busyGraph().tone, pasted.tone)
    }

    @Test
    fun shouldCopyComposition_whenGeometryAndWarpAreExplicitlyIncluded() {
        val source = busyGraph()
        val mask = CopyMask(
            includeGeometryCropRotate = true,
            includeLiquify = true,
            includePortraitReshapeBody = true
        )

        val pasted = mask.apply(source, EditGraph())

        assertEquals(source.geometry, pasted.geometry)
        assertEquals(source.portrait.reshape, pasted.portrait.reshape)
        assertEquals(source.portrait.body, pasted.portrait.body)
        assertNotNull(pasted.geometry.liquify)
    }

    @Test
    fun shouldCopyOnlyLiquifyWithoutCrop_whenOnlyLiquifyIsIncluded() {
        val source = busyGraph()
        val target = EditGraph(geometry = GeometryOps(rotate90 = 3, flipV = true))

        val pasted = CopyMask(includeLiquify = true).apply(source, target)

        assertEquals(3, pasted.geometry.rotate90)
        assertTrue(pasted.geometry.flipV)
        assertNotNull(pasted.geometry.liquify)
        assertTrue(pasted.geometry.crop.isIdentity())
    }

    @Test
    fun shouldExcludeEveryAxis_whenAllIncludesAreDisabled() {
        val pasted = CopyMask(
            includeBaseLook = false,
            includeTone = false,
            includeColor = false,
            includeDetail = false,
            includeEffects = false,
            includePortraitSkin = false
        ).apply(busyGraph(), EditGraph())

        assertTrue(pasted.isIdentity())
    }

    @Test
    fun shouldExtractMaskedLook_whenExtractingFromSource() {
        val source = busyGraph()

        val extracted = CopyMask().extract(source)

        assertEquals(CopyMask().apply(source, EditGraph()), extracted)
        assertTrue(extracted.geometry.isIdentity())
        assertTrue(extracted.portrait.reshape.isIdentity())
    }

    @Test
    fun shouldSanitizeOutOfRangeSourceValues_whenApplyingMask() {
        val hostile = EditGraph(
            tone = ToneOps(exposure = Float.NaN, brightness = 5000, shadows = -9000)
        )

        val pasted = CopyMask().apply(hostile, EditGraph())

        assertEquals(0.0f, pasted.tone.exposure, 1e-4f)
        assertEquals(100, pasted.tone.brightness)
        assertEquals(-100, pasted.tone.shadows)
    }

    @Test
    fun shouldResetEveryAxis_whenClearingToNoPreset() {
        val target = busyGraph()

        val reset = PresetGraphMerger.resetToOriginal(target)

        assertTrue(reset.isIdentity())
        assertNull(reset.geometry.liquify)
        assertEquals(BaseLook.CAMERA_DEFAULT, reset.baseLook)
    }

    @Test
    fun shouldApplyPresetPortraitWorkWithoutTouchingGeometry_whenMergingPreset() {
        val preset = EditGraph(
            tone = ToneOps(exposure = 0.4f, contrast = 15),
            color = ColorOps(temp = 12),
            portrait = PortraitOps(
                skin = SkinOps(smooth = 25),
                reshape = ReshapeOps(face = 30),
                body = BodyOps(waist = 8)
            )
        )
        val target = EditGraph(
            geometry = GeometryOps(
                straighten = 3.0f,
                crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f),
                liquify = LiquifyRef(meshRelPath = "edits/9/liquify_128x128.bin", sha256 = "")
            ),
            portrait = PortraitOps(reshape = ReshapeOps(face = 14))
        )

        val merged = PresetGraphMerger.applyPreset(preset, target)

        assertEquals(0.4f, merged.tone.exposure, 1e-4f)
        assertEquals(15, merged.tone.contrast)
        assertEquals(12, merged.color.temp)
        assertEquals(25, merged.portrait.skin.smooth)
        assertEquals(30, merged.portrait.reshape.face)
        assertEquals(8, merged.portrait.body.waist)
        assertEquals(3.0f, merged.geometry.straighten, 1e-4f)
        assertEquals(0.1f, merged.geometry.crop.left, 1e-4f)
        assertNotNull(merged.geometry.liquify)
        assertFalse(merged.isIdentity())
    }

    @Test
    fun shouldExtractPortraitWorkButNoGeometry_whenSavingAPreset() {
        val source = busyGraph()

        val extracted = PresetGraphMerger.extractPreset(source)

        assertEquals(source.baseLook, extracted.baseLook)
        assertEquals(source.tone, extracted.tone)
        assertEquals(source.color, extracted.color)
        assertEquals(source.detail, extracted.detail)
        assertEquals(source.effects, extracted.effects)
        assertEquals(source.portrait, extracted.portrait)
        assertTrue(extracted.geometry.isIdentity())
        assertNull(extracted.geometry.liquify)
    }

    @Test
    fun shouldMatchPresetByCarriedAxesOnly_whenTargetHasDifferentGeometry() {
        val specs = BuiltinPresets.specs().map { it.id to it.graphSubsetJson }
        val filmGraph = PresetGraphMerger.applyPreset(
            checkNotNull(BuiltinPresets.graphFor(BuiltinPresets.FILM_CHROME_ID)),
            EditGraph(geometry = GeometryOps(rotate90 = 1))
        )

        val matched = PresetGraphMerger.matchingPresetId(filmGraph, specs)

        assertEquals(BuiltinPresets.FILM_CHROME_ID, matched)
    }

    @Test
    fun shouldMatchPreset_whenThePresetCarriesPortraitReshape() {
        val presetJson = EditGraphJsonCodec.encode(
            EditGraph(portrait = PortraitOps(reshape = ReshapeOps(face = 20)))
        )
        val specs = listOf("user_face" to presetJson)
        val applied = PresetGraphMerger.applyPreset(checkNotNull(EditGraphJsonCodec.decode(presetJson)), EditGraph())

        assertEquals("user_face", PresetGraphMerger.matchingPresetId(applied, specs))
    }
}
