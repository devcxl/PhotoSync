package cn.devcxl.photosync.editor.render

import kotlin.math.min

import android.opengl.GLES20
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.PassDEffectsMath
import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.SpatialDetailPlan
import cn.devcxl.photosync.editor.domain.SpatialDetailStage
import cn.devcxl.photosync.editor.domain.TileUvTransform

/**
 * Physical `SpatialDetailChain`: runs the planned Pass C sub-passes through a two-target ping-pong so
 * every stage reads a complete frame and never its own write.
 *
 * Scales `sharpenRadiusPx` by `workingShortEdge / fullResolutionShortEdge` to match the full-res
 * Bake and CPU preview cache, and applies `1/255` triangular dithering whenever writing to a Tier 2
 * `GL_RGBA8` target.
 */
internal class SpatialDetailGlChain {

    private val program = SpatialDetailGlProgram()
    private val pingPong = arrayOf(OffscreenColorTarget(), OffscreenColorTarget())

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
        fullResolutionShortEdge: Int,
        params: SpatialDetailParams,
        plan: SpatialDetailPlan,
        prepassTextureId: Int,
        atmosphericLight: FloatArray,
        guideTransform: TileUvTransform = TileUvTransform.IDENTITY
    ): Int {
        lastDrawCalls = 0
        if (!program.isReady || inputTextureId == 0 || plan.stages.isEmpty()) return inputTextureId
        val resolutionScale = (min(width, height).toFloat() / fullResolutionShortEdge.coerceAtLeast(1).toFloat())
            .coerceIn(MIN_RESOLUTION_SCALE, 1.0f)
        val scaledRadiusPx = (params.sharpenRadiusPx * resolutionScale)
            .coerceIn(SpatialDetailParams.MIN_SHARPEN_RADIUS_PX, SpatialDetailParams.MAX_SHARPEN_RADIUS_PX)
        var current = inputTextureId
        for ((index, stage) in plan.stages.withIndex()) {
            val target = pingPong[index % pingPong.size]
            target.ensureSize(width, height, allowHalfFloat = true)
            if (!target.isReady) {
                Timber.w("Pass C target unavailable; spatial detail stops at stage %s", stage)
                return current
            }
            val ditherAmp = if (target.isHalfFloatTarget) 0.0f else PassDEffectsMath.DITHER_AMPLITUDE
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
                    sharpenRadiusPx = scaledRadiusPx,
                    sharpenThreshold = params.sharpenThreshold,
                    sharpenMasking = params.sharpenMasking,
                    texelWidth = width,
                    texelHeight = height,
                    ditherAmp = ditherAmp,
                    guideUvScaleX = guideTransform.scaleX,
                    guideUvScaleY = guideTransform.scaleY,
                    guideUvOffsetX = guideTransform.offsetX,
                    guideUvOffsetY = guideTransform.offsetY
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

    private companion object {
        private const val MIN_RESOLUTION_SCALE = 0.05f
    }
}
