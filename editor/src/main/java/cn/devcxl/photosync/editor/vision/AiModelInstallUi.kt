package cn.devcxl.photosync.editor.vision

import cn.devcxl.photosync.editor.R

/**
 * One install phase shared by the AI settings page and the editor's first-install prompt.
 *
 * Both surfaces reduce their own disk/probe facts to this enum, so a "source offline" row cannot
 * look like "network error" in one place and "source gone" in another.
 */
enum class AiModelInstallPhase {

    /** No file, no partial download and no failed probe yet. */
    NOT_INSTALLED,

    /** A resumable `.part` exists. */
    PARTIAL,

    /** The verified final file is on disk. */
    INSTALLED,

    /** A transfer is running. */
    DOWNLOADING,

    /** A SAF import is being copied and verified. */
    IMPORTING,

    /** Nothing is installed and the last real probe found every mirror gone (404/410). */
    SOURCE_GONE,

    /** Nothing is installed and the last real probe failed for another reason. */
    FAILED
}

/** A user-facing next step bound to an [AiModelInstallPhase] or [AiModelFailureReason]. */
enum class AiModelUiAction {

    /** Start a first download, or a normal retry when no verified file exists. */
    DOWNLOAD,

    /** Resume from the kept `.part`. */
    RESUME_DOWNLOAD,

    /** Download again after an explicit confirmation; the installed file stays until verified. */
    REDOWNLOAD,

    /** Pick a local `.onnx` file through the system document picker. */
    IMPORT_LOCAL,

    /** Retry with the catalog's ordered mirrors (the downloader already falls through). */
    SWITCH_MIRROR,

    /** Free storage, then retry. */
    CLEAR_STORAGE,

    /** Ask the running transfer to stop, keeping the `.part`. */
    CANCEL,

    /** Delete the model directory, including a partial download. */
    DELETE
}

/**
 * Localized failure copy plus the actions that can follow it.
 *
 * @property messageRes String resource of the one-line reason, shared by both UI surfaces.
 * @property actions Ordered next steps; the first actionable one is the primary button.
 */
data class AiModelFailureUi(
    val messageRes: Int,
    val actions: List<AiModelUiAction>
) {

    /** True when [action] is offered for this failure. */
    fun offers(action: AiModelUiAction): Boolean = action in actions
}

/**
 * The single reason → copy/action mapping used by the settings page and the editor prompt.
 *
 * Keeping it in `:editor` means the downloader, dialog and settings page all speak the same wording;
 * no view is allowed to invent a second sentence for the same [AiModelFailureReason].
 */
object AiModelFailureUiCopy {

    /** Copy and actions for [reason]; `null` means "no reason recorded yet". */
    fun of(reason: AiModelFailureReason?): AiModelFailureUi = when (reason) {
        AiModelFailureReason.NOT_AVAILABLE -> AiModelFailureUi(
            messageRes = R.string.editor_ai_model_reason_not_available,
            actions = emptyList()
        )
        AiModelFailureReason.SOURCE_GONE -> AiModelFailureUi(
            messageRes = R.string.editor_ai_model_reason_source_gone,
            actions = listOf(
                AiModelUiAction.IMPORT_LOCAL,
                AiModelUiAction.SWITCH_MIRROR,
                AiModelUiAction.DOWNLOAD
            )
        )
        AiModelFailureReason.SOURCE_DENIED -> AiModelFailureUi(
            messageRes = R.string.editor_ai_model_reason_source_denied,
            actions = listOf(
                AiModelUiAction.IMPORT_LOCAL,
                AiModelUiAction.SWITCH_MIRROR,
                AiModelUiAction.DOWNLOAD
            )
        )
        AiModelFailureReason.TRANSPORT -> AiModelFailureUi(
            messageRes = R.string.editor_ai_model_reason_transport,
            actions = listOf(AiModelUiAction.RESUME_DOWNLOAD)
        )
        AiModelFailureReason.SIZE_MISMATCH -> AiModelFailureUi(
            messageRes = R.string.editor_ai_model_reason_size_mismatch,
            actions = listOf(AiModelUiAction.REDOWNLOAD)
        )
        AiModelFailureReason.CHECKSUM_MISMATCH -> AiModelFailureUi(
            messageRes = R.string.editor_ai_model_reason_checksum_mismatch,
            actions = listOf(AiModelUiAction.REDOWNLOAD, AiModelUiAction.IMPORT_LOCAL)
        )
        AiModelFailureReason.INCOMPATIBLE -> AiModelFailureUi(
            messageRes = R.string.editor_ai_model_reason_incompatible,
            actions = listOf(AiModelUiAction.IMPORT_LOCAL)
        )
        AiModelFailureReason.STORAGE_FAILURE -> AiModelFailureUi(
            messageRes = R.string.editor_ai_model_reason_storage_failure,
            actions = listOf(AiModelUiAction.CLEAR_STORAGE, AiModelUiAction.DOWNLOAD)
        )
        null -> AiModelFailureUi(
            messageRes = R.string.editor_ai_model_reason_transport,
            actions = listOf(AiModelUiAction.RESUME_DOWNLOAD)
        )
    }

    /** Copy for a user-requested cancel; distinct from an accidental transport interruption. */
    fun cancelled(): AiModelFailureUi = AiModelFailureUi(
        messageRes = R.string.editor_ai_model_cancelled,
        actions = listOf(AiModelUiAction.RESUME_DOWNLOAD)
    )
}

/**
 * The install state rendered by both the settings row and the editor prompt.
 *
 * @property phase Coarse state both surfaces can render with the same rules.
 * @property spec Catalog entry this state belongs to.
 * @property downloadedBytes Bytes already on disk; for [AiModelInstallPhase.DOWNLOADING] it is the
 *   bytes written so far, for [AiModelInstallPhase.PARTIAL] the size of the kept `.part`.
 * @property failureReason Last failure that produced [AiModelInstallPhase.FAILED] or
 *   [AiModelInstallPhase.SOURCE_GONE].
 */
data class AiModelInstallUiState(
    val phase: AiModelInstallPhase,
    val spec: AiModelSpec,
    val downloadedBytes: Long = 0L,
    val failureReason: AiModelFailureReason? = null
) {

    /** Pinned total size; `0` for an unpinned entry. */
    val totalBytes: Long
        get() = spec.byteSize

    /** Whole-percent progress; clamped to `0..100`. */
    val percent: Int
        get() = if (totalBytes <= 0L) 0 else ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)

    /** True while a transfer or import owns the row. */
    val isBusy: Boolean
        get() = phase == AiModelInstallPhase.DOWNLOADING || phase == AiModelInstallPhase.IMPORTING

    /** True while a cancel button makes sense. */
    val canCancel: Boolean
        get() = phase == AiModelInstallPhase.DOWNLOADING

    /** True when the download button must carry the "may be unavailable" hint. */
    val downloadMayFail: Boolean
        get() = phase == AiModelInstallPhase.SOURCE_GONE

    /** True when the primary action is a local import rather than a download. */
    val importIsPrimary: Boolean
        get() = phase == AiModelInstallPhase.SOURCE_GONE || failureReason == AiModelFailureReason.SOURCE_GONE

    /** Actions ordered for the current phase. */
    val actions: List<AiModelUiAction>
        get() = when (phase) {
            AiModelInstallPhase.NOT_INSTALLED -> listOf(
                AiModelUiAction.DOWNLOAD,
                AiModelUiAction.IMPORT_LOCAL
            )
            AiModelInstallPhase.PARTIAL -> listOf(
                AiModelUiAction.RESUME_DOWNLOAD,
                AiModelUiAction.IMPORT_LOCAL,
                AiModelUiAction.DELETE
            )
            AiModelInstallPhase.INSTALLED -> listOf(
                AiModelUiAction.REDOWNLOAD,
                AiModelUiAction.DELETE
            )
            AiModelInstallPhase.DOWNLOADING, AiModelInstallPhase.IMPORTING -> listOf(
                AiModelUiAction.CANCEL
            )
            AiModelInstallPhase.SOURCE_GONE -> listOf(
                AiModelUiAction.IMPORT_LOCAL,
                AiModelUiAction.SWITCH_MIRROR,
                AiModelUiAction.DOWNLOAD
            )
            AiModelInstallPhase.FAILED -> AiModelFailureUiCopy.of(failureReason).actions
        }

    /** True when [action] is available in this state. */
    fun offers(action: AiModelUiAction): Boolean = action in actions

    /** True when any download-flavoured action (new, resumed or replacement) is offered. */
    val offersDownload: Boolean
        get() = offers(AiModelUiAction.DOWNLOAD) ||
            offers(AiModelUiAction.RESUME_DOWNLOAD) ||
            offers(AiModelUiAction.REDOWNLOAD)
}

/**
 * Reducer that turns disk facts and the last real probe into a shared [AiModelInstallUiState].
 *
 * No probing happens here: the caller passes the persisted result of an explicit attempt, which is
 * what keeps the "no background polling" rule intact.
 */
object AiModelInstallUi {

    /**
     * True when a download must ask the user before starting.
     *
     * An installed model is replaced only after the user confirms; the old file stays until the
     * replacement passed both the byte-count and SHA-256 checks.
     */
    fun requiresRedownloadConfirmation(installed: Boolean): Boolean = installed

    /** Idle state before the user starts a transfer. */
    fun idle(
        spec: AiModelSpec,
        installed: Boolean,
        partialBytes: Long,
        lastProbe: AiModelProbeResult
    ): AiModelInstallUiState {
        if (installed) return AiModelInstallUiState(AiModelInstallPhase.INSTALLED, spec)
        if (partialBytes > 0L) {
            return AiModelInstallUiState(
                phase = AiModelInstallPhase.PARTIAL,
                spec = spec,
                downloadedBytes = partialBytes,
                failureReason = lastProbe.failureReason
            )
        }
        if (lastProbe.outcome == AiModelProbeOutcome.FAILED && lastProbe.failureReason != null) {
            val phase = if (lastProbe.sourceGone) {
                AiModelInstallPhase.SOURCE_GONE
            } else {
                AiModelInstallPhase.FAILED
            }
            val reason = lastProbe.failureReason
            return AiModelInstallUiState(
                phase = phase,
                spec = spec,
                failureReason = reason
            )
        }
        return AiModelInstallUiState(AiModelInstallPhase.NOT_INSTALLED, spec)
    }

    /** State while the downloader reports progress. */
    fun downloading(
        spec: AiModelSpec,
        downloadedBytes: Long,
        failureReason: AiModelFailureReason? = null
    ): AiModelInstallUiState = AiModelInstallUiState(
        phase = AiModelInstallPhase.DOWNLOADING,
        spec = spec,
        downloadedBytes = downloadedBytes.coerceIn(0L, spec.byteSize.coerceAtLeast(0L)),
        failureReason = failureReason
    )

    /** State while a SAF import is being copied and verified. */
    fun importing(spec: AiModelSpec, downloadedBytes: Long = 0L): AiModelInstallUiState =
        AiModelInstallUiState(
            phase = AiModelInstallPhase.IMPORTING,
            spec = spec,
            downloadedBytes = downloadedBytes.coerceIn(0L, spec.byteSize.coerceAtLeast(0L))
        )

    /** State after a transfer failed or was paused. */
    fun failed(
        spec: AiModelSpec,
        reason: AiModelFailureReason,
        partialBytes: Long,
        cancelled: Boolean = false
    ): AiModelInstallUiState = AiModelInstallUiState(
        phase = if (cancelled && partialBytes > 0L) {
            AiModelInstallPhase.PARTIAL
        } else if (reason == AiModelFailureReason.SOURCE_GONE) {
            AiModelInstallPhase.SOURCE_GONE
        } else {
            AiModelInstallPhase.FAILED
        },
        spec = spec,
        downloadedBytes = partialBytes,
        failureReason = reason
    )
}
