package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [CopyMask] and [PresetGraphMerger] — the strong isolation contract of the
 * pixel-level AI layers.
 *
 * `inpaint` and `cloudLayer` belong to the frame they were generated for. Neither a full-axis copy
 * nor a preset may carry them onto another photo, and a preset must never persist them.
 */
class CopyMaskAiIsolationTest {

    private val digest = "b".repeat(64)

    private val sourcePatch = InpaintPatchRef(
        bbox = InpaintBBox(0.2f, 0.2f, 0.5f, 0.5f),
        patchRelPath = "edits/1/inpaint_patch_${digest}.png",
        maskRelPath = "edits/1/inpaint_mask_${digest}.png",
        sha256 = digest
    )

    private val targetPatch = InpaintPatchRef(
        bbox = InpaintBBox(0.6f, 0.6f, 0.8f, 0.8f),
        patchRelPath = "edits/2/inpaint_patch_${digest}.png",
        maskRelPath = "edits/2/inpaint_mask_${digest}.png",
        sha256 = digest
    )

    private val sourceCloud = CloudEditLayerRef(
        imageRelPath = "edits/1/cloud_layer_${digest}.png",
        sha256 = digest,
        opacity = 60,
        providerTag = "openai-compatible"
    )

    private val targetCloud = CloudEditLayerRef(
        imageRelPath = "edits/2/cloud_layer_${digest}.png",
        sha256 = digest,
        opacity = 30
    )

    private fun sourceGraph(): EditGraph = EditGraph(
        tone = ToneOps(exposure = 0.5f),
        subject = SubjectOps(
            maskRef = "edits/1/subject_mask_${digest}.png",
            bgMode = SubjectBackgroundMode.TRANSPARENT,
            blur = 40
        ),
        inpaint = listOf(sourcePatch),
        sky = SkyOps(
            maskRef = "edits/1/sky_mask_${digest}.png",
            assetType = SkyAssetType.CUSTOM,
            assetIdOrRelPath = "edits/1/sky_asset_${digest}.png",
            relight = 35
        ),
        cloudLayer = sourceCloud
    )

    private fun allAxesEnabled(): CopyMask = CopyMask(
        includeBaseLook = true,
        includeTone = true,
        includeColor = true,
        includeDetail = true,
        includeEffects = true,
        includePortraitSkin = true,
        includeGeometryCropRotate = true,
        includeLiquify = true,
        includePortraitReshapeBody = true
    )

    @Test
    fun shouldCopySubjectParametersWithoutTheMask_whenEveryCopyAxisIsEnabled() {
        val pasted = allAxesEnabled().apply(sourceGraph(), EditGraph())

        assertTrue("inpaint must never travel across photos", pasted.inpaint.isEmpty())
        assertNull("cloudLayer must never travel across photos", pasted.cloudLayer)
        assertEquals("the subject mask belongs to the source frame", "", pasted.subject.maskRef)
        assertEquals(SubjectBackgroundMode.TRANSPARENT, pasted.subject.bgMode)
        assertEquals(40, pasted.subject.blur)
        assertEquals("sky stays a frame-local mask", SkyOps(), pasted.sky)
        assertEquals(0.5f, pasted.tone.exposure, 1e-4f)
    }

    @Test
    fun shouldKeepTargetLocalInpaintAndCloudLayer_whenCopyingOntoAnEditedPhoto() {
        val target = EditGraph(inpaint = listOf(targetPatch), cloudLayer = targetCloud)

        val pasted = allAxesEnabled().apply(sourceGraph(), target)

        assertEquals(listOf(targetPatch), pasted.inpaint)
        assertEquals(targetCloud, pasted.cloudLayer)
    }

    @Test
    fun shouldExtractSubjectParametersWithoutTheMask_whenSavingAPresetFromAnAiEditedPhoto() {
        val extracted = PresetGraphMerger.extractPreset(sourceGraph())

        assertTrue(extracted.inpaint.isEmpty())
        assertNull(extracted.cloudLayer)
        assertEquals("", extracted.subject.maskRef)
        assertEquals(SubjectBackgroundMode.TRANSPARENT, extracted.subject.bgMode)
        assertEquals(40, extracted.subject.blur)
        assertEquals(SkyOps(), extracted.sky)
        assertEquals(0.5f, extracted.tone.exposure, 1e-4f)
    }

    @Test
    fun shouldKeepTargetAiLayers_whenApplyingAPresetToAnEditedPhoto() {
        val target = EditGraph(inpaint = listOf(targetPatch), cloudLayer = targetCloud)

        val merged = PresetGraphMerger.applyPreset(sourceGraph(), target)

        assertEquals(listOf(targetPatch), merged.inpaint)
        assertEquals(targetCloud, merged.cloudLayer)
        assertEquals(0.5f, merged.tone.exposure, 1e-4f)
    }

    @Test
    fun shouldClearEveryAiGroup_whenResettingToTheOriginal() {
        val reset = PresetGraphMerger.resetToOriginal(sourceGraph())

        assertTrue(reset.isIdentity())
        assertEquals(SubjectOps(), reset.subject)
        assertTrue(reset.inpaint.isEmpty())
        assertEquals(SkyOps(), reset.sky)
        assertNull(reset.cloudLayer)
    }

    @Test
    fun shouldNeverLetAiLayersMakeAPresetLookActive_whenMatchingPresets() {
        val graphWithOnlyAiWork = EditGraph(
            inpaint = listOf(sourcePatch),
            cloudLayer = sourceCloud
        )

        assertNull(
            "AI layers are not preset axes",
            PresetGraphMerger.matchingPresetId(graphWithOnlyAiWork, emptyList())
        )
    }
}
