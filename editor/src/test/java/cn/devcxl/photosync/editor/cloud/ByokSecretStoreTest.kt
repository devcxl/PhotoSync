package cn.devcxl.photosync.editor.cloud

import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.CopyOnWriteArrayList

import android.security.keystore.KeyPermanentlyInvalidatedException

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

import timber.log.Timber

class ByokSecretStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var backend: FakeCipherBackend
    private lateinit var store: ByokSecretStore

    @Before
    fun setUp() {
        backend = FakeCipherBackend()
        store = ByokSecretStore(File(tempFolder.root, "byok"), backend)
    }

    @Test
    fun shouldRoundTripMultipleCredentials_whenSaved() {
        val openAi = ByokCredential("openai-default", apiKey = "sk-alice-123")
        val comfy = ByokCredential(
            profileId = "comfy-home",
            apiKey = "",
            customAuthHeaderName = "X-Comfy-Token",
            customAuthHeaderValue = "t0ken-value"
        )

        val saved = store.save(mapOf(openAi.profileId to openAi, comfy.profileId to comfy))

        assertTrue(saved.isSuccess)
        val loaded = store.load() as ByokReadResult.Loaded
        assertEquals(setOf("openai-default", "comfy-home"), loaded.credentials.keys)
        assertEquals(openAi, loaded.credentials["openai-default"])
        assertEquals(comfy, loaded.credentials["comfy-home"])
    }

    @Test
    fun shouldReturnEmpty_whenNoBlobWasWritten() {
        assertEquals(ByokReadResult.Empty, store.load())
        assertEquals(0, backend.deleteKeyCalls)
    }

    @Test
    fun shouldWriteVersionedEnvelopeWithoutPlaintext_whenSaved() {
        val secret = "sk-never-on-disk"
        store.save(mapOf("openai" to ByokCredential("openai", apiKey = secret)))

        val blob = secretBlob().readBytes()
        assertEquals(SECRET_ENVELOPE_VERSION, blob[0].toInt())
        val ivLength = blob[1].toInt() and 0xFF
        assertEquals(backend.iv.size, ivLength)
        val ciphertext = blob.copyOfRange(2 + ivLength, blob.size)
        val decrypted = backend.decrypt(backend.iv, ciphertext)
        assertTrue(String(decrypted, Charsets.UTF_8).contains(secret))
        assertFalse(String(blob, Charsets.ISO_8859_1).contains(secret))
    }

    @Test
    fun shouldRejectBlankProfileId_whenSaving() {
        val result = store.save(mapOf("" to ByokCredential("", apiKey = "sk-orphan")))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertFalse(secretBlob().exists())
    }

    @Test
    fun shouldDeleteBlobAndAlias_whenCiphertextIsTamperedWith() {
        store.save(mapOf("openai" to ByokCredential("openai", apiKey = "sk-tamper-target")))
        val file = secretBlob()
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        val result = store.load()

        assertEquals(
            ByokReadResult.RecoveryRequired(ByokRecoveryReason.CIPHERTEXT_CORRUPT),
            result
        )
        assertFalse(file.exists())
        assertEquals(1, backend.deleteKeyCalls)
    }

    @Test
    fun shouldDeleteBlobAndAlias_whenKeyWasPermanentlyInvalidated() {
        store.save(mapOf("openai" to ByokCredential("openai", apiKey = "sk-lost-key")))
        backend.failDecryptWith = KeyPermanentlyInvalidatedException()

        val result = store.load()

        assertEquals(ByokReadResult.RecoveryRequired(ByokRecoveryReason.KEY_UNAVAILABLE), result)
        assertFalse(secretBlob().exists())
        assertEquals(1, backend.deleteKeyCalls)
    }

    @Test
    fun shouldDeleteBlobAndAlias_whenEnvelopeVersionIsUnknown() {
        store.save(mapOf("openai" to ByokCredential("openai", apiKey = "sk-version")))
        val file = secretBlob()
        val bytes = file.readBytes()
        bytes[0] = 9
        file.writeBytes(bytes)

        val result = store.load()

        assertEquals(
            ByokReadResult.RecoveryRequired(ByokRecoveryReason.CIPHERTEXT_CORRUPT),
            result
        )
        assertFalse(file.exists())
        assertEquals(1, backend.deleteKeyCalls)
    }

    @Test
    fun shouldDeleteBlobAndAlias_whenAuthenticatedPayloadIsTruncated() {
        val truncatedPayload = byteArrayOf(0, 0, 0, 1)
        val sealed = backend.encrypt(truncatedPayload)
        val envelope = SecretEnvelope.write(sealed.iv, sealed.ciphertext)
        tempFolder.root.resolve("byok").mkdirs()
        secretBlob().writeBytes(envelope)

        val result = store.load()

        assertEquals(
            ByokReadResult.RecoveryRequired(ByokRecoveryReason.CIPHERTEXT_CORRUPT),
            result
        )
        assertFalse(secretBlob().exists())
        assertEquals(1, backend.deleteKeyCalls)
    }

    @Test
    fun shouldKeepBlobAndReturnUnavailable_whenKeystoreFailsTransiently() {
        store.save(mapOf("openai" to ByokCredential("openai", apiKey = "sk-transient")))
        backend.failDecryptWithIo = IOException("keystore busy")

        val result = store.load()

        assertEquals(ByokReadResult.Unavailable, result)
        assertTrue(secretBlob().exists())
        assertEquals(0, backend.deleteKeyCalls)
    }

    @Test
    fun shouldKeepExistingCredentials_whenEncryptionFailsTransiently() {
        val stored = ByokCredential("openai", apiKey = "sk-keep-me")
        assertTrue(store.save(mapOf(stored.profileId to stored)).isSuccess)
        val committedBlob = secretBlob().readBytes()

        backend.failEncryptWith = RuntimeException("keystore busy")
        val result = store.save(
            mapOf("openai" to ByokCredential("openai", apiKey = "sk-replacement"))
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(0, backend.deleteKeyCalls)
        assertTrue(secretBlob().exists())
        assertArrayEquals(committedBlob, secretBlob().readBytes())
        backend.failEncryptWith = null
        val loaded = store.load() as ByokReadResult.Loaded
        assertEquals(stored, loaded.credentials["openai"])
    }

    @Test
    fun shouldReturnUnavailableAndKeepBlob_whenEncryptionFailsWithGeneralSecurityException() {
        store.save(mapOf("openai" to ByokCredential("openai", apiKey = "sk-survivor")))
        backend.failEncryptWith = GeneralSecurityException("keystore rejected the operation")

        val result = store.save(mapOf("openai" to ByokCredential("openai", apiKey = "sk-new")))

        assertTrue(result.isFailure)
        assertEquals(0, backend.deleteKeyCalls)
        assertTrue(secretBlob().exists())
        backend.failEncryptWith = null
        assertEquals(
            "sk-survivor",
            (store.load() as ByokReadResult.Loaded).credentials.getValue("openai").apiKey
        )
    }

    @Test
    fun shouldClearExistingCredentials_whenTheKeyIsPermanentlyInvalidatedDuringEncryption() {
        store.save(mapOf("openai" to ByokCredential("openai", apiKey = "sk-doomed")))
        backend.failEncryptWith = KeyPermanentlyInvalidatedException()

        val result = store.save(mapOf("openai" to ByokCredential("openai", apiKey = "sk-doomed")))

        assertTrue(result.isFailure)
        assertEquals(1, backend.deleteKeyCalls)
        assertFalse(secretBlob().exists())
        assertEquals(ByokReadResult.Empty, store.load())
    }

    @Test
    fun shouldClearAllCredentials_whenClearAllIsCalled() {
        store.save(mapOf("openai" to ByokCredential("openai", apiKey = "sk-clear-me")))

        assertTrue(store.clearAll().isSuccess)

        assertFalse(secretBlob().exists())
        assertEquals(ByokReadResult.Empty, store.load())
    }

    @Test
    fun shouldNeverLogPlaintextOrCiphertext_whenRecoveringFromCorruption() {
        val secret = "sk-do-not-log-me-123"
        store.save(mapOf("openai" to ByokCredential("openai", apiKey = secret)))
        val file = secretBlob()
        val blob = file.readBytes()
        val ciphertextFragment = blob.copyOfRange(20, 28).joinToString("") { "%02x".format(it) }

        val messages = CopyOnWriteArrayList<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                messages += message
                t?.message?.let(messages::add)
            }
        }
        Timber.plant(tree)
        try {
            blob[blob.lastIndex] = 0
            file.writeBytes(blob)
            assertTrue(store.load() is ByokReadResult.RecoveryRequired)
        } finally {
            Timber.uproot(tree)
        }

        assertTrue(messages.isNotEmpty())
        assertFalse(messages.any { it.contains(secret) })
        assertFalse(messages.any { it.contains(ciphertextFragment) })
    }

    @Test
    fun shouldNotLogSecret_whenEncryptionFails() {
        val secret = "sk-never-logged-on-failure"
        backend.failEncryptWith = GeneralSecurityException("keystore rejected the operation")

        val messages = CopyOnWriteArrayList<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                messages += message
                t?.message?.let(messages::add)
            }
        }
        Timber.plant(tree)
        val result = try {
            store.save(mapOf("openai" to ByokCredential("openai", apiKey = secret)))
        } finally {
            Timber.uproot(tree)
        }

        assertTrue(result.isFailure)
        assertTrue(messages.isNotEmpty())
        assertFalse(messages.any { it.contains(secret) })
        assertFalse(messages.any { it.contains("sk-") })
    }

    private fun secretBlob(): File = File(tempFolder.root, "byok/credentials.bin")

    private companion object {
        const val SECRET_ENVELOPE_VERSION = 1
    }
}
