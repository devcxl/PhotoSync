package cn.devcxl.photosync.editor.export

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.opengl.GLES20

import androidx.exifinterface.media.ExifInterface
import org.junit.Assume

import cn.devcxl.photosync.editor.contract.ExifTagWriter
import cn.devcxl.photosync.editor.contract.GallerySink
import cn.devcxl.photosync.editor.domain.ColumnBandTiling
import cn.devcxl.photosync.editor.domain.DeviceGlBudget
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.SourceDimensions
import cn.devcxl.photosync.editor.domain.SourceResidencyPlanner

/**
 * Instrumented-test harness for the real EGL + shader + `libjpeg-turbo` bake chain.
 *
 * Renders either one full-frame band or the production `1920 x 512 (halo=64)` column-band loop and
 * captures the resulting RGBA8 pixels before JPEG quantization, so identity and seam assertions can
 * be `<= 1/255` instead of JPEG-lossy comparisons.
 */
internal class BakeTestSession private constructor(
    private val egl: SurfacelessEglContext,
    private val chain: BakePassChain,
    private val source: BakeSourceTextures,
    private val prepass: BakePrepass,
    private val frame: BakeFrame,
    private val inverseMatrix: cn.devcxl.photosync.editor.domain.Mat3,
    val cropWidth: Int,
    val cropHeight: Int
) : AutoCloseable {

    /** Renders the whole crop in one FBO and returns top-down ARGB pixels. */
    fun renderFullFrame(): IntArray {
        val band = ColumnBandTiling.fullFrame(cropWidth, cropHeight)
        val framebuffer = chain.renderBand(frame.copy(band = band))
        check(framebuffer != 0) { "Full-frame band render failed" }
        val buffer = ByteBuffer.allocateDirect(cropWidth * cropHeight * BYTES_PER_PIXEL)
            .order(ByteOrder.nativeOrder())
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 4)
        GLES20.glReadPixels(
            0, 0, cropWidth, cropHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return bottomUpToArgb(buffer, cropWidth, cropHeight)
    }

    /** Runs the production band loop with a capturing sink and returns top-down ARGB pixels. */
    fun renderTiled(): IntArray {
        val sink = CapturingStripSink(cropWidth, cropHeight)
        BakeBandEncoder(
            chain = chain,
            source = source,
            sink = sink,
            frameTemplate = frame,
            sourceToPostCropInverse = inverseMatrix,
            cropWidth = cropWidth,
            cropHeight = cropHeight
        ).encode { true }
        return sink.argb
    }

    override fun close() {
        prepass.close()
        source.close()
        chain.close()
        egl.close()
    }

    companion object {

        private const val BYTES_PER_PIXEL = 4

        /**
         * Opens [sourcePath] through the real residency planner and prepares the Pre-pass.
         *
         * @return The session, or `null` when the device cannot run the full RGBA16F export chain.
         */
        fun open(context: Context, sourcePath: String, graph: EditGraph): BakeTestSession? {
            val dimensions = BakeSourceProbe.dimensions(sourcePath, isRaw = false) ?: return null
            val egl = SurfacelessEglContext.create() ?: return null
            val chain = BakePassChain()
            if (!chain.init()) {
                egl.close()
                Assume.assumeTrue("Device lacks an RGBA16F-capable GLES 3 export chain", false)
                return null
            }
            val source = BakeSourceTextures.open(
                sourcePath = sourcePath,
                isRaw = false,
                plan = SourceResidencyPlanner.plan(
                    SourceDimensions(dimensions.first, dimensions.second),
                    DeviceGlBudget(egl.glMaxTextureSize, Long.MAX_VALUE / 2)
                ),
                sourceLongEdge = maxOf(dimensions.first, dimensions.second)
            ) ?: run {
                chain.close()
                egl.close()
                return null
            }
            val outSize = ColumnBandTiling.outputSize(source.width, source.height, graph.geometry)
            chain.stageGraph(warpField = null, skinMask = null, graphCurve = graph.color.curve, lut = null)
            val frame = BakeFrameFactory.create(graph, source, outSize.first, outSize.second)
            val prepass = BakePrepass(chain, source)
            prepass.prepare(frame, outSize.first, outSize.second)
            return BakeTestSession(
                egl = egl,
                chain = chain,
                source = source,
                prepass = prepass,
                frame = frame,
                inverseMatrix = BakeFrameFactory.inverseMatrix(graph, source),
                cropWidth = outSize.first,
                cropHeight = outSize.second
            )
        }

        private fun bottomUpToArgb(buffer: ByteBuffer, width: Int, height: Int): IntArray {
            val pixels = IntArray(width * height)
            for (row in 0 until height) {
                val sourceRow = height - 1 - row
                for (column in 0 until width) {
                    val offset = (sourceRow * width + column) * BYTES_PER_PIXEL
                    pixels[row * width + column] = argbAt(buffer, offset)
                }
            }
            return pixels
        }
    }
}

/** Copies production strips into one top-down ARGB image for pixel assertions. */
internal class CapturingStripSink(private val width: Int, private val height: Int) : StripSink {

    val argb: IntArray = IntArray(width * height)

    private var nextTopRow = 0

    override fun writeStrip(rgba: ByteBuffer, rows: Int) {
        val view = rgba.duplicate().order(ByteOrder.nativeOrder())
        view.rewind()
        for (row in 0 until rows) {
            val imageRow = nextTopRow + (rows - 1 - row)
            for (column in 0 until width) {
                val offset = (row * width + column) * 4
                argb[imageRow * width + column] = argbAt(view, offset)
            }
        }
        nextTopRow += rows
    }
}

/** Minimal [GallerySink] that writes into a private directory and applies EXIF through ExifInterface. */
internal class FileGallerySink(private val directory: File) : GallerySink {

    override fun save(
        displayName: String,
        mime: String,
        write: (java.io.OutputStream) -> Unit,
        writeExif: ((ExifTagWriter) -> Unit)?
    ): Uri? {
        if (!directory.exists() && !directory.mkdirs()) return null
        val target = File(directory, displayName)
        target.outputStream().use(write)
        if (writeExif != null) {
            val adapter = FileExifTagWriter(target)
            writeExif(adapter)
            adapter.persist()
        }
        return Uri.fromFile(target)
    }
}

/** [ExifTagWriter] over a real file, saving lazily so one bake writes one EXIF pass. */
internal class FileExifTagWriter(file: File) : ExifTagWriter {

    private val exif = ExifInterface(file)

    override fun setAttribute(tag: String, value: String?) = exif.setAttribute(tag, value)

    override fun setLatLong(latitude: Double, longitude: Double) = exif.setLatLong(latitude, longitude)

    override fun setAltitude(altitude: Double) = exif.setAltitude(altitude)

    /** Writes every staged attribute. */
    fun persist() = exif.saveAttributes()
}

/** Builds deterministic source JPEGs with shooting EXIF for the bake assertions. */
internal object BakeTestSources {

    /**
     * Draws a gradient with red/green/blue corner markers, then writes [orientation] EXIF.
     */
    fun createJpeg(
        directory: File,
        name: String,
        width: Int,
        height: Int,
        orientation: Int = ExifInterface.ORIENTATION_NORMAL,
        make: String = "SONY",
        model: String = "ILCE-7M4",
        dateTime: String = "2026:11:22 14:30:45"
    ): File {
        if (!directory.exists()) directory.mkdirs()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.shader = android.graphics.LinearGradient(
            0.0f, 0.0f, width.toFloat(), height.toFloat(),
            intArrayOf(Color.rgb(20, 30, 60), Color.rgb(220, 180, 120), Color.rgb(250, 245, 235)),
            null,
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0.0f, 0.0f, width.toFloat(), height.toFloat(), paint)
        val marker = Paint()
        marker.color = Color.rgb(220, 30, 40)
        canvas.drawRect(0.0f, 0.0f, width / 6.0f, height / 6.0f, marker)
        marker.color = Color.rgb(30, 200, 70)
        canvas.drawRect(width * 5.0f / 6.0f, 0.0f, width.toFloat(), height / 6.0f, marker)
        val file = File(directory, name)
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 98, out)
        }
        bitmap.recycle()
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setAttribute(ExifInterface.TAG_MAKE, make)
            setAttribute(ExifInterface.TAG_MODEL, model)
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime)
            setAttribute(ExifInterface.TAG_F_NUMBER, "2.8")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "400")
            saveAttributes()
        }
        return file
    }

    /** Decodes [path] exactly like the editor source path (orientation applied) into ARGB. */
    fun decodedArgb(path: String): IntArray {
        val decoded = android.graphics.BitmapFactory.decodeFile(
            path,
            android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        ) ?: return IntArray(0)
        val orientation = ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val upright = cn.devcxl.photosync.editor.render.EditorBitmapLoader
            .applyExifOrientation(decoded, orientation)
        val pixels = IntArray(upright.width * upright.height)
        upright.getPixels(pixels, 0, upright.width, 0, 0, upright.width, upright.height)
        upright.recycle()
        return pixels
    }

    /** Largest per-channel absolute difference between two same-sized ARGB images. */
    fun maxChannelDelta(left: IntArray, right: IntArray): Int {
        var maxDelta = 0
        for (index in left.indices) {
            val a = left[index]
            val b = right[index]
            maxDelta = maxOf(
                maxDelta,
                kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
                kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
                kotlin.math.abs((a and 0xFF) - (b and 0xFF))
            )
        }
        return maxDelta
    }
}

/** Reads one RGBA8 texel as ARGB. */
internal fun argbAt(buffer: ByteBuffer, offset: Int): Int {
    val red = buffer.get(offset).toInt() and 0xFF
    val green = buffer.get(offset + 1).toInt() and 0xFF
    val blue = buffer.get(offset + 2).toInt() and 0xFF
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
