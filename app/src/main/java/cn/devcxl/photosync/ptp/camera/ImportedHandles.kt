package cn.devcxl.photosync.ptp.camera

/** Storage for the handles a device has already imported, owned by the caller. */
internal interface ImportedHandleStore {
    /** Previously imported handles, or null when this device has no record. */
    suspend fun load(): List<Int>?

    /** Replaces the record with [handles]. */
    suspend fun save(handles: List<Int>)
}

/**
 * The handles this session already imported.
 *
 * Kept out of the sync loop so the delete path can remove a handle at once: handles are
 * reused by cameras, and a deleted handle left in the set would make the next photo on
 * that handle look imported.
 */
internal class HandledObjects {
    @Volatile
    private var handles: List<Int> = emptyList()

    @Synchronized
    fun snapshot(): List<Int> = handles

    @Synchronized
    fun replace(next: List<Int>) {
        handles = next
    }

    @Synchronized
    fun add(handle: Int): Boolean {
        val current = handles
        if (handle in current) {
            return false
        }
        handles = current + handle
        return true
    }

    @Synchronized
    fun remove(handle: Int): Boolean {
        val current = handles
        val next = current.filter { it != handle }
        if (next.size == current.size) {
            return false
        }
        handles = next
        return true
    }
}
