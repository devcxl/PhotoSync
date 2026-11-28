package cn.devcxl.photosync.activity

import org.junit.Assert.assertEquals
import org.junit.Test

import cn.devcxl.photosync.editor.vision.AiModelCatalog
import cn.devcxl.photosync.editor.vision.AiModelFailureReason
import cn.devcxl.photosync.editor.vision.AiModelFailureUiCopy
import cn.devcxl.photosync.editor.vision.AiModelInstallUi
import cn.devcxl.photosync.editor.vision.AiModelProbeOutcome
import cn.devcxl.photosync.editor.vision.AiModelProbeResult

/**
 * Seam under test: [AiModelSettingsText] — the settings page must not own a second wording table.
 *
 * The row renderer consumes the shared `:editor` state machine, and its failure sentence is the
 * same resource the editor's install prompt shows for that reason.
 */
class AiModelSettingsTextTest {

    @Test
    fun shouldDelegateEveryFailureSentenceToTheSharedCopy_whenTheReasonIsShown() {
        for (reason in AiModelFailureReason.entries) {
            assertEquals(
                "$reason must use the shared editor copy",
                AiModelFailureUiCopy.of(reason).messageRes,
                AiModelSettingsText.failureMessageRes(reason)
            )
        }
    }

    @Test
    fun shouldKeepTheSameFailureSentenceAsTheEditorPromptActionSet_whenRenderingTheSettingsRow() {
        val state = AiModelInstallUi.idle(
            spec = AiModelCatalog.MODNET_PORTRAIT,
            installed = false,
            partialBytes = 0L,
            lastProbe = AiModelProbeResult(
                outcome = AiModelProbeOutcome.FAILED,
                failureReason = AiModelFailureReason.SOURCE_GONE,
                probedAtMillis = 1L
            )
        )

        assertEquals(AiModelFailureReason.SOURCE_GONE, state.failureReason)
        assertEquals(
            AiModelFailureUiCopy.of(AiModelFailureReason.SOURCE_GONE).messageRes,
            AiModelSettingsText.failureMessageRes(state.failureReason)
        )
        assertEquals(
            AiModelFailureUiCopy.of(AiModelFailureReason.SOURCE_GONE).actions,
            state.actions
        )
    }
}
