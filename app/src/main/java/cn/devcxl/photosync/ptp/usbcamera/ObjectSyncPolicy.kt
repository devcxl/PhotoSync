package cn.devcxl.photosync.ptp.usbcamera

/**
 * Object handles that appeared on the camera since the last reconciliation.
 *
 * @param handled Handles the app already imported.
 * @param onCamera Handles the camera reports right now.
 */
internal fun newObjectHandles(handled: List<Int>, onCamera: List<Int>): List<Int> {
    val known = handled.toHashSet()
    return onCamera.filter { it !in known }
}

/**
 * The handled set for the next tick, in camera order.
 *
 * Starts from what was already handled plus what this tick imported, then keeps only the
 * handles the camera still reports. Dropping vanished handles matters twice: a deleted
 * photo must not stay "imported", and handles are reused — if the deleted handle stayed in
 * the set, the next photo that lands on it would be treated as already imported and would
 * never sync.
 *
 * A handle whose download failed is not in [imported], so it stays out of the result and
 * is retried on the next tick.
 *
 * @param handled Handles imported before this tick.
 * @param onCamera Handles the camera reports right now.
 * @param imported Handles successfully imported during this tick.
 */
internal fun nextHandledHandles(
    handled: List<Int>,
    onCamera: List<Int>,
    imported: List<Int>
): List<Int> {
    val known = handled.toHashSet()
    known.addAll(imported)
    return onCamera.filter { it in known }
}

/**
 * The handled set after one event-mode import.
 *
 * Event mode learns about single objects, never about the whole card, so it can only add:
 * dropping a handle that left the camera needs an enumeration, and that happens either
 * when the app deletes the object or when the next session builds its baseline.
 *
 * @param handled Handles imported before this event.
 * @param imported Handle the event channel reported and the app has just imported.
 * @return [handled] itself when the handle was already known, so callers can skip
 *   persisting an unchanged set.
 */
internal fun withImportedHandle(handled: List<Int>, imported: Int): List<Int> {
    if (imported in handled) {
        return handled
    }
    return handled + imported
}

/**
 * Whether an event-mode object handle still needs to be downloaded and imported.
 *
 * Vendor event channels (such as Canon EOS reporting both `ObjectAddedEx` and
 * `ObjectAddedEx64`, or queuing an event for a capture already caught by session-start
 * reconciliation) can surface the same handle more than once.
 */
internal fun shouldImportEventHandle(handled: List<Int>, handle: Int): Boolean {
    return handle !in handled
}

/**
 * Pure retry policy for event-mode object handles whose initial `GetObjectInfo`/`GetObject`
 * failed transiently (e.g., while the camera was busy committing a parameter change or
 * flushing a new capture to storage).
 */
internal class EventHandleRetryTracker(private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS) {
    private val attemptsByHandle = LinkedHashMap<Int, Int>()

    fun enqueue(handle: Int) {
        if (handle !in attemptsByHandle) {
            attemptsByHandle[handle] = 0
        }
    }

    fun pendingHandles(): List<Int> = attemptsByHandle.keys.toList()

    fun hasPending(): Boolean = attemptsByHandle.isNotEmpty()

    fun markSucceeded(handle: Int) {
        attemptsByHandle.remove(handle)
    }

    fun markFailedAttempt(handle: Int) {
        val next = (attemptsByHandle[handle] ?: 0) + 1
        if (next >= maxAttempts) {
            attemptsByHandle.remove(handle)
        } else {
            attemptsByHandle[handle] = next
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 10
    }
}

