package cn.devcxl.photosync.editor.lut

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

import timber.log.Timber

import cn.devcxl.photosync.editor.domain.CubeLutParser
import cn.devcxl.photosync.editor.domain.Lut16fCache
import cn.devcxl.photosync.editor.domain.Lut3dSamples

/** One imported `.cube` LUT as it exists on disk, pre-compiled into a `.lut16f` cache. */
internal data class CubeLutInfo(
    /** Stable identifier persisted in `EditGraph.color.lut.id`. */
    val id: String,
    /** Human readable name shown on the LUT chips. */
    val displayName: String,
    /** Pre-compiled cache file the renderer and preview writer read. */
    val cacheFile: File
)

/**
 * On-device library of imported `.cube` 3D LUTs stored as pre-compiled `.lut16f` caches under
 * `filesDir/luts/`.
 *
 * Imports parse and resample the whole file once, write the cache atomically, and keep the decoded
 * samples in a single-entry memory cache so switching back to the previous LUT never re-reads disk.
 */
internal class CubeLutStore(rootDir: File) {

    private val lutsDir = File(rootDir, LUTS_DIR_NAME)

    @Volatile
    private var cachedId: String? = null

    @Volatile
    private var cachedSamples: Lut3dSamples? = null

    @Volatile
    private var installedCache: List<CubeLutInfo>? = null

    /** Lists every installed LUT, sorted by display name. */
    fun installedLuts(): List<CubeLutInfo> {
        installedCache?.let { return it }
        val files = lutsDir.listFiles { file ->
            file.isFile && file.name.endsWith(CACHE_SUFFIX, ignoreCase = true)
        } ?: return emptyList()
        val installed = files.mapNotNull { file ->
            val id = file.name.removeSuffix(CACHE_SUFFIX).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CubeLutInfo(id = id, displayName = id, cacheFile = file)
        }.sortedBy { it.displayName.lowercase() }
        installedCache = installed
        return installed
    }

    /**
     * Imports the `.cube` document named [fileName] from [input] and pre-bakes it.
     *
     * @return The installed LUT, or a failure when the document is malformed or cannot be written.
     */
    fun importFromStream(fileName: String, input: InputStream): Result<CubeLutInfo> {
        val text = readBoundedText(input) ?: return Result.failure(
            IOException("cube document exceeds $MAX_CUBE_BYTES bytes")
        )
        val lut = CubeLutParser.parse(text).getOrElse { return Result.failure(it) }
        val cacheBytes = CubeLutParser.encodeCache(lut)
        val id = sanitizeId(fileName)
        return writeAtomically(id, cacheBytes).map { cacheFile ->
            installedCache = null
            Timber.i("Imported a 3D LUT with %d grid samples", lut.size)
            CubeLutInfo(id = id, displayName = id, cacheFile = cacheFile)
        }
    }

    /**
     * Loads the decoded samples of [id], using the memory cache when it already holds them.
     */
    fun loadSamples(id: String): Result<Lut3dSamples> {
        cachedSamplesIf(id)?.let { return Result.success(it) }
        val file = cacheFile(id) ?: return Result.failure(FileNotFoundException(id))
        return try {
            val bytes = file.readBytes()
            val samples = Lut16fCache.samplesFromCache(bytes)
                ?: return Result.failure(IllegalStateException("LUT cache for $id is not a .lut16f payload"))
            cachedId = id
            cachedSamples = samples
            Result.success(samples)
        } catch (error: IOException) {
            Timber.e(error, "Failed to read the imported 3D LUT cache")
            Result.failure(error)
        }
    }

    /** Returns the cached samples of [id] without touching disk, or `null` when not cached. */
    fun cachedSamplesIf(id: String): Lut3dSamples? =
        if (cachedId == id) cachedSamples else null

    /** Returns the cache file of [id] when [id] is a safe, installed identifier. */
    fun cacheFile(id: String): File? {
        if (id.isBlank() || id.length > MAX_ID_LENGTH) return null
        if (id.any { it == '/' || it == '\\' } || id == "." || id == "..") return null
        val file = File(lutsDir, id + CACHE_SUFFIX)
        return file.takeIf { it.isFile }
    }

    private fun writeAtomically(id: String, cacheBytes: ByteArray): Result<File> {
        val directoryReady = lutsDir.isDirectory || lutsDir.mkdirs()
        if (!directoryReady) return Result.failure(IOException("Cannot create the LUT directory"))
        val target = File(lutsDir, id + CACHE_SUFFIX)
        val temporary = File(lutsDir, id + TEMP_SUFFIX)
        return try {
            FileOutputStream(temporary).use { stream ->
                stream.write(cacheBytes)
                stream.flush()
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                temporary.delete()
                Result.failure(IOException("Cannot publish the LUT cache atomically"))
            } else {
                Result.success(target)
            }
        } catch (error: IOException) {
            temporary.delete()
            Timber.e(error, "Failed to persist the 3D LUT cache")
            Result.failure(error)
        }
    }

    private fun readBoundedText(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var total = 0
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            total += read
            if (total > MAX_CUBE_BYTES) return null
            output.write(chunk, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun sanitizeId(fileName: String): String {
        val stem = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        val sanitized = buildString {
            for (character in stem) {
                append(if (character.isLetterOrDigit() || character == ' ' || character == '-' || character == '_') {
                    character
                } else {
                    '_'
                })
            }
        }.trim().take(MAX_ID_LENGTH)
        return sanitized.ifBlank { DEFAULT_ID }
    }

    private companion object {
        private const val LUTS_DIR_NAME = "luts"
        private const val CACHE_SUFFIX = ".lut16f"
        private const val TEMP_SUFFIX = ".lut16f.tmp"
        private const val MAX_CUBE_BYTES = CubeLutParser.MAX_TEXT_CHARS.toLong()
        private const val MAX_ID_LENGTH = 64
        private const val DEFAULT_ID = "lut"
        private const val READ_CHUNK_BYTES = 8192
    }
}
