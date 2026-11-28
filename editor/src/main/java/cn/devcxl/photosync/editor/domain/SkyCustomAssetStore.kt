package cn.devcxl.photosync.editor.domain

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

import timber.log.Timber

/**
 * One user-imported sky image already persisted in the content-addressed custom sky store.
 *
 * @property sha256 Full SHA-256 of the JPEG file, also part of its name.
 * @property relativePath Path relative to `filesDir`, e.g. `skies/sky_<sha256>.jpg`.
 * @property width Decoded pixel width reported by the importer.
 * @property height Decoded pixel height reported by the importer.
 * @property byteSize Stored file size in bytes.
 */
data class SkyCustomAsset(
    val sha256: String,
    val relativePath: String,
    val width: Int,
    val height: Int,
    val byteSize: Long
)

/**
 * Content-addressed store for user-selected sky photographs (`SAF` import).
 *
 * Layout: `filesDir/skies/sky_<sha256>.jpg`. The importer hands over an already re-encoded JPEG and
 * its decoded dimensions; the store refuses a long edge above [MAX_EDGE_PX] because a larger source
 * would only inflate the editor's GL texture without adding visible detail at 24–60 MP. Writes go
 * through a unique temporary sibling and an atomic rename, and [read] re-verifies the SHA-256 so a
 * truncated or tampered file degrades to `null` instead of reaching the renderer.
 *
 * The store is independent of any photo: deleting a sky here removes it from the picker but never
 * mutates an `EditGraph`, whose `sky.assetSha256` then simply fails to resolve and the renderer falls
 * back to the previous look.
 */
class SkyCustomAssetStore(private val filesDir: File) {

    /** The directory holding every imported sky JPEG. */
    fun directory(): File = File(filesDir, SKIES_DIR)

    /**
     * Persists [jpeg] for the decoded [width] x [height] image.
     *
     * @return The stored asset, or `null` when the payload is empty, oversized, not a JPEG, its
     *   long edge exceeds [MAX_EDGE_PX], the dimensions are invalid or the write failed.
     */
    fun importJpeg(jpeg: ByteArray, width: Int, height: Int): SkyCustomAsset? {
        if (!isImportable(jpeg, width, height)) return null
        val digest = LiquifyMeshCodec.sha256Hex(jpeg)
        val directory = ensureDirectory() ?: return null
        val target = File(directory, fileNameFor(digest))
        if (target.isFile && target.length() == jpeg.size.toLong()) {
            return storedAsset(digest, width, height, target)
        }
        return writeAtomically(jpeg, digest, width, height, directory, target)
    }

    private fun isImportable(jpeg: ByteArray, width: Int, height: Int): Boolean {
        if (jpeg.isEmpty() || jpeg.size > MAX_BYTES) {
            Timber.w("Custom sky rejected: payload is empty or exceeds the size budget")
            return false
        }
        if (!SkyJpegProbe.hasSignature(jpeg)) {
            Timber.w("Custom sky rejected: payload is not a JPEG")
            return false
        }
        if (width <= 0 || height <= 0 || maxOf(width, height) > MAX_EDGE_PX) {
            Timber.w("Custom sky rejected: dimensions exceed the long-edge budget")
            return false
        }
        return true
    }

    private fun ensureDirectory(): File? {
        val directory = directory()
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            Timber.e("Custom sky directory could not be created")
            return null
        }
        return directory
    }

    private fun writeAtomically(
        jpeg: ByteArray,
        digest: String,
        width: Int,
        height: Int,
        directory: File,
        target: File
    ): SkyCustomAsset? {
        val temporary = try {
            File.createTempFile("${target.name}.", TEMP_SUFFIX, directory)
        } catch (io: IOException) {
            Timber.e(io, "Custom sky temporary file could not be created")
            return null
        }
        return try {
            FileOutputStream(temporary).use { stream ->
                stream.write(jpeg)
                stream.flush()
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                temporary.delete()
                Timber.e("Custom sky could not be moved into place")
                null
            } else {
                storedAsset(digest, width, height, target)
            }
        } catch (io: IOException) {
            temporary.delete()
            Timber.e(io, "Custom sky could not be written")
            null
        }
    }

    private fun storedAsset(digest: String, width: Int, height: Int, file: File): SkyCustomAsset =
        SkyCustomAsset(
            sha256 = digest,
            relativePath = relativePathFor(digest),
            width = width,
            height = height,
            byteSize = file.length()
        )

    /**
     * Lists every stored sky, newest first is not tracked, so the order is the stable file order.
     *
     * A file whose name does not carry a SHA-256 is ignored: it was not written by this store.
     */
    fun list(): List<SkyCustomAsset> {
        val directory = directory()
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.mapNotNull { file -> assetOf(file) }
            ?.sortedBy { it.sha256 }
            ?.toList()
            ?: emptyList()
    }

    /** True when [sha256] resolves to a stored file. */
    fun contains(sha256: String): Boolean = fileFor(sha256)?.isFile == true

    /** Resolves [sha256] to its stored file, refusing anything outside the sky directory. */
    fun fileFor(sha256: String): File? {
        val digest = sha256.trim()
        if (!isSha256Hex(digest)) return null
        return confinedFile(fileNameFor(digest))
    }

    /**
     * Loads the JPEG referenced by [sha256] after verifying its digest.
     *
     * @return The bytes, or `null` when the file is missing, oversized or no longer matches.
     */
    fun read(sha256: String): ByteArray? {
        val file = fileFor(sha256) ?: return null
        if (!file.isFile || file.length() > MAX_BYTES) return null
        val payload = try {
            file.readBytes()
        } catch (io: IOException) {
            Timber.e(io, "Custom sky could not be read")
            return null
        }
        val actual = LiquifyMeshCodec.sha256Hex(payload)
        if (!actual.equals(sha256, ignoreCase = true)) {
            Timber.w("Custom sky rejected: sha256 digest mismatch")
            return null
        }
        return payload
    }

    /**
     * Deletes the sky referenced by [sha256], leaving untouched any edit graph that referenced it.
     *
     * @return True when no file remains afterwards.
     */
    fun delete(sha256: String): Boolean {
        val file = fileFor(sha256) ?: return false
        if (!file.exists()) return true
        val deleted = file.delete()
        if (!deleted) Timber.w("Custom sky could not be deleted")
        return deleted
    }

    /** Extracts the digest from `skies/sky_<sha>.jpg`, or from a bare 64-character digest. */
    fun shaFromReference(reference: String): String? {
        val trimmed = reference.trim()
        if (isSha256Hex(trimmed)) return trimmed.lowercase()
        val name = trimmed.substringAfterLast('/', "")
        return NAME_REGEX.matchEntire(name)?.groupValues?.get(1)?.lowercase()
    }

    private fun assetOf(file: File): SkyCustomAsset? {
        val digest = NAME_REGEX.matchEntire(file.name)?.groupValues?.get(1) ?: return null
        if (file.length() > MAX_BYTES) return null
        val dimensions = try {
            readHeader(file)
        } catch (io: IOException) {
            Timber.w(io, "Custom sky entry could not be inspected")
            return null
        }.let(SkyJpegProbe::dimensions)
        return SkyCustomAsset(
            sha256 = digest.lowercase(),
            relativePath = relativePathFor(digest),
            width = dimensions?.first ?: 0,
            height = dimensions?.second ?: 0,
            byteSize = file.length()
        )
    }

    /** Reads only the JPEG header, so listing a large library never loads whole images. */
    private fun readHeader(file: File): ByteArray {
        val buffer = ByteArray(HEADER_BYTES)
        FileInputStream(file).use { stream ->
            var total = 0
            while (total < buffer.size) {
                val read = stream.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            return buffer.copyOf(total)
        }
    }

    private fun confinedFile(fileName: String): File? = try {
        val base = directory().canonicalPath + File.separator
        val candidate = File(directory(), fileName).canonicalFile
        if (candidate.path.startsWith(base)) candidate else null
    } catch (io: IOException) {
        Timber.e(io, "Custom sky path could not be resolved")
        null
    }

    companion object {
        /** Directory under `filesDir`. */
        const val SKIES_DIR = "skies"

        /** Longest accepted edge of a user sky, matching the P3 content-addressing budget. */
        const val MAX_EDGE_PX = 4096

        /** Hard ceiling of one imported JPEG. */
        const val MAX_BYTES = 32L * 1024L * 1024L

        private const val TEMP_SUFFIX = ".tmp"
        private const val HEADER_BYTES = 64 * 1024
        private val NAME_REGEX = Regex("^sky_([0-9a-fA-F]{64})\\.jpg$")

        fun fileNameFor(sha256: String): String = "sky_${sha256.lowercase()}.jpg"

        fun relativePathFor(sha256: String): String = "$SKIES_DIR/${fileNameFor(sha256)}"
    }
}
