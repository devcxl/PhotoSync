package cn.devcxl.photosync.activity

import java.io.IOException

import android.content.Context

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.R
import cn.devcxl.photosync.adapter.FilmstripAdapter
import cn.devcxl.photosync.adapter.PhotoPagerAdapter
import cn.devcxl.photosync.data.PhotoDao
import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.contract.PhotoEditRecord
import cn.devcxl.photosync.editor.domain.CopyMask
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.domain.LiquifyMeshCopier
import cn.devcxl.photosync.editor.render.BackgroundPreviewRenderer
import cn.devcxl.photosync.export.BatchBakeController
import cn.devcxl.photosync.utils.ExtensionUtils

/**
 * Implements the quick strip's actions for `MainActivity`.
 *
 * Applies presets, pastes copied parameters (cloning liquify meshes when needed) with background
 * preview generation, toggles multi-select and enqueues batch bakes.
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
     * Renders the preview cache and persists [graph] for [photoId].
     */
    fun applyPreset(photoId: Long, graph: EditGraph) {
        persistWithRenderedPreview(photoId, graph)
    }

    /**
     * Copies the current photo's look, or reports when the photo was never edited.
     */
    fun copyCurrent(includeChecklist: Boolean) {
        val photoId = currentPhotoId()
        val graph = photoId?.let { graphOf(it) }
        if (graph == null) {
            onFeedback(context.getString(R.string.quick_strip_copy_unedited))
            return
        }
        copyPasteHelper.copy(graph, photoId, includeChecklist) { mask ->
            val message = if (mask == CopyMask()) {
                R.string.quick_strip_copied
            } else {
                R.string.quick_strip_copied_masked
            }
            onFeedback(context.getString(message))
            quickStrip.onClipboardChanged()
        }
    }

    /**
     * Pastes the clipboard onto the current photo, or onto every selected photo in multi-select.
     */
    fun paste() {
        if (!EditClipboard.hasContent) {
            onFeedback(context.getString(R.string.quick_strip_paste_empty))
            return
        }
        val targets = pasteTargets()
        if (targets.isEmpty()) {
            onFeedback(context.getString(R.string.batch_bake_no_selection))
            return
        }
        val changed = copyPasteHelper.paste(targets) { photoId, graph, originId, mask ->
            persistPasted(photoId, graph, originId, mask)
        }
        val msg = if (changed > 0) {
            context.getString(R.string.quick_strip_pasted, changed)
        } else {
            context.getString(R.string.quick_strip_paste_unchanged)
        }
        onFeedback(msg)
        quickStrip.onClipboardChanged()
    }

    /** Toggles multi-select and reports the new mode so the filmstrip can paint it. */
    fun toggleMultiSelect() {
        if (filmstripAdapter.selectionMode) {
            leaveSelectionMode()
        } else {
            val entity = adapter.getItem(currentIndex())
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
            onFeedback(context.getString(R.string.batch_bake_no_selection))
            return
        }
        batchBake.enqueue(ids)
        onFeedback(context.getString(R.string.batch_bake_queued, ids.size))
        if (filmstripAdapter.selectionMode) {
            leaveSelectionMode()
        }
    }

    private fun leaveSelectionMode() {
        filmstripAdapter.exitSelectionMode()
        quickStrip.renderSelectionMode(false)
    }

    private fun pasteTargets(): Map<Long, EditGraph> {
        if (filmstripAdapter.selectionMode) {
            return filmstripAdapter.selectedPhotoIds.associateWith { id -> graphOf(id) ?: EditGraph() }
        }
        val entity = adapter.getItem(currentIndex()) ?: return emptyMap()
        return mapOf(entity.id to (graphOf(entity.id) ?: EditGraph()))
    }

    private fun persistPasted(
        photoId: Long,
        graph: EditGraph,
        originPhotoId: Long?,
        mask: CopyMask
    ) {
        val needsClone = mask.includeLiquify &&
            graph.geometry.liquify != null &&
            originPhotoId != photoId
        persistWithRenderedPreview(photoId, graph, cloneLiquify = needsClone)
    }

    private fun persistWithRenderedPreview(
        photoId: Long,
        graph: EditGraph,
        cloneLiquify: Boolean = false
    ) {
        val currentStore = store ?: return
        scope.launch {
            val saved = saveOnIo(currentStore, photoId, graph, cloneLiquify)
            withContext(Dispatchers.Main) {
                if (saved) {
                    onGraphPersisted(photoId)
                } else {
                    onFeedback(context.getString(R.string.toast_save_failed))
                }
            }
        }
    }

    private suspend fun saveOnIo(
        currentStore: EditStore,
        photoId: Long,
        rawGraph: EditGraph,
        cloneLiquify: Boolean
    ): Boolean = try {
        val entity = withContext(Dispatchers.IO) { dao.getById(photoId) } ?: return false
        val graph = if (cloneLiquify) {
            withContext(Dispatchers.IO) { LiquifyMeshCopier.cloneForTarget(context.filesDir, photoId, rawGraph) }
        } else {
            rawGraph
        }
        val previewPath = if (graph.isIdentity()) {
            null
        } else {
            val isRaw = ExtensionUtils.isRawExtension(ExtensionUtils.fileExtension(entity.path))
            BackgroundPreviewRenderer.render(
                context = context,
                filesDir = context.filesDir,
                cacheDir = context.cacheDir,
                photoId = photoId,
                sourcePath = entity.path,
                isRaw = isRaw,
                graph = graph
            ) ?: return false
        }
        withContext(Dispatchers.IO) {
            currentStore.saveEditForPhoto(photoId, graph, previewCachePath = previewPath)
        }
        true
    } catch (io: IOException) {
        Timber.e(io, "Failed to persist gallery edit for photoId=%d", photoId)
        false
    } catch (error: RuntimeException) {
        Timber.e(error, "Database failure persisting gallery edit for photoId=%d", photoId)
        false
    }

    private fun graphOf(photoId: Long): EditGraph? {
        val json = editedMap()[photoId]?.graphJson ?: return null
        return EditGraphJsonCodec.decode(json)
    }
}
