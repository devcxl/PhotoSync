package cn.devcxl.photosync.editor.domain

import java.util.Locale

/**
 * Pure-Kotlin JSON serializer and parser for [EditGraph] v2.
 *
 * The encoder always writes every group, while the decoder treats missing fields as neutral
 * defaults. A schema v1 document therefore decodes losslessly: its absent AI groups fall back to
 * [SubjectOps], an empty inpaint list, [SkyOps] and no cloud layer.
 *
 * Avoids `android.jar`'s `org.json.JSONObject` stub so serialization round-trips work
 * identically in pure JVM unit tests and on Android devices without extra third-party jars.
 */
object EditGraphJsonCodec {

    fun encode(graph: EditGraph): String {
        val clean = EditValidator.sanitize(graph)
        val sb = StringBuilder(2048)
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
        sb.append(',')
        encodeSubject(sb, clean.subject)
        sb.append(',')
        encodeInpaint(sb, clean.inpaint)
        sb.append(',')
        encodeSky(sb, clean.sky)
        sb.append(',')
        encodeCloudLayer(sb, clean.cloudLayer)
        sb.append('}')
        return sb.toString()
    }

    /**
     * Decodes [json] into a sanitized [EditGraph], or returns `null` if [json] is blank or
     * structurally invalid.
     */
    fun decode(json: String): EditGraph? = EditGraphJsonDecoder.decode(json)

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

    private fun encodeSubject(sb: StringBuilder, s: SubjectOps) {
        sb.append("\"subject\":{")
        sb.append("\"maskRef\":").append(quote(s.maskRef)).append(',')
        sb.append("\"bgMode\":").append(quote(s.bgMode.wireName)).append(',')
        sb.append("\"bgColorArgb\":").append(s.bgColorArgb).append(',')
        sb.append("\"blur\":").append(s.blur).append(',')
        sb.append("\"fgExposure\":").append(s.fgExposure).append(',')
        sb.append("\"fgTemp\":").append(s.fgTemp).append(',')
        sb.append("\"fgSat\":").append(s.fgSat).append(',')
        sb.append("\"bgExposure\":").append(s.bgExposure).append(',')
        sb.append("\"bgTemp\":").append(s.bgTemp).append(',')
        sb.append("\"bgSat\":").append(s.bgSat)
        sb.append('}')
    }

    private fun encodeInpaint(sb: StringBuilder, patches: List<InpaintPatchRef>) {
        sb.append("\"inpaint\":[")
        patches.forEachIndexed { index, patch ->
            if (index > 0) sb.append(',')
            sb.append("{\"bbox\":[")
            sb.append(fmt(patch.bbox.left)).append(',')
            sb.append(fmt(patch.bbox.top)).append(',')
            sb.append(fmt(patch.bbox.right)).append(',')
            sb.append(fmt(patch.bbox.bottom)).append("],")
            sb.append("\"patchRelPath\":").append(quote(patch.patchRelPath)).append(',')
            sb.append("\"maskRelPath\":").append(quote(patch.maskRelPath)).append(',')
            sb.append("\"sha256\":").append(quote(patch.sha256)).append('}')
        }
        sb.append(']')
    }

    private fun encodeSky(sb: StringBuilder, sky: SkyOps) {
        sb.append("\"sky\":{")
        sb.append("\"maskRef\":").append(quote(sky.maskRef)).append(',')
        sb.append("\"assetType\":").append(quote(sky.assetType.wireName)).append(',')
        sb.append("\"assetIdOrRelPath\":").append(quote(sky.assetIdOrRelPath)).append(',')
        sb.append("\"assetSha256\":").append(quote(sky.assetSha256)).append(',')
        sb.append("\"relight\":").append(sky.relight).append(',')
        sb.append("\"horizonOffset\":").append(fmt(sky.horizonOffset)).append(',')
        sb.append("\"horizonFeather\":").append(sky.horizonFeather).append(',')
        sb.append("\"edgeShift\":").append(sky.edgeShift)
        sb.append('}')
    }

    private fun encodeCloudLayer(sb: StringBuilder, layer: CloudEditLayerRef?) {
        sb.append("\"cloudLayer\":")
        if (layer == null) {
            sb.append("null")
            return
        }
        sb.append("{\"imageRelPath\":").append(quote(layer.imageRelPath)).append(',')
        sb.append("\"maskRelPath\":").append(layer.maskRelPath?.let(::quote) ?: "null").append(',')
        sb.append("\"sha256\":").append(quote(layer.sha256)).append(',')
        sb.append("\"opacity\":").append(layer.opacity).append(',')
        sb.append("\"featherPx\":").append(fmt(layer.featherPx)).append(',')
        sb.append("\"geometryDigest\":").append(quote(layer.geometryDigest)).append(',')
        sb.append("\"providerTag\":").append(quote(layer.providerTag)).append('}')
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
}
