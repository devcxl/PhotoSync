package cn.devcxl.photosync.editor.export

import android.opengl.GLES20

import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.ColorGradingParams
import cn.devcxl.photosync.editor.domain.ColumnBand
import cn.devcxl.photosync.editor.domain.CurveOps
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.PassDEffectsMath
import cn.devcxl.photosync.editor.domain.PassDEffectsParams
import cn.devcxl.photosync.editor.domain.SourceSliceRect
import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.SpatialDetailPlanner
import cn.devcxl.photosync.editor.domain.TileUvTransform
import cn.devcxl.photosync.editor.domain.ToneShaderParams
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.lut.Lut3dTexture
import cn.devcxl.photosync.editor.render.CurveLutTexture
import cn.devcxl.photosync.editor.render.EffectsDrawParams
import cn.devcxl.photosync.editor.render.EffectsOutputGlProgram
import cn.devcxl.photosync.editor.render.ExposureDrawParams
import cn.devcxl.photosync.editor.render.ExposureGlProgram
import cn.devcxl.photosync.editor.render.GeometryCaGlProgram
import cn.devcxl.photosync.editor.render.GeometryDrawParams
import cn.devcxl.photosync.editor.render.GlCapabilities
import cn.devcxl.photosync.editor.render.OffscreenColorTarget
import cn.devcxl.photosync.editor.render.SkinMaskTexture
import cn.devcxl.photosync.editor.render.SpatialDetailGlChain
import cn.devcxl.photosync.editor.render.WarpTexture

/** Every graph-derived uniform one bake band needs, plus the band geometry and source binding. */
internal data class BakeFrame(
    val sourceTextureId: Int,
    val srcSliceRect: SourceSliceRect,
    val band: ColumnBand,
    val srcAspect: Float,
    val isLinear: Boolean,
    val autoBrightGain: Float,
    val columnMajorInverseMatrix: FloatArray,
    val baseLook: BaseLook,
    val toneParams: ToneShaderParams,
    val colorParams: ColorGradingParams,
    val spatialParams: SpatialDetailParams,
    val effectsParams: PassDEffectsParams,
    val caRedK: Float,
    val caBlueK: Float,
    val cropWidth: Int,
    val cropHeight: Int,
    val fullResolutionShortEdge: Int
)

/**
 * Runs the physical `Pass A -> Pass B -> Pass C -> Pass D` chain for one bake band.
 *
 * Uses the exact same shader programs as the preview, including the unified `uWarpTex`
 * (face MLS + body + liquify) and `uSkinMaskTex` (Pass C skin smoothing + sharpening suppression).
 */
internal class BakePassChain {

    private val geometryProgram = GeometryCaGlProgram()
    private val exposureProgram = ExposureGlProgram()
    private val spatialChain = SpatialDetailGlChain()
    private val effectsProgram = EffectsOutputGlProgram()
    private val warpTexture = WarpTexture()
    private val skinMaskTexture = SkinMaskTexture()
    private val curveLutTexture = CurveLutTexture()
    private val lutTexture = Lut3dTexture()

    private val geometryTarget = OffscreenColorTarget()
    private val passBTarget = OffscreenColorTarget()
    private val outputTarget = OffscreenColorTarget()

    private var prepassTextureId: Int = 0
    private var atmosphericLight: FloatArray = floatArrayOf(1.0f, 1.0f, 1.0f)

    /** True when every required program compiled on this driver. */
    var isReady: Boolean = false
        private set

    /** Compiles the programs and probes capabilities. Must run with the EGL context current. */
    fun init(): Boolean {
        GlCapabilities.invalidate()
        GlCapabilities.refresh()
        val passA = geometryProgram.init()
        exposureProgram.init(preferEs3 = true)
        val passC = spatialChain.init()
        val passD = effectsProgram.init()
        warpTexture.stage(null)
        warpTexture.uploadIfNeeded()
        skinMaskTexture.stage(null)
        skinMaskTexture.uploadIfNeeded()
        isReady = passA && passC && passD && GlCapabilities.supportsHalfFloatTargets
        return isReady
    }

    /** Stages the unified warp grid, optional `skinMask`, 1D tone curve and 3D LUT on GL thread. */
    fun stageGraph(
        warpField: WarpDisplacementField?,
        skinMask: FloatArray?,
        graphCurve: CurveOps,
        lut: Lut3dSamples?
    ) {
        warpTexture.stage(warpField)
        warpTexture.uploadIfNeeded()
        skinMaskTexture.stage(skinMask)
        skinMaskTexture.uploadIfNeeded()
        curveLutTexture.stage(graphCurve)
        curveLutTexture.uploadIfNeeded(preferEs3 = true)
        lutTexture.stage(lut)
        lutTexture.uploadIfNeeded(preferEs3 = true)
    }

    /** Publishes the Pre-pass guide texture and atmospheric light used by Pass C. */
    fun setPrepass(textureId: Int, light: FloatArray) {
        prepassTextureId = textureId
        atmosphericLight = light
    }

    /** Renders the `Pass A + Pass B` frame of [frame] into [target]; used by the 1024px Pre-pass. */
    fun renderPrepassFrame(target: OffscreenColorTarget, frame: BakeFrame): Boolean {
        if (!drawPassA(frame, geometryTarget)) return false
        target.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        exposureProgram.draw(
            passBParams(
                frame = frame,
                textureId = geometryTarget.textureId,
                texelWidth = target.width,
                texelHeight = target.height,
                ditherAmp = if (target.isHalfFloatTarget) 0.0f else PassDEffectsMath.DITHER_AMPLITUDE
            )
        )
        target.unbind()
        return true
    }

    /** Renders one band into [outputTarget] and returns its framebuffer, or `0` on failure. */
    fun renderBand(frame: BakeFrame): Int {
        val width = frame.band.fboWidth
        val height = frame.band.fboHeight
        if (!drawPassA(frame, geometryTarget)) return 0
        passBTarget.ensureSize(width, height, allowHalfFloat = true)
        if (!passBTarget.isReady) return 0
        passBTarget.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        exposureProgram.draw(
            passBParams(frame, geometryTarget.textureId, width, height, ditherAmp = 0.0f)
        )
        passBTarget.unbind()

        val detailTexture = renderSpatialDetail(frame, width, height)
        outputTarget.ensureSize(width, height, allowHalfFloat = false)
        if (!outputTarget.isReady) return 0
        outputTarget.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        effectsProgram.draw(
            EffectsDrawParams(
                textureId = if (detailTexture != 0) detailTexture else passBTarget.textureId,
                lutTextureId = if (lutTexture.isReady) lutTexture.textureId else 0,
                compareOriginal = false,
                scaleX = 1.0f,
                scaleY = 1.0f,
                offsetX = 0.0f,
                offsetY = 0.0f,
                effects = frame.effectsParams.withLutSize(lutTexture.sampleSize),
                ditherAmp = PassDEffectsMath.DITHER_AMPLITUDE,
                tileUv = frame.band.tileUv,
                pixelScaleX = frame.cropWidth.toFloat(),
                pixelScaleY = frame.cropHeight.toFloat()
            )
        )
        outputTarget.unbind()
        return outputTarget.framebuffer
    }

    /** Releases every GL object; safe to call when the context is already gone. */
    fun close() {
        geometryProgram.release()
        spatialChain.release()
        effectsProgram.release()
        warpTexture.release()
        skinMaskTexture.release()
        curveLutTexture.release()
        lutTexture.release()
        geometryTarget.release()
        passBTarget.release()
        outputTarget.release()
        isReady = false
    }

    private fun drawPassA(frame: BakeFrame, target: OffscreenColorTarget): Boolean {
        target.ensureSize(frame.band.fboWidth, frame.band.fboHeight, allowHalfFloat = true)
        if (!target.isReady) return false
        target.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        geometryProgram.draw(
            GeometryDrawParams(
                sourceTextureId = frame.sourceTextureId,
                columnMajorInverseMatrix = frame.columnMajorInverseMatrix,
                caRedK = frame.caRedK,
                caBlueK = frame.caBlueK,
                srcAspect = frame.srcAspect,
                encodeSrgbWithDither = false,
                warpTextureId = if (warpTexture.isWarpActive) warpTexture.textureId else 0,
                warpDecodeScale = warpTexture.decodeScale,
                warpDecodeOffset = warpTexture.decodeOffset,
                srcSliceRect = frame.srcSliceRect,
                tileUv = frame.band.tileUv
            )
        )
        target.unbind()
        return true
    }

    private fun renderSpatialDetail(frame: BakeFrame, width: Int, height: Int): Int {
        val plan = SpatialDetailPlanner.plan(
            params = frame.spatialParams,
            prepassAvailable = prepassTextureId != 0,
            skinMaskAvailable = skinMaskTexture.isActive
        )
        if (plan.stages.isEmpty()) return 0
        return spatialChain.run(
            inputTextureId = passBTarget.textureId,
            width = width,
            height = height,
            fullResolutionShortEdge = frame.fullResolutionShortEdge,
            params = frame.spatialParams,
            plan = plan,
            prepassTextureId = prepassTextureId,
            skinMaskTextureId = if (skinMaskTexture.isActive) skinMaskTexture.textureId else 0,
            atmosphericLight = atmosphericLight,
            guideTransform = frame.band.tileUv
        )
    }

    private fun passBParams(
        frame: BakeFrame,
        textureId: Int,
        texelWidth: Int,
        texelHeight: Int,
        ditherAmp: Float
    ): ExposureDrawParams = ExposureDrawParams(
        textureId = textureId,
        compareOriginal = false,
        scaleX = 1.0f,
        scaleY = 1.0f,
        offsetX = 0.0f,
        offsetY = 0.0f,
        isLinearInput = frame.isLinear,
        baseLook = frame.baseLook,
        autoBrightGain = frame.autoBrightGain,
        toneParams = frame.toneParams,
        colorParams = frame.colorParams,
        curveTextureId = curveLutTexture.textureId,
        texelWidth = texelWidth,
        texelHeight = texelHeight,
        ditherAmp = ditherAmp
    )
}
