package cn.devcxl.photosync.activity

import android.content.Context
import android.view.View

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import cn.devcxl.photosync.R
import cn.devcxl.photosync.adapter.FilmstripAdapter
import cn.devcxl.photosync.adapter.PhotoPagerAdapter
import cn.devcxl.photosync.data.PhotoDao
import cn.devcxl.photosync.databinding.ActivityMainBinding
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.contract.PhotoEditRecord
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.export.BatchBakeController
import cn.devcxl.photosync.export.BatchBakeProgress
import cn.devcxl.photosync.export.BatchBakeState

/**
 * Wires the whole tethered darkroom floor of the main gallery: the quick preset strip, the batch bake
 * status card, the press-and-hold `Before` comparison and the sync announcements from `App`.
 *
 * Extracted from `MainActivity` so the activity stays view plumbing plus lifecycle. Everything here is
 * a one-line delegation or a pure render, so the class has no domain logic of its own.
 */
internal class TetheredDarkroomCoordinator(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val owner: LifecycleOwner,
    private val scope: CoroutineScope,
    private val dao: PhotoDao,
    private val adapter: PhotoPagerAdapter,
    private val filmstripAdapter: FilmstripAdapter,
    private val originalPreviewCache: OriginalPreviewCache,
    private val currentIndex: () -> Int,
    private val editedMap: () -> Map<Long, PhotoEditRecord>,
    private val decodeOriginal: (String) -> android.graphics.Bitmap?,
    private val onPhotoRevealed: (String) -> Unit,
    private val onEditPersisted: (Long) -> Unit,
    private val onFeedback: (String) -> Unit
) {

    private lateinit var quickStrip: TetheredQuickStripController
    private lateinit var darkroom: TetheredDarkroomHost
    private lateinit var batchBake: BatchBakeController
    private lateinit var compare: BeforeAfterCompareController
    private lateinit var copyPaste: MainActivityCopyPasteHelper

    /** The quick strip, so the activity can re-render it when the current page changes. */
    val quickStripController: TetheredQuickStripController
        get() = quickStrip

    /**
     * Builds every controller and subscribes the WorkManager queue.
     *
     * Must run after the adapters exist: the strip reads the pager's current graph and the compare
     * controller pushes bitmaps back into the pager.
     */
    fun bind() {
        copyPaste = MainActivityCopyPasteHelper(context)
        batchBake = BatchBakeController(context, owner).also(::observeBatchBake)
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
            filmstripAdapter = filmstripAdapter,
            quickStrip = quickStrip,
            batchBake = batchBake,
            copyPasteHelper = copyPaste,
            currentIndex = currentIndex,
            editedMap = editedMap,
            onGraphPersisted = onEditPersisted,
            onFeedback = onFeedback
        )
        quickStrip.refresh(EditGraph())
    }

    /** Enqueues the current photo (or the filmstrip selection) for a background batch bake. */
    fun enqueueBatchBake() = darkroom.enqueueBatchBake()

    /** Starts or ends the `Before` comparison for [path]. */
    fun setComparing(path: String, comparing: Boolean) = compare.setComparing(path, comparing)

    /** Repaints the strip for a new page. */
    fun refreshQuickStrip(graph: EditGraph) = quickStrip.refresh(graph)

    /**
     * Subscribes to the sync events `App` publishes.
     *
     * The download sink itself lives in `App` so a sync that finishes while the editor is in front is
     * still committed with its `LiveTetherPreset`; the gallery only reacts by revealing the photo.
     */
    fun observeSyncedPhotos() {
        val app = context.applicationContext as? cn.devcxl.photosync.App ?: return
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.syncedPhotoEvents.collect { photoId -> revealSyncedPhoto(photoId) }
            }
        }
    }

    private suspend fun revealSyncedPhoto(photoId: Long) {
        binding.transferStatusCard.visibility = View.GONE
        val entity = withContext(Dispatchers.IO) { dao.getById(photoId) }
        if (entity != null) onPhotoRevealed(entity.path)
    }

    private fun observeBatchBake(controller: BatchBakeController) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.progress.collect { progress -> renderBatchBake(progress) }
            }
        }
        binding.batchBakeCancelButton.setOnClickListener { controller.cancel() }
    }

    /**
     * Paints the top-right batch card.
     *
     * Hidden when no queue exists, visible while one runs, and dismissed by the controller once the
     * last photo is published so it does not cover the gallery forever.
     */
    private fun renderBatchBake(progress: BatchBakeProgress?) {
        if (progress == null) {
            binding.batchBakeStatusCard.visibility = View.GONE
            return
        }
        binding.batchBakeStatusCard.visibility = View.VISIBLE
        binding.batchBakeProgressText.text = context.getString(
            captionRes(progress.state),
            progress.done,
            progress.total
        )
        binding.batchBakeProgressBar.progress = (progress.ratio * MAX_PROGRESS).toInt()
        binding.batchBakeCancelButton.visibility =
            if (progress.running) View.VISIBLE else View.GONE
    }

    private fun captionRes(state: BatchBakeState): Int = when (state) {
        BatchBakeState.PREPARING -> R.string.batch_bake_preparing
        BatchBakeState.RUNNING -> R.string.batch_bake_running
        BatchBakeState.FINISHED -> R.string.batch_bake_finished
        BatchBakeState.CANCELLED -> R.string.batch_bake_cancelled
    }

    private fun setLiveTetherPreset(presetId: String?) {
        val store = EditorHostRegistry.editStore
        if (store == null || (presetId == null && store.getLiveTetherPresetId() == null)) {
            onFeedback(context.getString(R.string.quick_strip_live_tether_needs_preset))
            return
        }
        store.setLiveTetherPresetId(presetId)
        val messageRes = if (presetId == null) {
            R.string.quick_strip_live_tether_off
        } else {
            R.string.quick_strip_live_tether_on
        }
        onFeedback(context.getString(messageRes))
    }

    private fun onFilmstripSelectionChanged(selection: Set<Long>) {
        if (selection.isEmpty() && !filmstripAdapter.selectionMode) {
            quickStrip.renderSelectionMode(false)
        } else {
            quickStrip.renderSelectionMode(true)
            onFeedback(context.getString(R.string.quick_strip_selected_count, selection.size))
        }
    }

    private inner class QuickStripHost : TetheredQuickStripController.Host {
        override fun onQuickPresetSelected(photoId: Long, graph: EditGraph) =
            darkroom.applyPreset(photoId, graph)

        override fun onLiveTetherToggled(presetId: String?) = setLiveTetherPreset(presetId)

        override fun onQuickCopyRequested() = darkroom.copyCurrent(includeChecklist = false)

        override fun onQuickCopyWithMaskRequested() = darkroom.copyCurrent(includeChecklist = true)

        override fun onQuickPasteRequested() = darkroom.paste()

        override fun onMultiSelectToggled() = darkroom.toggleMultiSelect()

        override fun currentGraph(): EditGraph = darkroom.currentGraph()

        override fun currentPhotoId(): Long? = darkroom.currentPhotoId()
    }

    /** Public entry for the filmstrip's selection callback. */
    fun handleSelectionChanged(selection: Set<Long>) = onFilmstripSelectionChanged(selection)

    private companion object {
        const val MAX_PROGRESS = 100
    }
}
