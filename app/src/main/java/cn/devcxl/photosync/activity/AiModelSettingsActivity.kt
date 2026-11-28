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
import cn.devcxl.photosync.editor.vision.AiModelDownloadCancellation
import cn.devcxl.photosync.editor.vision.AiModelDownloadManager
import cn.devcxl.photosync.editor.vision.AiModelFailureReason
import cn.devcxl.photosync.editor.vision.AiModelFailureUiCopy
import cn.devcxl.photosync.editor.vision.AiModelInstallException
import cn.devcxl.photosync.editor.vision.AiModelInstallPhase
import cn.devcxl.photosync.editor.vision.AiModelInstallUi
import cn.devcxl.photosync.editor.vision.AiModelInstallUiState
import cn.devcxl.photosync.editor.vision.AiModelSpec
import cn.devcxl.photosync.editor.vision.AiModelUiAction
import cn.devcxl.photosync.editor.vision.isOnnxModelFileName

/**
 * Settings page that lists every on-demand AI model with its size, license and install state.
 *
 * Every row is rendered from the shared [AiModelInstallUi] state machine: the phase and the
 * available actions are the same ones the editor's first-install prompt uses, and the failure
 * sentence comes from [AiModelFailureUiCopy]. A download can be cancelled without losing the
 * `.part`, and "download again" only starts after a confirmation that promises the installed file
 * is kept until the replacement passes verification.
 */
class AiModelSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiModelSettingsBinding

    private val downloadManager by lazy {
        AiModelDownloadManager(File(filesDir, AiModelCatalog.MODELS_DIR_NAME))
    }
    private val rows = linkedMapOf<String, ItemAiModelBinding>()
    private var pendingImportSpec: AiModelSpec? = null
    private var busyModelId: String? = null
    private var busyState: AiModelInstallUiState? = null
    private var activeCancellation: AiModelDownloadCancellation? = null

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
            row.modelDownloadButton.setOnClickListener { requestDownload(spec) }
            row.modelImportButton.setOnClickListener { pickLocalModel(spec) }
            row.modelDeleteButton.setOnClickListener { confirmDelete(spec) }
            row.modelCancelButton.setOnClickListener { cancelActiveDownload() }
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
            val state = busyState?.takeIf { it.spec.id == spec.id }
                ?: snapshot.models[spec.id]?.let { disk ->
                    AiModelInstallUi.idle(spec, disk.installed, disk.partialBytes, disk.lastProbe)
                }
                ?: continue
            renderState(spec, row, state)
        }
    }

    private fun renderState(spec: AiModelSpec, row: ItemAiModelBinding, state: AiModelInstallUiState) {
        row.modelProgressBar.isVisible = state.isBusy
        row.modelProgressBar.progress = state.percent
        row.modelCancelButton.isVisible = state.canCancel
        row.modelCancelButton.isEnabled = state.canCancel

        when (state.phase) {
            AiModelInstallPhase.NOT_INSTALLED -> row.modelStatusText.setText(
                R.string.ai_models_status_not_installed
            )
            AiModelInstallPhase.PARTIAL -> row.modelStatusText.text = getString(
                R.string.ai_models_status_partial,
                state.percent
            )
            AiModelInstallPhase.INSTALLED -> row.modelStatusText.setText(
                R.string.ai_models_status_installed
            )
            AiModelInstallPhase.SOURCE_GONE -> row.modelStatusText.text =
                getString(AiModelFailureUiCopy.of(AiModelFailureReason.SOURCE_GONE).messageRes)
            AiModelInstallPhase.FAILED -> row.modelStatusText.text = getString(
                R.string.ai_models_status_failed,
                getString(AiModelFailureUiCopy.of(state.failureReason).messageRes)
            )
            AiModelInstallPhase.DOWNLOADING -> row.modelStatusText.text = getString(
                R.string.ai_models_status_downloading_bytes,
                AiModelSettingsText.formatBytes(this, state.downloadedBytes),
                AiModelSettingsText.formatBytes(this, state.totalBytes),
                state.percent
            )
            AiModelInstallPhase.IMPORTING -> row.modelStatusText.setText(
                R.string.ai_models_status_importing
            )
        }

        val downloadLabel = downloadLabelRes(state)
        row.modelDownloadButton.isVisible = downloadLabel != null
        row.modelDownloadButton.isEnabled = downloadLabel != null
        if (downloadLabel != null) row.modelDownloadButton.setText(downloadLabel)

        val showImport = state.offers(AiModelUiAction.IMPORT_LOCAL)
        row.modelImportButton.isVisible = showImport
        row.modelImportButton.isEnabled = showImport && !state.isBusy
        row.modelImportButton.setText(
            if (state.importIsPrimary) R.string.ai_models_import_primary
            else R.string.ai_models_import
        )

        val showDelete = state.offers(AiModelUiAction.DELETE)
        row.modelDeleteButton.isVisible = showDelete
        row.modelDeleteButton.isEnabled = showDelete && !state.isBusy
    }

    /** Download button label for [state], or `null` when downloading is not an offered action. */
    private fun downloadLabelRes(state: AiModelInstallUiState): Int? {
        if (!state.offersDownload || state.isBusy) return null
        return when (state.phase) {
            AiModelInstallPhase.INSTALLED -> R.string.ai_models_redownload
            AiModelInstallPhase.PARTIAL -> R.string.ai_models_continue_download
            AiModelInstallPhase.SOURCE_GONE -> R.string.ai_models_download_may_fail
            AiModelInstallPhase.FAILED -> when (state.failureReason) {
                AiModelFailureReason.SIZE_MISMATCH,
                AiModelFailureReason.CHECKSUM_MISMATCH -> R.string.ai_models_redownload
                AiModelFailureReason.STORAGE_FAILURE -> R.string.ai_models_download
                else -> R.string.ai_models_retry_download
            }
            else -> R.string.ai_models_download
        }
    }

    private fun requestDownload(spec: AiModelSpec) {
        if (busyModelId != null) return
        if (AiModelInstallUi.requiresRedownloadConfirmation(downloadManager.isInstalled(spec))) {
            confirmRedownload(spec)
        } else {
            startDownload(spec, forceRedownload = false)
        }
    }

    /**
     * Asks before replacing an installed model. Nothing is deleted here or in the manager before the
     * replacement passed both the byte-count and SHA-256 checks.
     */
    private fun confirmRedownload(spec: AiModelSpec) {
        if (busyModelId != null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.ai_models_redownload_title)
            .setMessage(
                getString(
                    R.string.ai_models_redownload_message,
                    getString(AiModelSettingsText.nameRes(spec))
                )
            )
            .setPositiveButton(R.string.ai_models_redownload_confirm) { _, _ ->
                startDownload(spec, forceRedownload = true)
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun startDownload(spec: AiModelSpec, forceRedownload: Boolean) {
        val row = beginBusy(spec) ?: return
        val cancellation = AiModelDownloadCancellation()
        activeCancellation = cancellation
        val initialBytes = downloadManager.partFile(spec).takeIf { it.isFile }?.length() ?: 0L
        busyState = AiModelInstallUi.downloading(spec, initialBytes)
        renderState(spec, row, checkNotNull(busyState))
        lifecycleScope.launch {
            try {
                val result = downloadManager.download(
                    spec = spec,
                    onProgress = { ratio ->
                        val bytes = (ratio * spec.byteSize).toLong()
                        val state = AiModelInstallUi.downloading(spec, bytes)
                        busyState = state
                        runOnUiThread { renderState(spec, row, state) }
                    },
                    forceRedownload = forceRedownload,
                    cancellation = cancellation
                )
                reportDownloadResult(spec, result, cancellation)
            } catch (e: IOException) {
                Timber.e(e, "Model download failed before the manager could report it")
                toastFailure(AiModelFailureReason.STORAGE_FAILURE)
            } catch (e: SecurityException) {
                Timber.e(e, "The platform denied access to the model directory")
                toastFailure(AiModelFailureReason.STORAGE_FAILURE)
            } finally {
                finishBusy()
            }
        }
    }

    private fun cancelActiveDownload() {
        val cancellation = activeCancellation ?: return
        cancellation.cancel()
        val row = busyModelId?.let(rows::get) ?: return
        row.modelStatusText.setText(R.string.ai_models_status_cancelling)
        row.modelCancelButton.isEnabled = false
    }

    private fun reportDownloadResult(
        spec: AiModelSpec,
        result: Result<*>,
        cancellation: AiModelDownloadCancellation
    ) {
        when {
            result.isSuccess -> toast(R.string.ai_models_download_success)
            cancellation.isCancelled -> toast(getString(AiModelFailureUiCopy.cancelled().messageRes))
            else -> toast(getString(R.string.ai_models_download_failed, failureMessage(result)))
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
        busyState = AiModelInstallUi.importing(spec)
        renderState(spec, row, checkNotNull(busyState))
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        downloadManager.importLocalAny(
                            candidates = AiModelCatalog.importCandidates(spec),
                            input = stream,
                            onProgress = { ratio ->
                                val state = AiModelInstallUi.importing(
                                    spec,
                                    (ratio * spec.byteSize).toLong()
                                )
                                busyState = state
                                runOnUiThread { renderState(spec, row, state) }
                            }
                        )
                    }
                }
                if (result == null) {
                    toastFailure(AiModelFailureReason.STORAGE_FAILURE)
                } else if (result.isSuccess) {
                    toast(R.string.ai_models_import_success)
                } else {
                    toast(getString(R.string.ai_models_import_failed, failureMessage(result)))
                }
            } catch (e: IOException) {
                Timber.e(e, "The selected ONNX document could not be opened")
                toastFailure(AiModelFailureReason.STORAGE_FAILURE)
            } catch (e: SecurityException) {
                Timber.e(e, "The platform denied reading the selected ONNX document")
                toastFailure(AiModelFailureReason.STORAGE_FAILURE)
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
        bindStaticContent(spec, row)
        return row
    }

    private fun finishBusy() {
        busyModelId = null
        busyState = null
        activeCancellation = null
        refreshUsageAndStates()
    }

    private fun failureMessage(result: Result<*>): String =
        (result.exceptionOrNull() as? AiModelInstallException)?.reason
            .let { getString(AiModelFailureUiCopy.of(it).messageRes) }

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

    private fun toastFailure(reason: AiModelFailureReason) {
        toast(getString(R.string.ai_models_download_failed, getString(AiModelFailureUiCopy.of(reason).messageRes)))
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** Busy marker while the clear-all sweep runs; never matches a catalog id. */
        const val CLEAR_ALL_BUSY_ID = "__clear_all__"
    }
}
