package cn.devcxl.photosync.editor.ui

import java.io.IOException

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import timber.log.Timber

import cn.devcxl.photosync.editor.lut.CubeLutInfo
import cn.devcxl.photosync.editor.lut.CubeLutStore

/**
 * Reads a `.cube` document picked through the Storage Access Framework and installs it in
 * [CubeLutStore].
 *
 * Every failure path (no stream, malformed file, revoked permission) returns `null` so the caller
 * can show a localized error instead of crashing on a community LUT.
 */
internal object EditorLutImporter {

    /** Installs the document behind [uri], or returns `null` when it cannot be imported. */
    fun import(resolver: ContentResolver, store: CubeLutStore, uri: Uri): CubeLutInfo? = try {
        resolver.openInputStream(uri)?.use { input ->
            store.importFromStream(displayName(resolver, uri), input).getOrElse { error ->
                Timber.w(error, "Rejected the imported .cube LUT")
                null
            }
        }
    } catch (error: IOException) {
        Timber.e(error, "Failed to read the imported .cube LUT")
        null
    } catch (error: SecurityException) {
        Timber.e(error, "No permission to read the picked .cube LUT")
        null
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String = try {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        } ?: DEFAULT_NAME
    } catch (error: SecurityException) {
        Timber.w(error, "Cannot query the picked LUT display name")
        DEFAULT_NAME
    }

    private const val DEFAULT_NAME = "imported.cube"
}
