package cn.devcxl.photosync.editor.vision

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color

import com.google.mediapipe.framework.image.BitmapImageBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

import cn.devcxl.photosync.editor.domain.SkinMaskMath

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PortraitAnalyzerBitmapTest {

    @Test
    fun shouldProduceMasks_whenMediaPipeClosesItsBitmapImage() {
        assertAnalysisSurvivesImageClose(384, 512)
    }

    @Test
    fun shouldProduceMasks_whenInputAlreadyMatchesMaskSize() {
        assertAnalysisSurvivesImageClose(SkinMaskMath.MASK_SIZE, SkinMaskMath.MASK_SIZE)
    }

    @Test
    fun shouldSkipInference_whenInputIsAlreadyRecycled() {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        bitmap.recycle()
        val analyzer = PortraitAnalyzer { error("Inference should not receive a recycled bitmap") }

        assertNull(analyzer.analyze(bitmap))
    }

    private fun assertAnalysisSurvivesImageClose(width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(200, 150, 120))
        val analyzer = PortraitAnalyzer { input ->
            BitmapImageBuilder(input).build().close()
            PortraitEngineResult(
                faces = emptyList(),
                confidenceMasks = null,
                personMask = FloatArray(SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE) { 1f },
                usesMulticlass = false
            )
        }

        val result = analyzer.analyze(bitmap)

        assertNotNull(result)
        assertEquals(SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE, result?.skinMask?.size)
        assertTrue(checkNotNull(result).skinMask.any { it > 0f })
    }
}
