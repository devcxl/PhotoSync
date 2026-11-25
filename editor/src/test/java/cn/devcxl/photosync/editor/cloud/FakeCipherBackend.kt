package cn.devcxl.photosync.editor.cloud

import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM test double. It keeps a fixed key and IV so envelope bytes can be inspected
 * deterministically, and exposes failure injection for Keystore corruption scenarios.
 */
internal class FakeCipherBackend : KeyCipherBackend {
    val iv = ByteArray(12) { (0x10 + it).toByte() }
    var deleteKeyCalls = 0
        private set
    var failEncryptWith: GeneralSecurityException? = null
    var failDecryptWith: GeneralSecurityException? = null
    var failDecryptWithIo: IOException? = null

    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    override fun encrypt(plaintext: ByteArray): SealedBytes {
        failEncryptWith?.let { throw it }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return SealedBytes(iv = iv.copyOf(), ciphertext = cipher.doFinal(plaintext))
    }

    override fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        failDecryptWith?.let { throw it }
        failDecryptWithIo?.let { throw it }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    override fun deleteKey() {
        deleteKeyCalls++
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
