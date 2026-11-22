package cn.devcxl.photosync.editor.render

import java.io.File

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LiquifyMesh

/**
 * Owns the editor's preview textures and preview-cache rendering.
 *
 * Keeping this outside [cn.devcxl.photosync.editor.ui.EditorActivity] lets the activity stay a
 * thin controller: it delegates source loading, renderer state and cache writing here.
 */
internal class EditorPreviewSession(
    private val renderer: EditorGlRenderer,
    private val cacheDir: File,
    private val photoId: Long
) {

    var source: EditorSource? = null
        private set

    /**
     * True when the staged source is a 16-bit linear RAW buffer, i.e. the `BaseLook` toggle can
     * change the rendered baseline. 8-bit fallbacks already carry the browse look baked in.
     */
    val supportsBaseLook: Boolean
        get() = source?.isLinear == true

    /**
     * Loads and stages the source textures. Blocking: call from an IO dispatcher.
     *
     * @return The loaded source, or `null` when the file cannot be decoded.
     */
    fun loadSource(sourcePath: String, isRaw: Boolean, preferLinear: Boolean): EditorSource? {
        val loaded = EditorSourceLoader.load(sourcePath, isRaw, preferLinear)
        source = loaded
        if (loaded != null) {
            renderer.setSource(loaded)
        }
        return loaded
    }

    /** Pushes the parameter state a preview draw depends on into [renderer]. */
    fun applyGraph(graph: EditGraph, useProxy: Boolean = false) {
        renderer.applyState(graph, useFastProxy = useProxy)
    }

    /** Stages the liquify displacement grid the canvas must warp by. */
    fun applyLiquifyMesh(mesh: LiquifyMesh?) {
        renderer.setLiquifyMesh(mesh)
    }

    /**
     * Renders the screen-sized preview cache for [graph]. Blocking: call from an IO dispatcher.
     *
     * @param liquifyMesh The photo's displacement grid so the cache matches the canvas exactly.
     * @return Absolute path of the written JPEG, or `null` when nothing was written.
     */
    fun writePreviewCache(graph: EditGraph, liquifyMesh: LiquifyMesh? = null): String? =
        when (val current = source) {
            null -> null
            is EditorSource.LinearRawSource -> PreviewCacheWriter.writePreviewCache(
                source = current.full,
                graph = graph,
                cacheDir = cacheDir,
                photoId = photoId,
                liquifyMesh = liquifyMesh
            )
            is EditorSource.BitmapSource -> PreviewCacheWriter.writePreviewCache(
                source = current.full,
                graph = graph,
                cacheDir = cacheDir,
                photoId = photoId,
                liquifyMesh = liquifyMesh
            )
        }

    /** Releases staged textures and recycles any bitmaps owned by the source. */
    fun release() {
        source?.release()
        source = null
        renderer.releaseSource()
    }
}
