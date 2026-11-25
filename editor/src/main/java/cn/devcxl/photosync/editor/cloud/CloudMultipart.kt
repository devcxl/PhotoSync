package cn.devcxl.photosync.editor.cloud

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Streaming `multipart/form-data` builder used by the BYOK uploads.
 *
 * File parts are streamed from disk, so a multi-megabyte photo is never copied into a byte array.
 * [contentLength] is computed before the first byte is written and is fed to
 * `HttpURLConnection.setFixedLengthStreamingMode`, which keeps the upload out of a memory buffer.
 */
internal class CloudMultipart {

    private val boundary = "photosync-${UUID.randomUUID()}"
    private val parts = ArrayList<MultipartPart>()

    /** MIME type including the generated boundary. */
    val contentType: String
        get() = "multipart/form-data; boundary=$boundary"

    /** Adds a plain text form field. */
    fun addField(name: String, value: String): CloudMultipart {
        parts += MultipartPart.Field(name, value)
        return this
    }

    /** Adds a file part streamed from [file]. */
    fun addFile(name: String, fileName: String, contentType: String, file: File): CloudMultipart {
        require(isSafePartValue(fileName)) { "Unsafe multipart file name" }
        require(isSafePartValue(contentType)) { "Unsafe multipart content type" }
        parts += MultipartPart.FilePart(name, fileName, contentType, file)
        return this
    }

    /** Exact number of bytes [writeTo] will emit. */
    fun contentLength(): Long {
        var total = 0L
        for (part in parts) {
            total += part.headerBytes().size.toLong()
            total += part.contentLength()
            total += CRLF.size
        }
        return total + closingBoundaryBytes().size
    }

    /**
     * Writes the whole body to [output].
     *
     * @throws IOException when a file cannot be read; the caller maps it to a cloud error.
     */
    fun writeTo(output: OutputStream) {
        val buffered = if (output is BufferedOutputStream) output else BufferedOutputStream(output)
        for (part in parts) {
            buffered.write(part.headerBytes())
            part.writeContentTo(buffered)
            buffered.write(CRLF)
        }
        buffered.write(closingBoundaryBytes())
        buffered.flush()
    }

    private fun closingBoundaryBytes(): ByteArray = "--$boundary--\r\n".toByteArray()

    private fun MultipartPart.headerBytes(): ByteArray {
        val builder = StringBuilder()
        builder.append("--").append(boundary).append("\r\n")
        builder.append("Content-Disposition: form-data; name=\"").append(name).append('"')
        if (this is MultipartPart.FilePart) {
            builder.append("; filename=\"").append(fileName).append('"')
        }
        builder.append("\r\n")
        if (this is MultipartPart.FilePart) {
            builder.append("Content-Type: ").append(contentType).append("\r\n")
        }
        builder.append("\r\n")
        return builder.toString().toByteArray()
    }

    private sealed class MultipartPart(val name: String) {
        abstract fun contentLength(): Long
        abstract fun writeContentTo(output: OutputStream)

        class Field(name: String, private val value: String) : MultipartPart(name) {
            override fun contentLength(): Long = value.toByteArray().size.toLong()
            override fun writeContentTo(output: OutputStream) = output.write(value.toByteArray())
        }

        class FilePart(
            name: String,
            val fileName: String,
            val contentType: String,
            private val file: File
        ) : MultipartPart(name) {
            override fun contentLength(): Long = file.length()
            override fun writeContentTo(output: OutputStream) {
                FileInputStream(file).use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) return
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private companion object {
        val CRLF = "\r\n".toByteArray()
        const val BUFFER_BYTES = 16 * 1024

        /** Rejects CR/LF header injection and quote-breaking names. */
        fun isSafePartValue(value: String): Boolean =
            value.isNotEmpty() && value.none { it == '\r' || it == '\n' || it == '"' }
    }
}
