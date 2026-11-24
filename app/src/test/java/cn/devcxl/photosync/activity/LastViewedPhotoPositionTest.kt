package cn.devcxl.photosync.activity

import android.app.Application
import android.content.Context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Seam under test: [LastViewedPhotoStore] and [restoreIndexForPhoto] — the "reopen on the photo you
 * left" contract of the gallery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LastViewedPhotoPositionTest {

    @Test
    fun shouldResolveTheSavedIndex_whenThePhotoStillExists() {
        val ids = listOf(11L, 22L, 33L)

        assertEquals(1, restoreIndexForPhoto(22L, ids))
    }

    @Test
    fun shouldReturnNull_whenTheSavedPhotoWasDeleted() {
        assertNull(restoreIndexForPhoto(99L, listOf(11L, 22L)))
    }

    @Test
    fun shouldReturnNull_whenNothingWasSaved() {
        assertNull(restoreIndexForPhoto(null, listOf(11L, 22L)))
    }

    @Test
    fun shouldRoundTripThePhotoId_throughTheStore() {
        val prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("last_viewed_photo_test", Context.MODE_PRIVATE)
        val store = LastViewedPhotoStore(prefs)

        assertNull(store.load())

        store.save(42L)
        assertEquals(42L, store.load())

        store.clear()
        assertNull(store.load())
    }
}
