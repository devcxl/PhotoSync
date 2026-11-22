package cn.devcxl.photosync.editor.vision

/**
 * One portrait model that can be downloaded on demand.
 *
 * @property fileName Name inside the model directory and therefore the `*.part` sibling name.
 * @property downloadUrl HTTPS source; GitHub Releases, `storage.googleapis.com` and the HuggingFace
 *   `litert-community` mirror all serve byte-identical files and support `Range`.
 * @property byteSize Exact size in bytes, verified after the transfer.
 * @property sha256 Lower-case hex SHA-256 of the whole file, verified before the atomic rename.
 */
data class PortraitModelSpec(
    val fileName: String,
    val downloadUrl: String,
    val byteSize: Long,
    val sha256: String
) {

    /** Sibling `.part` file the resumable transfer writes before the atomic rename. */
    val partFileName: String
        get() = "$fileName.part"

    companion object {
        /** The 16.37 MB FP32 six-class selfie segmentation model. */
        val MULTICLASS_SELFIE = PortraitModelSpec(
            fileName = "selfie_multiclass_256x256.tflite",
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/image_segmenter/" +
                "selfie_multiclass_256x256/float32/1/selfie_multiclass_256x256.tflite",
            byteSize = 16_371_837L,
            sha256 = "c6748b1253a99067ef71f7e26ca71096cd449baefa8f101900ea23016507e0e0"
        )
    }
}

/**
 * Models shipped inside `assets/` so the Portrait tab works offline from the first launch.
 */
object PortraitBundledModels {

    /** MediaPipe Face Landmarker task bundle (3.58 MiB, Apache-2.0). */
    const val FACE_LANDMARKER_ASSET = "face_landmarker.task"

    /** MediaPipe Selfie Segmenter, FP16, `250 KB`: the offline person mask. */
    const val SELFIE_SEGMENTER_ASSET = "selfie_segmenter.tflite"

    /** Output channels of the six-class multiclass model. */
    const val MULTICLASS_CHANNEL_COUNT = 6

    /** Class index of hair in [PortraitModelSpec.MULTICLASS_SELFIE]. */
    const val HAIR_CLASS = 1

    /** Class index of body skin in [PortraitModelSpec.MULTICLASS_SELFIE]. */
    const val BODY_SKIN_CLASS = 2

    /** Class index of face skin in [PortraitModelSpec.MULTICLASS_SELFIE]. */
    const val FACE_SKIN_CLASS = 3

    /** Class index of clothes in [PortraitModelSpec.MULTICLASS_SELFIE]. */
    const val CLOTHES_CLASS = 4
}
