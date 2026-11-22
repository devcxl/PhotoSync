package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [BuiltinPresets] — the curated library must be pure-parameter, valid JSON and
 * applicable to any frame.
 */
class BuiltinPresetsTest {

    @Test
    fun shouldShipExactlyFiveDeterministicBuiltins_whenReadingTheLibrary() {
        val presets = BuiltinPresets.all()

        assertEquals(5, presets.size)
        assertEquals(
            listOf(
                "builtin_clean_portrait",
                "builtin_film_chrome",
                "builtin_warm_sunset",
                "builtin_cool_nordic",
                "builtin_mono_silver"
            ),
            presets.map { it.id }
        )
        assertTrue(presets.all { it.isBuiltin })
        assertTrue(presets.all { it.lutFilePath == null })
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
    }

    @Test
    fun shouldRoundTripEveryBuiltinThroughTheJsonCodec_whenDecodingItsSubset() {
        for (preset in BuiltinPresets.all()) {
            val decoded = EditGraphJsonCodec.decode(preset.graphSubsetJson)

            assertNotNull("${preset.id} must decode", decoded)
            val graph = checkNotNull(decoded)
            assertEquals(
                "${preset.id} must re-encode identically",
                preset.graphSubsetJson,
                EditGraphJsonCodec.encode(graph)
            )
        }
    }

    @Test
    fun shouldNeverBeIdentity_whenReadingEveryBuiltin() {
        for (preset in BuiltinPresets.all()) {
            val graph = checkNotNull(EditGraphJsonCodec.decode(preset.graphSubsetJson))

            assertFalse("${preset.id} must change the picture", graph.isIdentity())
        }
    }

    @Test
    fun shouldCarryNoComposition_whenReadingEveryBuiltin() {
        for (preset in BuiltinPresets.all()) {
            val graph = checkNotNull(EditGraphJsonCodec.decode(preset.graphSubsetJson))

            assertTrue("${preset.id} crop", graph.geometry.isIdentity())
            assertNull("${preset.id} liquify", graph.geometry.liquify)
            assertTrue("${preset.id} reshape", graph.portrait.reshape.isIdentity())
            assertTrue("${preset.id} body", graph.portrait.body.isIdentity())
        }
    }

    @Test
    fun shouldSurviveSanitizeWithoutChanging_whenApplyingTheValidator() {
        for (preset in BuiltinPresets.all()) {
            val graph = checkNotNull(EditGraphJsonCodec.decode(preset.graphSubsetJson))

            assertEquals("${preset.id} must already be in range", graph, EditValidator.sanitize(graph))
        }
    }

    @Test
    fun shouldReturnGraphOnlyForKnownIds_whenLookingUpById() {
        assertNotNull(BuiltinPresets.graphFor(BuiltinPresets.MONO_SILVER_ID))
        assertNull(BuiltinPresets.graphFor("user_custom_preset"))
    }

    @Test
    fun shouldKeepTheGraphsDistinct_whenComparingEveryBuiltin() {
        val graphs = BuiltinPresets.all().map { EditGraphJsonCodec.decode(it.graphSubsetJson) }

        assertEquals(graphs.size, graphs.toSet().size)
    }
}
