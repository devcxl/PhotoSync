package cn.devcxl.photosync.editor.ui

import java.util.UUID

import android.app.Application
import android.content.Context
import android.graphics.Bitmap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

import cn.devcxl.photosync.editor.R
import cn.devcxl.photosync.editor.cloud.CloudConsentStore
import cn.devcxl.photosync.editor.cloud.CloudEditCommand
import cn.devcxl.photosync.editor.cloud.CloudEditExecutor
import cn.devcxl.photosync.editor.cloud.CloudEditMode
import cn.devcxl.photosync.editor.cloud.CloudEditOutcome
import cn.devcxl.photosync.editor.cloud.CloudEditProvider
import cn.devcxl.photosync.editor.cloud.CloudGeometryDigest
import cn.devcxl.photosync.editor.cloud.CloudJobStage
import cn.devcxl.photosync.editor.cloud.CloudProviderKind
import cn.devcxl.photosync.editor.cloud.CloudProviderProfile
import cn.devcxl.photosync.editor.cloud.CloudProviderSource
import cn.devcxl.photosync.editor.domain.CloudEditLayerRef
import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.ui.widgets.CloudPanelListener
import cn.devcxl.photosync.editor.ui.widgets.CloudPanelState
import cn.devcxl.photosync.editor.vision.SubjectMask

/**
 * Seam under test: [EditorCloudController] — visibility gating, consent gating, the Gemini advisory
 * and the non-destructive geometry conflict branches. The executor, panel and host are fakes, so no
 * network, dialog or Activity is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EditorCloudControllerTest {

    private lateinit var application: Application
    private lateinit var scope: CoroutineScope
    private lateinit var undoController: EditorUndoController
    private lateinit var consent: CloudConsentStore
    private lateinit var source: FakeProviderSource
    private lateinit var executor: FakeExecutor
    private lateinit var host: FakeHost
    private lateinit var panel: FakePanel

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        undoController = EditorUndoController(EditGraph())
        consent = CloudConsentStore(
            application.getSharedPreferences("consent-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        )
        source = FakeProviderSource()
        executor = FakeExecutor(
            CloudEditOutcome.Applied(layer(GeometryOps()))
        )
        host = FakeHost()
        panel = FakePanel()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun shouldHideTheCloudEntry_whenTheGlobalSwitchIsOff() {
        source.enabled = false
        val controller = controller()

        controller.refresh()

        assertFalse(panel.cloudPanelVisible)
        assertNull(panel.state)
    }

    @Test
    fun shouldShowTheCloudEntryAndRenderState_whenTheSwitchIsOn() {
        val controller = controller()

        controller.refresh()

        assertTrue(panel.cloudPanelVisible)
        assertEquals(CloudEditMode.WHOLE_IMAGE, panel.state?.mode)
        assertFalse(panel.state!!.hasLayer)
    }

    @Test
    fun shouldBlockTheSendAndAskForConsent_whenConsentWasNotAccepted() {
        val controller = controller()
        controller.onCloudPromptChanged("make it cinematic")

        controller.onCloudGenerateClicked()

        assertTrue(executor.commands.isEmpty())
        assertEquals(1, host.consentCallbacks.size)

        host.consentCallbacks.single().invoke()

        assertEquals(1, executor.commands.size)
        assertNotNull(undoController.currentGraph.cloudLayer)
        assertEquals(1, undoController.undoDepth)
        assertEquals(R.string.editor_cloud_applied, host.toasts.last())
    }

    @Test
    fun shouldSendDirectly_whenConsentWasAlreadyAccepted() {
        consent.accept()
        val controller = controller()
        controller.onCloudPromptChanged("make it cinematic")

        controller.onCloudGenerateClicked()

        assertEquals(1, executor.commands.size)
        assertTrue(host.consentCallbacks.isEmpty())
    }

    @Test
    fun shouldDiscardTheLayer_whenTheGeometryChangedWhileGenerating() {
        consent.accept()
        executor = FakeExecutor(CloudEditOutcome.Applied(layer(GeometryOps(rotate90 = 1))))
        val controller = controller()
        controller.onCloudPromptChanged("rotate it")

        controller.onCloudGenerateClicked()

        assertNull(undoController.currentGraph.cloudLayer)
        assertTrue(host.toasts.contains(R.string.editor_cloud_failed))
    }

    @Test
    fun shouldRequireAPrompt_whenGenerateIsTappedWithAnEmptyField() {
        consent.accept()
        val controller = controller()

        controller.onCloudGenerateClicked()

        assertTrue(executor.commands.isEmpty())
        assertTrue(host.toasts.contains(R.string.editor_cloud_prompt_required))
    }

    @Test
    fun shouldWarnAboutSoftBoundaries_whenGeminiHandlesALocalMode() {
        source.profile = profile(CloudProviderKind.GEMINI)
        val controller = controller()
        controller.refresh()

        controller.onCloudModeSelected(CloudEditMode.PAINTED_MASK)
        assertTrue(panel.state!!.softMaskWarning)

        controller.onCloudModeSelected(CloudEditMode.SUBJECT_BACKGROUND)
        assertTrue(panel.state!!.softMaskWarning)

        controller.onCloudModeSelected(CloudEditMode.WHOLE_IMAGE)
        assertFalse(panel.state!!.softMaskWarning)
    }

    @Test
    fun shouldBlockMaskedModes_whenTheEndpointHasNoMaskNode() {
        source.profile = profile(CloudProviderKind.COMFY_UI, maskNodeId = "")
        val controller = controller()
        controller.refresh()

        controller.onCloudModeSelected(CloudEditMode.PAINTED_MASK)

        assertEquals(CloudEditMode.WHOLE_IMAGE, panel.state!!.mode)
        assertTrue(host.toasts.contains(R.string.editor_cloud_mask_unsupported))
    }

    @Test
    fun shouldVetoTheGeometryChange_whenTheCloudDigestNoLongerMatches() {
        val oldGeometry = GeometryOps()
        val newGeometry = GeometryOps(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f))
        undoController.resetTo(EditGraph(cloudLayer = layer(oldGeometry)))
        val controller = installedController()

        val applied = undoController.onParameterDrag(
            undoController.currentGraph.copy(geometry = newGeometry),
            EditParameterKey.GEOMETRY_CROP
        )

        assertEquals(oldGeometry, applied.geometry)
        assertEquals(1, host.conflictCallbacks.size)
        assertNotNull("The layer must survive until the user decides", undoController.currentGraph.cloudLayer)
        assertTrue(controller.isEnabled())
    }

    @Test
    fun shouldRemoveTheLayerAndApplyGeometry_whenTheConflictIsConfirmed() {
        val oldGeometry = GeometryOps()
        val newGeometry = GeometryOps(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f))
        undoController.resetTo(EditGraph(cloudLayer = layer(oldGeometry)))
        installedController()
        undoController.onParameterDrag(
            undoController.currentGraph.copy(geometry = newGeometry),
            EditParameterKey.GEOMETRY_CROP
        )

        host.conflictCallbacks.single().invoke()

        assertEquals(newGeometry, undoController.currentGraph.geometry)
        assertNull(undoController.currentGraph.cloudLayer)
        assertEquals(1, undoController.undoDepth)
        assertEquals(1, host.committedGraphs.size)
        assertTrue(host.toasts.contains(R.string.editor_cloud_geometry_removed))
    }

    @Test
    fun shouldKeepTheLayerAndGeometry_whenTheConflictIsCancelled() {
        val oldGeometry = GeometryOps()
        val newGeometry = GeometryOps(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f))
        val layer = layer(oldGeometry)
        undoController.resetTo(EditGraph(cloudLayer = layer))
        installedController()
        undoController.onParameterDrag(
            undoController.currentGraph.copy(geometry = newGeometry),
            EditParameterKey.GEOMETRY_CROP
        )

        host.conflictCancels.single().invoke()

        assertEquals(oldGeometry, undoController.currentGraph.geometry)
        assertEquals(layer, undoController.currentGraph.cloudLayer)
        assertEquals(0, undoController.undoDepth)
        assertTrue(host.committedGraphs.isEmpty())
        // A later attempt must be able to open the sheet again.
        undoController.onParameterDrag(
            undoController.currentGraph.copy(geometry = newGeometry),
            EditParameterKey.GEOMETRY_CROP
        )
        assertEquals(2, host.conflictCallbacks.size)
    }

    @Test
    fun shouldApplyGeometryWithoutADialog_whenTheDigestMatches() {
        val newGeometry = GeometryOps(rotate90 = 1)
        val layer = layer(newGeometry)
        undoController.resetTo(EditGraph(geometry = GeometryOps(), cloudLayer = layer))
        installedController()

        val applied = undoController.onParameterDrag(
            undoController.currentGraph.copy(geometry = newGeometry),
            EditParameterKey.GEOMETRY_ROTATE_FLIP
        )

        assertEquals(newGeometry, applied.geometry)
        assertTrue(host.conflictCallbacks.isEmpty())
        assertEquals(layer, undoController.currentGraph.cloudLayer)
    }

    @Test
    fun shouldHideTheChipAndPanel_whenTheP4SwitchIsOff() {
        source.enabled = false
        val controller = controller()

        controller.refresh()

        assertFalse(panel.cloudPanelVisible)
        assertEquals(false, controller.isEnabled())
    }

    @Test
    fun shouldCommitOpacityAsOneUndoStep_whenTheSliderIsReleased() {
        undoController.resetTo(EditGraph(cloudLayer = layer(GeometryOps()).copy(opacity = 100)))
        val controller = controller()

        controller.onCloudOpacityCommitted(40)

        assertEquals(40, undoController.currentGraph.cloudLayer?.opacity)
        assertEquals(1, undoController.undoDepth)
    }

    @Test
    fun shouldRemoveTheLayer_whenTheRemoveButtonIsTapped() {
        undoController.resetTo(EditGraph(cloudLayer = layer(GeometryOps())))
        val controller = controller()

        controller.onCloudRemoveLayerClicked()

        assertNull(undoController.currentGraph.cloudLayer)
        assertEquals(1, undoController.undoDepth)
        assertTrue(host.toasts.contains(R.string.editor_cloud_layer_removed))
    }

    @Test
    fun shouldPreviewOpacityWithoutAnUndoStep_whenTheSliderMoves() {
        undoController.resetTo(EditGraph(cloudLayer = layer(GeometryOps())))
        val controller = controller()

        controller.onCloudOpacityChanged(70)

        assertEquals(100, undoController.currentGraph.cloudLayer?.opacity)
        assertEquals(70, host.previewLayer?.opacity)
        assertEquals(0, undoController.undoDepth)
    }

    private fun installedController(): EditorCloudController {
        val controller = controller()
        controller.installGeometryGuard(undoController)
        return controller
    }

    private fun controller(): EditorCloudController = EditorCloudController(
        scope = scope,
        host = host,
        panel = panel,
        undoController = undoController,
        providers = source,
        executor = executor,
        consent = consent,
        frameDispatcher = Dispatchers.Unconfined
    )

    private fun layer(geometry: GeometryOps): CloudEditLayerRef = CloudEditLayerRef(
        imageRelPath = "edits/1/cloud_layer_${"a".repeat(64)}.png",
        sha256 = "a".repeat(64),
        opacity = 100,
        geometryDigest = CloudGeometryDigest.digest(geometry)
    )

    private fun profile(
        kind: CloudProviderKind,
        maskNodeId: String = "mask_node"
    ): CloudProviderProfile = CloudProviderProfile(
        profileId = "profile-$kind",
        displayName = "Test provider",
        kind = kind,
        baseUrl = "https://example.test",
        maskNodeId = maskNodeId
    )

    private class FakePanel : CloudPanelPort {
        var cloudPanelVisible: Boolean = false
            private set

        var state: CloudPanelState? = null
            private set

        override fun setCloudPanelVisible(visible: Boolean) {
            this.cloudPanelVisible = visible
        }

        override fun renderCloudPanel(state: CloudPanelState) {
            this.state = state
        }

        override fun bindListener(listener: CloudPanelListener) = Unit
    }

    private class FakeHost : EditorCloudHost {
        private val bitmap: Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        private val painted: Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        private val subject: SubjectMask = SubjectMask(4, 4, FloatArray(16) { 1.0f })

        val toasts = mutableListOf<Int>()
        val consentCallbacks = mutableListOf<() -> Unit>()
        val conflictCallbacks = mutableListOf<() -> Unit>()
        val conflictCancels = mutableListOf<() -> Unit>()
        val committedGraphs = mutableListOf<EditGraph>()

        var previewLayer: CloudEditLayerRef? = null
            private set

        var progressVisible: Boolean = false
            private set

        override fun currentPostPassABitmap(): Bitmap = bitmap

        override fun paintedMaskBitmap(): Bitmap = painted

        override fun subjectMask(): SubjectMask = subject

        override fun showProgress(labelRes: Int, initialPercent: Int) {
            progressVisible = true
        }

        override fun updateProgress(labelRes: Int, percent: Int) = Unit

        override fun hideProgress() {
            progressVisible = false
        }

        override fun showToast(messageRes: Int) {
            toasts += messageRes
        }

        override fun showConsentDialog(onAccepted: () -> Unit) {
            consentCallbacks += onAccepted
        }

        override fun showGeometryConflictDialog(onRemoveLayer: () -> Unit, onCancel: () -> Unit) {
            conflictCallbacks += onRemoveLayer
            conflictCancels += onCancel
        }

        override fun onGraphCommitted(graph: EditGraph) {
            committedGraphs += graph
        }

        override fun onCloudLayerPreviewChanged(layer: CloudEditLayerRef?) {
            previewLayer = layer
        }
    }

    private class FakeExecutor(
        private val outcome: CloudEditOutcome
    ) : CloudEditExecutor {
        val commands = mutableListOf<CloudEditCommand>()

        override suspend fun execute(
            command: CloudEditCommand,
            onStage: (CloudJobStage) -> Unit
        ): CloudEditOutcome {
            commands += command
            onStage(CloudJobStage.RUNNING)
            return outcome
        }
    }

    private class FakeProviderSource : CloudProviderSource {
        var enabled: Boolean = true
        var profile: CloudProviderProfile? = CloudProviderProfile(
            profileId = "profile-openai",
            displayName = "OpenAI",
            kind = CloudProviderKind.OPENAI_COMPATIBLE,
            baseUrl = "https://example.test"
        )

        override fun isCloudEnabled(): Boolean = enabled

        override fun activeProfile(): CloudProviderProfile? = profile

        override fun createProvider(): Result<CloudEditProvider> =
            Result.failure(IllegalStateException("The controller must not create providers"))
    }
}
