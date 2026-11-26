package cn.devcxl.photosync.activity

import java.io.IOException

import android.content.Context

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.R
import cn.devcxl.photosync.adapter.PhotoPagerAdapter
import cn.devcxl.photosync.data.PhotoDao
import cn.devcxl.photosync.data.entity.PhotoEntity
import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.contract.PhotoEditRecord
import cn.devcxl.photosync.editor.domain.AiAssetKind
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.render.BackgroundPreviewRenderer
import cn.devcxl.photosync.editor.render.TetheredSubjectPresetPreparer
import cn.devcxl.photosync.utils.ExtensionUtils

/**
 * Executes unified preset selections for `MainActivity`: updates the active tethered preset so new
 * incoming photos automatically match, and renders the current photo with an inline chip spinner.
 */
internal class TetheredDarkroomHost(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dao: PhotoDao,
    private val adapter: PhotoPagerAdapter,
    private val quickStrip: TetheredQuickStripController,
    private val currentIndex: () -> Int,
    private val editedMap: () -> Map<Long, PhotoEditRecord>,
    private val onGraphPersisted: (Long) -> Unit,
    private val onFeedback: (String) -> Unit,
    private val subjectPresetPreparer: TetheredSubjectPresetPreparer? = null
) {

    private val store: EditStore?
        get() = EditorHostRegistry.editStore

    /** The portrait prewarm running for the preset strip, cancelled when the strip reopens. */
    private var prewarmJob: Job? = null

    /** The graph shown for the pager's current page, or a neutral one when unedited. */
    fun currentGraph(): EditGraph {
        val entity = adapter.getItem(currentIndex()) ?: return EditGraph()
        return graphOf(entity.id) ?: EditGraph()
    }

    /** Current photo id, or `null` when the library is empty. */
    fun currentPhotoId(): Long? = adapter.getItem(currentIndex())?.id

    /** Current photo path, or `null` when the library is empty. */
    fun currentPhotoPath(): String? = adapter.getItem(currentIndex())?.path

    /** True when the current photo has a rendered edited preview to compare against. */
    fun canCompareCurrent(): Boolean {
        val entity = adapter.getItem(currentIndex()) ?: return false
        return resolveEffectivePreviewPath(entity, editedMap()) != entity.path
    }

    /**
     * Activates [presetId] for tethered sync (or clears it when `null`), and renders [graph] onto
     * [photoId] when a photo is currently displayed.
     */
    fun selectPreset(photoId: Long?, graph: EditGraph?, presetId: String?, presetName: String?) {
        val currentStore = store ?: return
        currentStore.setLiveTetherPresetId(presetId)
        if (photoId == null || graph == null) {
            val message = if (presetName != null) {
                context.getString(R.string.quick_strip_preset_selected_tether_only, presetName)
            } else {
                context.getString(R.string.quick_strip_preset_cleared)
            }
            onFeedback(message)
            return
        }
        val successMessage = if (presetName != null) {
            context.getString(R.string.quick_strip_preset_applied_named, presetName)
        } else {
            context.getString(R.string.quick_strip_preset_cleared)
        }
        quickStrip.setApplyingPreset(active = true, presetId = presetId)
        scope.launch {
            try {
                val saved = saveOnIo(currentStore, photoId, graph)
                if (saved) {
                    onGraphPersisted(photoId)
                    onFeedback(successMessage)
                } else {
                    onFeedback(context.getString(R.string.quick_strip_apply_failed))
                }
            } finally {
                quickStrip.setApplyingPreset(active = false, presetId = null)
            }
        }
    }

    /**
     * Starts the portrait analysis for the photo on screen in the background.
     *
     * The preset strip calls this when it opens: browsing the chips takes long enough for the
     * MediaPipe pass and the source decode to finish, so tapping a preset that carries face or body
     * reshaping only waits for the render instead of the whole analysis.
     *
     * A newer request cancels the previous one: only the photo currently on screen is worth warming.
     */
    fun prewarmCurrentPortrait() {
        val entity = adapter.getItem(currentIndex()) ?: return
        prewarmJob?.cancel()
        prewarmJob = scope.launch {
            val isRaw = ExtensionUtils.isRawExtension(ExtensionUtils.fileExtension(entity.path))
            BackgroundPreviewRenderer.prewarmPortrait(
                context = context,
                filesDir = context.filesDir,
                sourcePath = entity.path,
                isRaw = isRaw
            )
        }
    }

    private suspend fun saveOnIo(
        currentStore: EditStore,
        photoId: Long,
        graph: EditGraph
    ): Boolean = try {
        val entity = withContext(Dispatchers.IO) { dao.getById(photoId) } ?: return false
        val effectiveGraph = withContext(Dispatchers.IO) {
            ensureSubjectMask(photoId, entity, graph)
        }
        val previewPath = if (effectiveGraph.isIdentity()) {
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
                graph = effectiveGraph
            ) ?: return false
        }
        withContext(Dispatchers.IO) {
            currentStore.saveEditForPhoto(photoId, effectiveGraph, previewCachePath = previewPath)
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (io: IOException) {
        Timber.e(io, "Failed to persist gallery edit for photoId=%d", photoId)
        false
    } catch (error: RuntimeException) {
        Timber.e(error, "Database failure persisting gallery edit for photoId=%d", photoId)
        false
    }

    /**
     * Ensures the target photo has its own subject mask before a preset that carries `SubjectOps`
     * is rendered and saved.
     *
     * A mask already referenced by the graph is kept. Otherwise the preparer runs MODNet once; when
     * the device is `<= 4 GiB`, the model is missing or inference fails, the graph silently degrades
     * to the traditional grade. The prepared payload is persisted under the same `photoId` the edit
     * row will use, so the stored `maskRef` is valid immediately.
     */
    private suspend fun ensureSubjectMask(
        photoId: Long,
        entity: PhotoEntity,
        graph: EditGraph
    ): EditGraph {
        if (!graph.subject.requiresMaskProcessing()) return graph
        if (graph.subject.maskRef.isNotBlank()) return graph
        val preparer = subjectPresetPreparer
        if (preparer == null) {
            Timber.i("No subject preparer; the gallery preset applies the traditional grade")
            return degradeSubject(graph)
        }
        val isRaw = ExtensionUtils.isRawExtension(ExtensionUtils.fileExtension(entity.path))
        val preparation = try {
            preparer.prepare(entity.path, isRaw, graph)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: RuntimeException) {
            Timber.w(error, "Subject preparation threw; applying the traditional grade")
            null
        }
        if (preparation == null) {
            Timber.i("Gallery preset subject effect degraded to the traditional grade")
            return degradeSubject(graph)
        }
        val reference = AiAssetStore(context.filesDir)
            .write(photoId, AiAssetKind.SUBJECT_MASK, preparation.payload)
        if (reference == null) {
            Timber.w("Gallery preset subject mask could not be stored; applying the traditional grade")
            return degradeSubject(graph)
        }
        return graph.copy(subject = graph.subject.copy(maskRef = reference.relativePath))
    }

    private fun degradeSubject(graph: EditGraph): EditGraph = graph.copy(subject = SubjectOps())

    private fun graphOf(photoId: Long): EditGraph? {
        val json = editedMap()[photoId]?.graphJson ?: return null
        return EditGraphJsonCodec.decode(json)
    }
}
