package cn.devcxl.photosync.editor.domain

/**
 * What a liquify brush stamp does.
 */
enum class LiquifyBrushMode {
    /** Push content in the drag direction. */
    PUSH,

    /** Erase displacement back towards the untouched image. */
    RESTORE
}

/**
 * Tier-1 settings of the liquify brush.
 *
 * `sizeNorm` is the brush radius in normalized horizontal frame units and `strength` is the `0..1`
 * equivalent of the panel's `0..100` slider.
 */
data class LiquifyBrushSettings(
    val mode: LiquifyBrushMode = LiquifyBrushMode.PUSH,
    val sizeNorm: Float = DEFAULT_SIZE_NORM,
    val strength: Float = DEFAULT_STRENGTH
) {

    /** Returns a copy with [mode] selected. */
    fun withMode(mode: LiquifyBrushMode): LiquifyBrushSettings = copy(mode = mode)

    /** Returns a copy with the radius clamped into the legal range. */
    fun withSizeNorm(sizeNorm: Float): LiquifyBrushSettings =
        copy(sizeNorm = sizeNorm.takeIf { it.isFinite() }?.coerceIn(MIN_SIZE_NORM, MAX_SIZE_NORM) ?: this.sizeNorm)

    /** Returns a copy with the strength clamped into `0..1`. */
    fun withStrength(strength: Float): LiquifyBrushSettings =
        copy(strength = strength.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f) ?: this.strength)

    companion object {
        /** Smallest brush radius the size slider can reach. */
        const val MIN_SIZE_NORM = 0.03f

        /** Largest brush radius the size slider can reach. */
        const val MAX_SIZE_NORM = 0.20f

        /** Default brush radius: a comfortable cheek-sized stamp on a phone screen. */
        const val DEFAULT_SIZE_NORM = 0.08f

        /** Default strength, roughly half of the maximum. */
        const val DEFAULT_STRENGTH = 0.5f
    }
}
