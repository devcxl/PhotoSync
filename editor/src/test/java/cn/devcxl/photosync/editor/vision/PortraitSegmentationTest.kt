package cn.devcxl.photosync.editor.vision

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Optional

import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter.SegmentationOptions
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

import cn.devcxl.photosync.editor.domain.SkinMaskMath

class PortraitSegmentationTest {

    @Test
    fun shouldReadPersonMask_whenInputIsNotMaskSized() {
        assertMasksForInput(width = 512, height = 768, channels = 1)
    }

    @Test
    fun shouldReadMulticlassMasksInOrder_whenInputIsNotMaskSized() {
        assertMasksForInput(width = 768, height = 512, channels = 6)
    }

    private fun assertMasksForInput(width: Int, height: Int, channels: Int) {
        val pixels = ByteBuffer.allocateDirect(width * height * 4)
        ByteBufferImageBuilder(pixels, width, height, MPImage.IMAGE_FORMAT_RGBA).build().use { input ->
            val masks = readPortraitMasks(input, channels) { _, options ->
                val outputWidth = dimension(options, "outputWidth")
                val outputHeight = dimension(options, "outputHeight")
                val imageMasks = List(channels) { channel ->
                    val plane = ByteBuffer.allocateDirect(outputWidth * outputHeight * Float.SIZE_BYTES)
                        .order(ByteOrder.nativeOrder())
                    plane.putFloat(0, sample(channel))
                    ByteBufferImageBuilder(plane, outputWidth, outputHeight, MPImage.IMAGE_FORMAT_VEC32F1).build()
                }
                ImageSegmenterResult.create(Optional.of(imageMasks), Optional.empty(), emptyList(), 0L)
            }
            assertNotNull("Mask output must match the grid consumed by portrait effects", masks)
            assertEquals(channels, masks?.size)
            masks.orEmpty().forEachIndexed { channel, mask ->
                assertEquals(SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE, mask.size)
                assertEquals(sample(channel), mask.first(), 0f)
            }
        }
    }

    private fun sample(channel: Int): Float = (channel + 1) / 8f

    // MediaPipe exposes the setters but keeps the corresponding getters package-private.
    private fun dimension(options: SegmentationOptions, getter: String): Int =
        SegmentationOptions::class.java.getDeclaredMethod(getter).apply { isAccessible = true }
            .invoke(options) as Int
}
