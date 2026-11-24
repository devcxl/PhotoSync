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
        val specs = BuiltinPresets.specs()

        assertEquals(5, specs.size)
        assertEquals(
            listOf(
                "builtin_clean_portrait",
                "builtin_film_chrome",
                "builtin_warm_sunset",
                "builtin_cool_nordic",
                "builtin_mono_silver"
            ),
            specs.map { it.id }
        )
        assertEquals(specs.size, specs.map { it.id }.toSet().size)
    }

    @Test
    fun shouldRoundTripEveryBuiltinThroughTheJsonCodec_whenDecodingItsSubset() {
        for (spec in BuiltinPresets.specs()) {
            val decoded = EditGraphJsonCodec.decode(spec.graphSubsetJson)

            assertNotNull("${spec.id} must decode", decoded)
            val graph = checkNotNull(decoded)
            assertEquals(
                "${spec.id} must re-encode identically",
                spec.graphSubsetJson,
                EditGraphJsonCodec.encode(graph)
            )
        }
    }

    @Test
    fun shouldNeverBeIdentity_whenReadingEveryBuiltin() {
        for (spec in BuiltinPresets.specs()) {
            val graph = checkNotNull(EditGraphJsonCodec.decode(spec.graphSubsetJson))

            assertFalse("${spec.id} must change the picture", graph.isIdentity())
        }
    }

    @Test
    fun shouldCarryNoComposition_whenReadingEveryBuiltin() {
        for (spec in BuiltinPresets.specs()) {
            val graph = checkNotNull(EditGraphJsonCodec.decode(spec.graphSubsetJson))

            assertTrue("${spec.id} crop", graph.geometry.isIdentity())
            assertNull("${spec.id} liquify", graph.geometry.liquify)
            assertTrue("${spec.id} reshape", graph.portrait.reshape.isIdentity())
            assertTrue("${spec.id} body", graph.portrait.body.isIdentity())
        }
    }

    @Test
    fun shouldSurviveSanitizeWithoutChanging_whenApplyingTheValidator() {
        for (spec in BuiltinPresets.specs()) {
            val graph = checkNotNull(EditGraphJsonCodec.decode(spec.graphSubsetJson))

            assertEquals("${spec.id} must already be in range", graph, EditValidator.sanitize(graph))
        }
    }

    @Test
    fun shouldReturnGraphOnlyForKnownIds_whenLookingUpById() {
        assertNotNull(BuiltinPresets.graphFor(BuiltinPresets.MONO_SILVER_ID))
        assertNull(BuiltinPresets.graphFor("user_custom_preset"))
    }

    @Test
    fun shouldKeepTheGraphsDistinct_whenComparingEveryBuiltin() {
        val graphs = BuiltinPresets.specs().map { EditGraphJsonCodec.decode(it.graphSubsetJson) }

        assertEquals(graphs.size, graphs.toSet().size)
    }
}
