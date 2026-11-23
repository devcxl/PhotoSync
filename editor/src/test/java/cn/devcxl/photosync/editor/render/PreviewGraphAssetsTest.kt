package cn.devcxl.photosync.editor.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.ColorOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.LutRef

/**
 * Seam under test: [PreviewGraphAssets] — a graph whose referenced 3D LUT could not be loaded must be
 * refused before either renderer runs, so a look-changing gap is never published as a preview.
 */
class PreviewGraphAssetsTest {

    @Test
    fun shouldAcceptAGraphWithoutLut_whenNothingIsLoaded() {
        assertTrue(PreviewGraphAssets.isComplete(EditGraph(), lut = null))
    }

    @Test
    fun shouldRejectAGraphThatReferencesAMissingLut() {
        val graph = graphWithLut()

        assertFalse(PreviewGraphAssets.isComplete(graph, lut = null))
    }

    @Test
    fun shouldAcceptMissingLut_whenItsStrengthIsZero() {
        val graph = EditGraph(color = ColorOps(lut = LutRef(id = "portra-400", strength = 0)))

        assertTrue(PreviewGraphAssets.isComplete(graph, lut = null))
    }

    @Test
    fun shouldAcceptAGraphWhoseLutLoaded() {
        val graph = graphWithLut()

        assertTrue(PreviewGraphAssets.isComplete(graph, lut = samples()))
    }

    private fun graphWithLut(): EditGraph = EditGraph(
        color = ColorOps(lut = LutRef(id = "portra-400", strength = 100))
    )

    private fun samples(): Lut3dSamples {
        val size = 2
        return Lut3dSamples(size = size, rgba = FloatArray(size * size * size * 4) { 0.5f })
    }
}
