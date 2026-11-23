package cn.devcxl.photosync.editor.vision

import android.content.Context
import android.graphics.Bitmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

import cn.devcxl.photosync.editor.domain.SkinMaskMath

@RunWith(AndroidJUnit4::class)
class PortraitConfidenceMaskTest {

    @Test
    fun shouldReadBundledPersonMask_whenNativeSegmenterProcessesANonSquareImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences(MediaPipeDelegatePolicy.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val engine = MediaPipePortraitEngine(context, MediaPipeDelegatePolicy(preferences, glEsVersion = 0x30000))
        val bitmap = Bitmap.createBitmap(384, 512, Bitmap.Config.ARGB_8888)
        try {
            val analysis = checkNotNull(PortraitAnalyzer(engine).analyze(bitmap))
            val expectedSize = SkinMaskMath.MASK_SIZE * SkinMaskMath.MASK_SIZE
            assertEquals(expectedSize, analysis.personMask?.size)
            assertEquals(expectedSize, analysis.skinMask.size)
        } finally {
            bitmap.recycle()
            engine.release()
        }
    }
}
