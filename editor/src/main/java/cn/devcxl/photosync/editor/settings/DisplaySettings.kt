package cn.devcxl.photosync.editor.settings

import android.content.Context

/**
 * Read-only view of the `:app`-owned `display_keep_screen_on` key.
 *
 * The key belongs to the `:app` `AppSettings` facade, which owns every tether/gallery key in the
 * shared file. `:editor` still needs it for the editor window, and the dependency rule forbids the
 * editor module from depending on `:app`; this object therefore reads the same preference file
 * directly and deliberately stays outside [EditorSettings.ALL_KEYS], which must remain disjoint from
 * the app facade.
 */
object DisplaySettings {

    /** Key of the keep-screen-on switch; identical to `AppSettings.KEY_KEEP_SCREEN_ON`. */
    const val KEY_KEEP_SCREEN_ON = "display_keep_screen_on"

    /** Historical default: the gallery and editor keep the screen awake. */
    const val DEFAULT_KEEP_SCREEN_ON: Boolean = true

    /** True when the gallery/editor should keep the screen awake while visible. */
    fun isKeepScreenOn(context: Context): Boolean =
        SettingsStorage.open(context).getBoolean(KEY_KEEP_SCREEN_ON, DEFAULT_KEEP_SCREEN_ON)
}
