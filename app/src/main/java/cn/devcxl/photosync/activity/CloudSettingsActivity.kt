package cn.devcxl.photosync.activity

import java.util.UUID

import android.net.Uri
import android.os.Bundle

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import cn.devcxl.photosync.R
import cn.devcxl.photosync.databinding.ActivityCloudSettingsBinding
import cn.devcxl.photosync.editor.cloud.ByokCredential
import cn.devcxl.photosync.editor.cloud.CloudConnectionResult
import cn.devcxl.photosync.editor.cloud.CloudEditProviderFactory
import cn.devcxl.photosync.editor.cloud.CloudProviderConfigStore
import cn.devcxl.photosync.editor.cloud.CloudProviderKind
import cn.devcxl.photosync.editor.cloud.CloudProviderProfile

/**
 * Settings page for the default-off BYOK cloud feature: profiles, provider fields and an explicit
 * connectivity test. No request is sent while the master switch is off; API keys only travel
 * through [CloudProviderConfigStore] into the encrypted Keystore store and are never echoed.
 */
class CloudSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloudSettingsBinding
    private lateinit var store: CloudProviderConfigStore
    private lateinit var form: CloudSettingsFormBinder
    private lateinit var profileListRenderer: CloudProfileListRenderer
    private var profiles: List<CloudProviderProfile> = emptyList()
    private var selectedProfileId: String? = null
    private var existingCredential: ByokCredential? = null
    private var importedWorkflowName: String? = null
    private var busy = false
    private var suppressSwitchCallback = false

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::importWorkflow) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloudSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = CloudProviderConfigStore(this)
        form = CloudSettingsFormBinder(binding)
        profileListRenderer = CloudProfileListRenderer(binding)
        form.setupKindSpinner()
        wireActions()
        refreshFromStore()
    }

    private fun wireActions() {
        binding.cloudBackButton.setOnClickListener { finish() }
        binding.cloudNewProfileButton.setOnClickListener { startNewProfile() }
        binding.cloudEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            onMasterSwitchChanged(checked)
        }
        binding.cloudWorkflowImportButton.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain"))
        }
        binding.cloudTestButton.setOnClickListener { testConnection() }
        binding.cloudSaveButton.setOnClickListener { saveProfile() }
        binding.cloudDeleteButton.setOnClickListener { confirmDelete() }
    }

    private fun onMasterSwitchChanged(checked: Boolean) {
        if (suppressSwitchCallback) return
        store.setCloudEnabled(checked)
        binding.cloudDisabledHint.isVisible = !checked
        if (!checked) form.showStatus(getString(R.string.cloud_master_off_hint), false)
    }

    private fun refreshFromStore(selectId: String? = selectedProfileId, status: String? = null) {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                CloudSettingsSnapshot(
                    enabled = store.isCloudEnabled(),
                    profiles = store.loadProfiles(),
                    activeId = store.activeProfileId()
                )
            }
            applySnapshot(snapshot, selectId, status)
        }
    }

    private fun applySnapshot(snapshot: CloudSettingsSnapshot, selectId: String?, status: String?) {
        suppressSwitchCallback = true
        binding.cloudEnabledSwitch.isChecked = snapshot.enabled
        suppressSwitchCallback = false
        binding.cloudDisabledHint.isVisible = !snapshot.enabled
        renderProfileList(snapshot.profiles, snapshot.activeId)
        val selected = snapshot.profiles.firstOrNull { it.profileId == selectId }
            ?: snapshot.profiles.firstOrNull { it.profileId == snapshot.activeId }
            ?: snapshot.profiles.firstOrNull()
        if (selected == null) {
            startNewProfile(status)
        } else {
            selectProfile(selected, setActive = false, status = status)
        }
    }

    private fun renderProfileList(newProfiles: List<CloudProviderProfile>, activeId: String?) {
        profiles = newProfiles
        profileListRenderer.render(newProfiles, activeId, ::kindLabel) { profile ->
            selectProfile(profile, setActive = true)
        }
    }

    private fun selectProfile(
        profile: CloudProviderProfile,
        setActive: Boolean,
        status: String? = null
    ) {
        selectedProfileId = profile.profileId
        binding.cloudDeleteButton.isEnabled = !busy
        importedWorkflowName = null
        if (setActive) {
            store.setActiveProfile(profile.profileId)
            renderProfileList(profiles, profile.profileId)
        }
        lifecycleScope.launch {
            val credential = withContext(Dispatchers.IO) {
                store.loadCredential(profile.profileId)
            }
            if (selectedProfileId != profile.profileId) return@launch
            existingCredential = credential
            form.render(profile, credential, importedWorkflowName)
            form.showStatus(status ?: getString(R.string.cloud_status_idle), false)
        }
    }

    private fun startNewProfile(status: String? = null) {
        selectedProfileId = null
        existingCredential = null
        importedWorkflowName = null
        binding.cloudDeleteButton.isEnabled = false
        form.render(null, null, null)
        form.showStatus(status ?: getString(R.string.cloud_status_idle), false)
    }

    private fun importWorkflow(uri: Uri) {
        lifecycleScope.launch {
            when (val loaded = withContext(Dispatchers.IO) {
                CloudWorkflowLoader.load(this@CloudSettingsActivity, uri)
            }) {
                is CloudWorkflowLoad.Loaded -> {
                    importedWorkflowName = loaded.fileName
                    binding.cloudWorkflowInput.setText(loaded.json)
                    binding.cloudWorkflowFileNameText.text = getString(
                        R.string.cloud_workflow_loaded,
                        loaded.fileName
                    )
                }
                CloudWorkflowLoad.TooLarge -> form.showStatus(
                    getString(R.string.cloud_validation_workflow_too_large),
                    true
                )
                CloudWorkflowLoad.Unreadable -> form.showStatus(
                    getString(R.string.cloud_validation_workflow_unreadable),
                    true
                )
                CloudWorkflowLoad.Invalid -> form.showStatus(
                    getString(R.string.cloud_validation_workflow_invalid),
                    true
                )
            }
        }
    }

    private fun testConnection() {
        if (busy) return
        if (!store.isCloudEnabled()) {
            form.showStatus(getString(R.string.cloud_status_enable_first), true)
            return
        }
        val values = form.readValues()
        form.validate(values)?.let { error ->
            form.showStatus(CloudSettingsText.describeFormError(this, error), true)
            return
        }
        val profileId = selectedProfileId ?: DRAFT_PROFILE_ID
        val profile = form.toProfile(values, profileId)
        val credential = form.credentialFrom(values, profileId, existingCredential)
        val provider = CloudEditProviderFactory.create(profile, credential).getOrElse { failure ->
            form.showStatus(
                getString(R.string.cloud_status_building, failure.message.orEmpty()),
                true
            )
            return
        }
        setBusy(true)
        form.showStatus(getString(R.string.cloud_status_testing), false)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { provider.testConnection() }
            setBusy(false)
            form.showStatus(
                CloudSettingsText.describeConnection(this@CloudSettingsActivity, result),
                result is CloudConnectionResult.Failed
            )
        }
    }

    private fun saveProfile() {
        if (busy) return
        val values = form.readValues()
        form.validate(values)?.let { error ->
            form.showStatus(CloudSettingsText.describeFormError(this, error), true)
            return
        }
        val newProfile = selectedProfileId == null
        val profileId = selectedProfileId ?: UUID.randomUUID().toString()
        val profile = form.toProfile(values, profileId)
        val credential = form.credentialFrom(values, profileId, existingCredential)
        setBusy(true)
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { store.saveProfile(profile, credential) }
            setBusy(false)
            val failure = saved.exceptionOrNull()
            if (failure != null) {
                form.showStatus(
                    getString(R.string.cloud_status_save_failed, failure.message.orEmpty()),
                    true
                )
                return@launch
            }
            selectedProfileId = profile.profileId
            existingCredential = credential
            importedWorkflowName = null
            if (newProfile) store.setActiveProfile(profile.profileId)
            refreshFromStore(profile.profileId, getString(R.string.cloud_status_saved))
        }
    }

    private fun confirmDelete() {
        val profileId = selectedProfileId ?: return
        val name = profiles.firstOrNull { it.profileId == profileId }?.displayName
            ?: form.readValues().displayName
        AlertDialog.Builder(this)
            .setTitle(R.string.cloud_delete_title)
            .setMessage(getString(R.string.cloud_delete_message, name))
            .setPositiveButton(R.string.cloud_delete_confirm) { _, _ -> deleteProfile(profileId) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun deleteProfile(profileId: String) {
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) { store.deleteProfile(profileId) }
            setBusy(false)
            val failure = deleted.exceptionOrNull()
            if (failure != null) {
                form.showStatus(
                    getString(R.string.cloud_status_save_failed, failure.message.orEmpty()),
                    true
                )
                return@launch
            }
            selectedProfileId = null
            existingCredential = null
            importedWorkflowName = null
            refreshFromStore(null, getString(R.string.cloud_status_deleted))
        }
    }

    private fun setBusy(busy: Boolean) {
        this.busy = busy
        form.setBusy(busy)
        binding.cloudDeleteButton.isEnabled = !busy && selectedProfileId != null
    }

    private fun kindLabel(kind: CloudProviderKind): String = getString(
        when (kind) {
            CloudProviderKind.OPENAI_COMPATIBLE -> R.string.cloud_kind_openai
            CloudProviderKind.GEMINI -> R.string.cloud_kind_gemini
            CloudProviderKind.COMFY_UI -> R.string.cloud_kind_comfy
        }
    )

    private data class CloudSettingsSnapshot(
        val enabled: Boolean,
        val profiles: List<CloudProviderProfile>,
        val activeId: String?
    )

    private companion object {
        /** Credential key used for an unsaved draft; nothing is persisted under it. */
        const val DRAFT_PROFILE_ID = "cloud-settings-draft"
    }
}
