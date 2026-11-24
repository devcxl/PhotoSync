package cn.devcxl.photosync.adapter

import java.util.concurrent.Executor

import android.graphics.Bitmap
import android.os.AsyncTask
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView
import timber.log.Timber

import cn.devcxl.photosync.R
import cn.devcxl.photosync.data.entity.PhotoEntity

/**
 * Decode executor for the tracked tiled viewer: the same pool `SubsamplingScaleImageView` uses by
 * default, so decode parallelism and queueing behaviour are what the viewer already had. The
 * tracker observes submissions and does not add a queue of its own.
 */
@Suppress("DEPRECATION")
private val defaultTiledDecodeExecutor: Executor = AsyncTask.THREAD_POOL_EXECUTOR

class PhotoPagerAdapter internal constructor(
    private val renderStateProvider: (String) -> PhotoRenderState,
    private val onBindRequest: (String, Int) -> Unit,
    private val jpegSourceInfoProvider: (String) -> JpegSourceInfo?,
    private val isCurrentPageProvider: (Int) -> Boolean,
    private val isJpegProvider: (String) -> Boolean,
    private val onPhotoScaleChanged: (String, Int, Float, Float) -> Unit,
    private val onPhotoTap: () -> Unit = {},
    /**
     * Test-only seam: the decode worker the tracker submits to. Production keeps SSIV's default
     * pool; tests inject a manual executor. Kept `internal` so production code cannot replace
     * main-thread delivery or view setup.
     */
    private val tiledDecodeExecutor: Executor = defaultTiledDecodeExecutor,
    /**
     * Test-only seam: main-thread enqueue for the tracker sentinel. `null` uses this adapter's
     * main [handler].
     */
    private val postToMain: ((Runnable) -> Boolean)? = null,
    /**
     * Test-only seam: extra setup for each tiled view (decoder factories in tests). It must not
     * start an image decode.
     */
    private val configureTiledViewer: (SubsamplingScaleImageView) -> Unit = {}
) : RecyclerView.Adapter<PhotoPagerViewHolder>() {

    companion object {
        private val PAYLOAD_IMAGE_STATE = Any()
        private val PAYLOAD_VIEWER_MODE = Any()
    }

    private val items = mutableListOf<PhotoEntity>()
    private var currentPrimaryPosition: Int = RecyclerView.NO_POSITION
    private val handler = Handler(Looper.getMainLooper())

    /** Path and bitmap of the unedited original currently shown by the hold-to-compare button. */
    private var overridePath: String? = null
    private var overrideBitmap: Bitmap? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoPagerViewHolder {
        val container = FrameLayout(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val photoView = PhotoView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            maximumScale = 5.0f
            minimumScale = 1.0f
        }
        val loadingTextView = TextView(parent.context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.darkroom_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setText(R.string.decoding_raw_preview)
        }
        val loadingOverlay = createLoadingOverlay(parent.context, loadingTextView)
        container.addView(photoView)
        container.addView(loadingOverlay.view)
        val tiledSlot = TiledViewerSlot(
            host = SubsamplingTiledHost(container, configureTiledViewer),
            workerExecutor = tiledDecodeExecutor,
            postToMain = postToMain ?: { runnable -> handler.post(runnable) },
            onTaskPostFailure = { throwable ->
                Timber.e(
                    throwable,
                    "Tiled viewer sentinel could not be posted; its retired view will not be recycled"
                )
            }
        )
        return PhotoPagerViewHolder(container, photoView, tiledSlot, loadingOverlay)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: PhotoPagerViewHolder, position: Int) {
        val entity = items[position]
        bindHolder(holder, entity, position)
        onBindRequest(entity.path, position)
    }

    override fun onBindViewHolder(holder: PhotoPagerViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_IMAGE_STATE) || payloads.contains(PAYLOAD_VIEWER_MODE)) {
            bindHolder(holder, items[position], position)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onViewRecycled(holder: PhotoPagerViewHolder) {
        holder.onRecycled()
        super.onViewRecycled(holder)
    }

    private fun bindHolder(holder: PhotoPagerViewHolder, entity: PhotoEntity, position: Int) {
        val renderState = renderStateProvider(entity.path)
        if (renderState.isPreviewUnavailable) disarmOverrideOf(entity.path)
        val override = if (renderState.isPreviewUnavailable) null else overrideFor(entity.path)
        val viewerMode = if (override != null) {
            PhotoViewerMode.PHOTO_VIEW
        } else {
            // An unavailable preview must stay on the bitmap surface: the tiled viewer would
            // reveal the original file instead of the recovery message.
            resolvePhotoViewerMode(
                isJpeg = isJpegProvider(entity.path),
                isPreviewUnavailable = renderState.isPreviewUnavailable
            )
        }
        holder.bind(
            path = entity.path,
            renderState = renderState,
            overrideBitmap = override,
            viewerMode = viewerMode,
            isCurrentPage = isCurrentPageProvider(position),
            jpegSourceInfo = jpegSourceInfoProvider(entity.path),
            onPhotoScaleChanged = onPhotoScaleChanged,
            onPhotoTap = onPhotoTap
        )
    }

    fun updateItems(newItems: List<PhotoEntity>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition].path == newItems[newItemPosition].path
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val o = items[oldItemPosition]
                val n = newItems[newItemPosition]
                return o.name == n.name && o.uriString == n.uriString && o.createdAt == n.createdAt
            }
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun getItem(position: Int): PhotoEntity? = items.getOrNull(position)

    fun indexOfPath(path: String): Int = items.indexOfFirst { it.path == path }

    fun notifyPathChanged(path: String) {
        val idx = items.indexOfFirst { it.path == path }
        if (idx >= 0) handler.post { notifyItemChanged(idx, PAYLOAD_IMAGE_STATE) }
    }

    fun updatePrimaryPosition(position: Int) {
        if (currentPrimaryPosition == position) return
        val previousPosition = currentPrimaryPosition
        currentPrimaryPosition = position
        if (previousPosition != RecyclerView.NO_POSITION) {
            handler.post { notifyItemChanged(previousPosition, PAYLOAD_VIEWER_MODE) }
        }
        if (position != RecyclerView.NO_POSITION) {
            handler.post { notifyItemChanged(position, PAYLOAD_VIEWER_MODE) }
        }
    }

    /**
     * Shows the unedited [bitmap] for [path] until [clearImageOverride] is called.
     *
     * Used by the press-and-hold `Before` comparison. The override is intentionally not part of the
     * render state: it must survive a bind that happens to run mid-hold. A bind of *this* path whose
     * render state reports an unavailable preview disarms it, because the original must never
     * replace the recovery message; an unavailable neighbour leaves it untouched.
     */
    fun showImageOverride(path: String, bitmap: Bitmap) {
        overridePath = path
        overrideBitmap = bitmap
        notifyPathChanged(path)
    }

    /** Ends an override, restoring the graded preview. */
    fun clearImageOverride() {
        val path = overridePath
        overridePath = null
        overrideBitmap = null
        if (path != null) notifyPathChanged(path)
    }

    /**
     * Disarms the override when the page that owns it went unavailable.
     *
     * A comparison frame is the original by design. Keeping it armed would let the original
     * reappear when the record recovers, so an unavailable page drops it — but only its *own*
     * override: a pre-bound or payload-updated neighbour must not end the comparison the user is
     * holding on another page, which is why this cannot call the global [clearImageOverride].
     */
    private fun disarmOverrideOf(path: String) {
        if (path != overridePath) return
        overridePath = null
        overrideBitmap = null
    }

    /** Returns the original to render for [path], or `null` when no override is armed. */
    internal fun overrideFor(path: String): Bitmap? =
        if (path == overridePath) overrideBitmap else null
}
