package cn.devcxl.photosync.activity

import android.content.Context

import androidx.appcompat.app.AlertDialog

import cn.devcxl.photosync.R
import cn.devcxl.photosync.editor.domain.CopyMask
import cn.devcxl.photosync.editor.domain.EditGraph

/**
 * The cross-photo `复制参数` / `粘贴参数` clipboard of the main gallery.
 *
 * A short copy uses the default [CopyMask] (look axes only), a long copy opens the checklist dialog so a
 * burst shot can also carry its crop, liquify or face/body warp when the user really wants that.
 *
 * The clipboard is kept in memory, not on disk: it is a transient hand-off between two photos of the
 * same shoot, and persisting it would raise "which frame is this crop from?" questions forever.
 */
object EditClipboard {

    /** The last copied parameter subset, or `null` when nothing was copied yet. */
    @Volatile
    var pending: EditGraph? = null

    /** True when [paste] has something to paste. */
    val hasContent: Boolean
        get() = pending != null

    /** Copies the masked axes of [source] into the clipboard. */
    fun copy(source: EditGraph, mask: CopyMask = CopyMask()): EditGraph {
        val extracted = mask.extract(source)
        pending = extracted
        return extracted
    }

    /** Applies the clipboard onto [target]; returns [target] unchanged when the clipboard is empty. */
    fun paste(target: EditGraph, mask: CopyMask = CopyMask()): EditGraph {
        val source = pending ?: return target
        return mask.apply(source, target)
    }

    /** Drops the clipboard contents. */
    fun clear() {
        pending = null
    }
}

/**
 * Copy/paste plumbing that keeps `MainActivity` free of mask dialogs and graph decoding.
 */
internal class MainActivityCopyPasteHelper(private val context: Context) {

    /**
     * Copies [sourceGraph] into [EditClipboard].
     *
     * @param includeChecklist True for the long-press path, which lets the user opt into the
     *   single-frame axes (crop/rotate, liquify, face/body warp).
     * @param onCopied Called with the mask that was actually used; ignored when nothing was copied.
     */
    fun copy(sourceGraph: EditGraph?, includeChecklist: Boolean, onCopied: (CopyMask) -> Unit) {
        if (sourceGraph == null) return
        if (!includeChecklist) {
            EditClipboard.copy(sourceGraph)
            onCopied(CopyMask())
            return
        }
        CopyMaskDialog.show(context) { mask ->
            EditClipboard.copy(sourceGraph, mask)
            onCopied(mask)
        }
    }

    /**
     * Applies the clipboard onto every graph in [targets].
     *
     * @param onPasted Called for each photo whose graph actually changed.
     * @return The number of photos the paste changed.
     */
    fun paste(targets: Map<Long, EditGraph>, onPasted: (Long, EditGraph) -> Unit): Int {
        if (!EditClipboard.hasContent) return 0
        var changed = 0
        for ((photoId, graph) in targets) {
            val pasted = EditClipboard.paste(graph)
            if (pasted != graph) {
                onPasted(photoId, pasted)
                changed++
            }
        }
        return changed
    }
}

/**
 * The long-press `复制参数` checklist.
 */
internal object CopyMaskDialog {

    /**
     * Shows the mask checklist and calls [onConfirm] with the chosen mask.
     *
     * Every single-frame axis defaults to off, mirroring [CopyMask]'s defaults, so the dialog starts in
     * exactly the state a short tap produces and the user only opts into what they need.
     */
    fun show(context: Context, onConfirm: (CopyMask) -> Unit) {
        val labels = arrayOf(
            R.string.copy_mask_base_look,
            R.string.copy_mask_tone,
            R.string.copy_mask_color,
            R.string.copy_mask_detail,
            R.string.copy_mask_effects,
            R.string.copy_mask_portrait_skin,
            R.string.copy_mask_geometry,
            R.string.copy_mask_liquify,
            R.string.copy_mask_portrait_warp
        ).map { context.getString(it) }.toTypedArray()
        val checked = BooleanArray(CopyMask.AXIS_COUNT) { index -> index < DEFAULT_INCLUDED_OPTIONS }
        AlertDialog.Builder(context)
            .setTitle(R.string.copy_mask_title)
            .setMultiChoiceItems(labels, checked) { _, index, isChecked ->
                checked[index] = isChecked
            }
            .setPositiveButton(R.string.copy_mask_confirm) { _, _ ->
                onConfirm(CopyMask.fromCheckboxState(checked))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** How many leading checkboxes the default mask includes. */
    private const val DEFAULT_INCLUDED_OPTIONS = 6
}
