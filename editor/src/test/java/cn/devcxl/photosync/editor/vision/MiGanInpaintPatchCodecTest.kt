package cn.devcxl.photosync.editor.vision

import android.app.Application
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Seam under test: [BitmapInpaintPatchCodec] — the PNG pair the inpainter writes and Pre-Pass A
 * reads. The mask must survive as the red channel because the compositor reads it from there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiGanInpaintPatchCodecTest {

    private val codec = BitmapInpaintPatchCodec

    @Test
    fun shouldRoundTripRgbAndFeatheredAlpha_whenEncodingBothPngs() {
        val width = 9
        val height = 7
        val argb = IntArray(width * height) { index ->
            val red = (index * 7) % 256
            val green = (index * 13) % 256
            val blue = (index * 29) % 256
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
        val alpha = ByteArray(width * height) { index -> ((index * 11) % 256).toByte() }

        val patchPng = checkNotNull(codec.encodeRgb(argb, width, height))
        val maskPng = checkNotNull(codec.encodeAlpha(alpha, width, height))
        val decoded = checkNotNull(codec.decode(patchPng, maskPng))

        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        assertArrayEquals(argb, decoded.argb)
        assertArrayEquals(alpha, decoded.alpha)
    }

    @Test
    fun shouldReturnNull_whenTheMaskDimensionsDiffer() {
        val patchPng = checkNotNull(codec.encodeRgb(IntArray(4) { 0xFF102030.toInt() }, 2, 2))
        val maskPng = checkNotNull(codec.encodeAlpha(ByteArray(6) { 0xFF.toByte() }, 3, 2))

        assertNull(codec.decode(patchPng, maskPng))
    }

    @Test
    fun shouldReturnNull_whenAPayloadIsEmpty() {
        assertNull(codec.decode(ByteArray(0), ByteArray(0)))
        assertNull(codec.encodeRgb(IntArray(0), 0, 0))
        assertNull(codec.encodeAlpha(ByteArray(0), 0, 0))
    }
}
