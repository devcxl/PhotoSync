package cn.devcxl.photosync.editor.ui.panels

import android.view.View

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.DiscreteOrientationOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.GeometryOps

/**
 * Binds the Tier-1 icon action bar for discrete 90-degree rotations (CCW / CW), horizontal and
 * vertical mirrors, and orientation reset.
 *
 * Every button acts immediately on tap (zero double-tap delay) and commutes through active flips
 * via [DiscreteOrientationOps] so the displayed picture always rotates in the exact visual
 * direction shown by the button icon.
 */
internal class RotateFlipPanelBinder(
    private val binding: ActivityEditorBinding,
    private val onCommit: (EditParameterKey, (EditGraph) -> EditGraph) -> Unit
) {

    init {
        val panel = binding.rotateFlipPanel
        panel.btnRotateCcw.setOnClickListener {
            applyGeometry(DiscreteOrientationOps::rotateCounterClockwise90)
        }
        panel.btnRotateCw.setOnClickListener {
            applyGeometry(DiscreteOrientationOps::rotateClockwise90)
        }
        panel.btnFlipHorizontal.setOnClickListener {
            applyGeometry(DiscreteOrientationOps::toggleFlipHorizontal)
        }
        panel.btnFlipVertical.setOnClickListener {
            applyGeometry(DiscreteOrientationOps::toggleFlipVertical)
        }
        panel.btnRotateReset.setOnClickListener {
            applyGeometry(DiscreteOrientationOps::resetOrientation)
        }
    }

    /** Shows or hides the rotate/flip icon bar. */
    fun setVisible(visible: Boolean) {
        binding.rotateFlipPanel.rotateFlipPanelRoot.visibility =
            if (visible) View.VISIBLE else View.GONE
    }

    /** Updates active-flip highlights and the reset button's enabled state for [graph]. */
    fun refresh(graph: EditGraph) {
        val panel = binding.rotateFlipPanel
        val geometry = graph.geometry
        panel.btnFlipHorizontal.setBackgroundResource(
            if (geometry.flipH) R.drawable.bg_editor_icon_btn_active else R.drawable.bg_editor_icon_btn
        )
        panel.btnFlipVertical.setBackgroundResource(
            if (geometry.flipV) R.drawable.bg_editor_icon_btn_active else R.drawable.bg_editor_icon_btn
        )
        val modified = DiscreteOrientationOps.isOrientationModified(geometry)
        panel.btnRotateReset.isEnabled = modified
        panel.btnRotateReset.alpha = if (modified) 1.0f else DISABLED_ALPHA
    }

    private fun applyGeometry(transform: (GeometryOps) -> GeometryOps) {
        onCommit(EditParameterKey.GEOMETRY_ROTATE_FLIP) { graph ->
            graph.copy(geometry = transform(graph.geometry))
        }
    }

    private companion object {
        const val DISABLED_ALPHA = 0.35f
    }
}
