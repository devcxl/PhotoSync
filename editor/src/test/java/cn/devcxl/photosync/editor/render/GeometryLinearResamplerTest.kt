package cn.devcxl.photosync.editor.render

import org.junit.Assert.assertEquals
import org.junit.Test

import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.wrapper.LinearRawBuffer

class GeometryLinearResamplerTest {

    @Test
    fun shouldCropAndPreserveLinearMetadata_whenResamplingLinearRawBuffer() {
        // 4x2 source: left half is dark (0.1), right half is bright linear HDR (1.5)
        val samples = FloatArray(4 * 2 * 4)
        for (y in 0 until 2) {
            for (x in 0 until 4) {
                val base = (y * 4 + x) * 4
                val v = if (x < 2) 0.1f else 1.5f
                samples[base] = v
                samples[base + 1] = v
                samples[base + 2] = v
                samples[base + 3] = 1.0f
            }
        }
        val source = LinearRawBuffer.fromSamples(
            width = 4,
            height = 2,
            autoBrightGain = 2.0f,
            effectiveIso = 800f,
            rgbaSamples = samples
        )

        val geometry = GeometryOps(crop = CropRect(left = 0.5f, top = 0.0f, right = 1.0f, bottom = 1.0f))
        val inv = GeometryMath.buildPassAInverseMatrix(geometry, srcAspect = 2.0f)

        val out = GeometryCpuResampler.resampleLinear(
            source = source,
            inverseMatrix = inv,
            outputWidth = 2,
            outputHeight = 2,
            caRedK = 0.0f,
            caBlueK = 0.0f
        )

        assertEquals(2, out.width)
        assertEquals(2, out.height)
        assertEquals(2.0f, out.autoBrightGain, 1e-4f)
        assertEquals(800f, out.effectiveIso, 1e-4f)

        val pixel0 = FloatArray(4)
        out.readPixel(0, pixel0)
        // u=0.25 of right-half crop [0.5..1.0] maps to pre-crop u=0.625 -> x=1.875 (blending 0.1 and 1.5)
        assertEquals(1.325f, pixel0[0], 0.02f)

        val pixel1 = FloatArray(4)
        out.readPixel(1, pixel1)
        // u=0.75 of right-half crop maps to pre-crop u=0.875 -> x=2.625 (purely inside 1.5f half)
        assertEquals(1.5f, pixel1[0], 0.02f)
    }
}
