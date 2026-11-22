package cn.devcxl.photosync.editor.render

import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryMath
import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * GLES 3.0 renderer for the editor preview canvas.
 *
 * Runs the physical two-pass preview pipeline:
 * 1. **Pass A** ([GeometryCaGlProgram]) renders the upright source (`GL_RGBA16F` for RAW,
 *    `GL_RGBA8` for JPEG) through `H^-1` into an offscreen target sized to the cropped aspect
 *    ratio, applying pre-crop lateral CA resampling.
 * 2. **Pass B** ([ExposureGlProgram]) applies RAW `BaseLook` compensation, linear-light exposure,
 *    soft-shoulder highlight roll-off, and canvas pan/zoom.
 *
 * When geometry and CA are neutral, Pass A is skipped so the 0-parameter view is identical to
 * the source texture. The staged [EditorSource] is retained across EGL surface recreation so
 * returning from the background never leaves a black canvas.
 */
internal class EditorGlRenderer : GLSurfaceView.Renderer {

    private val exposureProgram = ExposureGlProgram()
    private val geometryProgram = GeometryCaGlProgram()
    private val geometryTarget = PassAFrameBuffer()

    private var proxyTextureId: Int = 0
    private var fullTextureId: Int = 0
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
    private var needsFullUpload: Boolean = false

    @Volatile
    private var needsProxyUpload: Boolean = false

    @Volatile
    var preferEs3: Boolean = true

    @Volatile
    var exposureEv: Float = 0.0f

    @Volatile
    var baseLook: BaseLook = BaseLook.CAMERA_DEFAULT

    @Volatile
    var compareOriginal: Boolean = false

    @Volatile
    var useFastProxy: Boolean = false

    @Volatile
    var geometryActive: Boolean = false

    @Volatile
    var geometryInverseMatrix: FloatArray = IDENTITY_MATRIX

    @Volatile
    var lateralCaRedK: Float = 0.0f

    @Volatile
    var lateralCaBlueK: Float = 0.0f

    @Volatile
    var outputAspectRatio: Float = 1.0f

    @Volatile
    var canvasScale: Float = 1.0f

    @Volatile
    var canvasOffsetX: Float = 0.0f

    @Volatile
    var canvasOffsetY: Float = 0.0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        exposureProgram.init(preferEs3)
        geometryTarget.release()
        geometryTarget.resetDisableFlag()
        geometryFallbackLogged = false
        proxyTextureId = 0
        fullTextureId = 0
        val source = stagedSource
        needsFullUpload = source != null
        needsProxyUpload = source != null
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
        uploadPendingTextures()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val activeTexture = if (useFastProxy && proxyTextureId != 0) proxyTextureId else fullTextureId
        if (activeTexture == 0) return

        if (compareOriginal) {
            drawExposure(activeTexture, comparingOriginal = true, aspect = sourceAspect())
            return
        }
        if (drawGeometryPass(activeTexture)) return
        drawExposure(activeTexture, comparingOriginal = false, aspect = sourceAspect())
    }

    /**
     * Stages [source] for upload on the GL thread and adopts its dimensions and gain metadata.
     */
    fun setSource(source: EditorSource) {
        stagedSource = source
        applySourceMetadata(source)
        needsFullUpload = true
        needsProxyUpload = true
    }

    /**
     * Clears staged source references when the hosting activity is destroyed so recycled
     * bitmaps are never touched on a late GL draw.
     */
    fun releaseSource() {
        stagedSource = null
        needsFullUpload = false
        needsProxyUpload = false
    }

    /**
     * Width/height ratio of the upright source texture, used when geometry is neutral.
     */
    fun sourceAspect(): Float = imageWidth.toFloat() / imageHeight.toFloat()

    /**
     * Pushes the geometry and lateral-CA parameters of [graph] onto the render thread.
     */
    fun applyGeometry(graph: EditGraph) {
        val aspect = sourceAspect()
        val geometry = graph.geometry
        val aberration = graph.effects.ca
        geometryActive = !geometry.isIdentity() || !aberration.isIdentity()
        outputAspectRatio = GeometryMath.outputAspect(geometry, aspect)
        geometryInverseMatrix = GeometryMath.buildPassAInverseMatrix(geometry, aspect)
            .toColumnMajorFloatArray()
        lateralCaRedK = GeometryMath.lateralCaKFromSlider(aberration.redCyan)
        lateralCaBlueK = GeometryMath.lateralCaKFromSlider(aberration.blueYellow)
    }

    private fun applySourceMetadata(source: EditorSource?) {
        if (source == null) return
        imageWidth = source.width.coerceAtLeast(1)
        imageHeight = source.height.coerceAtLeast(1)
        linearInput = source.isLinear
        autoBrightGain = (source as? EditorSource.LinearRawSource)?.autoBrightGain ?: 1.0f
    }

    private fun drawGeometryPass(sourceTexture: Int): Boolean {
        if (!geometryActive || !geometryProgram.isReady) return false
        val aspect = outputAspectRatio.takeIf { it.isFinite() && it > 0.0f } ?: sourceAspect()
        val (scaleX, scaleY) = computeLetterboxScale(aspect)
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
        geometryTarget.bind()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        geometryProgram.draw(
            textureId = sourceTexture,
            columnMajorInverseMatrix = geometryInverseMatrix,
            caRedK = lateralCaRedK,
            caBlueK = lateralCaBlueK
        )
        geometryTarget.unbind()
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        drawExposure(geometryTarget.textureId, comparingOriginal = false, aspect = aspect)
        return true
    }

    private fun drawExposure(textureId: Int, comparingOriginal: Boolean, aspect: Float) {
        val (scaleX, scaleY) = computeLetterboxScale(aspect)
        exposureProgram.draw(
            ExposureDrawParams(
                textureId = textureId,
                exposureEv = exposureEv,
                compareOriginal = comparingOriginal,
                scaleX = scaleX,
                scaleY = scaleY,
                offsetX = canvasOffsetX,
                offsetY = canvasOffsetY,
                isLinearInput = linearInput,
                baseLook = baseLook,
                autoBrightGain = autoBrightGain
            )
        )
    }

    private fun logGeometryFallbackOnce() {
        if (geometryFallbackLogged) return
        geometryFallbackLogged = true
        Timber.w("Pass A target unavailable; preview falls back to the un-warped source")
    }

    private fun uploadPendingTextures() {
        val source = stagedSource ?: return
        if (needsFullUpload) {
            needsFullUpload = false
            fullTextureId = upload(source, fullFrame = true)
        }
        if (needsProxyUpload) {
            needsProxyUpload = false
            proxyTextureId = upload(source, fullFrame = false)
        }
    }

    private fun upload(source: EditorSource, fullFrame: Boolean): Int {
        val existing = if (fullFrame) fullTextureId else proxyTextureId
        return when (source) {
            is EditorSource.BitmapSource -> {
                val bitmap = if (fullFrame) source.full else source.proxy
                EditorTextureUploader.uploadBitmap(bitmap, existing)
            }
            is EditorSource.LinearRawSource -> {
                val buffer = if (fullFrame) source.full else source.proxy
                uploadLinear(buffer, existing)
            }
        }
    }

    private fun uploadLinear(buffer: LinearRawBuffer, existing: Int): Int {
        val halfFloatTexture = EditorTextureUploader.uploadLinearHalfFloat(buffer, existing)
        if (halfFloatTexture != 0) return halfFloatTexture
        return EditorTextureUploader.uploadLinearAsBytes(buffer, existingTextureId = 0)
    }

    private fun computeLetterboxScale(imageAspect: Float): Pair<Float, Float> {
        val viewAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val zoom = canvasScale.takeIf { it.isFinite() && it > 0.0f }?.coerceIn(1.0f, MAX_CANVAS_ZOOM)
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
        private val IDENTITY_MATRIX = floatArrayOf(
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )
    }
}
