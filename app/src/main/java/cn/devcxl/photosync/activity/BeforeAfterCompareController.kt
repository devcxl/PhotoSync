package cn.devcxl.photosync.activity

import android.graphics.Bitmap
import android.util.LruCache
import android.view.View

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

import cn.devcxl.photosync.adapter.PhotoPagerAdapter
import cn.devcxl.photosync.databinding.ActivityMainBinding

/**
 * Press-and-hold Before/After comparison for the main photo pager.
 *
 * While the user holds a page down, the pager is told to render the *original* pixels and the
 * `未编辑原图 (Before)` pill appears; releasing clears the override so the graded preview returns.
 *
 * The swap is instantaneous because the original is decoded once and cached: decoding on hold would
 * show the original a frame late, which is worse than not showing it at all.
 */
internal class BeforeAfterCompareController(
    private val binding: ActivityMainBinding,
    private val adapter: PhotoPagerAdapter,
    private val scope: CoroutineScope,
    private val cache: OriginalPreviewCache,
    private val decodeOriginal: (String) -> Bitmap?
) {

    /** The path currently shown as the unedited original, or `null` when not comparing. */
    private var comparingPath: String? = null

    /** True while a page is being shown as the unedited original. */
    val isComparing: Boolean
        get() = comparingPath != null

    /**
     * Starts or ends the comparison for [path].
     *
     * A hold on a page without an original is ignored: the detector was never armed for it, and this
     * guard keeps a stray callback harmless.
     */
    fun setComparing(path: String, comparing: Boolean) {
        if (comparing) {
            beginCompare(path)
        } else {
            endCompare(path)
        }
    }

    /**
     * Pushes the cached original of [path] into the pager as an override.
     *
     * Runs the decode off the UI thread the first time only; every later hold is a cache hit.
     */
    private fun beginCompare(path: String) {
        if (comparingPath == path) return
        val cached = cache.get(path)
        if (cached != null) {
            showOriginal(path, cached)
            return
        }
        comparingPath = path
        binding.compareStatusPill.visibility = View.VISIBLE
        scope.launch {
            val original = decodeOriginal(path)
            if (original == null) {
                Timber.w("Compare requested for a photo whose original could not be decoded")
                onDecodeFailed(path)
            } else {
                cache.put(path, original)
                onDecoded(path, original)
            }
        }
    }

    private fun onDecodeFailed(path: String) {
        if (comparingPath == path) endCompare(path)
    }

    private fun onDecoded(path: String, original: Bitmap) {
        if (comparingPath != path) {
            // The user released before the decode finished; do not leave the override armed.
            adapter.clearImageOverride()
        } else {
            showOriginal(path, original)
        }
    }

    private fun showOriginal(path: String, original: Bitmap) {
        comparingPath = path
        adapter.showImageOverride(path, original)
        binding.compareStatusPill.visibility = View.VISIBLE
    }

    private fun endCompare(path: String) {
        if (comparingPath != path) return
        comparingPath = null
        adapter.clearImageOverride()
        binding.compareStatusPill.visibility = View.GONE
    }
}

/**
 * Bounded cache of decoded *unedited* previews used only while a page is compared.
 *
 * It is deliberately separate from the pager's caches: the pager holds the graded preview (what the
 * user normally sees), so reusing those entries would make `Before` identical to `After`.
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

    /** True when [path] still has a decoded original, i.e. comparing needs no decode. */
    fun contains(path: String): Boolean = cache.get(path) != null

    private fun maxSizeKb(divisor: Int): Int {
        val maxMemKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (maxMemKb / divisor).coerceAtLeast(1024)
    }
}
