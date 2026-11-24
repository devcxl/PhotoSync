package cn.devcxl.photosync.editor.domain

import kotlin.math.abs

/**
 * Starting tone baseline for RAW photos when all user sliders are at zero.
 */
enum class BaseLook(val wireName: String) {
    CAMERA_DEFAULT("camera_default"),
    LINEAR_NEUTRAL("linear_neutral");

    companion object {
        fun fromWireName(raw: String?): BaseLook =
            entries.firstOrNull { it.wireName == raw } ?: CAMERA_DEFAULT
    }
}

data class CropRect(
    val left: Float = 0.0f,
    val top: Float = 0.0f,
    val right: Float = 1.0f,
    val bottom: Float = 1.0f
) {
    fun isIdentity(): Boolean =
        abs(left) < 1e-4f &&
            abs(top) < 1e-4f &&
            abs(right - 1.0f) < 1e-4f &&
            abs(bottom - 1.0f) < 1e-4f
}

data class LiquifyRef(
    val meshRelPath: String,
    val gridSize: Int = 128,
    val sha256: String = ""
)

data class GeometryOps(
    val rotate90: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val straighten: Float = 0.0f,
    val crop: CropRect = CropRect(),
    val perspective: List<Float> = DEFAULT_PERSPECTIVE_QUAD,
    val liquify: LiquifyRef? = null
) {
    fun isIdentity(): Boolean =
        rotate90 == 0 &&
            !flipH &&
            !flipV &&
            abs(straighten) < 1e-4f &&
            crop.isIdentity() &&
            perspective == DEFAULT_PERSPECTIVE_QUAD &&
            liquify == null

    companion object {
        val DEFAULT_PERSPECTIVE_QUAD = listOf(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f)
    }
}

data class ToneOps(
    val exposure: Float = 0.0f,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val glow: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
    val whites: Int = 0,
    val blacks: Int = 0
) {
    fun isIdentity(): Boolean =
        abs(exposure) < 1e-4f &&
            brightness == 0 &&
            contrast == 0 &&
            glow == 0 &&
            highlights == 0 &&
            shadows == 0 &&
            whites == 0 &&
            blacks == 0
}

data class HslBandShift(
    val hue: Int = 0,
    val saturation: Int = 0,
    val luminance: Int = 0
) {
    fun isIdentity(): Boolean = hue == 0 && saturation == 0 && luminance == 0
}

data class HslOps(
    val red: HslBandShift = HslBandShift(),
    val orange: HslBandShift = HslBandShift(),
    val yellow: HslBandShift = HslBandShift(),
    val green: HslBandShift = HslBandShift(),
    val aqua: HslBandShift = HslBandShift(),
    val blue: HslBandShift = HslBandShift(),
    val purple: HslBandShift = HslBandShift(),
    val magenta: HslBandShift = HslBandShift()
) {
    fun isIdentity(): Boolean =
        red.isIdentity() &&
            orange.isIdentity() &&
            yellow.isIdentity() &&
            green.isIdentity() &&
            aqua.isIdentity() &&
            blue.isIdentity() &&
            purple.isIdentity() &&
            magenta.isIdentity()
}

data class CurvePoint(
    val x: Float,
    val y: Float
)

data class CurveOps(
    val rgb: List<CurvePoint> = DEFAULT_LINEAR_CURVE,
    val r: List<CurvePoint> = DEFAULT_LINEAR_CURVE,
    val g: List<CurvePoint> = DEFAULT_LINEAR_CURVE,
    val b: List<CurvePoint> = DEFAULT_LINEAR_CURVE
) {
    fun isIdentity(): Boolean =
        isChannelLinear(rgb) &&
            isChannelLinear(r) &&
            isChannelLinear(g) &&
            isChannelLinear(b)

    companion object {
        val DEFAULT_LINEAR_CURVE = listOf(CurvePoint(0.0f, 0.0f), CurvePoint(1.0f, 1.0f))

        private fun isChannelLinear(points: List<CurvePoint>): Boolean =
            points.size == 2 &&
                abs(points[0].x) < 1e-4f &&
                abs(points[0].y) < 1e-4f &&
                abs(points[1].x - 1.0f) < 1e-4f &&
                abs(points[1].y - 1.0f) < 1e-4f
    }
}

data class SplitToningOps(
    val hiHue: Int = 45,
    val hiSat: Int = 0,
    val shHue: Int = 210,
    val shSat: Int = 0,
    val balance: Int = 0
) {
    fun isIdentity(): Boolean = hiSat == 0 && shSat == 0
}

data class LutRef(
    val id: String,
    val strength: Int = 100
)

data class ColorOps(
    val temp: Int = 0,
    val tint: Int = 0,
    val saturation: Int = 0,
    val vibrance: Int = 0,
    val hsl: HslOps = HslOps(),
    val curve: CurveOps = CurveOps(),
    val split: SplitToningOps = SplitToningOps(),
    val fade: Int = 0,
    val lut: LutRef? = null
) {
    fun isIdentity(): Boolean =
        temp == 0 &&
            tint == 0 &&
            saturation == 0 &&
            vibrance == 0 &&
            hsl.isIdentity() &&
            curve.isIdentity() &&
            split.isIdentity() &&
            fade == 0 &&
            (lut == null || lut.strength == 0)
}

data class SharpenOps(
    val amount: Int = 0,
    val radius: Float = 1.0f,
    val threshold: Int = 0,
    val masking: Int = 0
) {
    fun isIdentity(): Boolean = amount == 0
}

data class NoiseReductionOps(
    val luma: Int = 0,
    val chroma: Int = 0,
    val detail: Int = 50
) {
    fun isIdentity(): Boolean = luma == 0 && chroma == 0
}

data class DetailOps(
    val sharpen: SharpenOps = SharpenOps(),
    val clarity: Int = 0,
    val texture: Int = 0,
    val dehaze: Int = 0,
    val nr: NoiseReductionOps = NoiseReductionOps()
) {
    fun isIdentity(): Boolean =
        sharpen.isIdentity() &&
            clarity == 0 &&
            texture == 0 &&
            dehaze == 0 &&
            nr.isIdentity()
}

data class GrainOps(
    val amount: Int = 0,
    val size: Int = 25,
    val roughness: Int = 50,
    val seed: Int = 104729
) {
    fun isIdentity(): Boolean = amount == 0
}

data class VignetteOps(
    val amount: Int = 0,
    val midpoint: Int = 50,
    val feather: Int = 50,
    val roundness: Int = 0
) {
    fun isIdentity(): Boolean = amount == 0
}

data class ChromaticAberrationOps(
    val redCyan: Int = 0,
    val blueYellow: Int = 0,
    val defringe: Int = 0
) {
    fun hasLateralCa(): Boolean = redCyan != 0 || blueYellow != 0

    fun isIdentity(): Boolean = !hasLateralCa() && defringe == 0
}

data class EffectsOps(
    val grain: GrainOps = GrainOps(),
    val vignette: VignetteOps = VignetteOps(),
    val ca: ChromaticAberrationOps = ChromaticAberrationOps()
) {
    fun isIdentity(): Boolean =
        grain.isIdentity() &&
            vignette.isIdentity() &&
            ca.isIdentity()
}

data class SkinOps(
    val smooth: Int = 0,
    val texture: Int = 50,
    val evenTone: Int = 0
) {
    fun isIdentity(): Boolean = smooth == 0 && evenTone == 0
}

data class ReshapeOps(
    val face: Int = 0,
    val eyes: Int = 0,
    val nose: Int = 0,
    val chin: Int = 0,
    val forehead: Int = 0,
    val smile: Int = 0
) {
    fun isIdentity(): Boolean =
        face == 0 && eyes == 0 && nose == 0 && chin == 0 && forehead == 0 && smile == 0
}

data class BodyOps(
    val waist: Int = 0,
    val legs: Int = 0,
    val waistY: Float? = null,
    val hipY: Float? = null
) {
    fun isIdentity(): Boolean = waist == 0 && legs == 0
}

data class PortraitOps(
    val skin: SkinOps = SkinOps(),
    val reshape: ReshapeOps = ReshapeOps(),
    val body: BodyOps = BodyOps()
) {
    fun isIdentity(): Boolean =
        skin.isIdentity() &&
            reshape.isIdentity() &&
            body.isIdentity()
}

/**
 * Non-destructive parametric edit graph v1 for a single photo.
 */
data class EditGraph(
    val schema: Int = CURRENT_SCHEMA_VERSION,
    val baseLook: BaseLook = BaseLook.CAMERA_DEFAULT,
    val geometry: GeometryOps = GeometryOps(),
    val tone: ToneOps = ToneOps(),
    val color: ColorOps = ColorOps(),
    val detail: DetailOps = DetailOps(),
    val effects: EffectsOps = EffectsOps(),
    val portrait: PortraitOps = PortraitOps()
) {
    /**
     * Returns true when every parameter equals its neutral default so no edit record needs
     * to be persisted.
     */
    fun isIdentity(): Boolean =
        baseLook == BaseLook.CAMERA_DEFAULT &&
            geometry.isIdentity() &&
            tone.isIdentity() &&
            color.isIdentity() &&
            detail.isIdentity() &&
            effects.isIdentity() &&
            portrait.isIdentity()

    /**
     * True when Pass A (crop, rotate, straighten, perspective, lateral CA or warp mesh) must run;
     * display-domain `defringe` runs in Pass B and does not force a Pass A resample.
     */
    fun requiresPassAGeometry(hasWarp: Boolean = false): Boolean =
        !geometry.isIdentity() || effects.ca.hasLateralCa() || hasWarp

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
