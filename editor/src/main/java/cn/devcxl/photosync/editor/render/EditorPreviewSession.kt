package cn.devcxl.photosync.editor.render

import java.io.File

import timber.log.Timber

import cn.devcxl.photosync.editor.contract.EditorCachePaths
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.lut.CubeLutStore

/**
 * Owns the editor's preview textures, the imported 3D LUT and preview-cache rendering.
 *
 * Keeping this outside [cn.devcxl.photosync.editor.ui.EditorActivity] lets the activity stay a
 * thin controller: it delegates source loading, renderer state and cache writing here.
 */
internal class EditorPreviewSession(
    private val renderer: EditorGlRenderer,
    private val cacheDir: File,
    private val photoId: Long,
    private val lutStore: CubeLutStore,
    private val filesDir: File
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

    /**
     * Decodes the 3D LUT referenced by [graph] into the memory cache. Blocking: call from an IO
     * dispatcher before the first preview frame so [applyGraph] never reads disk on the UI thread.
     *
     * @return True when no LUT is referenced or the referenced cache exists and decoded.
     */
    fun preloadLut(graph: EditGraph): Boolean {
        val reference = graph.color.lut ?: return true
        val loaded = lutStore.loadSamples(reference.id)
        if (loaded.isFailure) {
            Timber.w("The referenced 3D LUT is unavailable; rendering without it")
            renderer.setLut3d(null)
            return false
        }
        renderer.setLut3d(loaded.getOrNull())
        return true
    }

    /**
     * Pushes the parameter state a preview draw depends on into [renderer].
     *
     * @return True if any referenced 3D LUT is already warm in memory (or none is set); false when
     *   the caller should asynchronously call [preloadLut] and re-request a render.
     */
    fun applyGraph(graph: EditGraph, useProxy: Boolean = false): Boolean {
        val lut = resolveCachedLut(graph)
        renderer.setLut3d(lut)
        renderer.applyState(graph, useFastProxy = useProxy)
        return graph.color.lut == null || lut != null
    }

    /** Stages the liquify displacement grid the canvas must warp by. */
    fun applyLiquifyMesh(mesh: LiquifyMesh?) {
        renderer.setLiquifyMesh(mesh)
    }

    /** Stages an already-built subject composite for the next canvas frame. */
    fun applySubjectComposite(composite: SubjectComposite?) {
        renderer.setSubjectComposite(composite)
    }

    /**
     * Loads the mask referenced by [graph] and builds its composite. Blocking: call from an IO
     * dispatcher before requesting the next frame.
     */
    fun resolveSubjectComposite(graph: EditGraph): SubjectComposite? {
        val current = source ?: return null
        return SubjectCompositeLoader.resolve(graph, current, filesDir)
    }

    /**
     * Renders the screen-sized preview cache for [graph]. Blocking: call from an IO dispatcher.
     *
     * Tries the offscreen GPU chain first — synchronously on this thread, with its own headless EGL
     * context, so the session's source pixels are never shared with another thread — and falls back to
     * [PreviewCacheWriter] (the CPU reference implementation) whenever the GPU cannot reproduce the
     * graph exactly.
     *
     * @param warpField Composed displacement grid (face MLS + body + liquify) so the cache matches
     *   the canvas exactly.
     * @param skinMask Portrait analysis mask, or `null` when the portrait group is unused.
     * @return Absolute path of the written JPEG, or `null` when nothing was written.
     */
    fun writePreviewCache(
        graph: EditGraph,
        warpField: WarpDisplacementField? = null,
        skinMask: FloatArray? = null
    ): String? {
        val current = source ?: return null
        val lut = loadLutFor(graph)
        if (!PreviewGraphAssets.isComplete(graph, lut)) {
            // Publishing without the LUT would commit a preview that shows a different look.
            Timber.e("The referenced 3D LUT is unavailable; keeping the previous preview")
            return null
        }
        val subject = SubjectCompositeLoader.resolve(graph, current, filesDir)
        val accelerated = OffscreenPreviewRenderer.renderNow(
            OffscreenPreviewRequest(
                source = current,
                graph = graph,
                warpField = warpField,
                skinMask = skinMask,
                lut = lut,
                outDir = File(cacheDir, EditorCachePaths.photoEditDirName(photoId)),
                subject = subject
            )
        )
        if (accelerated != null) return accelerated.absolutePath
        return when (current) {
            is EditorSource.LinearRawSource -> PreviewCacheWriter.writePreviewCache(
                source = current.full,
                graph = graph,
                cacheDir = cacheDir,
                photoId = photoId,
                warpField = warpField,
                lut = lut,
                skinMask = skinMask,
                subject = subject
            )
            is EditorSource.BitmapSource -> PreviewCacheWriter.writePreviewCache(
                source = current.full,
                graph = graph,
                cacheDir = cacheDir,
                photoId = photoId,
                warpField = warpField,
                lut = lut,
                skinMask = skinMask,
                subject = subject
            )
        }
    }

    /** Releases staged textures and recycles any bitmaps owned by the source. */
    fun release() {
        source?.release()
        source = null
        renderer.releaseSource()
    }

    private fun resolveCachedLut(graph: EditGraph): Lut3dSamples? =
        graph.color.lut?.let { lutStore.cachedSamplesIf(it.id) }

    private fun loadLutFor(graph: EditGraph): Lut3dSamples? {
        val reference = graph.color.lut ?: return null
        return lutStore.loadSamples(reference.id).getOrNull()
    }
}
