package cn.devcxl.photosync.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

import timber.log.Timber

import cn.devcxl.photosync.R
import cn.devcxl.photosync.data.settings.AppSettings
import cn.devcxl.photosync.databinding.ActivitySettingsBinding
import cn.devcxl.photosync.editor.cloud.CloudProviderConfigStore
import cn.devcxl.photosync.editor.settings.AiIdleRelease
import cn.devcxl.photosync.editor.settings.AiTierOverride
import cn.devcxl.photosync.editor.settings.EditorSettings
import cn.devcxl.photosync.editor.settings.InpaintQualityMode
import cn.devcxl.photosync.editor.settings.PreviewMaxEdge
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier

/**
 * Unified settings hub, organised into the six user-intent groups from the S1 spec.
 *
 * This activity owns the base skeleton only: it reads and writes the typed settings through
 * [EditorSettings] and [AppSettings], hosts the two former top-level pages as read-only entry rows
 * ([AiModelSettingsActivity] and [CloudSettingsActivity]) and mirrors the cloud master switch. The
 * storage numbers, model verification states and full diagnostics stay placeholders until the
 * storage/diagnostics ticket owns them.
 *
 * Nothing here changes pixel output: every value only selects runtime defaults or policies.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var editorSettings: EditorSettings
    private lateinit var appSettings: AppSettings
    private lateinit var cloudStore: CloudProviderConfigStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        editorSettings = EditorSettings(this)
        appSettings = AppSettings(this)
        cloudStore = CloudProviderConfigStore(this)

        binding.settingsBackButton.setOnClickListener { finish() }
        bindTetherGroup()
        bindEditorGroup()
        bindAiGroup()
        bindCloudGroup()
        refreshEffectiveSummary()
    }

    override fun onResume() {
        super.onResume()
        refreshCloudStatus()
        refreshEffectiveSummary()
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
        }
    }

    private fun bindEditorGroup() {
        refreshEditorValues()
        binding.settingsPreviewResolutionRow.setOnClickListener { choosePreviewMaxEdge() }
        binding.settingsExportQualityRow.setOnClickListener { chooseExportQuality() }
        binding.settingsUndoDepthRow.setOnClickListener { chooseUndoDepth() }
        binding.settingsLutLibraryRow.setOnClickListener { scrollToStorageGroup() }
    }

    private fun bindAiGroup() {
        refreshAiValues()
        binding.settingsAiModelsRow.setOnClickListener {
            startActivity(Intent(this, AiModelSettingsActivity::class.java))
        }
        binding.settingsSkyAssetsRow.setOnClickListener { scrollToStorageGroup() }
        binding.settingsTierOverrideRow.setOnClickListener { chooseTierOverride() }
        binding.settingsIntraThreadsRow.setOnClickListener { chooseIntraThreads() }
        binding.settingsIdleReleaseRow.setOnClickListener { chooseIdleRelease() }
        binding.settingsInpaintModeRow.setOnClickListener { chooseInpaintMode() }
        binding.settingsInpaintBrushRow.setOnClickListener { chooseInpaintBrush() }
        bindSwitch(binding.settingsLivePresetApplyAiSwitch, appSettings.livePresetApplyAi) {
            appSettings.livePresetApplyAi = it
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
        val editor = editorSettings.snapshot()
        val app = appSettings.snapshot()
        binding.settingsAboutVersionValue.text = resolveVersionName()
        binding.settingsAboutRamTierValue.text = resolveRamTierLabel()
        binding.settingsAboutEffectivePreview.text = getString(
            R.string.settings_effective_preview,
            editor.previewMaxEdge.maxEdgePx,
            editor.exportJpegQuality,
            editor.previewCacheJpegQuality
        )
        binding.settingsAboutEffectiveUndo.text = getString(
            R.string.settings_effective_undo,
            editor.undoDepth,
            editor.intraOpThreads
        )
        binding.settingsAboutEffectiveAi.text = getString(
            R.string.settings_effective_ai,
            SettingsOptionLabels.idleRelease(this, editor.idleRelease),
            SettingsOptionLabels.inpaintMode(this, editor.inpaintDefaultMode)
        )
        binding.settingsAboutEffectiveBrush.text =
            getString(R.string.settings_effective_brush, editor.inpaintBrushPx)
        binding.settingsAboutEffectiveTether.text = getString(
            R.string.settings_effective_tether,
            SettingsOptionLabels.onOff(this, app.autoFollowEnabled),
            SettingsOptionLabels.onOff(this, app.deleteOnCamera),
            SettingsOptionLabels.onOff(this, app.keepScreenOn)
        )
    }

    private fun resolveRamTierLabel(): String {
        val override = editorSettings.tierOverride
        val effective = editorSettings.resolveMemoryTier(DeviceMemoryTier.detect(this))
        val tier = getString(
            if (effective == DeviceMemoryTier.LOW) {
                R.string.settings_value_low
            } else {
                R.string.settings_value_standard
            }
        )
        val mode = getString(
            if (override == AiTierOverride.AUTO) {
                R.string.settings_ram_tier_auto
            } else {
                R.string.settings_ram_tier_manual
            }
        )
        return getString(R.string.settings_ram_tier_value, tier, mode)
    }

    private fun resolveVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.settings_about_version_unknown)
    } catch (e: PackageManager.NameNotFoundException) {
        Timber.w(e, "Package info unavailable for the settings diagnostics header")
        getString(R.string.settings_about_version_unknown)
    }

    private fun scrollToStorageGroup() {
        binding.settingsScrollView.smoothScrollTo(0, binding.settingsGroupStorageCard.top)
    }

    private fun bindSwitch(view: SwitchCompat, checked: Boolean, onChange: (Boolean) -> Unit) {
        view.isChecked = checked
        view.setOnCheckedChangeListener { _, value -> onChange(value) }
    }
}
