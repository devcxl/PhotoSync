package cn.devcxl.photosync.editor.domain

/**
 * Pure-domain descriptor of one curated built-in look before localization or Room mapping.
 */
data class BuiltinPresetSpec(
    val id: String,
    val graphSubsetJson: String,
    val createdAt: Long
)

/**
 * The curated built-in look library shipped with the app.
 *
 * Every entry is a *pure parameter* preset: it only carries `baseLook`, tone, colour, detail, effects
 * and portrait skin, never a crop, straighten, perspective quad, liquify grid, face reshape or body
 * warp. Applying one replaces the whole appearance, so a built-in look resets any face or body
 * reshaping to neutral — only a user preset carries that work. Display names live in localized
 * `strings.xml` so `domain/` stays pure Kotlin without hardcoded UI strings or dependencies on
 * `contract/`.
 */
object BuiltinPresets {

    /** Stable id of the clean portrait look. */
    const val CLEAN_PORTRAIT_ID = "builtin_clean_portrait"

    /** Stable id of the chrome film look. */
    const val FILM_CHROME_ID = "builtin_film_chrome"

    /** Stable id of the warm sunset look. */
    const val WARM_SUNSET_ID = "builtin_warm_sunset"

    /** Stable id of the cool nordic look. */
    const val COOL_NORDIC_ID = "builtin_cool_nordic"

    /** Stable id of the monochrome silver look. */
    const val MONO_SILVER_ID = "builtin_mono_silver"

    /** Returns every built-in preset descriptor in display order. */
    fun specs(): List<BuiltinPresetSpec> = listOf(
        specOf(CLEAN_PORTRAIT_ID, cleanPortraitGraph()),
        specOf(FILM_CHROME_ID, filmChromeGraph()),
        specOf(WARM_SUNSET_ID, warmSunsetGraph()),
        specOf(COOL_NORDIC_ID, coolNordicGraph()),
        specOf(MONO_SILVER_ID, monoSilverGraph())
    )

    /** Returns the graph of the built-in preset with [id], or `null` when [id] is not built in. */
    fun graphFor(id: String): EditGraph? = when (id) {
        CLEAN_PORTRAIT_ID -> cleanPortraitGraph()
        FILM_CHROME_ID -> filmChromeGraph()
        WARM_SUNSET_ID -> warmSunsetGraph()
        COOL_NORDIC_ID -> coolNordicGraph()
        MONO_SILVER_ID -> monoSilverGraph()
        else -> null
    }

    private fun specOf(id: String, graph: EditGraph): BuiltinPresetSpec = BuiltinPresetSpec(
        id = id,
        graphSubsetJson = EditGraphJsonCodec.encode(graph),
        createdAt = FIXED_CREATED_AT
    )

    private fun cleanPortraitGraph(): EditGraph = EditGraph(
        tone = ToneOps(exposure = 0.3f, brightness = 6, contrast = 8, highlights = -18, shadows = 14),
        color = ColorOps(
            temp = 6,
            tint = 2,
            saturation = -4,
            vibrance = 12,
            split = SplitToningOps(hiHue = 38, hiSat = 8, shHue = 205, shSat = 10, balance = -10),
            fade = 6
        ),
        detail = DetailOps(
            clarity = 8,
            texture = -6,
            sharpen = SharpenOps(amount = 32, radius = 1.0f, threshold = 12, masking = 40),
            nr = NoiseReductionOps(luma = 16, chroma = 20, detail = 62)
        ),
        portrait = PortraitOps(skin = SkinOps(smooth = 34, texture = 46, evenTone = 18))
    )

    private fun filmChromeGraph(): EditGraph = EditGraph(
        baseLook = BaseLook.CAMERA_DEFAULT,
        tone = ToneOps(contrast = 16, glow = 12, highlights = -26, shadows = 22, whites = -8, blacks = 10),
        color = ColorOps(
            temp = -8,
            tint = 4,
            saturation = -12,
            vibrance = 18,
            split = SplitToningOps(hiHue = 52, hiSat = 12, shHue = 192, shSat = 18, balance = 0),
            fade = 22
        ),
        detail = DetailOps(
            clarity = 14,
            texture = 8,
            dehaze = 6,
            sharpen = SharpenOps(amount = 26, threshold = 6, masking = 28)
        ),
        effects = EffectsOps(
            grain = GrainOps(amount = 22, size = 38, roughness = 62, seed = 424_242),
            vignette = VignetteOps(amount = -14, midpoint = 46, feather = 62, roundness = 8)
        )
    )

    private fun warmSunsetGraph(): EditGraph = EditGraph(
        tone = ToneOps(
            exposure = 0.15f,
            brightness = 4,
            contrast = 10,
            glow = 26,
            highlights = -14,
            shadows = 28
        ),
        color = ColorOps(
            temp = 26,
            tint = -6,
            saturation = 12,
            vibrance = 22,
            hsl = HslOps(
                orange = HslBandShift(hue = -6, saturation = 16, luminance = 8),
                yellow = HslBandShift(hue = -4, saturation = 10, luminance = 6)
            ),
            split = SplitToningOps(hiHue = 32, hiSat = 22, shHue = 226, shSat = 14, balance = 24),
            fade = 10
        ),
        detail = DetailOps(clarity = 6, dehaze = 12),
        effects = EffectsOps(
            vignette = VignetteOps(amount = -18, midpoint = 42, feather = 58, roundness = -6)
        )
    )

    private fun coolNordicGraph(): EditGraph = EditGraph(
        baseLook = BaseLook.LINEAR_NEUTRAL,
        tone = ToneOps(brightness = -4, contrast = 14, highlights = -20, shadows = 10, blacks = -6),
        color = ColorOps(
            temp = -28,
            tint = 6,
            saturation = -16,
            vibrance = 8,
            hsl = HslOps(
                blue = HslBandShift(hue = 4, saturation = 14, luminance = -8),
                aqua = HslBandShift(hue = 2, saturation = 10, luminance = 4)
            ),
            split = SplitToningOps(hiHue = 205, hiSat = 18, shHue = 232, shSat = 22, balance = -16),
            fade = 8
        ),
        detail = DetailOps(clarity = 18, texture = 10, dehaze = 18),
        effects = EffectsOps(
            grain = GrainOps(amount = 12, size = 30, roughness = 48, seed = 313_131)
        )
    )

    private fun monoSilverGraph(): EditGraph = EditGraph(
        tone = ToneOps(
            exposure = 0.1f,
            brightness = 2,
            contrast = 22,
            highlights = -24,
            shadows = 16,
            whites = -6
        ),
        color = ColorOps(
            saturation = -100,
            vibrance = -100,
            split = SplitToningOps(hiHue = 40, hiSat = 6, shHue = 212, shSat = 8, balance = 0),
            fade = 12
        ),
        detail = DetailOps(
            clarity = 20,
            texture = 14,
            sharpen = SharpenOps(amount = 44, radius = 1.2f, threshold = 18, masking = 52)
        ),
        effects = EffectsOps(
            grain = GrainOps(amount = 34, size = 52, roughness = 72, seed = 777_777),
            vignette = VignetteOps(amount = -22, midpoint = 48, feather = 64, roundness = 0)
        )
    )

    /** Fixed timestamp so re-seeding the table is a byte-identical no-op. */
    private const val FIXED_CREATED_AT = 1_704_067_200_000L
}
