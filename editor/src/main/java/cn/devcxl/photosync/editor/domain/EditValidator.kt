package cn.devcxl.photosync.editor.domain

/**
 * Sanitizes an [EditGraph] at persistence and UI boundaries, rejecting `NaN`/`Infinity`
 * and clamping all 34 atomic parameters into their legal domain ranges before they can
 * reach shader uniforms.
 */
object EditValidator {

    private const val MIN_CROP_SPAN = 0.05f
    private const val MIN_CURVE_DX = 1.0f / 256.0f
    private const val SHA256_HEX_LENGTH = 64

    fun sanitize(graph: EditGraph): EditGraph {
        return EditGraph(
            schema = graph.schema.coerceAtLeast(1),
            baseLook = graph.baseLook,
            geometry = sanitizeGeometry(graph.geometry),
            tone = sanitizeTone(graph.tone),
            color = sanitizeColor(graph.color),
            detail = sanitizeDetail(graph.detail),
            effects = sanitizeEffects(graph.effects),
            portrait = sanitizePortrait(graph.portrait)
        )
    }

    private fun sanitizeGeometry(g: GeometryOps): GeometryOps {
        val l = sanitizeFloat(g.crop.left, 0.0f, 0.0f, 1.0f - MIN_CROP_SPAN)
        val t = sanitizeFloat(g.crop.top, 0.0f, 0.0f, 1.0f - MIN_CROP_SPAN)
        val r = sanitizeFloat(g.crop.right, 1.0f, l + MIN_CROP_SPAN, 1.0f)
        val b = sanitizeFloat(g.crop.bottom, 1.0f, t + MIN_CROP_SPAN, 1.0f)
        val persp = if (g.perspective.size == 8 && g.perspective.all { it.isFinite() }) {
            g.perspective.map { it.coerceIn(-0.5f, 1.5f) }
        } else {
            GeometryOps.DEFAULT_PERSPECTIVE_QUAD
        }
        return g.copy(
            rotate90 = g.rotate90.coerceIn(0, 3),
            straighten = sanitizeFloat(g.straighten, 0.0f, -45.0f, 45.0f),
            crop = CropRect(left = l, top = t, right = r, bottom = b),
            perspective = persp,
            liquify = sanitizeLiquifyRef(g.liquify)
        )
    }

    /**
     * Keeps a liquify reference only when it can be trusted: a confined relative path, the fixed
     * `128 x 128` grid and either a pending (empty) or a full SHA-256 digest. A hostile or corrupt
     * row therefore degrades to "no liquify" instead of pointing the loader at arbitrary files.
     */
    private fun sanitizeLiquifyRef(ref: LiquifyRef?): LiquifyRef? {
        if (ref == null) return null
        val path = ref.meshRelPath.trim()
        val pathIsConfined = path.isNotEmpty() && !path.startsWith("/") && !path.contains("..")
        val gridIsSupported = ref.gridSize == LiquifyMesh.GRID_SIZE
        val digestIsValid = ref.sha256.isEmpty() || (
            ref.sha256.length == SHA256_HEX_LENGTH &&
                ref.sha256.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            )
        return if (pathIsConfined && gridIsSupported && digestIsValid) {
            LiquifyRef(meshRelPath = path, gridSize = ref.gridSize, sha256 = ref.sha256)
        } else {
            null
        }
    }

    private fun sanitizeTone(t: ToneOps): ToneOps = ToneOps(
        exposure = sanitizeFloat(t.exposure, 0.0f, -5.0f, 5.0f),
        brightness = t.brightness.clampSigned100(),
        contrast = t.contrast.clampSigned100(),
        glow = t.glow.clampSigned100(),
        highlights = t.highlights.clampSigned100(),
        shadows = t.shadows.clampSigned100(),
        whites = t.whites.clampSigned100(),
        blacks = t.blacks.clampSigned100()
    )

    private fun sanitizeColor(c: ColorOps): ColorOps = ColorOps(
        temp = c.temp.clampSigned100(),
        tint = c.tint.clampSigned100(),
        saturation = c.saturation.clampSigned100(),
        vibrance = c.vibrance.clampSigned100(),
        hsl = sanitizeHsl(c.hsl),
        curve = CurveOps(
            rgb = sanitizeCurvePoints(c.curve.rgb),
            r = sanitizeCurvePoints(c.curve.r),
            g = sanitizeCurvePoints(c.curve.g),
            b = sanitizeCurvePoints(c.curve.b)
        ),
        split = SplitToningOps(
            hiHue = c.split.hiHue.coerceIn(0, 360),
            hiSat = c.split.hiSat.clampUnsigned100(),
            shHue = c.split.shHue.coerceIn(0, 360),
            shSat = c.split.shSat.clampUnsigned100(),
            balance = c.split.balance.clampSigned100()
        ),
        fade = c.fade.clampUnsigned100(),
        lut = c.lut?.takeIf { it.id.isNotBlank() }?.let {
            LutRef(id = it.id.trim(), strength = it.strength.clampUnsigned100())
        }
    )

    private fun sanitizeHsl(h: HslOps): HslOps = HslOps(
        red = h.red.sanitized(),
        orange = h.orange.sanitized(),
        yellow = h.yellow.sanitized(),
        green = h.green.sanitized(),
        aqua = h.aqua.sanitized(),
        blue = h.blue.sanitized(),
        purple = h.purple.sanitized(),
        magenta = h.magenta.sanitized()
    )

    private fun sanitizeCurvePoints(points: List<CurvePoint>): List<CurvePoint> {
        val valid = points
            .filter { it.x.isFinite() && it.y.isFinite() }
            .map { CurvePoint(it.x.coerceIn(0.0f, 1.0f), it.y.coerceIn(0.0f, 1.0f)) }
            .sortedBy { it.x }
        if (valid.size < 2) return CurveOps.DEFAULT_LINEAR_CURVE
        val deduplicated = mutableListOf(valid.first())
        for (i in 1 until valid.size) {
            if (valid[i].x - deduplicated.last().x >= MIN_CURVE_DX) {
                deduplicated += valid[i]
            }
            if (deduplicated.size >= 16) break
        }
        return if (deduplicated.size >= 2) deduplicated else CurveOps.DEFAULT_LINEAR_CURVE
    }

    private fun sanitizeDetail(d: DetailOps): DetailOps = DetailOps(
        sharpen = SharpenOps(
            amount = d.sharpen.amount.coerceIn(0, 150),
            radius = sanitizeFloat(d.sharpen.radius, 1.0f, 0.5f, 3.0f),
            threshold = d.sharpen.threshold.clampUnsigned100(),
            masking = d.sharpen.masking.clampUnsigned100()
        ),
        clarity = d.clarity.clampSigned100(),
        texture = d.texture.clampSigned100(),
        dehaze = d.dehaze.clampSigned100(),
        nr = NoiseReductionOps(
            luma = d.nr.luma.clampUnsigned100(),
            chroma = d.nr.chroma.clampUnsigned100(),
            detail = d.nr.detail.clampUnsigned100()
        )
    )

    private fun sanitizeEffects(e: EffectsOps): EffectsOps = EffectsOps(
        grain = GrainOps(
            amount = e.grain.amount.clampUnsigned100(),
            size = e.grain.size.clampUnsigned100(),
            roughness = e.grain.roughness.clampUnsigned100(),
            seed = e.grain.seed.coerceIn(1, 999999)
        ),
        vignette = VignetteOps(
            amount = e.vignette.amount.clampSigned100(),
            midpoint = e.vignette.midpoint.clampUnsigned100(),
            feather = e.vignette.feather.clampUnsigned100(),
            roundness = e.vignette.roundness.clampSigned100()
        ),
        ca = ChromaticAberrationOps(
            redCyan = e.ca.redCyan.clampSigned100(),
            blueYellow = e.ca.blueYellow.clampSigned100(),
            defringe = e.ca.defringe.clampUnsigned100()
        )
    )

    private fun sanitizePortrait(p: PortraitOps): PortraitOps = PortraitOps(
        skin = SkinOps(
            smooth = p.skin.smooth.clampUnsigned100(),
            texture = p.skin.texture.clampUnsigned100(),
            evenTone = p.skin.evenTone.clampUnsigned100()
        ),
        reshape = ReshapeOps(
            face = p.reshape.face.clampSigned100(),
            eyes = p.reshape.eyes.clampSigned100(),
            nose = p.reshape.nose.clampSigned100(),
            chin = p.reshape.chin.clampSigned100(),
            forehead = p.reshape.forehead.clampSigned100(),
            smile = p.reshape.smile.clampSigned100()
        ),
        body = BodyOps(
            waist = p.body.waist.clampSigned100(),
            legs = p.body.legs.clampSigned100(),
            waistY = p.body.waistY?.takeIf { it.isFinite() }?.coerceIn(0.2f, 0.85f),
            hipY = p.body.hipY?.takeIf { it.isFinite() }?.coerceIn(0.3f, 0.9f)
        )
    )

    private fun HslBandShift.sanitized() = HslBandShift(
        hue = hue.clampSigned100(),
        saturation = saturation.clampSigned100(),
        luminance = luminance.clampSigned100()
    )

    private fun Int.clampSigned100(): Int = coerceIn(-100, 100)
    private fun Int.clampUnsigned100(): Int = coerceIn(0, 100)

    private fun sanitizeFloat(value: Float, defaultValue: Float, min: Float, max: Float): Float {
        return if (value.isFinite()) value.coerceIn(min, max) else defaultValue
    }
}
