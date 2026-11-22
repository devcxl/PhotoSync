package cn.devcxl.photosync.editor.render

import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

import android.opengl.GLES20
import android.opengl.GLSurfaceView

import cn.devcxl.photosync.editor.domain.BaseLook
import cn.devcxl.photosync.wrapper.LinearRawBuffer

/**
 * GLES 3.0 renderer for the editor preview canvas.
 *
 * Maintains both a `1024px` drag-proxy texture (`PROXY_1024_FAST`) and a full-preview texture
 * (`IDLE_FULL_16F`). RAW sources are 16-bit linear `GL_RGBA16F` textures so the shader keeps
 * the full dynamic range; JPEG sources stay 8-bit sRGB. The staged [EditorSource] is retained
 * across EGL surface recreation so returning from the background never leaves a black canvas.
 */
internal class EditorGlRenderer : GLSurfaceView.Renderer {

    private val program = ExposureGlProgram()
    private var proxyTextureId: Int = 0
    private var fullTextureId: Int = 0
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
    var baseLook: BaseLook = BaseLook.CAMERA_DEFAULT

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        program.init(preferEs3)
        proxyTextureId = 0
        fullTextureId = 0
        val source = stagedSource
        needsFullUpload = source != null
        needsProxyUpload = source != null
        applySourceMetadata(source)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
    }

    override fun onDrawFrame(gl: GL10?) {
        uploadPendingTextures()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val activeTex = if (useFastProxy && proxyTextureId != 0) {
            proxyTextureId
        } else {
            fullTextureId
        }
        if (activeTex == 0) return

        val (scaleX, scaleY) = computeLetterboxScale()
        program.draw(
            ExposureDrawParams(
                textureId = activeTex,
                exposureEv = exposureEv,
                compareOriginal = compareOriginal,
                scaleX = scaleX,
                scaleY = scaleY,
                isLinearInput = linearInput,
                baseLook = baseLook,
                autoBrightGain = autoBrightGain
            )
        )
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

    private fun applySourceMetadata(source: EditorSource?) {
        if (source == null) return
        imageWidth = source.width.coerceAtLeast(1)
        imageHeight = source.height.coerceAtLeast(1)
        linearInput = source.isLinear
        autoBrightGain = (source as? EditorSource.LinearRawSource)?.autoBrightGain ?: 1.0f
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
        // The rejected half-float attempt already deleted `existing`, so allocate a new name.
        return EditorTextureUploader.uploadLinearAsBytes(buffer, existingTextureId = 0)
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
}
