package cn.devcxl.photosync.activity

import android.graphics.Bitmap
import android.util.LruCache
import android.view.View

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.adapter.PhotoPagerAdapter
import cn.devcxl.photosync.databinding.ActivityMainBinding

/**
 * Press-and-hold Before/After comparison for the main photo pager.
 *
 * While the user holds a page down, the pager is told to render the *original* pixels and the
 * `未编辑原图 (Before)` pill appears; releasing clears the override so the graded preview returns.
 */
internal class BeforeAfterCompareController(
    private val binding: ActivityMainBinding,
    private val adapter: PhotoPagerAdapter,
    private val scope: CoroutineScope,
    private val cache: OriginalPreviewCache,
    private val decodeOriginal: (String) -> Bitmap?,
    /** Runs [decodeOriginal]. Kept injectable so tests can drive the decode deterministically. */
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * The comparison that currently owns the pager override, or `null` when not comparing.
     *
     * Decoding is asynchronous, so a result can arrive long after the session that asked for it
     * ended: the user may have released the hold, swiped to another photo, or started comparing the
     * very same photo again. Each [beginCompare] installs a fresh, never reused token and every
     * in-flight decode carries the token it was started with. A callback whose token is no longer
     * installed belongs to a superseded session: it must not show anything, end the current
     * comparison, or clear the override — that override now belongs to a newer session.
     */
    @Volatile
    private var session: CompareSession? = null

    /** Starts or ends the comparison for [path]. */
    fun setComparing(path: String, comparing: Boolean) {
        if (comparing) {
            beginCompare(path)
        } else {
            endCompare(path)
        }
    }

    /** Ends any active comparison unconditionally (e.g. on page change). */
    fun clear() {
        if (session == null) return
        endSession()
    }

    private fun beginCompare(path: String) {
        if (session?.path == path) return
        val started = CompareSession(path)
        session = started
        val cached = cache.get(path)
        if (cached != null) {
            showOriginal(started, cached)
            return
        }
        binding.compareStatusPill.visibility = View.VISIBLE
        scope.launch {
            val original = withContext(decodeDispatcher) { decodeOriginal(path) }
            if (session !== started) return@launch
            if (original == null) {
                Timber.w("Compare requested for a photo whose original could not be decoded")
                endSession()
            } else {
                cache.put(path, original)
                showOriginal(started, original)
            }
        }
    }

    private fun endCompare(path: String) {
        if (session?.path != path) return
        endSession()
    }

    private fun endSession() {
        session = null
        adapter.clearImageOverride()
        binding.compareStatusPill.visibility = View.GONE
    }

    /** Shows [original] for the current session only; a superseded [owner] is ignored. */
    private fun showOriginal(owner: CompareSession, original: Bitmap) {
        if (session !== owner) return
        adapter.showImageOverride(owner.path, original)
        binding.compareStatusPill.visibility = View.VISIBLE
    }

    /**
     * Identity of one comparison attempt. Instances are never reused, so identity comparison tells
     * an in-flight decode of an ended session apart from the live one even when both decode the
     * same [path].
     */
    private class CompareSession(val path: String)
}

/**
 * Bounded cache of decoded *unedited* previews used only while a page is compared.
 */
internal class OriginalPreviewCache(divisor: Int = 12) {

    private val cache = object : LruCache<String, Bitmap>(maxSizeKb(divisor)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Returns the cached original of [path], or `null` when absent. */
    fun get(path: String): Bitmap? = cache.get(path)

    /** Caches [bitmap] as the original of [path]. */
    fun put(path: String, bitmap: Bitmap) {
        cache.put(path, bitmap)
    }

    /** Drops the entry of [path]. */
    fun remove(path: String) {
        cache.remove(path)
    }

    /** Drops every entry, e.g. when the library is emptied. */
    fun clear() {
        cache.evictAll()
    }

    private fun maxSizeKb(divisor: Int): Int {
        val maxMemKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (maxMemKb / divisor).coerceAtLeast(1024)
    }
}
