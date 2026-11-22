package cn.devcxl.photosync.editor.render

import java.io.File
import java.io.IOException

import android.content.Context

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.contract.EditorCachePaths
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.LiquifyMeshStore
import cn.devcxl.photosync.editor.domain.LiquifyRef
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.export.BakeConcurrencyGate
import cn.devcxl.photosync.editor.export.BakePortraitSupport
import cn.devcxl.photosync.editor.lut.CubeLutStore

/**
 * Renders the screen-sized preview JPEG of a graph that no editor session ever opened — used both
 * by `LiveTetherPreset` ingest and by quick-preset / paste actions from the main gallery.
 *
 * RAW renders wait on [BakeConcurrencyGate.rawBakeMutex] so parallel syncs or gallery pastes never
 * decode multiple full RAW buffers concurrently. Every failure is logged and yields `null` so a
 * half-bound edit row is never written.
 */
object BackgroundPreviewRenderer {

    /**
     * Renders [graph] applied to the photo at [sourcePath] into `cacheDir/edits/<photoId>/`.
     *
     * @return The absolute path of the written JPEG, or `null` when nothing could be rendered.
     */
    suspend fun render(
        context: Context,
        filesDir: File,
        cacheDir: File,
        photoId: Long,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph
    ): String? {
        if (photoId <= 0L || sourcePath.isBlank() || graph.isIdentity()) return null
        val editDir = File(cacheDir, EditorCachePaths.photoEditDirName(photoId))
        if (!editDir.exists() && !editDir.mkdirs()) {
            Timber.e("Preview cache directory could not be created for photoId=%d", photoId)
            return null
        }
        editDir.listFiles()?.forEach { it.delete() }
        return renderToDir(context, filesDir, editDir, sourcePath, isRaw, graph)?.absolutePath
    }

    /**
     * Renders [graph] into [outDir] without a photo id, so an ingestor can stage the JPEG in a temp
     * directory before it knows the id Room will assign.
     *
     * @return The staged JPEG, or `null` when the source cannot be decoded or written.
     */
    suspend fun renderToDir(
        context: Context,
        filesDir: File,
        outDir: File,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph
    ): File? {
        if (sourcePath.isBlank() || graph.isIdentity()) return null
        if (!outDir.isDirectory && !outDir.mkdirs()) {
            Timber.e("Background preview staging directory could not be created")
            return null
        }
        return withContext(Dispatchers.IO) {
            if (isRaw) {
                BakeConcurrencyGate.rawBakeMutex.withLock {
                    renderUnsafe(context, filesDir, outDir, sourcePath, isRaw = true, graph = graph)
                }
            } else {
                renderUnsafe(context, filesDir, outDir, sourcePath, isRaw = false, graph = graph)
            }
        }
    }

    private fun renderUnsafe(
        context: Context,
        filesDir: File,
        outDir: File,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph
    ): File? = try {
        val source = EditorSourceLoader.load(sourcePath, isRaw, preferLinear = true)
        if (source == null) {
            Timber.w("Background preview source could not be decoded")
            null
        } else {
            try {
                writeFromSource(context, filesDir, outDir, sourcePath, isRaw, graph, source)
            } finally {
                source.release()
            }
        }
    } catch (oom: OutOfMemoryError) {
        Timber.e(oom, "OOM while rendering background preview")
        null
    } catch (io: IOException) {
        Timber.e(io, "I/O failed while rendering background preview")
        null
    } catch (error: RuntimeException) {
        Timber.e(error, "Background preview render failed")
        null
    }

    private fun writeFromSource(
        context: Context,
        filesDir: File,
        outDir: File,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph,
        source: EditorSource
    ): File? {
        val portrait = resolvePortrait(context, filesDir, sourcePath, isRaw, graph)
        val lut = loadLut(filesDir, graph)
        val written = when (source) {
            is EditorSource.LinearRawSource -> PreviewCacheWriter.writeLinearPreviewTo(
                source = source.full,
                graph = graph,
                outDir = outDir,
                warpField = portrait.warpField,
                lut = lut,
                skinMask = portrait.skinMask
            )
            is EditorSource.BitmapSource -> PreviewCacheWriter.writeBitmapPreviewTo(
                source = source.full,
                graph = graph,
                outDir = outDir,
                warpField = portrait.warpField,
                lut = lut,
                skinMask = portrait.skinMask
            )
        }
        return written?.let(::File)
    }

    private fun resolvePortrait(
        context: Context,
        filesDir: File,
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph
    ): BakePortraitSupport.ResolvedPortrait {
        val liquifyMesh = loadLiquifyMesh(filesDir, graph.geometry.liquify)
        if (graph.portrait.isIdentity()) {
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

    private fun loadLiquifyMesh(filesDir: File, reference: LiquifyRef?): LiquifyMesh? =
        reference?.let { LiquifyMeshStore(filesDir).read(it) }

    private fun loadLut(filesDir: File, graph: EditGraph): Lut3dSamples? {
        val reference = graph.color.lut ?: return null
        val loaded = CubeLutStore(filesDir).loadSamples(reference.id)
        if (loaded.isFailure) {
            Timber.w(loaded.exceptionOrNull(), "3D LUT %s unavailable for background preview", reference.id)
        }
        return loaded.getOrNull()
    }
}
