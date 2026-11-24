package cn.devcxl.photosync.activity

import android.content.Context
import android.graphics.Bitmap
import android.view.View

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

import cn.devcxl.photosync.App
import cn.devcxl.photosync.adapter.PhotoPagerAdapter
import cn.devcxl.photosync.data.PhotoDao
import cn.devcxl.photosync.databinding.ActivityMainBinding
import cn.devcxl.photosync.editor.contract.PhotoEditRecord
import cn.devcxl.photosync.editor.domain.EditGraph

/**
 * Wires the tethered darkroom controls of `MainActivity`: the unified collapsible preset strip
 * (controlling both current-photo look and tethered auto-apply) and the explicit hold-to-compare
 * button inside the strip.
 */
internal class TetheredDarkroomCoordinator(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val owner: LifecycleOwner,
    private val scope: CoroutineScope,
    private val dao: PhotoDao,
    private val adapter: PhotoPagerAdapter,
    private val originalPreviewCache: OriginalPreviewCache,
    private val currentIndex: () -> Int,
    private val editedMap: () -> Map<Long, PhotoEditRecord>,
    private val decodeOriginal: (String) -> Bitmap?,
    private val onEditPersisted: (Long) -> Unit,
    private val onFeedback: (String) -> Unit
) {

    private lateinit var quickStrip: TetheredQuickStripController
    private lateinit var darkroom: TetheredDarkroomHost
    private lateinit var compare: BeforeAfterCompareController

    /** Builds the darkroom strip and compare controllers. */
    fun bind() {
        compare = BeforeAfterCompareController(
            binding = binding,
            adapter = adapter,
            scope = scope,
            cache = originalPreviewCache,
            decodeOriginal = decodeOriginal
        )
        quickStrip = TetheredQuickStripController(context, binding, owner, QuickStripHost())
        darkroom = TetheredDarkroomHost(
            context = context,
            scope = scope,
            dao = dao,
            adapter = adapter,
            quickStrip = quickStrip,
            currentIndex = currentIndex,
            editedMap = editedMap,
            onGraphPersisted = onEditPersisted,
            onFeedback = onFeedback
        )
        quickStrip.refresh(EditGraph())
    }

    /** Ends any active Before/After comparison (e.g. when swiping to another page). */
    fun endCompare() = compare.clear()

    /** Repaints the strip for the current page. */
    fun refreshQuickStrip() = quickStrip.refresh(darkroom.currentGraph())

    /** Repaints the strip for a caller-supplied [graph]. */
    fun refreshQuickStrip(graph: EditGraph) = quickStrip.refresh(graph)

    /** Keeps the USB transfer card consistent when transfers complete while the editor was open. */
    fun observeTransferState() {
        val app = context.applicationContext as? App ?: return
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.transferActive.collect { active ->
                    if (!active) binding.transferStatusCard.visibility = View.GONE
                }
            }
        }
    }

    private inner class QuickStripHost : TetheredQuickStripController.Host {
        override fun onPresetSelected(
            photoId: Long?,
            graph: EditGraph?,
            presetId: String?,
            presetName: String?
        ) = darkroom.selectPreset(photoId, graph, presetId, presetName)

        override fun onCompareChanged(comparing: Boolean) {
            val path = darkroom.currentPhotoPath()
            if (!comparing || path == null || !darkroom.canCompareCurrent()) {
                compare.clear()
            } else {
                compare.setComparing(path, true)
            }
        }

        override fun currentGraph(): EditGraph = darkroom.currentGraph()

        override fun currentPhotoId(): Long? = darkroom.currentPhotoId()

        override fun canCompareCurrent(): Boolean = darkroom.canCompareCurrent()

        override fun prewarmPortrait() = darkroom.prewarmCurrentPortrait()
    }
}
