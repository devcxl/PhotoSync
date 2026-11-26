package cn.devcxl.photosync.editor.export

import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.opengl.GLES20
import timber.log.Timber

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
import cn.devcxl.photosync.editor.domain.SubjectBokehMath
import cn.devcxl.photosync.editor.domain.SubjectOps
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
import cn.devcxl.photosync.editor.render.SubjectBokehDrawParams
import cn.devcxl.photosync.editor.render.SubjectDrawParams
import cn.devcxl.photosync.editor.render.SubjectMaskTexture
import cn.devcxl.photosync.editor.render.SubjectRenderPlan
import cn.devcxl.photosync.editor.render.SubjectWeightTexture
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
    val fullResolutionShortEdge: Int,
    val maxWarpDisplacementUv: Float = 0.0f,
    val subjectOps: SubjectOps = SubjectOps()
)

/**
 * Runs the physical `Pass A -> Pass B -> Pass C -> Pass D` chain for one bake band.
 *
 * Uses the exact same shader programs as the preview, including the unified `uWarpTex`
 * (face MLS + body + liquify) and `uSkinMaskTex` (Pass C skin smoothing + sharpening suppression).
 */
internal class BakePassChain {

    /**
     * Capabilities of this bake's headless EGL context.
     *
     * Scoped to the chain so a bake can never raise Tier 3 for the editor preview (or inherit the
     * preview's tier) even when both run at the same time.
     */
    internal val capabilities = GlCapabilities()

    private val geometryProgram = GeometryCaGlProgram()
    private val exposureProgram = ExposureGlProgram()
    private val spatialChain = SpatialDetailGlChain(capabilities)
    private val effectsProgram = EffectsOutputGlProgram()
    private val copyProgram = EffectsOutputGlProgram()
    private val warpTexture = WarpTexture()
    private val skinMaskTexture = SkinMaskTexture()
    private val subjectTexture = SubjectMaskTexture()
    private val subjectWeightTexture = SubjectWeightTexture()
    private val curveLutTexture = CurveLutTexture()
    private val lutTexture = Lut3dTexture()

    private val geometryTarget = OffscreenColorTarget(capabilities)
    private val passBTarget = OffscreenColorTarget(capabilities)
    private val subjectInputTarget = OffscreenColorTarget(capabilities)
    private val outputTarget = OffscreenColorTarget(capabilities)

    private var subjectSupport: BakeSubjectSupport? = null
    private var subjectPlan: SubjectRenderPlan? = null
    private var subjectReadback: ByteBuffer? = null

    private var prepassTextureId: Int = 0
    private var atmosphericLight: FloatArray = floatArrayOf(1.0f, 1.0f, 1.0f)

    /** True when every required program compiled on this driver. */
    var isReady: Boolean = false
        private set

    /** Compiles the programs and probes capabilities. Must run with the EGL context current. */
    fun init(): Boolean {
        capabilities.reset()
        capabilities.refresh()
        val passA = geometryProgram.init()
        exposureProgram.init(preferEs3 = true)
        val passC = spatialChain.init()
        val passD = effectsProgram.init()
        val copy = copyProgram.init()
        if (!passA) capabilities.markMinimalFallback("Pass A GeometryCa")
        if (!passD) capabilities.markMinimalFallback("Pass D effects shader")
        warpTexture.stage(null)
        warpTexture.uploadIfNeeded()
        skinMaskTexture.stage(null)
        skinMaskTexture.uploadIfNeeded()
        subjectTexture.stage(null)
        subjectTexture.uploadIfNeeded()
        subjectWeightTexture.stage(null, 0, 0)
        subjectWeightTexture.uploadIfNeeded()
        isReady = passA && passC && passD && copy
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

    /**
     * Installs the per-band subject compositor and its shader plan.
     *
     * @param support `null` when the graph's subject group is neutral.
     */
    fun setSubject(support: BakeSubjectSupport?, plan: SubjectRenderPlan?) {
        subjectSupport = support
        subjectPlan = plan
    }

    /** Renders the `Pass A + Pass B` frame of [frame] into [target]; used by the 1024px Pre-pass. */
    fun renderPrepassFrame(target: OffscreenColorTarget, frame: BakeFrame): Boolean {
        if (!drawPassA(frame, geometryTarget)) return false
        target.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val linearPassBInput = frame.isLinear && geometryTarget.isHalfFloatTarget
        exposureProgram.draw(
            passBParams(
                frame = frame,
                textureId = geometryTarget.textureId,
                texelWidth = target.width,
                texelHeight = target.height,
                isLinearInput = linearPassBInput,
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
        stageSubjectBandMask(frame, width, height)
        passBTarget.ensureSize(width, height, allowHalfFloat = true, exactSize = true)
        if (!passBTarget.isReady) return 0
        passBTarget.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val ditherPassB = if (passBTarget.isHalfFloatTarget) 0.0f else PassDEffectsMath.DITHER_AMPLITUDE
        val linearPassBInput = frame.isLinear && geometryTarget.isHalfFloatTarget
        exposureProgram.draw(
            passBParams(
                frame, geometryTarget.textureId, width, height, linearPassBInput, ditherPassB,
                subject = frame.subjectOps,
                subjectWeightTextureId = if (subjectWeightTexture.isActive) {
                    subjectWeightTexture.textureId
                } else {
                    0
                },
                subjectLocalSpace = true
            )
        )
        passBTarget.unbind()

        val detailTexture = renderSpatialDetail(frame, width, height)
        val subject = composeSubject(frame, detailTexture = detailTexture, width = width, height = height)
        outputTarget.ensureSize(width, height, allowHalfFloat = false, exactSize = true)
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
                pixelScaleY = frame.cropHeight.toFloat(),
                subject = subject
            )
        )
        outputTarget.unbind()
        return outputTarget.framebuffer
    }

    /** Releases every GL object; safe to call when the context is already gone. */
    fun close() {
        geometryProgram.release()
        exposureProgram.release()
        spatialChain.release()
        effectsProgram.release()
        copyProgram.release()
        warpTexture.release()
        skinMaskTexture.release()
        subjectTexture.release()
        subjectWeightTexture.release()
        curveLutTexture.release()
        lutTexture.release()
        geometryTarget.release()
        passBTarget.release()
        subjectInputTarget.release()
        outputTarget.release()
        subjectReadback = null
        subjectSupport = null
        subjectPlan = null
        // The headless context is destroyed right after this call, so nothing stays cached.
        capabilities.reset()
        isReady = false
    }

    private fun composeSubject(
        frame: BakeFrame,
        detailTexture: Int,
        width: Int,
        height: Int
    ): SubjectDrawParams? {
        val plan = subjectPlan ?: return null
        val support = subjectSupport ?: return null
        val inputTexture = if (detailTexture != 0) detailTexture else passBTarget.textureId
        val pixels = copyAndReadBand(inputTexture, width, height)
        val composite = if (pixels != null) {
            support.composeBand(pixels, width, height, frame.band)
        } else {
            Timber.w("Subject bake band read-back failed; using the feathered proxy composite")
            support.composeFallbackBand(width, height, frame.band)
        } ?: return null
        subjectTexture.stageRgba(composite.toRgba8(), width, height)
        subjectTexture.uploadIfNeeded()
        if (!subjectTexture.isActive) return null
        return SubjectDrawParams(
            textureId = subjectTexture.textureId,
            mode = plan.shaderMode,
            solidColorRgb = plan.solidColorRgb,
            localSpace = true,
            uvMatrix = IDENTITY_MATRIX,
            checkerboard = false
        )
    }

    /**
     * Blits [inputTextureId] into an `RGBA8` target and reads the band back.
     *
     * Pass C may ping-pong through `RGBA16F` targets; reading a half-float FBO with
     * `GL_UNSIGNED_BYTE` is invalid, so a dedicated 8-bit copy keeps the readback well-defined and
     * gives the CPU compositor the exact display-referred pixels the shader would sample.
     */
    private fun copyAndReadBand(inputTextureId: Int, width: Int, height: Int): IntArray? {
        return try {
            subjectInputTarget.ensureSize(width, height, allowHalfFloat = false, exactSize = true)
            if (!subjectInputTarget.isReady) return null
            subjectInputTarget.bind()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            copyProgram.draw(
                EffectsDrawParams(
                    textureId = inputTextureId,
                    lutTextureId = 0,
                    compareOriginal = false,
                    scaleX = 1.0f,
                    scaleY = 1.0f,
                    offsetX = 0.0f,
                    offsetY = 0.0f,
                    effects = PassDEffectsParams(),
                    ditherAmp = 0.0f,
                    flipVerticalForScreen = false
                )
            )
            val buffer = subjectReadback?.takeIf { it.capacity() >= width * height * BYTES_PER_RGBA }
                ?: ByteBuffer
                    .allocateDirect(width * height * BYTES_PER_RGBA)
                    .order(ByteOrder.nativeOrder())
                    .also { subjectReadback = it }
            buffer.rewind()
            GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, DEFAULT_PACK_ALIGNMENT)
            subjectInputTarget.unbind()
            if (GLES20.glGetError() != GLES20.GL_NO_ERROR) return null
            val pixels = IntArray(width * height)
            buffer.rewind()
            for (index in pixels.indices) {
                val red = buffer.get().toInt() and 0xFF
                val green = buffer.get().toInt() and 0xFF
                val blue = buffer.get().toInt() and 0xFF
                val alpha = buffer.get().toInt() and 0xFF
                pixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
            pixels
        } catch (oom: OutOfMemoryError) {
            subjectInputTarget.unbind()
            Timber.e(oom, "OOM while reading a subject bake band back")
            null
        }
    }

    private fun drawPassA(frame: BakeFrame, target: OffscreenColorTarget): Boolean {
        target.ensureSize(frame.band.fboWidth, frame.band.fboHeight, allowHalfFloat = true, exactSize = true)
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
                encodeSrgbWithDither = frame.isLinear && !target.isHalfFloatTarget,
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
            skinMaskAvailable = skinMaskTexture.isActive,
            subjectMaskAvailable = subjectWeightTexture.isActive
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
            guideTransform = frame.band.tileUv,
            subjectBokeh = bokehParams(frame, width, height)
        )
    }

    /**
     * Stages the coarse band alpha before Pass B and Pass C run.
     *
     * The refined Pass D composite needs the graded band pixels and is built later; the relative
     * grade and bokeh only need the model/guided mask, and mapping it through the band's global
     * `tileUv` keeps every band consistent with its neighbours.
     */
    private fun stageSubjectBandMask(frame: BakeFrame, width: Int, height: Int) {
        val support = subjectSupport
        val needsWeight = frame.subjectOps.hasGradingAdjustments() ||
            frame.subjectOps.hasBackgroundBlur()
        val alpha = if (support != null && needsWeight) {
            support.bandAlpha(width, height, frame.band)
        } else {
            null
        }
        subjectWeightTexture.stage(alpha, width, height)
        subjectWeightTexture.uploadIfNeeded()
    }

    private fun bokehParams(frame: BakeFrame, width: Int, height: Int): SubjectBokehDrawParams? {
        if (!frame.subjectOps.hasBackgroundBlur() || !subjectWeightTexture.isActive) return null
        val maxRadius = if (frame.band.halo > 0) frame.band.halo - 2 else Int.MAX_VALUE
        val radius = SubjectBokehMath.radiusPixels(
            blur = frame.subjectOps.blur,
            shortEdge = minOf(frame.cropWidth, frame.cropHeight),
            maxRadiusPx = maxRadius
        )
        if (radius <= 0) return null
        return SubjectBokehDrawParams(
            maskTextureId = subjectWeightTexture.textureId,
            radiusPx = radius.toFloat(),
            localSpace = true,
            uvMatrix = IDENTITY_MATRIX
        )
    }

    private fun passBParams(
        frame: BakeFrame,
        textureId: Int,
        texelWidth: Int,
        texelHeight: Int,
        isLinearInput: Boolean,
        ditherAmp: Float,
        subject: SubjectOps? = null,
        subjectWeightTextureId: Int = 0,
        subjectLocalSpace: Boolean = false
    ): ExposureDrawParams = ExposureDrawParams(
        textureId = textureId,
        compareOriginal = false,
        scaleX = 1.0f,
        scaleY = 1.0f,
        offsetX = 0.0f,
        offsetY = 0.0f,
        isLinearInput = isLinearInput,
        baseLook = frame.baseLook,
        autoBrightGain = frame.autoBrightGain,
        toneParams = frame.toneParams,
        colorParams = frame.colorParams,
        curveTextureId = curveLutTexture.textureId,
        texelWidth = texelWidth,
        texelHeight = texelHeight,
        ditherAmp = ditherAmp,
        subject = subject,
        subjectWeightTextureId = subjectWeightTextureId,
        subjectLocalSpace = subjectLocalSpace
    )

    private companion object {
        const val BYTES_PER_RGBA = 4
        const val DEFAULT_PACK_ALIGNMENT = 4
        val IDENTITY_MATRIX = floatArrayOf(
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )
    }
}
