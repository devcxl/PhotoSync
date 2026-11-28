package cn.devcxl.photosync.editor.vision

import java.io.File
import java.io.IOException
import java.util.Properties

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import timber.log.Timber

/**
 * Element type of one ONNX model input, reduced to the two variants the inpainting tool supports.
 */
enum class ModelTensorType {

    /** `tensor(float)`, i.e. the legacy generator's `input` tensor. */
    FLOAT32,

    /** `tensor(uint8)`, i.e. the pipeline's `image` / `mask` tensors. */
    UINT8,

    /** Any other element type; such an input can never match a supported variant. */
    OTHER
}

/**
 * Structural description of one model input, independent of ONNX Runtime.
 *
 * @property name Graph input name, e.g. `input`, `image` or `mask`.
 * @property elementType Reduced element type.
 * @property shape Declared dimensions; dynamic dimensions are `-1`.
 */
data class ModelInputSignature(
    val name: String,
    val elementType: ModelTensorType,
    val shape: List<Long>
)

/**
 * Reads the input signatures of an ONNX file.
 *
 * The seam keeps [InpaintVariantDetector] unit-testable on the JVM: production uses
 * [OrtOnnxSignatureProbe], tests hand in fixed signatures.
 */
fun interface OnnxSignatureProbe {

    /**
     * @return Every input of [modelFile] in declaration order.
     * @throws OrtException when the runtime cannot load the model.
     */
    fun read(modelFile: File): List<ModelInputSignature>
}

/** [OnnxSignatureProbe] backed by a short-lived native [OrtSession]. */
class OrtOnnxSignatureProbe : OnnxSignatureProbe {

    override fun read(modelFile: File): List<ModelInputSignature> {
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        try {
            // The probe only reads metadata: no XNNPACK, no optimisation and no run is needed.
            environment.createSession(modelFile.absolutePath, options).use { session ->
                return session.inputInfo.map { (name, node) ->
                    node.toSignature(name)
                }
            }
        } finally {
            options.close()
        }
    }

    private fun NodeInfo.toSignature(name: String): ModelInputSignature {
        val tensor = info as? TensorInfo
            ?: return ModelInputSignature(name, ModelTensorType.OTHER, emptyList())
        return ModelInputSignature(
            name = name,
            elementType = tensor.type.toModelTensorType(),
            shape = tensor.shape?.toList() ?: emptyList()
        )
    }

    private fun OnnxJavaType.toModelTensorType(): ModelTensorType = when (this) {
        OnnxJavaType.FLOAT -> ModelTensorType.FLOAT32
        OnnxJavaType.UINT8 -> ModelTensorType.UINT8
        else -> ModelTensorType.OTHER
    }
}

/**
 * Classifies a MI-GAN artifact into an [InpaintVariant] from its ONNX input signature (spec §6.1).
 *
 * ## Rules
 * | Inputs | Variant |
 * |---|---|
 * | `{input}`, float32 `[1,4,512,512]` | [InpaintVariant.GENERATOR_FIXED_512] |
 * | `{image, mask}`, both uint8, rank 4, dynamic H/W | [InpaintVariant.PIPELINE_UINT8] |
 * | anything else | rejected with [AiModelFailureReason.INCOMPATIBLE] |
 *
 * ## Cache
 * The verdict is stored next to the model file as `.<file>.variant.properties` together with the
 * file length and the verified catalog SHA-256. A later detection skips the native probe when the
 * length still matches and the recorded digest equals the expected one; a length or digest change
 * forces a re-probe. The SHA-256 is never recomputed here — the install path already verified it,
 * and hashing tens of megabytes on every open would defeat the cache.
 *
 * @param probe Input-signature reader; tests replace it with a fake.
 * @param clock Timestamp source for the journal; injectable for deterministic tests.
 */
class InpaintVariantDetector(
    private val probe: OnnxSignatureProbe = OrtOnnxSignatureProbe(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    /**
     * Detects [modelFile]'s variant, using the cache when its identity still matches.
     *
     * @param modelFile Verified `.onnx` file.
     * @param sha256 Verified catalog digest of that file, or blank when the caller has none.
     * @throws AiModelInstallException carrying [AiModelFailureReason.INCOMPATIBLE] when the
     *   signature matches no supported variant or the file cannot be read.
     */
    fun detect(modelFile: File, sha256: String = ""): InpaintVariant {
        if (!modelFile.isFile) {
            throw incompatible("MI-GAN model file is missing")
        }
        val length = modelFile.length()
        val cached = readCache(modelFile)
        if (cached != null && cached.matches(length, sha256)) {
            return cached.variant ?: throw incompatible("Cached MI-GAN signature is incompatible")
        }
        val inputs = try {
            probe.read(modelFile)
        } catch (error: OrtException) {
            throw AiModelInstallException(
                AiModelFailureReason.INCOMPATIBLE,
                "MI-GAN model could not be read",
                error
            )
        } catch (error: RuntimeException) {
            throw AiModelInstallException(
                AiModelFailureReason.INCOMPATIBLE,
                "MI-GAN model signature could not be probed",
                error
            )
        }
        val variant = classify(inputs)
        writeCache(modelFile, length, sha256, variant)
        return variant ?: throw incompatible(
            "MI-GAN inputs ${inputs.map { it.name }} match no supported variant"
        )
    }

    /**
     * Builds the [AiModelVariantCompatibility] seam used by the download and SAF-import paths.
     *
     * A `null` hint means the caller does not care about the variant, so the file is accepted
     * without loading ONNX Runtime. A failed probe rejects the file with the normal
     * `INCOMPATIBLE` install failure.
     */
    fun compatibility(): AiModelVariantCompatibility =
        AiModelVariantCompatibility { file, hint ->
            hint == null || runCatching { detect(file, "") == hint }.getOrDefault(false)
        }

    /**
     * Applies the two signature rules to a list of inputs.
     *
     * @return The matching variant, or `null` when the signature is unsupported.
     */
    fun classify(inputs: List<ModelInputSignature>): InpaintVariant? {
        if (inputs.size == 1) {
            val input = inputs.single()
            if (input.name == GENERATOR_INPUT_NAME &&
                input.elementType == ModelTensorType.FLOAT32 &&
                input.shape == GENERATOR_SHAPE
            ) {
                return InpaintVariant.GENERATOR_FIXED_512
            }
            return null
        }
        if (inputs.size != PIPELINE_INPUTS.size) return null
        if (inputs.map { it.name }.toSet() != PIPELINE_INPUTS) return null
        val allValid = inputs.all { input ->
            input.elementType == ModelTensorType.UINT8 &&
                input.shape.size == PIPELINE_RANK &&
                input.shape[DYNAMIC_AXIS_1] == DYNAMIC_DIMENSION &&
                input.shape[DYNAMIC_AXIS_2] == DYNAMIC_DIMENSION
        }
        return if (allValid) InpaintVariant.PIPELINE_UINT8 else null
    }

    private fun incompatible(message: String): AiModelInstallException =
        AiModelInstallException(AiModelFailureReason.INCOMPATIBLE, message)

    private fun cacheFile(modelFile: File): File {
        val parent = modelFile.parentFile ?: File(".")
        return File(parent, ".${modelFile.name}.$CACHE_SUFFIX")
    }

    private fun readCache(modelFile: File): CachedVariant? {
        val file = cacheFile(modelFile)
        if (!file.isFile) return null
        return try {
            val properties = Properties()
            file.inputStream().use(properties::load)
            val length = properties.getProperty(KEY_LENGTH)?.toLongOrNull() ?: return null
            val sha = properties.getProperty(KEY_SHA256).orEmpty()
            val rawVariant = properties.getProperty(KEY_VARIANT) ?: return null
            val variant = if (rawVariant == INCOMPATIBLE_MARKER) {
                null
            } else {
                InpaintVariant.valueOf(rawVariant)
            }
            CachedVariant(length = length, sha256 = sha, variant = variant)
        } catch (error: IOException) {
            Timber.w(error, "MI-GAN variant cache could not be read")
            null
        } catch (error: IllegalArgumentException) {
            Timber.w(error, "MI-GAN variant cache had an unreadable value")
            null
        }
    }

    private fun writeCache(
        modelFile: File,
        length: Long,
        sha256: String,
        variant: InpaintVariant?
    ) {
        val target = cacheFile(modelFile)
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            Timber.w("MI-GAN variant cache directory could not be created")
            return
        }
        val properties = Properties().apply {
            setProperty(KEY_LENGTH, length.toString())
            setProperty(KEY_SHA256, sha256.trim().lowercase())
            setProperty(KEY_VARIANT, variant?.name ?: INCOMPATIBLE_MARKER)
            setProperty(KEY_PROBED_AT, clock().toString())
        }
        val temporary = try {
            File.createTempFile(CACHE_TEMP_PREFIX, ".tmp", parent)
        } catch (error: IOException) {
            Timber.w(error, "MI-GAN variant cache temporary file could not be created")
            return
        }
        try {
            temporary.outputStream().use { properties.store(it, null) }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } catch (error: IOException) {
            Timber.w(error, "MI-GAN variant cache could not be persisted")
            temporary.delete()
        }
    }

    private class CachedVariant(
        val length: Long,
        val sha256: String,
        val variant: InpaintVariant?
    ) {

        fun matches(actualLength: Long, expectedSha256: String): Boolean {
            if (length != actualLength) return false
            val expected = expectedSha256.trim()
            if (expected.isEmpty()) return true
            return sha256.equals(expected, ignoreCase = true)
        }
    }

    private companion object {
        const val GENERATOR_INPUT_NAME = "input"
        const val PIPELINE_RANK = 4
        const val DYNAMIC_AXIS_1 = 2
        const val DYNAMIC_AXIS_2 = 3
        const val DYNAMIC_DIMENSION = -1L
        const val CACHE_SUFFIX = "variant.properties"
        const val CACHE_TEMP_PREFIX = "variant-"
        const val KEY_LENGTH = "fileLength"
        const val KEY_SHA256 = "sha256"
        const val KEY_VARIANT = "variant"
        const val KEY_PROBED_AT = "probedAt"
        const val INCOMPATIBLE_MARKER = "INCOMPATIBLE"

        val GENERATOR_SHAPE: List<Long> = listOf(1L, 4L, 512L, 512L)
        val PIPELINE_INPUTS: Set<String> = setOf("image", "mask")
    }
}
