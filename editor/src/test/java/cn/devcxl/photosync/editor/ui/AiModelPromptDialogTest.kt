package cn.devcxl.photosync.editor.ui

import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

import cn.devcxl.photosync.editor.vision.AiModelCatalog
import cn.devcxl.photosync.editor.vision.AiModelFailureReason
import cn.devcxl.photosync.editor.vision.AiModelFailureUiCopy

/**
 * Seam under test: [AiModelPromptDialog] — the editor's first-install sheet must repeat the exact
 * shared failure sentence of the settings page, not invent its own wording.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiModelPromptDialogTest {

    @Test
    fun shouldAppendTheSharedFailureSentence_whenTheLastProbeFailed() {
        val context = ContextThemeWrapper(
            RuntimeEnvironment.getApplication(),
            androidx.appcompat.R.style.Theme_AppCompat
        )
        val expected = context.getString(
            AiModelFailureUiCopy.of(AiModelFailureReason.SOURCE_GONE).messageRes
        )

        AiModelPromptDialog.show(
            context = context,
            spec = AiModelCatalog.MI_GAN_PIPELINE,
            onDownload = {},
            onImport = {},
            failureReason = AiModelFailureReason.SOURCE_GONE
        )

        val dialog = ShadowDialog.getLatestDialog()
        val message = dialog.findViewById<TextView>(android.R.id.message)?.text?.toString().orEmpty()
        assertTrue("dialog must show the shared reason, got: $message", message.contains(expected))
        dialog.dismiss()
    }
}
