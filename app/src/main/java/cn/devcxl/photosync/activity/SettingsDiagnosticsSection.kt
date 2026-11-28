package cn.devcxl.photosync.activity

import java.io.File

import android.Manifest
import android.app.ActivityManager
import android.content.pm.PackageManager
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.R
import cn.devcxl.photosync.data.StorageUsageReporter
import cn.devcxl.photosync.data.settings.AppSettings
import cn.devcxl.photosync.databinding.ActivitySettingsBinding
import cn.devcxl.photosync.editor.settings.AiTierOverride
import cn.devcxl.photosync.editor.settings.EditorSettings
import cn.devcxl.photosync.editor.vision.AiModelCatalog
import cn.devcxl.photosync.editor.vision.AiModelDownloadManager
import cn.devcxl.photosync.editor.vision.AiModelFailureUiCopy
import cn.devcxl.photosync.editor.vision.AiModelProbeOutcome
import cn.devcxl.photosync.editor.vision.AiModelSpec
import cn.devcxl.photosync.editor.vision.DeviceMemoryTier
import cn.devcxl.photosync.editor.vision.MediaPipeDelegatePolicy
import cn.devcxl.photosync.editor.vision.OnnxRuntimeInfo
import cn.devcxl.photosync.editor.render.GlCapabilityRegistry
import cn.devcxl.photosync.editor.render.GlCapabilityReport

/**
 * "About & diagnostics" group: a fully read-only page.
 *
 * Every number comes from an already-cached fact — package metadata, `ActivityManager`, the last GL
 * probe, the pinned ONNX Runtime version, the model probe journal — and never from a network call or
 * a native session. A model that was never probed is shown as "not probed" rather than as installed.
 */
internal class SettingsDiagnosticsSection(
    private val activity: AppCompatActivity,
    private val binding: ActivitySettingsBinding,
    private val editorSettings: EditorSettings,
    private val appSettings: AppSettings,
    private val downloadManager: AiModelDownloadManager =
        AiModelDownloadManager(File(activity.filesDir, AiModelCatalog.MODELS_DIR_NAME)),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = activity.lifecycleScope
) {

    /** Repaints the static facts and starts the model snapshot; safe to call from `onResume`. */
    fun refresh() {
        renderStaticFacts()
        refreshModels()
    }

    private fun renderStaticFacts() {
        renderVersion()
        renderMemory()
        renderGl()
        renderOrt()
        renderGpuFallback()
        renderNetworkPermissions()
        renderEffectiveSettings()
    }

    private fun refreshModels() {
        scope.launch {
            val snapshot = withContext(ioDispatcher) { AiModelUsageSnapshot.load(downloadManager) }
            renderModels(snapshot)
        }
    }

    private fun renderVersion() {
        val info = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        } catch (error: PackageManager.NameNotFoundException) {
            Timber.w(error, "Package info unavailable for the settings diagnostics header")
            null
        }
        val versionName = info?.versionName?.takeIf { it.isNotBlank() }
            ?: activity.getString(R.string.settings_about_version_unknown)
        val versionCode = info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
        binding.settingsAboutVersionValue.text = activity.getString(
            R.string.settings_about_version_value,
            versionName,
            versionCode
        )
    }

    private fun renderMemory() {
        val manager = activity.getSystemService(ActivityManager::class.java)
        val totalMem = manager?.let { am ->
            ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem
        } ?: 0L
        binding.settingsAboutRamTotalValue.text = StorageUsageReporter.formatBytes(totalMem)

        val detected = DeviceMemoryTier.fromTotalMemoryBytes(totalMem)
        val effective = editorSettings.resolveMemoryTier(detected)
        val detectedLabel = tierLabel(detected)
        val effectiveLabel = tierLabel(effective)
        val override = editorSettings.tierOverride
        binding.settingsAboutRamTierValue.text = if (override == AiTierOverride.AUTO) {
            activity.getString(
                R.string.settings_ram_tier_value,
                effectiveLabel,
                activity.getString(R.string.settings_ram_tier_auto)
            )
        } else {
            activity.getString(
                R.string.settings_ram_tier_override_value,
                effectiveLabel,
                activity.getString(R.string.settings_ram_tier_manual),
                detectedLabel
            )
        }
    }

    private fun renderGl() {
        val policy = MediaPipeDelegatePolicy(activity)
        binding.settingsAboutGlesDeviceValue.text = describeGlEs(policy.glEsVersion)
        val report = GlCapabilityRegistry.lastReport
        binding.settingsAboutGlesRendererValue.text =
            if (report == null) activity.getString(R.string.settings_gles_unknown) else describeGlReport(report)
    }

    private fun describeGlReport(report: GlCapabilityReport): String {
        val tier = when {
            report.minimalFallbackStage != null -> activity.getString(
                R.string.settings_gles_tier_minimal,
                report.minimalFallbackStage
            )
            report.supportsHalfFloatTargets -> activity.getString(R.string.settings_gles_tier_half_float)
            else -> activity.getString(R.string.settings_gles_tier_rgba8)
        }
        return activity.getString(
            R.string.settings_gles_report,
            report.renderer.ifBlank { activity.getString(R.string.settings_about_version_unknown) },
            report.version.ifBlank { activity.getString(R.string.settings_about_version_unknown) },
            tier,
            report.maxTextureSize
        )
    }

    private fun describeGlEs(reqGlEsVersion: Int): String {
        if (reqGlEsVersion <= 0) return activity.getString(R.string.settings_gles_unknown)
        return activity.getString(
            R.string.settings_gles_device_value,
            reqGlEsVersion shr 16,
            reqGlEsVersion and 0xFFFF
        )
    }

    private fun renderOrt() {
        binding.settingsAboutOrtVersionValue.text = OnnxRuntimeInfo.VERSION
            .takeIf { it.isNotBlank() }
            ?: activity.getString(R.string.settings_about_version_unknown)
    }

    private fun renderGpuFallback() {
        val blacklisted = MediaPipeDelegatePolicy(activity).isGpuBlacklisted
        binding.settingsAboutGpuBlacklistValue.setText(
            if (blacklisted) {
                R.string.settings_gpu_blacklisted_yes
            } else {
                R.string.settings_gpu_blacklisted_no
            }
        )
    }

    private fun renderNetworkPermissions() {
        val requested = try {
            activity.packageManager
                .getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
        } catch (error: PackageManager.NameNotFoundException) {
            Timber.w(error, "Permissions unavailable for the diagnostics page")
            emptyArray()
        }
        val network = requested.filter {
            it == Manifest.permission.INTERNET || it == Manifest.permission.ACCESS_NETWORK_STATE
        }
        binding.settingsAboutNetworkValue.text = if (network.isEmpty()) {
            activity.getString(R.string.settings_about_network_none)
        } else {
            activity.getString(
                R.string.settings_about_network_value,
                network.joinToString(" / ") { it.substringAfterLast('.') }
            )
        }
    }

    private fun renderModels(snapshot: AiModelUsageSnapshot) {
        binding.settingsAboutModelsUsage.text = activity.getString(
            R.string.settings_about_models_usage,
            StorageUsageReporter.formatBytes(snapshot.totalBytes)
        )
        val list = binding.settingsAboutModelsList
        list.removeAllViews()
        AiModelCatalog.onDemandModels.forEach { spec ->
            list.addView(modelRow(spec, snapshot.models[spec.id]))
        }
    }

    private fun modelRow(spec: AiModelSpec, state: AiModelState?): TextView {
        val installed = state?.installed == true
        val partialBytes = state?.partialBytes ?: 0L
        val sizeBytes = when {
            installed -> downloadManager.modelFile(spec).length()
            partialBytes > 0L -> partialBytes
            else -> spec.byteSize
        }
        val status = when {
            installed -> activity.getString(R.string.ai_models_status_installed)
            partialBytes > 0L -> activity.getString(
                R.string.ai_models_status_partial,
                ((partialBytes * 100L) / spec.byteSize.coerceAtLeast(1L)).toInt().coerceIn(0, 100)
            )
            else -> activity.getString(R.string.ai_models_status_not_installed)
        }
        return TextView(activity).apply {
            text = activity.getString(
                R.string.settings_about_model_row,
                activity.getString(AiModelSettingsText.nameRes(spec)),
                AiModelSettingsText.formatBytes(activity, sizeBytes),
                status,
                probeText(state)
            )
            setTextColor(ContextCompat.getColor(activity, R.color.darkroom_text_primary))
            textSize = 12f
            setPadding(0, dp(3), 0, dp(3))
        }
    }

    private fun probeText(state: AiModelState?): String {
        val probe = state?.lastProbe
        return when (probe?.outcome) {
            AiModelProbeOutcome.SUCCEEDED -> activity.getString(R.string.settings_model_probe_success)
            AiModelProbeOutcome.FAILED -> activity.getString(
                R.string.settings_model_probe_failure,
                activity.getString(AiModelFailureUiCopy.of(probe.failureReason).messageRes)
            )
            else -> activity.getString(R.string.settings_model_probe_not_probed)
        }
    }

    private fun renderEffectiveSettings() {
        val editor = editorSettings.snapshot()
        val app = appSettings.snapshot()
        binding.settingsAboutEffectivePreview.text = activity.getString(
            R.string.settings_effective_preview,
            editor.previewMaxEdge.maxEdgePx,
            editor.exportJpegQuality,
            editor.previewCacheJpegQuality
        )
        binding.settingsAboutEffectiveUndo.text = activity.getString(
            R.string.settings_effective_undo,
            editor.undoDepth,
            editor.intraOpThreads
        )
        binding.settingsAboutEffectiveAi.text = activity.getString(
            R.string.settings_effective_ai,
            SettingsOptionLabels.idleRelease(activity, editor.idleRelease),
            SettingsOptionLabels.inpaintMode(activity, editor.inpaintDefaultMode)
        )
        binding.settingsAboutEffectiveBrush.text =
            activity.getString(R.string.settings_effective_brush, editor.inpaintBrushPx)
        binding.settingsAboutEffectiveTether.text = activity.getString(
            R.string.settings_effective_tether,
            SettingsOptionLabels.onOff(activity, app.autoFollowEnabled),
            SettingsOptionLabels.onOff(activity, app.deleteOnCamera),
            SettingsOptionLabels.onOff(activity, app.keepScreenOn)
        )
        binding.settingsAboutEffectiveTier.text = activity.getString(
            R.string.settings_effective_tier,
            SettingsOptionLabels.tierOverride(activity, editor.tierOverride),
            tierLabel(editorSettings.resolveMemoryTier(deviceTier()))
        )
        binding.settingsAboutEffectiveLivePreset.text = activity.getString(
            R.string.settings_effective_live_preset,
            SettingsOptionLabels.onOff(activity, app.livePresetEnabled),
            SettingsOptionLabels.onOff(activity, app.livePresetApplyAi)
        )
    }

    private fun deviceTier(): DeviceMemoryTier {
        val manager = activity.getSystemService(ActivityManager::class.java) ?: return DeviceMemoryTier.LOW
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return DeviceMemoryTier.fromTotalMemoryBytes(info.totalMem)
    }

    private fun tierLabel(tier: DeviceMemoryTier): String = activity.getString(
        if (tier == DeviceMemoryTier.LOW) R.string.settings_value_low else R.string.settings_value_standard
    )

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
