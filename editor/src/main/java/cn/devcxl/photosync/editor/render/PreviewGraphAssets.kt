package cn.devcxl.photosync.editor.render

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.Lut3dSamples

/**
 * Graph assets a preview render cannot synthesise by itself.
 *
 * An active 3D LUT changes the look of the frame, so both preview paths refuse to publish when its
 * samples are missing. A zero-strength LUT does not affect the image and needs no samples.
 */
internal object PreviewGraphAssets {

    /**
     * True when every asset referenced by [graph] is available for rendering.
     *
     * @param lut The samples loaded for `graph.color.lut`, or `null` when loading was not attempted or
     *   failed.
     */
    fun isComplete(graph: EditGraph, lut: Lut3dSamples?): Boolean {
        val reference = graph.color.lut
        return reference == null || reference.strength == 0 || lut != null
    }
}
