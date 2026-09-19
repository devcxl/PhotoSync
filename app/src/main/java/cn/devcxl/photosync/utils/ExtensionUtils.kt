package cn.devcxl.photosync.utils

import java.util.Locale

/**
 * Utility object for file extension checks (JPEG / RAW).
 */
object ExtensionUtils {

    /**
     * Returns the lower-cased extension of [path] without the leading dot.
     *
     * @param path Absolute file path or plain file name.
     * @return Lower-cased extension, or an empty string when [path] has none.
     */
    fun fileExtension(path: String): String {
        return path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    }

    fun isJpegExtension(ext: String): Boolean {
        return ext == "jpg" || ext == "jpeg"
    }

    fun isRawExtension(ext: String): Boolean {
        return when (ext) {
            "cr2", "cr3", // Canon
            "arw", "sr2", // Sony
            "nef", "nrw", // Nikon
            "raf", // Fujifilm
            "rw2", // Panasonic
            "dng", // Adobe DNG
            "orf", // Olympus
            "srw", // Samsung
            "pef", // Pentax
            "rwl", // Leica
            "3fr", // Hasselblad
            "mos", // Leaf
            "kdc", "mrw", "mef", "iiq", "x3f" -> true

            else -> false
        }
    }
}
