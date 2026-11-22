package cn.devcxl.photosync.editor.domain

/**
 * Sanitizes an [EditGraph] at persistence and UI boundaries, rejecting `NaN`/`Infinity`
 * and clamping all 34 atomic parameters into their legal domain ranges before they can
 * reach shader uniforms.
 */
object EditValidator {

    private const val MIN_CROP_SPAN = 0.05f
    private const val MIN_CURVE_DX = 1.0f / 256.0f

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
        val rot = g.rotate90.coerceIn(0, 3)
        val straighten = sanitizeFloat(g.straighten, 0.0f, -45.0f, 45.0f)
        val crop = sanitizeCrop(g.crop)
        val persp = if (g.perspective.size == 8 && g.perspective.all { it.isFinite() }) {
            g.perspective.map { it.coerceIn(-0.5f, 1.5f) }
        } else {
            GeometryOps.DEFAULT_PERSPECTIVE_QUAD
        }
        return g.copy(
            rotate90 = rot,
            straighten = straighten,
            crop = crop,
            perspective = persp
        )
    }

    private fun sanitizeCrop(c: CropRect): CropRect {
        val l = sanitizeFloat(c.left, 0.0f, 0.0f, 1.0f - MIN_CROP_SPAN)
        val t = sanitizeFloat(c.top, 0.0f, 0.0f, 1.0f - MIN_CROP_SPAN)
        val r = sanitizeFloat(c.right, 1.0f, l + MIN_CROP_SPAN, 1.0f)
        val b = sanitizeFloat(c.bottom, 1.0f, t + MIN_CROP_SPAN, 1.0f)
        return CropRect(left = l, top = t, right = r, bottom = b)
    }

    private fun sanitizeTone(t: ToneOps): ToneOps {
        return ToneOps(
            exposure = sanitizeFloat(t.exposure, 0.0f, -5.0f, 5.0f),
            brightness = t.brightness.coerceIn(-100, 100),
            contrast = t.contrast.coerceIn(-100, 100),
            glow = t.glow.coerceIn(-100, 100),
            highlights = t.highlights.coerceIn(-100, 100),
            shadows = t.shadows.coerceIn(-100, 100),
            whites = t.whites.coerceIn(-100, 100),
            blacks = t.blacks.coerceIn(-100, 100)
        )
    }

    private fun sanitizeColor(c: ColorOps): ColorOps {
        return ColorOps(
            temp = c.temp.coerceIn(-100, 100),
            tint = c.tint.coerceIn(-100, 100),
            saturation = c.saturation.coerceIn(-100, 100),
            vibrance = c.vibrance.coerceIn(-100, 100),
            hsl = sanitizeHsl(c.hsl),
            curve = sanitizeCurve(c.curve),
            split = SplitToningOps(
                hiHue = c.split.hiHue.coerceIn(0, 360),
                hiSat = c.split.hiSat.coerceIn(0, 100),
                shHue = c.split.shHue.coerceIn(0, 360),
                shSat = c.split.shSat.coerceIn(0, 100),
                balance = c.split.balance.coerceIn(-100, 100)
            ),
            fade = c.fade.coerceIn(0, 100),
            lut = c.lut?.takeIf { it.id.isNotBlank() }?.let {
                LutRef(id = it.id.trim(), strength = it.strength.coerceIn(0, 100))
            }
        )
    }

    private fun sanitizeHsl(h: HslOps): HslOps {
        fun band(b: HslBandShift) = HslBandShift(
            hue = b.hue.coerceIn(-100, 100),
            saturation = b.saturation.coerceIn(-100, 100),
            luminance = b.luminance.coerceIn(-100, 100)
        )
        return HslOps(
            red = band(h.red),
            orange = band(h.orange),
            yellow = band(h.yellow),
            green = band(h.green),
            aqua = band(h.aqua),
            blue = band(h.blue),
            purple = band(h.purple),
            magenta = band(h.magenta)
        )
    }

    private fun sanitizeCurve(curve: CurveOps): CurveOps {
        return CurveOps(
            rgb = sanitizeCurvePoints(curve.rgb),
            r = sanitizeCurvePoints(curve.r),
            g = sanitizeCurvePoints(curve.g),
            b = sanitizeCurvePoints(curve.b)
        )
    }

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

    private fun sanitizeDetail(d: DetailOps): DetailOps {
        return DetailOps(
            sharpen = SharpenOps(
                amount = d.sharpen.amount.coerceIn(0, 150),
                radius = sanitizeFloat(d.sharpen.radius, 1.0f, 0.5f, 3.0f),
                threshold = d.sharpen.threshold.coerceIn(0, 100),
                masking = d.sharpen.masking.coerceIn(0, 100)
            ),
            clarity = d.clarity.coerceIn(-100, 100),
            texture = d.texture.coerceIn(-100, 100),
            dehaze = d.dehaze.coerceIn(-100, 100),
            nr = NoiseReductionOps(
                luma = d.nr.luma.coerceIn(0, 100),
                chroma = d.nr.chroma.coerceIn(0, 100),
                detail = d.nr.detail.coerceIn(0, 100)
            )
        )
    }

    private fun sanitizeEffects(e: EffectsOps): EffectsOps {
        return EffectsOps(
            grain = GrainOps(
                amount = e.grain.amount.coerceIn(0, 100),
                size = e.grain.size.coerceIn(0, 100),
                roughness = e.grain.roughness.coerceIn(0, 100),
                seed = e.grain.seed.coerceIn(1, 999999)
            ),
            vignette = VignetteOps(
                amount = e.vignette.amount.coerceIn(-100, 100),
                midpoint = e.vignette.midpoint.coerceIn(0, 100),
                feather = e.vignette.feather.coerceIn(0, 100),
                roundness = e.vignette.roundness.coerceIn(-100, 100)
            ),
            ca = ChromaticAberrationOps(
                redCyan = e.ca.redCyan.coerceIn(-100, 100),
                blueYellow = e.ca.blueYellow.coerceIn(-100, 100),
                defringe = e.ca.defringe.coerceIn(0, 100)
            )
        )
    }

    private fun sanitizePortrait(p: PortraitOps): PortraitOps {
        return PortraitOps(
            skin = SkinOps(
                smooth = p.skin.smooth.coerceIn(0, 100),
                texture = p.skin.texture.coerceIn(0, 100),
                evenTone = p.skin.evenTone.coerceIn(0, 100)
            ),
            reshape = ReshapeOps(
                face = p.reshape.face.coerceIn(-100, 100),
                eyes = p.reshape.eyes.coerceIn(-100, 100),
                nose = p.reshape.nose.coerceIn(-100, 100),
                chin = p.reshape.chin.coerceIn(-100, 100),
                forehead = p.reshape.forehead.coerceIn(-100, 100),
                smile = p.reshape.smile.coerceIn(-100, 100)
            ),
            body = BodyOps(
                waist = p.body.waist.coerceIn(-100, 100),
                legs = p.body.legs.coerceIn(-100, 100),
                waistY = p.body.waistY?.takeIf { it.isFinite() }?.coerceIn(0.2f, 0.85f),
                hipY = p.body.hipY?.takeIf { it.isFinite() }?.coerceIn(0.3f, 0.9f)
            )
        )
    }

    private fun sanitizeFloat(value: Float, defaultValue: Float, min: Float, max: Float): Float {
        return if (value.isFinite()) value.coerceIn(min, max) else defaultValue
    }
}
