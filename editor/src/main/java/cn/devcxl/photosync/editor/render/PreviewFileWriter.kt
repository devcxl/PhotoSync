package cn.devcxl.photosync.editor.render

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Writes a new, uniquely named preview without touching any previously published preview.
 * The caller may publish the returned path only after this function succeeds. Older files must not
 * be deleted here: a database row or an in-flight image load may still reference them. They remain
 * disposable cache files and are removed when the photo's edit cache is cleared.
 */
internal object PreviewFileWriter {
    fun write(directory: File, encode: (OutputStream) -> Boolean): String? {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return null
        val file = File.createTempFile("preview_", ".jpg", directory)
        var complete = false
        try {
            FileOutputStream(file).use { stream ->
                if (!encode(stream)) return null
                stream.flush()
            }
            complete = true
            return file.absolutePath
        } finally {
            if (!complete) file.delete()
        }
    }
}
