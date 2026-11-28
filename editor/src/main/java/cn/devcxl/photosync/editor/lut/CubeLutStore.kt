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
data class CubeLutInfo(
    /** Stable identifier persisted in `EditGraph.color.lut.id`. */
    val id: String,
    /** Human readable name shown on the LUT chips. */
    val displayName: String,
    /** Pre-compiled cache file the renderer and preview writer read. */
    val cacheFile: File,
    /** Size of the pre-compiled cache on disk. */
    val byteSize: Long
)

/**
 * On-device library of imported `.cube` 3D LUTs stored as pre-compiled `.lut16f` caches under
 * `filesDir/luts/`.
 *
 * Imports parse and resample the whole file once, write the cache atomically, and retain up to
 * [MEMORY_CACHE_CAPACITY] recently used LUTs in memory so tapping between LUT chips never drops
 * an already-loaded LUT on the canvas.
 */
class CubeLutStore(rootDir: File) {

    private val lutsDir = File(rootDir, LUTS_DIR_NAME)

    private val sampleCache = object : LinkedHashMap<String, Lut3dSamples>(
        MEMORY_CACHE_CAPACITY,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Lut3dSamples>?): Boolean =
            size > MEMORY_CACHE_CAPACITY
    }

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
            CubeLutInfo(id = id, displayName = id, cacheFile = file, byteSize = file.length())
        }.sortedBy { it.displayName.lowercase() }
        installedCache = installed
        return installed
    }

    /**
     * Imports the `.cube` document named [fileName] from [input] and pre-bakes it.
     *
     * @return The installed LUT, or a failure when the document cannot be read, is malformed or
     *   cannot be written.
     */
    fun importFromStream(fileName: String, input: InputStream): Result<CubeLutInfo> {
        val text = try {
            readBoundedText(input)
        } catch (error: IOException) {
            Timber.e(error, "Failed to read the imported .cube stream")
            return Result.failure(error)
        } ?: return Result.failure(IOException("cube document exceeds $MAX_CUBE_BYTES bytes"))

        val lut = CubeLutParser.parse(text).getOrElse { return Result.failure(it) }
        val cacheBytes = CubeLutParser.encodeCache(lut)
        val id = uniqueId(sanitizeId(fileName))
        return writeAtomically(id, cacheBytes).map { cacheFile ->
            installedCache = null
            Timber.i("Imported a 3D LUT with %d grid samples", lut.size)
            CubeLutInfo(
                id = id,
                displayName = lut.title ?: id,
                cacheFile = cacheFile,
                byteSize = cacheFile.length()
            )
        }
    }

    /** Loads the decoded samples of [id], using the bounded LRU memory cache when warm. */
    internal fun loadSamples(id: String): Result<Lut3dSamples> {
        cachedSamplesIf(id)?.let { return Result.success(it) }
        val file = cacheFile(id) ?: return Result.failure(FileNotFoundException(id))
        return try {
            val bytes = file.readBytes()
            val samples = Lut16fCache.samplesFromCache(bytes)
                ?: return Result.failure(IllegalStateException("LUT cache for $id is not a .lut16f payload"))
            synchronized(sampleCache) {
                sampleCache[id] = samples
            }
            Result.success(samples)
        } catch (error: IOException) {
            Timber.e(error, "Failed to read the imported 3D LUT cache")
            Result.failure(error)
        }
    }

    /** Returns the cached samples of [id] without touching disk, or `null` when not cached. */
    internal fun cachedSamplesIf(id: String): Lut3dSamples? = synchronized(sampleCache) { sampleCache[id] }

    /** Returns the cache file of [id] when [id] is a safe, installed identifier. */
    fun cacheFile(id: String): File? {
        if (id.isBlank() || id.length > MAX_ID_LENGTH) return null
        if (id.any { it == '/' || it == '\\' } || id == "." || id == "..") return null
        val file = File(lutsDir, id + CACHE_SUFFIX)
        return file.takeIf { it.isFile }
    }

    /**
     * Deletes the installed LUT [id], leaving any `EditGraph` that references it untouched.
     *
     * The graph then simply fails to resolve its LUT and renders without it, exactly like a missing
     * model file. Deleting the same id twice is a no-op.
     *
     * @return True when no cache file remains afterwards.
     */
    fun delete(id: String): Boolean {
        val file = File(lutsDir, id + CACHE_SUFFIX)
        installedCache = null
        synchronized(sampleCache) { sampleCache.remove(id) }
        if (!file.exists()) return true
        val deleted = file.delete()
        if (!deleted) Timber.w("Imported 3D LUT could not be deleted")
        return deleted
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
                append(
                    if (character.isLetterOrDigit() || character == ' ' || character == '-' || character == '_') {
                        character
                    } else {
                        '_'
                    }
                )
            }
        }.trim().take(MAX_ID_LENGTH)
        return sanitized.ifBlank { DEFAULT_ID }
    }

    private fun uniqueId(baseId: String): String {
        if (!File(lutsDir, baseId + CACHE_SUFFIX).exists()) return baseId
        var suffix = 2
        while (true) {
            val suffixTag = "_$suffix"
            val candidate = baseId.take((MAX_ID_LENGTH - suffixTag.length).coerceAtLeast(1)) + suffixTag
            if (!File(lutsDir, candidate + CACHE_SUFFIX).exists()) return candidate
            suffix++
        }
    }

    private companion object {
        private const val LUTS_DIR_NAME = "luts"
        private const val CACHE_SUFFIX = ".lut16f"
        private const val TEMP_SUFFIX = ".lut16f.tmp"
        private const val MAX_CUBE_BYTES = CubeLutParser.MAX_TEXT_CHARS.toLong()
        private const val MAX_ID_LENGTH = 64
        private const val DEFAULT_ID = "lut"
        private const val READ_CHUNK_BYTES = 8192
        private const val MEMORY_CACHE_CAPACITY = 8
    }
}
