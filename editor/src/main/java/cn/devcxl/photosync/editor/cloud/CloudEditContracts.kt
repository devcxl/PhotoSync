package cn.devcxl.photosync.editor.cloud

import android.graphics.Bitmap

import cn.devcxl.photosync.editor.domain.CloudEditLayerRef
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.vision.SubjectMask

/** The three input shapes the editor can send to a BYOK endpoint. */
enum class CloudEditMode {

    /** Natural-language instruction over the whole Post-Pass A frame. */
    WHOLE_IMAGE,

    /** Repaint only the painted region; the stroke overlay becomes the editable mask. */
    PAINTED_MASK,

    /** Replace the background outside the current `SubjectMask` (inverted subject mask). */
    SUBJECT_BACKGROUND
}

/** Local reason a [CloudEditCommand] could not be turned into a request. */
enum class CloudEditInputIssue {

    /** The command carried no usable frame. */
    MISSING_SOURCE,

    /** A painted repaint was requested without any painted pixels. */
    MISSING_PAINTED_MASK,

    /** A background replacement was requested without a subject mask. */
    MISSING_SUBJECT_MASK,

    /** The prompt is empty. */
    BLANK_PROMPT,

    /** The active endpoint has no mask channel, so a local mode cannot be honoured. */
    MASK_NOT_SUPPORTED
}

/**
 * One user-initiated cloud edit, already validated by the UI layer.
 *
 * The frame is the current **Post-Pass A composite**: the same pixels Pass B would otherwise grade,
 * so a cloud result lands in the middle of the pipeline instead of replacing the camera frame.
 *
 * @property mode Which input shape to send.
 * @property prompt User instruction; must not be blank.
 * @property geometry Pass A geometry the returned layer is bound to.
 * @property source Post-Pass A frame; the service never recycles it.
 * @property paintedMask Painted stroke overlay for [CloudEditMode.PAINTED_MASK].
 * @property subjectMask Refined subject mask for [CloudEditMode.SUBJECT_BACKGROUND].
 * @property featherPx Blend-mask feather radius in upload pixels; `0` keeps a hard edge.
 * @property negativePrompt Optional provider negative prompt.
 */
class CloudEditCommand(
    val mode: CloudEditMode,
    val prompt: String,
    val geometry: GeometryOps,
    val source: Bitmap,
    val paintedMask: Bitmap? = null,
    val subjectMask: SubjectMask? = null,
    val featherPx: Float = DEFAULT_FEATHER_PX,
    val negativePrompt: String? = null
) {
    companion object {
        /** Soft edge used for local masks when the caller has no stronger preference. */
        const val DEFAULT_FEATHER_PX = 16.0f
    }
}

/** Outcome of one [CloudEditExecutor.execute] call. */
sealed interface CloudEditOutcome {

    /**
     * The result was written to the content-addressed store and is ready to commit.
     *
     * @property layer Content-addressed reference bound to the request geometry.
     */
    class Applied(val layer: CloudEditLayerRef) : CloudEditOutcome

    /**
     * The request never left the device or the provider returned a classified failure.
     *
     * @property error Classified, user-presentable reason.
     */
    class Failed(val error: CloudEditError) : CloudEditOutcome

    /** The user has not accepted the one-time privacy/cost disclosure yet. */
    data object ConsentRequired : CloudEditOutcome

    /** Cloud is disabled, no profile is selected or its credential is unreadable. */
    data object ProviderUnavailable : CloudEditOutcome

    /**
     * The command was incomplete or the endpoint cannot honour the selected mode.
     *
     * @property issue Typed local reason the controller localizes.
     */
    class InvalidInput(val issue: CloudEditInputIssue) : CloudEditOutcome
}

/**
 * Executes one cloud edit and reports provider stages.
 *
 * Implemented by [CloudEditService]; the editor controller depends on this seam so its state machine
 * is testable with a fake executor and no network.
 */
fun interface CloudEditExecutor {

    /**
     * Runs [command] to completion.
     *
     * @param onStage Provider lifecycle callback. It may be invoked from a provider worker thread.
     * @return A committed layer, a classified failure or a local precondition failure.
     * @throws kotlinx.coroutines.CancellationException when the caller cancelled the job; staged
     *   uploads are deleted before it propagates.
     */
    suspend fun execute(
        command: CloudEditCommand,
        onStage: (CloudJobStage) -> Unit
    ): CloudEditOutcome
}
