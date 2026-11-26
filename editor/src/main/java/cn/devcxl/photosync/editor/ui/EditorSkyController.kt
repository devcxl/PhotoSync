package cn.devcxl.photosync.editor.ui

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast

import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.domain.AiAssetKind
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.PresetSubjectGate
import cn.devcxl.photosync.editor.domain.SkyAssetType
import cn.devcxl.photosync.editor.domain.SkyCustomAsset
import cn.devcxl.photosync.editor.domain.SkyCustomAssetStore
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.render.SkySourceProxy
import cn.devcxl.photosync.editor.ui.panels.AiInferenceBarController
import cn.devcxl.photosync.editor.vision.ClassicSkySegmenter
import cn.devcxl.photosync.editor.vision.SkyMask
import cn.devcxl.photosync.editor.vision.SkyMaskCodec

/**
 * Owns the `AI -> 天空` workflow from a chip tap to the committed `EditGraph`.
 *
 * ## Flow
 * 1. Choosing the original sky is a plain graph write and needs no mask.
 * 2. Choosing a procedural/custom sky reuses `sky.maskRef` when it still exists, otherwise runs the
 *    zero-model [ClassicSkySegmenter] on the `1024px` proxy once.
 * 3. A result under `3 %` coverage shows the gentle capsule and **keeps the graph untouched**, so a
 *    photo without a readable sky is never painted over.
 * 4. The mask PNG goes through the content-addressed [AiAssetStore]; switching asset or moving a
 *    slider is one coalesced [EditParameterKey.AI_SKY] undo transaction.
 *
 * SAF imports are re-encoded to a JPEG capped at `4096 px` and stored by SHA-256 in
 * [SkyCustomAssetStore]; a long press deletes one asset without touching unrelated photos.
 */
internal class EditorSkyController(
    private val context: Context,
    filesDir: File,
    private val photoId: Long,
    private val undoController: EditorUndoController,
    private val previewSession: EditorPreviewSession,
    private val inferenceBar: AiInferenceBarController,
    private val scope: CoroutineScope,
    private val onGraphEdited: (EditGraph, EditParameterKey) -> Unit,
    private val onGraphCommitted: (EditGraph, EditParameterKey) -> Unit,
    private val onUiRefresh: () -> Unit
) {

    private val assetStore = AiAssetStore(filesDir)
    private val customStore = SkyCustomAssetStore(filesDir)
    private val segmenter = ClassicSkySegmenter()
    private var job: Job? = null
    private var customAssets: List<SkyCustomAsset> = emptyList()
    private var hasSky: Boolean = true
    private var lastMaskRef: String = ""

    init {
        refreshAssets()
    }

    /** The imported skies currently offered by the panel. */
    fun assets(): List<SkyCustomAsset> = customAssets

    /** True when the last segmentation found a usable sky region. */
    fun lastSegmentationHasSky(): Boolean = hasSky

    /** Stops the running segmentation or import; the graph is left as it was. */
    fun cancel() {
        job?.cancel()
        job = null
        if (inferenceBar.isVisible) inferenceBar.dismiss()
    }

    /** Handles a sky chip tap. */
    fun select(sky: SkyOps) {
        if (sky.assetType == SkyAssetType.NONE) {
            commit(SkyOps())
            return
        }
        val graph = undoController.currentGraph
        val existingMask = graph.sky.maskRef.ifBlank { lastMaskRef }
        if (existingMask.isNotBlank()) {
            commit(sky.copy(maskRef = existingMask))
            return
        }
        ensureMaskAndApply(sky)
    }

    /** Handles one slider tick. */
    fun adjust(sky: SkyOps) {
        if (sky.assetType == SkyAssetType.NONE) return
        val graph = undoController.currentGraph
        if (graph.sky.maskRef.isBlank()) {
            ensureMaskAndApply(sky)
            return
        }
        val updated = graph.copy(sky = sky.copy(maskRef = graph.sky.maskRef))
        val applied = undoController.onParameterDrag(updated, EditParameterKey.AI_SKY)
        onGraphEdited(applied, EditParameterKey.AI_SKY)
    }

    /** Seals the coalesced `AI_SKY` step when the user lifts the slider. */
    fun endAdjustment() {
        undoController.onParameterRelease()
        onGraphCommitted(undoController.currentGraph, EditParameterKey.AI_SKY)
    }

    /** Computes this photo's own mask after a preset carried sky parameters. */
    fun ensureMaskForPreset(graph: EditGraph) {
        if (!PresetSubjectGate.needsSkyMask(graph)) return
        select(graph.sky)
    }

    /** Restores the `< 3 %` state after reopening a photo that already has a sky mask. */
    fun restore(graph: EditGraph) {
        val maskRef = graph.sky.maskRef
        if (maskRef.isBlank()) return
        job?.cancel()
        job = scope.launch {
            val mask = withContext(Dispatchers.IO) { readMask(maskRef) } ?: return@launch
            lastMaskRef = maskRef
            hasSky = mask.coverageRatio >= SkyMask.MIN_USABLE_COVERAGE
            onUiRefresh()
        }
    }

    /** Reloads the imported sky library, for example after returning from the settings screen. */
    fun refreshAssets() {
        customAssets = customStore.list()
    }

    /** Asks for confirmation and deletes the imported sky [sha256]. */
    fun requestDeleteCustom(sha256: String) {
        AlertDialog.Builder(context)
            .setTitle(R.string.editor_sky_delete_title)
            .setMessage(R.string.editor_sky_delete_message)
            .setNegativeButton(R.string.editor_sky_cancel, null)
            .setPositiveButton(R.string.editor_sky_delete_confirm) { _, _ ->
                deleteCustom(sha256)
            }
            .show()
    }

    /** Imports a user-selected JPEG and selects it on success. */
    fun importSky(input: InputStream) {
        job?.cancel()
        job = scope.launch {
            inferenceBar.show(context.getString(R.string.editor_sky_importing))
            val imported = withContext(Dispatchers.IO) { importFromStream(input) }
            inferenceBar.dismiss()
            if (imported == null) {
                Toast.makeText(context, R.string.editor_sky_import_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            refreshAssets()
            Toast.makeText(context, R.string.editor_sky_imported, Toast.LENGTH_SHORT).show()
            val graph = undoController.currentGraph
            val sky = graph.sky.copy(
                assetType = SkyAssetType.CUSTOM,
                assetIdOrRelPath = imported.relativePath,
                assetSha256 = imported.sha256
            )
            select(sky)
        }
    }

    private fun ensureMaskAndApply(sky: SkyOps) {
        job?.cancel()
        job = scope.launch {
            inferenceBar.show(context.getString(R.string.editor_sky_segmentation_label))
            val mask = withContext(Dispatchers.Default) { segment() }
            currentCoroutineContext().ensureActive()
            inferenceBar.dismiss()
            if (mask == null) {
                Toast.makeText(context, R.string.editor_sky_apply_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            hasSky = mask.coverageRatio >= SkyMask.MIN_USABLE_COVERAGE
            if (!hasSky) {
                // The gentle hint is the whole action: a < 3 % sky must not paint over the frame.
                onUiRefresh()
                return@launch
            }
            val payload = SkyMaskCodec.encode(mask) ?: return@launch
            val reference = assetStore.write(photoId, AiAssetKind.SKY_MASK, payload) ?: return@launch
            lastMaskRef = reference.relativePath
            val graph = undoController.currentGraph
            commit(sky.copy(maskRef = reference.relativePath))
            onUiRefresh()
        }
    }

    private fun segment(): SkyMask? {
        val source = previewSession.source ?: return null
        val image = SkySourceProxy.of(source) ?: return null
        val result = segmenter.segment(image).getOrElse { error ->
            if (error is OutOfMemoryError) {
                Timber.e(error, "OOM while segmenting the sky")
            } else {
                Timber.w(error, "Classic sky segmentation failed")
            }
            return null
        }
        if (!result.hasSky) Timber.i("Classic sky segmentation found less than 3 % sky")
        return result.mask
    }

    private fun readMask(maskRef: String): SkyMask? {
        val digest = SkyMaskCodec.digestFromRef(maskRef) ?: return null
        val payload = assetStore.read(maskRef, digest) ?: return null
        return SkyMaskCodec.decode(payload)
    }

    private fun commit(sky: SkyOps) {
        val graph = undoController.currentGraph.copy(sky = sky)
        val applied = undoController.onParameterDrag(graph, EditParameterKey.AI_SKY)
        undoController.onParameterRelease()
        onGraphCommitted(applied, EditParameterKey.AI_SKY)
    }

    private fun deleteCustom(sha256: String) {
        val deleted = customStore.delete(sha256)
        if (!deleted) {
            Toast.makeText(context, R.string.editor_sky_import_failed, Toast.LENGTH_SHORT).show()
            return
        }
        refreshAssets()
        val graph = undoController.currentGraph
        if (graph.sky.assetType == SkyAssetType.CUSTOM &&
            graph.sky.assetSha256.equals(sha256, ignoreCase = true)
        ) {
            commit(SkyOps())
        }
        onUiRefresh()
    }

    private fun importFromStream(input: InputStream): SkyCustomAsset? {
        val payload = try {
            readBounded(input, MAX_IMPORT_BYTES) ?: return null
        } catch (io: IOException) {
            Timber.e(io, "The selected sky could not be read")
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(payload, 0, payload.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(payload, 0, payload.size, options) ?: return null
        return try {
            val scaled = scaleToMaxEdge(decoded, SkyCustomAssetStore.MAX_EDGE_PX)
            try {
                val jpeg = ByteArrayOutputStream().use { stream ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                    stream.toByteArray()
                }
                customStore.importJpeg(jpeg, scaled.width, scaled.height)
            } finally {
                if (scaled !== decoded && !scaled.isRecycled) scaled.recycle()
            }
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > MAX_IMPORT_EDGE_PX * 2) sample *= 2
        return sample
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) {
                Timber.w("Rejected a sky import above the byte budget")
                return null
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_IMPORT_EDGE_PX = 4096
        const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
        const val BUFFER_BYTES = 16 * 1024
        const val JPEG_QUALITY = 92
    }
}
