package cn.devcxl.photosync.editor.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.render.EditorPreviewSession

/**
 * Restores everything the editor needs before the first frame: the persisted [EditGraph], the source
 * textures and the liquify displacement grid.
 *
 * A liquify reference whose payload is missing or fails its SHA-256 check is dropped instead of
 * rendering a warp the user cannot see or repair — the same "source missing" degradation the spec
 * asks for.
 */
internal class EditorSessionLoader(
    private val photoId: Long,
    private val previewSession: EditorPreviewSession,
    private val liquifySession: LiquifySession
) {

    /**
     * The state restored by [load].
     *
     * @property graph The graph to adopt, already stripped of a stale liquify reference.
     * @property hasSource False when the photo's pixels could not be decoded at all.
     */
    internal data class LoadedState(
        val graph: EditGraph,
        val hasSource: Boolean
    )

    /**
     * Loads the graph, the source textures and the liquify mesh. Switches to `Dispatchers.IO`, so it
     * is safe to await from a lifecycle coroutine.
     */
    suspend fun load(sourcePath: String, isRaw: Boolean, preferLinear: Boolean): LoadedState =
        withContext(Dispatchers.IO) {
            val stored = readPersistedGraph()
            val source = previewSession.loadSource(
                sourcePath = sourcePath,
                isRaw = isRaw,
                preferLinear = preferLinear
            )
            val graph = dropMissingArtifacts(stored)
            LoadedState(graph = graph, hasSource = source != null)
        }

    /**
     * Drops references whose payload no longer exists: a liquify mesh that fails its SHA-256 check
     * and a `.cube` LUT whose pre-compiled cache was cleared.
     */
    private fun dropMissingArtifacts(graph: EditGraph): EditGraph {
        var adopted = graph
        val reference = graph.geometry.liquify
        val meshRestored = reference != null && liquifySession.load(reference)
        if (reference != null && !meshRestored) {
            Timber.w("Liquify mesh missing or corrupt for photoId=%d; clearing the reference", photoId)
            adopted = adopted.copy(geometry = adopted.geometry.copy(liquify = null))
        }
        if (!previewSession.preloadLut(adopted)) {
            Timber.w("3D LUT cache missing for photoId=%d; clearing the reference", photoId)
            adopted = adopted.copy(color = adopted.color.copy(lut = null))
            previewSession.preloadLut(adopted)
        }
        return adopted
    }

    private suspend fun readPersistedGraph(): EditGraph {
        val existingRecord = EditorHostRegistry.editStore?.getEditForPhoto(photoId)
        return existingRecord?.graphJson?.let { json ->
            EditGraphJsonCodec.decode(json) ?: run {
                Timber.w("Corrupt EditGraph JSON for photoId=%d; resetting to neutral", photoId)
                EditGraph()
            }
        } ?: EditGraph()
    }
}
