package cn.devcxl.photosync.editor.vision

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

import timber.log.Timber

/**
 * Streaming file primitives shared by [AiModelDownloadManager]'s download and SAF import paths.
 *
 * Both paths must honour the same bounds: never accept more than the pinned byte count, keep the
 * `.part` file resumable, and report progress without ever holding a whole model in memory.
 */
internal object AiModelTransferHelper {

    /**
     * Copies [input] into [output] while refusing to exceed the pinned model size.
     *
     * @return [AiModelFailureReason.SIZE_MISMATCH] when the source is larger than the catalog entry,
     *   `null` when the copy ended at or below the pinned size.
     */
    fun copyBounded(
        spec: AiModelSpec,
        input: InputStream,
        output: FileOutputStream,
        onProgress: (Float) -> Unit
    ): AiModelFailureReason? {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            if (written + read > spec.byteSize) {
                Timber.w("Local model import is larger than the pinned %d bytes", spec.byteSize)
                return AiModelFailureReason.SIZE_MISMATCH
            }
            output.write(buffer, 0, read)
            written += read
            onProgress((written.toFloat() / spec.byteSize.toFloat()).coerceIn(0f, 1f))
        }
        output.flush()
        return null
    }

    /**
     * Writes [body] into [partFile], appending when [alreadyWritten] bytes were already accepted
     * through a `Range` resume. Never writes past the pinned model size.
     */
    fun writeBody(
        spec: AiModelSpec,
        partFile: File,
        body: InputStream,
        alreadyWritten: Long,
        onProgress: (Float) -> Unit
    ) {
        FileOutputStream(partFile, alreadyWritten > 0L).use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            var written = alreadyWritten
            while (written < spec.byteSize) {
                val read = body.read(buffer)
                if (read <= 0) break
                val allowed = minOf(read.toLong(), spec.byteSize - written).toInt()
                output.write(buffer, 0, allowed)
                written += allowed
                onProgress((written.toFloat() / spec.byteSize.toFloat()).coerceIn(0f, 1f))
            }
            output.flush()
        }
    }

    /** Bytes occupied by [directory] or [file] on disk. */
    fun totalBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { totalBytes(it) } ?: 0L
    }

    /** Lower-case hex SHA-256 of the whole file. */
    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private const val BUFFER_BYTES = 64 * 1024
}
