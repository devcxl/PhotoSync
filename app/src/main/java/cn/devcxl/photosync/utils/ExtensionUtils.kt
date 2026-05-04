package cn.devcxl.photosync.utils

/**
 * Utility object for file extension checks (JPEG / RAW).
 */
object ExtensionUtils {
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