package cn.devcxl.photosync.editor.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [AiModelInstallUi] and [AiModelFailureUiCopy] — the one state machine and wording
 * mapping both the AI settings page and the editor's install prompt consume.
 */
class AiModelInstallUiTest {

    private val spec = AiModelCatalog.MI_GAN_PIPELINE

    @Test
    fun shouldMapEveryFailureReasonToDistinctCopyAndActions_whenTheReasonIsShown() {
        val reasons = AiModelFailureReason.entries
        val messageIds = reasons.map { AiModelFailureUiCopy.of(it).messageRes }

        assertEquals(reasons.size, messageIds.toSet().size)
        for (reason in reasons) {
            assertNotEquals("$reason must have a localized sentence", 0, AiModelFailureUiCopy.of(reason).messageRes)
        }
    }

    @Test
    fun shouldOfferLocalImport_whenEveryMirrorIsGone() {
        val ui = AiModelFailureUiCopy.of(AiModelFailureReason.SOURCE_GONE)

        assertTrue(ui.offers(AiModelUiAction.IMPORT_LOCAL))
        assertTrue("download stays available but is a secondary action", ui.offers(AiModelUiAction.DOWNLOAD))
        assertTrue(ui.offers(AiModelUiAction.SWITCH_MIRROR))
    }

    @Test
    fun shouldOfferResume_whenTheTransferWasInterrupted() {
        val ui = AiModelFailureUiCopy.of(AiModelFailureReason.TRANSPORT)

        assertTrue(ui.offers(AiModelUiAction.RESUME_DOWNLOAD))
        assertFalse(ui.offers(AiModelUiAction.IMPORT_LOCAL))
    }

    @Test
    fun shouldOfferRedownloadAndImport_whenTheChecksumFailed() {
        val ui = AiModelFailureUiCopy.of(AiModelFailureReason.CHECKSUM_MISMATCH)

        assertTrue(ui.offers(AiModelUiAction.REDOWNLOAD))
        assertTrue(ui.offers(AiModelUiAction.IMPORT_LOCAL))
    }

    @Test
    fun shouldOfferLocalImportOnly_whenTheSignatureIsIncompatible() {
        val ui = AiModelFailureUiCopy.of(AiModelFailureReason.INCOMPATIBLE)

        assertEquals(listOf(AiModelUiAction.IMPORT_LOCAL), ui.actions)
    }

    @Test
    fun shouldOfferStorageCleanup_whenTheDirectoryIsNotWritable() {
        val ui = AiModelFailureUiCopy.of(AiModelFailureReason.STORAGE_FAILURE)

        assertTrue(ui.offers(AiModelUiAction.CLEAR_STORAGE))
        assertTrue(ui.offers(AiModelUiAction.DOWNLOAD))
    }

    @Test
    fun shouldReportNotInstalled_whenNothingWasProbed() {
        val state = AiModelInstallUi.idle(spec, installed = false, partialBytes = 0L, lastProbe = notProbed())

        assertEquals(AiModelInstallPhase.NOT_INSTALLED, state.phase)
        assertTrue(state.offers(AiModelUiAction.DOWNLOAD))
        assertTrue(state.offers(AiModelUiAction.IMPORT_LOCAL))
    }

    @Test
    fun shouldReportInstalledEvenAfterAFailedProbe_whenTheVerifiedFileExists() {
        val failedProbe = AiModelProbeResult(
            AiModelProbeOutcome.FAILED,
            AiModelFailureReason.SOURCE_GONE,
            probedAtMillis = 1L
        )

        val state = AiModelInstallUi.idle(spec, installed = true, partialBytes = 0L, lastProbe = failedProbe)

        assertEquals(AiModelInstallPhase.INSTALLED, state.phase)
        assertTrue(state.offers(AiModelUiAction.REDOWNLOAD))
        assertTrue(state.offers(AiModelUiAction.DELETE))
        assertFalse(state.importIsPrimary)
    }

    @Test
    fun shouldReportSourceGone_whenTheLastProbeWasA404AndNothingIsInstalled() {
        val gone = AiModelProbeResult(
            AiModelProbeOutcome.FAILED,
            AiModelFailureReason.SOURCE_GONE,
            probedAtMillis = 1L
        )

        val state = AiModelInstallUi.idle(spec, installed = false, partialBytes = 0L, lastProbe = gone)

        assertEquals(AiModelInstallPhase.SOURCE_GONE, state.phase)
        assertTrue(state.importIsPrimary)
        assertTrue(state.downloadMayFail)
        assertTrue(state.offers(AiModelUiAction.DOWNLOAD))
    }

    @Test
    fun shouldReportFailedReason_whenTheLastProbeWasATransportError() {
        val failed = AiModelProbeResult(
            AiModelProbeOutcome.FAILED,
            AiModelFailureReason.SOURCE_DENIED,
            probedAtMillis = 1L
        )

        val state = AiModelInstallUi.idle(spec, installed = false, partialBytes = 0L, lastProbe = failed)

        assertEquals(AiModelInstallPhase.FAILED, state.phase)
        assertEquals(AiModelFailureReason.SOURCE_DENIED, state.failureReason)
        assertEquals(
            AiModelFailureUiCopy.of(AiModelFailureReason.SOURCE_DENIED).messageRes,
            AiModelFailureUiCopy.of(state.failureReason).messageRes
        )
    }

    @Test
    fun shouldReportPartialAndResume_whenABreakpointExists() {
        val state = AiModelInstallUi.idle(
            spec,
            installed = false,
            partialBytes = spec.byteSize / 2 + 1,
            lastProbe = AiModelProbeResult(
                AiModelProbeOutcome.CANCELLED,
                AiModelFailureReason.TRANSPORT,
                probedAtMillis = 1L
            )
        )

        assertEquals(AiModelInstallPhase.PARTIAL, state.phase)
        assertEquals(50, state.percent)
        assertTrue(state.offers(AiModelUiAction.RESUME_DOWNLOAD))
        assertTrue(state.offers(AiModelUiAction.DELETE))
    }

    @Test
    fun shouldExposeBytesAndCancel_whenADownloadIsRunning() {
        val state = AiModelInstallUi.downloading(spec, downloadedBytes = spec.byteSize / 4 + 1)

        assertEquals(AiModelInstallPhase.DOWNLOADING, state.phase)
        assertEquals(25, state.percent)
        assertTrue(state.canCancel)
        assertTrue(state.offers(AiModelUiAction.CANCEL))
        assertFalse(state.offers(AiModelUiAction.DOWNLOAD))
    }

    @Test
    fun shouldRequireConfirmation_whenAnInstalledModelWouldBeReplaced() {
        assertTrue(AiModelInstallUi.requiresRedownloadConfirmation(installed = true))
        assertFalse(AiModelInstallUi.requiresRedownloadConfirmation(installed = false))
    }

    @Test
    fun shouldKeepThePartialState_whenTheUserCancelledWithBytesOnDisk() {
        val state = AiModelInstallUi.failed(
            spec,
            AiModelFailureReason.TRANSPORT,
            partialBytes = 1024L,
            cancelled = true
        )

        assertEquals(AiModelInstallPhase.PARTIAL, state.phase)
        assertTrue(state.offers(AiModelUiAction.RESUME_DOWNLOAD))
    }

    private fun notProbed(): AiModelProbeResult = AiModelProbeResult(
        outcome = AiModelProbeOutcome.NOT_PROBED,
        failureReason = null,
        probedAtMillis = 0L
    )
}
