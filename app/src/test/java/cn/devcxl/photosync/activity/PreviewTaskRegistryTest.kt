package cn.devcxl.photosync.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewTaskRegistryTest {

    private val registry = PreviewTaskRegistry()
    private val published = mutableListOf<String>()

    @Test
    fun shouldRejectSecondBegin_whenDecodeIsAlreadyInFlight() {
        val first = registry.begin(PATH)

        assertNotNull(first)
        assertNull(registry.begin(PATH))

        registry.release(first!!)

        assertNotNull(registry.begin(PATH))
    }

    @Test
    fun shouldRejectPublish_whenPathWasInvalidatedBeforeCompletion() {
        val task = registry.begin(PATH)!!
        registry.invalidate(PATH)

        val accepted = registry.publishIfCurrent(task) { published += "original" }

        assertFalse(accepted)
        assertTrue(published.isEmpty())
    }

    @Test
    fun shouldKeepNewDecodeInFlight_whenStaleDecodeReleasesAfterInvalidation() {
        val stale = registry.begin(PATH)!!
        registry.invalidate(PATH)
        val current = registry.begin(PATH)

        assertNotNull(current)
        registry.release(stale)

        // A third begin only fails while the new decode still owns the path, so this proves the
        // stale release did not clear the new marker.
        assertNull(registry.begin(PATH))
    }

    @Test
    fun shouldNotPublishOriginal_whenEditedPreviewAlreadyLoadedNewerTask() {
        val original = registry.begin(PATH)!!
        registry.invalidate(PATH)
        val edited = registry.begin(PATH)!!
        registry.publishIfCurrent(edited) { published += "edited" }

        // The original decode finishes after the edited one: it must neither overwrite the cache
        // nor reopen the path for a third decode.
        val accepted = registry.publishIfCurrent(original) { published += "original" }

        assertFalse(accepted)
        assertEquals(listOf("edited"), published)
        // Both decodes are settled, so the path is free again; the stale token stays stale.
        assertNotNull(registry.begin(PATH))
    }

    @Test
    fun shouldAllowNewDecode_whenCurrentTaskPublished() {
        val first = registry.begin(PATH)!!
        assertTrue(registry.publishIfCurrent(first) { published += "first" })

        val second = registry.begin(PATH)
        assertNotNull(second)

        assertFalse(registry.publishIfCurrent(first) { published += "stale" })
        assertEquals(listOf("first"), published)
    }

    @Test
    fun shouldReleaseToken_whenDecodeCompletesWithoutPublishing() {
        val failed = registry.begin(PATH)!!

        registry.release(failed)

        assertNotNull(registry.begin(PATH))
    }

    @Test
    fun shouldIsolatePaths_whenBothHaveDecodesInFlight() {
        val first = registry.begin(PATH)!!
        val other = registry.begin(OTHER_PATH)!!

        assertNull(registry.begin(PATH))
        assertNull(registry.begin(OTHER_PATH))

        registry.invalidate(PATH)

        assertFalse(registry.publishIfCurrent(first) { published += "first" })
        assertTrue(registry.publishIfCurrent(other) { published += "other" })
        assertEquals(listOf("other"), published)
    }

    @Test
    fun shouldKeepTokenStale_whenEntryWasPrunedAndSamePathStartsAgain() {
        val stale = registry.begin(PATH)!!
        registry.release(stale)
        val fresh = registry.begin(PATH)

        assertNotNull(fresh)
        assertFalse(registry.publishIfCurrent(stale) { published += "stale" })
        assertTrue(registry.publishIfCurrent(fresh!!) { published += "fresh" })
        assertEquals(listOf("fresh"), published)
    }

    private companion object {
        const val PATH = "/cache/raw/DSC04821.ARW"
        const val OTHER_PATH = "/cache/raw/DSC04822.ARW"
    }
}
