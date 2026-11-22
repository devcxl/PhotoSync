package cn.devcxl.photosync.activity

import android.content.Context

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.adapter.FilmstripAdapter
import cn.devcxl.photosync.adapter.PhotoPagerAdapter
import cn.devcxl.photosync.data.PhotoDao
import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.contract.PhotoEditRecord
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.export.BatchBakeController
import cn.devcxl.photosync.utils.ExtensionUtils

/**
 * Implements the quick strip's actions for `MainActivity`.
 *
 * Everything the strip can do — apply a preset to the pager's current photo, toggle the
 * `LiveTetherPreset`, copy/paste parameters onto one or many photos, enter multi-select and enqueue a
 * batch bake — lives here, so the activity keeps only view plumbing and stays readable.
 */
internal class TetheredDarkroomHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dao: PhotoDao,
    private val adapter: PhotoPagerAdapter,
    private val filmstripAdapter: FilmstripAdapter,
    private val quickStrip: TetheredQuickStripController,
    private val batchBake: BatchBakeController,
    private val copyPasteHelper: MainActivityCopyPasteHelper,
    private val currentIndex: () -> Int,
    private val editedMap: () -> Map<Long, PhotoEditRecord>,
    private val onGraphPersisted: (Long) -> Unit,
    private val onFeedback: (String) -> Unit
) {

    private val store: EditStore?
        get() = EditorHostRegistry.editStore

    /** The graph shown for the pager's current page, or a neutral one when unedited. */
    fun currentGraph(): EditGraph {
        val entity = adapter.getItem(currentIndex()) ?: return EditGraph()
        return graphOf(entity.id) ?: EditGraph()
    }

    /** Current photo id, or `null` when the library is empty. */
    fun currentPhotoId(): Long? = adapter.getItem(currentIndex())?.id

    /**
     * Persists [graph] for [photoId] and refreshes the chrome.
     *
     * A preset applied from the gallery must be written straight away: the pager only renders the
     * preview cache path the store knows about.
     */
    fun applyPreset(photoId: Long, graph: EditGraph) {
        val currentStore = store ?: return
        scope.launch {
            try {
                currentStore.saveEditForPhoto(photoId, graph, previewCachePath = null)
                withContext(Dispatchers.Main) { onGraphPersisted(photoId) }
            } catch (io: java.io.IOException) {
                Timber.e(io, "Failed to persist a quick preset for photoId=%d", photoId)
            }
        }
    }

    /**
     * Copies the current photo's look.
     *
     * The gallery does not carry a full editor session, so the copy uses the persisted graph and the
     * request is ignored when the photo was never edited.
     */
    fun copyCurrent(includeChecklist: Boolean) {
        val photoId = currentPhotoId()
        val graph = photoId?.let { graphOf(it) }
        if (graph == null) {
            onFeedback(context.getString(cn.devcxl.photosync.R.string.quick_strip_paste_empty))
            return
        }
        copyPasteHelper.copy(graph, includeChecklist) { mask ->
            val message = if (mask == cn.devcxl.photosync.editor.domain.CopyMask()) {
                cn.devcxl.photosync.R.string.quick_strip_copied
            } else {
                cn.devcxl.photosync.R.string.quick_strip_copied_masked
            }
            onFeedback(context.getString(message))
            quickStrip.onClipboardChanged()
        }
    }

    /**
     * Pastes the clipboard onto the current photo, or onto every selected photo in multi-select.
     */
    fun paste() {
        val targets = pasteTargets()
        if (targets.isEmpty()) {
            onFeedback(context.getString(cn.devcxl.photosync.R.string.quick_strip_paste_empty))
            return
        }
        val changed = copyPasteHelper.paste(targets) { photoId, graph -> persistPasted(photoId, graph) }
        val message = context.getString(cn.devcxl.photosync.R.string.quick_strip_pasted, changed)
        onFeedback(if (changed > 0) message else context.getString(
            cn.devcxl.photosync.R.string.quick_strip_paste_empty
        ))
        quickStrip.onClipboardChanged()
    }

    /** Toggles multi-select and reports the new mode so the filmstrip can paint it. */
    fun toggleMultiSelect() {
        if (filmstripAdapter.selectionMode) {
            filmstripAdapter.exitSelectionMode()
            quickStrip.renderSelectionMode(false)
        } else {
            // Entering from the strip selects the current photo so the batch is never empty on entry.
            val index = currentIndex()
            val entity = adapter.getItem(index)
            if (entity != null) {
                filmstripAdapter.enterSelectionModeFor(entity.id)
            }
            quickStrip.renderSelectionMode(true)
        }
    }

    /** Enqueues every selected photo; falls back to the current one when nothing is selected. */
    fun enqueueBatchBake() {
        val ids = filmstripAdapter.selectedPhotoIds.toList().ifEmpty {
            listOfNotNull(currentPhotoId())
        }
        if (ids.isEmpty()) {
            onFeedback(context.getString(cn.devcxl.photosync.R.string.batch_bake_no_selection))
            return
        }
        batchBake.enqueue(ids)
        onFeedback(context.getString(cn.devcxl.photosync.R.string.batch_bake_queued, ids.size))
        filmstripAdapter.clearSelection()
    }

    /** Ends multi-select after a batch action so the strip returns to paging mode. */
    fun leaveSelectionMode() {
        filmstripAdapter.exitSelectionMode()
        quickStrip.renderSelectionMode(false)
    }

    private fun pasteTargets(): Map<Long, EditGraph> {
        if (filmstripAdapter.selectionMode) {
            return filmstripAdapter.selectedPhotoIds.mapNotNull { id ->
                graphOf(id)?.let { id to it }
            }.toMap()
        }
        val entity = adapter.getItem(currentIndex()) ?: return emptyMap()
        val graph = graphOf(entity.id) ?: EditGraph()
        return mapOf(entity.id to graph)
    }

    private fun persistPasted(photoId: Long, graph: EditGraph) {
        val currentStore = store ?: return
        scope.launch {
            try {
                currentStore.saveEditForPhoto(photoId, graph, previewCachePath = null)
                withContext(Dispatchers.Main) { onGraphPersisted(photoId) }
            } catch (io: java.io.IOException) {
                Timber.e(io, "Failed to paste parameters onto photoId=%d", photoId)
            }
        }
    }

    private fun graphOf(photoId: Long): EditGraph? {
        val json = editedMap()[photoId]?.graphJson ?: return null
        return EditGraphJsonCodec.decode(json)
    }

    /** True when [path] is a RAW file this pipeline can decode. */
    fun isRaw(path: String): Boolean =
        ExtensionUtils.isRawExtension(ExtensionUtils.fileExtension(path))
}
