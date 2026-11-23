package cn.devcxl.photosync.activity

import java.io.IOException

import android.content.Context

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.R
import cn.devcxl.photosync.adapter.PhotoPagerAdapter
import cn.devcxl.photosync.data.PhotoDao
import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.contract.PhotoEditRecord
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.render.BackgroundPreviewRenderer
import cn.devcxl.photosync.utils.ExtensionUtils

/**
 * Executes quick-strip preset applications for `MainActivity`, keeping an inline spinner visible on
 * the tapped chip while the background preview renders.
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

    /** Current photo path, or `null` when the library is empty. */
    fun currentPhotoPath(): String? = adapter.getItem(currentIndex())?.path

    /** True when the current photo has a rendered edited preview to compare against. */
    fun canCompareCurrent(): Boolean {
        val entity = adapter.getItem(currentIndex()) ?: return false
        return resolveEffectivePreviewPath(entity, editedMap()) != entity.path
    }

    /**
     * Renders the preview cache and persists [graph] for [photoId], displaying a spinner inside the
     * tapped chip until the render finishes.
     */
    fun applyPreset(photoId: Long, graph: EditGraph, presetId: String?, presetName: String?) {
        val currentStore = store ?: return
        val successMessage = if (presetName != null) {
            context.getString(R.string.quick_strip_preset_applied_named, presetName)
        } else {
            context.getString(R.string.quick_strip_preset_cleared)
        }
        quickStrip.setApplyingPreset(active = true, presetId = presetId)
        scope.launch {
            val saved = saveOnIo(currentStore, photoId, graph)
            withContext(Dispatchers.Main) {
                quickStrip.setApplyingPreset(active = false, presetId = null)
                if (saved) {
                    onGraphPersisted(photoId)
                    onFeedback(successMessage)
                } else {
                    onFeedback(context.getString(R.string.quick_strip_apply_failed))
                }
            }
        }
    }

    private suspend fun saveOnIo(
        currentStore: EditStore,
        photoId: Long,
        graph: EditGraph
    ): Boolean = try {
        val entity = withContext(Dispatchers.IO) { dao.getById(photoId) } ?: return false
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
