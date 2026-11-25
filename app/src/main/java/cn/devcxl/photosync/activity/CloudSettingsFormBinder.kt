package cn.devcxl.photosync.activity

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter

import androidx.core.content.ContextCompat

import org.json.JSONException
import org.json.JSONObject

import cn.devcxl.photosync.R
import cn.devcxl.photosync.databinding.ActivityCloudSettingsBinding
import cn.devcxl.photosync.editor.cloud.ByokCredential
import cn.devcxl.photosync.editor.cloud.CloudProviderKind
import cn.devcxl.photosync.editor.cloud.CloudProviderProfile

/** Raw values typed into the cloud profile form. */
internal data class CloudFormValues(
    val displayName: String,
    val kind: CloudProviderKind,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val authHeaderName: String,
    val authHeaderValue: String,
    val workflowJson: String,
    val imageNodeId: String,
    val maskNodeId: String,
    val promptNodeId: String,
    val negativeNodeId: String,
    val seedNodeId: String,
    val allowLanCleartextHttp: Boolean,
    val useBackgroundPolling: Boolean
)

/** Why the form cannot be submitted yet. The activity maps these to localized text. */
internal enum class CloudFormError {
    NAME_REQUIRED,
    BASE_URL_REQUIRED,
    MODEL_REQUIRED,
    WORKFLOW_REQUIRED,
    WORKFLOW_INVALID,
    WORKFLOW_NODES_REQUIRED,
    AUTH_HEADER_INCOMPLETE
}

/**
 * Binds one cloud provider profile to the settings form.
 *
 * Secrets are never rendered: the key and custom-header value fields start empty and only show a
 * masked hint that a secret is stored. Leaving them blank keeps the stored value.
 */
internal class CloudSettingsFormBinder(
    private val binding: ActivityCloudSettingsBinding
) {

    private val context: Context = binding.root.context

    /** Header name currently rendered from the stored credential; detects an explicit clear. */
    private var renderedHeaderName: String? = null

    /** Fills the provider spinner and keeps field visibility in sync with the selection. */
    fun setupKindSpinner() {
        val adapter = ArrayAdapter.createFromResource(
            context,
            R.array.cloud_provider_kinds,
            android.R.layout.simple_spinner_item
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.cloudKindSpinner.adapter = adapter
        binding.cloudKindSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                applyKindVisibility(selectedKind)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** Current provider kind selected in the spinner. */
    val selectedKind: CloudProviderKind
        get() = CloudProviderKind.entries[
            binding.cloudKindSpinner.selectedItemPosition.coerceIn(
                0,
                CloudProviderKind.entries.size - 1
            )
        ]

    /** Renders [profile] and the masked hints of [credential]. */
    fun render(
        profile: CloudProviderProfile?,
        credential: ByokCredential?,
        workflowFileName: String?
    ) {
        binding.cloudProfileNameInput.setText(profile?.displayName.orEmpty())
        binding.cloudKindSpinner.setSelection(profile?.kind?.ordinal ?: 0)
        binding.cloudBaseUrlInput.setText(profile?.baseUrl.orEmpty())
        binding.cloudModelInput.setText(profile?.model.orEmpty())
        binding.cloudApiKeyInput.setText("")
        binding.cloudApiKeyInput.hint = apiKeyHint(credential)
        renderedHeaderName = credential?.customAuthHeaderName
        binding.cloudAuthHeaderNameInput.setText(renderedHeaderName.orEmpty())
        binding.cloudAuthHeaderValueInput.setText("")
        binding.cloudAuthHeaderValueInput.hint = headerValueHint(credential)
        binding.cloudImageNodeInput.setText(profile?.imageNodeId.orEmpty())
        binding.cloudMaskNodeInput.setText(profile?.maskNodeId.orEmpty())
        binding.cloudPromptNodeInput.setText(profile?.promptNodeId.orEmpty())
        binding.cloudNegativeNodeInput.setText(profile?.negativePromptNodeId.orEmpty())
        binding.cloudSeedNodeInput.setText(profile?.seedNodeId.orEmpty())
        binding.cloudLanCleartextCheck.isChecked = profile?.allowLanCleartextHttp ?: false
        binding.cloudBackgroundCheck.isChecked = profile?.useBackgroundPolling ?: true
        val workflow = profile?.workflowJson.orEmpty()
        binding.cloudWorkflowInput.setText(workflow)
        binding.cloudWorkflowFileNameText.text = when {
            workflowFileName != null -> context.getString(
                R.string.cloud_workflow_loaded,
                workflowFileName
            )
            workflow.isNotBlank() -> context.getString(R.string.cloud_workflow_existing)
            else -> context.getString(R.string.cloud_workflow_none)
        }
        applyKindVisibility(selectedKind)
    }

    /** Reads every field. */
    fun readValues(): CloudFormValues = CloudFormValues(
        displayName = binding.cloudProfileNameInput.text.toString().trim(),
        kind = selectedKind,
        baseUrl = binding.cloudBaseUrlInput.text.toString().trim(),
        model = binding.cloudModelInput.text.toString().trim(),
        apiKey = binding.cloudApiKeyInput.text.toString().trim(),
        authHeaderName = binding.cloudAuthHeaderNameInput.text.toString().trim(),
        authHeaderValue = binding.cloudAuthHeaderValueInput.text.toString().trim(),
        workflowJson = binding.cloudWorkflowInput.text.toString().trim(),
        imageNodeId = binding.cloudImageNodeInput.text.toString().trim(),
        maskNodeId = binding.cloudMaskNodeInput.text.toString().trim(),
        promptNodeId = binding.cloudPromptNodeInput.text.toString().trim(),
        negativeNodeId = binding.cloudNegativeNodeInput.text.toString().trim(),
        seedNodeId = binding.cloudSeedNodeInput.text.toString().trim(),
        allowLanCleartextHttp = binding.cloudLanCleartextCheck.isChecked,
        useBackgroundPolling = binding.cloudBackgroundCheck.isChecked
    )

    /** Returns the first blocking problem, or `null` when the form can be submitted. */
    fun validate(values: CloudFormValues): CloudFormError? = when {
        values.displayName.isEmpty() -> CloudFormError.NAME_REQUIRED
        values.baseUrl.isEmpty() -> CloudFormError.BASE_URL_REQUIRED
        values.kind != CloudProviderKind.COMFY_UI && values.model.isEmpty() ->
            CloudFormError.MODEL_REQUIRED
        values.kind == CloudProviderKind.COMFY_UI && values.workflowJson.isEmpty() ->
            CloudFormError.WORKFLOW_REQUIRED
        values.kind == CloudProviderKind.COMFY_UI && !isJsonObject(values.workflowJson) ->
            CloudFormError.WORKFLOW_INVALID
        values.kind == CloudProviderKind.COMFY_UI &&
            (values.imageNodeId.isEmpty() || values.promptNodeId.isEmpty()) ->
            CloudFormError.WORKFLOW_NODES_REQUIRED
        values.authHeaderValue.isNotEmpty() && values.authHeaderName.isEmpty() ->
            CloudFormError.AUTH_HEADER_INCOMPLETE
        values.authHeaderName.isNotEmpty() && values.authHeaderValue.isEmpty() &&
            values.authHeaderName != renderedHeaderName.orEmpty() ->
            CloudFormError.AUTH_HEADER_INCOMPLETE
        else -> null
    }

    /** Combines the form with [profileId] into a persistable profile. */
    fun toProfile(values: CloudFormValues, profileId: String): CloudProviderProfile =
        CloudProviderProfile(
            profileId = profileId,
            displayName = values.displayName,
            kind = values.kind,
            baseUrl = values.baseUrl,
            model = values.model,
            workflowJson = values.workflowJson,
            imageNodeId = values.imageNodeId,
            maskNodeId = values.maskNodeId,
            promptNodeId = values.promptNodeId,
            negativePromptNodeId = values.negativeNodeId,
            seedNodeId = values.seedNodeId,
            allowLanCleartextHttp = values.allowLanCleartextHttp,
            useBackgroundPolling = values.useBackgroundPolling
        )

    /**
     * Builds the credential to persist or test with.
     *
     * Blank secret fields keep [existing]: an untouched form must never erase a stored key. A
     * cleared auth-header name removes the custom header.
     */
    fun credentialFrom(
        values: CloudFormValues,
        profileId: String,
        existing: ByokCredential?
    ): ByokCredential {
        val untouched = values.apiKey.isEmpty() &&
            values.authHeaderValue.isEmpty() &&
            values.authHeaderName == renderedHeaderName.orEmpty()
        if (untouched && existing != null) return existing
        val headerName = values.authHeaderName.takeIf { it.isNotEmpty() }
        val headerValue = when {
            values.authHeaderValue.isNotEmpty() -> values.authHeaderValue
            values.authHeaderName.isNotEmpty() &&
                existing?.customAuthHeaderName == values.authHeaderName ->
                existing.customAuthHeaderValue
            else -> null
        }
        return ByokCredential(
            profileId = profileId,
            apiKey = values.apiKey.ifEmpty { existing?.apiKey.orEmpty() },
            customAuthHeaderName = headerName,
            customAuthHeaderValue = headerValue
        )
    }

    /** Shows a status/error line under the form. */
    fun showStatus(message: String, isError: Boolean) {
        binding.cloudStatusText.text = message
        binding.cloudStatusText.setTextColor(
            ContextCompat.getColor(
                context,
                if (isError) R.color.status_error else R.color.darkroom_text_secondary
            )
        )
    }

    /** Disables the network and persistence actions while a request is in flight. */
    fun setBusy(busy: Boolean) {
        binding.cloudTestButton.isEnabled = !busy
        binding.cloudSaveButton.isEnabled = !busy
        binding.cloudNewProfileButton.isEnabled = !busy
    }

    private fun isJsonObject(json: String): Boolean = try {
        JSONObject(json)
        true
    } catch (e: JSONException) {
        false
    }

    private fun applyKindVisibility(kind: CloudProviderKind) {
        binding.cloudModelGroup.visibility =
            if (kind == CloudProviderKind.COMFY_UI) View.GONE else View.VISIBLE
        binding.cloudComfyGroup.visibility =
            if (kind == CloudProviderKind.COMFY_UI) View.VISIBLE else View.GONE
        binding.cloudBackgroundGroup.visibility =
            if (kind == CloudProviderKind.GEMINI) View.VISIBLE else View.GONE
    }

    private fun apiKeyHint(credential: ByokCredential?): String {
        val storedKey = credential?.apiKey
        return if (storedKey.isNullOrEmpty()) {
            context.getString(R.string.cloud_api_key_hint_empty)
        } else {
            context.getString(
                R.string.cloud_api_key_hint_saved,
                CloudSettingsText.maskSecret(storedKey)
            )
        }
    }

    private fun headerValueHint(credential: ByokCredential?): String {
        val storedValue = credential?.customAuthHeaderValue
        return if (storedValue.isNullOrEmpty()) {
            context.getString(R.string.cloud_auth_header_hint)
        } else {
            context.getString(
                R.string.cloud_auth_header_hint_saved,
                CloudSettingsText.maskSecret(storedValue)
            )
        }
    }
}
