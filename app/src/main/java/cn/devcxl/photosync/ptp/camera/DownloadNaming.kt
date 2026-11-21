package cn.devcxl.photosync.ptp.camera

import java.io.File
import java.util.Random

/**
 * Where a downloaded object is written.
 *
 * Names come from the camera and are therefore untrusted: they can be blank, contain path
 * separators, or collide with a file already on disk.
 */
internal object DownloadNaming {
    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|]")

    /**
     * Turns a camera file name into a usable local name.
     *
     * @return the sanitized name, or null when the camera did not provide one, in which
     *   case the caller generates a name
     */
    fun sanitize(rawName: String?): String? {
        val trimmed = rawName?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return null
        }
        val replaced = trimmed.replace(FORBIDDEN, "_")
        if (replaced.isBlank() || replaced.isDotsOnly()) {
            return null
        }
        return replaced
    }

    /**
     * Whether the name is only dots, i.e. `.` or `..`.
     *
     * Sanitizing keeps dots, and a bare `..` resolves to the parent directory, so those
     * names have to be refused rather than written.
     */
    private fun String.isDotsOnly(): Boolean = all { it == '.' }

    /**
     * A name for an object the camera did not name.
     *
     * The extension is a guess by design: the export path re-detects the format.
     */
    fun generatedName(random: Random): String {
        val value = random.nextInt().toLong() and 0xffff_ffffL
        return "photo_$value.jpg"
    }

    /**
     * A path in [directory] that does not exist yet.
     *
     * A second photo with the same name gets `_1`, `_2`, … before its extension rather than
     * overwriting the first.
     */
    fun uniqueFile(directory: File, fileName: String): File {
        val candidate = File(directory, fileName)
        if (!candidate.exists()) {
            return candidate
        }
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val extension = if (dot > 0) fileName.substring(dot) else ""
        var suffix = 1
        while (true) {
            val next = File(directory, "${base}_$suffix$extension")
            if (!next.exists()) {
                return next
            }
            suffix++
        }
    }
}
