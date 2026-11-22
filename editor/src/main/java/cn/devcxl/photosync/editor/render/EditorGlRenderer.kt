package cn.devcxl.photosync.editor.render

import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils

/**
 * GLES 3.0 renderer for the editor preview canvas.
 *
 * Maintains both a `1024px` drag-proxy texture (`PROXY_1024_FAST`) and a full-preview
 * texture (`IDLE_FULL_16F`). Retains bitmap references across EGL context/surface
 * recreations so returning from background never leaves a black canvas.
 */
internal class EditorGlRenderer : GLSurfaceView.Renderer {

    private val program = ExposureGlProgram()
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

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        program.init(preferEs3)
        proxyTextureId = 0
        fullTextureId = 0
        needsFullUpload = stagedFullBitmap != null
        needsProxyUpload = stagedProxyBitmap != null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        uploadPendingBitmapsIfNeeded()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val activeTex = if (useFastProxy && proxyTextureId != 0) {
            proxyTextureId
        } else {
            fullTextureId
        }
        if (activeTex == 0) return

        val (scaleX, scaleY) = computeLetterboxScale()
        program.draw(
            textureId = activeTex,
            exposureEv = exposureEv,
            compareOriginal = compareOriginal,
            scaleX = scaleX,
            scaleY = scaleY
        )
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

    private fun computeLetterboxScale(): Pair<Float, Float> {
        val imgAspect = imageWidth.toFloat() / imageHeight.toFloat()
        val viewAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        return if (imgAspect > viewAspect) {
            1.0f to (viewAspect / imgAspect)
        } else {
            (imgAspect / viewAspect) to 1.0f
        }
    }

    companion object {
        private const val PROXY_MAX_EDGE_PX = 1024

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
