package cn.devcxl.photosync.editor.render

import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

import android.opengl.GLES20
import android.opengl.GLSurfaceView

import cn.devcxl.photosync.editor.domain.ColorGradingParams
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.PassDEffectsParams
import cn.devcxl.photosync.editor.domain.PortraitWarpComposer
import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.ToneShaderParams

/**
 * GLES 3.0 renderer for the editor preview canvas.
 *
 * Runs the physical four-pass preview pipeline owned by [PreviewPassPipeline]:
 * 1. **Pass A** ([GeometryCaGlProgram]) renders the upright source through `H^-1`, `uWarpTex` and
 *    aspect-corrected lateral CA resampling.
 * 2. **Pass B** ([ExposureGlProgram]) applies `BaseLook`, Bradford WB, Pass B1 tone and Pass B2
 *    display color grading.
 * 3. **Pass C** ([SpatialDetailRenderer]) runs the 1024px Pre-pass, guided NR, dark-channel
 *    dehaze, Clarity/Texture and Y sharpening (short-circuited to 0 FBO draw calls when neutral).
 * 4. **Pass D** ([EffectsOutputStage]) applies tetrahedral 3D LUT, post-crop grain, vignette and
 *    `1/255` triangular dithering.
 */
internal class EditorGlRenderer : GLSurfaceView.Renderer {

    private val pipeline = PreviewPassPipeline()

    private var viewportWidth: Int = 1
    private var viewportHeight: Int = 1

    @Volatile
    private var prepassRevision: Int = 0

    @Volatile
    private var lastPrepassInputSignature: Int = 0

    @Volatile private var imageWidth: Int = 1
    @Volatile private var imageHeight: Int = 1
    @Volatile private var linearInput: Boolean = false
    @Volatile private var autoBrightGain: Float = 1.0f
    @Volatile private var stagedSource: EditorSource? = null

    @Volatile private var liquifyMesh: LiquifyMesh? = null

    @Volatile private var portraitFaceWarp: FloatArray? = null

    @Volatile private var portraitBodyWarp: FloatArray? = null

    @Volatile
    var preferEs3: Boolean = true

    @Volatile
    var compareOriginal: Boolean = false

    @Volatile
    var frameState: RenderFrameState = RenderFrameState()

    @Volatile
    var canvasTransform: CanvasViewportTransform = CanvasViewportTransform()

    /** FBO draw calls Pass C issued in the most recent frame; `0` when short-circuited. */
    @Volatile
    var lastPassCDrawCalls: Int = 0
        private set

    /** True when the current graph activates Pass C outside a fast proxy drag. */
    @Volatile
    var isPassCActive: Boolean = false
        private set

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        pipeline.init(preferEs3)
        applySourceMetadata(stagedSource)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (pipeline.uploadPending(preferEs3)) {
            frameState = frameState.copy(warpActive = false)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val state = frameState
        val activeTexture = pipeline.activeTexture(state.useFastProxy)
        if (activeTexture == 0) {
            lastPassCDrawCalls = 0
            return
        }
        val (srcW, srcH) = pipeline.activeDimensions(state.useFastProxy)
        pipeline.drawFrame(
            PreviewFrameRequest(
                sourceTextureId = activeTexture,
                sourceWidth = srcW,
                sourceHeight = srcH,
                fullResolutionShortEdge = minOf(imageWidth, imageHeight),
                sourceAspect = sourceAspect(),
                isLinearInput = linearInput,
                autoBrightGain = autoBrightGain,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                canvas = canvasTransform,
                state = state,
                compareOriginal = compareOriginal,
                graphRevision = prepassRevision
            )
        )
        lastPassCDrawCalls = pipeline.lastPassCDrawCalls
    }

    /** Stages [source] for upload on the GL thread and adopts its dimensions and gain metadata. */
    fun setSource(source: EditorSource) {
        stagedSource = source
        pipeline.setSource(source)
        applySourceMetadata(source)
        pipeline.invalidatePrepass()
        prepassRevision++
    }

    /** Clears staged source references when the hosting activity is destroyed. */
    fun releaseSource() {
        stagedSource = null
        pipeline.releaseSource()
    }

    /** Stages the liquify displacement grid for the next frame. */
    fun setLiquifyMesh(mesh: LiquifyMesh?) {
        liquifyMesh = mesh
        publishWarp()
    }

    /**
     * Stages the portrait face MLS and body warp meshes.
     *
     * The renderer is the single composition point: `uWarpTex` receives
     * `Δuv_face + Δuv_body + Δuv_liquify`, so Pass A still performs exactly one texture fetch.
     */
    fun setPortraitWarp(faceWarp: FloatArray?, bodyWarp: FloatArray?) {
        portraitFaceWarp = faceWarp
        portraitBodyWarp = bodyWarp
        publishWarp()
    }

    /** Stages the portrait `skinMask` that gates Pass C Stage 0 and the sharpening suppression. */
    fun setSkinMask(mask: FloatArray?) {
        pipeline.stageSkinMask(mask)
        pipeline.invalidatePrepass()
        prepassRevision++
    }

    private fun publishWarp() {
        val composed = PortraitWarpComposer.compose(portraitFaceWarp, portraitBodyWarp, liquifyMesh)
        frameState = frameState.copy(warpActive = pipeline.stageWarpField(composed))
        pipeline.invalidatePrepass()
        prepassRevision++
    }

    /** Stages the imported 3D LUT samples for the next frame, or `null` to unbind it. */
    fun setLut3d(samples: Lut3dSamples?) = pipeline.stageLut(samples)

    /** Width/height ratio of the upright source texture, used when geometry is neutral. */
    fun sourceAspect(): Float = imageWidth.toFloat() / imageHeight.toFloat()

    /** Atomically compiles and publishes all render parameters for [graph]. */
    fun applyState(graph: EditGraph, useFastProxy: Boolean) {
        val aspect = sourceAspect()
        val geometry = graph.geometry
        val aberration = graph.effects.ca
        val warpActive = pipeline.isWarpActive
        val geometryActive = graph.requiresPassAGeometry(hasWarp = warpActive)
        val outputAspect = if (geometryActive) GeometryMath.outputAspect(geometry, aspect) else aspect
        val inverseMatrix = if (geometryActive) {
            GeometryMath.buildPassAInverseMatrix(geometry, aspect).toColumnMajorFloatArray()
        } else {
            RenderFrameState.IDENTITY_MATRIX
        }
        val spatialParams = SpatialDetailParams.from(graph)
        pipeline.stageCurve(graph.color.curve)
        frameState = RenderFrameState(
            toneParams = ToneShaderParams.from(graph),
            colorParams = ColorGradingParams.from(graph),
            spatialParams = spatialParams,
            effectsParams = PassDEffectsParams.from(graph, outputAspect),
            baseLook = graph.baseLook,
            useFastProxy = useFastProxy,
            geometryActive = geometryActive,
            geometryInverseMatrix = inverseMatrix,
            lateralCaRedK = GeometryMath.lateralCaKFromSlider(aberration.redCyan),
            lateralCaBlueK = GeometryMath.lateralCaKFromSlider(aberration.blueYellow),
            outputAspectRatio = outputAspect,
            warpActive = warpActive
        )
        isPassCActive = !useFastProxy && !spatialParams.isZeroDrawCallShortCircuit
        val prepassSig = prepassSignature(graph, warpActive, portraitFaceWarp, portraitBodyWarp)
        if (prepassSig != lastPrepassInputSignature) {
            lastPrepassInputSignature = prepassSig
            pipeline.invalidatePrepass()
            prepassRevision++
        }
    }

    private fun prepassSignature(
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
        hash = 31 * hash + warpActive.hashCode()
        hash = 31 * hash + (faceWarp?.contentHashCode() ?: 0)
        hash = 31 * hash + (bodyWarp?.contentHashCode() ?: 0)
        return hash
    }

    private fun applySourceMetadata(source: EditorSource?) {
        if (source == null) return
        imageWidth = source.width.coerceAtLeast(1)
        imageHeight = source.height.coerceAtLeast(1)
        linearInput = source.isLinear
        autoBrightGain = (source as? EditorSource.LinearRawSource)?.autoBrightGain ?: 1.0f
    }
}
