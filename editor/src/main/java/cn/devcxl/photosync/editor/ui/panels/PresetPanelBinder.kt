package cn.devcxl.photosync.editor.ui.panels

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast

import androidx.appcompat.app.AlertDialog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.contract.EditStore
import cn.devcxl.photosync.editor.contract.EditorHostRegistry
import cn.devcxl.photosync.editor.contract.UserPresetRecord
import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.CopyMask
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditGraphJsonCodec
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.PresetGraphMerger
import cn.devcxl.photosync.editor.ui.widgets.PresetPanelView

/**
 * Binds the `预设` panel to the host `EditStore`: saves the current look, applies a preset onto the
 * live graph, renames/deletes user presets and toggles the `LiveTetherPreset`.
 *
 * Applying a preset is a real undo step under [EditParameterKey.PRESET], so one `撤销` returns to the
 * previous grade instead of a half-applied mixture of both.
 */
internal class PresetPanelBinder(
    private val context: Context,
    private val binding: ActivityEditorBinding,
    private val undoController: EditorUndoController,
    private val scope: CoroutineScope,
    private val host: Host
) {

    /** Callbacks the activity implements when a preset changes the graph. */
    interface Host {
        /** A preset (or the `无预设` chip) changed the graph; render at full quality. */
        fun onPresetApplied(graph: EditGraph)

        /** The active `LiveTetherPreset` changed. */
        fun onLiveTetherPresetChanged(presetId: String?)
    }

    private val store: EditStore?
        get() = EditorHostRegistry.editStore

    private val panel = PresetPanelView(
        context = context,
        binding = binding.presetPanel,
        onSaveCurrent = ::saveCurrentGraphAsPreset,
        onNoPreset = ::clearPresetAxes,
        onPresetSelected = ::applyPreset,
        onPresetLongPressed = ::showPresetMenu
    )

    init {
        observePresetLibrary()
    }

    /** Shows the panel only for the `预设` group. */
    fun setVisible(visible: Boolean) = panel.setVisible(visible)

    /** Re-highlights the active chip and marks the row neutral on an identity graph. */
    fun refresh(graph: EditGraph) {
        panel.select(if (PresetGraphMerger.hasNeutralPresetAxes(graph)) null else store?.getLiveTetherPresetId())
    }

    private fun observePresetLibrary() {
        val currentStore = store
        if (currentStore == null) {
            Timber.w("Preset library unavailable: the host never installed an EditStore")
            return
        }
        scope.launch {
            currentStore.observePresets().collect { presets ->
                panel.render(presets, currentStore.getLiveTetherPresetId())
            }
        }
    }

    private fun clearPresetAxes() {
        val graph = PresetGraphMerger.resetPresetAxes(undoController.currentGraph)
        commitPresetStep(graph)
        Toast.makeText(context, R.string.editor_preset_cleared, Toast.LENGTH_SHORT).show()
    }

    /**
     * Applies [preset] onto the live graph, preloading its LUT through the render host.
     *
     * A preset row whose JSON does not decode is reported and skipped: applying half a look would be
     * worse than not applying one.
     */
    private fun applyPreset(preset: UserPresetRecord) {
        val target = EditGraphJsonCodec.decode(preset.graphSubsetJson)
        if (target == null) {
            Timber.w("Preset %s carries corrupt JSON", preset.id)
            Toast.makeText(context, R.string.editor_preset_save_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val merged = PresetGraphMerger.applyPreset(target, undoController.currentGraph)
        commitPresetStep(merged)
        Toast.makeText(
            context,
            context.getString(R.string.editor_preset_applied, preset.name),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun commitPresetStep(graph: EditGraph) {
        undoController.onParameterDrag(graph, EditParameterKey.PRESET)
        undoController.onParameterRelease()
        host.onPresetApplied(graph)
    }

    private fun saveCurrentGraphAsPreset() {
        val graph = undoController.currentGraph
        if (graph.isIdentity()) {
            Toast.makeText(context, R.string.editor_preset_save_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // Persist only the look axes: a preset that carried this frame's crop would letterbox the next.
        val graphJson = EditGraphJsonCodec.encode(CopyMask().extract(graph))
        showNamePrompt(R.string.editor_preset_save_title, null) { name -> persistPreset(name, graphJson) }
    }

    private fun persistPreset(name: String, graphJson: String) {
        val currentStore = store ?: return
        val preset = UserPresetRecord(
            id = "user_${System.currentTimeMillis()}",
            name = name,
            graphSubsetJson = graphJson
        )
        scope.launch {
            try {
                currentStore.savePreset(preset)
                Toast.makeText(
                    context,
                    context.getString(R.string.editor_preset_saved, name),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (io: java.io.IOException) {
                Timber.e(io, "Failed to persist a user preset")
                Toast.makeText(context, R.string.editor_preset_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPresetMenu(preset: UserPresetRecord, anchor: View) {
        val menu = PopupMenu(context, anchor)
        val isLive = store?.getLiveTetherPresetId() == preset.id
        val toggleLabel = if (isLive) {
            R.string.editor_preset_menu_clear_live
        } else {
            R.string.editor_preset_menu_set_live
        }
        menu.menu.add(0, MENU_TOGGLE_LIVE, 0, toggleLabel)
        if (!preset.isBuiltin) {
            menu.menu.add(0, MENU_RENAME, 1, R.string.editor_preset_menu_rename)
            menu.menu.add(0, MENU_DELETE, 2, R.string.editor_preset_menu_delete)
        }
        menu.setOnMenuItemClickListener { item -> onPresetMenuItem(item.itemId, preset, isLive) }
        menu.show()
    }

    private fun onPresetMenuItem(itemId: Int, preset: UserPresetRecord, isLive: Boolean): Boolean {
        when (itemId) {
            MENU_TOGGLE_LIVE -> toggleLivePreset(preset.id, isLive)
            MENU_RENAME -> showRenamePrompt(preset)
            MENU_DELETE -> confirmDelete(preset)
            else -> Unit
        }
        return true
    }

    private fun toggleLivePreset(presetId: String, isLive: Boolean) {
        val currentStore = store ?: return
        val next = if (isLive) null else presetId
        currentStore.setLiveTetherPresetId(next)
        host.onLiveTetherPresetChanged(next)
        val messageRes = if (next == null) {
            R.string.editor_preset_live_off
        } else {
            R.string.editor_preset_live_on
        }
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
        scope.launch {
            panel.render(currentStore.listPresets(), next)
        }
    }

    private fun showRenamePrompt(preset: UserPresetRecord) {
        showNamePrompt(R.string.editor_preset_rename_title, preset.name) { name ->
            renamePreset(preset.id, name)
        }
    }

    private fun renamePreset(presetId: String, name: String) {
        val currentStore = store
        if (currentStore == null) return
        scope.launch {
            try {
                currentStore.renamePreset(presetId, name)
            } catch (io: java.io.IOException) {
                Timber.e(io, "Failed to rename a user preset")
            }
        }
    }

    private fun confirmDelete(preset: UserPresetRecord) {
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.editor_preset_delete_title, preset.name))
            .setPositiveButton(R.string.editor_preset_delete_confirm) { _, _ -> deletePreset(preset.id) }
            .setNegativeButton(R.string.editor_hint_cancel, null)
            .show()
    }

    private fun deletePreset(presetId: String) {
        val currentStore = store ?: return
        scope.launch {
            try {
                currentStore.deletePreset(presetId)
            } catch (io: java.io.IOException) {
                Timber.e(io, "Failed to delete a user preset")
            }
        }
    }

    /**
     * Shows a single-line name prompt prefilled with [initial] and calls [onName] with the trimmed,
     * non-blank result. A blank name simply closes the dialog.
     */
    private fun showNamePrompt(titleRes: Int, initial: String?, onName: (String) -> Unit) {
        val input = EditText(context).apply {
            setSingleLine(true)
            setText(initial ?: "")
            setHint(R.string.editor_preset_save_hint)
        }
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setView(input)
            .setPositiveButton(R.string.editor_hint_confirm) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) onName(name)
            }
            .setNegativeButton(R.string.editor_hint_cancel, null)
            .show()
    }

    private companion object {
        const val MENU_TOGGLE_LIVE = 1
        const val MENU_RENAME = 2
        const val MENU_DELETE = 3
    }
}
