package cn.devcxl.photosync.editor.cloud

import java.io.IOException

import android.content.Context
import android.content.SharedPreferences

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

import timber.log.Timber

/**
 * Non-secret configuration of one BYOK provider profile.
 *
 * API keys and reverse-proxy auth headers never appear here: they are stored separately through
 * [ByokSecretStore]. Everything in this class is safe to keep in plain [SharedPreferences].
 *
 * @property profileId Stable id that also keys the encrypted credential.
 * @property displayName Name shown in the settings page.
 * @property kind Provider family that decides which fields are used.
 * @property baseUrl Gateway root; the adapter appends its own versioned path.
 * @property model User-selected model. Never compiled into the adapters.
 * @property workflowJson ComfyUI API-format workflow; blank for the other providers.
 * @property imageNodeId ComfyUI `LoadImage` node for the sanitized photo.
 * @property maskNodeId ComfyUI `LoadImage` node for the mask; blank disables masks.
 * @property promptNodeId ComfyUI positive prompt node.
 * @property negativePromptNodeId Optional ComfyUI negative prompt node.
 * @property seedNodeId Optional ComfyUI sampler node that receives a seed.
 * @property allowLanCleartextHttp Explicit opt-in for `http://` on a literal private IPv4 LAN host.
 * @property useBackgroundPolling Gemini: submit with `"background": true` and poll.
 */
data class CloudProviderProfile(
    val profileId: String,
    val displayName: String,
    val kind: CloudProviderKind,
    val baseUrl: String,
    val model: String = "",
    val workflowJson: String = "",
    val imageNodeId: String = "",
    val maskNodeId: String = "",
    val promptNodeId: String = "",
    val negativePromptNodeId: String = "",
    val seedNodeId: String = "",
    val allowLanCleartextHttp: Boolean = false,
    val useBackgroundPolling: Boolean = true
) {

    /** Converts the ComfyUI fields into the injector binding. */
    fun comfyBinding(): ComfyUiWorkflowBinding = ComfyUiWorkflowBinding(
        workflowJson = workflowJson,
        imageNodeId = imageNodeId,
        maskNodeId = maskNodeId,
        promptNodeId = promptNodeId,
        negativePromptNodeId = negativePromptNodeId,
        seedNodeId = seedNodeId
    )
}

/**
 * Persists the BYOK provider catalogue and the global cloud switch.
 *
 * The switch is **off by default** and nothing in this class contacts the network: providers are
 * only created when the user explicitly tests or runs an edit. Secrets (API key, custom auth header)
 * are written exclusively through [ByokSecretStore], which encrypts them with AndroidKeyStore.
 *
 * Every method performs SharedPreferences or Keystore I/O and **must be called off the main
 * thread**. Save/delete operations are synchronized within the process.
 */
class CloudProviderConfigStore internal constructor(
    private val preferences: SharedPreferences,
    private val secretStore: ByokSecretStore
) {

    /** Creates a store backed by the app's private preferences and Keystore. */
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        ByokSecretStore(context)
    )

    /** True only after the user explicitly enabled the cloud feature. Defaults to `false`. */
    fun isCloudEnabled(): Boolean = preferences.getBoolean(KEY_CLOUD_ENABLED, false)

    /** Persists the global cloud switch. */
    fun setCloudEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CLOUD_ENABLED, enabled).apply()
    }

    /** Returns every readable profile in insertion order; malformed entries are skipped. */
    fun loadProfiles(): List<CloudProviderProfile> {
        val raw = preferences.getString(KEY_PROFILES, null) ?: return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (e: JSONException) {
            Timber.w("Ignoring an unreadable BYOK profile catalogue")
            return emptyList()
        }
        val profiles = ArrayList<CloudProviderProfile>(array.length())
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            profileFromJson(json)?.let(profiles::add)
        }
        return profiles
    }

    /** Finds one profile by id. */
    fun findProfile(profileId: String): CloudProviderProfile? =
        loadProfiles().firstOrNull { it.profileId == profileId }

    /** Returns the profile the editor should use, when it still exists. */
    fun activeProfile(): CloudProviderProfile? = activeProfileId()?.let(::findProfile)

    /** Returns the selected profile id, or `null` before the user picks one. */
    fun activeProfileId(): String? =
        preferences.getString(KEY_ACTIVE_PROFILE, null)?.takeIf { it.isNotBlank() }

    /** Selects [profileId], or clears the selection with `null`. */
    fun setActiveProfile(profileId: String?) {
        preferences.edit().putString(KEY_ACTIVE_PROFILE, profileId?.takeIf { it.isNotBlank() })
            .apply()
    }

    /**
     * Inserts or replaces [profile] and, when [credential] is non-null, replaces its secret.
     *
     * Passing `null` keeps the previously stored secret, so editing a display name never forces
     * the user to retype the API key.
     *
     * @return success once the profile catalogue and the encrypted credential are both persisted.
     */
    @Synchronized
    fun saveProfile(
        profile: CloudProviderProfile,
        credential: ByokCredential? = null
    ): Result<Unit> {
        validate(profile)?.let { return Result.failure(it) }
        if (credential != null) {
            if (credential.profileId != profile.profileId) {
                return Result.failure(IllegalArgumentException("Credential belongs to another profile"))
            }
            val existing = loadCredentialMap()
                ?: return Result.failure(IllegalStateException("BYOK secrets are unavailable"))
            val updated = existing.toMutableMap().apply { put(profile.profileId, credential) }
            val saved = secretStore.save(updated)
            if (saved.isFailure) return saved
        }
        val profiles = loadProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.profileId == profile.profileId }
        if (index >= 0) profiles[index] = profile else profiles.add(profile)
        if (!persist(profiles)) {
            return Result.failure(IOException("Could not persist the BYOK profile catalogue"))
        }
        if (activeProfileId() == null) setActiveProfile(profile.profileId)
        return Result.success(Unit)
    }

    /** Removes the profile and its encrypted credential. */
    @Synchronized
    fun deleteProfile(profileId: String): Result<Unit> {
        val profiles = loadProfiles().filterNot { it.profileId == profileId }
        if (!persist(profiles)) {
            return Result.failure(IOException("Could not persist the BYOK profile catalogue"))
        }
        if (activeProfileId() == profileId) setActiveProfile(profiles.firstOrNull()?.profileId)
        val existing = loadCredentialMap()
            ?: return Result.failure(IllegalStateException("BYOK secrets are unavailable"))
        if (profileId in existing) {
            val saved = secretStore.save(existing - profileId)
            if (saved.isFailure) return saved
        }
        return Result.success(Unit)
    }

    /**
     * Creates the provider for [profileId] (or the active profile), but only while the global cloud
     * switch is on. This is the single gate callers should use before an edit request.
     *
     * @return failure with [IllegalStateException] while cloud is disabled, no profile is selected,
     *   or the profile no longer exists.
     */
    fun createProvider(profileId: String? = activeProfileId()): Result<CloudEditProvider> {
        if (!isCloudEnabled()) {
            return Result.failure(IllegalStateException("Cloud generative edits are disabled"))
        }
        val id = profileId ?: return Result.failure(
            IllegalStateException("No cloud provider profile is selected")
        )
        val profile = findProfile(id) ?: return Result.failure(
            IllegalStateException("The selected cloud provider profile no longer exists")
        )
        val credential = loadCredential(id) ?: ByokCredential(id)
        return CloudEditProviderFactory.create(profile, credential)
    }

    /**
     * Returns the stored credential for [profileId].
     *
     * `null` also covers an unreadable store; the settings page then asks the user to re-enter the
     * secret. Credential values must never be logged.
     */
    fun loadCredential(profileId: String): ByokCredential? = loadCredentialMap()?.get(profileId)

    private fun loadCredentialMap(): Map<String, ByokCredential>? =
        when (val result = secretStore.load()) {
            is ByokReadResult.Loaded -> result.credentials
            ByokReadResult.Empty -> emptyMap()
            // Damaged artifacts were already deleted; the next save starts a clean credential map.
            is ByokReadResult.RecoveryRequired -> emptyMap()
            ByokReadResult.Unavailable -> null
        }

    private fun persist(profiles: List<CloudProviderProfile>): Boolean {
        val array = JSONArray()
        for (profile in profiles) array.put(profileToJson(profile))
        return preferences.edit().putString(KEY_PROFILES, array.toString()).commit()
    }

    private fun validate(profile: CloudProviderProfile): IllegalArgumentException? = when {
        profile.profileId.isBlank() -> IllegalArgumentException("The profile id is empty")
        profile.displayName.isBlank() -> IllegalArgumentException("The profile name is empty")
        profile.baseUrl.isBlank() -> IllegalArgumentException("The base URL is empty")
        else -> null
    }

    private fun profileToJson(profile: CloudProviderProfile): JSONObject = JSONObject()
        .put(FIELD_PROFILE_ID, profile.profileId)
        .put(FIELD_DISPLAY_NAME, profile.displayName)
        .put(FIELD_KIND, profile.kind.name)
        .put(FIELD_BASE_URL, profile.baseUrl)
        .put(FIELD_MODEL, profile.model)
        .put(FIELD_WORKFLOW, profile.workflowJson)
        .put(FIELD_IMAGE_NODE, profile.imageNodeId)
        .put(FIELD_MASK_NODE, profile.maskNodeId)
        .put(FIELD_PROMPT_NODE, profile.promptNodeId)
        .put(FIELD_NEGATIVE_NODE, profile.negativePromptNodeId)
        .put(FIELD_SEED_NODE, profile.seedNodeId)
        .put(FIELD_ALLOW_LAN, profile.allowLanCleartextHttp)
        .put(FIELD_BACKGROUND, profile.useBackgroundPolling)

    private fun profileFromJson(json: JSONObject): CloudProviderProfile? {
        val kind = try {
            CloudProviderKind.valueOf(json.optString(FIELD_KIND))
        } catch (e: IllegalArgumentException) {
            Timber.w("Skipping a BYOK profile with an unknown provider kind")
            return null
        }
        val profileId = json.optString(FIELD_PROFILE_ID)
        val displayName = json.optString(FIELD_DISPLAY_NAME)
        val baseUrl = json.optString(FIELD_BASE_URL)
        if (profileId.isBlank() || displayName.isBlank() || baseUrl.isBlank()) {
            Timber.w("Skipping an incomplete BYOK profile")
            return null
        }
        return CloudProviderProfile(
            profileId = profileId,
            displayName = displayName,
            kind = kind,
            baseUrl = baseUrl,
            model = json.optString(FIELD_MODEL),
            workflowJson = json.optString(FIELD_WORKFLOW),
            imageNodeId = json.optString(FIELD_IMAGE_NODE),
            maskNodeId = json.optString(FIELD_MASK_NODE),
            promptNodeId = json.optString(FIELD_PROMPT_NODE),
            negativePromptNodeId = json.optString(FIELD_NEGATIVE_NODE),
            seedNodeId = json.optString(FIELD_SEED_NODE),
            allowLanCleartextHttp = json.optBoolean(FIELD_ALLOW_LAN, false),
            useBackgroundPolling = json.optBoolean(FIELD_BACKGROUND, true)
        )
    }

    companion object {
        /** SharedPreferences file holding non-secret provider settings. */
        const val PREFERENCES_NAME = "photosync_cloud_providers"

        private const val KEY_CLOUD_ENABLED = "cloud_enabled"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"

        private const val FIELD_PROFILE_ID = "profileId"
        private const val FIELD_DISPLAY_NAME = "displayName"
        private const val FIELD_KIND = "kind"
        private const val FIELD_BASE_URL = "baseUrl"
        private const val FIELD_MODEL = "model"
        private const val FIELD_WORKFLOW = "workflow"
        private const val FIELD_IMAGE_NODE = "imageNodeId"
        private const val FIELD_MASK_NODE = "maskNodeId"
        private const val FIELD_PROMPT_NODE = "promptNodeId"
        private const val FIELD_NEGATIVE_NODE = "negativePromptNodeId"
        private const val FIELD_SEED_NODE = "seedNodeId"
        private const val FIELD_ALLOW_LAN = "allowLanCleartextHttp"
        private const val FIELD_BACKGROUND = "useBackgroundPolling"
    }
}
