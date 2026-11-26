package cn.devcxl.photosync.editor.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Seam under test: [SubjectWeightTexture] — the Pass B/C alpha binding.
 *
 * Clearing a staged `null` must stop every consumer from sampling the GL name that still backs the
 * previous mask; [SubjectWeightTexture.activeTextureId] is the value the pass chain must use.
 */
class SubjectWeightTextureTest {

    @Test
    fun shouldExposeTheTexture_whenTheMaskIsBound() {
        val texture = SubjectWeightTexture().apply { bindForTest(41, 2, 2) }

        assertEquals(41, texture.activeTextureId)
    }

    @Test
    fun shouldStopExposingTheTexture_whenTheMaskIsCleared() {
        val texture = SubjectWeightTexture().apply { bindForTest(41, 2, 2) }

        texture.stage(null, 0, 0)
        texture.uploadIfNeeded()

        assertFalse("a cleared binding must not report itself active", texture.isActive)
        assertEquals(0, texture.activeTextureId)
    }
}
