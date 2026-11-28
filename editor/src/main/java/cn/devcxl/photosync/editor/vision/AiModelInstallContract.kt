package cn.devcxl.photosync.editor.vision

import java.io.File

/**
 * Why an [AiModelDownloadManager] install failed; every reason is recoverable.
 *
 * The taxonomy deliberately separates "the source is gone" and "the source denies access" from a
 * generic transport failure: those two need a different user action (switch mirror or import
 * locally) than a dropped connection (resume the kept `.part`).
 */
enum class AiModelFailureReason {

    /** The catalog entry has no pinned artifact yet, so there is nothing to verify against. */
    NOT_AVAILABLE,

    /** Every mirror returned 404/410; the artifact has moved or the release was never published. */
    SOURCE_GONE,

    /** At least one mirror returned 401/403 and none produced the pinned bytes. */
    SOURCE_DENIED,

    /** Timeout, reset connection, no network, or a user-requested pause. */
    TRANSPORT,

    /** The transferred or imported file did not have the exact pinned byte count. */
    SIZE_MISMATCH,

    /** The file had the right size but a different SHA-256, so it may be corrupted or tampered. */
    CHECKSUM_MISMATCH,

    /** The verified bytes did not match the input signature the catalog expects. */
    INCOMPATIBLE,

    /** The model directory could not be created, written or renamed. */
    STORAGE_FAILURE
}

/**
 * Recoverable failure returned by [AiModelDownloadManager].
 *
 * @property reason Machine-readable cause the settings page maps to a localized hint.
 */
class AiModelInstallException(
    val reason: AiModelFailureReason,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Cooperative pause flag for one download attempt.
 *
 * A running transfer polls this flag between buffers, keeps the `.part` file and returns a
 * [AiModelFailureReason.TRANSPORT] failure, so a cancel button can offer "resume" instead of
 * destroying the bytes already received. Cancelling the coroutine job itself still leaves the
 * `.part` untouched, but no `Result` can be returned from a cancelled context.
 */
class AiModelDownloadCancellation {

    @Volatile
    private var cancelled = false

    /** True once [cancel] was called. */
    val isCancelled: Boolean
        get() = cancelled

    /** Requests a cooperative pause; idempotent. */
    fun cancel() {
        cancelled = true
    }
}

/**
 * Signature-compatibility seam checked before a verified file is promoted or imported.
 *
 * The production default accepts every pinned file. E1 supplies the real ONNX input-signature
 * detector here (and caches its verdict), so this ticket can already return
 * [AiModelFailureReason.INCOMPATIBLE] instead of a generic checksum story.
 */
fun interface AiModelVariantCompatibility {

    /** Returns `true` when [file] matches [hint] (or the caller does not care). */
    fun accepts(file: File, hint: InpaintVariant?): Boolean

    companion object {

        /** The permissive default used until the runtime detector is wired. */
        val ANY: AiModelVariantCompatibility = AiModelVariantCompatibility { _, _ -> true }
    }
}
