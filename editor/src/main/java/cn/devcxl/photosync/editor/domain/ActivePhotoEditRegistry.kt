package cn.devcxl.photosync.editor.domain

/**
 * Process-wide registry of photos that currently have a live editor session or a running bake.
 *
 * The offline [AiAssetStore.collectOrphanAssets] sweep must never delete payloads that a live
 * editor or export is still streaming, even when the persisted edit row does not mention them yet.
 * The registry answers exactly that question with a snapshot the sweep reads once at its start.
 *
 * Reference-counted on purpose: the same photo can be open in the editor while a background bake
 * reads its assets, and the id must stay active until the last holder releases it. Entries are
 * keyed by the photo id, so a stale [release] can never affect an unrelated photo.
 *
 * Thread-safe: editors register from the main thread, bakes from `Dispatchers.Default`.
 */
object ActivePhotoEditRegistry {

    private val lock = Any()
    private val holders = HashMap<Long, Int>()

    /**
     * Marks [photoId] as active. Every call must be paired with exactly one [release]; ids that are
     * not positive are ignored because no edit directory can exist for them.
     */
    fun acquire(photoId: Long) {
        if (photoId <= 0L) return
        synchronized(lock) {
            holders[photoId] = (holders[photoId] ?: 0) + 1
        }
    }

    /**
     * Drops one [acquire] of [photoId]. The photo leaves [snapshot] when the last holder releases
     * it; releasing an id that is not registered is a no-op.
     */
    fun release(photoId: Long) {
        if (photoId <= 0L) return
        synchronized(lock) {
            val remaining = (holders[photoId] ?: 0) - 1
            if (remaining > 0) {
                holders[photoId] = remaining
            } else {
                holders.remove(photoId)
            }
        }
    }

    /** Copy of the photo ids that are active right now, safe to hand to an offline sweep. */
    fun snapshot(): Set<Long> = synchronized(lock) { holders.keys.toSet() }
}
