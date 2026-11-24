package cn.devcxl.photosync.editor.domain

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

import timber.log.Timber

/**
 * Serialization of a [LiquifyMesh] into the O(1)-sized liquify payload referenced by
 * `EditGraph.geometry.liquify`.
 *
 * The on-disk form is the raw `128 x 128` `RG16F` byte stream (`65,536 B`, little-endian half
 * floats) compressed with Deflate and named `liquify_128x128.bin`. The `EditGraph` only stores the
 * relative path, the grid size and the SHA-256 of the compressed bytes, so the database row stays
 * constant in size no matter how many brush strokes produced the mesh — and reopening a photo costs
 * one inflate rather than replaying a stroke history.
 */
internal object LiquifyMeshCodec {

    private const val FILE_STEM = "liquify_128x128"
    private const val FILE_SUFFIX = ".bin"

    /**
     * File name of the legacy, non-content-addressed payload.
     *
     * Rows written before payloads became content-addressed still point at this name, so it stays
     * readable forever: nothing in this codec ever deletes it.
     */
    const val FILE_NAME: String = FILE_STEM + FILE_SUFFIX

    private const val BYTES_PER_HALF = 2

    /** Uncompressed payload size: `128 * 128` cells * 2 channels * 2 bytes. */
    const val HALF_FLOAT_BYTE_COUNT = LiquifyMesh.COMPONENT_COUNT * BYTES_PER_HALF

    /** Deflate output larger than this is rejected as corrupt rather than inflated. */
    const val MAX_COMPRESSED_BYTE_COUNT = 256 * 1024

    private const val SHA256_HEX_LENGTH = 64
    private const val EDIT_DIR_PREFIX = "edits/"

    /**
     * Returns the relative path a photo's mesh is stored under, for example
     * `edits/42/liquify_128x128.bin`.
     *
     * This is the pre-content-addressed name of the *live* in-session reference; a completed save
     * stores its payload under [persistedRelativePath] with the digest instead.
     */
    fun persistedRelativePath(photoId: Long): String = "$EDIT_DIR_PREFIX$photoId/$FILE_NAME"

    /**
     * Relative path of the content-addressed payload of [photoId] whose SHA-256 is [sha256].
     *
     * Content addressing is what lets a save write a *new* file instead of overwriting the one the
     * previous edit record verifies: two different grids never share a path, and re-saving the same
     * grid lands on the same name.
     */
    fun persistedRelativePath(photoId: Long, sha256: String): String =
        "$EDIT_DIR_PREFIX$photoId/${fileNameFor(sha256)}"

    /**
     * Content-addressed file name of the payload whose SHA-256 is [sha256].
     *
     * The **whole** digest is part of the name. A truncated digest would be shorter, but a collision
     * would silently make two different grids share one path — and the second save would then rewrite
     * the payload the older row still verifies, which is exactly the corruption content addressing is
     * meant to prevent.
     */
    fun fileNameFor(sha256: String): String = "${FILE_STEM}_$sha256$FILE_SUFFIX"

    /** Encodes [mesh] into the uncompressed `RG16F` byte stream. */
    fun encodeRaw(mesh: LiquifyMesh): ByteArray {
        val bits = mesh.toHalfFloatBits()
        val buffer = ByteBuffer.allocate(HALF_FLOAT_BYTE_COUNT).order(ByteOrder.LITTLE_ENDIAN)
        for (value in bits) {
            buffer.putShort(value)
        }
        return buffer.array()
    }

    /**
     * Decodes an uncompressed `RG16F` byte stream.
     *
     * @return The mesh, or `null` when the payload is not exactly one full grid.
     */
    fun decodeRaw(raw: ByteArray): LiquifyMesh? {
        if (raw.size != HALF_FLOAT_BYTE_COUNT) return null
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val bits = ShortArray(LiquifyMesh.COMPONENT_COUNT)
        for (index in bits.indices) {
            bits[index] = buffer.short
        }
        return LiquifyMesh.fromHalfFloatBits(bits)
    }

    /** Deflates [raw] into the persisted payload form. */
    fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(raw)
            deflater.finish()
            val sink = ByteArrayOutputStream(HALF_FLOAT_BYTE_COUNT / 4)
            val chunk = ByteArray(DEFLATE_CHUNK_BYTES)
            while (!deflater.finished()) {
                val produced = deflater.deflate(chunk)
                if (produced <= 0) break
                sink.write(chunk, 0, produced)
            }
            return sink.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /**
     * Inflates [compressed] back into the raw byte stream.
     *
     * @return The payload, or `null` when it is truncated, larger than one grid or not valid
     *   Deflate data at all.
     */
    fun inflate(compressed: ByteArray): ByteArray? {
        if (compressed.isEmpty()) return null
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val sink = ByteArrayOutputStream(HALF_FLOAT_BYTE_COUNT)
            val chunk = ByteArray(DEFLATE_CHUNK_BYTES)
            while (!inflater.finished()) {
                val produced = inflater.inflate(chunk)
                if (produced <= 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) return null
                    break
                }
                sink.write(chunk, 0, produced)
                if (sink.size() > HALF_FLOAT_BYTE_COUNT) return null
            }
            return sink.toByteArray()
        } catch (_: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }

    /** Returns the lower-case hex SHA-256 of [payload]. */
    fun sha256Hex(payload: ByteArray): String {
        val digest = MessageDigest.getInstance(SHA_256).digest(payload)
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            hex.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return hex.toString()
    }

    /**
     * Verifies, inflates and decodes a persisted liquify payload.
     *
     * Any mismatch — wrong digest, truncation, wrong length, invalid Deflate stream — is rejected
     * with `null` so a damaged file can never reach the shader.
     */
    fun decodePersisted(compressed: ByteArray, expectedSha256: String): LiquifyMesh? {
        if (!isAcceptableDigest(expectedSha256)) {
            Timber.w("Liquify mesh rejected: sha256 digest is not a 64-character hex string")
            return null
        }
        if (compressed.size > MAX_COMPRESSED_BYTE_COUNT) {
            Timber.w("Liquify mesh rejected: compressed payload exceeds the size budget")
            return null
        }
        if (!sha256Hex(compressed).equals(expectedSha256, ignoreCase = true)) {
            Timber.w("Liquify mesh rejected: sha256 digest mismatch")
            return null
        }
        val raw = inflate(compressed) ?: run {
            Timber.w("Liquify mesh rejected: payload is not a complete Deflate stream")
            return null
        }
        return decodeRaw(raw) ?: run {
            Timber.w("Liquify mesh rejected: inflated grid has the wrong length")
            null
        }
    }

    private fun isAcceptableDigest(sha256: String): Boolean =
        sha256.length == SHA256_HEX_LENGTH && sha256.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    private const val DEFLATE_CHUNK_BYTES = 8 * 1024
    private const val SHA_256 = "SHA-256"
    private const val HEX_DIGITS = "0123456789abcdef"
}
