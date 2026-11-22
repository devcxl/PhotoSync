package cn.devcxl.photosync.editor.domain

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

import timber.log.Timber

/**
 * Reads and writes the liquify displacement grid under `filesDir/edits/<photoId>/`.
 *
 * Files live in private file storage (not the evictable cache directory) because they are the only
 * copy of the edit: the `EditGraph` row merely references them by relative path and SHA-256. Writes
 * go through a temporary sibling and an atomic rename so a crash can never leave a half-written
 * mesh that fails its own digest.
 */
internal class LiquifyMeshStore(private val filesDir: File) {

    /**
     * Persists [mesh] for [photoId].
     *
     * @return The reference to store in `EditGraph.geometry.liquify`, or `null` when the mesh is
     *   effectively zero (in which case any previous payload is deleted) or the write failed.
     */
    fun write(photoId: Long, mesh: LiquifyMesh): LiquifyRef? {
        if (mesh.isZero()) {
            delete(photoId)
            return null
        }
        val relativePath = LiquifyMeshCodec.persistedRelativePath(photoId)
        val target = resolve(relativePath) ?: return null
        val parent = target.parentFile ?: return null
        if (!parent.exists() && !parent.mkdirs()) {
            Timber.e("Liquify mesh directory could not be created")
            return null
        }
        val payload = LiquifyMeshCodec.deflate(LiquifyMeshCodec.encodeRaw(mesh))
        val digest = LiquifyMeshCodec.sha256Hex(payload)
        val temporary = File(parent, "${LiquifyMeshCodec.FILE_NAME}.tmp")
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

    /** Deletes the photo's mesh directory entry, if any. */
    fun delete(photoId: Long) {
        val file = resolve(LiquifyMeshCodec.persistedRelativePath(photoId)) ?: return
        if (file.exists() && !file.delete()) {
            Timber.w("Liquify mesh could not be deleted")
        }
        file.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
    }

    /**
     * Resolves [relativePath] inside [filesDir], refusing anything that escapes it.
     */
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
}
