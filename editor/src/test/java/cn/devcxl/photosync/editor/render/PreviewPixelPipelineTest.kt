package cn.devcxl.photosync.editor.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.BuiltinPresets
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec

/**
 * Seam under test: [PreviewCacheWriter.gradeDisplayPixels] — verifies the full CPU preview grading
 * chain (Pass B -> Pass C -> Pass D) executes cleanly for every built-in preset without touching
 * immutable `Bitmap.setPixels`.
 */
class PreviewPixelPipelineTest {

    @Test
    fun shouldGradeDisplayPixelsCleanly_whenApplyingEveryBuiltinPreset() {
        val width = 16
        val height = 16
        val base = IntArray(width * height) { index ->
            val r = ((index * 7) % 200) + 30
            val g = ((index * 11) % 180) + 40
            val b = ((index * 13) % 160) + 50
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        for (spec in BuiltinPresets.specs()) {
            val graph = checkNotNull(EditGraphJsonCodec.decode(spec.graphSubsetJson))
            val input = base.copyOf()
            val graded = PreviewCacheWriter.gradeDisplayPixels(
                pixels = input,
                width = width,
                height = height,
                graph = graph,
                fullResolutionShortEdge = 1200
            )
            assertFalse("${spec.id} should visibly change the frame", base.contentEquals(graded))
            assertTrue("${spec.id} must preserve opaque alpha", graded.all { (it ushr 24) == 0xFF })
        }
    }

    @Test
    fun shouldLeavePixelsUnchanged_whenGraphIsIdentity() {
        val width = 8
        val height = 8
        val base = IntArray(width * height) { 0xFF_60_80_A0.toInt() }

        val graded = PreviewCacheWriter.gradeDisplayPixels(
            pixels = base.copyOf(),
            width = width,
            height = height,
            graph = EditGraph(),
            fullResolutionShortEdge = 800
        )

        assertTrue(base.contentEquals(graded))
    }
}
