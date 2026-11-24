package cn.devcxl.photosync.editor.ui.panels

import java.io.IOException
import java.util.UUID

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
 * previous grade instead of a half-applied mixture of both. Tapping `无预设` clears the whole graph —
 * grade, portrait and frame geometry — back to the imported original.
 */
internal class PresetPanelBinder(
    private val context: Context,
    private val binding: ActivityEditorBinding,
    private val undoController: EditorUndoController,
    private val owner: LifecycleOwner,
    private val host: Host
) {

    /** Callbacks the activity implements when a preset changes the graph. */
    interface Host {
        /** A preset (or the `无预设` chip) changed the graph; render at full quality. */
        fun onPresetApplied(graph: EditGraph)

        /**
         * Clears the photo back to its original: grade, portrait and frame geometry.
         *
         * @return False when the photo is already untouched.
         */
        fun onPresetCleared(): Boolean

        /** The active `LiveTetherPreset` changed. */
        fun onLiveTetherPresetChanged(presetId: String?)
    }

    private val store: EditStore?
        get() = EditorHostRegistry.editStore

    private var cachedPresets: List<UserPresetRecord> = emptyList()

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

    /** Re-highlights whichever preset matches [graph], or `无预设` on a neutral graph. */
    fun refresh(graph: EditGraph) {
        val matched = PresetGraphMerger.matchingPresetId(
            graph,
            cachedPresets.map { it.id to it.graphSubsetJson }
        )
        panel.select(matched)
    }

    private fun observePresetLibrary() {
        val currentStore = store
        if (currentStore == null) {
            Timber.w("Preset library unavailable: the host never installed an EditStore")
            return
        }
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    currentStore.observePresets().collect { presets ->
                        cachedPresets = presets
                        panel.render(presets, currentStore.getLiveTetherPresetId())
                        refresh(undoController.currentGraph)
                    }
                }
                launch {
                    currentStore.observeLiveTetherPresetId().collect { liveId ->
                        panel.render(cachedPresets, liveId)
                        refresh(undoController.currentGraph)
                    }
                }
            }
        }
    }

    private fun clearPresetAxes() {
        if (!host.onPresetCleared()) {
            Toast.makeText(context, R.string.editor_preset_already_clear, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, R.string.editor_preset_cleared, Toast.LENGTH_SHORT).show()
    }

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
            id = "user_${UUID.randomUUID()}",
            name = name,
            graphSubsetJson = graphJson
        )
        owner.lifecycleScope.launch {
            try {
                currentStore.savePreset(preset)
                Toast.makeText(
                    context,
                    context.getString(R.string.editor_preset_saved, name),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (io: IOException) {
                Timber.e(io, "Failed to persist a user preset")
                Toast.makeText(context, R.string.editor_preset_save_failed, Toast.LENGTH_SHORT).show()
            } catch (error: RuntimeException) {
                Timber.e(error, "Database failure persisting a user preset")
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
    }

    private fun showRenamePrompt(preset: UserPresetRecord) {
        showNamePrompt(R.string.editor_preset_rename_title, preset.name) { name ->
            renamePreset(preset.id, name)
        }
    }

    private fun renamePreset(presetId: String, name: String) {
        val currentStore = store ?: return
        owner.lifecycleScope.launch {
            try {
                currentStore.renamePreset(presetId, name)
            } catch (io: IOException) {
                Timber.e(io, "Failed to rename a user preset")
            } catch (error: RuntimeException) {
                Timber.e(error, "Database failure renaming a user preset")
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
        owner.lifecycleScope.launch {
            try {
                currentStore.deletePreset(presetId)
            } catch (io: IOException) {
                Timber.e(io, "Failed to delete a user preset")
            } catch (error: RuntimeException) {
                Timber.e(error, "Database failure deleting a user preset")
            }
        }
    }

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
