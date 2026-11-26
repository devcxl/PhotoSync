package cn.devcxl.photosync.editor.render

import java.io.File

import timber.log.Timber

import cn.devcxl.photosync.editor.contract.EditorCachePaths
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.lut.CubeLutStore

/**
 * Renders the screen-sized JPEG preview cache under `cacheDir/edits/<photoId>/`.
 *
 * Extracted from [EditorPreviewSession] so the session owns source/inpaint state only, while the
 * GPU-first/CPU-fallback render policy and its asset preconditions live in one place. Every call
 * must run on an IO dispatcher.
 */
internal class PreviewCacheRenderer(
    private val cacheDir: File,
    private val photoId: Long,
    private val lutStore: CubeLutStore,
    private val filesDir: File
) {

    /**
     * Renders [graph] from the untouched decode [current].
     *
     * @param warpField Composed displacement grid (face MLS + body + liquify) so the cache matches
     *   the canvas exactly.
     * @param skinMask Portrait analysis mask, or `null` when the portrait group is unused.
     * @return Absolute path of the written JPEG, or `null` when nothing was written.
     */
    fun render(
        current: EditorSource,
        graph: EditGraph,
        warpField: WarpDisplacementField?,
        skinMask: FloatArray?
    ): String? {
        val lut = loadLutFor(graph)
        if (!PreviewGraphAssets.isComplete(graph, lut)) {
            // Publishing without the LUT would commit a preview that shows a different look.
            Timber.e("The referenced 3D LUT is unavailable; keeping the previous preview")
            return null
        }
        // The cache renders from the untouched decode; both renderers compose the patches into a
        // private copy, so a repeated save can never stack the same patch twice.
        val inpaint = InpaintOverlay.of(filesDir, graph)
        val subject = SubjectCompositeLoader.resolve(graph, current, filesDir, inpaint)
        val sky = resolveSky(current, graph)
        val accelerated = renderAccelerated(current, graph, warpField, skinMask, lut, subject, inpaint, sky)
        if (accelerated != null) return accelerated.absolutePath
        return writeCpuFallback(current, graph, warpField, skinMask, lut, subject, inpaint, sky)
    }

    private fun renderAccelerated(
        current: EditorSource,
        graph: EditGraph,
        warpField: WarpDisplacementField?,
        skinMask: FloatArray?,
        lut: Lut3dSamples?,
        subject: SubjectComposite?,
        inpaint: InpaintOverlay?,
        sky: SkyLayerPixels?
    ): File? = OffscreenPreviewRenderer.renderNow(
        OffscreenPreviewRequest(
            source = current,
            graph = graph,
            warpField = warpField,
            skinMask = skinMask,
            lut = lut,
            outDir = File(cacheDir, EditorCachePaths.photoEditDirName(photoId)),
            subject = subject,
            inpaint = inpaint,
            cloudAssets = AiAssetStore(filesDir),
            sky = sky
        )
    )

    private fun writeCpuFallback(
        current: EditorSource,
        graph: EditGraph,
        warpField: WarpDisplacementField?,
        skinMask: FloatArray?,
        lut: Lut3dSamples?,
        subject: SubjectComposite?,
        inpaint: InpaintOverlay?,
        sky: SkyLayerPixels?
    ): String? = when (current) {
        is EditorSource.LinearRawSource -> PreviewCacheWriter.writePreviewCache(
            source = current.full,
            graph = graph,
            cacheDir = cacheDir,
            photoId = photoId,
            warpField = warpField,
            lut = lut,
            skinMask = skinMask,
            subject = subject,
            inpaint = inpaint,
            cloudAssets = AiAssetStore(filesDir),
            sky = sky
        )
        is EditorSource.BitmapSource -> PreviewCacheWriter.writePreviewCache(
            source = current.full,
            graph = graph,
            cacheDir = cacheDir,
            photoId = photoId,
            warpField = warpField,
            lut = lut,
            skinMask = skinMask,
            subject = subject,
            inpaint = inpaint,
            cloudAssets = AiAssetStore(filesDir),
            sky = sky
        )
    }

    /**
     * Builds the graph's sky layer at the preview size, or `null` when the group is neutral.
     *
     * The mask and the asset are decoded once per save; the shared Pre-pass delta is computed from
     * the same `1024px` proxy the editor used, so a saved preview matches the canvas.
     */
    private fun resolveSky(current: EditorSource, graph: EditGraph): SkyLayerPixels? {
        if (graph.sky.isIdentity()) return null
        val sourceAspect = current.width.toFloat() / current.height.toFloat()
        val aspect = GeometryMath.outputAspect(graph.geometry, sourceAspect)
        val (width, height) = PreviewCacheWriter.previewSize(aspect)
        return SkyPreparer.prepare(current, graph, filesDir, width, height)
    }

    private fun loadLutFor(graph: EditGraph): Lut3dSamples? {
        val reference = graph.color.lut ?: return null
        return lutStore.loadSamples(reference.id).getOrNull()
    }
}
