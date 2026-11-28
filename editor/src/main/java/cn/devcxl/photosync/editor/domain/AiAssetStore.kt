package cn.devcxl.photosync.editor.domain

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import timber.log.Timber

/**
 * The pixel payload types the AI edit groups can reference.
 *
 * The file name prefix keeps every kind distinguishable inside one photo directory, so a GC sweep
 * can report and tests can address a specific payload without decoding the graph.
 */
public enum class AiAssetKind(val fileNamePrefix: String, val extension: String) {
    SUBJECT_MASK("subject_mask", "png"),
    INPAINT_PATCH("inpaint_patch", "png"),
    INPAINT_MASK("inpaint_mask", "png"),
    SKY_MASK("sky_mask", "png"),
    CLOUD_LAYER("cloud_layer", "png"),
    CLOUD_MASK("cloud_mask", "png")
}

/**
 * Location and digest of one committed AI payload, as persisted in the `EditGraph`.
 */
public data class AiAssetRef(
    val relativePath: String,
    val sha256: String
)

/**
 * Outcome of one offline orphan sweep.
 */
public data class AiAssetGcReport(
    val scannedPhotoDirectories: Int,
    val skippedActivePhotoDirectories: Int,
    val deletedFiles: Int,
    val reclaimedBytes: Long
)

/**
 * Content-addressed store for the external pixel assets referenced by the `EditGraph` v2 AI nodes.
 *
 * Layout: `filesDir/edits/<photoId>/<kind>_<sha256>.<ext>`, shared with [LiquifyMeshStore]'s
 * directory so `Delete` can cascade over one photo directory.
 *
 * ## Write contract
 * Every payload is written through a unique temporary sibling, flushed and `fsync`-ed, then renamed
 * into place atomically. Because the full SHA-256 is part of the name, a save can never overwrite
 * the bytes a previously committed row verifies, and re-writing identical bytes reuses the same
 * file instead of adding one.
 *
 * ## Read contract
 * [read] confines the path to `edits/` and verifies the digest before returning the bytes, so a
 * truncated or tampered payload degrades to `null` instead of reaching the renderer.
 *
 * ## Cleanup ownership
 * [deletePhotoAssets] is called by the explicit `Delete` path. [collectOrphanAssets] is the offline
 * collector: it must only run when the caller can enumerate the persisted graph rows and knows which
 * photos are still being edited or exported. Directories of active photos are skipped whole.
 */
public class AiAssetStore(private val filesDir: File) {

    /**
     * Persists [payload] for [photoId] under a content-addressed name.
     *
     * @param extension File extension without the dot; must be one of `png`, `jpg`, `jpeg` or
     *   `webp`. It defaults to the kind's canonical raster format and does not transcode [payload].
     * @return The reference to store in the graph, or `null` when the payload is empty, the photo id
     *   is invalid, [extension] is unsupported or the write failed.
     */
    fun write(
        photoId: Long,
        kind: AiAssetKind,
        payload: ByteArray,
        extension: String = kind.extension
    ): AiAssetRef? {
        if (payload.isEmpty()) return null
        if (photoId <= 0L) {
            Timber.w("AI asset rejected: invalid photo id")
            return null
        }
        val normalizedExtension = extension.trim().lowercase()
        if (normalizedExtension !in SUPPORTED_EXTENSIONS) {
            Timber.w("AI asset rejected: unsupported file extension")
            return null
        }
        val digest = LiquifyMeshCodec.sha256Hex(payload)
        val relativePath = relativePathFor(photoId, kind, digest, normalizedExtension)
        val target = resolve(relativePath) ?: return null
        val parent = target.parentFile ?: return null
        if (!parent.isDirectory && !parent.mkdirs()) {
            Timber.e("AI asset directory could not be created")
            return null
        }
        val temporary = try {
            // A unique sibling per writer: two saves of the same photo must not share a temp file.
            File.createTempFile("${target.name}.", TEMP_SUFFIX, parent)
        } catch (io: IOException) {
            Timber.e(io, "AI asset temporary file could not be created")
            return null
        }
        return try {
            FileOutputStream(temporary).use { stream ->
                stream.write(payload)
                stream.flush()
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                Timber.e("AI asset could not be moved into place")
                temporary.delete()
                null
            } else {
                AiAssetRef(relativePath = relativePath, sha256 = digest)
            }
        } catch (io: IOException) {
            Timber.e(io, "AI asset could not be written")
            temporary.delete()
            null
        }
    }

    /**
     * Loads the payload referenced by [ref], verifying its SHA-256 first.
     *
     * @return The bytes, or `null` when the file is missing, unreadable, oversized or fails
     *   verification.
     */
    fun read(ref: AiAssetRef): ByteArray? = read(ref.relativePath, ref.sha256)

    /**
     * Loads [relativePath] and verifies it against [expectedSha256].
     *
     * @return The bytes, or `null` when the digest is malformed, the path escapes `edits/`, the file
     *   is missing or unreadable, or the bytes no longer match the digest.
     */
    fun read(relativePath: String, expectedSha256: String): ByteArray? {
        val digest = expectedSha256.trim()
        if (!isSha256Hex(digest)) {
            Timber.w("AI asset rejected: malformed sha256 digest")
            return null
        }
        val file = resolve(relativePath) ?: return null
        if (!file.isFile) return null
        if (file.length() > MAX_ASSET_BYTES) {
            Timber.w("AI asset rejected: payload exceeds the size budget")
            return null
        }
        val payload = try {
            file.readBytes()
        } catch (io: IOException) {
            Timber.e(io, "AI asset could not be read")
            return null
        }
        val actual = LiquifyMeshCodec.sha256Hex(payload)
        if (!actual.equals(digest, ignoreCase = true)) {
            Timber.w("AI asset rejected: sha256 digest mismatch")
            return null
        }
        return payload
    }

    /**
     * Every `edits/...` payload path [graph] still references, so an offline sweep can keep them.
     *
     * Values are collected from all AI groups; blank entries are dropped, duplicates collapse into
     * one and sky asset ids that are not store paths (procedural or pack ids) are ignored.
     */
    fun referencedRelativePaths(graph: EditGraph): Set<String> {
        val paths = LinkedHashSet<String>()
        fun add(path: String) {
            val normalized = path.trim()
            if (normalized.isNotEmpty()) paths.add(normalized)
        }
        add(graph.subject.maskRef)
        graph.inpaint.forEach { patch ->
            add(patch.patchRelPath)
            add(patch.maskRelPath)
        }
        add(graph.sky.maskRef)
        val skyAsset = graph.sky.assetIdOrRelPath.trim()
        if (skyAsset.startsWith("$EDITS_DIR/")) add(skyAsset)
        graph.cloudLayer?.let { layer ->
            add(layer.imageRelPath)
            layer.maskRelPath?.let(::add)
        }
        return paths
    }

    /**
     * Removes the whole `edits/<photoId>/` directory, including every liquify mesh and AI payload.
     *
     * @return True when the directory no longer exists after the call.
     */
    fun deletePhotoAssets(photoId: Long): Boolean {
        val directory = photoDirectory(photoId) ?: return true
        if (!directory.exists()) return true
        val deleted = directory.deleteRecursively()
        if (!deleted) Timber.w("AI asset directory could not be fully deleted")
        return deleted
    }

    /**
     * Deletes AI payloads that no persisted edit record references any more.
     *
     * Directories of [activePhotoIds] are skipped whole because an editor or export may be streaming
     * a payload that the previous database snapshot does not mention yet. Only files named with an
     * [AiAssetKind] prefix are considered, so liquify meshes in the same directory are never touched.
     * When the referenced-path provider throws, nothing is deleted: a failed read must never be
     * mistaken for "no references".
     *
     * @param referencedRelativePathsProvider Returns every `edits/<photoId>/<file>` path referenced
     *   by the persisted `photo_edits` rows; read lazily at the start of the sweep.
     * @param activePhotoIds Photos currently being edited or exported.
     */
    fun collectOrphanAssets(
        referencedRelativePathsProvider: () -> Set<String>,
        activePhotoIds: Set<Long>
    ): AiAssetGcReport {
        val referenced = try {
            referencedRelativePathsProvider().mapTo(HashSet()) { it.trim() }
        } catch (error: RuntimeException) {
            Timber.e(error, "AI asset GC skipped: referenced paths could not be read")
            return AiAssetGcReport(0, 0, 0, 0L)
        }
        val editsDir = File(filesDir, EDITS_DIR)
        if (!editsDir.isDirectory) return AiAssetGcReport(0, 0, 0, 0L)
        var scanned = 0
        var skipped = 0
        var deletedFiles = 0
        var reclaimedBytes = 0L
        editsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.forEach directoryLoop@{ directory ->
                val photoId = directory.name.toLongOrNull() ?: return@directoryLoop
                scanned++
                if (photoId in activePhotoIds) {
                    skipped++
                    return@directoryLoop
                }
                directory.listFiles()?.forEach fileLoop@{ file ->
                    if (!file.isFile || !hasAiAssetName(file.name)) return@fileLoop
                    val relativePath = "$EDITS_DIR/$photoId/${file.name}"
                    if (!referenced.contains(relativePath)) {
                        val size = file.length()
                        if (file.delete()) {
                            deletedFiles++
                            reclaimedBytes += size
                        } else {
                            Timber.w("Orphan AI asset could not be deleted")
                        }
                    }
                }
                if (directory.listFiles()?.isEmpty() == true) directory.delete()
            }
        return AiAssetGcReport(
            scannedPhotoDirectories = scanned,
            skippedActivePhotoDirectories = skipped,
            deletedFiles = deletedFiles,
            reclaimedBytes = reclaimedBytes
        )
    }

    /** Resolves [relativePath] inside `filesDir/edits`, refusing anything that escapes it. */
    private fun resolve(relativePath: String): File? {
        val normalized = relativePath.trim()
        if (!normalized.startsWith("$EDITS_DIR/")) return null
        return try {
            val editsBase = File(filesDir, EDITS_DIR).canonicalPath + File.separator
            val file = File(filesDir, normalized).canonicalFile
            if (file.path.startsWith(editsBase)) file else null
        } catch (io: IOException) {
            Timber.e(io, "AI asset path could not be resolved")
            null
        }
    }

    private fun photoDirectory(photoId: Long): File? {
        if (photoId <= 0L) return null
        return try {
            val editsDir = File(filesDir, EDITS_DIR)
            val directory = File(editsDir, photoId.toString()).canonicalFile
            if (directory.parentFile == editsDir.canonicalFile) directory else null
        } catch (io: IOException) {
            Timber.e(io, "AI asset photo directory could not be resolved")
            null
        }
    }

    /**
     * Only names this store owns are swept: a liquify mesh or any future payload sharing the photo
     * directory must not be deleted by an AI-asset collector that never wrote it.
     */
    private fun hasAiAssetName(fileName: String): Boolean =
        AiAssetKind.entries.any { fileName.startsWith("${it.fileNamePrefix}_") }

    companion object {
        /** Prefix of the per-photo edit directory inside `filesDir`. */
        const val EDITS_DIR = "edits"

        /** Hard ceiling for one payload read; the largest legitimate layer is a full-size PNG. */
        const val MAX_ASSET_BYTES = 64L * 1024L * 1024L

        private const val TEMP_SUFFIX = ".tmp"
        private val SUPPORTED_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

        /**
         * Relative path of the content-addressed payload of [photoId], for example
         * `edits/42/subject_mask_<sha256>.png`.
         */
        fun relativePathFor(
            photoId: Long,
            kind: AiAssetKind,
            sha256: String,
            extension: String = kind.extension
        ): String = "$EDITS_DIR/$photoId/${kind.fileNamePrefix}_$sha256.$extension"
    }
}
