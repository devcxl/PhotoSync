package cn.devcxl.photosync.activity

import java.io.File

import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.widget.CompoundButton
import android.widget.TextView

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

import androidx.core.content.pm.PackageInfoCompat

import cn.devcxl.photosync.R
import cn.devcxl.photosync.data.StorageCategory
import cn.devcxl.photosync.data.StorageUsageReporter
import cn.devcxl.photosync.data.settings.AppSettings
import cn.devcxl.photosync.editor.settings.SettingsStorage
import cn.devcxl.photosync.editor.vision.AiModelCatalog

/**
 * Seam under test: [SettingsActivity] — the six `SettingsGroup` cards, the storage group with live
 * per-folder usage and cleanup, the LUT / sky library dialogs, and the read-only diagnostics group.
 *
 * Coroutines that measure disk usage run on `Dispatchers.IO`, so every asynchronous assertion idles
 * the paused main looper in a bounded loop instead of assuming an instant repaint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsActivityTest {

    private lateinit var context: Context

    @Before
    fun clearState() {
        context = RuntimeEnvironment.getApplication()
        SettingsStorage.open(context).edit().clear().commit()
        context.getSharedPreferences(AppSettings.LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        storageDirectories().forEach { it.deleteRecursively() }
    }

    @Test
    fun shouldRenderEverySettingsGroup_whenOpened() {
        val activity = launch()

        assertGroupTitle(activity, R.id.settingsGroupTetherTitle, R.string.settings_group_tether)
        assertGroupTitle(activity, R.id.settingsGroupEditorTitle, R.string.settings_group_editor)
        assertGroupTitle(activity, R.id.settingsGroupAiTitle, R.string.settings_group_ai)
        assertGroupTitle(activity, R.id.settingsGroupCloudTitle, R.string.settings_group_cloud)
        assertGroupTitle(activity, R.id.settingsGroupStorageTitle, R.string.settings_group_storage)
        assertGroupTitle(activity, R.id.settingsGroupAboutTitle, R.string.settings_group_about)

        assertNotNull(activity.findViewById<View>(R.id.settingsGroupTetherCard))
        assertNotNull(activity.findViewById<View>(R.id.settingsGroupEditorCard))
        assertNotNull(activity.findViewById<View>(R.id.settingsGroupAiCard))
        assertNotNull(activity.findViewById<View>(R.id.settingsGroupCloudCard))
        assertNotNull(activity.findViewById<View>(R.id.settingsGroupStorageCard))
        assertNotNull(activity.findViewById<View>(R.id.settingsGroupAboutCard))
    }

    @Test
    fun shouldRenderDefaults_whenOpenedWithCleanPreferences() {
        val activity = launch()
        val autoFollow = activity.findViewById<CompoundButton>(R.id.settingsAutoFollowSwitch)
        val deleteOnCamera = activity.findViewById<CompoundButton>(R.id.settingsDeleteOnCameraSwitch)
        val keepScreenOn = activity.findViewById<CompoundButton>(R.id.settingsKeepScreenOnSwitch)
        val livePreset = activity.findViewById<CompoundButton>(R.id.settingsLivePresetEnabledSwitch)
        val applyAi = activity.findViewById<CompoundButton>(R.id.settingsLivePresetApplyAiSwitch)

        assertTrue(autoFollow.isChecked)
        assertFalse(deleteOnCamera.isChecked)
        assertTrue(keepScreenOn.isChecked)
        assertTrue(livePreset.isChecked)
        assertTrue(applyAi.isChecked)

        assertEquals(
            activity.getString(R.string.settings_value_px, 2048),
            text(activity, R.id.settingsPreviewResolutionValue)
        )
        assertEquals("95", text(activity, R.id.settingsExportQualityValue))
        assertEquals("50", text(activity, R.id.settingsUndoDepthValue))
        assertEquals("2", text(activity, R.id.settingsIntraThreadsValue))
        assertEquals(
            activity.getString(R.string.settings_value_fine),
            text(activity, R.id.settingsInpaintModeValue)
        )
        assertEquals(
            activity.getString(R.string.settings_cloud_disabled),
            text(activity, R.id.settingsCloudStatusValue)
        )
        awaitText(activity, R.id.settingsStorageModelsValue, "0 B")
        assertEquals("0 B", text(activity, R.id.settingsStorageTotalValue))
        assertFalse(activity.findViewById<View>(R.id.settingsStorageClearAllButton).isEnabled)
    }

    @Test
    fun shouldPersistTheAutoFollowSwitch_whenToggledHere() {
        val activity = launch()
        activity.findViewById<CompoundButton>(R.id.settingsAutoFollowSwitch).performClick()

        assertFalse(AppSettings(context).autoFollowEnabled)
    }

    @Test
    fun shouldRenderLiveUsageForEveryCategory_whenFilesExist() {
        writeFile(StorageCategory.MODELS, "a.onnx", bytes = 100)
        writeFile(StorageCategory.AI_EDITS, "7/mask.png", bytes = 10)
        writeFile(StorageCategory.PREVIEW_CACHE, "7/preview.jpg", bytes = 20)
        writeFile(StorageCategory.LUTS, "film.lut16f", bytes = 30)
        writeFile(StorageCategory.SKY, "sky_abc.jpg", bytes = 40)
        val activity = launch()

        awaitText(activity, R.id.settingsStorageModelsValue, "100 B")
        assertEquals("10 B", text(activity, R.id.settingsStorageAiEditsValue))
        assertEquals("20 B", text(activity, R.id.settingsStoragePreviewCacheValue))
        assertEquals("30 B", text(activity, R.id.settingsStorageLutValue))
        assertEquals("40 B", text(activity, R.id.settingsStorageSkyValue))
        assertEquals(
            StorageUsageReporter.formatBytes(200L),
            text(activity, R.id.settingsStorageTotalValue)
        )
        assertTrue(activity.findViewById<View>(R.id.settingsStorageClearAllButton).isEnabled)
        assertTrue(activity.findViewById<View>(R.id.settingsStorageModelsClearButton).isEnabled)
    }

    @Test
    fun shouldClearOneCategoryAndRefreshTheNumbers_whenConfirmingTheDialog() {
        writeFile(StorageCategory.MODELS, "a.onnx", bytes = 100)
        val activity = launch()
        awaitText(activity, R.id.settingsStorageModelsValue, "100 B")

        activity.findViewById<View>(R.id.settingsStorageModelsClearButton).performClick()
        val dialog = awaitDialog()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleMainLooper()

        awaitText(activity, R.id.settingsStorageModelsValue, "0 B")
        assertEquals("0 B", text(activity, R.id.settingsStorageTotalValue))
        assertTrue(
            "the model directory must be empty after its own cleanup",
            categoryDirectory(StorageCategory.MODELS).listFiles().orEmpty().isEmpty()
        )
    }

    @Test
    fun shouldOpenTheLutLibraryDialog_whenTheLibraryRowIsClicked() {
        val activity = launch()
        activity.findViewById<View>(R.id.settingsLutLibraryRow).performClick()
        val dialog = awaitDialog()

        assertEquals(
            activity.getString(R.string.settings_lut_library_title),
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString()
        )
        assertEquals(
            activity.getString(R.string.settings_library_import),
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString()
        )
    }

    @Test
    fun shouldOpenTheSkyLibraryDialog_whenTheSkyRowIsClicked() {
        val activity = launch()
        activity.findViewById<View>(R.id.settingsSkyAssetsRow).performClick()
        val dialog = awaitDialog()

        assertEquals(
            activity.getString(R.string.settings_sky_library_title),
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString()
        )
    }

    @Test
    fun shouldRenderTheCompleteDiagnostics_whenOpened() {
        val activity = launch()

        assertEquals(
            activity.getString(
                R.string.settings_about_version_value,
                packagedVersionName(),
                packagedVersionCode()
            ),
            text(activity, R.id.settingsAboutVersionValue)
        )
        assertEquals("1.22.0", text(activity, R.id.settingsAboutOrtVersionValue))
        assertNotPlaceholder(activity, R.id.settingsAboutRamTotalValue)
        assertNotPlaceholder(activity, R.id.settingsAboutRamTierValue)
        assertNotPlaceholder(activity, R.id.settingsAboutGlesDeviceValue)
        assertNotPlaceholder(activity, R.id.settingsAboutGlesRendererValue)
        assertNotPlaceholder(activity, R.id.settingsAboutGpuBlacklistValue)
        assertNotPlaceholder(activity, R.id.settingsAboutNetworkValue)
        assertNotPlaceholder(activity, R.id.settingsAboutEffectivePreview)
        assertNotPlaceholder(activity, R.id.settingsAboutEffectiveUndo)
        assertNotPlaceholder(activity, R.id.settingsAboutEffectiveAi)
        assertNotPlaceholder(activity, R.id.settingsAboutEffectiveBrush)
        assertNotPlaceholder(activity, R.id.settingsAboutEffectiveTether)
        assertNotPlaceholder(activity, R.id.settingsAboutEffectiveTier)
        assertNotPlaceholder(activity, R.id.settingsAboutEffectiveLivePreset)

        awaitCondition {
            activity.findViewById<android.widget.LinearLayout>(R.id.settingsAboutModelsList).childCount ==
                AiModelCatalog.onDemandModels.size
        }
        val firstRow = activity.findViewById<android.widget.LinearLayout>(R.id.settingsAboutModelsList)
            .getChildAt(0) as TextView
        assertTrue(
            "an uninstalled model must be reported as such",
            firstRow.text.contains(activity.getString(R.string.ai_models_status_not_installed))
        )
        assertNotPlaceholder(activity, R.id.settingsAboutModelsUsage)
    }

    @Test
    fun shouldOpenAiModelSettings_whenAiModelsRowClicked() {
        val activity = launch()
        activity.findViewById<View>(R.id.settingsAiModelsRow).performClick()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("the AI model row must start a secondary page", started)
        assertEquals(AiModelSettingsActivity::class.java.name, started.component?.className)
    }

    @Test
    fun shouldOpenCloudSettings_whenCloudEntryClicked() {
        val activity = launch()
        activity.findViewById<View>(R.id.settingsCloudEntryRow).performClick()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("the cloud entry must start a secondary page", started)
        assertEquals(CloudSettingsActivity::class.java.name, started.component?.className)
    }

    private fun launch(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    private fun packagedVersionName(): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    private fun packagedVersionCode(): Long = PackageInfoCompat.getLongVersionCode(
        context.packageManager.getPackageInfo(context.packageName, 0)
    )

    private fun assertGroupTitle(activity: SettingsActivity, viewId: Int, stringId: Int) {
        assertEquals(
            activity.getString(stringId),
            activity.findViewById<TextView>(viewId).text.toString()
        )
    }

    private fun text(activity: SettingsActivity, viewId: Int): String =
        activity.findViewById<TextView>(viewId).text.toString()

    private fun assertNotPlaceholder(activity: SettingsActivity, viewId: Int) {
        val value = text(activity, viewId)
        assertTrue("$viewId must render a real value", value.isNotBlank())
        assertFalse(
            "$viewId must not stay at the placeholder",
            value == activity.getString(R.string.settings_storage_unknown)
        )
    }

    private fun awaitText(activity: SettingsActivity, viewId: Int, expected: String) {
        awaitCondition { text(activity, viewId) == expected }
        assertEquals(expected, text(activity, viewId))
    }

    private fun awaitDialog() = try {
        var dialog: androidx.appcompat.app.AlertDialog? = null
        awaitCondition {
            idleMainLooper()
            dialog = ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog
            dialog?.isShowing == true
        }
        checkNotNull(dialog)
    } catch (error: AssertionError) {
        throw AssertionError("Timed out waiting for a settings dialog", error)
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            idleMainLooper()
            if (condition()) return
            Thread.sleep(AWAIT_POLL_MS)
        }
        idleMainLooper()
        assertTrue("Timed out waiting for the settings page to settle", condition())
    }

    private fun idleMainLooper() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun storageDirectories(): List<File> = listOf(
        File(context.filesDir, StorageCategory.MODELS.directoryName),
        File(context.filesDir, StorageCategory.AI_EDITS.directoryName),
        File(context.filesDir, StorageCategory.LUTS.directoryName),
        File(context.filesDir, StorageCategory.SKY.directoryName),
        File(context.cacheDir, StorageCategory.PREVIEW_CACHE.directoryName)
    )

    private fun categoryDirectory(category: StorageCategory): File = when (category) {
        StorageCategory.PREVIEW_CACHE -> File(context.cacheDir, category.directoryName)
        else -> File(context.filesDir, category.directoryName)
    }

    private fun writeFile(category: StorageCategory, relativePath: String, bytes: Int) {
        val file = File(categoryDirectory(category), relativePath)
        val parent = checkNotNull(file.parentFile) { "No parent for $file" }
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IllegalStateException("Could not create $parent")
        }
        file.writeBytes(ByteArray(bytes) { 1 })
    }

    private companion object {
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val AWAIT_POLL_MS = 5L
    }
}
