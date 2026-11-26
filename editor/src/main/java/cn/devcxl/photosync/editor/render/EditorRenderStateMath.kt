package cn.devcxl.photosync.editor.render

import cn.devcxl.photosync.editor.cloud.CloudGeometryDigest
import cn.devcxl.photosync.editor.domain.EditGraph

/**
 * Pure derived values of one preview frame.
 *
 * Kept out of [EditorGlRenderer] so the renderer stays a GL-thread binder and these rules — when the
 * 1024 px Pre-pass must re-run, and whether the cloud layer may be sampled at all — are individually
 * readable and testable.
 */
internal object EditorRenderStateMath {

    /**
     * Hash of every Pass A/B input the 1024 px Pre-pass depends on.
     *
     * A change means the guide pyramid must be rebuilt; an unchanged hash lets a slider drag reuse it.
     */
    fun prepassSignature(
        graph: EditGraph,
        warpActive: Boolean,
        faceWarp: FloatArray?,
        bodyWarp: FloatArray?
    ): Int {
        var hash = graph.baseLook.hashCode()
        hash = 31 * hash + graph.geometry.hashCode()
        hash = 31 * hash + graph.tone.hashCode()
        hash = 31 * hash + graph.color.copy(lut = null).hashCode()
        hash = 31 * hash + graph.effects.ca.hashCode()
        hash = 31 * hash + graph.subject.hashCode()
        hash = 31 * hash + warpActive.hashCode()
        hash = 31 * hash + (faceWarp?.contentHashCode() ?: 0)
        hash = 31 * hash + (bodyWarp?.contentHashCode() ?: 0)
        return hash
    }

    /**
     * Effective opacity of the Post-Pass A cloud layer for [graph].
     *
     * Returns `0` whenever the layer may not be sampled: no layer, a `geometryDigest` that no longer
     * matches the current framing, or a fully transparent slider. A zero value closes the shader
     * branch and the texture is never sampled.
     */
    fun cloudLayerOpacity(graph: EditGraph): Float {
        val layer = graph.cloudLayer ?: return 0.0f
        if (!CloudGeometryDigest.matches(layer, graph.geometry)) return 0.0f
        return layer.opacity.coerceIn(0, 100) / 100.0f
    }
}
