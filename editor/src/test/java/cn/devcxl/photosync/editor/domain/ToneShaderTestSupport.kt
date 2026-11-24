package cn.devcxl.photosync.editor.domain

/**
 * Shared fixtures for the [ToneShaderMath] suites.
 *
 * The parameters always travel through [ToneShaderParams.from], so every assertion also covers the
 * `EditGraph` → uniform mapping the renderer uses on the GL thread.
 */
internal fun toneParams(
    exposure: Float = 0.0f,
    brightness: Int = 0,
    contrast: Int = 0,
    glow: Int = 0,
    highlights: Int = 0,
    shadows: Int = 0,
    whites: Int = 0,
    blacks: Int = 0,
    temp: Int = 0,
    tint: Int = 0
): ToneShaderParams = ToneShaderParams.from(
    EditGraph(
        tone = ToneOps(
            exposure = exposure,
            brightness = brightness,
            contrast = contrast,
            glow = glow,
            highlights = highlights,
            shadows = shadows,
            whites = whites,
            blacks = blacks
        ),
        color = ColorOps(temp = temp, tint = tint)
    )
)

internal fun neutralParams(): ToneShaderParams = toneParams()

internal fun render(params: ToneShaderParams, sample: FloatArray): FloatArray {
    val out = FloatArray(3)
    ToneShaderMath.apply(params, sample, out)
    return out
}

internal fun render(params: ToneShaderParams, red: Float, green: Float, blue: Float): FloatArray =
    render(params, floatArrayOf(red, green, blue))

/** Renders a neutral grey through [params] and returns the resulting display sample. */
internal fun renderGrey(params: ToneShaderParams, grey: Float): Float =
    render(params, floatArrayOf(grey, grey, grey))[0]
