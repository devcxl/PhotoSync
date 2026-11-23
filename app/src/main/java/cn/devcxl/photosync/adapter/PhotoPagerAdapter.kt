package cn.devcxl.photosync.adapter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView

import cn.devcxl.photosync.R
import cn.devcxl.photosync.data.entity.PhotoEntity

data class PhotoRenderState(
    val thumbnail: Bitmap?,
    val full: Bitmap?
)

data class JpegSourceInfo(
    val width: Int,
    val height: Int
)

enum class PhotoViewerMode {
    PHOTO_VIEW,
    JPEG_TILED
}

internal enum class JpegTiledSourceMode {
    BITMAP_ONLY,
    TILED_WITH_PREVIEW,
    TILED_ORIGINAL_ONLY
}

internal enum class PhotoRenderStage {
    EMPTY,
    THUMBNAIL,
    FULL
}

internal data class PhotoRenderDecision(
    val stage: PhotoRenderStage,
    val shouldApplyImmediately: Boolean
)

internal data class JpegTiledBindDecision(
    val stage: PhotoRenderStage,
    val shouldSetImage: Boolean
)

internal fun resolveJpegPreviewStage(renderState: PhotoRenderState): PhotoRenderStage {
    return when {
        renderState.full != null -> PhotoRenderStage.FULL
        renderState.thumbnail != null -> PhotoRenderStage.THUMBNAIL
        else -> PhotoRenderStage.EMPTY
    }
}

internal fun resolvePhotoRenderDecision(
    currentPath: String?,
    currentStage: PhotoRenderStage,
    nextPath: String,
    hasThumbnail: Boolean,
    hasFull: Boolean,
    isZoomed: Boolean
): PhotoRenderDecision {
    val targetStage = when {
        hasFull -> PhotoRenderStage.FULL
        hasThumbnail -> PhotoRenderStage.THUMBNAIL
        else -> PhotoRenderStage.EMPTY
    }
    if (currentPath != nextPath) {
        return PhotoRenderDecision(targetStage, true)
    }
    if (currentStage == PhotoRenderStage.FULL && targetStage != PhotoRenderStage.FULL) {
        return PhotoRenderDecision(PhotoRenderStage.FULL, false)
    }
    if (currentStage == targetStage) {
        return PhotoRenderDecision(targetStage, false)
    }
    if (currentStage == PhotoRenderStage.THUMBNAIL &&
        targetStage == PhotoRenderStage.FULL &&
        isZoomed
    ) {
        return PhotoRenderDecision(PhotoRenderStage.FULL, false)
    }
    return PhotoRenderDecision(targetStage, true)
}

internal fun resolveJpegTiledBindDecision(
    currentPath: String?,
    currentStage: PhotoRenderStage,
    currentSourceMode: JpegTiledSourceMode?,
    nextPath: String,
    nextStage: PhotoRenderStage,
    nextSourceMode: JpegTiledSourceMode,
    isViewerReady: Boolean
): JpegTiledBindDecision {
    if (currentPath != nextPath) {
        return JpegTiledBindDecision(nextStage, true)
    }
    val resolvedStage = when {
        currentStage == PhotoRenderStage.FULL && nextStage != PhotoRenderStage.FULL -> {
            PhotoRenderStage.FULL
        }
        nextStage.ordinal > currentStage.ordinal -> nextStage
        else -> currentStage
    }
    if (currentSourceMode != nextSourceMode) {
        return JpegTiledBindDecision(resolvedStage, true)
    }
    if (resolvedStage == currentStage) {
        return JpegTiledBindDecision(currentStage, false)
    }
    if (!isViewerReady) {
        return JpegTiledBindDecision(resolvedStage, true)
    }
    return JpegTiledBindDecision(resolvedStage, false)
}

internal fun resolveJpegTiledSourceMode(
    isCurrentPage: Boolean,
    hasPreviewBitmap: Boolean
): JpegTiledSourceMode {
    return when {
        isCurrentPage && hasPreviewBitmap -> JpegTiledSourceMode.TILED_WITH_PREVIEW
        isCurrentPage -> JpegTiledSourceMode.TILED_ORIGINAL_ONLY
        else -> JpegTiledSourceMode.BITMAP_ONLY
    }
}

internal fun resolvePhotoViewerMode(isJpeg: Boolean): PhotoViewerMode {
    return if (isJpeg) {
        PhotoViewerMode.JPEG_TILED
    } else {
        PhotoViewerMode.PHOTO_VIEW
    }
}

internal fun resolveLoadingOverlayVisible(
    stage: PhotoRenderStage,
    isJpeg: Boolean,
    isTiledViewerReady: Boolean
): Boolean {
    return if (isJpeg) {
        stage == PhotoRenderStage.EMPTY && !isTiledViewerReady
    } else {
        stage == PhotoRenderStage.EMPTY
    }
}

class PhotoPagerAdapter(
    private val renderStateProvider: (String) -> PhotoRenderState,
    private val onBindRequest: (String, Int) -> Unit,
    private val jpegSourceInfoProvider: (String) -> JpegSourceInfo?,
    private val isCurrentPageProvider: (Int) -> Boolean,
    private val isJpegProvider: (String) -> Boolean,
    private val onPhotoScaleChanged: (String, Int, Float, Float) -> Unit,
    private val onPhotoTap: () -> Unit = {}
) : RecyclerView.Adapter<PhotoPagerAdapter.VH>() {
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
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
        val jpegDetailView = SubsamplingScaleImageView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        val loadingTextView = TextView(parent.context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.darkroom_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setText(R.string.decoding_raw_preview)
        }
        val loadingOverlay = createLoadingOverlay(parent.context, loadingTextView)
        container.addView(photoView)
        container.addView(jpegDetailView)
        container.addView(loadingOverlay)
        return VH(container, photoView, jpegDetailView, loadingOverlay, loadingTextView)
    }

    private fun createLoadingOverlay(context: Context, labelView: TextView): LinearLayout {
        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }
        val spinner = ProgressBar(context).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                marginEnd = dp(8)
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_glass_pill)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            visibility = View.GONE
            addView(spinner)
            addView(labelView)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entity = items[position]
        bindHolder(holder, entity, position)
        onBindRequest(entity.path, position)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_IMAGE_STATE) || payloads.contains(PAYLOAD_VIEWER_MODE)) {
            bindHolder(holder, items[position], position)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    private fun bindHolder(holder: VH, entity: PhotoEntity, position: Int) {
        val override = overrideFor(entity.path)
        val viewerMode = if (override != null) {
            PhotoViewerMode.PHOTO_VIEW
        } else {
            resolvePhotoViewerMode(isJpeg = isJpegProvider(entity.path))
        }
        holder.bind(
            path = entity.path,
            renderState = renderStateProvider(entity.path),
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
     * render state: it must survive a bind that happens to run mid-hold.
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

    /** Returns the original to render for [path], or `null` when no override is armed. */
    internal fun overrideFor(path: String): Bitmap? =
        if (path == overridePath) overrideBitmap else null

    class VH(
        container: FrameLayout,
        private val photoView: PhotoView,
        private val jpegDetailView: SubsamplingScaleImageView,
        private val loadingOverlay: LinearLayout,
        private val loadingTextView: TextView
    ) : RecyclerView.ViewHolder(container) {
        private var currentPath: String? = null
        private var currentStage: PhotoRenderStage = PhotoRenderStage.EMPTY
        private var pendingFullPath: String? = null
        private var pendingFullBitmap: Bitmap? = null
        private var currentViewerMode: PhotoViewerMode = PhotoViewerMode.PHOTO_VIEW
        private var currentJpegPreviewStage: PhotoRenderStage = PhotoRenderStage.EMPTY
        private var currentJpegSourceMode: JpegTiledSourceMode? = null
        private var showingOverride: Boolean = false
        private var displayedBitmap: Bitmap? = null

        fun bind(
            path: String,
            renderState: PhotoRenderState,
            overrideBitmap: Bitmap?,
            viewerMode: PhotoViewerMode,
            isCurrentPage: Boolean,
            jpegSourceInfo: JpegSourceInfo?,
            onPhotoScaleChanged: (String, Int, Float, Float) -> Unit,
            onPhotoTap: () -> Unit
        ) {
            if (viewerMode == PhotoViewerMode.JPEG_TILED) {
                showingOverride = false
                bindJpegTiled(
                    path = path,
                    renderState = renderState,
                    isCurrentPage = isCurrentPage,
                    jpegSourceInfo = jpegSourceInfo,
                    onPhotoScaleChanged = onPhotoScaleChanged,
                    onPhotoTap = onPhotoTap
                )
                return
            }
            bindPhotoView(path, renderState, overrideBitmap, onPhotoScaleChanged, onPhotoTap)
        }

        private fun bindPhotoView(
            path: String,
            renderState: PhotoRenderState,
            overrideBitmap: Bitmap?,
            onPhotoScaleChanged: (String, Int, Float, Float) -> Unit,
            onPhotoTap: () -> Unit
        ) {
            if (currentViewerMode == PhotoViewerMode.JPEG_TILED) {
                jpegDetailView.recycle()
                currentJpegPreviewStage = PhotoRenderStage.EMPTY
                currentJpegSourceMode = null
            }
            currentViewerMode = PhotoViewerMode.PHOTO_VIEW
            photoView.visibility = View.VISIBLE
            jpegDetailView.visibility = View.GONE
            loadingTextView.setText(R.string.decoding_raw_preview)
            photoView.setOnViewTapListener { _, _, _ -> onPhotoTap() }
            photoView.setOnScaleChangeListener { _, _, _ ->
                applyPendingFullIfIdle()
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onPhotoScaleChanged(path, position, photoView.scale, photoView.minimumScale)
                }
            }
            if (overrideBitmap != null) {
                showingOverride = true
                photoView.setImageBitmap(overrideBitmap)
                updateLoadingOverlay(PhotoRenderStage.FULL, isJpeg = false)
                return
            }
            val wasComparing = showingOverride
            showingOverride = false
            val decision = resolvePhotoRenderDecision(
                currentPath = currentPath,
                currentStage = currentStage,
                nextPath = path,
                hasThumbnail = renderState.thumbnail != null,
                hasFull = renderState.full != null,
                isZoomed = isZoomed()
            )

            if (currentPath != path) {
                pendingFullPath = null
                pendingFullBitmap = null
            }

            if (!decision.shouldApplyImmediately) {
                if (wasComparing) {
                    val restore = renderState.full ?: renderState.thumbnail
                    if (restore != null) applyBitmap(path, decision.stage, restore)
                }
                val replacement = when (decision.stage) {
                    PhotoRenderStage.FULL -> renderState.full
                    PhotoRenderStage.THUMBNAIL -> renderState.thumbnail
                    PhotoRenderStage.EMPTY -> null
                }
                if (currentPath == path && replacement != null && replacement !== displayedBitmap) {
                    if (!isZoomed()) {
                        pendingFullPath = null
                        pendingFullBitmap = null
                        applyBitmap(path, decision.stage, replacement)
                    } else if (decision.stage == PhotoRenderStage.FULL) {
                        pendingFullPath = path
                        pendingFullBitmap = replacement
                    }
                } else if (decision.stage == PhotoRenderStage.FULL &&
                    renderState.full != null &&
                    currentPath == path
                ) {
                    pendingFullPath = path
                    pendingFullBitmap = renderState.full
                }
                updateLoadingOverlay(currentStage, isJpeg = false)
                return
            }

            when (decision.stage) {
                PhotoRenderStage.FULL -> {
                    pendingFullPath = null
                    pendingFullBitmap = null
                    applyBitmap(path, PhotoRenderStage.FULL, renderState.full)
                }
                PhotoRenderStage.THUMBNAIL -> {
                    pendingFullPath = null
                    pendingFullBitmap = null
                    applyBitmap(path, PhotoRenderStage.THUMBNAIL, renderState.thumbnail)
                }
                PhotoRenderStage.EMPTY -> {
                    pendingFullPath = null
                    pendingFullBitmap = null
                    displayedBitmap = null
                    currentPath = path
                    currentStage = PhotoRenderStage.EMPTY
                    photoView.setImageDrawable(null)
                    photoView.scale = 1.0f
                }
            }
            updateLoadingOverlay(currentStage, isJpeg = false)
        }

        private fun bindJpegTiled(
            path: String,
            renderState: PhotoRenderState,
            isCurrentPage: Boolean,
            jpegSourceInfo: JpegSourceInfo?,
            onPhotoScaleChanged: (String, Int, Float, Float) -> Unit,
            onPhotoTap: () -> Unit
        ) {
            val previewBitmap = renderState.full ?: renderState.thumbnail
            val nextPreviewStage = resolveJpegPreviewStage(renderState)
            val nextSourceMode = resolveJpegTiledSourceMode(
                isCurrentPage = isCurrentPage,
                hasPreviewBitmap = previewBitmap != null
            )
            val decision = resolveJpegTiledBindDecision(
                currentPath = currentPath,
                currentStage = currentJpegPreviewStage,
                currentSourceMode = currentJpegSourceMode,
                nextPath = path,
                nextStage = nextPreviewStage,
                nextSourceMode = nextSourceMode,
                isViewerReady = jpegDetailView.isReady
            )
            currentViewerMode = PhotoViewerMode.JPEG_TILED
            photoView.visibility = View.GONE
            jpegDetailView.visibility = View.VISIBLE
            loadingTextView.setText(R.string.loading_photo_preview)
            jpegDetailView.setPanEnabled(isCurrentPage)
            jpegDetailView.setZoomEnabled(isCurrentPage)
            jpegDetailView.setOnClickListener { onPhotoTap() }
            jpegDetailView.setOnStateChangedListener(object : SubsamplingScaleImageView.OnStateChangedListener {
                override fun onScaleChanged(newScale: Float, origin: Int) {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onPhotoScaleChanged(path, position, newScale, jpegDetailView.minScale)
                    }
                }

                override fun onCenterChanged(newCenter: PointF?, origin: Int) = Unit
            })
            jpegDetailView.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    updateLoadingOverlay(currentJpegPreviewStage, isJpeg = true)
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onPhotoScaleChanged(path, position, jpegDetailView.scale, jpegDetailView.minScale)
                    }
                }

                override fun onImageLoaded() {
                    updateLoadingOverlay(currentJpegPreviewStage, isJpeg = true)
                }
            })
            pendingFullPath = null
            pendingFullBitmap = null
            currentPath = path
            currentStage = decision.stage
            currentJpegPreviewStage = decision.stage
            currentJpegSourceMode = nextSourceMode
            updateLoadingOverlay(currentJpegPreviewStage, isJpeg = true)
            if (!decision.shouldSetImage) {
                return
            }
            when (nextSourceMode) {
                JpegTiledSourceMode.BITMAP_ONLY -> {
                    val bitmapSource = previewBitmap?.let { ImageSource.cachedBitmap(it) }
                    if (bitmapSource != null) {
                        jpegDetailView.setImage(bitmapSource)
                    } else {
                        jpegDetailView.setImage(ImageSource.uri(path))
                    }
                }
                JpegTiledSourceMode.TILED_WITH_PREVIEW -> {
                    if (jpegSourceInfo == null) {
                        jpegDetailView.setImage(ImageSource.uri(path))
                        return
                    }
                    val imageSource = ImageSource
                        .uri(path)
                        .dimensions(jpegSourceInfo.width, jpegSourceInfo.height)
                    val previewSource = previewBitmap?.let { ImageSource.cachedBitmap(it) }
                    if (previewSource != null) {
                        jpegDetailView.setImage(imageSource, previewSource)
                    } else {
                        jpegDetailView.setImage(imageSource)
                    }
                }
                JpegTiledSourceMode.TILED_ORIGINAL_ONLY -> {
                    if (jpegSourceInfo == null) {
                        jpegDetailView.setImage(ImageSource.uri(path))
                    } else {
                        jpegDetailView.setImage(
                            ImageSource.uri(path).dimensions(
                                jpegSourceInfo.width,
                                jpegSourceInfo.height
                            )
                        )
                    }
                }
            }
        }

        private fun updateLoadingOverlay(stage: PhotoRenderStage, isJpeg: Boolean) {
            val show = resolveLoadingOverlayVisible(
                stage = stage,
                isJpeg = isJpeg,
                isTiledViewerReady = jpegDetailView.isReady
            )
            loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        }

        private fun applyPendingFullIfIdle() {
            val pendingPath = pendingFullPath ?: return
            val pendingBitmap = pendingFullBitmap ?: return
            if (currentPath != pendingPath || isZoomed()) return

            pendingFullPath = null
            pendingFullBitmap = null
            applyBitmap(pendingPath, PhotoRenderStage.FULL, pendingBitmap)
        }

        private fun applyBitmap(path: String, stage: PhotoRenderStage, bitmap: Bitmap?) {
            if (bitmap == null) return
            currentPath = path
            currentStage = stage
            displayedBitmap = bitmap
            photoView.setImageBitmap(bitmap)
        }

        private fun isZoomed(): Boolean {
            return testIsZoomed(photoView.scale, photoView.minimumScale)
        }

        companion object {
            fun testIsZoomed(scale: Float, minScale: Float): Boolean {
                return minScale > 0f && scale > minScale * 1.02f
            }
        }
    }
}
