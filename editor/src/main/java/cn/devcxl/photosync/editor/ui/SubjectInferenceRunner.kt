package cn.devcxl.photosync.editor.ui

import java.io.File

import android.content.Context
import android.widget.Toast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.render.EditorSource
import cn.devcxl.photosync.editor.render.SubjectComposite
import cn.devcxl.photosync.editor.render.SubjectCompositeLoader
import cn.devcxl.photosync.editor.ui.panels.AiInferenceBarController
import cn.devcxl.photosync.editor.vision.ModnetSubjectSegmenter

/**
 * Runs one MODNet pass and turns it into a persisted mask plus a source-space composite.
 *
 * Kept apart from [EditorSubjectController] so the controller owns only the interaction policy
 * (mode chips, install prompts, undo commits) while the inference progress, failure toasts and
 * asset write live in one place.
 */
internal class SubjectInferenceRunner(
    private val context: Context,
    private val photoId: Long,
    private val inferenceBar: AiInferenceBarController,
    private val sourceProvider: () -> EditorSource?,
    private val composites: SubjectCompositeRefresher,
    private val segmenter: ModnetSubjectSegmenter
) {

    /** What one successful inference produced. */
    data class Result(val maskRef: String, val composite: SubjectComposite?)

    /**
     * Segments the staged source with [modelFile].
     *
     * @return The persisted mask reference and its composite, or `null` after a localized failure.
     */
    suspend fun run(modelFile: File): Result? {
        val source = sourceProvider()
        val image = source?.let { composites.subjectImage(it) }
        if (image == null) {
            Toast.makeText(context, R.string.editor_subject_inference_failed, Toast.LENGTH_SHORT)
                .show()
            return null
        }
        inferenceBar.show(context.getString(R.string.editor_subject_inference_label))
        inferenceBar.updateProgress(PROGRESS_INFERENCE_START)
        val segmented = withContext(Dispatchers.Default) { segmenter.segment(image, modelFile) }
        currentCoroutineContext().ensureActive()
        inferenceBar.updateProgress(PROGRESS_INFERENCE_DONE)
        val mask = segmented.getOrElse { error ->
            Timber.e(error, "MODNet subject segmentation failed")
            inferenceBar.dismiss()
            Toast.makeText(context, R.string.editor_subject_inference_failed, Toast.LENGTH_SHORT)
                .show()
            return null
        }
        val reference = segmenter.saveMask(photoId, mask)
        if (reference == null) {
            inferenceBar.dismiss()
            Toast.makeText(context, R.string.editor_subject_inference_failed, Toast.LENGTH_SHORT)
                .show()
            return null
        }
        inferenceBar.updateLabel(context.getString(R.string.editor_subject_refine_label))
        val composite = withContext(Dispatchers.Default) {
            SubjectCompositeLoader.fromSource(source, mask)
        }
        currentCoroutineContext().ensureActive()
        inferenceBar.dismiss()
        return Result(reference.relativePath, composite)
    }

    private companion object {
        const val PROGRESS_INFERENCE_START = 35
        const val PROGRESS_INFERENCE_DONE = 75
    }
}
