package cn.devcxl.photosync.editor.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.BodyOps
import cn.devcxl.photosync.editor.domain.BodyWarpSolver
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.PortraitOps
import cn.devcxl.photosync.editor.domain.SkinOps
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.vision.PortraitAnalysisSnapshot

/**
 * Verifies the preview result the cached analysis feeds: the same [PortraitAnalysisSnapshot] must
 * produce a different mesh for a different graph (nothing warp-shaped is cached) and must reuse the
 * cached mask instance when the graph asks for it.
 */
class PortraitPreviewResolutionTest {

    private val snapshot = PortraitAnalysisSnapshot(
        faces = emptyList(),
        skinMask = FloatArray(MASK_CELLS) { 0.5f },
        personMask = null,
        usesMulticlass = false
    )

    @Test
    fun shouldRebuildTheBodyWarp_whenTheGraphChanges() {
        val analysis = snapshot.toAnalysis(BodyOps())
        val neutral = BakePortraitSupport.composeResolvedPortrait(analysis, EditGraph(), liquifyMesh = null)
        assertNull(neutral.skinMask)
        assertTrue(neutral.warpField!!.isZero())

        val slim = BakePortraitSupport.composeResolvedPortrait(analysis, graphWith(waist = 100), null)
        val widen = BakePortraitSupport.composeResolvedPortrait(analysis, graphWith(waist = -100), null)
        val slimWarp = slim.warpField!!
        val widenWarp = widen.warpField!!

        assertFalse(slimWarp.isZero())
        assertFalse(widenWarp.isZero())
        assertTrue(
            "opposite waist sliders must produce different meshes from the same cached analysis",
            differs(slimWarp, widenWarp)
        )
    }

    @Test
    fun shouldGateTheCachedSkinMask_onTheCurrentGraph() {
        val analysis = snapshot.toAnalysis(BodyOps())
        val smoothing = BakePortraitSupport.composeResolvedPortrait(
            analysis,
            EditGraph(portrait = PortraitOps(skin = SkinOps(smooth = 50))),
            liquifyMesh = null
        )
        assertSame("the cached mask must be handed out unchanged", analysis.skinMask, smoothing.skinMask)

        val maskOff = BakePortraitSupport.composeResolvedPortrait(analysis, EditGraph(), null)
        assertNull(maskOff.skinMask)
    }

    private fun graphWith(waist: Int): EditGraph =
        EditGraph(portrait = PortraitOps(body = BodyOps(waist = waist)))

    private fun differs(left: WarpDisplacementField, right: WarpDisplacementField): Boolean {
        if (left.gridSize != right.gridSize) return true
        val cells = left.gridSize * left.gridSize * COMPONENTS
        for (index in 0 until cells) {
            if (left.displacementRaw(index) != right.displacementRaw(index)) return true
        }
        return false
    }

    private companion object {
        private const val COMPONENTS = 2
        private val MASK_CELLS = BodyWarpSolver.MASK_SIZE * BodyWarpSolver.MASK_SIZE
    }
}
