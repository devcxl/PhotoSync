package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [CopyMask] and [PresetGraphMerger] — the `SubjectOps` parameter-transfer
 * contract.
 *
 * Parameters (`bgMode`, `bgColorArgb`, `blur`, the six foreground/background offsets) travel with a
 * copy/preset; the source's refined `maskRef` never does, because a mask describes the pixels it was
 * generated from. The target either keeps its own mask or recomputes one from its own image.
 */
class CopyMaskSubjectTest {

    private val sourceDigest = "a".repeat(64)
    private val targetDigest = "b".repeat(64)

    private fun sourceSubject(): SubjectOps = SubjectOps(
        maskRef = "edits/1/subject_mask_${sourceDigest}.png",
        bgMode = SubjectBackgroundMode.SOLID,
        bgColorArgb = 0xFF22AA44.toInt(),
        blur = 65,
        fgExposure = 20,
        fgTemp = -10,
        fgSat = 15,
        bgExposure = -25,
        bgTemp = 5,
        bgSat = -30
    )

    private fun targetSubject(): SubjectOps = SubjectOps(
        maskRef = "edits/2/subject_mask_${targetDigest}.png",
        bgMode = SubjectBackgroundMode.ORIGINAL
    )

    @Test
    fun shouldCopySubjectParametersAndKeepTheTargetMask_whenTheAxisIsEnabled() {
        val source = EditGraph(subject = sourceSubject())
        val target = EditGraph(subject = targetSubject())

        val pasted = CopyMask().apply(source, target)

        assertEquals("edits/2/subject_mask_${targetDigest}.png", pasted.subject.maskRef)
        assertEquals(SubjectBackgroundMode.SOLID, pasted.subject.bgMode)
        assertEquals(0xFF22AA44.toInt(), pasted.subject.bgColorArgb)
        assertEquals(65, pasted.subject.blur)
        assertEquals(20, pasted.subject.fgExposure)
        assertEquals(-10, pasted.subject.fgTemp)
        assertEquals(15, pasted.subject.fgSat)
        assertEquals(-25, pasted.subject.bgExposure)
        assertEquals(5, pasted.subject.bgTemp)
        assertEquals(-30, pasted.subject.bgSat)
    }

    @Test
    fun shouldKeepTheTargetSubject_whenTheAxisIsDisabled() {
        val source = EditGraph(subject = sourceSubject())
        val target = EditGraph(subject = targetSubject())

        val pasted = CopyMask(includeAiSubject = false).apply(source, target)

        assertEquals(target.subject, pasted.subject)
    }

    @Test
    fun shouldLeaveTheMaskBlank_whenTheTargetHasNoMask() {
        val pasted = CopyMask().apply(EditGraph(subject = sourceSubject()), EditGraph())

        assertEquals("", pasted.subject.maskRef)
        assertEquals(65, pasted.subject.blur)
        assertFalse("the source mask must never travel", pasted.subject.maskRef.contains(sourceDigest))
    }

    @Test
    fun shouldExtractSubjectParametersWithoutAMask_whenSavingAPreset() {
        val extracted = PresetGraphMerger.extractPreset(EditGraph(subject = sourceSubject()))

        assertEquals("", extracted.subject.maskRef)
        assertEquals(SubjectBackgroundMode.SOLID, extracted.subject.bgMode)
        assertEquals(65, extracted.subject.blur)
    }

    @Test
    fun shouldApplyPresetParametersWithoutAMask_whenTheTargetIsPlain() {
        val preset = PresetGraphMerger.extractPreset(EditGraph(subject = sourceSubject()))

        val applied = PresetGraphMerger.applyPreset(preset, EditGraph())

        assertTrue(applied.subject.requiresMaskProcessing())
        assertEquals("", applied.subject.maskRef)
        assertEquals(65, applied.subject.blur)
    }

    @Test
    fun shouldTreatASubjectOnlyGraphAsANonNeutralPreset() {
        val subjectOnly = EditGraph(subject = SubjectOps(blur = 45))

        assertFalse(PresetGraphMerger.hasNeutralPresetAxes(subjectOnly))
        val encoded = EditGraphJsonCodec.encode(PresetGraphMerger.extractPreset(subjectOnly))
        val matched = PresetGraphMerger.matchingPresetId(subjectOnly, listOf("subject_only" to encoded))
        assertEquals("subject_only", matched)
    }

    @Test
    fun shouldDefaultTheSubjectAxisToTrue_whenTheCheckboxArrayIsShort() {
        val mask = CopyMask.fromCheckboxState(booleanArrayOf(true, true, true, true, true, true))

        assertTrue(mask.includeAiSubject)
        assertEquals(10, CopyMask.AXIS_COUNT)
    }

    @Test
    fun shouldReadTheSubjectAxisFromTheLastCheckbox() {
        val checked = BooleanArray(CopyMask.AXIS_COUNT) { true }

        assertTrue(CopyMask.fromCheckboxState(checked).includeAiSubject)

        checked[CopyMask.AXIS_COUNT - 1] = false
        assertFalse(CopyMask.fromCheckboxState(checked).includeAiSubject)
    }

    @Test
    fun shouldNeverCopyInpaintOrCloudLayer_whenTheSubjectAxisIsEnabled() {
        val digest = "c".repeat(64)
        val source = EditGraph(
            subject = sourceSubject(),
            inpaint = listOf(
                InpaintPatchRef(
                    patchRelPath = "edits/1/inpaint_patch_${digest}.png",
                    maskRelPath = "edits/1/inpaint_mask_${digest}.png",
                    sha256 = digest
                )
            ),
            cloudLayer = CloudEditLayerRef(imageRelPath = "edits/1/cloud_layer_${digest}.png")
        )

        val pasted = CopyMask().apply(source, EditGraph())

        assertTrue(pasted.inpaint.isEmpty())
        assertNull(pasted.cloudLayer)
        assertEquals(65, pasted.subject.blur)
    }
}
