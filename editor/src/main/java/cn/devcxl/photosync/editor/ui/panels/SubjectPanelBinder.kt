package cn.devcxl.photosync.editor.ui.panels

import android.view.View

import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.SubjectBackgroundMode
import cn.devcxl.photosync.editor.ui.widgets.SubjectPanelView

/**
 * Binds the `AI -> 主体` panel to the editor controller.
 *
 * A mode change is not a plain graph write: the controller must first guarantee that a subject mask
 * exists (model download, inference, composite load), so the binder only forwards the user's intent
 * through [Host]. The controller then owns the undo transaction and the asynchronous work.
 *
 * The seven effect dials are forwarded straight to
 * [cn.devcxl.photosync.editor.ui.EditorSubjectController] by
 * [cn.devcxl.photosync.editor.ui.EditorAiToolHost] through the view's own grading listener: they are
 * continuous parameter writes with a different failure policy (a first drag may start the same
 * inference), so routing them through the exclusive mode/colour host would blur both contracts.
 */
internal class SubjectPanelBinder(
    private val binding: ActivityEditorBinding,
    private val host: Host
) {

    /** Host actions behind the panel. */
    interface Host {
        /** The user selected a background mode. */
        fun onSubjectModeRequested(mode: SubjectBackgroundMode)

        /** The user picked a solid background colour. */
        fun onSubjectColorRequested(argb: Int)
    }

    private val panel = binding.subjectPanel.root

    init {
        panel.listener = object : SubjectPanelView.Listener {
            override fun onModeSelected(mode: SubjectBackgroundMode) =
                host.onSubjectModeRequested(mode)

            override fun onColorSelected(argb: Int) = host.onSubjectColorRequested(argb)
        }
    }

    /** Shows the panel only while the subject tool owns the tier-1 slot. */
    fun setVisible(visible: Boolean) {
        panel.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** Repaints the panel for [graph]. */
    fun refresh(graph: EditGraph) {
        panel.bind(graph.subject)
    }
}
