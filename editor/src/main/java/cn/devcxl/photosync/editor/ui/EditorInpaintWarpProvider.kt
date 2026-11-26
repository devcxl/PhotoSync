package cn.devcxl.photosync.editor.ui

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.LiquifySession
import cn.devcxl.photosync.editor.domain.PortraitAnalysis
import cn.devcxl.photosync.editor.domain.PortraitWarpComposer
import cn.devcxl.photosync.editor.domain.WarpDisplacementField

/**
 * Supplies the composed Pass A warp for coordinate projections without re-solving it per touch.
 *
 * The inpainting brush asks for the field on every stroke sample. Face/body meshes are pure
 * functions of the graph and the liquify mesh is replaced on every stroke, so a signature of those
 * inputs is enough to reuse the last composition; an idle photo returns `null` without allocating.
 */
internal class EditorInpaintWarpProvider(
    private val undoController: EditorUndoController,
    private val liquifySession: LiquifySession,
    private val analysisProvider: () -> PortraitAnalysis?
) {

    private var signature: Int = 0
    private var cached: WarpDisplacementField? = null
    private var cachedAnalysisIdentity: Int = 0

    /** Returns the active composed field, or `null` when the graph has no warp at all. */
    operator fun invoke(): WarpDisplacementField? {
        val graph = undoController.currentGraph
        val mesh = liquifySession.mesh
        if (graph.portrait.reshape.isIdentity() &&
            graph.portrait.body.isIdentity() &&
            mesh.isZero()
        ) {
            cached = null
            return null
        }
        val analysis = analysisProvider()
        val nextSignature = signatureOf(graph)
        val nextAnalysisIdentity = analysis?.let(System::identityHashCode) ?: 0
        if (cached != null &&
            nextSignature == signature &&
            nextAnalysisIdentity == cachedAnalysisIdentity
        ) {
            return cached
        }
        cached = PortraitWarpComposer.compose(
            faceWarp = analysis?.faceWarp(graph.portrait.reshape),
            bodyWarp = analysis?.bodyWarp(graph.portrait.body),
            liquify = mesh
        )
        signature = nextSignature
        cachedAnalysisIdentity = nextAnalysisIdentity
        return cached
    }

    private fun signatureOf(graph: EditGraph): Int {
        var result = graph.portrait.reshape.hashCode()
        result = 31 * result + graph.portrait.body.hashCode()
        result = 31 * result + liquifySession.revision
        return result
    }
}
