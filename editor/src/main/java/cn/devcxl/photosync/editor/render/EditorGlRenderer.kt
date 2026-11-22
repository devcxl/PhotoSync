package cn.devcxl.photosync.editor.render

import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

import android.opengl.GLES20
import android.opengl.GLSurfaceView

import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.ColorGradingParams
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.LiquifyMesh
import cn.devcxl.photosync.editor.domain.Lut3dSamples
import cn.devcxl.photosync.editor.domain.PassDEffectsParams
import cn.devcxl.photosync.editor.domain.ToneShaderParams

/**
 * GLES 3.0 renderer for the editor preview canvas.
 *
 * Runs the physical three-pass preview pipeline owned by [PreviewPassPipeline]:
 * 1. **Pass A** ([GeometryCaGlProgram]) renders the upright source (`GL_RGBA16F` for RAW,
 *    `GL_RGBA8` for JPEG) through `H^-1` and the liquify `uWarpTex` into an offscreen target sized
 *    to the cropped aspect ratio, applying aspect-corrected circular pre-crop lateral CA resampling.
 * 2. **Pass B** ([ExposureGlProgram]) applies RAW `BaseLook` compensation, D65-anchored Bradford WB,
 *    linear/display tone (Pass B1), plus 1024-entry curve LUT, OKLCH saturation/vibrance/8-band HSL,
 *    OKLab split toning, edge defringe and film fade (Pass B2).
 * 3. **Pass D** ([EffectsOutputStage]) applies the tetrahedral 3D LUT, post-crop film grain,
 *    post-crop vignette and `1/255` triangular dithering on the cropped frame's global UVs.
 */
internal class EditorGlRenderer : GLSurfaceView.Renderer {

    private val pipeline = PreviewPassPipeline()

    private var viewportWidth: Int = 1
    private var viewportHeight: Int = 1

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
            frameState = frameState.copy(liquifyWarp = false)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val state = frameState
        val activeTexture = pipeline.activeTexture(state.useFastProxy)
        if (activeTexture == 0) return
        val (srcW, srcH) = pipeline.activeDimensions(state.useFastProxy)
        pipeline.drawFrame(
            PreviewFrameRequest(
                sourceTextureId = activeTexture,
                sourceWidth = srcW,
                sourceHeight = srcH,
                sourceAspect = sourceAspect(),
                isLinearInput = linearInput,
                autoBrightGain = autoBrightGain,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                canvas = canvasTransform,
                state = state,
                compareOriginal = compareOriginal
            )
        )
    }

    /** Stages [source] for upload on the GL thread and adopts its dimensions and gain metadata. */
    fun setSource(source: EditorSource) {
        stagedSource = source
        pipeline.setSource(source)
        applySourceMetadata(source)
    }

    /** Clears staged source references when the hosting activity is destroyed. */
    fun releaseSource() {
        stagedSource = null
        pipeline.releaseSource()
    }

    /** Stages the liquify displacement grid for the next frame. */
    fun setLiquifyMesh(mesh: LiquifyMesh?) {
        frameState = frameState.copy(liquifyWarp = pipeline.stageLiquifyMesh(mesh))
    }

    /** Stages the imported 3D LUT samples for the next frame, or `null` to unbind it. */
    fun setLut3d(samples: Lut3dSamples?) {
        pipeline.stageLut(samples)
    }

    /** Width/height ratio of the upright source texture, used when geometry is neutral. */
    fun sourceAspect(): Float = imageWidth.toFloat() / imageHeight.toFloat()

    /** Atomically compiles and publishes all render parameters for [graph]. */
    fun applyState(graph: EditGraph, useFastProxy: Boolean) {
        val aspect = sourceAspect()
        val geometry = graph.geometry
        val aberration = graph.effects.ca
        val liquifyWarp = pipeline.isLiquifyWarpActive
        val geometryActive = graph.requiresPassAGeometry(hasWarp = liquifyWarp)
        val outputAspect = if (geometryActive) GeometryMath.outputAspect(geometry, aspect) else aspect
        val inverseMatrix = if (geometryActive) {
            GeometryMath.buildPassAInverseMatrix(geometry, aspect).toColumnMajorFloatArray()
        } else {
            RenderFrameState.IDENTITY_MATRIX
        }
        pipeline.stageCurve(graph.color.curve)
        frameState = RenderFrameState(
            toneParams = ToneShaderParams.from(graph),
            colorParams = ColorGradingParams.from(graph),
            effectsParams = PassDEffectsParams.from(graph, outputAspect),
            baseLook = graph.baseLook,
            useFastProxy = useFastProxy,
            geometryActive = geometryActive,
            geometryInverseMatrix = inverseMatrix,
            lateralCaRedK = GeometryMath.lateralCaKFromSlider(aberration.redCyan),
            lateralCaBlueK = GeometryMath.lateralCaKFromSlider(aberration.blueYellow),
            outputAspectRatio = outputAspect,
            liquifyWarp = liquifyWarp
        )
    }

    private fun applySourceMetadata(source: EditorSource?) {
        if (source == null) return
        imageWidth = source.width.coerceAtLeast(1)
        imageHeight = source.height.coerceAtLeast(1)
        linearInput = source.isLinear
        autoBrightGain = (source as? EditorSource.LinearRawSource)?.autoBrightGain ?: 1.0f
    }
}
