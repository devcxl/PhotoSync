package cn.devcxl.photosync.editor.render

import java.io.File

import android.graphics.Bitmap

import timber.log.Timber

import kotlin.math.roundToInt

import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.lut.CubeLutStore

/**
 * Owns the editor's preview textures, the imported 3D LUT and preview-cache rendering.
 *
 * Keeping this outside [cn.devcxl.photosync.editor.ui.EditorActivity] lets the activity stay a
 * thin controller: it delegates source loading, renderer state and cache writing here.
 *
 * ## Inpainting (Pre-Pass A)
 * The **untouched decode** is kept for the whole session: [source] is either that decode or a
 * private copy with `graph.inpaint` blended in. Erasing therefore never re-reads the RAW, undoing
 * every erase returns to the exact original pixels, and the press-and-hold comparison can show the
 * un-repaired frame while the canvas shows the repaired one. Composition only ever runs against the
 * original, never against an already composed copy, so overlapping patches are applied once.
 */
internal class EditorPreviewSession(
    private val renderer: EditorGlRenderer,
    private val cacheDir: File,
    private val photoId: Long,
    private val lutStore: CubeLutStore,
    private val filesDir: File
) {

    /** The source the canvas renders: the untouched decode or its inpaint-composed copy. */
    var source: EditorSource? = null
        private set

    private val cacheRenderer = PreviewCacheRenderer(cacheDir, photoId, lutStore, filesDir)
    private var originalSource: EditorSource? = null
    private var composedSource: EditorSource? = null
    private var appliedInpaint: List<InpaintPatchRef> = emptyList()

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
        composedSource?.release()
        composedSource = null
        appliedInpaint = emptyList()
        originalSource = loaded
        source = loaded
        if (loaded != null) {
            renderer.setSource(loaded)
        }
        renderer.setCompareSource(null)
        return loaded
    }

    /**
     * Re-stages the canvas source for [graph]'s `EditGraph.inpaint` list.
     *
     * A no-op when the list did not change, so the 60Hz slider path never pays for a recomposition.
     * When patches exist, [renderer] also receives the untouched decode as its comparison source, so
     * press-and-hold shows the frame before the repair instead of the repaired pixels.
     *
     * @return True when the canvas source changed and a new frame is required.
     */
    fun applyInpaintOverlay(graph: EditGraph): Boolean {
        val original = originalSource ?: return false
        if (graph.inpaint == appliedInpaint) return false
        appliedInpaint = graph.inpaint
        if (graph.inpaint.isEmpty()) {
            restoreOriginalSource(original)
            return true
        }
        val overlay = InpaintOverlay.of(filesDir, graph) ?: return false
        val composed = EditorSourceComposer.compose(original, overlay)
        if (composed == null) {
            Timber.w("Inpaint overlay could not be composed; keeping the unrepaired preview")
            restoreOriginalSource(original)
            return true
        }
        // The previous copy is only dropped here: the GL thread may still be uploading it, so it is
        // left to the collector instead of being recycled under a live texture upload.
        composedSource = composed
        source = composed
        renderer.setSource(composed)
        renderer.setCompareSource(original)
        return true
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
        applyInpaintOverlay(graph)
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

    /** Stages the decoded Post-Pass A cloud layer for the next canvas frame, or unbinds it. */
    fun applyCloudLayer(pixels: CloudLayerPixels?) {
        renderer.setCloudLayer(pixels)
    }

    /**
     * Decodes the Post-Pass A cloud layer of [graph] at a canvas-sized resolution.
     *
     * Blocking: call from an IO dispatcher. The returned pixels are frame-aspect aligned, and any
     * digest mismatch or failed asset check degrades to `null` so the canvas never blends a layer
     * that belongs to another framing.
     */
    fun decodeCloudLayer(graph: EditGraph): CloudLayerPixels? {
        if (graph.cloudLayer == null) return null
        val current = source ?: return null
        val sourceAspect = current.width.toFloat() / current.height.toFloat()
        val aspect = GeometryMath.outputAspect(graph.geometry, sourceAspect)
            .takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
        val (width, height) = if (aspect >= 1.0f) {
            CLOUD_PREVIEW_MAX_EDGE_PX to
                (CLOUD_PREVIEW_MAX_EDGE_PX / aspect).roundToInt().coerceAtLeast(1)
        } else {
            (CLOUD_PREVIEW_MAX_EDGE_PX * aspect).roundToInt().coerceAtLeast(1) to
                CLOUD_PREVIEW_MAX_EDGE_PX
        }
        return CloudPreviewBlend.prepare(AiAssetStore(filesDir), graph, width, height)
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
     * The cache is always rendered from the **untouched** decode with `graph.inpaint` composed in, so
     * a repeated save can never stack the same patch twice.
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
        val base = originalSource ?: return null
        return cacheRenderer.render(base, graph, warpField, skinMask)
    }

    /**
     * Renders the clean Post-Pass A / Pre-Pass B frame a cloud request uploads.
     *
     * Blocking: call from an IO dispatcher. [source] is already the current pixels (the untouched
     * decode or its inpaint-composed copy), so no overlay is composed a second time.
     */
    fun renderPostPassABitmap(graph: EditGraph, warpField: WarpDisplacementField?): Bitmap? {
        val current = source ?: return null
        return PostPassAFrameRenderer.render(current, graph, warpField, inpaint = null)
    }

    private fun restoreOriginalSource(original: EditorSource) {
        composedSource = null
        source = original
        renderer.setSource(original)
        renderer.setCompareSource(null)
    }

    /** Releases staged textures and recycles any bitmaps owned by the source. */
    fun release() {
        composedSource?.release()
        composedSource = null
        originalSource?.release()
        originalSource = null
        source = null
        appliedInpaint = emptyList()
        renderer.setCompareSource(null)
        renderer.releaseSource()
    }

    private fun resolveCachedLut(graph: EditGraph): Lut3dSamples? =
        graph.color.lut?.let { lutStore.cachedSamplesIf(it.id) }

    private companion object {
        /** Long edge of the canvas cloud texture; any resolution samples correctly in Pass B. */
        const val CLOUD_PREVIEW_MAX_EDGE_PX = 2048
    }
}
