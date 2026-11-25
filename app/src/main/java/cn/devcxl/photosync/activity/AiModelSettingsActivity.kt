package cn.devcxl.photosync.activity

import java.io.File
import java.io.IOException

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
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
import cn.devcxl.photosync.editor.vision.AiModelFailureReason
import cn.devcxl.photosync.editor.vision.AiModelInstallException
import cn.devcxl.photosync.editor.vision.AiModelSpec
import cn.devcxl.photosync.editor.vision.isOnnxModelFileName

/**
 * Settings page that lists every on-demand AI model with its size, license and install state.
 *
 * Actions delegate to [AiModelDownloadManager]: download (with ordered mirror fallback), SAF import
 * of a local `.onnx` file, delete and re-download. Deleting a model only removes model files, never
 * the edit records stored in Room.
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
        row.modelNameText.setText(modelNameRes(spec))
        row.modelDescriptionText.setText(modelDescriptionRes(spec))
        row.modelMetaText.text = getString(
            R.string.ai_models_meta,
            formatBytes(if (spec.isPinned) spec.byteSize else 0L),
            spec.version
        )
        row.modelLicenseText.text = getString(R.string.ai_models_license, spec.license.name)
    }

    private fun refreshUsageAndStates() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val states = AiModelCatalog.onDemandModels.associate { spec ->
                    spec.id to ModelState(
                        installed = downloadManager.isInstalled(spec),
                        partialBytes = downloadManager.partFile(spec)
                            .takeIf { it.isFile }?.length() ?: 0L
                    )
                }
                UsageSnapshot(states, downloadManager.totalBytesOnDisk())
            }
            binding.aiModelsUsageText.text = getString(
                R.string.ai_models_total_usage,
                formatBytes(snapshot.totalBytes)
            )
            applyStates(snapshot)
        }
    }

    private fun applyStates(snapshot: UsageSnapshot) {
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
        if (busyModelId != null) return
        val row = rows[spec.id] ?: return
        busyModelId = spec.id
        row.modelProgressBar.isVisible = true
        row.modelProgressBar.progress = 0
        bindButtons(row, showDownload = false, showImport = false, showDelete = false)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (downloadManager.isInstalled(spec)) downloadManager.delete(spec)
                }
                var lastPercent = -1
                val result = downloadManager.download(spec) { ratio ->
                    val percent = (ratio * 100).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        runOnUiThread {
                            row.modelStatusText.text =
                                getString(R.string.ai_models_status_downloading, percent)
                            row.modelProgressBar.progress = percent
                        }
                    }
                }
                if (result.isSuccess) {
                    toast(R.string.ai_models_download_success)
                } else {
                    toast(
                        getString(
                            R.string.ai_models_download_failed,
                            failureMessage(result)
                        )
                    )
                }
            } catch (e: IOException) {
                Timber.e(e, "Model download failed before the manager could report it")
                toastStorageFailure(R.string.ai_models_download_failed)
            } catch (e: SecurityException) {
                Timber.e(e, "The platform denied access to the model directory")
                toastStorageFailure(R.string.ai_models_download_failed)
            } finally {
                busyModelId = null
                refreshUsageAndStates()
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
        val displayName = queryDisplayName(uri)
        if (!isOnnxModelFileName(displayName)) {
            toast(R.string.ai_models_import_wrong_type)
            return
        }
        if (busyModelId != null) return
        val row = rows[spec.id] ?: return
        busyModelId = spec.id
        row.modelStatusText.setText(R.string.ai_models_status_importing)
        row.modelProgressBar.isVisible = true
        row.modelProgressBar.progress = 0
        bindButtons(row, showDownload = false, showImport = false, showDelete = false)
        lifecycleScope.launch {
            try {
                var lastPercent = -1
                val result = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        downloadManager.importLocal(spec, stream) { ratio ->
                            val percent = (ratio * 100).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                runOnUiThread {
                                    row.modelProgressBar.progress = percent
                                }
                            }
                        }
                    }
                }
                if (result == null) {
                    toast(
                        getString(
                            R.string.ai_models_import_failed,
                            getString(R.string.ai_models_reason_storage_failure)
                        )
                    )
                    return@launch
                }
                if (result.isSuccess) {
                    toast(R.string.ai_models_import_success)
                } else {
                    toast(
                        getString(
                            R.string.ai_models_import_failed,
                            failureMessage(result)
                        )
                    )
                }
            } catch (e: IOException) {
                Timber.e(e, "The selected ONNX document could not be opened")
                toastStorageFailure(R.string.ai_models_import_failed)
            } catch (e: SecurityException) {
                Timber.e(e, "The platform denied reading the selected ONNX document")
                toastStorageFailure(R.string.ai_models_import_failed)
            } finally {
                busyModelId = null
                refreshUsageAndStates()
            }
        }
    }

    private fun confirmDelete(spec: AiModelSpec) {
        if (busyModelId != null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.ai_models_delete_title)
            .setMessage(
                getString(R.string.ai_models_delete_message, getString(modelNameRes(spec)))
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
            busyModelId = null
            toast(if (deleted) R.string.ai_models_deleted else R.string.ai_models_delete_failed)
            refreshUsageAndStates()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: SecurityException) {
        Timber.w(e, "Could not read the selected document name")
        null
    }

    private fun failureMessage(result: Result<*>): String {
        val reason = (result.exceptionOrNull() as? AiModelInstallException)?.reason
        val messageRes = when (reason) {
            AiModelFailureReason.NOT_AVAILABLE -> R.string.ai_models_reason_not_available
            AiModelFailureReason.DOWNLOAD_FAILED -> R.string.ai_models_reason_download_failed
            AiModelFailureReason.SIZE_MISMATCH -> R.string.ai_models_reason_size_mismatch
            AiModelFailureReason.CHECKSUM_MISMATCH -> R.string.ai_models_reason_checksum_mismatch
            AiModelFailureReason.STORAGE_FAILURE -> R.string.ai_models_reason_storage_failure
            null -> R.string.ai_models_reason_download_failed
        }
        return getString(messageRes)
    }

    private fun formatBytes(bytes: Long): String =
        if (bytes <= 0L) getString(R.string.ai_models_size_unknown)
        else Formatter.formatFileSize(this, bytes)

    private fun modelNameRes(spec: AiModelSpec): Int = when (spec.id) {
        AiModelCatalog.MODNET_PORTRAIT.id -> R.string.ai_models_model_modnet
        AiModelCatalog.MI_GAN_GENERATOR.id -> R.string.ai_models_model_migan
        else -> R.string.ai_models_model_unknown
    }

    private fun modelDescriptionRes(spec: AiModelSpec): Int = when (spec.id) {
        AiModelCatalog.MODNET_PORTRAIT.id -> R.string.ai_models_model_modnet_desc
        AiModelCatalog.MI_GAN_GENERATOR.id -> R.string.ai_models_model_migan_desc
        else -> R.string.ai_models_model_unknown
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

    private class ModelState(
        val installed: Boolean,
        val partialBytes: Long
    )

    private class UsageSnapshot(
        val models: Map<String, ModelState>,
        val totalBytes: Long
    )
}
