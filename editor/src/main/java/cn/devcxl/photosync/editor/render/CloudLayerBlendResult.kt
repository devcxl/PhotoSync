package cn.devcxl.photosync.editor.render

import android.graphics.Color

import cn.devcxl.photosync.editor.domain.CloudEditLayerRef
import cn.devcxl.photosync.editor.domain.ExposureShaderMath
import cn.devcxl.photosync.editor.domain.LinearCloudLayer

/** Why a [CloudEditLayerRef] was or was not blended into a frame. */
enum class CloudBlendStatus {

    /** Pixels were fused with the requested opacity and optional feathered mask. */
    APPLIED,

    /** The graph has no cloud layer. */
    NO_LAYER,

    /** The layer belongs to a different Pass A framing and must not be pasted. */
    GEOMETRY_MISMATCH,

    /** The layer exists but its opacity is zero, so the frame is already correct. */
    OPACITY_ZERO,

    /** The layer or its mask file is missing, truncated or fails its SHA-256 check. */
    ASSET_INVALID
}

/**
 * Result of one cloud blend.
 *
 * @property status What happened.
 * @property blendedPixels Pixels whose coverage was greater than zero.
 */
data class CloudBlendResult(
    val status: CloudBlendStatus,
    val blendedPixels: Long = 0L
) {
    /** True when the frame now contains the cloud layer. */
    val isApplied: Boolean
        get() = status == CloudBlendStatus.APPLIED

    /** True when the geometry guard blocked the blend. */
    val isGeometryMismatch: Boolean
        get() = status == CloudBlendStatus.GEOMETRY_MISMATCH
}

/**
 * Decoded, frame-sized pixels of one cloud layer, ready for CPU blending or a GL upload.
 *
 * @property width Frame width the pixels were resampled to.
 * @property height Frame height the pixels were resampled to.
 * @property argb Straight `ARGB_8888` cloud pixels in row-major order.
 * @property coverage Optional blend coverage (`0..1`, row-major); `null` means the whole frame.
 * @property opacity Effective opacity in `0..1` after the `0..100` graph value was normalised.
 */
class CloudLayerPixels(
    val width: Int,
    val height: Int,
    val argb: IntArray,
    val coverage: FloatArray?,
    val opacity: Float
) {

    /**
     * Converts these display-referred pixels into the scene-linear layer the 16-bit path grades.
     *
     * The PNG is sRGB, so every channel is decoded with the same transfer function the GL Pass B
     * applies to a linear input; blending encoded values into a linear frame would darken the layer.
     */
    fun toLinear(): LinearCloudLayer {
        val count = width * height
        val rgb = FloatArray(count * LINEAR_CHANNELS)
        for (index in 0 until count) {
            val source = argb[index]
            val target = index * LINEAR_CHANNELS
            rgb[target] = ExposureShaderMath.decodeSrgb(Color.red(source) / RGB_MAX)
            rgb[target + 1] = ExposureShaderMath.decodeSrgb(Color.green(source) / RGB_MAX)
            rgb[target + 2] = ExposureShaderMath.decodeSrgb(Color.blue(source) / RGB_MAX)
        }
        return LinearCloudLayer(
            width = width,
            height = height,
            linearRgb = rgb,
            coverage = coverage,
            opacity = opacity
        )
    }

    private companion object {
        const val LINEAR_CHANNELS = 3
        const val RGB_MAX = 255.0f
    }
}

/** Outcome of [CloudLayerBlender.prepare]. */
sealed interface CloudLayerLoad {

    /**
     * Decoded pixels ready for blending or texture staging.
     *
     * @property pixels Frame-sized cloud pixels and their coverage.
     */
    class Ready(val pixels: CloudLayerPixels) : CloudLayerLoad

    /**
     * Nothing to apply; [status] explains why.
     *
     * @property status Why the layer was skipped.
     */
    class NotApplied(val status: CloudBlendStatus) : CloudLayerLoad
}
