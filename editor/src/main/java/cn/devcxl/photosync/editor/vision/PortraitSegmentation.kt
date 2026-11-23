package cn.devcxl.photosync.editor.vision

import java.nio.ByteOrder

import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter.SegmentationOptions
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenterResult
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.SkinMaskMath

/** Reads the model's ordered confidence planes into the shared skin and body coordinate grid. */
internal fun readPortraitMasks(
    image: MPImage,
    expectedChannels: Int,
    segment: (MPImage, SegmentationOptions) -> ImageSegmenterResult
): List<FloatArray>? {
    val edge = SkinMaskMath.MASK_SIZE
    // The default overload returns the input dimensions, not the model or renderer grid.
    val options = SegmentationOptions.builder()
        .setOutputWidth(edge)
        .setOutputHeight(edge)
        .build()
    val masks = segment(image, options).confidenceMasks().orElse(null) ?: return null
    try {
        if (masks.size != expectedChannels || masks.any { it.width != edge || it.height != edge }) {
            Timber.w("Portrait segmentation returned unexpected mask dimensions or channel count")
            return null
        }
        return masks.map { mask ->
            val floats = ByteBufferExtractor.extract(mask, MPImage.IMAGE_FORMAT_VEC32F1)
                .duplicate().order(ByteOrder.nativeOrder()).apply { rewind() }.asFloatBuffer()
            if (floats.remaining() < edge * edge) {
                Timber.w("Portrait segmentation returned a truncated mask")
                return null
            }
            FloatArray(edge * edge).also { floats.get(it) }
        }
    } finally {
        masks.forEach { it.close() }
    }
}
