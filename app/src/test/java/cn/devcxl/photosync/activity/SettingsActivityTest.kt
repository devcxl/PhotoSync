package cn.devcxl.photosync.activity

import android.app.Application
import android.content.Context
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

import cn.devcxl.photosync.R
import cn.devcxl.photosync.data.settings.AppSettings
import cn.devcxl.photosync.editor.settings.SettingsStorage

/**
 * Seam under test: [SettingsActivity] — the six `SettingsGroup` cards render with their core controls,
 * and the AI model / cloud rows still route to the two secondary pages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsActivityTest {

    @Before
    fun clearPreferences() {
        val context = RuntimeEnvironment.getApplication()
        SettingsStorage.open(context).edit().clear().commit()
        context.getSharedPreferences(AppSettings.LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
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
        assertFalse(activity.findViewById<View>(R.id.settingsStorageClearAllButton).isEnabled)
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

    private fun assertGroupTitle(activity: SettingsActivity, viewId: Int, stringId: Int) {
        assertEquals(
            activity.getString(stringId),
            activity.findViewById<TextView>(viewId).text.toString()
        )
    }

    private fun text(activity: SettingsActivity, viewId: Int): String =
        activity.findViewById<TextView>(viewId).text.toString()
}
