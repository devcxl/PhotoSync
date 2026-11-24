package cn.devcxl.photosync.adapter

import android.graphics.Bitmap
import android.graphics.PointF
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import cn.devcxl.photosync.R

/**
 * Applies JPEG "tiled" binds to one holder's [TiledViewerSlot].
 *
 * Owns everything that only makes sense while the tiled viewer shows a path: the path/stage/source
 * mode that [resolveJpegTiledBindDecision] compares against, the viewer configuration, the loading
 * pill for tiled loads, and the image application for the three [JpegTiledSourceMode]s.
 *
 * A bind never mutates a busy view: [TiledViewerSlot.acquireForBind] replaces it, and this class
 * resets its tracking before re-applying the image to the fresh view. Without that reset a
 * same-path rebind of a fresh view would look "already applied" and stay blank.
 */
internal class JpegTiledViewBinder(
    private val slot: TiledViewerSlot<SubsamplingScaleImageView>,
    private val loadingOverlay: LoadingOverlay,
    private val adapterPosition: () -> Int
) {
    private var currentPath: String? = null
    private var currentStage: PhotoRenderStage = PhotoRenderStage.EMPTY
    private var currentSourceMode: JpegTiledSourceMode? = null

    /**
     * Shows [path] in the tiled viewer.
     *
     * The holder keeps its PhotoView state separate: hiding the PhotoView does not change what
     * its drawable shows, so the tiled stage must never be reported as the PhotoView's stage.
     */
    fun bind(
        path: String,
        renderState: PhotoRenderState,
        isCurrentPage: Boolean,
        jpegSourceInfo: JpegSourceInfo?,
        onPhotoScaleChanged: (String, Int, Float, Float) -> Unit,
        onPhotoTap: () -> Unit
    ) {
        if (renderState.isPreviewUnavailable) {
            // Defense in depth for a caller that ignored the record: the tiled viewer reads the
            // original file, so it must never stand in for a missing graded preview.
            showUnavailable()
            return
        }
        val previewBitmap = renderState.full ?: renderState.thumbnail
        val nextStage = resolveJpegPreviewStage(renderState)
        val nextSourceMode = resolveJpegTiledSourceMode(isCurrentPage, previewBitmap != null)
        var decision = resolveJpegTiledBindDecision(
            currentPath = currentPath,
            currentStage = currentStage,
            currentSourceMode = currentSourceMode,
            nextPath = path,
            nextStage = nextStage,
            nextSourceMode = nextSourceMode,
            isViewerReady = slot.currentView?.isReady == true
        )
        val target = slot.acquireForBind(needsNewImage = decision.shouldSetImage)
        if (target.isFresh) {
            // The replacement view shows nothing yet, so the decision computed for the old view
            // no longer applies: recompute it against an empty viewer.
            currentPath = null
            currentStage = PhotoRenderStage.EMPTY
            currentSourceMode = null
            decision = resolveJpegTiledBindDecision(
                currentPath = null,
                currentStage = PhotoRenderStage.EMPTY,
                currentSourceMode = null,
                nextPath = path,
                nextStage = nextStage,
                nextSourceMode = nextSourceMode,
                isViewerReady = false
            )
        }
        val view = target.view
        view.visibility = View.VISIBLE
        configure(view, path, isCurrentPage, onPhotoScaleChanged, onPhotoTap)
        currentPath = path
        currentStage = decision.stage
        currentSourceMode = nextSourceMode
        updateLoadingOverlay()
        if (decision.shouldSetImage) {
            applyImage(view, nextSourceMode, previewBitmap, path, jpegSourceInfo)
        }
    }

    /** Drops the attached tiled view and forgets the path it showed. */
    fun release() {
        slot.release()
        currentPath = null
        currentStage = PhotoRenderStage.EMPTY
        currentSourceMode = null
    }

    private fun showUnavailable() {
        slot.release()
        currentPath = null
        currentStage = PhotoRenderStage.UNAVAILABLE
        currentSourceMode = null
        loadingOverlay.showMessage(R.string.edited_preview_unavailable)
    }

    private fun configure(
        view: SubsamplingScaleImageView,
        path: String,
        isCurrentPage: Boolean,
        onPhotoScaleChanged: (String, Int, Float, Float) -> Unit,
        onPhotoTap: () -> Unit
    ) {
        view.setPanEnabled(isCurrentPage)
        view.setZoomEnabled(isCurrentPage)
        view.setOnClickListener { onPhotoTap() }
        view.setOnStateChangedListener(object : SubsamplingScaleImageView.OnStateChangedListener {
            override fun onScaleChanged(newScale: Float, origin: Int) {
                val position = adapterPosition()
                if (position != RecyclerView.NO_POSITION) {
                    onPhotoScaleChanged(path, position, newScale, view.minScale)
                }
            }

            override fun onCenterChanged(newCenter: PointF?, origin: Int) = Unit
        })
        view.setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
            override fun onReady() {
                updateLoadingOverlay()
                val position = adapterPosition()
                if (position != RecyclerView.NO_POSITION) {
                    onPhotoScaleChanged(path, position, view.scale, view.minScale)
                }
            }

            override fun onImageLoaded() {
                updateLoadingOverlay()
            }
        })
    }

    private fun applyImage(
        view: SubsamplingScaleImageView,
        sourceMode: JpegTiledSourceMode,
        previewBitmap: Bitmap?,
        path: String,
        jpegSourceInfo: JpegSourceInfo?
    ) {
        val imageSource = if (jpegSourceInfo == null) {
            ImageSource.uri(path)
        } else {
            ImageSource.uri(path).dimensions(jpegSourceInfo.width, jpegSourceInfo.height)
        }
        when (sourceMode) {
            JpegTiledSourceMode.BITMAP_ONLY -> {
                val bitmapSource = previewBitmap?.let { ImageSource.cachedBitmap(it) }
                view.setImage(bitmapSource ?: ImageSource.uri(path))
            }
            JpegTiledSourceMode.TILED_WITH_PREVIEW -> {
                if (jpegSourceInfo == null) {
                    view.setImage(ImageSource.uri(path))
                    return
                }
                val previewSource = previewBitmap?.let { ImageSource.cachedBitmap(it) }
                if (previewSource != null) {
                    view.setImage(imageSource, previewSource)
                } else {
                    view.setImage(imageSource)
                }
            }
            JpegTiledSourceMode.TILED_ORIGINAL_ONLY -> view.setImage(imageSource)
        }
    }

    private fun updateLoadingOverlay() {
        val show = resolveLoadingOverlayVisible(
            stage = currentStage,
            isJpeg = true,
            isTiledViewerReady = slot.currentView?.isReady == true
        )
        if (show) {
            loadingOverlay.showLoading(R.string.loading_photo_preview)
        } else {
            loadingOverlay.hide()
        }
    }
}
