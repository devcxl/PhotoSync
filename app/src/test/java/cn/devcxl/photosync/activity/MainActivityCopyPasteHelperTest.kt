package cn.devcxl.photosync.activity

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

import cn.devcxl.photosync.editor.domain.CopyMask
import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.domain.PortraitOps
import cn.devcxl.photosync.editor.domain.ReshapeOps
import cn.devcxl.photosync.editor.domain.ToneOps

/**
 * Seam under test: [EditClipboard] — verifies that opt-in [CopyMask] flags chosen from the
 * checklist survive through `paste`.
 */
class MainActivityCopyPasteHelperTest {

    @After
    fun tearDown() {
        EditClipboard.clear()
    }

    @Test
    fun shouldCarryOptInGeometryAndReshape_whenMaskEnablesThem() {
        val crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f)
        val source = EditGraph(
            geometry = GeometryOps(rotate90 = 1, crop = crop),
            tone = ToneOps(exposure = 0.5f),
            portrait = PortraitOps(reshape = ReshapeOps(face = 24))
        )
        val mask = CopyMask(
            includeGeometryCropRotate = true,
            includePortraitReshapeBody = true
        )

        EditClipboard.copy(source, mask, sourcePhotoId = 10L)
        val pasted = EditClipboard.paste(EditGraph())

        assertEquals(1, pasted.geometry.rotate90)
        assertEquals(crop, pasted.geometry.crop)
        assertEquals(24, pasted.portrait.reshape.face)
        assertEquals(0.5f, pasted.tone.exposure, 1e-4f)
        assertEquals(10L, EditClipboard.sourcePhotoId)
    }

    @Test
    fun shouldExcludeGeometryByDefault_whenUsingShortPressCopy() {
        val source = EditGraph(
            geometry = GeometryOps(rotate90 = 2),
            tone = ToneOps(contrast = 20)
        )

        EditClipboard.copy(source, sourcePhotoId = 5L)
        val pasted = EditClipboard.paste(EditGraph())

        assertNotEquals(2, pasted.geometry.rotate90)
        assertEquals(0, pasted.geometry.rotate90)
        assertEquals(20, pasted.tone.contrast)
    }
}
