package cn.devcxl.photosync.editor.ui

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast

import kotlinx.coroutines.CoroutineScope

import cn.devcxl.photosync.editor.cloud.CloudConsentStore
import cn.devcxl.photosync.editor.cloud.CloudEditService
import cn.devcxl.photosync.editor.cloud.CloudProviderConfigStore
import cn.devcxl.photosync.editor.cloud.ConfigStoreCloudProviderSource
import cn.devcxl.photosync.editor.databinding.ActivityEditorBinding
import cn.devcxl.photosync.editor.domain.CloudEditLayerRef
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.ui.panels.AiInferenceBarController
import cn.devcxl.photosync.editor.ui.panels.EditorPanelController
import cn.devcxl.photosync.editor.ui.widgets.CloudPanelListener
import cn.devcxl.photosync.editor.ui.widgets.CloudPanelState
import cn.devcxl.photosync.editor.ui.widgets.CloudPanelView
import cn.devcxl.photosync.editor.vision.SubjectMask

/**
 * Routes the cloud panel's visibility through [EditorPanelController].
 *
 * The panel view itself can only show or hide itself; the controller also has to know whether the
 * `AI -> 云端` tool is the active sub-tool and whether the P4 switch is on. Keeping that decision in
 * one place is what makes "switch off ⇒ no cloud entry anywhere" hold.
 */
internal class CloudPanelAdapter(
    private val view: CloudPanelView,
    private val panelProvider: () -> EditorPanelController
) : CloudPanelPort {

    override fun setCloudPanelVisible(visible: Boolean) {
        panelProvider().setCloudPanelVisible(visible)
    }

    override fun renderCloudPanel(state: CloudPanelState) {
        view.renderCloudPanel(state)
    }

    override fun bindListener(listener: CloudPanelListener) {
        view.bindListener(listener)
    }
}

/**
 * Activity-side implementation of [EditorCloudHost].
 *
 * Every dependency is a lambda so the cloud controller stays testable and this bridge owns no state
 * beyond forwarding: frames, masks, the shared inference bar and the dialog helpers.
 */
internal class EditorCloudHostBridge(
    private val context: Context,
    private val inferenceBar: AiInferenceBarController,
    private val postPassAProvider: () -> Bitmap?,
    private val paintedMaskProvider: () -> Bitmap?,
    private val subjectMaskProvider: () -> SubjectMask?,
    private val onConsentAccepted: () -> Unit,
    private val onGraphChanged: (EditGraph) -> Unit,
    private val onCloudLayerPreview: (CloudEditLayerRef?) -> Unit
) : EditorCloudHost {

    override fun currentPostPassABitmap(): Bitmap? = postPassAProvider()

    override fun paintedMaskBitmap(): Bitmap? = paintedMaskProvider()

    override fun subjectMask(): SubjectMask? = subjectMaskProvider()

    override fun showProgress(labelRes: Int, initialPercent: Int) {
        inferenceBar.show(context.getString(labelRes), initialPercent)
    }

    override fun updateProgress(labelRes: Int, percent: Int) {
        inferenceBar.updateLabel(context.getString(labelRes))
        inferenceBar.updateProgress(percent)
    }

    override fun hideProgress() = inferenceBar.dismiss()

    override fun showToast(messageRes: Int) {
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }

    override fun showConsentDialog(onAccepted: () -> Unit) {
        CloudDialogs.showConsent(context) {
            onConsentAccepted()
            onAccepted()
        }
    }

    override fun showGeometryConflictDialog(onRemoveLayer: () -> Unit, onCancel: () -> Unit) {
        CloudDialogs.showGeometryConflict(context, onRemoveLayer = onRemoveLayer, onCancel = onCancel)
    }

    override fun onGraphCommitted(graph: EditGraph) = onGraphChanged(graph)

    override fun onCloudLayerPreviewChanged(layer: CloudEditLayerRef?) = onCloudLayerPreview(layer)
}

/** Builds the wired [EditorCloudController] of one editor session. */
internal object EditorCloudBindings {

    /**
     * Creates the cloud controller for [photoId].
     *
     * @param panelProvider The drawer controller that owns the panel's visibility.
     * @param binding Editor view binding that holds the cloud panel.
     */
    fun create(
        context: Context,
        photoId: Long,
        panelProvider: () -> EditorPanelController,
        binding: ActivityEditorBinding,
        inferenceBar: AiInferenceBarController,
        undoController: EditorUndoController,
        scope: CoroutineScope,
        postPassAProvider: () -> Bitmap?,
        paintedMaskProvider: () -> Bitmap?,
        subjectMaskProvider: () -> SubjectMask?,
        onGraphCommitted: (EditGraph) -> Unit,
        onCloudLayerPreview: (CloudEditLayerRef?) -> Unit
    ): EditorCloudController {
        val providers = ConfigStoreCloudProviderSource(CloudProviderConfigStore(context))
        val consent = CloudConsentStore(context)
        return EditorCloudController(
            scope = scope,
            host = EditorCloudHostBridge(
                context = context,
                inferenceBar = inferenceBar,
                postPassAProvider = postPassAProvider,
                paintedMaskProvider = paintedMaskProvider,
                subjectMaskProvider = subjectMaskProvider,
                onConsentAccepted = consent::accept,
                onGraphChanged = onGraphCommitted,
                onCloudLayerPreview = onCloudLayerPreview
            ),
            panel = CloudPanelAdapter(binding.cloudPanelView, panelProvider),
            undoController = undoController,
            providers = providers,
            executor = CloudEditService(context, photoId, providers),
            consent = consent
        )
    }
}
