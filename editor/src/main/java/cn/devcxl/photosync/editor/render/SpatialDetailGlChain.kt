package cn.devcxl.photosync.editor.render

import android.opengl.GLES20
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.SpatialDetailPlan
import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.SpatialDetailStage

/**
 * Physical `SpatialDetailChain`: runs the planned Pass C sub-passes through a two-target ping-pong so
 * every stage reads a complete frame and never its own write.
 *
 * The chain reports [lastDrawCalls] after each frame, which is the number the renderer publishes as
 * its observable draw-call counter. A fully short-circuited plan issues zero draw calls and hands the
 * input texture straight back, so the default "nothing enabled" path costs exactly nothing.
 */
internal class SpatialDetailGlChain {

    private val program = SpatialDetailGlProgram()
    private val pingPong = arrayOf(PassAFrameBuffer(), PassAFrameBuffer())

    /** FBO draw calls issued by the most recent [run]. */
    var lastDrawCalls: Int = 0
        private set

    /** True when the Pass C shader compiled on this driver. */
    val isReady: Boolean
        get() = program.isReady

    /** Compiles the Pass C program; returns false when the driver rejects it (Tier 2/3). */
    fun init(): Boolean {
        lastDrawCalls = 0
        return program.init()
    }

    /** Releases every GL object owned by the chain. */
    fun release() {
        program.release()
        pingPong.forEach { it.release() }
        lastDrawCalls = 0
    }

    /**
     * Executes [plan] against [inputTextureId].
     *
     * @return The texture that now holds the Pass C output, or [inputTextureId] when nothing ran.
     */
    fun run(
        inputTextureId: Int,
        width: Int,
        height: Int,
        params: SpatialDetailParams,
        plan: SpatialDetailPlan,
        prepassTextureId: Int,
        atmosphericLight: FloatArray
    ): Int {
        lastDrawCalls = 0
        if (!program.isReady || inputTextureId == 0 || plan.stages.isEmpty()) return inputTextureId
        var current = inputTextureId
        for ((index, stage) in plan.stages.withIndex()) {
            val target = pingPong[index % pingPong.size]
            target.ensureSize(width, height, allowHalfFloat = true)
            if (!target.isReady) {
                Timber.w("Pass C target unavailable; spatial detail stops at stage %s", stage)
                return current
            }
            target.bind()
            program.draw(
                SpatialDetailDrawParams(
                    stage = stage,
                    inputTextureId = current,
                    prepassTextureId = if (needsPrepass(stage)) prepassTextureId else 0,
                    dehazeStrength = params.dehaze,
                    atmosphericLight = atmosphericLight,
                    clarity = params.clarity,
                    texture = params.texture,
                    nrLuma = params.nrLuma,
                    nrChroma = params.nrChroma,
                    nrDetail = params.nrDetail,
                    sharpenAmount = params.sharpenAmount,
                    sharpenRadiusPx = params.sharpenRadiusPx,
                    sharpenThreshold = params.sharpenThreshold,
                    sharpenMasking = params.sharpenMasking,
                    texelWidth = width,
                    texelHeight = height
                )
            )
            target.unbind()
            current = target.textureId
            lastDrawCalls++
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        return current
    }

    private fun needsPrepass(stage: SpatialDetailStage): Boolean =
        stage == SpatialDetailStage.DEHAZE || stage == SpatialDetailStage.CLARITY_TEXTURE
}
