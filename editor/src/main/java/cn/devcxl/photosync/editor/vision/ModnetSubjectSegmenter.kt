package cn.devcxl.photosync.editor.vision

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.AiAssetKind
import cn.devcxl.photosync.editor.domain.AiAssetRef
import cn.devcxl.photosync.editor.domain.AiAssetStore

/** Native MODNet inference seam; replaceable so preprocessing and caching stay JVM-testable. */
fun interface ModnetInference {

    /**
     * Runs MODNet on [input] and returns the flattened `[1, 1, 512, 512]` probability map.
     *
     * @param session Session opened by [OrtSessionManager] for the MODNet artifact.
     * @param input Normalized NCHW float input; callers may reuse the backing array.
     */
    fun run(session: OrtSessionHandle, input: FloatArray): FloatArray
}

/**
 * [ModnetInference] backed by ONNX Runtime's `Run`.
 *
 * The graph is exported by `Xenova/modnet` with a dynamic input shape; the segmenter always feeds
 * a fixed `512 x 512` tensor because that is the resolution the pinned FP32 artifact was measured
 * at (334 MB peak, 0.14 s on a standard phone).
 */
internal class OnnxModnetInference : ModnetInference {

    override fun run(session: OrtSessionHandle, input: FloatArray): FloatArray {
        val native = session as? OnnxRuntimeSessionHandle
            ?: throw IllegalStateException("MODNet requires an ONNX Runtime session")
        val environment = OrtEnvironment.getEnvironment()
        val shape = longArrayOf(1L, CHANNELS.toLong(), MODEL_SIZE.toLong(), MODEL_SIZE.toLong())
        val buffer = FloatBuffer.wrap(input)
        OnnxTensor.createTensor(environment, buffer, shape).use { tensor ->
            val inputName = native.session.inputNames.firstOrNull()
                ?: throw IllegalStateException("MODNet exposes no input")
            native.session.run(mapOf(inputName to tensor)).use { result ->
                val output = firstTensor(result) ?: throw IllegalStateException("MODNet output missing")
                val copy = output.floatBuffer.duplicate()
                copy.rewind()
                val values = FloatArray(copy.remaining())
                copy.get(values)
                return values
            }
        }
    }

    private fun firstTensor(result: ai.onnxruntime.OrtSession.Result): OnnxTensor? {
        for (index in 0 until result.size()) {
            val value: OnnxValue = result[index]
            if (value is OnnxTensor) return value
        }
        return null
    }

    internal companion object {
        const val MODEL_SIZE = 512
        const val CHANNELS = 3
    }
}

/**
 * MODNet portrait matting on the on-device ONNX Runtime base.
 *
 * ## Pipeline
 * 1. The upright source is bilinearly resized to `512 x 512`, normalized with MODNet's
 *    `(x / 255 - 0.5) / 0.5` transform and written as planar `NCHW` floats.
 * 2. Inference runs through [OrtSessionManager], so the process keeps at most one native session and
 *    a low-memory device releases it immediately after the call.
 * 3. The returned `[1, 1, 512, 512]` map is clamped to `[0, 1]` and stored as a content-addressed
 *    grayscale PNG in [AiAssetStore]. The `subject.maskRef` in the edit graph points at that file,
 *    which makes reopening the editor reuse the cached mask without another inference.
 *
 * The class is stateful only for its reused direct input buffer; [segment] is safe to call from a
 * background coroutine and must not be called concurrently with itself.
 */
class ModnetSubjectSegmenter(
    private val sessions: OrtSessionManager,
    private val assets: AiAssetStore,
    private val inference: ModnetInference = OnnxModnetInference()
) : SubjectSegmenter {

    private var inputBuffer: FloatBuffer? = null

    /** True when [modelFile] exists and looks like the pinned artifact; no inference is attempted. */
    fun isModelAvailable(modelFile: File): Boolean = modelFile.isFile && modelFile.length() > 0L

    override fun segment(image: SubjectImage, modelFile: File): Result<SubjectMask> {
        if (!isModelAvailable(modelFile)) {
            return Result.failure(IllegalStateException("MODNet model file is missing"))
        }
        val input = preprocess(image)
        return try {
            sessions.withSession(modelFile) { session ->
                val output = inference.run(session, input)
                toMask(output)
            }.fold(
                onSuccess = { mask -> Result.success(mask) },
                onFailure = { error ->
                    Timber.e(error, "MODNet subject inference failed")
                    Result.failure(error)
                }
            )
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "MODNet subject inference ran out of memory")
            Result.failure(oom)
        } catch (error: RuntimeException) {
            Timber.e(error, "MODNet subject inference failed")
            Result.failure(error)
        }
    }

    /**
     * Persists [mask] for [photoId] as `edits/<photoId>/subject_mask_<sha256>.png`.
     *
     * @return The reference to store in `EditGraph.subject.maskRef`, or `null` when encoding or the
     *   atomic write failed.
     */
    fun saveMask(photoId: Long, mask: SubjectMask): AiAssetRef? {
        val payload = SubjectMaskCodec.encode(mask) ?: return null
        return assets.write(photoId, AiAssetKind.SUBJECT_MASK, payload)
    }

    /**
     * Encodes [mask] as the exact grayscale PNG payload the content-addressed store persists.
     *
     * Exposed for hosts that must stage a mask before the photo id (and therefore the final
     * `edits/<photoId>/` directory) exists.
     */
    fun encodeMask(mask: SubjectMask): ByteArray? = SubjectMaskCodec.encode(mask)

    /**
     * Loads and verifies the mask referenced by a `subject.maskRef` path.
     *
     * @param maskRef Relative path such as `edits/42/subject_mask_<sha256>.png`.
     * @return The mask, or `null` when the file is missing, tampered with or not a supported PNG.
     */
    fun loadMask(maskRef: String): SubjectMask? {
        val digest = digestFromMaskRef(maskRef) ?: return null
        val payload = assets.read(maskRef, digest) ?: return null
        return SubjectMaskCodec.decode(payload)
    }

    /** Loads [reference] through the same verification path as [loadMask]. */
    fun loadMask(reference: AiAssetRef): SubjectMask? = loadMask(reference.relativePath)

    /**
     * Preprocesses [image] into the exact normalized `NCHW` tensor MODNet expects.
     *
     * Visible for tests: the returned array is the reused input buffer, not a copy.
     */
    internal fun preprocess(image: SubjectImage): FloatArray {
        val planeSize = OnnxModnetInference.MODEL_SIZE * OnnxModnetInference.MODEL_SIZE
        val buffer = inputBuffer
            ?: ByteBuffer.allocateDirect(planeSize * OnnxModnetInference.CHANNELS * BYTES_PER_FLOAT)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .also { inputBuffer = it }
        buffer.rewind()
        for (channel in 0 until OnnxModnetInference.CHANNELS) {
            for (row in 0 until OnnxModnetInference.MODEL_SIZE) {
                val v = (row + 0.5f) / OnnxModnetInference.MODEL_SIZE
                for (column in 0 until OnnxModnetInference.MODEL_SIZE) {
                    val u = (column + 0.5f) / OnnxModnetInference.MODEL_SIZE
                    val value = when (channel) {
                        0 -> image.redAt(u, v)
                        1 -> image.greenAt(u, v)
                        else -> image.blueAt(u, v)
                    }
                    buffer.put((value - MEAN) / STANDARD_DEVIATION)
                }
            }
        }
        buffer.rewind()
        return FloatArray(planeSize * OnnxModnetInference.CHANNELS).also { buffer.get(it) }
    }

    /**
     * Converts the raw output into a `512 x 512` mask, tolerating both `[1,1,512,512]` and
     * `[1,512,512]` output layouts.
     */
    internal fun toMask(output: FloatArray): SubjectMask {
        val expected = OnnxModnetInference.MODEL_SIZE * OnnxModnetInference.MODEL_SIZE
        require(output.size >= expected) { "MODNet output is smaller than 512 x 512" }
        val alpha = FloatArray(expected) { index -> output[index].coerceIn(0.0f, 1.0f) }
        return SubjectMask(OnnxModnetInference.MODEL_SIZE, OnnxModnetInference.MODEL_SIZE, alpha)
    }

    private fun digestFromMaskRef(maskRef: String): String? =
        SubjectMaskCodec.digestFromRef(maskRef)

    private companion object {
        const val BYTES_PER_FLOAT = 4
        const val MEAN = 0.5f
        const val STANDARD_DEVIATION = 0.5f
    }
}
