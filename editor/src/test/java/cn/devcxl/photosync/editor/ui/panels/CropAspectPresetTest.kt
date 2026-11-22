package cn.devcxl.photosync.editor.ui.panels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Seam under test: the crop aspect-ratio presets and the chip highlight resolution.
 */
class CropAspectPresetTest {

    @Test
    fun shouldResolveToFreePreset_whenNoAspectLockIsActive() {
        val preset = presetFor(lockedAspect = null, frameAspect = 1.5f)
        assertNull(preset.aspectOf(1.5f))
    }

    @Test
    fun shouldResolveToTheFrameAspect_whenTheOriginalRatioPresetIsActive() {
        listOf(0.6f, 1.0f, 1.5f, 16.0f / 9.0f).forEach { frameAspect ->
            val preset = presetFor(lockedAspect = frameAspect, frameAspect = frameAspect)
            assertEquals(frameAspect, preset.aspectOf(frameAspect) ?: 0.0f, 1e-4f)
        }
    }

    @Test
    fun shouldResolveToTheFixedRatio_whenASquareOrWidescreenLockIsActive() {
        val square = presetFor(lockedAspect = 1.0f, frameAspect = 1.5f)
        assertEquals(1.0f, square.aspectOf(1.5f) ?: 0.0f, 1e-4f)

        val widescreen = presetFor(lockedAspect = 16.0f / 9.0f, frameAspect = 1.5f)
        assertEquals(16.0f / 9.0f, widescreen.aspectOf(1.5f) ?: 0.0f, 1e-4f)

        val fourThree = presetFor(lockedAspect = 4.0f / 3.0f, frameAspect = 1.5f)
        assertEquals(4.0f / 3.0f, fourThree.aspectOf(1.5f) ?: 0.0f, 1e-4f)
    }

    @Test
    fun shouldFallBackToFreePreset_whenTheLockMatchesNoPreset() {
        val preset = presetFor(lockedAspect = 2.35f, frameAspect = 1.5f)
        assertNull(preset.aspectOf(1.5f))
    }

    private fun presetFor(lockedAspect: Float?, frameAspect: Float): CropAspectPreset {
        val key = EditorReadoutFormatter.aspectKey(lockedAspect, frameAspect)
        return EditorReadoutFormatter.presets.first { it.key == key }
    }
}
