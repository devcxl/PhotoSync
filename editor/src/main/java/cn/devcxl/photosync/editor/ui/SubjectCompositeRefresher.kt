package cn.devcxl.photosync.editor.ui

import java.io.File

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import timber.log.Timber

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LinearRawPreviewRenderer
import cn.devcxl.photosync.editor.domain.SubjectOps
import cn.devcxl.photosync.editor.render.EditorSource
import cn.devcxl.photosync.editor.render.SubjectComposite
import cn.devcxl.photosync.editor.render.SubjectCompositeLoader
import cn.devcxl.photosync.editor.vision.SubjectImage

/**
 * Loads and caches the source-space [SubjectComposite] for one editor session.
 *
 * The mask is content-addressed, so its reference is a complete cache key: a mode or colour change
 * reuses the composite, while a new mask or a repaint after an erase reloads it. Loading runs off
 * the main thread and never blocks a slider drag.
 */
internal class SubjectCompositeRefresher(
    private val filesDir: File,
    private val sourceProvider: () -> EditorSource?,
    private val scope: CoroutineScope,
    private val onCompositeReady: (SubjectComposite) -> Unit,
    private val onRepaintRequested: () -> Unit
) {

    private var cachedMaskRef: String = ""
    private var cachedComposite: SubjectComposite? = null

    /** Drops the cached composite; the next [load] rebuilds it from the staged source. */
    fun reset() {
        cachedMaskRef = ""
        cachedComposite = null
    }

    /**
     * Returns the composite of [graph], reusing the cached one while its mask reference is unchanged.
     *
     * @return `null` when the mask is missing or the source pixels are not staged yet.
     */
    suspend fun load(graph: EditGraph): SubjectComposite? {
        val maskRef = graph.subject.maskRef
        if (maskRef == cachedMaskRef && cachedComposite != null) return cachedComposite
        return withContext(Dispatchers.IO) {
            val source = sourceProvider()
            val mask = SubjectCompositeLoader.loadMask(filesDir, graph)
            if (mask == null || source == null) {
                null
            } else {
                SubjectCompositeLoader.fromSource(source, mask)?.also { composite ->
                    store(graph.subject, composite)
                }
            }
        }
    }

    /** Caches [composite] for the mask of [subject]. */
    fun store(subject: SubjectOps, composite: SubjectComposite) {
        cachedMaskRef = subject.maskRef
        cachedComposite = composite
    }

    /**
     * Converts the staged [source] into the upright inference image.
     *
     * A linear RAW preview is rendered through the same tone path as the CPU cache so MODNet sees
     * display-referred colours; an 8-bit source is used as-is.
     */
    fun subjectImage(source: EditorSource): SubjectImage? = try {
        when (source) {
            is EditorSource.BitmapSource -> source.proxy.toSubjectImage()
            is EditorSource.LinearRawSource -> {
                val proxy = source.proxy
                val pixels = LinearRawPreviewRenderer.renderArgb(
                    source = proxy,
                    graph = EditGraph(),
                    fullResolutionShortEdge = minOf(proxy.width, proxy.height)
                )
                SubjectImage(proxy.width, proxy.height, pixels)
            }
        }
    } catch (oom: OutOfMemoryError) {
        Timber.e(oom, "OOM while preparing the subject inference image")
        null
    }

    private fun android.graphics.Bitmap.toSubjectImage(): SubjectImage {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return SubjectImage(width, height, pixels)
    }

    /**
     * Re-reads the composite after the pixels under it changed, for example a completed erase.
     *
     * Deliberately not tied to the controller's in-flight mask job: the refresh must never cancel a
     * running segmentation, and an absent mask or mode is a no-op.
     */
    fun refresh(graph: EditGraph) {
        if (graph.subject.maskRef.isBlank()) return
        reset()
        scope.launch {
            val composite = load(graph) ?: return@launch
            onCompositeReady(composite)
            onRepaintRequested()
        }
    }
}
