package cn.devcxl.photosync.editor.vision

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteOrder
import java.nio.channels.FileChannel

import android.content.Context
import android.graphics.Bitmap

import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.FaceLandmarks

/**
 * Raw output of one MediaPipe portrait pass.
 *
 * @property faces Up to five 478-point meshes in normalized image coordinates.
 * @property confidenceMasks Six `256 x 256` class probabilities when the multiclass model is
 *   loaded, otherwise `null`.
 * @property personMask A single `256 x 256` person probability when only the built-in selfie
 *   segmenter is available, otherwise `null`.
 */
class PortraitEngineResult(
    val faces: List<FaceLandmarks>,
    val confidenceMasks: List<FloatArray>?,
    val personMask: FloatArray?,
    val usesMulticlass: Boolean
)

/**
 * Thin, defensive wrapper around `FaceLandmarker` and `ImageSegmenter`.
 *
 * The engine runs the bundled `face_landmarker.task` plus `selfie_segmenter.tflite` from the first
 * frame, and swaps the segmenter for the downloaded six-class model as soon as
 * [upgradeToMulticlass] succeeds. Everything is created through [MediaPipeDelegatePolicy], so a
 * GPU initialisation failure degrades to CPU instead of breaking the Portrait tab.
 */
class MediaPipePortraitEngine(
    private val context: Context,
    private val delegatePolicy: MediaPipeDelegatePolicy
) {

    private var faceLandmarker: FaceLandmarker? = null
    private var segmenter: ImageSegmenter? = null
    private var multiclassSegmenter: ImageSegmenter? = null
    private var maskBuffer: FloatArray? = null

    /** True when both the landmarker and at least one segmenter are alive. */
    val isReady: Boolean
        get() = faceLandmarker != null && (segmenter != null || multiclassSegmenter != null)

    /** True when the downloaded six-class model has replaced the built-in person segmenter. */
    val isMulticlassReady: Boolean
        get() = multiclassSegmenter != null

    /** Creates the built-in tasks; safe to call repeatedly. */
    fun ensureInitialized(): Boolean {
        if (faceLandmarker == null) {
            faceLandmarker = delegatePolicy.createTask { delegate -> createFaceLandmarker(delegate) }
        }
        if (segmenter == null && multiclassSegmenter == null) {
            segmenter = delegatePolicy.createTask { delegate ->
                createSegmenter(delegate, assetPath = PortraitBundledModels.SELFIE_SEGMENTER_ASSET)
            }
        }
        if (!isReady) {
            Timber.w("MediaPipe portrait engine is unavailable on this device")
        }
        return isReady
    }

    /**
     * Loads the downloaded six-class model and drops the built-in segmenter.
     *
     * @return True when the multiclass segmenter is now active.
     */
    fun upgradeToMulticlass(modelFile: File): Boolean {
        if (multiclassSegmenter != null) return true
        if (!modelFile.isFile) return false
        val buffer = try {
            FileInputStream(modelFile).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
            }
        } catch (e: IOException) {
            Timber.e(e, "Downloaded portrait model could not be mapped into memory")
            return false
        }
        val upgraded = delegatePolicy.createTask { delegate ->
            createSegmenter(delegate, modelBuffer = buffer)
        }
        if (upgraded == null) {
            Timber.w("MediaPipe rejected the downloaded multiclass model; keeping the person mask")
            return false
        }
        multiclassSegmenter = upgraded
        runCatching { segmenter?.close() }.onFailure {
            Timber.w(it, "Failed to close built-in segmenter after multiclass upgrade")
        }
        segmenter = null
        return true
    }

    /** Runs the landmarker and the active segmenter on [bitmap]. */
    fun analyze(bitmap: Bitmap): PortraitEngineResult? {
        if (!ensureInitialized()) return null
        val image = BitmapImageBuilder(bitmap).build()
        return try {
            val faces = detectFaces(image)
            val multiclass = multiclassSegmenter
            if (multiclass != null) {
                PortraitEngineResult(
                    faces = faces,
                    confidenceMasks = readConfidenceMasks(multiclass, image),
                    personMask = null,
                    usesMulticlass = true
                )
            } else {
                PortraitEngineResult(
                    faces = faces,
                    confidenceMasks = null,
                    personMask = segmenter?.let { readPersonMask(it, image) },
                    usesMulticlass = false
                )
            }
        } catch (e: RuntimeException) {
            Timber.e(e, "MediaPipe portrait analysis failed")
            null
        } finally {
            image.close()
        }
    }

    /** Releases every task; the engine re-creates them on the next [analyze]. */
    fun release() {
        closeTask("faceLandmarker") { faceLandmarker?.close() }
        closeTask("segmenter") { segmenter?.close() }
        closeTask("multiclassSegmenter") { multiclassSegmenter?.close() }
        faceLandmarker = null
        segmenter = null
        multiclassSegmenter = null
    }

    private inline fun closeTask(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Timber.w(e, "Failed to close MediaPipe %s task", name)
        }
    }

    private fun detectFaces(image: MPImage): List<FaceLandmarks> {
        val landmarker = faceLandmarker ?: return emptyList()
        val result = landmarker.detect(image)
        return result.faceLandmarks().mapNotNull { landmarks ->
            if (landmarks.isEmpty()) null else toFaceLandmarks(landmarks)
        }
    }

    private fun toFaceLandmarks(landmarks: List<NormalizedLandmark>): FaceLandmarks {
        val points = FloatArray(FaceLandmarks.POINT_COUNT * FaceLandmarks.STRIDE)
        val count = minOf(landmarks.size, FaceLandmarks.POINT_COUNT)
        for (index in 0 until count) {
            val landmark = landmarks[index]
            val offset = index * FaceLandmarks.STRIDE
            points[offset] = landmark.x()
            points[offset + 1] = landmark.y()
            points[offset + 2] = landmark.z()
        }
        return FaceLandmarks(points)
    }

    private fun readConfidenceMasks(segmenter: ImageSegmenter, image: MPImage): List<FloatArray>? {
        val result = segmenter.segment(image)
        val masks = result.confidenceMasks().orElse(null) ?: return null
        if (masks.size < PortraitBundledModels.MULTICLASS_CHANNEL_COUNT) {
            Timber.w("Multiclass model returned %d masks; expected 6", masks.size)
            return null
        }
        return masks.mapNotNull { maskImage -> readFloatMask(maskImage) }
            .takeIf { it.size == PortraitBundledModels.MULTICLASS_CHANNEL_COUNT }
    }

    private fun readPersonMask(segmenter: ImageSegmenter, image: MPImage): FloatArray? {
        val result = segmenter.segment(image)
        val masks = result.confidenceMasks().orElse(null) ?: return null
        return masks.firstOrNull()?.let { readFloatMask(it) }
    }

    /**
     * Reads one `H x W` float mask.
     *
     * MediaPipe hands the masks out as `MPImage`s whose storage is a `float32` plane; the extractor
     * is the only supported way to reach it and throws when the driver built a different layout, so
     * a `null` here simply disables skin smoothing instead of crashing the editor.
     */
    private fun readFloatMask(maskImage: MPImage): FloatArray? {
        return try {
            val width = maskImage.width
            val height = maskImage.height
            if (width <= 0 || height <= 0) return null
            val buffer = ByteBufferExtractor.extract(maskImage, MPImage.IMAGE_FORMAT_VEC32F1)
            buffer.rewind()
            val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
            val required = width * height
            if (floats.remaining() < required) return null
            val reused = maskBuffer?.takeIf { it.size == required } ?: FloatArray(required).also {
                maskBuffer = it
            }
            floats.get(reused, 0, required)
            reused.copyOf()
        } catch (e: RuntimeException) {
            Timber.w(e, "A MediaPipe mask could not be read; skin smoothing stays disabled")
            null
        }
    }

    private fun createFaceLandmarker(delegate: Delegate): FaceLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(PortraitBundledModels.FACE_LANDMARKER_ASSET)
            .setDelegate(delegate)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(MAX_FACES)
            .setOutputFaceBlendshapes(false)
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    private fun createSegmenter(
        delegate: Delegate,
        assetPath: String? = null,
        modelBuffer: java.nio.ByteBuffer? = null
    ): ImageSegmenter {
        val baseBuilder = BaseOptions.builder().setDelegate(delegate)
        if (modelBuffer != null) {
            baseBuilder.setModelAssetBuffer(modelBuffer)
        } else if (assetPath != null) {
            baseBuilder.setModelAssetPath(assetPath)
        }
        val options = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(baseBuilder.build())
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(false)
            .setOutputConfidenceMasks(true)
            .build()
        return ImageSegmenter.createFromOptions(context, options)
    }

    private companion object {
        private const val MAX_FACES = 5
    }
}
