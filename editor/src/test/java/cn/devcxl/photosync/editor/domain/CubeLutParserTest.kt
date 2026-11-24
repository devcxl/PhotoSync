package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [CubeLutParser] — the importer contract that turns community `.cube` text into
 * the pre-compiled `.lut16f` cache the renderer uploads, and rejects malformed files safely.
 */
class CubeLutParserTest {

    @Test
    fun shouldParseHeaderAndSamples_whenCubeTextIsWellFormed() {
        val lut = CubeLutParser.parse(identityCubeText(size = 2, title = "Kodak 2383")).getOrNull()

        checkNotNull(lut)
        assertEquals("Kodak 2383", lut.title)
        assertEquals(2, lut.size)
        assertEquals(2 * 2 * 2 * 3, lut.samples.size)
        assertEquals(0.0f, lut.samples[0], 1e-6f)
        assertEquals(1.0f, lut.samples[lut.samples.size - 1], 1e-6f)
    }

    @Test
    fun shouldHonourDeclaredDomain_whenMappingNormalizedColours() {
        val text = identityCubeText(size = 2).replace("DOMAIN_MAX 1.0 1.0 1.0", "DOMAIN_MAX 0.5 0.5 0.5")
        val lut = CubeLutParser.parse(text).getOrNull()

        checkNotNull(lut)
        assertEquals(0.0f, lut.gridCoordinate(0, 0.0f), 1e-6f)
        assertEquals(1.0f, lut.gridCoordinate(0, 0.5f), 1e-6f)
        assertEquals(1.0f, lut.gridCoordinate(0, 1.0f), 1e-6f)
    }

    @Test
    fun shouldWriteFixedLayoutCache_whenEncodingAParsedCube() {
        val lut = checkNotNull(CubeLutParser.parse(identityCubeText(size = 2)).getOrNull())

        val cache = CubeLutParser.encodeCache(lut)

        assertEquals(287_504, cache.size)
        assertEquals(287_496, Lut16fCache.DATA_SIZE_BYTES)
        assertEquals(8, Lut16fCache.HEADER_SIZE_BYTES)
        assertTrue(Lut16fCache.isValid(cache))
        val samples = checkNotNull(Lut16fCache.samplesFromCache(cache))
        assertEquals(Lut16fCache.GRID_SIZE, samples.size)
        val rgb = FloatArray(3)
        samples.rgb(0, rgb)
        assertEquals(0.0f, rgb[0], 1e-4f)
        samples.rgb(Lut16fCache.GRID_SIZE * Lut16fCache.GRID_SIZE * Lut16fCache.GRID_SIZE - 1, rgb)
        assertEquals(1.0f, rgb[0], 1e-4f)
        assertEquals(1.0f, rgb[1], 1e-4f)
        assertEquals(1.0f, rgb[2], 1e-4f)
    }

    @Test
    fun shouldKeepOpaqueAlphaAndIdentityRamp_whenResamplingACube() {
        val lut = checkNotNull(CubeLutParser.parse(identityCubeText(size = 5)).getOrNull())
        val cache = CubeLutParser.encodeCache(lut)
        val samples = checkNotNull(Lut16fCache.samplesFromCache(cache))
        val middle = Lut16fCache.GRID_SIZE / 2
        val index = (middle * Lut16fCache.GRID_SIZE + middle) * Lut16fCache.GRID_SIZE + middle
        val base = index * Lut16fCache.CHANNEL_COUNT

        val expected = middle.toFloat() / (Lut16fCache.GRID_SIZE - 1)
        assertEquals(expected, samples.rgba[base], 1e-3f)
        assertEquals(expected, samples.rgba[base + 1], 1e-3f)
        assertEquals(expected, samples.rgba[base + 2], 1e-3f)
        assertEquals(1.0f, samples.rgba[base + 3], 1e-4f)
    }

    @Test
    fun shouldRejectEveryMalformedCube_whenParsing() {
        val cases = listOf(
            identityCubeText(size = 2).replace("LUT_3D_SIZE 2", "LUT_3D_SIZE 1"),
            identityCubeText(size = 2).replace("LUT_3D_SIZE 2", "LUT_3D_SIZE 66"),
            identityCubeText(size = 2).replace("LUT_3D_SIZE 2", "LUT_3D_SIZE two"),
            identityCubeText(size = 2).replace("DOMAIN_MAX 1.0 1.0 1.0", "DOMAIN_MIN 1.0 1.0 1.0"),
            identityCubeText(size = 2).replace("DOMAIN_MAX 1.0 1.0 1.0", "DOMAIN_MAX 1.0 1.0 NaN"),
            identityCubeText(size = 2).lines().dropLast(2).joinToString("\n"),
            identityCubeText(size = 2) + "0.1 0.2 0.3\n",
            identityCubeText(size = 2).replace("1.0 0.0 0.0", "1.0 Zero 0.0"),
            identityCubeText(size = 2).replace("LUT_3D_SIZE 2", "LUT_1D_SIZE 2"),
            "TITLE \"No size\"\n0.0 0.0 0.0\n"
        )

        for ((index, text) in cases.withIndex()) {
            assertTrue("case $index must be rejected", CubeLutParser.parse(text).isFailure)
        }
    }

    @Test
    fun shouldDenyOversizedText_whenCubeFileExceedsTheImportLimit() {
        val oversized = identityCubeText(size = 2) + "#" + "x".repeat(CubeLutParser.MAX_TEXT_CHARS)

        assertTrue(CubeLutParser.parse(oversized).isFailure)
    }

    @Test
    fun shouldRejectForeignCacheBytes_whenValidatingLut16fLayout() {
        val lut = checkNotNull(CubeLutParser.parse(identityCubeText(2)).getOrNull())
        val corruptedMagic = CubeLutParser.encodeCache(lut).also { it[0] = 'X'.code.toByte() }

        assertFalse(Lut16fCache.isValid(corruptedMagic))
        assertNull(Lut16fCache.samplesFromCache(corruptedMagic))
        assertFalse(Lut16fCache.isValid(ByteArray(Lut16fCache.TOTAL_SIZE_BYTES)))
    }

    private fun identityCubeText(size: Int, title: String = "Identity"): String {
        val steps = (size - 1).toFloat()
        val builder = StringBuilder()
        builder.appendLine("TITLE \"$title\"")
        builder.appendLine("LUT_3D_SIZE $size")
        builder.appendLine("DOMAIN_MIN 0.0 0.0 0.0")
        builder.appendLine("DOMAIN_MAX 1.0 1.0 1.0")
        for (blue in 0 until size) {
            for (green in 0 until size) {
                for (red in 0 until size) {
                    builder.appendLine("${red / steps} ${green / steps} ${blue / steps}")
                }
            }
        }
        return builder.toString()
    }
}
