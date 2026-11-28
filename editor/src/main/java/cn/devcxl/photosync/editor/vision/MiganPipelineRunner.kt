package cn.devcxl.photosync.editor.vision

import java.nio.ByteBuffer

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtEnvironment

import cn.devcxl.photosync.editor.domain.InpaintMaskBuffer
import cn.devcxl.photosync.editor.domain.InpaintPixelRect

/**
 * Runs one `PIPELINE_UINT8` MI-GAN request on a loaded pipeline session.
 *
 * The seam exists so [MiGanInpainter] can prove the uint8 image/mask contract, tile scheduling and
 * per-tile persistence on the JVM; the production implementation is [OnnxMiganPipelineRunner].
 */
fun interface MiganPipelineRunner {

    /**
     * Executes one request and returns the `result` tensor bytes in planar RGB order.
     *
     * @param image `uint8 [1,3,H,W]`, channels R, G, B, row-major per plane.
     * @param mask `uint8 [1,1,H,W]`, `255` keeps the pixel and `0` marks it for repair.
     * @param width Crop width, i.e. the `W` of both tensors.
     * @param height Crop height, i.e. the `H` of both tensors.
     * @return `3 * width * height` bytes in the same planar RGB layout.
     * @throws OrtException when ONNX Runtime rejects the request.
     */
    fun run(
        handle: OrtSessionHandle,
        image: ByteArray,
        mask: ByteArray,
        width: Int,
        height: Int
    ): ByteArray
}

/**
 * [MiganPipelineRunner] backed by the native upstream pipeline session.
 *
 * The pinned artifact (`AiModelCatalog.MI_GAN_PIPELINE`) takes uint8 `image` and `mask` tensors with
 * dynamic height/width and returns uint8 `result`. The graph performs its own downsample, mask
 * dilation (`maxpool 3×3`) and Gaussian feathering, so the mask handed in here must be the hard
 * (0/255) mask — no Kotlin-side pre-feather.
 */
class OnnxMiganPipelineRunner : MiganPipelineRunner {

    override fun run(
        handle: OrtSessionHandle,
        image: ByteArray,
        mask: ByteArray,
        width: Int,
        height: Int
    ): ByteArray {
        val nativeHandle = handle as? OnnxRuntimeSessionHandle
            ?: throw IllegalStateException("MI-GAN pipeline requires a native ONNX Runtime session")
        require(width > 0 && height > 0) { "MI-GAN pipeline input must not be empty" }
        require(image.size == PIXEL_CHANNELS * width * height) {
            "MI-GAN pipeline image must be [1,3,H,W]"
        }
        require(mask.size == width * height) { "MI-GAN pipeline mask must be [1,1,H,W]" }
        val environment = OrtEnvironment.getEnvironment()
        val imageTensor = OnnxTensor.createTensor(
            environment,
            ByteBuffer.wrap(image),
            longArrayOf(1, PIXEL_CHANNELS.toLong(), height.toLong(), width.toLong()),
            OnnxJavaType.UINT8
        )
        return imageTensor.use {
            OnnxTensor.createTensor(
                environment,
                ByteBuffer.wrap(mask),
                longArrayOf(1, 1, height.toLong(), width.toLong()),
                OnnxJavaType.UINT8
            ).use { maskTensor ->
                nativeHandle.session.run(
                    mapOf(IMAGE_NAME to imageTensor, MASK_NAME to maskTensor)
                ).use { result ->
                    val output = result.get(0) as? OnnxTensor
                        ?: throw OrtException("MI-GAN pipeline produced a non-tensor output")
                    val buffer = output.byteBuffer
                    val expected = PIXEL_CHANNELS * width * height
                    if (buffer.remaining() != expected) {
                        throw OrtException(
                            "MI-GAN pipeline output has ${buffer.remaining()} bytes, expected $expected"
                        )
                    }
                    val bytes = ByteArray(expected)
                    buffer.get(bytes)
                    bytes
                }
            }
        }
    }

    private companion object {
        const val PIXEL_CHANNELS = 3
        const val IMAGE_NAME = "image"
        const val MASK_NAME = "mask"
    }
}

/**
 * Pure uint8 tensor codec of the `PIPELINE_UINT8` variant.
 *
 * Kept free of Android and ONNX Runtime types so the three contract-defining rules can be locked by
 * JVM tests without loading a native session:
 *
 * 1. **Channels are RGB, not BGR.** The measured artifact reproduces `R,G,B = 200,40,10` as
 *    `200,40,10` outside the hole; swapping channels would silently tint every repair.
 * 2. **The mask is hard and inverted at the boundary only.** Kotlin keeps `255 = hole`; the tensor
 *    is `if (coverage > 0) 0 else 255`, i.e. `255 = keep, 0 = repair`. No feather is applied here,
 *    because the graph already runs `maxpool(3×3)` plus a `5-tap Gaussian (σ = 1)`.
 * 3. **The output is read directly.** `result` is uint8 RGB at input resolution; no float
 *    un-normalisation is involved.
 */
object MiganPipelineCodec {

    /** Planar RGB channel count. */
    const val PIXEL_CHANNELS: Int = 3

    /**
     * Encodes the source pixels of [crop] as `uint8 [1,3,H,W]` in R, G, B plane order.
     *
     * The pipeline resizes internally, so the crop is fed at native resolution (≤ the tier's
     * maximum crop side); no Kotlin-side resampling happens here.
     *
     * @param sourceArgb Opaque source pixels of the whole image, row-major.
     * @param sourceWidth Width of the source image.
     * @param crop Region to encode.
     * @throws IllegalArgumentException when the crop is empty or escapes the source.
     */
    fun encodeImageRgbUint8(
        sourceArgb: IntArray,
        sourceWidth: Int,
        crop: InpaintPixelRect
    ): ByteArray {
        require(sourceWidth > 0 && sourceArgb.size % sourceWidth == 0) {
            "Source buffer does not match its width"
        }
        val sourceHeight = sourceArgb.size / sourceWidth
        require(!crop.isEmpty && crop.right <= sourceWidth && crop.bottom <= sourceHeight) {
            "Inpaint crop must be a non-empty region inside the source"
        }
        val width = crop.width
        val height = crop.height
        val plane = width * height
        val encoded = ByteArray(PIXEL_CHANNELS * plane)
        for (y in 0 until height) {
            val sourceRow = (crop.top + y) * sourceWidth + crop.left
            val row = y * width
            for (x in 0 until width) {
                val pixel = sourceArgb[sourceRow + x]
                val index = row + x
                encoded[index] = (pixel shr 16 and 0xFF).toByte()
                encoded[plane + index] = (pixel shr 8 and 0xFF).toByte()
                encoded[2 * plane + index] = (pixel and 0xFF).toByte()
            }
        }
        return encoded
    }

    /**
     * Encodes the hard, boundary-inverted mask of [crop] as `uint8 [1,1,H,W]`.
     *
     * Domain semantics stay `255 = hole`; the tensor uses `0 = repair, 255 = keep`. Any non-zero
     * coverage becomes a hole, and no Gaussian/max-pool is applied — the graph owns that feather.
     *
     * @throws IllegalArgumentException when the crop is empty or escapes the mask.
     */
    fun encodeInvertedHardMaskUint8(mask: InpaintMaskBuffer, crop: InpaintPixelRect): ByteArray {
        require(!crop.isEmpty && crop.right <= mask.width && crop.bottom <= mask.height) {
            "Inpaint crop must be a non-empty region inside the mask"
        }
        val encoded = ByteArray(crop.width * crop.height)
        for (y in 0 until crop.height) {
            val row = y * crop.width
            for (x in 0 until crop.width) {
                encoded[row + x] = if (mask.coverageAt(crop.left + x, crop.top + y) > 0) {
                    REPAIR.toByte()
                } else {
                    KEEP.toByte()
                }
            }
        }
        return encoded
    }

    /**
     * Decodes the uint8 `result` tensor into opaque ARGB pixels.
     *
     * @param result Planar RGB bytes of `[1,3,H,W]`.
     * @throws IllegalArgumentException when the payload does not have the requested shape.
     */
    fun decodeResultRgbUint8(result: ByteArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0) { "Decoded result dimensions must be positive" }
        val plane = width * height
        require(result.size == PIXEL_CHANNELS * plane) { "MI-GAN pipeline result must be [1,3,H,W]" }
        return IntArray(plane) { index ->
            val red = result[index].toInt() and 0xFF
            val green = result[plane + index].toInt() and 0xFF
            val blue = result[2 * plane + index].toInt() and 0xFF
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    /** Tensor value that marks a pixel for repair. */
    private const val REPAIR = 0

    /** Tensor value that keeps a pixel untouched. */
    private const val KEEP = 255
}
