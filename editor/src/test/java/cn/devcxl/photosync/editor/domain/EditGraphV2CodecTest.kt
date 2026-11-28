package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [EditGraphJsonCodec] and [EditValidator] — the schema v2 document contract.
 *
 * Covers the three promises the renderer relies on: a v1 document still decodes losslessly, a v2
 * document round-trips, and hostile numbers never survive sanitisation or reach shader uniforms.
 */
class EditGraphV2CodecTest {

    private val digest = "a".repeat(64)

    /** A complete document written by the schema v1 encoder: no AI keys at all. */
    private val legacyV1Json = """
        {
          "schema": 1,
          "baseLook": "linear_neutral",
          "geometry": {
            "rotate90": 2,
            "flipH": true,
            "flipV": false,
            "straighten": -3.5,
            "crop": [0.1, 0.15, 0.9, 0.85],
            "perspective": [0.1, 0, 0.9, 0, 0.9, 0.9, 0, 0.9],
            "liquify": {"meshRelPath": "edits/7/liquify_128x128.bin", "gridSize": 128, "sha256": ""}
          },
          "tone": {
            "exposure": 1.25,
            "brightness": 10,
            "contrast": -5,
            "glow": 4,
            "highlights": -20,
            "shadows": 30,
            "whites": 3,
            "blacks": -2
          },
          "color": {
            "temp": 12,
            "tint": -3,
            "saturation": 5,
            "vibrance": 7,
            "hsl": {
              "red": [1, 2, 3],
              "orange": [0, 0, 0],
              "yellow": [0, 0, 0],
              "green": [0, 0, 0],
              "aqua": [0, 0, 0],
              "blue": [0, 0, 0],
              "purple": [0, 0, 0],
              "magenta": [0, 0, 0]
            },
            "curve": {
              "rgb": [[0, 0], [1, 1]],
              "r": [[0, 0], [1, 1]],
              "g": [[0, 0], [1, 1]],
              "b": [[0, 0], [1, 1]]
            },
            "split": {"hiHue": 30, "hiSat": 10, "shHue": 200, "shSat": 8, "balance": 5},
            "fade": 8,
            "lut": {"id": "kodak_2383", "strength": 50}
          },
          "detail": {
            "sharpen": {"amount": 40, "radius": 1.4, "threshold": 10, "masking": 30},
            "clarity": 11,
            "texture": -7,
            "dehaze": 13,
            "nr": {"luma": 25, "chroma": 15, "detail": 70}
          },
          "effects": {
            "grain": {"amount": 20, "size": 40, "roughness": 55, "seed": 1234},
            "vignette": {"amount": -18, "midpoint": 44, "feather": 60, "roundness": 7},
            "ca": {"redCyan": 3, "blueYellow": -2, "defringe": 12}
          },
          "portrait": {
            "skin": {"smooth": 35, "texture": 42, "evenTone": 18},
            "reshape": {"face": 22, "eyes": 10, "nose": -5, "chin": 8, "forehead": -3, "smile": 15},
            "body": {"waist": 18, "legs": 12, "waistY": 0.45, "hipY": 0.7}
          }
        }
    """.trimIndent()

    @Test
    fun shouldDecodeLegacyV1DocumentLosslesslyAndDefaultAiGroups_whenReadingSchemaOneJson() {
        val decoded = checkNotNull(EditGraphJsonCodec.decode(legacyV1Json))

        assertEquals(1, decoded.schema)
        assertEquals(BaseLook.LINEAR_NEUTRAL, decoded.baseLook)
        assertEquals(2, decoded.geometry.rotate90)
        assertTrue(decoded.geometry.flipH)
        assertEquals(-3.5f, decoded.geometry.straighten, 1e-4f)
        assertEquals(0.1f, decoded.geometry.crop.left, 1e-4f)
        assertEquals("edits/7/liquify_128x128.bin", decoded.geometry.liquify?.meshRelPath)
        assertEquals(1.25f, decoded.tone.exposure, 1e-4f)
        assertEquals(1, decoded.color.hsl.red.hue)
        assertEquals("kodak_2383", decoded.color.lut?.id)
        assertEquals(70, decoded.detail.nr.detail)
        assertEquals(1234, decoded.effects.grain.seed)
        assertEquals(22, decoded.portrait.reshape.face)
        assertEquals(0.45f, decoded.portrait.body.waistY ?: 0.0f, 1e-4f)
        assertEquals(SubjectOps(), decoded.subject)
        assertTrue(decoded.inpaint.isEmpty())
        assertEquals(SkyOps(), decoded.sky)
        assertNull(decoded.cloudLayer)
        assertFalse(decoded.isIdentity())
    }

    @Test
    fun shouldDecodeMinimalV1Json_whenOnlySchemaAndOneGroupArePresent() {
        val decoded = checkNotNull(EditGraphJsonCodec.decode("{\"schema\":1,\"tone\":{\"exposure\":1.5}}"))

        assertEquals(1, decoded.schema)
        assertEquals(1.5f, decoded.tone.exposure, 1e-4f)
        assertTrue(decoded.inpaint.isEmpty())
        assertNull(decoded.cloudLayer)
        assertEquals(SubjectBackgroundMode.ORIGINAL, decoded.subject.bgMode)
        assertEquals(SkyAssetType.NONE, decoded.sky.assetType)
    }

    @Test
    fun shouldTreatAnEmptyV1DocumentAsIdentity_whenNoGroupIsModified() {
        val decoded = checkNotNull(EditGraphJsonCodec.decode("{\"schema\":1}"))

        assertTrue(decoded.isIdentity())
        assertFalse(decoded.requiresPassAGeometry())
    }

    @Test
    fun shouldRoundTripEveryV2AiNode_whenEncodingAndDecoding() {
        val original = EditGraph(
            tone = ToneOps(exposure = 0.4f, contrast = 12),
            subject = SubjectOps(
                maskRef = "edits/7/subject_mask_${digest}.png",
                bgMode = SubjectBackgroundMode.SOLID,
                bgColorArgb = 0x80112233.toInt(),
                blur = 35,
                fgExposure = 10,
                fgTemp = -6,
                fgSat = 8,
                bgExposure = -12,
                bgTemp = 4,
                bgSat = -3
            ),
            inpaint = listOf(
                InpaintPatchRef(
                    bbox = InpaintBBox(0.1f, 0.2f, 0.35f, 0.45f),
                    patchRelPath = "edits/7/inpaint_patch_${digest}.png",
                    maskRelPath = "edits/7/inpaint_mask_${digest}.png",
                    sha256 = digest
                )
            ),
            sky = SkyOps(
                maskRef = "edits/7/sky_mask_${digest}.png",
                assetType = SkyAssetType.CUSTOM,
                assetIdOrRelPath = "edits/7/sky_asset_${digest}.png",
                assetSha256 = digest,
                relight = 40,
                horizonOffset = -0.15f,
                horizonFeather = 25,
                edgeShift = -1
            ),
            cloudLayer = CloudEditLayerRef(
                imageRelPath = "edits/7/cloud_layer_${digest}.png",
                maskRelPath = "edits/7/cloud_mask_${digest}.png",
                sha256 = digest,
                opacity = 80,
                featherPx = 12.5f,
                geometryDigest = "geo_0123456789abcdef",
                providerTag = "openai-compatible"
            )
        )

        val json = EditGraphJsonCodec.encode(original)
        val decoded = checkNotNull(EditGraphJsonCodec.decode(json))

        assertEquals(EditGraph.CURRENT_SCHEMA_VERSION, original.schema)
        assertEquals(original, decoded)
        assertFalse(decoded.isIdentity())
        assertFalse(decoded.requiresPassAGeometry())
    }

    @Test
    fun shouldUpgradeSchemaToOneOnlyWhileAiGroupsStayNeutral_whenSanitizing() {
        val v1Graph = EditGraph(schema = 1, tone = ToneOps(exposure = 0.5f))
        assertEquals(1, EditValidator.sanitize(v1Graph).schema)

        val withAi = EditGraph(schema = 1, subject = SubjectOps(blur = 20))
        assertEquals(EditGraph.CURRENT_SCHEMA_VERSION, EditValidator.sanitize(withAi).schema)
        assertEquals(EditGraph.CURRENT_SCHEMA_VERSION, EditGraph().schema)
    }

    @Test
    fun shouldClampAndDropHostileAiValues_whenSanitizing() {
        val dirty = EditGraph(
            schema = 1,
            subject = SubjectOps(
                maskRef = "/absolute/mask.png",
                bgMode = SubjectBackgroundMode.TRANSPARENT,
                blur = 250,
                fgExposure = -250,
                fgTemp = 140,
                bgSat = -180
            ),
            inpaint = listOf(
                InpaintPatchRef(
                    bbox = InpaintBBox(Float.NaN, 0.0f, 0.4f, 0.5f),
                    patchRelPath = "edits/7/nan.png",
                    maskRelPath = "edits/7/nan_mask.png"
                ),
                InpaintPatchRef(
                    bbox = InpaintBBox(0.1f, 0.2f, 0.3f, 0.4f),
                    patchRelPath = "edits/7/valid.png",
                    maskRelPath = "edits/7/valid_mask.png",
                    sha256 = "not-a-digest"
                ),
                InpaintPatchRef(
                    bbox = InpaintBBox(0.1f, 0.2f, 0.3f, 0.4f),
                    patchRelPath = "/escape.png",
                    maskRelPath = "edits/7/mask.png"
                )
            ),
            sky = SkyOps(
                assetType = SkyAssetType.CUSTOM,
                assetIdOrRelPath = "../escape.png",
                assetSha256 = "xyz",
                relight = 250,
                horizonOffset = Float.POSITIVE_INFINITY,
                horizonFeather = 150,
                edgeShift = -9
            ),
            cloudLayer = CloudEditLayerRef(
                imageRelPath = "/absolute/layer.png",
                maskRelPath = "../mask.png",
                opacity = 500,
                featherPx = Float.NaN
            )
        )

        val clean = EditValidator.sanitize(dirty)

        assertEquals(EditGraph.CURRENT_SCHEMA_VERSION, clean.schema)
        assertEquals("", clean.subject.maskRef)
        assertEquals(SubjectBackgroundMode.TRANSPARENT, clean.subject.bgMode)
        assertEquals(100, clean.subject.blur)
        assertEquals(-100, clean.subject.fgExposure)
        assertEquals(100, clean.subject.fgTemp)
        assertEquals(-100, clean.subject.bgSat)
        assertEquals(1, clean.inpaint.size)
        val patch = clean.inpaint.single()
        assertEquals("edits/7/valid.png", patch.patchRelPath)
        assertEquals("", patch.sha256)
        assertEquals("", clean.sky.assetIdOrRelPath)
        assertEquals("", clean.sky.assetSha256)
        assertEquals(100, clean.sky.relight)
        assertEquals(0.0f, clean.sky.horizonOffset, 1e-4f)
        assertEquals(100, clean.sky.horizonFeather)
        assertEquals(-2, clean.sky.edgeShift)
        assertNull(clean.cloudLayer)

        val json = EditGraphJsonCodec.encode(dirty)
        assertFalse(json.contains("NaN"))
        assertFalse(json.contains("Infinity"))
    }

    @Test
    fun shouldClampNonFiniteNumbers_whenJsonLiteralsOverflowToInfinity() {
        val json = """
            {"schema":1,"sky":{"assetType":"procedural","relight":-5,"horizonOffset":1e999}}
        """.trimIndent()

        val decoded = checkNotNull(EditGraphJsonCodec.decode(json))

        assertEquals(0, decoded.sky.relight)
        assertEquals(0.0f, decoded.sky.horizonOffset, 1e-4f)
        assertEquals(SkyAssetType.PROCEDURAL, decoded.sky.assetType)
        assertFalse(decoded.isIdentity())
    }

    @Test
    fun shouldClampCloudLayerOpacityAndFeather_whenDecodingHostileJson() {
        val json = """
            {
              "schema": 2,
              "cloudLayer": {
                "imageRelPath": "edits/7/layer.png",
                "opacity": 140,
                "featherPx": -3.5
              }
            }
        """.trimIndent()

        val decoded = checkNotNull(EditGraphJsonCodec.decode(json))

        assertNotNull(decoded.cloudLayer)
        assertEquals(100, decoded.cloudLayer?.opacity)
        assertEquals(0.0f, decoded.cloudLayer?.featherPx ?: -1.0f, 1e-4f)
        assertNull(decoded.cloudLayer?.maskRelPath)
    }

    @Test
    fun shouldReportIdentityOnlyWhileEveryAiGroupIsNeutral_whenCheckingIsIdentity() {
        assertTrue(EditGraph().isIdentity())
        assertTrue(
            "a mask without an effect renders nothing",
            EditGraph(subject = SubjectOps(maskRef = "edits/7/subject_mask_x.png")).isIdentity()
        )
        assertFalse(EditGraph(subject = SubjectOps(bgMode = SubjectBackgroundMode.TRANSPARENT)).isIdentity())
        assertFalse(EditGraph(subject = SubjectOps(blur = 1)).isIdentity())
        assertFalse(EditGraph(subject = SubjectOps(bgExposure = -1)).isIdentity())
        assertFalse(
            EditGraph(
                inpaint = listOf(
                    InpaintPatchRef(
                        bbox = InpaintBBox(0.1f, 0.1f, 0.2f, 0.2f),
                        patchRelPath = "edits/7/p.png",
                        maskRelPath = "edits/7/m.png"
                    )
                )
            ).isIdentity()
        )
        assertFalse(EditGraph(sky = SkyOps(assetType = SkyAssetType.PROCEDURAL)).isIdentity())
        assertFalse(EditGraph(sky = SkyOps(relight = 1)).isIdentity())
        assertFalse(
            EditGraph(cloudLayer = CloudEditLayerRef(imageRelPath = "edits/7/layer.png")).isIdentity()
        )
    }
}
