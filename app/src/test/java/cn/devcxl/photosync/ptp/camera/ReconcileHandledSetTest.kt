package cn.devcxl.photosync.ptp.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The handled set is what stops a working session from downloading the card again, so its
 * two awkward cases are pinned here.
 */
class ReconcileHandledSetTest {

    @Test
    fun shouldKeepHandlesFromAnUnreadStorage_whenTheEnumerationIsPartial() {
        // Storage A answered with {1, 2}; storage B failed, so handle 9 — which lives on B —
        // is unknown rather than gone, and must survive the pass.
        val next = reconcileHandledSet(
            handled = listOf(1, 2, 9),
            onCamera = listOf(1, 2),
            imported = emptyList(),
            complete = false,
        )

        assertEquals(setOf(1, 2, 9), next.toSet())
    }

    @Test
    fun shouldAddImportedHandles_evenWhenTheEnumerationIsPartial() {
        val next = reconcileHandledSet(
            handled = listOf(1),
            onCamera = listOf(1, 2),
            imported = listOf(2),
            complete = false,
        )

        assertEquals(setOf(1, 2), next.toSet())
    }

    @Test
    fun shouldNotDuplicateAHandle_thePassImportedTwice() {
        val next = reconcileHandledSet(
            handled = listOf(1, 2),
            onCamera = listOf(1, 2),
            imported = listOf(2),
            complete = false,
        )

        assertEquals(listOf(1, 2), next)
    }

    @Test
    fun shouldPruneHandlesOnly_whenEveryStorageAnswered() {
        // Handle 9 really is gone: the camera answered for all of its storages.
        val next = reconcileHandledSet(
            handled = listOf(1, 2, 9),
            onCamera = listOf(1, 2),
            imported = emptyList(),
            complete = true,
        )

        assertEquals(listOf(1, 2), next)
    }

    @Test
    fun shouldKeepCameraOrder_whenTheEnumerationIsComplete() {
        val next = reconcileHandledSet(
            handled = listOf(2, 1),
            onCamera = listOf(3, 1, 2),
            imported = listOf(3),
            complete = true,
        )

        assertEquals(listOf(3, 1, 2), next)
    }
}
