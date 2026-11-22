package cn.devcxl.photosync.editor.domain

import java.util.Locale

/**
 * Pure-Kotlin JSON serializer and parser for [EditGraph] v1.
 *
 * Avoids `android.jar`'s `org.json.JSONObject` stub so serialization round-trips work
 * identically in pure JVM unit tests and on Android devices without extra third-party jars.
 */
object EditGraphJsonCodec {

    fun encode(graph: EditGraph): String {
        val clean = EditValidator.sanitize(graph)
        val sb = StringBuilder(1024)
        sb.append('{')
        sb.append("\"schema\":").append(clean.schema).append(',')
        sb.append("\"baseLook\":").append(quote(clean.baseLook.wireName)).append(',')
        encodeGeometry(sb, clean.geometry)
        sb.append(',')
        encodeTone(sb, clean.tone)
        sb.append(',')
        encodeColor(sb, clean.color)
        sb.append(',')
        encodeDetail(sb, clean.detail)
        sb.append(',')
        encodeEffects(sb, clean.effects)
        sb.append(',')
        encodePortrait(sb, clean.portrait)
        sb.append('}')
        return sb.toString()
    }

    fun decode(json: String): EditGraph {
        if (json.isBlank()) return EditGraph()
        val root = try {
            MiniJsonParser(json).parseValue() as? Map<*, *> ?: return EditGraph()
        } catch (_: Exception) {
            return EditGraph()
        }
        val raw = EditGraph(
            schema = root.int("schema", EditGraph.CURRENT_SCHEMA_VERSION),
            baseLook = BaseLook.fromWireName(root.str("baseLook")),
            geometry = decodeGeometry(root.obj("geometry")),
            tone = decodeTone(root.obj("tone")),
            color = decodeColor(root.obj("color")),
            detail = decodeDetail(root.obj("detail")),
            effects = decodeEffects(root.obj("effects")),
            portrait = decodePortrait(root.obj("portrait"))
        )
        return EditValidator.sanitize(raw)
    }

    private fun encodeGeometry(sb: StringBuilder, g: GeometryOps) {
        sb.append("\"geometry\":{")
        sb.append("\"rotate90\":").append(g.rotate90).append(',')
        sb.append("\"flipH\":").append(g.flipH).append(',')
        sb.append("\"flipV\":").append(g.flipV).append(',')
        sb.append("\"straighten\":").append(fmt(g.straighten)).append(',')
        sb.append("\"crop\":[").append(fmt(g.crop.left)).append(',').append(fmt(g.crop.top))
            .append(',').append(fmt(g.crop.right)).append(',').append(fmt(g.crop.bottom)).append("],")
        sb.append("\"perspective\":[")
        g.perspective.forEachIndexed { idx, v ->
            if (idx > 0) sb.append(',')
            sb.append(fmt(v))
        }
        sb.append("],\"liquify\":")
        val liq = g.liquify
        if (liq == null) {
            sb.append("null")
        } else {
            sb.append("{\"meshRelPath\":").append(quote(liq.meshRelPath))
                .append(",\"gridSize\":").append(liq.gridSize)
                .append(",\"sha256\":").append(quote(liq.sha256)).append('}')
        }
        sb.append('}')
    }

    private fun encodeTone(sb: StringBuilder, t: ToneOps) {
        sb.append("\"tone\":{")
        sb.append("\"exposure\":").append(fmt(t.exposure)).append(',')
        sb.append("\"brightness\":").append(t.brightness).append(',')
        sb.append("\"contrast\":").append(t.contrast).append(',')
        sb.append("\"glow\":").append(t.glow).append(',')
        sb.append("\"highlights\":").append(t.highlights).append(',')
        sb.append("\"shadows\":").append(t.shadows).append(',')
        sb.append("\"whites\":").append(t.whites).append(',')
        sb.append("\"blacks\":").append(t.blacks)
        sb.append('}')
    }

    private fun encodeColor(sb: StringBuilder, c: ColorOps) {
        sb.append("\"color\":{")
        sb.append("\"temp\":").append(c.temp).append(',')
        sb.append("\"tint\":").append(c.tint).append(',')
        sb.append("\"saturation\":").append(c.saturation).append(',')
        sb.append("\"vibrance\":").append(c.vibrance).append(',')
        sb.append("\"hsl\":{")
        encodeBand(sb, "red", c.hsl.red, true)
        encodeBand(sb, "orange", c.hsl.orange, true)
        encodeBand(sb, "yellow", c.hsl.yellow, true)
        encodeBand(sb, "green", c.hsl.green, true)
        encodeBand(sb, "aqua", c.hsl.aqua, true)
        encodeBand(sb, "blue", c.hsl.blue, true)
        encodeBand(sb, "purple", c.hsl.purple, true)
        encodeBand(sb, "magenta", c.hsl.magenta, false)
        sb.append("},\"curve\":{")
        encodeCurveChannel(sb, "rgb", c.curve.rgb, true)
        encodeCurveChannel(sb, "r", c.curve.r, true)
        encodeCurveChannel(sb, "g", c.curve.g, true)
        encodeCurveChannel(sb, "b", c.curve.b, false)
        sb.append("},\"split\":{")
        sb.append("\"hiHue\":").append(c.split.hiHue).append(',')
        sb.append("\"hiSat\":").append(c.split.hiSat).append(',')
        sb.append("\"shHue\":").append(c.split.shHue).append(',')
        sb.append("\"shSat\":").append(c.split.shSat).append(',')
        sb.append("\"balance\":").append(c.split.balance)
        sb.append("},\"fade\":").append(c.fade).append(",\"lut\":")
        if (c.lut == null) {
            sb.append("null")
        } else {
            sb.append("{\"id\":").append(quote(c.lut.id))
                .append(",\"strength\":").append(c.lut.strength).append('}')
        }
        sb.append('}')
    }

    private fun encodeBand(sb: StringBuilder, name: String, b: HslBandShift, trailingComma: Boolean) {
        sb.append(quote(name)).append(":[").append(b.hue).append(',')
            .append(b.saturation).append(',').append(b.luminance).append(']')
        if (trailingComma) sb.append(',')
    }

    private fun encodeCurveChannel(
        sb: StringBuilder,
        name: String,
        pts: List<CurvePoint>,
        trailingComma: Boolean
    ) {
        sb.append(quote(name)).append(":[")
        pts.forEachIndexed { idx, pt ->
            if (idx > 0) sb.append(',')
            sb.append('[').append(fmt(pt.x)).append(',').append(fmt(pt.y)).append(']')
        }
        sb.append(']')
        if (trailingComma) sb.append(',')
    }

    private fun encodeDetail(sb: StringBuilder, d: DetailOps) {
        sb.append("\"detail\":{\"sharpen\":{")
        sb.append("\"amount\":").append(d.sharpen.amount).append(',')
        sb.append("\"radius\":").append(fmt(d.sharpen.radius)).append(',')
        sb.append("\"threshold\":").append(d.sharpen.threshold).append(',')
        sb.append("\"masking\":").append(d.sharpen.masking).append("},")
        sb.append("\"clarity\":").append(d.clarity).append(',')
        sb.append("\"texture\":").append(d.texture).append(',')
        sb.append("\"dehaze\":").append(d.dehaze).append(",\"nr\":{")
        sb.append("\"luma\":").append(d.nr.luma).append(',')
        sb.append("\"chroma\":").append(d.nr.chroma).append(',')
        sb.append("\"detail\":").append(d.nr.detail).append("}}")
    }

    private fun encodeEffects(sb: StringBuilder, e: EffectsOps) {
        sb.append("\"effects\":{\"grain\":{")
        sb.append("\"amount\":").append(e.grain.amount).append(',')
        sb.append("\"size\":").append(e.grain.size).append(',')
        sb.append("\"roughness\":").append(e.grain.roughness).append(',')
        sb.append("\"seed\":").append(e.grain.seed).append("},\"vignette\":{")
        sb.append("\"amount\":").append(e.vignette.amount).append(',')
        sb.append("\"midpoint\":").append(e.vignette.midpoint).append(',')
        sb.append("\"feather\":").append(e.vignette.feather).append(',')
        sb.append("\"roundness\":").append(e.vignette.roundness).append("},\"ca\":{")
        sb.append("\"redCyan\":").append(e.ca.redCyan).append(',')
        sb.append("\"blueYellow\":").append(e.ca.blueYellow).append(',')
        sb.append("\"defringe\":").append(e.ca.defringe).append("}}")
    }

    private fun encodePortrait(sb: StringBuilder, p: PortraitOps) {
        sb.append("\"portrait\":{\"skin\":{")
        sb.append("\"smooth\":").append(p.skin.smooth).append(',')
        sb.append("\"texture\":").append(p.skin.texture).append(',')
        sb.append("\"evenTone\":").append(p.skin.evenTone).append("},\"reshape\":{")
        sb.append("\"face\":").append(p.reshape.face).append(',')
        sb.append("\"eyes\":").append(p.reshape.eyes).append(',')
        sb.append("\"nose\":").append(p.reshape.nose).append(',')
        sb.append("\"chin\":").append(p.reshape.chin).append(',')
        sb.append("\"forehead\":").append(p.reshape.forehead).append(',')
        sb.append("\"smile\":").append(p.reshape.smile).append("},\"body\":{")
        sb.append("\"waist\":").append(p.body.waist).append(',')
        sb.append("\"legs\":").append(p.body.legs).append(',')
        sb.append("\"waistY\":").append(p.body.waistY?.let { fmt(it) } ?: "null").append(',')
        sb.append("\"hipY\":").append(p.body.hipY?.let { fmt(it) } ?: "null").append("}}")
    }

    private fun decodeGeometry(m: Map<*, *>?): GeometryOps {
        if (m == null) return GeometryOps()
        val cropList = m.list("crop")
        val crop = if (cropList != null && cropList.size == 4) {
            CropRect(
                left = cropList.floatAt(0, 0f),
                top = cropList.floatAt(1, 0f),
                right = cropList.floatAt(2, 1f),
                bottom = cropList.floatAt(3, 1f)
            )
        } else {
            CropRect()
        }
        val perspList = m.list("perspective")
        val persp = if (perspList != null && perspList.size == 8) {
            List(8) { idx -> perspList.floatAt(idx, GeometryOps.DEFAULT_PERSPECTIVE_QUAD[idx]) }
        } else {
            GeometryOps.DEFAULT_PERSPECTIVE_QUAD
        }
        val liqObj = m.obj("liquify")
        val liq = liqObj?.str("meshRelPath")?.takeIf { it.isNotBlank() }?.let { path ->
            LiquifyRef(
                meshRelPath = path,
                gridSize = liqObj.int("gridSize", 128),
                sha256 = liqObj.str("sha256") ?: ""
            )
        }
        return GeometryOps(
            rotate90 = m.int("rotate90", 0),
            flipH = m.bool("flipH", false),
            flipV = m.bool("flipV", false),
            straighten = m.float("straighten", 0f),
            crop = crop,
            perspective = persp,
            liquify = liq
        )
    }

    private fun decodeTone(m: Map<*, *>?): ToneOps {
        if (m == null) return ToneOps()
        return ToneOps(
            exposure = m.float("exposure", 0f),
            brightness = m.int("brightness", 0),
            contrast = m.int("contrast", 0),
            glow = m.int("glow", 0),
            highlights = m.int("highlights", 0),
            shadows = m.int("shadows", 0),
            whites = m.int("whites", 0),
            blacks = m.int("blacks", 0)
        )
    }

    private fun decodeColor(m: Map<*, *>?): ColorOps {
        if (m == null) return ColorOps()
        val hslMap = m.obj("hsl")
        val curveMap = m.obj("curve")
        val splitMap = m.obj("split")
        val lutMap = m.obj("lut")
        return ColorOps(
            temp = m.int("temp", 0),
            tint = m.int("tint", 0),
            saturation = m.int("saturation", 0),
            vibrance = m.int("vibrance", 0),
            hsl = HslOps(
                red = decodeBand(hslMap?.list("red")),
                orange = decodeBand(hslMap?.list("orange")),
                yellow = decodeBand(hslMap?.list("yellow")),
                green = decodeBand(hslMap?.list("green")),
                aqua = decodeBand(hslMap?.list("aqua")),
                blue = decodeBand(hslMap?.list("blue")),
                purple = decodeBand(hslMap?.list("purple")),
                magenta = decodeBand(hslMap?.list("magenta"))
            ),
            curve = CurveOps(
                rgb = decodeCurvePoints(curveMap?.list("rgb")),
                r = decodeCurvePoints(curveMap?.list("r")),
                g = decodeCurvePoints(curveMap?.list("g")),
                b = decodeCurvePoints(curveMap?.list("b"))
            ),
            split = SplitToningOps(
                hiHue = splitMap.int("hiHue", 45),
                hiSat = splitMap.int("hiSat", 0),
                shHue = splitMap.int("shHue", 210),
                shSat = splitMap.int("shSat", 0),
                balance = splitMap.int("balance", 0)
            ),
            fade = m.int("fade", 0),
            lut = lutMap?.str("id")?.takeIf { it.isNotBlank() }?.let { id ->
                LutRef(id = id, strength = lutMap.int("strength", 100))
            }
        )
    }

    private fun decodeBand(list: List<*>?): HslBandShift {
        if (list == null || list.size < 3) return HslBandShift()
        return HslBandShift(
            hue = (list[0] as? Number)?.toInt() ?: 0,
            saturation = (list[1] as? Number)?.toInt() ?: 0,
            luminance = (list[2] as? Number)?.toInt() ?: 0
        )
    }

    private fun decodeCurvePoints(list: List<*>?): List<CurvePoint> {
        if (list == null) return CurveOps.DEFAULT_LINEAR_CURVE
        val pts = list.mapNotNull { item ->
            val pair = item as? List<*>
            if (pair != null && pair.size >= 2) {
                val x = (pair[0] as? Number)?.toFloat()
                val y = (pair[1] as? Number)?.toFloat()
                if (x != null && y != null) CurvePoint(x, y) else null
            } else {
                null
            }
        }
        return pts.ifEmpty { CurveOps.DEFAULT_LINEAR_CURVE }
    }

    private fun decodeDetail(m: Map<*, *>?): DetailOps {
        if (m == null) return DetailOps()
        val sh = m.obj("sharpen")
        val nr = m.obj("nr")
        return DetailOps(
            sharpen = SharpenOps(
                amount = sh.int("amount", 0),
                radius = sh.float("radius", 1.0f),
                threshold = sh.int("threshold", 0),
                masking = sh.int("masking", 0)
            ),
            clarity = m.int("clarity", 0),
            texture = m.int("texture", 0),
            dehaze = m.int("dehaze", 0),
            nr = NoiseReductionOps(
                luma = nr.int("luma", 0),
                chroma = nr.int("chroma", 0),
                detail = nr.int("detail", 50)
            )
        )
    }

    private fun decodeEffects(m: Map<*, *>?): EffectsOps {
        if (m == null) return EffectsOps()
        val gr = m.obj("grain")
        val vig = m.obj("vignette")
        val ca = m.obj("ca")
        return EffectsOps(
            grain = GrainOps(
                amount = gr.int("amount", 0),
                size = gr.int("size", 25),
                roughness = gr.int("roughness", 50),
                seed = gr.int("seed", 104729)
            ),
            vignette = VignetteOps(
                amount = vig.int("amount", 0),
                midpoint = vig.int("midpoint", 50),
                feather = vig.int("feather", 50),
                roundness = vig.int("roundness", 0)
            ),
            ca = ChromaticAberrationOps(
                redCyan = ca.int("redCyan", 0),
                blueYellow = ca.int("blueYellow", 0),
                defringe = ca.int("defringe", 0)
            )
        )
    }

    private fun decodePortrait(m: Map<*, *>?): PortraitOps {
        if (m == null) return PortraitOps()
        val sk = m.obj("skin")
        val rs = m.obj("reshape")
        val bd = m.obj("body")
        return PortraitOps(
            skin = SkinOps(
                smooth = sk.int("smooth", 0),
                texture = sk.int("texture", 50),
                evenTone = sk.int("evenTone", 0)
            ),
            reshape = ReshapeOps(
                face = rs.int("face", 0),
                eyes = rs.int("eyes", 0),
                nose = rs.int("nose", 0),
                chin = rs.int("chin", 0),
                forehead = rs.int("forehead", 0),
                smile = rs.int("smile", 0)
            ),
            body = BodyOps(
                waist = bd.int("waist", 0),
                legs = bd.int("legs", 0),
                waistY = bd?.get("waistY")?.let { (it as? Number)?.toFloat() },
                hipY = bd?.get("hipY")?.let { (it as? Number)?.toFloat() }
            )
        )
    }

    private fun fmt(value: Float): String =
        String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun Map<*, *>?.obj(key: String): Map<*, *>? = this?.get(key) as? Map<*, *>
    private fun Map<*, *>?.list(key: String): List<*>? = this?.get(key) as? List<*>
    private fun Map<*, *>?.str(key: String): String? = this?.get(key) as? String
    private fun Map<*, *>?.int(key: String, default: Int): Int =
        (this?.get(key) as? Number)?.toInt() ?: default
    private fun Map<*, *>?.float(key: String, default: Float): Float =
        (this?.get(key) as? Number)?.toFloat() ?: default
    private fun Map<*, *>?.bool(key: String, default: Boolean): Boolean =
        (this?.get(key) as? Boolean) ?: default
    private fun List<*>.floatAt(index: Int, default: Float): Float =
        (getOrNull(index) as? Number)?.toFloat() ?: default
}
