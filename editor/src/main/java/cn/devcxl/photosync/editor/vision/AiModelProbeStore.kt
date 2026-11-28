package cn.devcxl.photosync.editor.vision

import java.io.File
import java.io.IOException
import java.util.Properties

import timber.log.Timber

/** What the last real install attempt of one model concluded. */
enum class AiModelProbeOutcome {

    /** Nothing was probed yet; the model is simply not installed. */
    NOT_PROBED,

    /** At least one mirror produced the pinned bytes and the file was promoted. */
    SUCCEEDED,

    /** Every mirror failed, or the verified bytes were unusable. */
    FAILED,

    /** The user cancelled; a resumable `.part` file may still be on disk. */
    CANCELLED
}

/**
 * The persisted result of the last real probe, readable by the settings page and S2 diagnostics.
 *
 * @property outcome Terminal outcome of the attempt, or [AiModelProbeOutcome.NOT_PROBED].
 * @property failureReason Reason that ended the attempt when [outcome] is not a success.
 * @property probedAtMillis Epoch millis of the attempt; `0` when nothing was recorded yet.
 */
data class AiModelProbeResult(
    val outcome: AiModelProbeOutcome,
    val failureReason: AiModelFailureReason?,
    val probedAtMillis: Long
) {

    /** True when the last real attempt ended because every mirror returned 404/410. */
    val sourceGone: Boolean
        get() = outcome == AiModelProbeOutcome.FAILED &&
            failureReason == AiModelFailureReason.SOURCE_GONE

    /** True when a previous real attempt ended in a recoverable failure. */
    val hasFailure: Boolean
        get() = outcome == AiModelProbeOutcome.FAILED && failureReason != null
}

/**
 * Tiny per-model probe journal stored beside the model file.
 *
 * The record is written only by an explicit download attempt, never by a background poll: this is
 * exactly the "last real probe" fact the settings page and the installer prompt render. A missing
 * or unreadable file degrades to [AiModelProbeOutcome.NOT_PROBED] instead of failing the page.
 *
 * @param modelsRootDir Directory that holds one sub-directory per model id.
 */
internal class AiModelProbeStore(private val modelsRootDir: File) {

    /** File inside the model directory that carries the last probe result. */
    fun probeFile(spec: AiModelSpec): File = File(File(modelsRootDir, spec.id), PROBE_FILE_NAME)

    /** Reads the last recorded result, or [AiModelProbeOutcome.NOT_PROBED] when none exists. */
    @Synchronized
    fun read(spec: AiModelSpec): AiModelProbeResult {
        val file = probeFile(spec)
        if (!file.isFile) return NOT_PROBED_RESULT
        return try {
            val properties = Properties()
            file.inputStream().use(properties::load)
            val outcome = parseOutcome(properties.getProperty(KEY_OUTCOME))
            val reason = parseReason(properties.getProperty(KEY_REASON))
            val at = properties.getProperty(KEY_PROBED_AT)?.toLongOrNull() ?: 0L
            AiModelProbeResult(outcome, reason, at)
        } catch (e: IOException) {
            Timber.w(e, "Could not read the probe journal of %s", spec.id)
            NOT_PROBED_RESULT
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Probe journal of %s had an unreadable value", spec.id)
            NOT_PROBED_RESULT
        }
    }

    /**
     * Records one terminal attempt.
     *
     * Failures to write the journal are logged but never escalate: the journal is a reader hint, not
     * part of the integrity contract.
     */
    @Synchronized
    fun record(spec: AiModelSpec, outcome: AiModelProbeOutcome, reason: AiModelFailureReason?) {
        val directory = File(modelsRootDir, spec.id)
        if (!directory.isDirectory && !directory.mkdirs()) {
            Timber.w("Could not create the probe directory of %s", spec.id)
            return
        }
        val properties = Properties().apply {
            setProperty(KEY_OUTCOME, outcome.name)
            reason?.let { setProperty(KEY_REASON, it.name) }
            setProperty(KEY_PROBED_AT, System.currentTimeMillis().toString())
        }
        val target = probeFile(spec)
        val temporary = try {
            File.createTempFile(PROBE_TEMP_PREFIX, ".tmp", directory)
        } catch (e: IOException) {
            Timber.w(e, "Could not create the probe journal of %s", spec.id)
            return
        }
        try {
            temporary.outputStream().use { properties.store(it, null) }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } catch (e: IOException) {
            Timber.w(e, "Could not persist the probe journal of %s", spec.id)
            temporary.delete()
        }
    }

    private fun parseOutcome(raw: String?): AiModelProbeOutcome =
        raw?.let { runCatching { AiModelProbeOutcome.valueOf(it) }.getOrNull() }
            ?: AiModelProbeOutcome.NOT_PROBED

    private fun parseReason(raw: String?): AiModelFailureReason? =
        raw?.let { runCatching { AiModelFailureReason.valueOf(it) }.getOrNull() }

    private companion object {
        const val PROBE_FILE_NAME = ".probe.properties"
        const val PROBE_TEMP_PREFIX = "probe-"
        const val KEY_OUTCOME = "outcome"
        const val KEY_REASON = "reason"
        const val KEY_PROBED_AT = "probedAt"

        val NOT_PROBED_RESULT = AiModelProbeResult(
            outcome = AiModelProbeOutcome.NOT_PROBED,
            failureReason = null,
            probedAtMillis = 0L
        )
    }
}
