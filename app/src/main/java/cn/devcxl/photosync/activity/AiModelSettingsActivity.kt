package cn.devcxl.photosync.activity

import java.io.File
import java.io.IOException

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.R
import cn.devcxl.photosync.databinding.ActivityAiModelSettingsBinding
import cn.devcxl.photosync.databinding.ItemAiModelBinding
import cn.devcxl.photosync.editor.vision.AiModelCatalog
import cn.devcxl.photosync.editor.vision.AiModelDownloadManager
import cn.devcxl.photosync.editor.vision.AiModelSpec
import cn.devcxl.photosync.editor.vision.isOnnxModelFileName

/**
 * Settings page that lists every on-demand AI model with its size, license and install state.
 *
 * Actions delegate to [AiModelDownloadManager]: download (with ordered mirror fallback), SAF import
 * of a local `.onnx` file, delete and re-download, plus the spec-mandated clear-all sweep. Deleting a
 * model only removes model files, never the edit records stored in Room.
 */
class AiModelSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiModelSettingsBinding

    private val downloadManager by lazy {
        AiModelDownloadManager(File(filesDir, AiModelCatalog.MODELS_DIR_NAME))
    }
    private val rows = linkedMapOf<String, ItemAiModelBinding>()
    private var pendingImportSpec: AiModelSpec? = null
    private var busyModelId: String? = null

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importFrom(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiModelSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.aiModelsBackButton.setOnClickListener { finish() }
        binding.aiModelsClearAllButton.setOnClickListener { confirmClearAll() }
        binding.aiModelsCloudEntryCard.setOnClickListener {
            startActivity(Intent(this, CloudSettingsActivity::class.java))
        }
        renderModelRows()
        refreshUsageAndStates()
    }

    private fun renderModelRows() {
        binding.aiModelsList.removeAllViews()
        rows.clear()
        for (spec in AiModelCatalog.onDemandModels) {
            val row = ItemAiModelBinding.inflate(layoutInflater, binding.aiModelsList, true)
            rows[spec.id] = row
            bindStaticContent(spec, row)
            row.modelDownloadButton.setOnClickListener { startDownload(spec) }
            row.modelImportButton.setOnClickListener { pickLocalModel(spec) }
            row.modelDeleteButton.setOnClickListener { confirmDelete(spec) }
        }
    }

    private fun bindStaticContent(spec: AiModelSpec, row: ItemAiModelBinding) {
        row.modelNameText.setText(AiModelSettingsText.nameRes(spec))
        row.modelDescriptionText.setText(AiModelSettingsText.descriptionRes(spec))
        row.modelMetaText.text = getString(
            R.string.ai_models_meta,
            AiModelSettingsText.formatBytes(this, if (spec.isPinned) spec.byteSize else 0L),
            spec.version
        )
        row.modelLicenseText.text = getString(R.string.ai_models_license, spec.license.name)
    }

    private fun refreshUsageAndStates() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                AiModelUsageSnapshot.load(downloadManager)
            }
            binding.aiModelsUsageText.text = getString(
                R.string.ai_models_total_usage,
                AiModelSettingsText.formatBytes(this@AiModelSettingsActivity, snapshot.totalBytes)
            )
            binding.aiModelsClearAllButton.isEnabled =
                snapshot.totalBytes > 0L && busyModelId == null
            applyStates(snapshot)
        }
    }

    private fun applyStates(snapshot: AiModelUsageSnapshot) {
        for (spec in AiModelCatalog.onDemandModels) {
            val row = rows[spec.id] ?: continue
            val state = snapshot.models[spec.id] ?: continue
            row.modelProgressBar.isVisible = false
            if (!spec.isPinned) {
                row.modelStatusText.setText(R.string.ai_models_status_pending)
                bindButtons(row, showDownload = false, showImport = false, showDelete = false)
                continue
            }
            when {
                state.installed -> {
                    row.modelStatusText.setText(R.string.ai_models_status_installed)
                    row.modelDownloadButton.setText(R.string.ai_models_redownload)
                    bindButtons(row, showDownload = true, showImport = false, showDelete = true)
                }
                state.partialBytes > 0L -> {
                    val percent = ((state.partialBytes * 100L) / spec.byteSize).toInt()
                    row.modelStatusText.text = getString(R.string.ai_models_status_partial, percent)
                    row.modelDownloadButton.setText(R.string.ai_models_continue_download)
                    bindButtons(row, showDownload = true, showImport = true, showDelete = true)
                }
                else -> {
                    row.modelStatusText.setText(R.string.ai_models_status_not_installed)
                    row.modelDownloadButton.setText(R.string.ai_models_download)
                    bindButtons(row, showDownload = true, showImport = true, showDelete = false)
                }
            }
            if (busyModelId == spec.id) {
                bindButtons(row, showDownload = false, showImport = false, showDelete = false)
            }
        }
    }

    private fun bindButtons(
        row: ItemAiModelBinding,
        showDownload: Boolean,
        showImport: Boolean,
        showDelete: Boolean
    ) {
        row.modelDownloadButton.isVisible = showDownload
        row.modelImportButton.isVisible = showImport
        row.modelDeleteButton.isVisible = showDelete
        row.modelDownloadButton.isEnabled = showDownload
        row.modelImportButton.isEnabled = showImport
        row.modelDeleteButton.isEnabled = showDelete
    }

    private fun startDownload(spec: AiModelSpec) {
        val row = beginBusy(spec) ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (downloadManager.isInstalled(spec)) downloadManager.delete(spec)
                }
                val progress = progressReporter(row, R.string.ai_models_status_downloading)
                val result = downloadManager.download(spec, progress)
                reportResult(result, R.string.ai_models_download_success, R.string.ai_models_download_failed)
            } catch (e: IOException) {
                Timber.e(e, "Model download failed before the manager could report it")
                toastStorageFailure(R.string.ai_models_download_failed)
            } catch (e: SecurityException) {
                Timber.e(e, "The platform denied access to the model directory")
                toastStorageFailure(R.string.ai_models_download_failed)
            } finally {
                finishBusy()
            }
        }
    }

    private fun pickLocalModel(spec: AiModelSpec) {
        if (busyModelId != null) return
        pendingImportSpec = spec
        importLauncher.launch(arrayOf("*/*"))
    }

    private fun importFrom(uri: Uri) {
        val spec = pendingImportSpec ?: return
        if (!isOnnxModelFileName(queryDisplayName(uri))) {
            toast(R.string.ai_models_import_wrong_type)
            return
        }
        val row = beginBusy(spec) ?: return
        row.modelStatusText.setText(R.string.ai_models_status_importing)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        downloadManager.importLocal(spec, stream, progressReporter(row))
                    }
                }
                if (result == null) {
                    toastStorageFailure(R.string.ai_models_import_failed)
                } else {
                    reportResult(result, R.string.ai_models_import_success, R.string.ai_models_import_failed)
                }
            } catch (e: IOException) {
                Timber.e(e, "The selected ONNX document could not be opened")
                toastStorageFailure(R.string.ai_models_import_failed)
            } catch (e: SecurityException) {
                Timber.e(e, "The platform denied reading the selected ONNX document")
                toastStorageFailure(R.string.ai_models_import_failed)
            } finally {
                finishBusy()
            }
        }
    }

    /** Marks [spec] busy and returns its row, or `null` when another action already owns the page. */
    private fun beginBusy(spec: AiModelSpec): ItemAiModelBinding? {
        if (busyModelId != null) return null
        val row = rows[spec.id] ?: return null
        busyModelId = spec.id
        row.modelProgressBar.isVisible = true
        row.modelProgressBar.progress = 0
        bindButtons(row, showDownload = false, showImport = false, showDelete = false)
        return row
    }

    private fun finishBusy() {
        busyModelId = null
        refreshUsageAndStates()
    }

    /**
     * Coalesces progress callbacks to whole percentages and posts them to the UI thread; when
     * [statusRes] is set the status line also shows the percentage.
     */
    private fun progressReporter(
        row: ItemAiModelBinding,
        statusRes: Int? = null
    ): (Float) -> Unit {
        var lastPercent = -1
        return { ratio ->
            val percent = (ratio * 100).toInt().coerceIn(0, 100)
            if (percent != lastPercent) {
                lastPercent = percent
                runOnUiThread {
                    statusRes?.let { row.modelStatusText.text = getString(it, percent) }
                    row.modelProgressBar.progress = percent
                }
            }
        }
    }

    private fun reportResult(result: Result<*>, successRes: Int, failureRes: Int) {
        if (result.isSuccess) {
            toast(successRes)
        } else {
            toast(getString(failureRes, AiModelSettingsText.failureMessage(this, result)))
        }
    }

    private fun confirmDelete(spec: AiModelSpec) {
        if (busyModelId != null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.ai_models_delete_title)
            .setMessage(
                getString(
                    R.string.ai_models_delete_message,
                    getString(AiModelSettingsText.nameRes(spec))
                )
            )
            .setPositiveButton(R.string.ai_models_delete_confirm) { _, _ -> deleteModel(spec) }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun deleteModel(spec: AiModelSpec) {
        if (busyModelId != null) return
        busyModelId = spec.id
        lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) { downloadManager.delete(spec) }
            finishBusy()
            toast(if (deleted) R.string.ai_models_deleted else R.string.ai_models_delete_failed)
        }
    }

    private fun confirmClearAll() {
        if (busyModelId != null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.ai_models_clear_all_title)
            .setMessage(R.string.ai_models_clear_all_message)
            .setPositiveButton(R.string.ai_models_clear_all_confirm) { _, _ -> clearAllModels() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun clearAllModels() {
        if (busyModelId != null) return
        busyModelId = CLEAR_ALL_BUSY_ID
        binding.aiModelsClearAllButton.isEnabled = false
        lifecycleScope.launch {
            val allDeleted = withContext(Dispatchers.IO) {
                AiModelCatalog.onDemandModels.map { downloadManager.delete(it) }.all { it }
            }
            finishBusy()
            toast(
                if (allDeleted) R.string.ai_models_clear_all_done
                else R.string.ai_models_clear_all_failed
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: SecurityException) {
        Timber.w(e, "Could not read the selected document name")
        null
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun toastStorageFailure(prefixRes: Int) {
        toast(getString(prefixRes, getString(R.string.ai_models_reason_storage_failure)))
    }

    private companion object {
        /** Busy marker while the clear-all sweep runs; never matches a catalog id. */
        const val CLEAR_ALL_BUSY_ID = "__clear_all__"
    }
}
