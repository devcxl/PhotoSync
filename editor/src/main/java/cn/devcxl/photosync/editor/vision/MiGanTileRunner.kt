package cn.devcxl.photosync.editor.vision

import java.nio.FloatBuffer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException

import cn.devcxl.photosync.editor.domain.InpaintCropBlender

/**
 * Runs one `[1,4,512,512]` MI-GAN request on a loaded generator session.
 *
 * The seam exists so [MiGanInpainter] can prove its tile scheduling, persistence and cancellation
 * contract on the JVM with a fake handle; the production implementation is
 * [OnnxMiGanTileRunner].
 */
fun interface MiGanTileRunner {

    /**
     * Executes one request and returns `[1,3,512,512]` floats in CHW order.
     *
     * @throws OrtException when ONNX Runtime rejects the request.
     */
    fun run(handle: OrtSessionHandle, modelInput: FloatArray): FloatArray
}

/**
 * [MiGanTileRunner] backed by the native generator session.
 *
 * Tensor names and shapes are the ones pinned in `AiModelCatalog.MI_GAN_GENERATOR`: `input` is
 * float32 `[1,4,512,512]` and `output` is float32 `[1,3,512,512]`. The runner validates both
 * buffers so a catalog/file mismatch fails with a clear error instead of corrupting a patch.
 */
class OnnxMiGanTileRunner : MiGanTileRunner {

    override fun run(handle: OrtSessionHandle, modelInput: FloatArray): FloatArray {
        val nativeHandle = handle as? OnnxRuntimeSessionHandle
            ?: throw IllegalStateException("MI-GAN requires a native ONNX Runtime session")
        require(modelInput.size == InpaintCropBlender.MODEL_INPUT_ELEMENTS) {
            "MI-GAN input must be [1,4,512,512]"
        }
        val environment = OrtEnvironment.getEnvironment()
        return OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(modelInput),
            longArrayOf(1, 4, 512, 512)
        ).use { tensor ->
            nativeHandle.session.run(mapOf(INPUT_NAME to tensor)).use { result ->
                val output = result.get(0) as? OnnxTensor
                    ?: throw OrtException("MI-GAN produced a non-tensor output")
                val buffer = output.floatBuffer
                if (buffer.remaining() != InpaintCropBlender.MODEL_OUTPUT_ELEMENTS) {
                    throw OrtException(
                        "MI-GAN output has ${buffer.remaining()} floats, expected [1,3,512,512]"
                    )
                }
                val floats = FloatArray(InpaintCropBlender.MODEL_OUTPUT_ELEMENTS)
                buffer.get(floats)
                floats
            }
        }
    }

    private companion object {
        const val INPUT_NAME = "input"
    }
}
