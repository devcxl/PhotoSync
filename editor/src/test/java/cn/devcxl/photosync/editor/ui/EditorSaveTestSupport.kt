package cn.devcxl.photosync.editor.ui

import java.io.File

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.contract.PhotoEditRecord
import cn.devcxl.photosync.editor.contract.UserPresetRecord
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LiquifyMeshCodec
import cn.devcxl.photosync.editor.domain.LiquifyMeshStore
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.domain.ToneOps
import cn.devcxl.photosync.editor.domain.Uv
import cn.devcxl.photosync.editor.domain.WarpDisplacementField

/**
 * Fixtures shared by [EditorSaveCoordinatorTest] and [EditorSavePayloadTest].
 *
 * Only what both seams need lives here: the recording store, the graph and stroke builders every
 * case starts from, and the payload accessors. The assertions and the scenarios stay in the two test
 * classes.
 */

/** Photo id every save fixture uses. */
internal const val SAVE_TEST_PHOTO_ID = 42L

/** Upper bound for the tests that wait on a real dispatcher. */
internal const val SAVE_TEST_TIMEOUT_MS = 5_000L

/** The visibly non-identity graph most save tests start from. */
internal fun toneGraph(): EditGraph = EditGraph().copy(tone = ToneOps(exposure = 0.5f))

/**
 * A session with one committed liquify stroke.
 *
 * [dragTo] selects the resulting grid, so two different values produce two different payloads.
 */
internal fun strokedLiquifySession(
    meshStore: LiquifyMeshStore,
    dragTo: Float = 0.55f
): LiquifySession = LiquifySession(meshStore).apply {
    beginStroke()
    pushStroke(Uv(0.4f, 0.5f), Uv(dragTo, 0.5f), radiusNorm = 0.1f, frameAspect = 1.5f)
    commitStroke()
}

/** Freezes the editor state a save is usually driven by: a tone edit plus one committed stroke. */
internal fun EditorSaveCoordinator.captureStroked(
    meshStore: LiquifyMeshStore,
    dragTo: Float = 0.55f
): EditorSaveSnapshot = captureSnapshot(
    graph = toneGraph(),
    liquify = strokedLiquifySession(meshStore, dragTo),
    portrait = null,
    hasSource = true
)

/** A stand-in for the JPEG the preview renderer claims to have written. */
internal fun previewJpegFile(root: File): String =
    File(root, "preview_${System.nanoTime()}.jpg").apply { writeText("jpeg") }.absolutePath

/** Directory holding [photoId]'s liquify payloads. */
internal fun payloadDirectory(root: File, photoId: Long = SAVE_TEST_PHOTO_ID): File =
    requireNotNull(File(root, LiquifyMeshCodec.persistedRelativePath(photoId)).parentFile)

/** Every payload file currently on disk for [photoId]. */
internal fun payloadFiles(root: File, photoId: Long = SAVE_TEST_PHOTO_ID): List<File> =
    payloadDirectory(root, photoId).listFiles()
        ?.filter { it.isFile && it.name.endsWith(".bin") }
        .orEmpty()

/** Records what the coordinator committed and lets a test suspend, observe or fail the commit. */
internal class RecordingEditStore : EditStore {

    val commits = mutableListOf<SavedEdit>()
    val saved: SavedEdit? get() = commits.lastOrNull()
    var previewedWarpField: WarpDisplacementField? = null
    var saveEntered: CompletableDeferred<Unit>? = null
    var saveGate: CompletableDeferred<Unit>? = null
    var failWith: RuntimeException? = null
    var onSave: (() -> Unit)? = null

    /**
     * Wraps [render] into the renderer seam, recording the warp grid the coordinator composed so a
     * test can assert the cache agrees with the canvas.
     */
    fun previewRenderer(
        render: (EditGraph, WarpDisplacementField?, FloatArray?) -> String?
    ): EditorPreviewCacheRenderer = EditorPreviewCacheRenderer { graph, warpField, skinMask ->
        previewedWarpField = warpField
        render(graph, warpField, skinMask)
    }

    fun requireSaved(): SavedEdit = requireNotNull(saved) { "nothing was committed" }

    override suspend fun saveEditForPhoto(
        photoId: Long,
        graph: EditGraph,
        previewCachePath: String?
    ) {
        saveEntered?.complete(Unit)
        onSave?.invoke()
        saveGate?.await()
        failWith?.let { throw it }
        commits += SavedEdit(graph = graph, previewCachePath = previewCachePath)
    }

    data class SavedEdit(
        val graph: EditGraph,
        val previewCachePath: String?
    )

    override suspend fun getEditForPhoto(photoId: Long): PhotoEditRecord? = null

    override suspend fun deleteEditForPhoto(photoId: Long) = Unit

    override fun observeEditedPhotoMap(): Flow<Map<Long, PhotoEditRecord>> = emptyFlow()

    override fun observePresets(): Flow<List<UserPresetRecord>> = emptyFlow()

    override suspend fun listPresets(): List<UserPresetRecord> = emptyList()

    override suspend fun getPreset(id: String): UserPresetRecord? = null

    override suspend fun savePreset(preset: UserPresetRecord) = Unit

    override suspend fun renamePreset(id: String, newName: String) = Unit

    override suspend fun deletePreset(id: String) = Unit

    override fun getLiveTetherPresetId(): String? = null

    override fun setLiveTetherPresetId(presetId: String?) = Unit

    override fun observeLiveTetherPresetId(): Flow<String?> = emptyFlow()
}
