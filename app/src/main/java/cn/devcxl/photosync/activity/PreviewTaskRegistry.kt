package cn.devcxl.photosync.activity

/**
 * Identifies one started preview decode for a path.
 *
 * Tokens are compared by identity. Every [PreviewTaskRegistry.begin] creates a new token, so a
 * token that was once superseded or released can never become current again.
 */
internal class PreviewTaskToken internal constructor(val path: String)

/**
 * Tracks which preview decode currently owns each path.
 *
 * At most one decode per path can be in flight. [invalidate] revokes that ownership: it clears
 * the marker so a new decode can start, while the old token stays stale forever. A superseded
 * decode therefore cannot publish its bitmap into the caches and cannot clear the marker of the
 * newer decode, no matter how late its cancellation or completion arrives.
 */
internal class PreviewTaskRegistry {

    private val lock = Any()
    private val currentByPath = HashMap<String, PreviewTaskToken>()

    /** Starts a decode for [path], or returns `null` when one is already in flight. */
    fun begin(path: String): PreviewTaskToken? =
        synchronized(lock) {
            if (currentByPath.containsKey(path)) {
                null
            } else {
                PreviewTaskToken(path).also { currentByPath[path] = it }
            }
        }

    /**
     * Publishes [task]'s bitmap through [publish] and clears its in-flight marker.
     *
     * The ownership check, the cache write and the marker removal run under the same lock as
     * [invalidate], so a bitmap decoded before an edit invalidation can never land in the cache
     * after it.
     *
     * @return `true` when [task] still owned its path and [publish] ran.
     */
    fun publishIfCurrent(task: PreviewTaskToken, publish: () -> Unit): Boolean =
        synchronized(lock) {
            if (currentByPath[task.path] !== task) {
                false
            } else {
                currentByPath.remove(task.path)
                publish()
                true
            }
        }

    /**
     * Releases the in-flight marker of [task] when it still owns the path.
     *
     * A late release from a cancelled or failed decode that [invalidate] already superseded is a
     * no-op, so it cannot hide the newer decode.
     */
    fun release(task: PreviewTaskToken) {
        synchronized(lock) {
            if (currentByPath[task.path] === task) {
                currentByPath.remove(task.path)
            }
        }
    }

    /** Revokes the decode currently owning [path], if any, so its completion becomes stale. */
    fun invalidate(path: String) {
        synchronized(lock) {
            currentByPath.remove(path)
        }
    }
}
