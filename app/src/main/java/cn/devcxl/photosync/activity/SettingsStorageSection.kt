package cn.devcxl.photosync.activity

import android.net.Uri
import android.view.View
import android.widget.Toast

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import cn.devcxl.photosync.R
import cn.devcxl.photosync.data.StorageCategory
import cn.devcxl.photosync.data.StorageUsageReporter
import cn.devcxl.photosync.data.StorageUsageSnapshot
import cn.devcxl.photosync.databinding.ActivitySettingsBinding
import cn.devcxl.photosync.editor.domain.SkyCustomAsset
import cn.devcxl.photosync.editor.domain.SkyCustomAssetStore
import cn.devcxl.photosync.editor.lut.CubeLutInfo
import cn.devcxl.photosync.editor.lut.CubeLutStore
import cn.devcxl.photosync.editor.settings.EditorSettings

/**
 * "Storage & cache" group: live usage of the five folders, per-category cleanup and the real LUT /
 * sky libraries.
 *
 * Cleanup never touches the `Room` database; the confirmation copy states exactly which files and
 * which edit effects are affected before anything is deleted. Measurements and library listings run
 * on [ioDispatcher] so a multi-gigabyte model folder never blocks the settings page.
 */
internal class SettingsStorageSection(
    private val activity: AppCompatActivity,
    private val binding: ActivitySettingsBinding,
    private val onLutImportRequested: () -> Unit,
    private val onSkyImportRequested: () -> Unit,
    private val reporter: StorageUsageReporter = StorageUsageReporter(activity.filesDir, activity.cacheDir),
    private val lutStore: CubeLutStore = CubeLutStore(activity.filesDir),
    private val skyStore: SkyCustomAssetStore = SkyCustomAssetStore(activity.filesDir),
    private val editorSettings: EditorSettings = EditorSettings(activity),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = activity.lifecycleScope
) {

    private var activeLibraryDialog: AlertDialog? = null

    /** SAF import path; the section only maps its outcome to a toast and a refresh. */
    private val importer = SettingsAssetImporter(
        activity = activity,
        lutStore = lutStore,
        skyStore = skyStore,
        editorSettings = editorSettings,
        onFinished = ::onImportFinished
    )

    /** Wires the five clear buttons, the one-key clear and both library entry rows. */
    fun bind() {
        bindClearButton(binding.settingsStorageModelsClearButton, StorageCategory.MODELS)
        bindClearButton(binding.settingsStorageAiEditsClearButton, StorageCategory.AI_EDITS)
        bindClearButton(binding.settingsStoragePreviewCacheClearButton, StorageCategory.PREVIEW_CACHE)
        bindClearButton(binding.settingsStorageLutClearButton, StorageCategory.LUTS)
        bindClearButton(binding.settingsStorageSkyClearButton, StorageCategory.SKY)
        binding.settingsStorageClearAllButton.setOnClickListener { confirmClearAll() }
        binding.settingsLutLibraryRow.setOnClickListener { openLutLibrary() }
        binding.settingsSkyAssetsRow.setOnClickListener { openSkyLibrary() }
        refresh()
    }

    /** Re-measures every category and repaints the group. */
    fun refresh() {
        scope.launch {
            val measured = withContext(ioDispatcher) { reporter.snapshot() }
            render(measured)
        }
    }

    /** Imports the `.cube` document selected through SAF. */
    fun importLut(uri: Uri) {
        importer.importLut(uri)
    }

    /** Imports the sky image selected through SAF with the shared preview-cache quality. */
    fun importSky(uri: Uri) {
        importer.importSky(uri)
    }

    private fun onImportFinished(outcome: SettingsAssetImportOutcome) {
        when (outcome) {
            SettingsAssetImportOutcome.LUT_IMPORTED -> {
                toast(R.string.settings_lut_import_success)
                refresh()
                openLutLibrary()
            }
            SettingsAssetImportOutcome.LUT_WRONG_TYPE -> toast(R.string.settings_lut_import_wrong_type)
            SettingsAssetImportOutcome.LUT_FAILED -> toast(R.string.settings_lut_import_failed)
            SettingsAssetImportOutcome.SKY_IMPORTED -> {
                toast(R.string.settings_sky_import_success)
                refresh()
                openSkyLibrary()
            }
            SettingsAssetImportOutcome.SKY_FAILED -> toast(R.string.settings_sky_import_failed)
        }
    }

    private fun render(measured: StorageUsageSnapshot) {
        binding.settingsStorageModelsValue.text =
            StorageUsageReporter.formatBytes(measured.bytes(StorageCategory.MODELS))
        binding.settingsStorageAiEditsValue.text =
            StorageUsageReporter.formatBytes(measured.bytes(StorageCategory.AI_EDITS))
        binding.settingsStoragePreviewCacheValue.text =
            StorageUsageReporter.formatBytes(measured.bytes(StorageCategory.PREVIEW_CACHE))
        binding.settingsStorageLutValue.text = StorageUsageReporter.formatBytes(measured.bytes(StorageCategory.LUTS))
        binding.settingsStorageSkyValue.text = StorageUsageReporter.formatBytes(measured.bytes(StorageCategory.SKY))
        binding.settingsStorageTotalValue.text = StorageUsageReporter.formatBytes(measured.totalBytes)

        setClearEnabled(binding.settingsStorageModelsClearButton, measured.bytes(StorageCategory.MODELS))
        setClearEnabled(binding.settingsStorageAiEditsClearButton, measured.bytes(StorageCategory.AI_EDITS))
        setClearEnabled(
            binding.settingsStoragePreviewCacheClearButton,
            measured.bytes(StorageCategory.PREVIEW_CACHE)
        )
        setClearEnabled(binding.settingsStorageLutClearButton, measured.bytes(StorageCategory.LUTS))
        setClearEnabled(binding.settingsStorageSkyClearButton, measured.bytes(StorageCategory.SKY))
        binding.settingsStorageClearAllButton.isEnabled = measured.totalBytes > 0L
    }

    private fun setClearEnabled(view: View, bytes: Long) {
        view.isEnabled = bytes > 0L
        view.alpha = if (bytes > 0L) 1.0f else 0.45f
    }

    private fun bindClearButton(view: View, category: StorageCategory) {
        view.setOnClickListener { confirmClear(category) }
    }

    private fun confirmClear(category: StorageCategory) {
        val title = when (category) {
            StorageCategory.MODELS -> R.string.settings_storage_clear_models_title
            StorageCategory.AI_EDITS -> R.string.settings_storage_clear_ai_edits_title
            StorageCategory.PREVIEW_CACHE -> R.string.settings_storage_clear_preview_title
            StorageCategory.LUTS -> R.string.settings_storage_clear_lut_title
            StorageCategory.SKY -> R.string.settings_storage_clear_sky_title
        }
        val message = when (category) {
            StorageCategory.MODELS -> R.string.settings_storage_clear_models_message
            StorageCategory.AI_EDITS -> R.string.settings_storage_clear_ai_edits_message
            StorageCategory.PREVIEW_CACHE -> R.string.settings_storage_clear_preview_message
            StorageCategory.LUTS -> R.string.settings_storage_clear_lut_message
            StorageCategory.SKY -> R.string.settings_storage_clear_sky_message
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.settings_dialog_confirm) { _, _ -> clear(category) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.settings_storage_clear_all_title)
            .setMessage(R.string.settings_storage_clear_all_message)
            .setPositiveButton(R.string.settings_dialog_confirm) { _, _ -> clearAll() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun clear(category: StorageCategory) {
        scope.launch {
            val freed = withContext(ioDispatcher) { reporter.clear(category) }
            toast(activity.getString(R.string.settings_storage_cleared, StorageUsageReporter.formatBytes(freed)))
            refresh()
        }
    }

    private fun clearAll() {
        scope.launch {
            val freed = withContext(ioDispatcher) { reporter.clearAll() }
            toast(activity.getString(R.string.settings_storage_cleared, StorageUsageReporter.formatBytes(freed)))
            refresh()
        }
    }

    private fun openLutLibrary() {
        scope.launch {
            val luts = withContext(ioDispatcher) { lutStore.installedLuts() }
            showLutLibrary(luts)
        }
    }

    private fun showLutLibrary(luts: List<CubeLutInfo>) {
        val rows = luts.map { lut ->
            SettingsLibraryDialogView.Row(
                title = lut.displayName,
                subtitle = StorageUsageReporter.formatBytes(lut.byteSize)
            )
        }
        showLibraryDialog(
            titleRes = R.string.settings_lut_library_title,
            rows = rows,
            totalBytes = luts.sumOf { it.byteSize },
            onDeleteRow = { index ->
                confirmDelete(luts[index].displayName) { deleteLut(luts[index]) }
            },
            onImport = onLutImportRequested
        )
    }

    private fun openSkyLibrary() {
        scope.launch {
            val assets = withContext(ioDispatcher) { skyStore.list() }
            showSkyLibrary(assets)
        }
    }

    private fun showSkyLibrary(assets: List<SkyCustomAsset>) {
        val rows = assets.map { asset ->
            SettingsLibraryDialogView.Row(
                title = activity.getString(
                    R.string.settings_library_sky_row,
                    asset.width,
                    asset.height,
                    StorageUsageReporter.formatBytes(asset.byteSize)
                ),
                subtitle = asset.sha256.take(SHA_PREFIX_LENGTH)
            )
        }
        showLibraryDialog(
            titleRes = R.string.settings_sky_library_title,
            rows = rows,
            totalBytes = assets.sumOf { it.byteSize },
            onDeleteRow = { index ->
                val asset = assets[index]
                confirmDelete(asset.sha256.take(SHA_PREFIX_LENGTH)) { deleteSky(asset) }
            },
            onImport = onSkyImportRequested
        )
    }

    private fun deleteLut(lut: CubeLutInfo) {
        scope.launch {
            val deleted = withContext(ioDispatcher) { lutStore.delete(lut.id) }
            if (deleted) {
                toast(activity.getString(R.string.settings_library_deleted, lut.displayName))
                refresh()
                openLutLibrary()
            } else {
                toast(R.string.settings_library_delete_failed)
            }
        }
    }

    private fun deleteSky(asset: SkyCustomAsset) {
        scope.launch {
            val deleted = withContext(ioDispatcher) { skyStore.delete(asset.sha256) }
            if (deleted) {
                toast(activity.getString(R.string.settings_library_deleted, asset.sha256.take(SHA_PREFIX_LENGTH)))
                refresh()
                openSkyLibrary()
            } else {
                toast(R.string.settings_library_delete_failed)
            }
        }
    }

    private fun confirmDelete(label: String, onConfirmed: () -> Unit) {
        // Close the library first so the confirmation and the reopened library never stack.
        activeLibraryDialog?.dismiss()
        activeLibraryDialog = null
        SettingsLibraryDialogView.confirmDelete(activity, label, onConfirmed)
    }

    private fun showLibraryDialog(
        titleRes: Int,
        rows: List<SettingsLibraryDialogView.Row>,
        totalBytes: Long,
        onDeleteRow: (Int) -> Unit,
        onImport: () -> Unit
    ) {
        activeLibraryDialog?.dismiss()
        val dialog = SettingsLibraryDialogView.show(
            activity = activity,
            titleRes = titleRes,
            totalBytes = totalBytes,
            rows = rows,
            onDeleteRow = onDeleteRow,
            onImport = onImport
        )
        activeLibraryDialog = dialog
        dialog.setOnDismissListener {
            if (activeLibraryDialog === dialog) activeLibraryDialog = null
        }
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(activity, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** Prefix of a content-addressed sky digest shown as its readable label. */
        const val SHA_PREFIX_LENGTH = 12
    }
}
