package cn.devcxl.photosync.editor.export

import org.junit.Assert.assertEquals
import org.junit.Test

import cn.devcxl.photosync.editor.contract.GallerySink
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.InpaintBBox
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.domain.SubjectOps

class BakeOutputNamingTest {

    @Test
    fun shouldRouteToPngOnly_whenTheSubjectBackgroundIsTransparent() {
        val transparent = EditGraph(
            subject = SubjectOps(
                maskRef = "edits/7/subject_mask_a.png",
                bgMode = SubjectBackgroundMode.TRANSPARENT
            )
        )

        assertEquals(BakeOutputFormat.PNG_RGBA, BakeOutputNaming.outputFormatFor(transparent))
        assertEquals(BakeOutputFormat.JPEG, BakeOutputNaming.outputFormatFor(EditGraph()))
        assertEquals(
            BakeOutputFormat.JPEG,
            BakeOutputNaming.outputFormatFor(
                EditGraph(subject = SubjectOps(bgMode = SubjectBackgroundMode.SOLID))
            )
        )
    }

    @Test
    fun shouldRouteToJpeg_whenTheGraphOnlyCarriesInpaintPatches() {
        val inpainted = EditGraph(
            inpaint = listOf(
                InpaintPatchRef(
                    bbox = InpaintBBox(0.2f, 0.2f, 0.6f, 0.6f),
                    patchRelPath = "edits/7/inpaint_patch_a.png",
                    maskRelPath = "edits/7/inpaint_mask_a.png",
                    sha256 = ""
                )
            )
        )

        assertEquals(
            "an opaque repaired frame must not pay for an RGBA container",
            BakeOutputFormat.JPEG,
            BakeOutputNaming.outputFormatFor(inpainted)
        )
        assertEquals(
            "DSC04821_edited.jpg",
            BakeOutputNaming.displayName("/photos/DSC04821.ARW", 7L, BakeOutputFormat.JPEG)
        )
    }

    @Test
    fun shouldAppendJpgSuffix_whenFormatIsJpeg() {
        val name = BakeOutputNaming.displayName("/photos/DSC04821.ARW", 7L, BakeOutputFormat.JPEG)

        assertEquals("DSC04821_edited.jpg", name)
        assertEquals("jpg", BakeOutputNaming.extension(BakeOutputFormat.JPEG))
        assertEquals(GallerySink.JPEG_MIME, BakeOutputNaming.mimeType(BakeOutputFormat.JPEG))
    }

    @Test
    fun shouldAppendPngSuffix_whenFormatIsTransparentPng() {
        val name = BakeOutputNaming.displayName("/photos/DSC04821.JPG", 7L, BakeOutputFormat.PNG_RGBA)

        assertEquals("DSC04821_edited.png", name)
        assertEquals("png", BakeOutputNaming.extension(BakeOutputFormat.PNG_RGBA))
        assertEquals(GallerySink.PNG_MIME, BakeOutputNaming.mimeType(BakeOutputFormat.PNG_RGBA))
        assertEquals("image/png", GallerySink.PNG_MIME)
    }

    @Test
    fun shouldFallBackToThePhotoId_whenSourceNameIsBlank() {
        val name = BakeOutputNaming.displayName("", 42L, BakeOutputFormat.PNG_RGBA)

        assertEquals("IMG_42_edited.png", name)
    }

    @Test
    fun shouldDefaultToJpeg_whenBakeRequestDoesNotSpecifyAFormat() {
        val request = BakeRequest(
            sourcePath = "/photos/DSC04821.ARW",
            displayName = "DSC04821_edited.jpg",
            isRaw = true,
            graph = EditGraph()
        )

        assertEquals(BakeOutputFormat.JPEG, request.outputFormat)
    }
}
