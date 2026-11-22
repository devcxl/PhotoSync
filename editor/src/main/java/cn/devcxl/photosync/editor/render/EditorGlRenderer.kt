package cn.devcxl.photosync.editor.render

import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.editor.domain.LiquifyMesh

/**
 * Immutable snapshot of all per-frame render uniforms so the GL thread never observes a torn
 * combination of homography, output aspect ratio, CA coefficients and tone parameters.
 */
internal data class RenderFrameState(
    val exposureEv: Float = 0.0f,
    val baseLook: BaseLook = BaseLook.CAMERA_DEFAULT,
    val useFastProxy: Boolean = false,
    val geometryActive: Boolean = false,
    val geometryInverseMatrix: FloatArray = IDENTITY_MATRIX,
    val lateralCaRedK: Float = 0.0f,
    val lateralCaBlueK: Float = 0.0f,
    val outputAspectRatio: Float = 1.0f,
    val liquifyWarp: Boolean = false
) {
    companion object {
        val IDENTITY_MATRIX = floatArrayOf(
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )
    }
}

/**
 * Immutable snapshot of canvas pan/zoom transforms.
 */
internal data class CanvasViewportTransform(
    val scale: Float = 1.0f,
    val offsetX: Float = 0.0f,
    val offsetY: Float = 0.0f
)

/**
 * GLES 3.0 renderer for the editor preview canvas.
 *
 * Runs the physical two-pass preview pipeline:
 * 1. **Pass A** ([GeometryCaGlProgram]) renders the upright source (`GL_RGBA16F` for RAW,
 *    `GL_RGBA8` for JPEG) through `H^-1` and the liquify `uWarpTex` into an offscreen target sized
 *    to the cropped aspect ratio, applying aspect-corrected circular pre-crop lateral CA resampling.
 * 2. **Pass B** ([ExposureGlProgram]) applies RAW `BaseLook` compensation, linear-light exposure,
 *    soft-shoulder highlight roll-off, and canvas pan/zoom.
 */
internal class EditorGlRenderer : GLSurfaceView.Renderer {

    private val exposureProgram = ExposureGlProgram()
    private val geometryProgram = GeometryCaGlProgram()
    private val geometryTarget = PassAFrameBuffer()
    private val textures = EditorSourceTextures()
    private val warpTexture = LiquifyWarpTexture()

    private var viewportWidth: Int = 1
    private var viewportHeight: Int = 1
    private var geometryFallbackLogged: Boolean = false

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
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        exposureProgram.init(preferEs3)
        geometryTarget.release()
        geometryTarget.resetDisableFlag()
        geometryFallbackLogged = false
        warpTexture.invalidate()
        textures.invalidateContext()
        val source = stagedSource
        applySourceMetadata(source)
        if (preferEs3) {
            PassAFrameBuffer.cacheCapabilities()
            geometryProgram.init()
        }
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
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val state = frameState
        val viewport = canvasTransform
        val activeTexture = textures.activeTexture(state.useFastProxy)
        if (activeTexture == 0) return

        if (compareOriginal) {
            drawExposure(
                textureId = activeTexture,
                state = state,
                viewport = viewport,
                comparingOriginal = true,
                aspect = sourceAspect(),
                inputIsLinear = linearInput
            )
            return
        }
        if (drawGeometryPass(activeTexture, state, viewport)) return
        drawExposure(
            textureId = activeTexture,
            state = state,
            viewport = viewport,
            comparingOriginal = false,
            aspect = sourceAspect(),
            inputIsLinear = linearInput
        )
    }

    /**
     * Stages [source] for upload on the GL thread and adopts its dimensions and gain metadata.
     */
    fun setSource(source: EditorSource) {
        stagedSource = source
        textures.stage(source)
        applySourceMetadata(source)
    }

    /**
     * Clears staged source references when the hosting activity is destroyed.
     */
    fun releaseSource() {
        stagedSource = null
        textures.clear()
    }

    /**
     * Stages the liquify displacement grid for the next frame.
     *
     * The mesh is uploaded lazily on the GL thread, so a live brush stroke only has to publish a
     * reference plus the "dirty" flag here.
     *
     * @param mesh The live grid, or `null` to disable the warp entirely.
     */
    fun setLiquifyMesh(mesh: LiquifyMesh?) {
        warpTexture.stage(mesh)
        frameState = frameState.copy(liquifyWarp = warpTexture.isWarpActive)
    }

    /**
     * Width/height ratio of the upright source texture, used when geometry is neutral.
     */
    fun sourceAspect(): Float = imageWidth.toFloat() / imageHeight.toFloat()

    /**
     * Atomically compiles and publishes all render parameters for [graph].
     */
    fun applyState(graph: EditGraph, useFastProxy: Boolean) {
        val aspect = sourceAspect()
        val geometry = graph.geometry
        val aberration = graph.effects.ca
        val liquifyWarp = warpTexture.isWarpActive
        val geometryActive = !geometry.isIdentity() || !aberration.isIdentity() || liquifyWarp
        val outputAspect = if (geometryActive) {
            GeometryMath.outputAspect(geometry, aspect)
        } else {
            aspect
        }
        val inverseMatrix = if (geometryActive) {
            GeometryMath.buildPassAInverseMatrix(geometry, aspect).toColumnMajorFloatArray()
        } else {
            RenderFrameState.IDENTITY_MATRIX
        }
        frameState = RenderFrameState(
            exposureEv = graph.tone.exposure,
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

    private fun drawGeometryPass(
        sourceTexture: Int,
        state: RenderFrameState,
        viewport: CanvasViewportTransform
    ): Boolean {
        if (!state.geometryActive && !state.liquifyWarp) return false
        if (!geometryProgram.isReady) return false
        val srcAspect = sourceAspect()
        val aspect = state.outputAspectRatio.takeIf { it.isFinite() && it > 0.0f } ?: srcAspect
        val (scaleX, scaleY) = computeLetterboxScale(aspect, viewport.scale)
        val (targetWidth, targetHeight) = PassAFrameBuffer.targetSizeFor(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            scaleX = scaleX,
            scaleY = scaleY,
            maxEdgePx = MAX_TARGET_EDGE_PX
        )
        geometryTarget.ensureSize(targetWidth, targetHeight, allowHalfFloat = true)
        if (!geometryTarget.isReady) {
            logGeometryFallbackOnce()
            return false
        }
        val encodeSrgbInPassA = linearInput && !geometryTarget.isHalfFloatTarget
        geometryTarget.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        geometryProgram.draw(
            GeometryDrawParams(
                sourceTextureId = sourceTexture,
                columnMajorInverseMatrix = state.geometryInverseMatrix,
                caRedK = state.lateralCaRedK,
                caBlueK = state.lateralCaBlueK,
                srcAspect = srcAspect,
                encodeSrgbWithDither = encodeSrgbInPassA,
                warpTextureId = if (state.liquifyWarp) warpTexture.textureId else 0,
                warpDecodeScale = warpTexture.decodeScale,
                warpDecodeOffset = warpTexture.decodeOffset
            )
        )
        geometryTarget.unbind()
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        drawExposure(
            textureId = geometryTarget.textureId,
            state = state,
            viewport = viewport,
            comparingOriginal = false,
            aspect = aspect,
            inputIsLinear = linearInput && geometryTarget.isHalfFloatTarget
        )
        return true
    }

    private fun drawExposure(
        textureId: Int,
        state: RenderFrameState,
        viewport: CanvasViewportTransform,
        comparingOriginal: Boolean,
        aspect: Float,
        inputIsLinear: Boolean
    ) {
        val (scaleX, scaleY) = computeLetterboxScale(aspect, viewport.scale)
        exposureProgram.draw(
            ExposureDrawParams(
                textureId = textureId,
                exposureEv = state.exposureEv,
                compareOriginal = comparingOriginal,
                scaleX = scaleX,
                scaleY = scaleY,
                offsetX = viewport.offsetX,
                offsetY = viewport.offsetY,
                isLinearInput = inputIsLinear,
                baseLook = state.baseLook,
                autoBrightGain = autoBrightGain
            )
        )
    }

    private fun logGeometryFallbackOnce() {
        if (geometryFallbackLogged) return
        geometryFallbackLogged = true
        Timber.w("Pass A target unavailable; preview falls back to the un-warped source")
    }

    private fun computeLetterboxScale(imageAspect: Float, rawZoom: Float): Pair<Float, Float> {
        val viewAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val zoom = rawZoom.takeIf { it.isFinite() && it > 0.0f }?.coerceIn(1.0f, MAX_CANVAS_ZOOM)
            ?: 1.0f
        return if (imageAspect > viewAspect) {
            zoom to (viewAspect / imageAspect) * zoom
        } else {
            (imageAspect / viewAspect) * zoom to zoom
        }
    }

    private companion object {
        private const val MAX_TARGET_EDGE_PX = 2048
        private const val MAX_CANVAS_ZOOM = 8.0f
    }
}
