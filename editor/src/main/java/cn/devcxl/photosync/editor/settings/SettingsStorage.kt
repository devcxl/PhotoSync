package cn.devcxl.photosync.editor.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Single `SharedPreferences` file shared by [EditorSettings] and the `:app` side `AppSettings`.
 *
 * The file is shared only so a backup or a "reset everything" action sees one artifact. Each facade
 * owns a disjoint key set and writes independently; the dependency direction stays `:app -> :editor`
 * because `:app` can read this constant while `:editor` never references the app module.
 */
object SettingsStorage {

    /** Name of the file holding every typed setting. */
    const val FILE_NAME = "photosync_settings"

    /** Opens the shared settings file. */
    fun open(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
}
