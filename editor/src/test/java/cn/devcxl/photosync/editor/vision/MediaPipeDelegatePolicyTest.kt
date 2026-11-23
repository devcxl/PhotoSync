package cn.devcxl.photosync.editor.vision

import java.lang.reflect.Proxy

import android.content.SharedPreferences

import com.google.mediapipe.tasks.core.Delegate
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaPipeDelegatePolicyTest {

    @Test
    fun shouldProduceConfidenceMasks_whenGpuResultCopyRejectsItsBuffer() {
        val policy = gpuCapablePolicy()
        val segment = policy.createSegmentationTask { delegate ->
            // Task construction succeeds; JNI fails only when copying the first confidence mask.
            {
                check(delegate == Delegate.CPU) { "Expected buffer size 131072 got: 262144" }
                "confidence mask"
            }
        }

        assertEquals("confidence mask", segment?.invoke())
    }

    @Test
    fun shouldStillOfferGpuForFaceLandmarks_whenGpuIsSupported() {
        assertEquals(Delegate.GPU, gpuCapablePolicy().createTask { it })
    }

    private fun gpuCapablePolicy(): MediaPipeDelegatePolicy {
        val preferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, arguments ->
            check(method.name == "getBoolean")
            arguments[1]
        } as SharedPreferences
        return MediaPipeDelegatePolicy(preferences, MediaPipeDelegatePolicy.MIN_GPU_GLES_VERSION)
    }
}
