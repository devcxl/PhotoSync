package cn.devcxl.photosync.editor.render

import java.io.File

import android.content.Context

import timber.log.Timber

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.LiquifyMeshStore
import cn.devcxl.photosync.editor.domain.LiquifyRef
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.export.BakePortraitSupport
import cn.devcxl.photosync.editor.lut.CubeLutStore

/**
 * Renders the screen-sized preview JPEG of a graph that no editor session ever opened — the
 * `LiveTetherPreset` path, where a photo arrives from the camera and must already look graded on its
 * very first `AutoFollow` emission.
 *
 * The whole point is ordering: [renderToDir] writes into a staging directory *before* the caller
 * inserts the Room row, so a crash can never leave a photo pointing at a preview that does not exist
 * yet, and a failed render simply yields `null` instead of a half-bound edit.
 */
object BackgroundPreviewRenderer {

    /**
     * Renders [graph] applied to the photo at [sourcePath] and returns the written JPEG.
     *
     * @param context Application context; the portrait pipeline is only spun up when the graph
     *   actually carries portrait adjustments.
     * @param filesDir Private files directory holding the liquify mesh and LUT caches.
     * @param cacheDir Cache root the preview lands under, i.e. `cacheDir/edits/<photoId>/`.
     * @param photoId Photo id the cache directory is named after.
     * @param sourcePath Absolute path of the original JPEG or RAW file.
     * @param isRaw True when [sourcePath] decodes through `:libraw`.
     * @param graph The edited parameters to bake into the preview.
     * @return The absolute path of the written JPEG, or `null` when nothing could be rendered.
     */
    fun render(
        context: Context,
        filesDir: File,
        cacheDir: File,
        photoId: Long,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph
    ): String? {
        if (photoId <= 0L || sourcePath.isBlank() || graph.isIdentity()) return null
        return try {
            renderGraph(context, filesDir, cacheDir, photoId, sourcePath, isRaw, graph)
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while rendering the background preview for photoId=%d", photoId)
            null
        } catch (io: java.io.IOException) {
            Timber.e(io, "I/O failed while rendering the background preview for photoId=%d", photoId)
            null
        }
    }

    /**
     * Renders [graph] into [outDir] without a photo id, so an ingestor can stage the JPEG in a temp
     * directory before it knows the id Room will assign.
     *
     * @return The staged JPEG, or `null` when the source cannot be decoded or written.
     */
    fun renderToDir(
        context: Context,
        filesDir: File,
        outDir: File,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph
    ): File? {
        if (sourcePath.isBlank()) return null
        if (!outDir.isDirectory && !outDir.mkdirs()) {
            Timber.e("Background preview staging directory could not be created")
            return null
        }
        if (graph.isIdentity()) return null
        val source = loadSource(sourcePath, isRaw, preferLinear = true)
        if (source == null) {
            Timber.w("Background preview source could not be decoded")
            return null
        }
        return try {
            val portrait = resolvePortrait(context, filesDir, sourcePath, isRaw, graph)
            val lut = loadLut(filesDir, graph)
            val skinMask = portrait.skinMask
            val warpField = portrait.warpField
            val written = when (source) {
                is EditorSource.LinearRawSource -> PreviewCacheWriter.writeLinearPreviewTo(
                    source = source.full,
                    graph = graph,
                    outDir = outDir,
                    warpField = warpField,
                    lut = lut,
                    skinMask = skinMask
                )
                is EditorSource.BitmapSource -> PreviewCacheWriter.writeBitmapPreviewTo(
                    source = source.full,
                    graph = graph,
                    outDir = outDir,
                    warpField = warpField,
                    lut = lut,
                    skinMask = skinMask
                )
            }
            written?.let { File(it) }
        } finally {
            source.release()
        }
    }

    private fun renderGraph(
        context: Context,
        filesDir: File,
        cacheDir: File,
        photoId: Long,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph
    ): String? {
        val source = loadSource(sourcePath, isRaw, preferLinear = true)
        if (source == null) {
            Timber.w("Background preview source could not be decoded for photoId=%d", photoId)
            return null
        }
        return try {
            val portrait = resolvePortrait(context, filesDir, sourcePath, isRaw, graph)
            val lut = loadLut(filesDir, graph)
            val skinMask = portrait.skinMask
            val warpField = portrait.warpField
            when (source) {
                is EditorSource.LinearRawSource -> PreviewCacheWriter.writePreviewCache(
                    source = source.full,
                    graph = graph,
                    cacheDir = cacheDir,
                    photoId = photoId,
                    warpField = warpField,
                    lut = lut,
                    skinMask = skinMask
                )
                is EditorSource.BitmapSource -> PreviewCacheWriter.writePreviewCache(
                    source = source.full,
                    graph = graph,
                    cacheDir = cacheDir,
                    photoId = photoId,
                    warpField = warpField,
                    lut = lut,
                    skinMask = skinMask
                )
            }
        } finally {
            source.release()
        }
    }

    private fun loadSource(sourcePath: String, isRaw: Boolean, preferLinear: Boolean): EditorSource? =
        EditorSourceLoader.load(sourcePath, isRaw, preferLinear)

    /**
     * Resolves the warp and skin mask. The portrait pipeline is only touched when the graph carries
     * portrait adjustments, because spinning up MediaPipe for a pure tone/colour preset would cost
     * hundreds of milliseconds per arriving photo.
     */
    private fun resolvePortrait(
        context: Context,
        filesDir: File,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph
    ): BakePortraitSupport.ResolvedPortrait {
        val liquifyMesh = loadLiquifyMesh(filesDir, graph.geometry.liquify)
        val needsPortrait = !graph.portrait.isIdentity()
        if (!needsPortrait) {
            return BakePortraitSupport.ResolvedPortrait(warpField = liquifyMesh, skinMask = null)
        }
        return BakePortraitSupport.resolve(
            context = context,
            filesDir = filesDir,
            sourcePath = sourcePath,
            isRaw = isRaw,
            graph = graph,
            liquifyMesh = liquifyMesh
        )
    }

    private fun loadLiquifyMesh(
        filesDir: File,
        reference: LiquifyRef?
    ): LiquifyMesh? {
        if (reference == null) return null
        return LiquifyMeshStore(filesDir).read(reference)
    }

    private fun loadLut(filesDir: File, graph: EditGraph): Lut3dSamples? {
        val reference = graph.color.lut ?: return null
        return CubeLutStore(filesDir).loadSamples(reference.id).getOrNull()
    }
}
