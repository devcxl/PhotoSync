package cn.devcxl.photosync.editor.cloud

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * On-disk envelope: `version | ivLength | iv | ciphertext`.
 *
 * Kept separate from the cipher backend so format handling, truncation and version rejection are
 * covered by plain JVM tests.
 */
internal object SecretEnvelope {
    private const val VERSION: Byte = 1
    private const val HEADER_BYTES = 2
    private const val MIN_IV_LENGTH = 12
    private const val MAX_IV_LENGTH = 32

    /** Parsed envelope pieces handed back to the cipher backend. */
    data class Parsed(val iv: ByteArray, val ciphertext: ByteArray)

    fun write(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(iv.size in MIN_IV_LENGTH..MAX_IV_LENGTH) { "Unsupported GCM IV length" }
        require(ciphertext.isNotEmpty()) { "Ciphertext must not be empty" }
        val blob = ByteArray(HEADER_BYTES + iv.size + ciphertext.size)
        blob[0] = VERSION
        blob[1] = iv.size.toByte()
        iv.copyInto(blob, destinationOffset = HEADER_BYTES)
        ciphertext.copyInto(blob, destinationOffset = HEADER_BYTES + iv.size)
        return blob
    }

    fun parse(blob: ByteArray): Parsed {
        require(blob.size >= HEADER_BYTES) { "Envelope is truncated" }
        require(blob[0] == VERSION) { "Unsupported envelope version" }
        val ivLength = blob[1].toInt() and 0xFF
        require(ivLength in MIN_IV_LENGTH..MAX_IV_LENGTH) { "Invalid envelope IV length" }
        require(blob.size > HEADER_BYTES + ivLength) { "Envelope has no ciphertext" }
        val iv = blob.copyOfRange(HEADER_BYTES, HEADER_BYTES + ivLength)
        val ciphertext = blob.copyOfRange(HEADER_BYTES + ivLength, blob.size)
        return Parsed(iv, ciphertext)
    }
}

/**
 * Compact credential payload codec.
 *
 * The payload never leaves the encrypted envelope, so this deliberately avoids a JSON dependency
 * and stays usable from plain JVM tests. Every length is bounded before allocation so a corrupt
 * payload cannot exhaust memory.
 */
internal object SecretPayload {
    private const val VERSION = 1
    private const val MAX_CREDENTIALS = 64
    private const val MAX_PROFILE_ID_LENGTH = 128
    private const val MAX_API_KEY_LENGTH = 16 * 1024
    private const val MAX_HEADER_NAME_LENGTH = 256
    private const val MAX_HEADER_VALUE_LENGTH = 16 * 1024
    private const val NULL_FIELD = -1

    /** Validates caller input and returns a copy keyed by profile id. */
    fun validate(credentials: Map<String, ByokCredential>): Map<String, ByokCredential> {
        require(credentials.size <= MAX_CREDENTIALS) { "Too many BYOK credential profiles" }
        val validated = LinkedHashMap<String, ByokCredential>(credentials.size)
        for (credential in credentials.values) {
            val profileId = credential.profileId
            require(profileId.isNotBlank()) { "BYOK profile id must not be blank" }
            require(profileId.length <= MAX_PROFILE_ID_LENGTH) { "BYOK profile id is too long" }
            require(credential.apiKey.length <= MAX_API_KEY_LENGTH) { "BYOK API key is too long" }
            val headerName = credential.customAuthHeaderName
            if (headerName != null) {
                require(headerName.isNotBlank()) { "BYOK auth header name must not be blank" }
                require(headerName.length <= MAX_HEADER_NAME_LENGTH) {
                    "BYOK auth header name is too long"
                }
            }
            val headerValue = credential.customAuthHeaderValue
            if (headerValue != null) {
                require(headerName != null) { "BYOK auth header value requires a header name" }
                require(headerValue.length <= MAX_HEADER_VALUE_LENGTH) {
                    "BYOK auth header value is too long"
                }
            }
            validated[profileId] = credential
        }
        return validated
    }

    fun encode(credentials: Map<String, ByokCredential>): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(VERSION)
            out.writeInt(credentials.size)
            for (credential in credentials.values.sortedBy { it.profileId }) {
                out.writeString(credential.profileId)
                out.writeString(credential.apiKey)
                out.writeNullableString(credential.customAuthHeaderName)
                out.writeNullableString(credential.customAuthHeaderValue)
            }
        }
        return buffer.toByteArray()
    }

    fun decode(payload: ByteArray): Map<String, ByokCredential> {
        val input = DataInputStream(ByteArrayInputStream(payload))
        require(input.readInt() == VERSION) { "Unsupported BYOK payload version" }
        val count = input.readInt()
        require(count in 0..MAX_CREDENTIALS) { "Invalid BYOK credential count" }
        val credentials = LinkedHashMap<String, ByokCredential>(count)
        repeat(count) {
            val profileId = input.readRequiredString(MAX_PROFILE_ID_LENGTH)
            require(profileId.isNotBlank()) { "Invalid BYOK profile id" }
            val apiKey = input.readRequiredString(MAX_API_KEY_LENGTH)
            val headerName = input.readNullableString(MAX_HEADER_NAME_LENGTH)
            val headerValue = input.readNullableString(MAX_HEADER_VALUE_LENGTH)
            require(headerName != null || headerValue == null) { "Orphaned BYOK auth header value" }
            credentials[profileId] = ByokCredential(
                profileId = profileId,
                apiKey = apiKey,
                customAuthHeaderName = headerName,
                customAuthHeaderValue = headerValue
            )
        }
        require(input.read() == -1) { "Trailing bytes in BYOK payload" }
        return credentials
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
        bytes.fill(0)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) {
            writeInt(NULL_FIELD)
        } else {
            writeString(value)
        }
    }

    private fun DataInputStream.readRequiredString(maxLength: Int): String =
        readNullableString(maxLength) ?: throw IllegalArgumentException("Missing BYOK payload field")

    private fun DataInputStream.readNullableString(maxLength: Int): String? {
        val length = readInt()
        if (length == NULL_FIELD) return null
        require(length in 0..maxLength) { "Invalid BYOK payload field length" }
        val bytes = ByteArray(length)
        readFully(bytes)
        val value = String(bytes, Charsets.UTF_8)
        bytes.fill(0)
        return value
    }
}
