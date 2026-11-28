package cn.devcxl.photosync.editor.cloud

import java.io.File
import java.util.UUID

import android.app.Application
import android.content.SharedPreferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CloudProviderConfigStoreTest {

    private lateinit var context: Application
    private lateinit var prefs: SharedPreferences
    private lateinit var secretDir: File
    private lateinit var store: CloudProviderConfigStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences(
            "cloud-test-${UUID.randomUUID()}",
            Application.MODE_PRIVATE
        )
        secretDir = File(context.noBackupFilesDir, "byok-test-${UUID.randomUUID()}")
        store = CloudProviderConfigStore(prefs, ByokSecretStore(secretDir, FakeCipherBackend()))
    }

    @Test
    fun shouldDefaultToDisabledAndEmpty_whenNothingWasSaved() {
        assertFalse(store.isCloudEnabled())
        assertEquals(emptyList<CloudProviderProfile>(), store.loadProfiles())
        assertNull(store.activeProfileId())
        assertNull(store.loadCredential("openai-default"))
    }

    @Test
    fun shouldKeepApiKeysOutOfPreferences_whenProfileIsSaved() {
        val apiKey = "sk-plaintext-must-not-appear"
        val saved = store.saveProfile(openAiProfile(), ByokCredential("openai-default", apiKey = apiKey))

        assertTrue(saved.isSuccess)
        assertFalse("The cloud feature must stay off until the user opts in", store.isCloudEnabled())
        assertEquals(apiKey, store.loadCredential("openai-default")?.apiKey)
        assertEquals("openai-default", store.activeProfileId())
        val preferencesDump = prefs.all.values.joinToString("|") { it.toString() }
        assertFalse("Preferences must never contain the API key", preferencesDump.contains(apiKey))
        val blob = File(secretDir, ByokSecretStore.FILE_NAME)
        assertTrue(blob.isFile)
        assertFalse(
            "The ciphertext blob must not contain the plaintext key",
            String(blob.readBytes(), Charsets.ISO_8859_1).contains(apiKey)
        )
    }

    @Test
    fun shouldSwitchBetweenProfilesAndCredentials_whenMultipleAreSaved() {
        assertTrue(
            store.saveProfile(openAiProfile(), ByokCredential("openai-default", "sk-openai")).isSuccess
        )
        assertTrue(
            store.saveProfile(
                comfyProfile(),
                ByokCredential(
                    profileId = "comfy-home",
                    customAuthHeaderName = "Authorization",
                    customAuthHeaderValue = "Basic Y29tZnk6cGFzcw=="
                )
            ).isSuccess
        )

        store.setActiveProfile("comfy-home")

        assertEquals("comfy-home", store.activeProfileId())
        assertEquals(CloudProviderKind.COMFY_UI, store.activeProfile()?.kind)
        assertEquals("sk-openai", store.loadCredential("openai-default")?.apiKey)
        assertEquals(
            "Basic Y29tZnk6cGFzcw==",
            store.loadCredential("comfy-home")?.customAuthHeaderValue
        )
    }

    @Test
    fun shouldRoundTripComfyWorkflowAndNodeIds_whenProfileIsSaved() {
        val profile = comfyProfile()
        assertTrue(store.saveProfile(profile, ByokCredential("comfy-home", apiKey = "")).isSuccess)

        val loaded = store.findProfile("comfy-home")

        assertEquals(profile, loaded)
        assertEquals("10", loaded?.imageNodeId)
        assertEquals("11", loaded?.maskNodeId)
        assertEquals("6", loaded?.promptNodeId)
        assertTrue(loaded?.allowLanCleartextHttp == true)
    }

    @Test
    fun shouldRemoveCredentialAndFallBackToAnotherProfile_whenProfileIsDeleted() {
        store.saveProfile(openAiProfile(), ByokCredential("openai-default", "sk-openai"))
        store.saveProfile(comfyProfile(), ByokCredential("comfy-home", "comfy-token"))
        store.setActiveProfile("comfy-home")

        assertTrue(store.deleteProfile("comfy-home").isSuccess)

        assertNull(store.findProfile("comfy-home"))
        assertNull(store.loadCredential("comfy-home"))
        assertEquals("openai-default", store.activeProfileId())
        assertEquals("sk-openai", store.loadCredential("openai-default")?.apiKey)
    }

    @Test
    fun shouldRefuseToCreateProviderUntilCloudIsEnabled_whenProfileExists() {
        store.saveProfile(openAiProfile(), ByokCredential("openai-default", apiKey = "sk-test"))
        assertTrue(store.createProvider("openai-default").isFailure)

        store.setCloudEnabled(true)

        assertTrue(store.createProvider("openai-default").isSuccess)
        assertTrue(store.createProvider("missing-profile").isFailure)
    }

    @Test
    fun shouldKeepTheStoredSecret_whenProfileIsEditedWithoutCredential() {
        store.saveProfile(openAiProfile(), ByokCredential("openai-default", "sk-original"))
        val renamed = openAiProfile().copy(displayName = "Renamed gateway", model = "model-b")

        assertTrue(store.saveProfile(renamed).isSuccess)

        assertEquals("Renamed gateway", store.findProfile("openai-default")?.displayName)
        assertEquals("sk-original", store.loadCredential("openai-default")?.apiKey)
    }

    @Test
    fun shouldPersistGlobalSwitch_whenUserOptsIn() {
        store.setCloudEnabled(true)

        assertTrue(store.isCloudEnabled())
        assertTrue(prefs.getBoolean("cloud_enabled", false))
    }

    @Test
    fun shouldRejectProfileWithoutBaseUrl_whenSaving() {
        val result = store.saveProfile(openAiProfile().copy(baseUrl = ""))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals(emptyList<CloudProviderProfile>(), store.loadProfiles())
    }

    private fun openAiProfile() = CloudProviderProfile(
        profileId = "openai-default",
        displayName = "OpenAI gateway",
        kind = CloudProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "https://gateway.example.com",
        model = "user-selected-image-model"
    )

    private fun comfyProfile() = CloudProviderProfile(
        profileId = "comfy-home",
        displayName = "Home ComfyUI",
        kind = CloudProviderKind.COMFY_UI,
        baseUrl = "http://192.168.1.10:8188",
        workflowJson = """{"10":{"class_type":"LoadImage","inputs":{"image":"x.jpg"}}}""",
        imageNodeId = "10",
        maskNodeId = "11",
        promptNodeId = "6",
        negativePromptNodeId = "7",
        seedNodeId = "3",
        allowLanCleartextHttp = true,
        useBackgroundPolling = false
    )
}
