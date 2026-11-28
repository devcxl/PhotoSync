package cn.devcxl.photosync.editor.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.ToneOps

/**
 * Seam under test: `editor_undo_depth` reaching [EditorUndoController]. The controller must keep the
 * default, honour a custom depth and clamp values written by an older build.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorUndoDepthWiringTest {

    private lateinit var preferences: SharedPreferences
    private lateinit var settings: EditorSettings

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        preferences = SettingsStorage.open(context)
        preferences.edit().clear().commit()
        settings = EditorSettings(preferences)
    }

    @Test
    fun shouldKeepFiftySteps_whenUsingTheDefaultSetting() {
        val controller = controllerFromSettings()

        pushChanges(controller, count = 80)

        assertEquals(EditorSettings.DEFAULT_UNDO_DEPTH, controller.undoDepth)
    }

    @Test
    fun shouldKeepTheCustomDepth_whenTheUserLoweredIt() {
        settings.undoDepth = 20
        val controller = controllerFromSettings()

        pushChanges(controller, count = 80)

        assertEquals(20, controller.undoDepth)
    }

    @Test
    fun shouldClampTheDepth_whenAStoredValueIsOutOfRange() {
        preferences.edit().putInt(EditorSettings.KEY_EDITOR_UNDO_DEPTH, 5).commit()
        val controller = controllerFromSettings()

        pushChanges(controller, count = 80)

        assertEquals(EditorSettings.MIN_UNDO_DEPTH, controller.undoDepth)
    }

    private fun controllerFromSettings(): EditorUndoController =
        EditorUndoController(maxDepth = EditorRenderSettings.from(settings).undoDepth)

    /** Each call uses a new timestamp so the 300 ms coalescing window never merges the steps. */
    private fun pushChanges(controller: EditorUndoController, count: Int) {
        repeat(count) { index ->
            controller.onParameterDrag(
                EditGraph(tone = ToneOps(exposure = index.toFloat() / 100.0f)),
                EditParameterKey.TONE_EXPOSURE,
                timestampMs = index.toLong() * 1_000L
            )
        }
    }
}
