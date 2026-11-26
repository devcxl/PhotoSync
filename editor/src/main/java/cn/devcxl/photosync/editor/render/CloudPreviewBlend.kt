package cn.devcxl.photosync.editor.render

import timber.log.Timber

import cn.devcxl.photosync.editor.cloud.CloudGeometryDigest
import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.LinearCloudLayer

/**
 * Resolves the graph's `CloudEditLayer` for the CPU preview paths.
 *
 * The GPU preview, the offscreen save render and the full-resolution bake all sample the layer in
 * the Pass B shader, so the CPU reference implementation must mount it at the same place: after the
 * Pass A resample and before `BaseLook` / white balance / tone / display colour. Every entry point
 * therefore asks this object for frame-sized pixels instead of blending after Pass D.
 *
 * A missing asset, a failed SHA-256 check or a `geometryDigest` that no longer matches the current
 * framing degrades to `null`, which is exactly what the shader does when it closes the cloud branch.
 */
internal object CloudPreviewBlend {

    /** True when [graph] has a layer that was generated for its current Pass A framing. */
    fun isBound(graph: EditGraph): Boolean {
        val layer = graph.cloudLayer ?: return false
        return CloudGeometryDigest.matches(layer, graph.geometry)
    }

    /**
     * Decodes and resamples the layer for a `width x height` frame.
     *
     * @param assets Asset store of the photo directory, or `null` to skip the layer entirely.
     */
    fun prepare(
        assets: AiAssetStore?,
        graph: EditGraph,
        width: Int,
        height: Int
    ): CloudLayerPixels? {
        val layer = graph.cloudLayer ?: return null
        if (assets == null) return null
        if (width <= 0 || height <= 0) return null
        if (!CloudGeometryDigest.matches(layer, graph.geometry)) {
            Timber.w("Preview skips the cloud layer: geometry digest mismatch")
            return null
        }
        val blender = CloudLayerBlender(assets)
        return when (val loaded = blender.prepare(layer, graph.geometry, width, height)) {
            is CloudLayerLoad.Ready -> loaded.pixels
            is CloudLayerLoad.NotApplied -> {
                Timber.w("Preview skips the cloud layer: %s", loaded.status)
                null
            }
        }
    }

    /** Same as [prepare] but converted to the scene-linear encoding the 16-bit path grades. */
    fun prepareLinear(
        assets: AiAssetStore?,
        graph: EditGraph,
        width: Int,
        height: Int
    ): LinearCloudLayer? = prepare(assets, graph, width, height)?.toLinear()

    /**
     * Blends the layer into a display-referred ARGB frame, in place.
     *
     * @return True when pixels were actually replaced.
     */
    fun blendIntoArgb(
        assets: AiAssetStore?,
        graph: EditGraph,
        pixels: IntArray,
        width: Int,
        height: Int
    ): Boolean {
        val store = assets ?: return false
        val prepared = prepare(store, graph, width, height) ?: return false
        return CloudLayerBlender(store).blendPrepared(pixels, width, height, prepared).isApplied
    }
}
