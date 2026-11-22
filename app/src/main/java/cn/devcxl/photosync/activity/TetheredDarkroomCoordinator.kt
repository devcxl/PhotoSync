package cn.devcxl.photosync.activity

import android.content.Context
import android.graphics.Bitmap
import android.view.View

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import cn.devcxl.photosync.App
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
 * status card and the press-and-hold `Before` comparison.
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
    private val decodeOriginal: (String) -> Bitmap?,
    private val onEditPersisted: (Long) -> Unit,
    private val onFeedback: (String) -> Unit
) {

    private lateinit var quickStrip: TetheredQuickStripController
    private lateinit var darkroom: TetheredDarkroomHost
    private lateinit var batchBake: BatchBakeController
    private lateinit var compare: BeforeAfterCompareController
    private lateinit var copyPaste: MainActivityCopyPasteHelper
    private var autoDismissJob: Job? = null

    /**
     * Builds every controller and subscribes the WorkManager queue.
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

    private fun observeBatchBake(controller: BatchBakeController) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.progress.collect { progress -> renderBatchBake(progress) }
            }
        }
        binding.batchBakeCancelButton.setOnClickListener { controller.cancel() }
    }

    private fun renderBatchBake(progress: BatchBakeProgress?) {
        autoDismissJob?.cancel()
        if (progress == null) {
            binding.batchBakeStatusCard.visibility = View.GONE
            return
        }
        binding.batchBakeStatusCard.visibility = View.VISIBLE
        binding.batchBakeProgressText.text = formatBatchCaption(progress)
        binding.batchBakeProgressBar.progress = (progress.ratio * MAX_PROGRESS).toInt()
        binding.batchBakeCancelButton.visibility =
            if (progress.running) View.VISIBLE else View.GONE
        if (!progress.running) {
            autoDismissJob = owner.lifecycleScope.launch {
                delay(TERMINAL_CARD_DISMISS_MS)
                batchBake.dismissTerminal()
            }
        }
    }

    private fun formatBatchCaption(progress: BatchBakeProgress): String = when (progress.state) {
        BatchBakeState.PREPARING -> context.getString(R.string.batch_bake_preparing)
        BatchBakeState.RUNNING -> context.getString(
            R.string.batch_bake_running,
            progress.done,
            progress.total
        )
        BatchBakeState.FINISHED -> if (progress.failed > 0) {
            context.getString(
                R.string.batch_bake_finished_with_failures,
                progress.done,
                progress.total,
                progress.failed
            )
        } else {
            context.getString(R.string.batch_bake_finished, progress.done, progress.total)
        }
        BatchBakeState.CANCELLED -> context.getString(R.string.batch_bake_cancelled)
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

    /** Public entry for the filmstrip's selection callback. */
    fun handleSelectionChanged(selection: Set<Long>) {
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

    private companion object {
        const val MAX_PROGRESS = 100
        const val TERMINAL_CARD_DISMISS_MS = 2500L
    }
}
