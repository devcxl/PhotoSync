package cn.devcxl.photosync.editor.render

import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

import android.opengl.GLES20
import android.opengl.GLSurfaceView

import cn.devcxl.photosync.editor.domain.ColorGradingParams
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.SpatialDetailParams
import cn.devcxl.photosync.editor.domain.ToneShaderParams

/**
 * GLES 3.0 renderer for the editor preview canvas.
 *
 * Runs the physical preview pipeline:
 * 1. **Pass A** ([EditorScenePasses]) renders the upright source (`GL_RGBA16F` for RAW,
 *    `GL_RGBA8` for JPEG) through `H^-1` and the liquify `uWarpTex` into an offscreen target sized
 *    to the cropped aspect ratio, applying aspect-corrected circular pre-crop lateral CA resampling.
 * 2. **Pass B** ([ExposureGlProgram]) applies RAW `BaseLook` compensation, D65-anchored Bradford WB,
 *    linear/display tone (Pass B1), plus 1024-entry curve LUT, OKLCH saturation/vibrance/8-band HSL,
 *    OKLab split toning, edge defringe and film fade (Pass B2).
 * 3. **Pass C** ([SpatialDetailRenderer]) runs the 1024px Pre-pass, guided NR, dark-channel dehaze,
 *    Clarity/Texture high-pass and Y-channel sharpening, then presents the frame. A neutral spatial
 *    group short-circuits the whole group to **zero FBO draw calls** ([lastPassCDrawCalls]).
 *
 * A slider drag (`useFastProxy`) skips Pass C so the 1024px proxy stays a point-operation pipeline;
 * the full spatial chain runs as soon as the finger lifts.
 */
internal class EditorGlRenderer : GLSurfaceView.Renderer {

    private val scene = EditorScenePasses()
    private val spatialDetail = SpatialDetailRenderer()
    private val textures = EditorSourceTextures()
    private val warpTexture = LiquifyWarpTexture()
    private val curveLutTexture = CurveLutTexture()

    private var viewportWidth: Int = 1
    private var viewportHeight: Int = 1

    @Volatile
    private var graphRevision: Int = 0

    @Volatile
    private var imageWidth: Int = 1

    @Volatile
    private var imageHeight: Int = 1

    @Volatile
    private var linearInput: Boolean = false

    @Volatile
    private var autoBrightGain: Float = 1.0f

    @Volatile
    private var stagedSource: EditorSource? = null

    @Volatile
    var preferEs3: Boolean = true

    @Volatile
    var compareOriginal: Boolean = false

    @Volatile
    var frameState: RenderFrameState = RenderFrameState()

    @Volatile
    var canvasTransform: CanvasViewportTransform = CanvasViewportTransform()

    /** FBO draw calls Pass C issued in the most recent frame; `0` when it was short-circuited. */
    @Volatile
    var lastPassCDrawCalls: Int = 0
        private set

    /** True when the current graph activates Pass C outside a proxy drag. */
    @Volatile
    var isPassCActive: Boolean = false
        private set

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        if (preferEs3) PassAFrameBuffer.cacheCapabilities()
        warpTexture.invalidate()
        curveLutTexture.invalidate()
        textures.invalidateContext()
        scene.init(preferEs3)
        spatialDetail.init()
        applySourceMetadata(stagedSource)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        textures.uploadPending()
        if (warpTexture.uploadIfNeeded()) {
            frameState = frameState.copy(liquifyWarp = false)
        }
        curveLutTexture.uploadIfNeeded(preferEs3)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val state = frameState
        val viewport = canvasTransform
        val activeTexture = textures.activeTexture(state.useFastProxy)
        if (activeTexture == 0) {
            lastPassCDrawCalls = 0
            return
        }
        val (sourceWidth, sourceHeight) = textures.activeDimensions(state.useFastProxy)
        if (compareOriginal) {
            drawOriginal(activeTexture, state, viewport, sourceWidth, sourceHeight)
        } else {
            drawGraded(activeTexture, state, viewport, sourceWidth, sourceHeight)
        }
    }

    private fun drawOriginal(
        activeTexture: Int,
        state: RenderFrameState,
        viewport: CanvasViewportTransform,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        lastPassCDrawCalls = 0
        scene.drawToCanvas(
            passA = PassAOutput(activeTexture, linearInput, sourceWidth, sourceHeight, sourceAspect()),
            state = state,
            comparingOriginal = true,
            viewport = viewport,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            autoBrightGain = autoBrightGain,
            curveTextureId = curveLutTexture.textureId
        )
    }

    private fun drawGraded(
        activeTexture: Int,
        state: RenderFrameState,
        viewport: CanvasViewportTransform,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        val passA = scene.renderPassA(
            state = state,
            warp = WarpBinding(warpTexture.textureId, warpTexture.decodeScale, warpTexture.decodeOffset),
            sourceTexture = activeTexture,
            sourceAspect = sourceAspect(),
            linearInput = linearInput,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            canvasZoom = viewport.scale
        )
        if (drawSpatialDetail(passA, state, viewport)) return
        lastPassCDrawCalls = 0
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        scene.drawToCanvas(
            passA = passA,
            state = state,
            comparingOriginal = false,
            viewport = viewport,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            autoBrightGain = autoBrightGain,
            curveTextureId = curveLutTexture.textureId
        )
    }

    /** Stages [source] for upload on the GL thread and adopts its dimensions and gain metadata. */
    fun setSource(source: EditorSource) {
        stagedSource = source
        textures.stage(source)
        applySourceMetadata(source)
    }

    /**
     * Clears staged source references when the hosting activity is destroyed.
     *
     * GL objects are intentionally left for [onSurfaceCreated] / context teardown: this can run on the
     * UI thread, where issuing `glDelete*` calls would be invalid.
     */
    fun releaseSource() {
        stagedSource = null
        textures.clear()
    }

    /** Stages the liquify displacement grid for the next frame. */
    fun setLiquifyMesh(mesh: LiquifyMesh?) {
        warpTexture.stage(mesh)
        frameState = frameState.copy(liquifyWarp = warpTexture.isWarpActive)
    }

    /** Width/height ratio of the upright source texture, used when geometry is neutral. */
    fun sourceAspect(): Float = imageWidth.toFloat() / imageHeight.toFloat()

    /** Atomically compiles and publishes all render parameters for [graph]. */
    fun applyState(graph: EditGraph, useFastProxy: Boolean) {
        val aspect = sourceAspect()
        val geometry = graph.geometry
        val aberration = graph.effects.ca
        val liquifyWarp = warpTexture.isWarpActive
        val geometryActive = graph.requiresPassAGeometry(hasWarp = liquifyWarp)
        val outputAspect = if (geometryActive) GeometryMath.outputAspect(geometry, aspect) else aspect
        val inverseMatrix = if (geometryActive) {
            GeometryMath.buildPassAInverseMatrix(geometry, aspect).toColumnMajorFloatArray()
        } else {
            RenderFrameState.IDENTITY_MATRIX
        }
        val spatialParams = SpatialDetailParams.from(graph)
        curveLutTexture.stage(graph.color.curve)
        frameState = RenderFrameState(
            toneParams = ToneShaderParams.from(graph),
            colorParams = ColorGradingParams.from(graph),
            spatialParams = spatialParams,
            baseLook = graph.baseLook,
            useFastProxy = useFastProxy,
            geometryActive = geometryActive,
            geometryInverseMatrix = inverseMatrix,
            lateralCaRedK = GeometryMath.lateralCaKFromSlider(aberration.redCyan),
            lateralCaBlueK = GeometryMath.lateralCaKFromSlider(aberration.blueYellow),
            outputAspectRatio = outputAspect,
            liquifyWarp = liquifyWarp
        )
        isPassCActive = !useFastProxy && !spatialParams.isZeroDrawCallShortCircuit
        spatialDetail.invalidatePrepass()
        graphRevision++
    }

    private fun applySourceMetadata(source: EditorSource?) {
        if (source == null) return
        imageWidth = source.width.coerceAtLeast(1)
        imageHeight = source.height.coerceAtLeast(1)
        linearInput = source.isLinear
        autoBrightGain = (source as? EditorSource.LinearRawSource)?.autoBrightGain ?: 1.0f
    }

    private fun drawSpatialDetail(
        passA: PassAOutput,
        state: RenderFrameState,
        viewport: CanvasViewportTransform
    ): Boolean {
        if (!isPassCActive || compareOriginal) return false
        val viewAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val (scaleX, scaleY) = CanvasLetterbox.scale(passA.aspect, viewAspect, viewport.scale)
        val (targetWidth, targetHeight) = CanvasLetterbox.targetSize(
            viewportWidth, viewportHeight, scaleX, scaleY, MAX_TARGET_EDGE_PX
        )
        val drawn = spatialDetail.render(
            input = passA,
            params = state.spatialParams,
            revision = graphRevision,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            scaleX = scaleX,
            scaleY = scaleY,
            offsetX = viewport.offsetX,
            offsetY = viewport.offsetY,
            allowHalfFloat = true
        ) { target, _, _ ->
            scene.drawPassBInto(
                target = target,
                passA = passA,
                state = state,
                autoBrightGain = autoBrightGain,
                curveTextureId = curveLutTexture.textureId
            )
            true
        }
        lastPassCDrawCalls = if (drawn) spatialDetail.lastDrawCalls else 0
        return drawn
    }

    private companion object {
        private const val MAX_TARGET_EDGE_PX = 2048
    }
}
