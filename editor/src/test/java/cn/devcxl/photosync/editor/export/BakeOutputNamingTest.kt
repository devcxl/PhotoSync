package cn.devcxl.photosync.editor.export

import org.junit.Assert.assertEquals
import org.junit.Test

import cn.devcxl.photosync.editor.contract.GallerySink
import cn.devcxl.photosync.editor.domain.EditGraph

class BakeOutputNamingTest {

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
