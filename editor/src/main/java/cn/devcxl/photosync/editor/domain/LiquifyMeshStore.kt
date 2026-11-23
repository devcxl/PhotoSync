package cn.devcxl.photosync.editor.domain

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import timber.log.Timber

/**
 * Reads and writes the liquify displacement grid under `filesDir/edits/<photoId>/`.
 *
 * Files live in private file storage (not the evictable cache directory) because they are the only
 * copy of the edit: the `EditGraph` row merely references them by relative path and SHA-256.
 *
 * ## Payload lifetime
 * A payload is **content-addressed** (`liquify_128x128_<sha256>.bin`, whole digest) and written
 * through a unique temporary sibling followed by an atomic rename, so:
 * - writing a grid never overwrites the payload a previously committed row verifies — a save that
 *   fails or is cancelled between the mesh write and the row update leaves the old edit readable;
 * - an empty grid stores nothing and deletes nothing;
 * - re-saving an unchanged grid reuses the same name, so only a *changed* grid adds a file.
 *
 * ## No automatic cleanup
 * This store never deletes a payload. Retiring one needs coordination it does not have: a second
 * editor or the tethered darkroom may be saving the same photo and already have pointed its new row
 * at the file, an export may be streaming it, and two writers may be mid-`createTempFile` in the same
 * directory. A "delete everything the new row does not reference" sweep is therefore a data-loss race
 * without a single owner of the photo's payloads, and it is deliberately absent rather than guarded by
 * an ad-hoc lock.
 *
 * The cost is bounded and small: one compressed grid is ≈65 KiB and a file is added only when a save
 * changes the grid, so a photo accumulates roughly one payload per liquify edit session. They stay
 * valid read targets for the rows that reference them and may only be reclaimed by
 * 1. an explicit, serialized "delete this photo's edit" operation that owns the photo, or
 * 2. a future collector that first proves a payload is unreferenced — for example by reading the
 *    persisted rows while the photo cannot be opened for editing.
 *
 * The legacy fixed name (`liquify_128x128.bin`) written by older versions stays readable for the rows
 * that point at it, and is never removed either.
 */
internal class LiquifyMeshStore(private val filesDir: File) {

    /**
     * Persists [mesh] for [photoId] under a content-addressed name.
     *
     * @return The reference to store in `EditGraph.geometry.liquify`, or `null` when the mesh is
     *   effectively zero (nothing is written and nothing is deleted) or the write failed.
     */
    fun write(photoId: Long, mesh: LiquifyMesh): LiquifyRef? {
        if (mesh.isZero()) return null
        val payload = LiquifyMeshCodec.deflate(LiquifyMeshCodec.encodeRaw(mesh))
        val digest = LiquifyMeshCodec.sha256Hex(payload)
        val relativePath = LiquifyMeshCodec.persistedRelativePath(photoId, digest)
        val target = resolve(relativePath) ?: return null
        val parent = target.parentFile ?: return null
        if (!parent.exists() && !parent.mkdirs()) {
            Timber.e("Liquify mesh directory could not be created")
            return null
        }
        val temporary = try {
            // A unique sibling per writer: two saves of the same photo must not share a temp file.
            File.createTempFile("${target.name}.", TEMP_SUFFIX, parent)
        } catch (io: IOException) {
            Timber.e(io, "Liquify mesh temporary file could not be created")
            return null
        }
        return try {
            FileOutputStream(temporary).use { stream ->
                stream.write(payload)
                stream.flush()
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                Timber.e("Liquify mesh could not be moved into place")
                temporary.delete()
                return null
            }
            LiquifyRef(
                meshRelPath = relativePath,
                gridSize = mesh.gridSize,
                sha256 = digest
            )
        } catch (io: IOException) {
            Timber.e(io, "Liquify mesh could not be written")
            temporary.delete()
            null
        }
    }

    /**
     * Loads the mesh referenced by [ref], verifying its SHA-256 first.
     *
     * Any confined relative path is accepted, so rows written before content addressing keep loading.
     *
     * @return The mesh, or `null` when the file is missing, unreadable or fails verification.
     */
    fun read(ref: LiquifyRef): LiquifyMesh? {
        if (ref.gridSize != LiquifyMesh.GRID_SIZE) {
            Timber.w("Liquify mesh rejected: unsupported grid size %d", ref.gridSize)
            return null
        }
        val file = resolve(ref.meshRelPath) ?: return null
        if (!file.isFile) return null
        val payload = try {
            file.readBytes()
        } catch (io: IOException) {
            Timber.e(io, "Liquify mesh could not be read")
            return null
        }
        return LiquifyMeshCodec.decodePersisted(payload, ref.sha256)
    }

    /** Resolves [relativePath] inside [filesDir], refusing anything that escapes it. */
    private fun resolve(relativePath: String): File? {
        if (relativePath.isBlank()) return null
        return try {
            val base = filesDir.canonicalPath + File.separator
            val file = File(filesDir, relativePath).canonicalFile
            if (file.path.startsWith(base)) file else null
        } catch (io: IOException) {
            Timber.e(io, "Liquify mesh path could not be resolved")
            null
        }
    }

    private companion object {
        /** Suffix of the temporary sibling a payload write goes through. */
        const val TEMP_SUFFIX = ".tmp"
    }
}

/**
 * Copies a persisted liquify mesh into another photo's private edit directory when cross-photo
 * paste opts into `includeLiquify`.
 */
object LiquifyMeshCopier {

    /**
     * Clones [graph]'s liquify payload to [targetPhotoId]'s directory if present, returning an
     * updated [EditGraph] pointing at the cloned file (or clearing `liquify` if cloning failed).
     *
     * The clone is content-addressed, so the target photo's previous payload is left in place: it
     * stays readable for whichever row still references it, even if that row is later replaced.
     */
    fun cloneForTarget(filesDir: File, targetPhotoId: Long, graph: EditGraph): EditGraph {
        val ref = graph.geometry.liquify ?: return graph
        val store = LiquifyMeshStore(filesDir)
        val mesh = store.read(ref) ?: return graph.copy(geometry = graph.geometry.copy(liquify = null))
        val clonedRef = store.write(targetPhotoId, mesh)
        return graph.copy(geometry = graph.geometry.copy(liquify = clonedRef))
    }
}
