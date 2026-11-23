package cn.devcxl.photosync.editor.export

import cn.devcxl.photosync.editor.domain.ColumnBandTiling
import cn.devcxl.photosync.editor.domain.SourceSliceRect
import cn.devcxl.photosync.editor.render.OffscreenColorTarget
import cn.devcxl.photosync.editor.render.PrepassAnalyzer

/**
 * Runs the one-shot `1024px` Pre-pass a bake needs before its column bands.
 *
 * The Pre-pass must come from the full image, not from a band: dehaze's atmospheric light and the
 * Clarity/Texture guide pyramid are global quantities, and recomputing them per band is exactly the
 * seam the Pre-pass exists to prevent. The guide texture stays alive for the whole bake and is
 * released by [close].
 */
internal class BakePrepass(
    private val chain: BakePassChain,
    private val source: BakeSourceTextures
) : AutoCloseable {

    private val analyzer = PrepassAnalyzer(chain.capabilities)

    /**
     * Analyses the proxy and publishes the guide texture into [chain].
     *
     * @return True when the guide texture is available.
     */
    fun prepare(frame: BakeFrame, outputWidth: Int, outputHeight: Int): Boolean {
        if (!frame.spatialParams.needsPrepass) {
            chain.setPrepass(0, floatArrayOf(1.0f, 1.0f, 1.0f))
            return false
        }
        val aspect = outputWidth.toFloat() / outputHeight.toFloat()
        val ready = analyzer.ensureAnalysis(
            revision = PREPASS_REVISION,
            aspect = aspect,
            allowHalfFloat = false
        ) { target ->
            renderInto(chain, source, frame, target)
        }
        chain.setPrepass(
            textureId = if (ready) analyzer.textureId else 0,
            light = analyzer.atmosphericLight
        )
        return ready
    }

    /** Releases the Pre-pass guide texture. */
    override fun close() {
        analyzer.release()
    }

    private fun renderInto(
        chain: BakePassChain,
        source: BakeSourceTextures,
        frame: BakeFrame,
        target: OffscreenColorTarget
    ): Boolean {
        val band = ColumnBandTiling.fullFrame(target.width, target.height)
        val proxyFrame = frame.copy(
            sourceTextureId = source.proxyTexture,
            srcSliceRect = SourceSliceRect.FULL,
            band = band
        )
        return chain.renderPrepassFrame(target, proxyFrame)
    }

    private companion object {
        /** A bake analyses exactly one graph revision, so any constant is a valid cache key. */
        const val PREPASS_REVISION = 1
    }
}
