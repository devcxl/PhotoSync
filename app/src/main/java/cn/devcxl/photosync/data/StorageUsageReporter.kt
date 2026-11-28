package cn.devcxl.photosync.data

import java.io.File
import java.util.Locale

import timber.log.Timber

/**
 * One bounded storage category shown by the settings page.
 *
 * @property directoryName Directory name under the app's `filesDir` or `cacheDir`.
 */
enum class StorageCategory(val directoryName: String) {

    /** On-demand `.onnx` models plus the MediaPipe portrait model. */
    MODELS("models"),

    /** Content-addressed AI payloads (masks, cloud layers, inpaint patches, liquify meshes). */
    AI_EDITS("edits"),

    /** Screen-sized preview JPEGs rendered by the editor. */
    PREVIEW_CACHE("edits"),

    /** Pre-compiled imported 3D LUTs. */
    LUTS("luts"),

    /** Content-addressed user-imported sky photographs. */
    SKY("skies")
}

/**
 * Byte counts per [StorageCategory], as read from disk at one instant.
 *
 * @property byCategory Measured bytes of every category; missing directories read as `0`.
 */
data class StorageUsageSnapshot(
    val byCategory: Map<StorageCategory, Long>
) {

    /** Sum of every category. */
    val totalBytes: Long get() = byCategory.values.sum()

    /** Bytes of [category], or `0` when it was not measured. */
    fun bytes(category: StorageCategory): Long = byCategory[category] ?: 0L
}

/**
 * Pure-directory accounting and cleanup for the five storage categories of the settings page.
 *
 * ## Contract
 * - Only files under [filesDir] / [cacheDir] are touched. The `Room` database lives elsewhere, so
 *   clearing a category can never delete a saved `EditGraph` row.
 * - A missing directory reads as `0` and a missing directory clears as a no-op.
 * - Each category is cleared independently; `clearAll` is exactly the union of those operations.
 * - Deleting AI assets, LUTs or skies may leave an `EditGraph` referencing a payload that is no
 *   longer there. The renderer already degrades such references to "no effect"; the settings page
 *   confirms that scope before calling this class.
 *
 * The class is Android-free apart from the directories it is given, so unit tests run against a
 * temporary folder.
 */
class StorageUsageReporter(
    private val filesDir: File,
    private val cacheDir: File
) {

    /** Absolute directory backing [category]. */
    fun directoryOf(category: StorageCategory): File = when (category) {
        StorageCategory.PREVIEW_CACHE -> File(cacheDir, category.directoryName)
        else -> File(filesDir, category.directoryName)
    }

    /** Recursive byte size of [category]; a missing or unreadable directory yields `0`. */
    fun bytes(category: StorageCategory): Long = directoryBytes(directoryOf(category))

    /** Measures every category once. */
    fun snapshot(): StorageUsageSnapshot =
        StorageUsageSnapshot(StorageCategory.entries.associateWith(::bytes))

    /**
     * Deletes the contents of [category].
     *
     * @return The number of bytes the measurement reported before deletion; `0` when nothing was
     *   there or the delete was refused.
     */
    fun clear(category: StorageCategory): Long {
        val directory = directoryOf(category)
        val before = directoryBytes(directory)
        if (!directory.isDirectory) return 0L
        directory.listFiles()?.forEach { child ->
            if (!child.deleteRecursively()) {
                Timber.w("Could not delete a storage entry under %s", directory.name)
            }
        }
        return before - directoryBytes(directory)
    }

    /**
     * Clears every category; the direct union of [clear] over [StorageCategory.entries].
     *
     * @return Total bytes released.
     */
    fun clearAll(): Long = StorageCategory.entries.sumOf(::clear)

    private fun directoryBytes(directory: File): Long {
        if (!directory.isDirectory) return 0L
        return try {
            directory.walkTopDown()
                .filter { it.isFile }
                .sumOf { it.length() }
        } catch (error: SecurityException) {
            // A refused stat must not crash the settings page; the number stays conservative.
            Timber.w(error, "Cannot stat %s", directory.name)
            0L
        }
    }

    companion object {

        /**
         * Human-readable size used by the settings page.
         *
         * Deterministic (`Locale.US`, 1024-based) so the Robolectric test can assert exact text;
         * `0 B` is shown instead of an empty value.
         */
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0L) return "0 B"
            val kib = bytes / 1024.0
            if (kib < 1.0) return "$bytes B"
            val mib = kib / 1024.0
            if (mib < 1.0) return String.format(Locale.US, "%.1f KB", kib)
            val gib = mib / 1024.0
            if (gib < 1.0) return String.format(Locale.US, "%.1f MB", mib)
            return String.format(Locale.US, "%.2f GB", gib)
        }
    }
}
