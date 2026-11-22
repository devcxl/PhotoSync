package cn.devcxl.photosync.editor.ui

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

import android.content.Context
import android.graphics.Bitmap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.BodyGuides
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LinearRawPreviewRenderer
import cn.devcxl.photosync.editor.domain.PortraitAnalysis
import cn.devcxl.photosync.editor.render.EditorGlRenderer
import cn.devcxl.photosync.editor.render.EditorSource
import cn.devcxl.photosync.editor.vision.MediaPipeDelegatePolicy
import cn.devcxl.photosync.editor.vision.MediaPipePortraitEngine
import cn.devcxl.photosync.editor.vision.ModelDownloadManager
import cn.devcxl.photosync.editor.vision.PortraitAnalyzer

/**
 * Owns the asynchronous Portrait work of one editor session:
 *
 * 1. **Analysis** — runs MediaPipe once when the user first opens the Portrait tab (the bundled
 *    landmarker plus person segmenter need no download), publishes the `skinMask` and the resolved
 *    body guides, and keeps them for the whole session.
 * 2. **Model upgrade** — starts the on-demand download of the 16.37 MB six-class model in the
 *    background, verifies its SHA-256, hot-swaps the segmenter and re-runs the analysis so the mask
 *    silently becomes the precise hair/clothes-aware version.
 * 3. **Warp solving** — recomputes the face MLS and body meshes off the UI thread whenever the
 *    `portrait.reshape` / `portrait.body` sliders change, and hands them to the renderer, which is
 *    the single point that composes them with the liquify grid into `uWarpTex`.
 */
internal class EditorPortraitCoordinator(
    private val context: Context,
    filesDir: File,
    private val renderer: EditorGlRenderer,
    private val scope: CoroutineScope,
    private val currentGraph: () -> EditGraph,
    private val onWarpsPublished: () -> Unit
) {

    private val delegatePolicy = MediaPipeDelegatePolicy(context)
    private val engine = MediaPipePortraitEngine(context, delegatePolicy)
    private val analyzer = PortraitAnalyzer(engine)
    private val downloads = ModelDownloadManager(File(filesDir, MODEL_DIRECTORY))

    private val analysisMutex = Mutex()

    @Volatile
    private var analysis: PortraitAnalysis? = null

    @Volatile
    private var source: EditorSource? = null

    private val parkedRelease = AtomicBoolean(false)

    private var downloadJob: Job? = null
    private var analysisJob: Job? = null
    private var warpJob: Job? = null
    private var released = false

    /** The live analysis, or `null` while the first pass is still running. */
    fun currentAnalysis(): PortraitAnalysis? = analysis

    /** Resolved guide lines for the body overlay, falling back to the neutral defaults. */
    fun currentGuides(): BodyGuides = analysis?.guides ?: BodyGuides()

    /**
     * Called when the Portrait tab is opened: starts the background download and runs the analysis
     * for [source] if it has not happened yet.
     */
    fun onPortraitEntered(source: EditorSource?) {
        this.source = source
        startDownload()
        if (source != null && analysis == null) startAnalysis()
    }

    /**
     * Re-solves the portrait meshes for [graph].
     *
     * Called from the slider pipeline, so it must return immediately; the meshes are published
     * through [EditorGlRenderer.setPortraitWarp] on the main thread.
     */
    fun onGraphChanged(graph: EditGraph) {
        if (released) return
        warpJob?.cancel()
        val current = analysis
        if (current == null || (graph.portrait.reshape.isIdentity() && graph.portrait.body.isIdentity())) {
            renderer.setPortraitWarp(null, null)
            onWarpsPublished()
            return
        }
        warpJob = scope.launch {
            val faceWarp = withContext(Dispatchers.Default) {
                if (graph.portrait.reshape.isIdentity()) null else current.faceWarp(graph.portrait.reshape)
            }
            val bodyWarp = withContext(Dispatchers.Default) {
                if (graph.portrait.body.isIdentity()) null else current.bodyWarp(graph.portrait.body)
            }
            renderer.setPortraitWarp(faceWarp, bodyWarp)
            onWarpsPublished()
        }
    }

    /**
     * Cancels every job and releases the MediaPipe tasks.
     *
     * A release that lands while an inference owns the native tasks is parked until that inference
     * leaves [analysisMutex]; closing a task mid-`detect` would abort native code.
     */
    fun release() {
        released = true
        downloadJob?.cancel()
        analysisJob?.cancel()
        warpJob?.cancel()
        if (analysisMutex.tryLock()) {
            try {
                engine.release()
            } finally {
                analysisMutex.unlock()
            }
        } else {
            parkedRelease.set(true)
        }
    }

    private fun startDownload() {
        if (downloadJob?.isActive == true || downloads.isDownloaded) return
        downloadJob = scope.launch {
            val model = downloads.ensureModel() ?: return@launch
            analysisMutex.withLock {
                if (released) return@withLock
                val upgraded = withContext(Dispatchers.Default) {
                    engine.upgradeToMulticlass(model)
                }
                if (!upgraded) {
                    Timber.w("The downloaded portrait model could not be loaded; staying offline")
                    return@withLock
                }
                analyzeLocked()
            }
        }
    }

    private fun startAnalysis() {
        if (analysisJob?.isActive == true) return
        analysisJob = scope.launch {
            analysisMutex.withLock {
                if (released) return@withLock
                analyzeLocked()
            }
        }
    }

    private suspend fun analyzeLocked() {
        try {
            val bitmap = withContext(Dispatchers.Default) { analysisBitmap() } ?: return
            val result = withContext(Dispatchers.Default) {
                try {
                    analyzer.analyze(bitmap)
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
            if (result == null) {
                Timber.w("Portrait analysis produced no skin mask")
                return
            }
            analysis = result
            renderer.setSkinMask(result.skinMask)
            applyCurrentWarps(result)
            onWarpsPublished()
        } finally {
            if (parkedRelease.getAndSet(false)) {
                engine.release()
            }
        }
    }

    /**
     * Solves the meshes for the sliders that are already non-neutral and publishes them.
     *
     * Runs on [Dispatchers.Default]: a five-face MLS solve is cheap but not free, and it must never
     * land on the frame the editor is about to draw.
     */
    private suspend fun applyCurrentWarps(current: PortraitAnalysis) {
        val graph = currentGraph()
        val reshape = graph.portrait.reshape
        val body = graph.portrait.body
        val faceWarp = withContext(Dispatchers.Default) {
            if (reshape.isIdentity()) null else current.faceWarp(reshape)
        }
        val bodyWarp = withContext(Dispatchers.Default) {
            if (body.isIdentity()) null else current.bodyWarp(body)
        }
        renderer.setPortraitWarp(faceWarp, bodyWarp)
    }

    private fun analysisBitmap(): Bitmap? {
        return when (val current = source) {
            null -> null
            is EditorSource.BitmapSource -> scaleToAnalysisSize(current.proxy)
            is EditorSource.LinearRawSource -> {
                val small = current.proxy.downscaledTo(ANALYSIS_SIZE)
                Bitmap.createBitmap(
                    LinearRawPreviewRenderer.renderArgb(
                        source = small,
                        graph = EditGraph(),
                        fullResolutionShortEdge = ANALYSIS_SIZE
                    ),
                    small.width,
                    small.height,
                    Bitmap.Config.ARGB_8888
                )
            }
        }
    }

    /**
     * Returns an analysis-sized copy of [bitmap].
     *
     * A copy is mandatory: the analysis recycles its input, and the source's proxy bitmap belongs to
     * the editor session.
     */
    private fun scaleToAnalysisSize(bitmap: Bitmap): Bitmap? {
        if (bitmap.isRecycled) return null
        val shortEdge = minOf(bitmap.width, bitmap.height).coerceAtLeast(1)
        val scale = (ANALYSIS_SIZE.toFloat() / shortEdge).coerceAtMost(1.0f)
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return if (width == bitmap.width && height == bitmap.height) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
    }

    private companion object {
        private const val MODEL_DIRECTORY = "models"
        private const val ANALYSIS_SIZE = 512
    }
}
