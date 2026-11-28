package cn.devcxl.photosync.editor.cloud

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/** One parsed `multipart/form-data` part. */
internal class MultipartPart(
    val name: String,
    val fileName: String?,
    val contentType: String?,
    val bytes: ByteArray
)

/** Encodes a solid-colour image with the real Android codec (requires NATIVE graphics). */
internal fun imageBytes(
    width: Int = 8,
    height: Int = 6,
    format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG,
    color: Int = Color.RED
): ByteArray {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(color)
        val output = ByteArrayOutputStream()
        check(bitmap.compress(format, 100, output)) { "Could not encode the test image" }
        return output.toByteArray()
    } finally {
        bitmap.recycle()
    }
}

/** Stages [bytes] as a [CloudUploadHandle] whose file is deleted when the request closes it. */
internal fun uploadHandle(
    directory: File,
    bytes: ByteArray,
    width: Int = 8,
    height: Int = 6,
    fileName: String = "upload-${UUID.randomUUID()}.jpg"
): CloudUploadHandle {
    val file = File(directory, fileName)
    file.writeBytes(bytes)
    return CloudUploadHandle(file, width, height)
}

/**
 * Produces a real editable-mask PNG: the left half is painted white (editable, `alpha = 0` once
 * encoded) and the right half stays untouched (preserved, `alpha = 255`).
 */
internal fun editableMaskHandle(
    directory: File,
    width: Int = 8,
    height: Int = 6
): CloudUploadHandle {
    val painted = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        Canvas(painted).drawRect(
            0f,
            0f,
            width / 2f,
            height.toFloat(),
            Paint().apply { color = Color.WHITE }
        )
        val file = File(directory, "mask-${UUID.randomUUID()}.png")
        CloudMaskPngEncoder.writePng(painted, width, height, file)
        return CloudUploadHandle(file, width, height)
    } finally {
        painted.recycle()
    }
}

/** Asserts that a multipart mask part is a PNG whose painted half is transparent. */
internal fun assertEditableMask(png: ByteArray, width: Int, height: Int) {
    val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size)
    assertNotNull("The multipart mask was not a decodable image", bitmap)
    try {
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        assertEquals(
            "Painted mask pixels must be transparent (editable)",
            0,
            Color.alpha(bitmap.getPixel(width / 4, height / 2))
        )
        assertEquals(
            "Untouched mask pixels must stay opaque (preserved)",
            255,
            Color.alpha(bitmap.getPixel(width * 3 / 4, height / 2))
        )
    } finally {
        bitmap.recycle()
    }
}

/**
 * Splits a multipart body using the boundary from the request Content-Type.
 *
 * ISO-8859-1 is used as a byte-transparent string encoding, so binary image parts round-trip
 * exactly.
 */
internal fun parseMultipart(request: TestHttpRequest): List<MultipartPart> {
    val contentType = request.header("content-type").orEmpty()
    val boundary = Regex("""boundary=([^;]+)""").find(contentType)?.groupValues?.get(1)
        ?: error("No multipart boundary in: $contentType")
    val body = String(request.body, Charsets.ISO_8859_1)
    val parts = ArrayList<MultipartPart>()
    for (chunk in body.split("--$boundary")) {
        val trimmed = chunk.trim('\r', '\n')
        if (trimmed.isEmpty() || trimmed == "--") continue
        val separator = chunk.indexOf("\r\n\r\n")
        if (separator < 0) continue
        val headerBlock = chunk.substring(0, separator)
        val name = Regex("""name="([^"]*)"""").find(headerBlock)?.groupValues?.get(1) ?: continue
        val fileName = Regex("""filename="([^"]*)"""").find(headerBlock)?.groupValues?.get(1)
        val partContentType = Regex("""(?im)^Content-Type:\s*(\S+)\s*$""")
            .find(headerBlock)?.groupValues?.get(1)
        var content = chunk.substring(separator + 4)
        if (content.endsWith("\r\n")) content = content.dropLast(2)
        parts += MultipartPart(
            name = name,
            fileName = fileName,
            contentType = partContentType,
            bytes = content.toByteArray(Charsets.ISO_8859_1)
        )
    }
    return parts
}

/** Finds one part by form field name. */
internal fun List<MultipartPart>.part(name: String): MultipartPart =
    firstOrNull { it.name == name } ?: error("No multipart part named $name")

/** Value of a text part. */
internal fun List<MultipartPart>.text(name: String): String =
    String(part(name).bytes, Charsets.UTF_8)
