package cn.devcxl.photosync.editor.cloud

import java.io.File

import android.app.Application

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ByokSecretStoreAndroidPathTest {

    @Test
    fun shouldWriteCiphertextUnderNoBackupFilesDir_whenCreatedFromContext() {
        val context = RuntimeEnvironment.getApplication()
        val backend = FakeCipherBackend()
        val store = ByokSecretStore(context, backend)
        val credential = ByokCredential("openai-default", apiKey = "sk-envelope-path")

        assertTrue(store.save(mapOf(credential.profileId to credential)).isSuccess)

        val blob = File(context.noBackupFilesDir, "byok/credentials.bin")
        assertTrue(blob.isFile)
        assertFalse(File(context.filesDir, "byok/credentials.bin").exists())
        val loaded = store.load() as ByokReadResult.Loaded
        assertEquals(credential, loaded.credentials["openai-default"])
    }
}
