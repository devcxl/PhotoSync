package cn.devcxl.photosync.activity

import kotlin.math.roundToInt

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

import cn.devcxl.photosync.R
import cn.devcxl.photosync.data.StorageUsageReporter

/**
 * Darkroom-styled list dialog shared by the LUT and sky libraries.
 *
 * Building the rows here keeps [SettingsStorageSection] focused on disk accounting and store calls;
 * the dialog itself only knows how to render names, sizes and a delete action, plus an import button
 * that the caller binds to its SAF launcher.
 */
internal object SettingsLibraryDialogView {

    /** One line of a library list: a readable name and its size/dimension summary. */
    data class Row(val title: String, val subtitle: String)

    /**
     * Shows the library and returns the dialog so the caller can dismiss it before asking for a
     * delete confirmation.
     *
     * @param onDeleteRow Receives the index into [rows] the user wants to remove.
     */
    fun show(
        activity: AppCompatActivity,
        titleRes: Int,
        totalBytes: Long,
        rows: List<Row>,
        onDeleteRow: (Int) -> Unit,
        onImport: () -> Unit
    ): AlertDialog {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 4))
        }
        container.addView(usageLine(activity, totalBytes, rows.size))
        if (rows.isEmpty()) {
            container.addView(emptyLine(activity))
        } else {
            rows.forEachIndexed { index, row ->
                container.addView(libraryRow(activity, row) { onDeleteRow(index) })
            }
        }
        val scroll = ScrollView(activity).apply { addView(container) }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(titleRes)
            .setView(scroll)
            .setPositiveButton(R.string.settings_library_import) { _, _ -> onImport() }
            .setNegativeButton(R.string.settings_library_close, null)
            .create()
        dialog.show()
        return dialog
    }

    /** Confirms deleting [label]; the caller dismisses its library dialog first. */
    fun confirmDelete(activity: AppCompatActivity, label: String, onConfirmed: () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_library_delete_title)
            .setMessage(activity.getString(R.string.settings_library_delete_message, label))
            .setPositiveButton(R.string.settings_library_delete) { _, _ -> onConfirmed() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun usageLine(activity: AppCompatActivity, totalBytes: Long, count: Int): TextView =
        TextView(activity).apply {
            text = activity.getString(
                R.string.settings_library_usage,
                StorageUsageReporter.formatBytes(totalBytes),
                count
            )
            setTextColor(ContextCompat.getColor(activity, R.color.darkroom_text_secondary))
            textSize = 12f
        }

    private fun emptyLine(activity: AppCompatActivity): TextView = TextView(activity).apply {
        setText(R.string.settings_library_empty)
        setTextColor(ContextCompat.getColor(activity, R.color.darkroom_text_muted))
        textSize = 13f
        setPadding(0, dp(activity, 12), 0, dp(activity, 12))
    }

    private fun libraryRow(
        activity: AppCompatActivity,
        row: Row,
        onDelete: () -> Unit
    ): LinearLayout {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 8), 0, dp(activity, 8))
        }
        val labels = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        labels.addView(TextView(activity).apply {
            text = row.title
            setTextColor(ContextCompat.getColor(activity, R.color.darkroom_text_primary))
            textSize = 14f
        })
        labels.addView(TextView(activity).apply {
            text = row.subtitle
            setTextColor(ContextCompat.getColor(activity, R.color.darkroom_text_muted))
            textSize = 12f
        })
        container.addView(labels)
        container.addView(TextView(activity).apply {
            setText(R.string.settings_library_delete)
            setTextColor(ContextCompat.getColor(activity, R.color.danger_text))
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(activity, 10), dp(activity, 6), dp(activity, 10), dp(activity, 6))
            isClickable = true
            isFocusable = true
            setBackgroundResource(R.drawable.bg_glass_pill)
            setOnClickListener { onDelete() }
        })
        return container
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()
}
