package cn.devcxl.photosync.editor.render

import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryMath

/**
 * GLES 3.0 renderer for the editor preview canvas.
 *
 * Runs the physical two-pass preview pipeline:
 * 1. **Pass A** ([GeometryCaGlProgram]) renders the upright source through `H^-1` into an offscreen
 *    target sized to the cropped aspect ratio, applying pre-crop lateral CA resampling.
 * 2. **Pass B** ([ExposureGlProgram]) applies linear-light exposure and letterboxes the result.
 *
 * When the geometry is neutral, Pass A is skipped entirely so the 0-parameter view is bit-identical
 * to the source texture. Both a `1024px` drag-proxy texture (`PROXY_1024_FAST`) and a full-preview
 * texture (`IDLE_FULL_16F`) are retained across EGL surface recreations.
 */
internal class EditorGlRenderer : GLSurfaceView.Renderer {

    private val exposureProgram = ExposureGlProgram()
    private val geometryProgram = GeometryCaGlProgram()
    private val geometryTarget = PassAFrameBuffer()

    private var proxyTextureId: Int = 0
    private var fullTextureId: Int = 0
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var viewportWidth: Int = 1
    private var viewportHeight: Int = 1

    @Volatile
    private var stagedFullBitmap: Bitmap? = null

    @Volatile
    private var stagedProxyBitmap: Bitmap? = null

    @Volatile
    private var needsFullUpload: Boolean = false

    @Volatile
    private var needsProxyUpload: Boolean = false

    @Volatile
    var preferEs3: Boolean = true

    @Volatile
    var exposureEv: Float = 0.0f

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
        needsFullUpload = stagedFullBitmap != null
        needsProxyUpload = stagedProxyBitmap != null
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
        uploadPendingBitmapsIfNeeded()
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
     * Stages a decoded full-preview bitmap and its 1024px proxy for upload on the GL thread.
     */
    fun setSourceBitmaps(fullBitmap: Bitmap, proxyBitmap: Bitmap) {
        imageWidth = fullBitmap.width.coerceAtLeast(1)
        imageHeight = fullBitmap.height.coerceAtLeast(1)
        stagedFullBitmap = fullBitmap
        stagedProxyBitmap = proxyBitmap
        needsFullUpload = true
        needsProxyUpload = true
    }

    /**
     * Clears bitmap references when the hosting activity is destroyed so recycled bitmaps are
     * never touched on a late GL draw.
     */
    fun clearBitmaps() {
        stagedFullBitmap = null
        stagedProxyBitmap = null
        needsFullUpload = false
        needsProxyUpload = false
    }

    /**
     * Width/height ratio of the upright source texture, used when geometry is neutral.
     */
    fun sourceAspect(): Float = imageWidth.toFloat() / imageHeight.toFloat()

    /**
     * Pushes the geometry and lateral-CA parameters of [graph] onto the render thread.
     *
     * The inverse homography and the output aspect ratio are compiled on the calling (UI) thread
     * from pure Kotlin math, so the GL thread only uploads uniforms.
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
        exposureProgram.draw(
            textureId = geometryTarget.textureId,
            exposureEv = exposureEv,
            compareOriginal = false,
            scaleX = scaleX,
            scaleY = scaleY,
            offsetX = canvasOffsetX,
            offsetY = canvasOffsetY
        )
        return true
    }

    private fun drawExposure(textureId: Int, comparingOriginal: Boolean, aspect: Float) {
        val (scaleX, scaleY) = computeLetterboxScale(aspect)
        exposureProgram.draw(
            textureId = textureId,
            exposureEv = exposureEv,
            compareOriginal = comparingOriginal,
            scaleX = scaleX,
            scaleY = scaleY,
            offsetX = canvasOffsetX,
            offsetY = canvasOffsetY
        )
    }

    private fun logGeometryFallbackOnce() {
        if (geometryFallbackLogged) return
        geometryFallbackLogged = true
        Timber.w("Pass A target unavailable; preview falls back to the un-warped source")
    }

    private fun uploadPendingBitmapsIfNeeded() {
        if (needsFullUpload) {
            val full = stagedFullBitmap
            needsFullUpload = false
            if (full != null && !full.isRecycled) {
                fullTextureId = uploadTexture(full, fullTextureId)
            }
        }
        if (needsProxyUpload) {
            val proxy = stagedProxyBitmap
            needsProxyUpload = false
            if (proxy != null && !proxy.isRecycled) {
                proxyTextureId = uploadTexture(proxy, proxyTextureId)
            }
        }
    }

    private fun uploadTexture(bitmap: Bitmap, existingTexId: Int): Int {
        val texIds = IntArray(1)
        if (existingTexId != 0) {
            texIds[0] = existingTexId
        } else {
            GLES20.glGenTextures(1, texIds, 0)
        }
        val texId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE
        )
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return texId
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

    private var geometryFallbackLogged = false

    companion object {
        private const val PROXY_MAX_EDGE_PX = 1024
        private const val MAX_TARGET_EDGE_PX = 2048
        private const val MAX_CANVAS_ZOOM = 8.0f
        private val IDENTITY_MATRIX = floatArrayOf(
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        )

        fun createProxyBitmap(source: Bitmap): Bitmap {
            val maxEdge = maxOf(source.width, source.height)
            if (maxEdge <= PROXY_MAX_EDGE_PX) return source
            val scale = PROXY_MAX_EDGE_PX.toFloat() / maxEdge.toFloat()
            val w = (source.width * scale).roundToInt().coerceAtLeast(1)
            val h = (source.height * scale).roundToInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(source, w, h, true)
        }
    }
}
