package cn.devcxl.photosync.editor.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

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

    /** Enables or disables the export button while a bake is running. */
    fun setExportEnabled(enabled: Boolean) {
        binding.btnExport.isEnabled = enabled
        binding.btnExport.alpha = if (enabled) 1.0f else DISABLED_ALPHA
    }

    /** Shows or hides the "comparing original" status pill. */
    fun setComparePillVisible(comparing: Boolean) {
        binding.compareStatusPill.visibility = if (comparing) View.VISIBLE else View.GONE
    }

    private companion object {
        private const val DISABLED_ALPHA = 0.4f
    }
}
