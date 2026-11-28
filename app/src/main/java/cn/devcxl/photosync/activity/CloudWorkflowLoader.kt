package cn.devcxl.photosync.activity

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

import org.json.JSONException
import org.json.JSONObject

import timber.log.Timber

/** Result of importing a ComfyUI workflow document. */
internal sealed interface CloudWorkflowLoad {
    /** A readable UTF-8 workflow document. */
    data class Loaded(val fileName: String, val json: String) : CloudWorkflowLoad

    /** The document exceeds the supported size. */
    data object TooLarge : CloudWorkflowLoad

    /** The document could not be opened or was empty. */
    data object Unreadable : CloudWorkflowLoad

    /** The document is not a JSON object. */
    data object Invalid : CloudWorkflowLoad
}

/**
 * Reads a user-selected ComfyUI API workflow from SAF.
 *
 * The document is read with a hard byte ceiling so a huge file can never be loaded into memory just
 * to be rejected later. Nothing here talks to the network.
 */
internal object CloudWorkflowLoader {

    /** Maximum accepted workflow size: far above a real API export, far below a memory problem. */
    const val MAX_WORKFLOW_BYTES = 512 * 1024

    /** Loads [uri] as UTF-8 text. */
    fun load(context: Context, uri: Uri): CloudWorkflowLoad {
        val fileName = queryDisplayName(context, uri) ?: DEFAULT_FILE_NAME
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return CloudWorkflowLoad.Unreadable
            val bytes = stream.use(::readBounded) ?: return CloudWorkflowLoad.TooLarge
            val json = String(bytes, Charsets.UTF_8).trim()
            if (json.isEmpty()) return CloudWorkflowLoad.Unreadable
            try {
                JSONObject(json)
            } catch (e: JSONException) {
                return CloudWorkflowLoad.Invalid
            }
            CloudWorkflowLoad.Loaded(fileName, json)
        } catch (e: IOException) {
            Timber.w("Could not read the selected workflow document (%s)", e.javaClass.simpleName)
            CloudWorkflowLoad.Unreadable
        } catch (e: SecurityException) {
            Timber.w("The platform denied reading the selected workflow document")
            CloudWorkflowLoad.Unreadable
        }
    }

    private fun readBounded(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_WORKFLOW_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: SecurityException) {
        Timber.w("Could not read the selected workflow document name")
        null
    } catch (e: RuntimeException) {
        Timber.w("Could not query the selected workflow document")
        null
    }

    private const val DEFAULT_FILE_NAME = "workflow.json"
}
