package cn.devcxl.photosync.activity

import android.content.Context

import androidx.appcompat.app.AlertDialog

import cn.devcxl.photosync.R
import cn.devcxl.photosync.editor.domain.CopyMask
import cn.devcxl.photosync.editor.domain.EditGraph

/**
 * The cross-photo `复制参数` / `粘贴参数` clipboard of the main gallery.
 *
 * Stores both the masked parameter graph and the exact [CopyMask] chosen during copy so long-press
 * opt-ins (crop, liquify, face/body warp) are faithfully applied at paste time.
 */
object EditClipboard {

    /** Captured copy snapshot paired with the [CopyMask] and originating photo id. */
    data class Entry(
        val source: EditGraph,
        val mask: CopyMask,
        val sourcePhotoId: Long? = null
    )

    @Volatile
    private var entry: Entry? = null

    /** The last copied parameter subset, or `null` when nothing was copied yet. */
    val pending: EditGraph?
        get() = entry?.source

    /** The mask used when [pending] was copied. */
    val pendingMask: CopyMask
        get() = entry?.mask ?: CopyMask()

    /** Photo id of the copy source when known, used to avoid self-cloning liquify files. */
    val sourcePhotoId: Long?
        get() = entry?.sourcePhotoId

    /** True when [paste] has something to paste. */
    val hasContent: Boolean
        get() = entry != null

    /** Copies the masked axes of [source] into the clipboard alongside [mask]. */
    fun copy(
        source: EditGraph,
        mask: CopyMask = CopyMask(),
        sourcePhotoId: Long? = null
    ): EditGraph {
        val extracted = mask.extract(source)
        entry = Entry(source = extracted, mask = mask, sourcePhotoId = sourcePhotoId)
        return extracted
    }

    /** Applies the clipboard onto [target] using [mask] (defaulting to the copied mask). */
    fun paste(target: EditGraph, mask: CopyMask? = null): EditGraph {
        val current = entry ?: return target
        return (mask ?: current.mask).apply(current.source, target)
    }

    /** Drops the clipboard contents. */
    fun clear() {
        entry = null
    }
}

/**
 * Copy/paste plumbing that keeps `MainActivity` free of mask dialogs and graph decoding.
 */
internal class MainActivityCopyPasteHelper(private val context: Context) {

    /**
     * Copies [sourceGraph] into [EditClipboard].
     */
    fun copy(
        sourceGraph: EditGraph?,
        sourcePhotoId: Long?,
        includeChecklist: Boolean,
        onCopied: (CopyMask) -> Unit
    ) {
        if (sourceGraph == null) return
        if (!includeChecklist) {
            EditClipboard.copy(sourceGraph, CopyMask(), sourcePhotoId)
            onCopied(CopyMask())
            return
        }
        CopyMaskDialog.show(context) { mask ->
            EditClipboard.copy(sourceGraph, mask, sourcePhotoId)
            onCopied(mask)
        }
    }

    /**
     * Applies the clipboard onto every graph in [targets].
     *
     * @param onPasted Called for each photo whose graph actually changed, receiving the target
     *   photo id, pasted graph, originating photo id, and active [CopyMask].
     * @return The number of photos the paste changed.
     */
    fun paste(
        targets: Map<Long, EditGraph>,
        onPasted: (Long, EditGraph, Long?, CopyMask) -> Unit
    ): Int {
        if (!EditClipboard.hasContent) return 0
        val mask = EditClipboard.pendingMask
        val originPhotoId = EditClipboard.sourcePhotoId
        var changed = 0
        for ((photoId, graph) in targets) {
            val pasted = EditClipboard.paste(graph, mask)
            if (pasted != graph) {
                onPasted(photoId, pasted, originPhotoId, mask)
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

    /** Shows the mask checklist and calls [onConfirm] with the chosen mask. */
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

    private const val DEFAULT_INCLUDED_OPTIONS = 6
}
