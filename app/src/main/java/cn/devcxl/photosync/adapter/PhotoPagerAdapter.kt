package cn.devcxl.photosync.adapter

import android.graphics.Bitmap
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import cn.devcxl.photosync.data.entity.PhotoEntity
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView

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

internal fun resolvePhotoViewerMode(
    isCurrentPage: Boolean,
    isJpeg: Boolean
): PhotoViewerMode {
    return if (isJpeg) {
        PhotoViewerMode.JPEG_TILED
    } else {
        PhotoViewerMode.PHOTO_VIEW
    }
}

class PhotoPagerAdapter(
    private val renderStateProvider: (String) -> PhotoRenderState,
    private val onBindRequest: (String, Int) -> Unit,
    private val jpegSourceInfoProvider: (String) -> JpegSourceInfo?,
    private val isCurrentPageProvider: (Int) -> Boolean,
    private val isJpegProvider: (String) -> Boolean,
    private val onPhotoScaleChanged: (String, Int, Float, Float) -> Unit
) : RecyclerView.Adapter<PhotoPagerAdapter.VH>() {
    companion object {
        private val PAYLOAD_IMAGE_STATE = Any()
        private val PAYLOAD_VIEWER_MODE = Any()
    }

    private val items = mutableListOf<PhotoEntity>()
    private var currentPrimaryPosition: Int = RecyclerView.NO_POSITION

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
            visibility = android.view.View.GONE
        }
        container.addView(photoView)
        container.addView(jpegDetailView)
        return VH(container, photoView, jpegDetailView)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entity = items[position]
        val isCurrentPage = isCurrentPageProvider(position)
        holder.bind(
            path = entity.path,
            renderState = renderStateProvider(entity.path),
            viewerMode = resolvePhotoViewerMode(
                isCurrentPage = isCurrentPage,
                isJpeg = isJpegProvider(entity.path)
            ),
            isCurrentPage = isCurrentPage,
            jpegSourceInfo = jpegSourceInfoProvider(entity.path),
            onPhotoScaleChanged = onPhotoScaleChanged
        )
        onBindRequest(entity.path, position)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_IMAGE_STATE) || payloads.contains(PAYLOAD_VIEWER_MODE)) {
            val entity = items[position]
            val isCurrentPage = isCurrentPageProvider(position)
            holder.bind(
                path = entity.path,
                renderState = renderStateProvider(entity.path),
                viewerMode = resolvePhotoViewerMode(
                    isCurrentPage = isCurrentPage,
                    isJpeg = isJpegProvider(entity.path)
                ),
                isCurrentPage = isCurrentPage,
                jpegSourceInfo = jpegSourceInfoProvider(entity.path),
                onPhotoScaleChanged = onPhotoScaleChanged
            )
            return
        }
        super.onBindViewHolder(holder, position, payloads)
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
        val idx = indexOfPath(path)
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_IMAGE_STATE)
    }

    fun updatePrimaryPosition(position: Int) {
        if (currentPrimaryPosition == position) return
        val previousPosition = currentPrimaryPosition
        currentPrimaryPosition = position
        if (previousPosition != RecyclerView.NO_POSITION) {
            notifyItemChanged(previousPosition, PAYLOAD_VIEWER_MODE)
        }
        if (position != RecyclerView.NO_POSITION) {
            notifyItemChanged(position, PAYLOAD_VIEWER_MODE)
        }
    }

    class VH(
        container: FrameLayout,
        private val photoView: PhotoView,
        private val jpegDetailView: SubsamplingScaleImageView
    ) : RecyclerView.ViewHolder(container) {
        private var currentPath: String? = null
        private var currentStage: PhotoRenderStage = PhotoRenderStage.EMPTY
        private var pendingFullPath: String? = null
        private var pendingFullBitmap: Bitmap? = null
        private var currentViewerMode: PhotoViewerMode = PhotoViewerMode.PHOTO_VIEW
        private var currentJpegPreviewStage: PhotoRenderStage = PhotoRenderStage.EMPTY
        private var currentJpegSourceMode: JpegTiledSourceMode? = null

        fun bind(
            path: String,
            renderState: PhotoRenderState,
            viewerMode: PhotoViewerMode,
            isCurrentPage: Boolean,
            jpegSourceInfo: JpegSourceInfo?,
            onPhotoScaleChanged: (String, Int, Float, Float) -> Unit
        ) {
            if (viewerMode == PhotoViewerMode.JPEG_TILED) {
                bindJpegTiled(path, renderState, isCurrentPage, jpegSourceInfo)
                return
            }

            if (currentViewerMode == PhotoViewerMode.JPEG_TILED) {
                jpegDetailView.recycle()
                currentJpegPreviewStage = PhotoRenderStage.EMPTY
                currentJpegSourceMode = null
            }
            currentViewerMode = PhotoViewerMode.PHOTO_VIEW
            photoView.visibility = android.view.View.VISIBLE
            jpegDetailView.visibility = android.view.View.GONE
            photoView.setOnScaleChangeListener { _, _, _ ->
                applyPendingFullIfIdle()
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onPhotoScaleChanged(path, position, photoView.scale, photoView.minimumScale)
                }
            }
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
                if (decision.stage == PhotoRenderStage.FULL &&
                    renderState.full != null &&
                    currentPath == path
                ) {
                    pendingFullPath = path
                    pendingFullBitmap = renderState.full
                }
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
                    currentPath = path
                    currentStage = PhotoRenderStage.EMPTY
                    photoView.setImageDrawable(null)
                    photoView.scale = 1.0f
                }
            }
        }

        private fun bindJpegTiled(
            path: String,
            renderState: PhotoRenderState,
            isCurrentPage: Boolean,
            jpegSourceInfo: JpegSourceInfo?
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
            photoView.visibility = android.view.View.GONE
            jpegDetailView.visibility = android.view.View.VISIBLE
            jpegDetailView.setPanEnabled(isCurrentPage)
            jpegDetailView.setZoomEnabled(isCurrentPage)
            pendingFullPath = null
            pendingFullBitmap = null
            currentPath = path
            currentStage = decision.stage
            currentJpegPreviewStage = decision.stage
            currentJpegSourceMode = nextSourceMode
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
            photoView.setImageBitmap(bitmap)
        }

        private fun isZoomed(): Boolean {
            return photoView.scale > photoView.minimumScale + 0.01f
        }

        companion object {
            fun testIsZoomed(scale: Float, minScale: Float): Boolean {
                return scale > minScale + 0.01f
            }
        }
    }
}
