package cn.devcxl.photosync.editor.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

import androidx.core.content.ContextCompat

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.BaseLook

/**
 * Binds the floating editor top bar (undo, redo, press-and-hold compare, `BaseLook` toggle and
 * Done) and keeps its enabled/visibility states in sync with the edit history.
 */
internal class EditorTopBarBinder(
    private val binding: ActivityEditorBinding,
    private val historyCoordinator: EditorHistoryCoordinator,
    private val host: Host
) {

    /** Callbacks the activity implements for the top bar actions. */
    interface Host {
        /** Press-and-hold compare started or ended. */
        fun onCompareChanged(comparing: Boolean)

        /** The user picked a `BaseLook` baseline. */
        fun onBaseLookSelected(baseLook: BaseLook)

        /** The user tapped Done. */
        fun onDoneRequested()

        /** The user asked for a full-resolution bake of the current graph. */
        fun onExportRequested()
    }

    /** Wires every top-bar control and paints its initial state. */
    @SuppressLint("ClickableViewAccessibility")
    fun bind() {
        binding.btnUndo.setOnClickListener { historyCoordinator.undo() }
        binding.btnRedo.setOnClickListener { historyCoordinator.redo() }
        binding.btnCompare.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> host.onCompareChanged(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> host.onCompareChanged(false)
            }
            false
        }
        binding.baseLookToggle.onLookSelected = host::onBaseLookSelected
        binding.btnDone.setOnClickListener { host.onDoneRequested() }
        binding.btnExport.setOnClickListener { host.onExportRequested() }
        updateHistoryButtons()
    }

    /** Enables or dims undo/redo from the current history depth. */
    fun updateHistoryButtons() {
        val canUndo = historyCoordinator.canUndo
        val canRedo = historyCoordinator.canRedo
        binding.btnUndo.isEnabled = canUndo
        binding.btnRedo.isEnabled = canRedo
        binding.btnUndo.alpha = if (canUndo) 1.0f else DISABLED_ALPHA
        binding.btnRedo.alpha = if (canRedo) 1.0f else DISABLED_ALPHA
    }

    /** Shows the `BaseLook` pill only for linear RAW sources and selects [baseLook]. */
    fun updateBaseLookPill(baseLook: BaseLook, visible: Boolean) {
        binding.baseLookToggle.visibility = if (visible) View.VISIBLE else View.GONE
        binding.baseLookToggle.setLook(baseLook)
    }

    /** Shows or hides the in-button spinner and full-resolution export progress overlay. */
    fun setExporting(exporting: Boolean) {
        binding.btnExport.isEnabled = !exporting
        val icon = if (exporting) {
            null
        } else {
            ContextCompat.getDrawable(binding.btnExport.context, R.drawable.ic_editor_export_24)
        }
        binding.btnExport.setImageDrawable(icon)
        binding.btnExportSpinner.visibility = if (exporting) View.VISIBLE else View.GONE
        fadeOverlay(binding.exportProgressOverlay, exporting)
    }

    /** Shows an in-button circular spinner inside Done while preview persistence runs. */
    fun setSaving(saving: Boolean) {
        binding.btnDone.isEnabled = !saving
        val icon = if (saving) {
            null
        } else {
            ContextCompat.getDrawable(binding.btnDone.context, R.drawable.ic_editor_done_24)
        }
        binding.btnDone.setImageDrawable(icon)
        binding.btnDoneSpinner.visibility = if (saving) View.VISIBLE else View.GONE
    }

    /** Shows or hides the "comparing original" status pill. */
    fun setComparePillVisible(comparing: Boolean) {
        binding.compareStatusPill.visibility = if (comparing) View.VISIBLE else View.GONE
    }

    private fun fadeOverlay(overlay: View, visible: Boolean) {
        overlay.animate().cancel()
        if (visible) {
            overlay.alpha = 0.0f
            overlay.visibility = View.VISIBLE
            overlay.animate().alpha(1.0f).setDuration(OVERLAY_FADE_MS).withEndAction(null).start()
        } else if (overlay.visibility == View.VISIBLE) {
            overlay.animate()
                .alpha(0.0f)
                .setDuration(OVERLAY_FADE_MS)
                .withEndAction { overlay.visibility = View.GONE }
                .start()
        }
    }

    private companion object {
        private const val DISABLED_ALPHA = 0.4f
        private const val OVERLAY_FADE_MS = 180L
    }
}
