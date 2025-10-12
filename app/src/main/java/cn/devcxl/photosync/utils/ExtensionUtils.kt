package cn.devcxl.photosync.utils

class ExtensionUtils {


    companion object {
        // --- helpers ---
        fun isJpegExtension(ext: String): Boolean {
            return ext == "jpg" || ext == "jpeg"
        }

        fun isRawExtension(ext: String): Boolean {
            return when (ext) {
                // Common RAW extensions
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
}