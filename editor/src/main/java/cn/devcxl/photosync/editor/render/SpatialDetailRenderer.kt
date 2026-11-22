package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import android.opengl.GLES30

import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.SpatialDetailPlanner

/**
 * Renders physical Pass C and presents its result.
 *
 * Responsibilities:
 * 1. Render `Pass B` into an offscreen target so the chain can sample real neighbours at the frame
 *    border instead of the viewport clear colour.
 * 2. On the first frame of a new graph revision, render the same `Pass B` at a `1024px` max edge and
 *    hand it to [PrepassAnalyzer] to extract `A`, `t_low` and the guide pyramid.
 * 3. Run the planned [SpatialDetailStage] sub-passes through [SpatialDetailGlChain].
 * 4. Present the chain output to the default framebuffer with the canvas letterbox transform.
 *
 * When the plan resolves to zero stages, [render] returns false without touching a framebuffer, so
 * the renderer can fall through to its plain `Pass B` draw.
 */
internal class SpatialDetailRenderer {

    private val chain = SpatialDetailGlChain()
    private val analyzer = PrepassAnalyzer()
    private val passBTarget = PassAFrameBuffer()
    private val presentProgram = PresentGlProgram()

    private var chainReady: Boolean = false

    /** FBO draw calls issued by the last [render] call. */
    var lastDrawCalls: Int = 0
        private set

    /** True when the Pass C program and the presentation program both compiled. */
    val isReady: Boolean
        get() = chainReady && presentProgram.isReady

    /** Runs the Pass C GL setup after a surface (re)creation. */
    fun init() {
        release()
        chainReady = chain.init()
        presentProgram.init()
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
     * Renders the Pass C chain for [params] and presents the result.
     *
     * @param input `Pass A` output the chain's `Pass B` feed samples.
     * @param revision Monotonic graph revision that keys the cached Pre-pass analysis.
     * @param drawPassB Renders `Pass B` into the supplied target at full bleed (no letterbox).
     * @return True when a frame was presented; false when the plan was empty or a target failed.
     */
    fun render(
        input: PassAOutput,
        params: SpatialDetailParams,
        revision: Int,
        targetWidth: Int,
        targetHeight: Int,
        viewportWidth: Int,
        viewportHeight: Int,
        scaleX: Float,
        scaleY: Float,
        offsetX: Float,
        offsetY: Float,
        allowHalfFloat: Boolean,
        drawPassB: (PassAFrameBuffer, Int, Int) -> Boolean
    ): Boolean {
        lastDrawCalls = 0
        val requested = SpatialDetailPlanner.plan(params, prepassAvailable = true)
        if (requested.stages.isEmpty() || !isReady) return false
        passBTarget.ensureSize(targetWidth, targetHeight, allowHalfFloat)
        if (!passBTarget.isReady) return false
        if (!drawPassB(passBTarget, targetWidth, targetHeight)) return false
        val prepassReady = !requested.prepassRequired ||
            analyzer.ensureAnalysis(revision, input.aspect, allowHalfFloat) { target ->
                drawPassB(target, target.width, target.height)
            }
        val resolved = SpatialDetailPlanner.plan(params, prepassAvailable = prepassReady)
        if (resolved.stages.isEmpty()) return false
        val output = chain.run(
            inputTextureId = passBTarget.textureId,
            width = targetWidth,
            height = targetHeight,
            params = params,
            plan = resolved,
            prepassTextureId = analyzer.textureId,
            atmosphericLight = analyzer.atmosphericLight
        )
        lastDrawCalls = chain.lastDrawCalls
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        presentProgram.draw(output, scaleX, scaleY, offsetX, offsetY)
        return true
    }
}
