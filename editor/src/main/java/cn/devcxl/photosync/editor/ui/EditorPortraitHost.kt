package cn.devcxl.photosync.editor.ui

import java.io.File

import android.content.Context

import kotlinx.coroutines.CoroutineScope

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditParameterKey
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.PortraitAnalysis
import cn.devcxl.photosync.editor.render.EditorGlRenderer
import cn.devcxl.photosync.editor.render.EditorSource
import cn.devcxl.photosync.editor.ui.gesture.EditorGroup
import cn.devcxl.photosync.editor.ui.panels.EditorPanelController

/**
 * Routes everything the Portrait group needs to the [EditorPortraitCoordinator]: the first entry
 * into the tab, the body guide drags, the warp re-solves and the release of the MediaPipe tasks.
 *
 * Keeping this out of [EditorActivity] leaves the activity owning only the session and the render
 * flow, and lets the portrait glue be reasoned about on its own.
 */
internal class EditorPortraitHost(
    context: Context,
    filesDir: File,
    private val undoController: EditorUndoController,
    renderer: EditorGlRenderer,
    scope: CoroutineScope,
    private val panelProvider: () -> EditorPanelController,
    private val sourceProvider: () -> EditorSource?,
    onWarpsPublished: () -> Unit,
    private val onGraphEdited: () -> Unit,
    private val onGraphCommitted: () -> Unit
) {

    private val coordinator = EditorPortraitCoordinator(
        context = context,
        filesDir = filesDir,
        renderer = renderer,
        scope = scope,
        currentGraph = { undoController.currentGraph },
        onWarpsPublished = onWarpsPublished
    )

    private var lastGroup: EditorGroup? = null
    private var lastSignature: Int = 0

    /** The analysis produced for this photo, or `null` before the first Portrait entry. */
    fun analysis(): PortraitAnalysis? = coordinator.currentAnalysis()

    /** Resolved waist / hip lines for the guide overlay. */
    fun guides() = coordinator.currentGuides()

    /**
     * Called on every tool change: entering the Portrait tab starts the analysis and the background
     * model download exactly once per session.
     */
    fun onToolChanged() {
        val group = panelProvider().currentGroup
        if (group == EditorGroup.PORTRAIT && lastGroup != EditorGroup.PORTRAIT) {
            coordinator.onPortraitEntered(sourceProvider())
        }
        lastGroup = group
    }

    /**
     * Called once the photo finished loading: a Portrait tab opened before the source was ready
     * gets its analysis here instead of waiting for the user to leave and re-enter the tab.
     */
    fun onSourceLoaded() {
        if (panelProvider().currentGroup == EditorGroup.PORTRAIT) {
            coordinator.onPortraitEntered(sourceProvider())
        }
    }

    /** Mirrors a guide drag into `EditGraph.portrait.body` and re-renders the proxy. */
    fun onGuidesChanged(waistY: Float, hipY: Float) {
        val current = undoController.currentGraph
        val updated = current.copy(
            portrait = current.portrait.copy(
                body = current.portrait.body.copy(waistY = waistY, hipY = hipY)
            )
        )
        undoController.onParameterDrag(updated, EditParameterKey.PORTRAIT_BODY)
        onGraphEdited()
    }

    /** Seals the guide drag's undo step. */
    fun onGuidesCommitted() {
        undoController.onParameterRelease()
        onGraphCommitted()
    }

    /**
     * Re-solves the portrait meshes when `portrait.reshape` / `portrait.body` changed.
     *
     * The signature guard keeps a tone or colour drag from paying for an MLS solve.
     */
    fun syncWarps(graph: EditGraph) {
        val signature = 31 * graph.portrait.reshape.hashCode() + graph.portrait.body.hashCode()
        if (signature == lastSignature) return
        lastSignature = signature
        coordinator.onGraphChanged(graph)
    }

    /** Cancels the portrait jobs and releases the MediaPipe tasks. */
    fun release() {
        coordinator.release()
    }
}
