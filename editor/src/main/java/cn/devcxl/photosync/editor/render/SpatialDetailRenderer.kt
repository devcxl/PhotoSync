package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import android.opengl.GLES30

import cn.devcxl.photosync.editor.domain.PassDEffectsMath
import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.SpatialDetailPlanner

/**
 * Renders physical Pass C (1024px Pre-pass + spatial-detail ping-pong chain) into an offscreen
 * texture, and can optionally present that texture when Pass D is inactive.
 *
 * When the plan resolves to zero stages, [renderToTexture] returns `0` without touching a
 * framebuffer so the caller maintains the **0 FBO Draw Call** guarantee.
 */
internal class SpatialDetailRenderer(private val capabilities: GlCapabilities) {

    private val chain = SpatialDetailGlChain(capabilities)
    private val analyzer = PrepassAnalyzer(capabilities)
    private val passBTarget = OffscreenColorTarget(capabilities)
    private val presentProgram = PresentGlProgram()

    private var chainReady: Boolean = false

    /** FBO draw calls issued by the last [renderToTexture] call. */
    var lastDrawCalls: Int = 0
        private set

    /** True when the Pass C program and the presentation program both compiled. */
    val isReady: Boolean
        get() = chainReady && presentProgram.isReady

    /** Runs the Pass C GL setup after a surface (re)creation. */
    fun init() {
        release()
        passBTarget.resetDisableFlag()
        chainReady = chain.init()
        if (!presentProgram.init()) capabilities.markMinimalFallback("PresentGlProgram")
        lastDrawCalls = 0
    }

    /** Releases every GL object owned by the chain. */
    fun release() {
        chain.release()
        analyzer.release()
        passBTarget.release()
        presentProgram.release()
        chainReady = false
        lastDrawCalls = 0
    }

    /** Forces the next frame to recompute the 1024px Pre-pass. */
    fun invalidatePrepass() = analyzer.invalidate()

    /**
     * Runs the Pass C chain for [params] and returns the output texture id, or `0` if skipped.
     */
    fun renderToTexture(
        aspect: Float,
        params: SpatialDetailParams,
        revision: Int,
        targetWidth: Int,
        targetHeight: Int,
        fullResolutionShortEdge: Int,
        skinMaskTextureId: Int = 0,
        allowHalfFloat: Boolean,
        subjectBokeh: SubjectBokehDrawParams? = null,
        drawPassB: (OffscreenColorTarget) -> Boolean
    ): Int {
        lastDrawCalls = 0
        val requested = SpatialDetailPlanner.plan(
            params,
            prepassAvailable = true,
            skinMaskAvailable = skinMaskTextureId != 0,
            subjectMaskAvailable = subjectBokeh != null
        )
        if (requested.stages.isEmpty() || !isReady) return 0
        val prepassReady = !requested.prepassRequired ||
            analyzer.ensureAnalysis(revision, aspect, allowHalfFloat) { target ->
                drawPassB(target)
            }
        val resolved = SpatialDetailPlanner.plan(
            params,
            prepassAvailable = prepassReady,
            skinMaskAvailable = skinMaskTextureId != 0,
            subjectMaskAvailable = subjectBokeh != null
        )
        if (resolved.stages.isEmpty()) return 0
        passBTarget.ensureSize(targetWidth, targetHeight, allowHalfFloat)
        if (!passBTarget.isReady || !drawPassB(passBTarget)) return 0
        val output = chain.run(
            inputTextureId = passBTarget.textureId,
            width = targetWidth,
            height = targetHeight,
            fullResolutionShortEdge = fullResolutionShortEdge,
            params = params,
            plan = resolved,
            prepassTextureId = analyzer.textureId,
            skinMaskTextureId = skinMaskTextureId,
            atmosphericLight = analyzer.atmosphericLight,
            subjectBokeh = subjectBokeh
        )
        lastDrawCalls = chain.lastDrawCalls
        return output
    }

    /** Presents [textureId] onto the default 8-bit framebuffer with `1/255` triangular dither. */
    fun present(
        textureId: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float
    ) {
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        presentProgram.draw(textureId, scaleX, scaleY, offsetX, offsetY, PassDEffectsMath.DITHER_AMPLITUDE)
    }
}
