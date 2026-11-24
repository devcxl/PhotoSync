package cn.devcxl.photosync.adapter

import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout

import androidx.recyclerview.widget.RecyclerView

import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.github.chrisbanes.photoview.PhotoView

import cn.devcxl.photosync.R

/**
 * Per-row view state for [PhotoPagerAdapter].
 *
 * The adapter owns the list and binding orchestration; this holder owns everything that must
 * survive a rebind of one row: which bitmap the PhotoView currently shows, which stage that
 * bitmap represents, the pending full-preview frame, and the tiled-viewer slot.
 */
class PhotoPagerViewHolder internal constructor(
    container: FrameLayout,
    private val photoView: PhotoView,
    tiledSlot: TiledViewerSlot<SubsamplingScaleImageView>,
    private val loadingOverlay: LoadingOverlay
) : RecyclerView.ViewHolder(container) {
    private val tiledViewer = JpegTiledViewBinder(
        slot = tiledSlot,
        loadingOverlay = loadingOverlay,
        adapterPosition = { bindingAdapterPosition }
    )
    private var currentPath: String? = null
    private var currentStage: PhotoRenderStage = PhotoRenderStage.EMPTY
    private var currentContentVersion: PhotoContentVersion = PhotoContentVersion.Unspecified
    private var pendingFullPath: String? = null
    private var pendingFullBitmap: Bitmap? = null
    private var pendingFullVersion: PhotoContentVersion? = null
    private var currentViewerMode: PhotoViewerMode? = null
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
        requireMainThreadForViewer()
        val modeChanged = viewerMode != currentViewerMode
        currentViewerMode = viewerMode
        if (renderState.isPreviewUnavailable) {
            // Even if a provider asked for the URI-backed tiled viewer, an unavailable preview
            // must go through the bitmap surface so it can clear and explain itself.
            bindPhotoView(
                path = path,
                renderState = renderState,
                overrideBitmap = null,
                forceRenderState = true,
                onPhotoScaleChanged = onPhotoScaleChanged,
                onPhotoTap = onPhotoTap
            )
            return
        }
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
        bindPhotoView(
            path = path,
            renderState = renderState,
            overrideBitmap = overrideBitmap,
            forceRenderState = modeChanged,
            onPhotoScaleChanged = onPhotoScaleChanged,
            onPhotoTap = onPhotoTap
        )
    }

    /**
     * Forgets everything this holder knows about the photo it showed.
     *
     * RecyclerView reuses holders. Without this reset a rebind of the same path would treat
     * the image as already applied (same path and stage) and leave the view empty, and a late
     * tiled decode callback could still touch the shared loading overlay.
     */
    fun onRecycled() {
        requireMainThreadForViewer()
        tiledViewer.release()
        photoView.setOnViewTapListener(null)
        photoView.setOnScaleChangeListener(null)
        photoView.setImageDrawable(null)
        loadingOverlay.hide()
        currentPath = null
        currentStage = PhotoRenderStage.EMPTY
        currentContentVersion = PhotoContentVersion.Unspecified
        currentViewerMode = null
        pendingFullPath = null
        pendingFullBitmap = null
        pendingFullVersion = null
        showingOverride = false
        displayedBitmap = null
    }

    private fun bindPhotoView(
        path: String,
        renderState: PhotoRenderState,
        overrideBitmap: Bitmap?,
        forceRenderState: Boolean,
        onPhotoScaleChanged: (String, Int, Float, Float) -> Unit,
        onPhotoTap: () -> Unit
    ) {
        tiledViewer.release()
        photoView.visibility = View.VISIBLE
        photoView.setOnViewTapListener { _, _, _ -> onPhotoTap() }
        photoView.setOnScaleChangeListener { _, _, _ ->
            applyPendingFullIfIdle()
            val position = bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) {
                onPhotoScaleChanged(path, position, photoView.scale, photoView.minimumScale)
            }
        }
        val contentVersion = renderState.contentVersion
        if (currentPath == path && contentVersion != currentContentVersion) {
            // The same file now resolves to different content. Anything produced for the previous
            // version is stale, including a full frame deferred under the old zoom, so drop it
            // before the override and empty-state branches can decide to keep anything.
            pendingFullPath = null
            pendingFullBitmap = null
            pendingFullVersion = null
        }
        if (renderState.isPreviewUnavailable) {
            pendingFullPath = null
            pendingFullBitmap = null
            pendingFullVersion = null
            showingOverride = false
            clearDisplayedBitmap(path, contentVersion)
            currentStage = PhotoRenderStage.UNAVAILABLE
            loadingOverlay.showMessage(R.string.edited_preview_unavailable)
            return
        }
        if (overrideBitmap != null) {
            showingOverride = true
            photoView.setImageBitmap(overrideBitmap)
            updateLoadingOverlay(PhotoRenderStage.FULL)
            return
        }
        val wasComparing = showingOverride
        showingOverride = false
        if (wasComparing || forceRenderState) {
            // The tracked stage described the override frame or the tiled viewer, not the
            // bitmap the PhotoView is about to show. Show what the render state offers now;
            // an empty state must clear instead of keeping the previous drawable.
            showBestAvailableRenderState(path, renderState)
            return
        }
        val decision = resolvePhotoRenderDecision(
            currentPath = currentPath,
            currentStage = currentStage,
            currentContentVersion = currentContentVersion,
            nextPath = path,
            nextContentVersion = contentVersion,
            hasThumbnail = renderState.thumbnail != null,
            hasFull = renderState.full != null,
            isZoomed = isZoomed(),
            hasPreviewUnavailable = renderState.isPreviewUnavailable
        )

        if (currentPath != path) {
            pendingFullPath = null
            pendingFullBitmap = null
            pendingFullVersion = null
        }

        if (!decision.shouldApplyImmediately) {
            val replacement = when (decision.stage) {
                PhotoRenderStage.FULL -> renderState.full
                PhotoRenderStage.THUMBNAIL -> renderState.thumbnail
                PhotoRenderStage.EMPTY -> null
                PhotoRenderStage.UNAVAILABLE -> null
            }
            if (currentPath == path && replacement != null && replacement !== displayedBitmap) {
                if (!isZoomed()) {
                    pendingFullPath = null
                    pendingFullBitmap = null
                    pendingFullVersion = null
                    applyBitmap(path, decision.stage, replacement, contentVersion)
                } else if (decision.stage == PhotoRenderStage.FULL) {
                    pendingFullPath = path
                    pendingFullBitmap = replacement
                    pendingFullVersion = contentVersion
                }
            } else if (decision.stage == PhotoRenderStage.FULL &&
                renderState.full != null &&
                currentPath == path
            ) {
                pendingFullPath = path
                pendingFullBitmap = renderState.full
                pendingFullVersion = contentVersion
            }
            updateLoadingOverlay(currentStage)
            return
        }

        when (decision.stage) {
            PhotoRenderStage.FULL -> {
                pendingFullPath = null
                pendingFullBitmap = null
                pendingFullVersion = null
                applyBitmap(path, PhotoRenderStage.FULL, renderState.full, contentVersion)
            }
            PhotoRenderStage.THUMBNAIL -> {
                pendingFullPath = null
                pendingFullBitmap = null
                pendingFullVersion = null
                applyBitmap(path, PhotoRenderStage.THUMBNAIL, renderState.thumbnail, contentVersion)
            }
            PhotoRenderStage.EMPTY -> {
                pendingFullPath = null
                pendingFullBitmap = null
                pendingFullVersion = null
                clearDisplayedBitmap(path, contentVersion)
            }
            PhotoRenderStage.UNAVAILABLE -> {
                pendingFullPath = null
                pendingFullBitmap = null
                pendingFullVersion = null
                clearDisplayedBitmap(path, contentVersion)
                currentStage = PhotoRenderStage.UNAVAILABLE
                loadingOverlay.showMessage(R.string.edited_preview_unavailable)
            }
        }
        updateLoadingOverlay(currentStage)
    }

    private fun bindJpegTiled(
        path: String,
        renderState: PhotoRenderState,
        isCurrentPage: Boolean,
        jpegSourceInfo: JpegSourceInfo?,
        onPhotoScaleChanged: (String, Int, Float, Float) -> Unit,
        onPhotoTap: () -> Unit
    ) {
        photoView.visibility = View.GONE
        pendingFullPath = null
        pendingFullBitmap = null
        pendingFullVersion = null
        tiledViewer.bind(
            path = path,
            renderState = renderState,
            isCurrentPage = isCurrentPage,
            jpegSourceInfo = jpegSourceInfo,
            onPhotoScaleChanged = onPhotoScaleChanged,
            onPhotoTap = onPhotoTap
        )
    }

    /** Loading pill for PhotoView-mode binds; the tiled binder owns its own overlay state. */
    private fun updateLoadingOverlay(stage: PhotoRenderStage) {
        // The unavailable branch already wrote the terminal message; leave it untouched.
        if (stage == PhotoRenderStage.UNAVAILABLE) return
        val show = resolveLoadingOverlayVisible(stage = stage, isJpeg = false)
        if (show) {
            loadingOverlay.showLoading(R.string.decoding_raw_preview)
        } else {
            loadingOverlay.hide()
        }
    }

    /**
     * Shows the best bitmap [renderState] currently offers, dropping anything else.
     *
     * Used when the tracked PhotoView state cannot be trusted: the comparison override was
     * just removed, or the viewer switched back from tiled to bitmaps. An empty state clears
     * the drawable (never keep the previous photo or the original compare frame) and lets the
     * loading pill explain the wait.
     */
    private fun showBestAvailableRenderState(path: String, renderState: PhotoRenderState) {
        pendingFullPath = null
        pendingFullBitmap = null
        pendingFullVersion = null
        val contentVersion = renderState.contentVersion
        val full = renderState.full
        val thumbnail = renderState.thumbnail
        when {
            full != null -> applyBitmap(path, PhotoRenderStage.FULL, full, contentVersion)
            thumbnail != null ->
                applyBitmap(path, PhotoRenderStage.THUMBNAIL, thumbnail, contentVersion)
            else -> clearDisplayedBitmap(path, contentVersion)
        }
        updateLoadingOverlay(currentStage)
    }

    private fun clearDisplayedBitmap(path: String, contentVersion: PhotoContentVersion) {
        displayedBitmap = null
        currentPath = path
        currentStage = PhotoRenderStage.EMPTY
        currentContentVersion = contentVersion
        photoView.setImageDrawable(null)
        photoView.scale = 1.0f
    }

    private fun applyPendingFullIfIdle() {
        // A pending graded frame must never replace the comparison override mid-hold; letting
        // go restores the best available state through the forced path in bindPhotoView.
        if (showingOverride) return
        val pendingPath = pendingFullPath ?: return
        val pendingBitmap = pendingFullBitmap ?: return
        val pendingVersion = pendingFullVersion ?: return
        if (pendingVersion != currentContentVersion) {
            // The content changed after this frame was deferred; it can only resurrect stale pixels.
            pendingFullPath = null
            pendingFullBitmap = null
            pendingFullVersion = null
            return
        }
        if (currentPath != pendingPath || isZoomed()) return

        pendingFullPath = null
        pendingFullBitmap = null
        pendingFullVersion = null
        applyBitmap(pendingPath, PhotoRenderStage.FULL, pendingBitmap, pendingVersion)
    }

    private fun applyBitmap(
        path: String,
        stage: PhotoRenderStage,
        bitmap: Bitmap?,
        contentVersion: PhotoContentVersion
    ) {
        if (bitmap == null) return
        currentPath = path
        currentStage = stage
        currentContentVersion = contentVersion
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
