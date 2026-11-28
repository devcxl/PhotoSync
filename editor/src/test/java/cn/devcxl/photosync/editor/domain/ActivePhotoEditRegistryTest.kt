package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivePhotoEditRegistryTest {

    @Test
    fun shouldKeepPhotoActive_whenOneOfTwoHoldersReleasesIt() {
        val photoId = 9_101L
        ActivePhotoEditRegistry.acquire(photoId)
        ActivePhotoEditRegistry.acquire(photoId)
        assertTrue(photoId in ActivePhotoEditRegistry.snapshot())

        ActivePhotoEditRegistry.release(photoId)
        assertTrue(photoId in ActivePhotoEditRegistry.snapshot())

        ActivePhotoEditRegistry.release(photoId)
        assertFalse(photoId in ActivePhotoEditRegistry.snapshot())
    }

    @Test
    fun shouldIgnoreNonPositiveIdsAndUnbalancedReleases() {
        ActivePhotoEditRegistry.acquire(0L)
        ActivePhotoEditRegistry.acquire(-3L)
        ActivePhotoEditRegistry.release(0L)
        ActivePhotoEditRegistry.release(7_777L)

        assertFalse(0L in ActivePhotoEditRegistry.snapshot())
        assertFalse(-3L in ActivePhotoEditRegistry.snapshot())
        assertFalse(7_777L in ActivePhotoEditRegistry.snapshot())
    }

    @Test
    fun shouldReturnACopy_whenTakingASnapshot() {
        val photoId = 9_102L
        ActivePhotoEditRegistry.acquire(photoId)
        val snapshot = ActivePhotoEditRegistry.snapshot()
        ActivePhotoEditRegistry.release(photoId)

        assertTrue(photoId in snapshot)
        assertFalse(photoId in ActivePhotoEditRegistry.snapshot())
    }
}
