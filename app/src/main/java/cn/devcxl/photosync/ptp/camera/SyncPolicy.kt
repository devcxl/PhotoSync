package cn.devcxl.photosync.ptp.camera

/**
 * How a session notices objects the camera added.
 */
internal enum class TriggerChannel {
    /** Poll a vendor event channel; the camera reports a photo as soon as it is shot. */
    EVENT,

    /** Enumerate the object list on a fixed interval. */
    POLL,
}

/**
 * Picks the trigger channel from the operations the camera advertises.
 *
 * No brand check: a camera that advertises the vendor's event operation gets the event
 * loop, whatever its model, and one that does not gets the poll loop. A firmware update
 * that removes the operation therefore changes the channel without a code change.
 */
internal fun chooseTriggerChannel(
    eventOperation: Int?,
    advertisedOperations: Set<Int>,
): TriggerChannel = when {
    eventOperation != null && eventOperation in advertisedOperations -> TriggerChannel.EVENT
    else -> TriggerChannel.POLL
}

/** Fast poll while the camera is reporting activity. */
internal const val EVENT_POLL_FAST_MS = 200L

/** Slowest idle poll; polling faster than this forever drains a session for nothing. */
internal const val EVENT_POLL_IDLE_MS = 3_000L

/** Interval between two object-list ticks. */
internal const val POLL_INTERVAL_MS = 2_000L

/**
 * Consecutive event-channel failures after which the session switches to object polling.
 *
 * One USB hiccup is normal on a busy camera; three in a row means the channel is not
 * coming back, and continuing to retry it would leave the session without a trigger.
 */
internal const val EVENT_FAILURE_DEGRADE_THRESHOLD = 3

/**
 * How long to wait before the next event poll.
 *
 * A quiet camera is polled at a growing interval; any event snaps it back to the fast one,
 * because a burst of activity — and every shutter press is one — is followed at full speed.
 */
internal fun nextEventPollDelay(currentMs: Long, eventsLastPoll: Int): Long {
    if (eventsLastPoll > 0) {
        return EVENT_POLL_FAST_MS
    }
    return (currentMs * 2).coerceAtMost(EVENT_POLL_IDLE_MS)
}

/** Whether a run of event-channel failures should move the session onto object polling. */
internal fun shouldFallBackToPolling(consecutiveFailures: Int): Boolean =
    consecutiveFailures >= EVENT_FAILURE_DEGRADE_THRESHOLD

/**
 * Handles that appeared on the camera since the last enumeration.
 *
 * @param handled handles this session already imported
 * @param onCamera handles the camera reports right now, in camera order
 */
internal fun newHandles(handled: Collection<Int>, onCamera: List<Int>): List<Int> {
    val known = handled.toHashSet()
    return onCamera.filter { it !in known }
}

/**
 * The handled set after one enumeration pass, in camera order.
 *
 * Starts from what was already handled plus what this pass imported, then keeps only the
 * handles the camera still reports. Dropping vanished handles matters twice: a photo
 * deleted on the camera must not stay "imported", and cameras reuse handles — a stale
 * handle would make the next photo on it look already synced and never download.
 *
 * A handle whose import failed is not in [imported], so it falls out of the result and is
 * retried on the next pass.
 */
internal fun nextHandledSet(
    handled: Collection<Int>,
    onCamera: List<Int>,
    imported: Collection<Int>,
): List<Int> {
    val known = handled.toHashSet()
    known.addAll(imported)
    return onCamera.filter { it in known }
}

/**
 * The handled set after an enumeration pass, which may have been partial.
 *
 * A partial pass — one storage answered, another did not — must only ever add. Pruning
 * against it would drop the handles that live on the unread storage, and the next complete
 * pass would then see them as new and download the whole card again.
 *
 * @param handled handles recorded before this pass
 * @param onCamera handles the pass read back
 * @param imported handles this pass imported successfully
 * @param complete whether every storage answered
 */
internal fun reconcileHandledSet(
    handled: Collection<Int>,
    onCamera: List<Int>,
    imported: Collection<Int>,
    complete: Boolean,
): List<Int> {
    if (!complete) {
        return if (imported.isEmpty()) {
            handled.toList()
        } else {
            handled.toList() + imported.filter { it !in handled }
        }
    }
    return nextHandledSet(handled, onCamera, imported)
}

/**
 * Retry bookkeeping for event-mode handles whose import failed transiently.
 *
 * Cameras announce a file before it is readable — the body is still flushing the capture —
 * so a failure is retried for a few polls before the handle is given up. The bound keeps a
 * handle the camera keeps advertising from being retried forever.
 */
internal class EventRetryQueue(private val maxAttempts: Int = 10) {
    private val attempts = LinkedHashMap<Int, Int>()

    /**
     * Remembers [handle] for a later poll.
     *
     * Queuing does not itself charge an attempt; only a retry that fails does. That is what
     * lets a handle wait out a condition the camera cannot change — no download directory
     * configured yet — without spending the budget that is meant for transfer failures.
     */
    fun enqueue(handle: Int) {
        attempts.putIfAbsent(handle, 0)
    }

    fun pending(): List<Int> = attempts.keys.toList()

    fun hasPending(): Boolean = attempts.isNotEmpty()

    fun markSucceeded(handle: Int) {
        attempts.remove(handle)
    }


    fun markFailed(handle: Int) {
        val next = (attempts[handle] ?: 0) + 1
        if (next >= maxAttempts) {
            attempts.remove(handle)
        } else {
            attempts[handle] = next
        }
    }
}
