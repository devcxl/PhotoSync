package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [PresetSubjectGate] — when applying a preset must trigger the photo's own
 * standard-tier `SubjectMask` inference.
 */
class PresetSubjectGateTest {

    @Test
    fun shouldNeedAMask_whenAPresetCarriesSubjectParametersWithoutAReference() {
        val graph = EditGraph(subject = SubjectOps(blur = 35, fgExposure = 10))

        assertTrue(PresetSubjectGate.needsMask(graph))
    }

    @Test
    fun shouldNotNeedAMask_whenThePhotoAlreadyHasOne() {
        val graph = EditGraph(
            subject = SubjectOps(maskRef = "edits/1/subject_mask_${"a".repeat(64)}.png", blur = 35)
        )

        assertFalse(PresetSubjectGate.needsMask(graph))
    }

    @Test
    fun shouldNotNeedAMask_whenThePresetIsSubjectNeutral() {
        assertFalse(PresetSubjectGate.needsMask(EditGraph()))
        assertFalse(
            PresetSubjectGate.needsMask(EditGraph(tone = ToneOps(exposure = 0.5f)))
        )
    }
}
