package cn.devcxl.photosync.editor.domain

/**
 * Decodes serialized JSON maps into sanitized [EditGraph] instances.
 */
internal object EditGraphJsonDecoder {

    fun decode(json: String): EditGraph? {
        if (json.isBlank()) return null
        val root = try {
            MiniJsonParser(json).parseValue() as? Map<*, *> ?: return null
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: IndexOutOfBoundsException) {
            return null
        }
        val raw = EditGraph(
            schema = root.int("schema", EditGraph.CURRENT_SCHEMA_VERSION),
            baseLook = BaseLook.fromWireName(root.str("baseLook")),
            geometry = decodeGeometry(root.obj("geometry")),
            tone = decodeTone(root.obj("tone")),
            color = decodeColor(root.obj("color")),
            detail = decodeDetail(root.obj("detail")),
            effects = decodeEffects(root.obj("effects")),
            portrait = decodePortrait(root.obj("portrait")),
            subject = decodeSubject(root.obj("subject")),
            inpaint = decodeInpaint(root.list("inpaint")),
            sky = decodeSky(root.obj("sky")),
            cloudLayer = decodeCloudLayer(root.obj("cloudLayer"))
        )
        return EditValidator.sanitize(raw)
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

    private fun decodeSubject(m: Map<*, *>?): SubjectOps {
        if (m == null) return SubjectOps()
        return SubjectOps(
            maskRef = m.str("maskRef") ?: "",
            bgMode = SubjectBackgroundMode.fromWireName(m.str("bgMode")),
            bgColorArgb = m.int("bgColorArgb", SubjectOps.DEFAULT_BG_COLOR_ARGB),
            blur = m.int("blur", 0),
            fgExposure = m.int("fgExposure", 0),
            fgTemp = m.int("fgTemp", 0),
            fgSat = m.int("fgSat", 0),
            bgExposure = m.int("bgExposure", 0),
            bgTemp = m.int("bgTemp", 0),
            bgSat = m.int("bgSat", 0)
        )
    }

    /**
     * A patch without a complete normalized bbox is dropped rather than defaulted to the full
     * frame: applying a local repair at the wrong position is worse than losing a corrupt entry.
     */
    private fun decodeInpaint(list: List<*>?): List<InpaintPatchRef> {
        if (list == null) return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val patchPath = m.str("patchRelPath") ?: return@mapNotNull null
            val maskPath = m.str("maskRelPath") ?: return@mapNotNull null
            val bbox = m.list("bbox") ?: return@mapNotNull null
            if (bbox.size != 4) return@mapNotNull null
            InpaintPatchRef(
                bbox = InpaintBBox(
                    left = bbox.floatAt(0, 0.0f),
                    top = bbox.floatAt(1, 0.0f),
                    right = bbox.floatAt(2, 1.0f),
                    bottom = bbox.floatAt(3, 1.0f)
                ),
                patchRelPath = patchPath,
                maskRelPath = maskPath,
                sha256 = m.str("sha256") ?: ""
            )
        }
    }

    private fun decodeSky(m: Map<*, *>?): SkyOps {
        if (m == null) return SkyOps()
        return SkyOps(
            maskRef = m.str("maskRef") ?: "",
            assetType = SkyAssetType.fromWireName(m.str("assetType")),
            assetIdOrRelPath = m.str("assetIdOrRelPath") ?: "",
            assetSha256 = m.str("assetSha256") ?: "",
            relight = m.int("relight", 0),
            horizonOffset = m.float("horizonOffset", 0.0f),
            horizonFeather = m.int("horizonFeather", 0),
            edgeShift = m.int("edgeShift", 0)
        )
    }

    private fun decodeCloudLayer(m: Map<*, *>?): CloudEditLayerRef? {
        if (m == null) return null
        val imagePath = m.str("imageRelPath") ?: return null
        return CloudEditLayerRef(
            imageRelPath = imagePath,
            maskRelPath = m.str("maskRelPath"),
            sha256 = m.str("sha256") ?: "",
            opacity = m.int("opacity", 100),
            featherPx = m.float("featherPx", 0.0f),
            geometryDigest = m.str("geometryDigest") ?: "",
            providerTag = m.str("providerTag") ?: ""
        )
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
