package cn.devcxl.photosync.activity

import android.content.Intent
import android.os.Bundle

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

import cn.devcxl.photosync.R
import cn.devcxl.photosync.data.settings.AppSettings
import cn.devcxl.photosync.databinding.ActivitySettingsBinding
import cn.devcxl.photosync.editor.cloud.CloudProviderConfigStore
import cn.devcxl.photosync.editor.settings.AiIdleRelease
import cn.devcxl.photosync.editor.settings.AiTierOverride
import cn.devcxl.photosync.editor.settings.EditorSettings
import cn.devcxl.photosync.editor.settings.InpaintQualityMode
import cn.devcxl.photosync.editor.settings.PreviewMaxEdge

/**
 * Unified settings hub, organised into the six user-intent groups from the S1 spec.
 *
 * This activity keeps the group cards and the enum/range pickers; storage accounting, library
 * management and the read-only diagnostics live in [SettingsStorageSection] and
 * [SettingsDiagnosticsSection]. Every value only selects runtime defaults or policies: nothing here
 * changes `EditGraph` semantics or the pixels a bake produces.
 *
 * Switches are re-read in [onResume] so a change made on the settings page is reflected both here and
 * in the gallery/editor without restarting the process.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var editorSettings: EditorSettings
    private lateinit var appSettings: AppSettings
    private lateinit var cloudStore: CloudProviderConfigStore
    private lateinit var storageSection: SettingsStorageSection
    private lateinit var diagnosticsSection: SettingsDiagnosticsSection

    private val lutImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { storageSection.importLut(it) } }

    private val skyImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { storageSection.importSky(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        editorSettings = EditorSettings(this)
        appSettings = AppSettings(this)
        cloudStore = CloudProviderConfigStore(this)
        storageSection = SettingsStorageSection(
            activity = this,
            binding = binding,
            onLutImportRequested = { lutImportLauncher.launch(arrayOf("*/*")) },
            onSkyImportRequested = { skyImportLauncher.launch(arrayOf("image/*")) }
        )
        diagnosticsSection = SettingsDiagnosticsSection(this, binding, editorSettings, appSettings)

        binding.settingsBackButton.setOnClickListener { finish() }
        bindTetherGroup()
        bindEditorGroup()
        bindAiGroup()
        bindCloudGroup()
        storageSection.bind()
        diagnosticsSection.refresh()
    }

    override fun onResume() {
        super.onResume()
        refreshCloudStatus()
        bindTetherGroup()
        bindAiGroup()
        storageSection.refresh()
        diagnosticsSection.refresh()
    }

    private fun bindTetherGroup() {
        bindSwitch(binding.settingsAutoFollowSwitch, appSettings.autoFollowEnabled) { checked ->
            appSettings.autoFollowEnabled = checked
            refreshEffectiveSummary()
        }
        bindSwitch(binding.settingsDeleteOnCameraSwitch, appSettings.deleteOnCamera) { checked ->
            appSettings.deleteOnCamera = checked
            refreshEffectiveSummary()
        }
        bindSwitch(binding.settingsKeepScreenOnSwitch, appSettings.keepScreenOn) { checked ->
            appSettings.keepScreenOn = checked
            refreshEffectiveSummary()
        }
        bindSwitch(binding.settingsLivePresetEnabledSwitch, appSettings.livePresetEnabled) {
            appSettings.livePresetEnabled = it
            refreshEffectiveSummary()
        }
    }

    private fun bindEditorGroup() {
        refreshEditorValues()
        binding.settingsPreviewResolutionRow.setOnClickListener { choosePreviewMaxEdge() }
        binding.settingsExportQualityRow.setOnClickListener { chooseExportQuality() }
        binding.settingsUndoDepthRow.setOnClickListener { chooseUndoDepth() }
    }

    private fun bindAiGroup() {
        refreshAiValues()
        binding.settingsAiModelsRow.setOnClickListener {
            startActivity(Intent(this, AiModelSettingsActivity::class.java))
        }
        binding.settingsTierOverrideRow.setOnClickListener { chooseTierOverride() }
        binding.settingsIntraThreadsRow.setOnClickListener { chooseIntraThreads() }
        binding.settingsIdleReleaseRow.setOnClickListener { chooseIdleRelease() }
        binding.settingsInpaintModeRow.setOnClickListener { chooseInpaintMode() }
        binding.settingsInpaintBrushRow.setOnClickListener { chooseInpaintBrush() }
        bindSwitch(binding.settingsLivePresetApplyAiSwitch, appSettings.livePresetApplyAi) {
            appSettings.livePresetApplyAi = it
            refreshEffectiveSummary()
        }
    }

    private fun bindCloudGroup() {
        refreshCloudStatus()
        binding.settingsCloudEntryRow.setOnClickListener {
            startActivity(Intent(this, CloudSettingsActivity::class.java))
        }
    }

    private fun refreshCloudStatus() {
        binding.settingsCloudStatusValue.setText(
            if (cloudStore.isCloudEnabled()) {
                R.string.settings_cloud_enabled
            } else {
                R.string.settings_cloud_disabled
            }
        )
    }

    private fun refreshEditorValues() {
        binding.settingsPreviewResolutionValue.text =
            getString(R.string.settings_value_px, editorSettings.previewMaxEdge.maxEdgePx)
        binding.settingsExportQualityValue.text =
            getString(R.string.settings_value_number, editorSettings.exportJpegQuality)
        binding.settingsUndoDepthValue.text =
            getString(R.string.settings_value_number, editorSettings.undoDepth)
    }

    private fun refreshAiValues() {
        binding.settingsTierOverrideValue.text =
            SettingsOptionLabels.tierOverride(this, editorSettings.tierOverride)
        binding.settingsIntraThreadsValue.text =
            getString(R.string.settings_value_number, editorSettings.intraOpThreads)
        binding.settingsIdleReleaseValue.text =
            SettingsOptionLabels.idleRelease(this, editorSettings.idleRelease)
        binding.settingsInpaintModeValue.text =
            SettingsOptionLabels.inpaintMode(this, editorSettings.inpaintDefaultMode)
        binding.settingsInpaintBrushValue.text =
            getString(R.string.settings_value_px, editorSettings.inpaintBrushPx)
    }

    private fun choosePreviewMaxEdge() {
        val options = PreviewMaxEdge.entries
        SettingsControlDialogs.showChoice(
            this,
            titleRes = R.string.settings_preview_resolution,
            labels = options.map { getString(R.string.settings_value_px, it.maxEdgePx) },
            selectedIndex = options.indexOf(editorSettings.previewMaxEdge)
        ) { index ->
            editorSettings.previewMaxEdge = options[index]
            refreshEditorValues()
            refreshEffectiveSummary()
        }
    }

    private fun chooseExportQuality() {
        SettingsControlDialogs.showRange(
            this,
            titleRes = R.string.settings_export_quality,
            range = EditorSettings.MIN_EXPORT_JPEG_QUALITY..EditorSettings.MAX_EXPORT_JPEG_QUALITY,
            current = editorSettings.exportJpegQuality,
            formatter = { getString(R.string.settings_value_number, it) }
        ) { value ->
            editorSettings.exportJpegQuality = value
            refreshEditorValues()
            refreshEffectiveSummary()
        }
    }

    private fun chooseUndoDepth() {
        SettingsControlDialogs.showRange(
            this,
            titleRes = R.string.settings_undo_depth,
            range = EditorSettings.MIN_UNDO_DEPTH..EditorSettings.MAX_UNDO_DEPTH,
            current = editorSettings.undoDepth,
            formatter = { getString(R.string.settings_value_number, it) }
        ) { value ->
            editorSettings.undoDepth = value
            refreshEditorValues()
            refreshEffectiveSummary()
        }
    }

    private fun chooseTierOverride() {
        val options = AiTierOverride.entries
        SettingsControlDialogs.showChoice(
            this,
            titleRes = R.string.settings_tier_override,
            labels = options.map { SettingsOptionLabels.tierOverride(this, it) },
            selectedIndex = options.indexOf(editorSettings.tierOverride)
        ) { index ->
            editorSettings.tierOverride = options[index]
            refreshAiValues()
            refreshEffectiveSummary()
        }
    }

    private fun chooseIntraThreads() {
        SettingsControlDialogs.showRange(
            this,
            titleRes = R.string.settings_intra_threads,
            range = EditorSettings.MIN_INTRA_OP_THREADS..EditorSettings.MAX_INTRA_OP_THREADS,
            current = editorSettings.intraOpThreads,
            formatter = { getString(R.string.settings_value_number, it) }
        ) { value ->
            editorSettings.intraOpThreads = value
            refreshAiValues()
            refreshEffectiveSummary()
        }
    }

    private fun chooseIdleRelease() {
        val options = AiIdleRelease.entries
        SettingsControlDialogs.showChoice(
            this,
            titleRes = R.string.settings_idle_release,
            labels = options.map { SettingsOptionLabels.idleRelease(this, it) },
            selectedIndex = options.indexOf(editorSettings.idleRelease)
        ) { index ->
            editorSettings.idleRelease = options[index]
            refreshAiValues()
            refreshEffectiveSummary()
        }
    }

    private fun chooseInpaintMode() {
        val options = InpaintQualityMode.entries
        SettingsControlDialogs.showChoice(
            this,
            titleRes = R.string.settings_inpaint_mode,
            labels = options.map { SettingsOptionLabels.inpaintMode(this, it) },
            selectedIndex = options.indexOf(editorSettings.inpaintDefaultMode)
        ) { index ->
            editorSettings.inpaintDefaultMode = options[index]
            refreshAiValues()
            refreshEffectiveSummary()
        }
    }

    private fun chooseInpaintBrush() {
        SettingsControlDialogs.showRange(
            this,
            titleRes = R.string.settings_inpaint_brush,
            range = EditorSettings.MIN_INPAINT_BRUSH_PX..EditorSettings.MAX_INPAINT_BRUSH_PX,
            current = editorSettings.inpaintBrushPx,
            formatter = { getString(R.string.settings_value_px, it) }
        ) { value ->
            editorSettings.inpaintBrushPx = value
            refreshAiValues()
            refreshEffectiveSummary()
        }
    }

    private fun refreshEffectiveSummary() {
        diagnosticsSection.refresh()
    }

    private fun bindSwitch(view: SwitchCompat, checked: Boolean, onChange: (Boolean) -> Unit) {
        // The listener is detached while restoring the stored value so a programmatic `isChecked`
        // can never write a value back during onResume.
        view.setOnCheckedChangeListener(null)
        view.isChecked = checked
        view.setOnCheckedChangeListener { _, value -> onChange(value) }
    }
}
