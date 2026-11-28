package cn.devcxl.photosync.editor.export

import java.io.File

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.ParcelFileDescriptor

import cn.devcxl.photosync.editor.domain.InpaintBBox
import cn.devcxl.photosync.editor.domain.InpaintPatchRef
import cn.devcxl.photosync.editor.vision.BitmapInpaintPatchCodec
import cn.devcxl.photosync.editor.vision.SkyMask
import cn.devcxl.photosync.editor.vision.SubjectMask

/**
 * Fixtures shared by the 24MP AI golden regression: a synthetic `6000 x 4000` JPEG, the subject and
 * sky masks the editor would have stored, the green inpaint patch and the read-back helpers.
 *
 * Every fixture is deterministic so a failure names the same pixel on every device. The masks are
 * built at model resolution (`600 x 400`, `512 x 342`) because that is what MODNet and the classic
 * sky segmenter produce; the bake upsamples them through the same band mapping the app uses.
 */
internal object AiGoldenFixtures {

    /** Photo id the fixtures are staged under; `AiGoldenFixtures` owns its directory cleanup. */
    const val PHOTO_ID = 4242L

    const val WIDTH = 6000
    const val HEIGHT = 4000

    private const val MASK_WIDTH = 600
    private const val MASK_HEIGHT = 400
    private const val RAMP_START = 0.2667f
    private const val RAMP_END = 0.3733f
    private const val SKY_MASK_WIDTH = 512
    private const val SKY_MASK_HEIGHT = 342
    private const val PATCH_SIDE = 400
    private const val PATCH_FEATHER_PX = 40
    private const val PATCH_LEFT = 1750
    private const val PATCH_TOP = 1800
    private const val GREEN = 0xFF2FA84F.toInt()

    /** Draws the shared dark-blue → cream diagonal gradient and writes it as a quality-96 JPEG. */
    fun createJpeg(directory: File, name: String): File {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            shader = LinearGradient(
                0.0f, 0.0f, WIDTH.toFloat(), HEIGHT.toFloat(),
                intArrayOf(Color.rgb(20, 30, 60), Color.rgb(220, 180, 120), Color.rgb(250, 245, 235)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0.0f, 0.0f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)
        val file = File(directory, name)
        file.parentFile?.mkdirs()
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    /** Subject mask with a left-to-right alpha ramp that crosses the first column-band boundary. */
    fun cutoutMask(): SubjectMask {
        val alpha = FloatArray(MASK_WIDTH * MASK_HEIGHT) { index ->
            val u = (index % MASK_WIDTH) / (MASK_WIDTH - 1).toFloat()
            when {
                u < RAMP_START -> 1.0f
                u > RAMP_END -> 0.0f
                else -> ((RAMP_END - u) / (RAMP_END - RAMP_START)).coerceIn(0.0f, 1.0f)
            }
        }
        return SubjectMask(MASK_WIDTH, MASK_HEIGHT, alpha)
    }

    /** Sky mask covering the top 38 % of the frame with a soft horizon band. */
    fun skyMask(): SkyMask {
        val probability = FloatArray(SKY_MASK_WIDTH * SKY_MASK_HEIGHT) { index ->
            val v = (index / SKY_MASK_WIDTH) / (SKY_MASK_HEIGHT - 1).toFloat()
            when {
                v < 0.38f -> 1.0f
                v > 0.50f -> 0.0f
                else -> ((0.50f - v) / 0.12f).coerceIn(0.0f, 1.0f)
            }
        }
        return SkyMask(SKY_MASK_WIDTH, SKY_MASK_HEIGHT, probability, coverageRatio = 0.38f)
    }

    /** Lossless green patch plus its feathered coverage, encoded exactly like the P2 tool does. */
    fun greenPatchPayload(): Pair<ByteArray, ByteArray> {
        val argb = IntArray(PATCH_SIDE * PATCH_SIDE) { GREEN }
        val coverage = ByteArray(PATCH_SIDE * PATCH_SIDE) { index ->
            val row = index / PATCH_SIDE
            val column = index % PATCH_SIDE
            val edge = minOf(row, column, PATCH_SIDE - 1 - row, PATCH_SIDE - 1 - column)
            (edge.coerceAtMost(PATCH_FEATHER_PX) * 255 / PATCH_FEATHER_PX).toByte()
        }
        val patchPng = checkNotNull(BitmapInpaintPatchCodec.encodeRgb(argb, PATCH_SIDE, PATCH_SIDE))
        val maskPng = checkNotNull(BitmapInpaintPatchCodec.encodeAlpha(coverage, PATCH_SIDE, PATCH_SIDE))
        return patchPng to maskPng
    }

    /** Patch reference whose bbox spans the `1920 px` column-band boundary. */
    fun greenPatchRef(patchRelPath: String, maskRelPath: String, sha256: String): InpaintPatchRef =
        InpaintPatchRef(
            bbox = InpaintBBox(
                left = PATCH_LEFT.toFloat() / WIDTH,
                top = PATCH_TOP.toFloat() / HEIGHT,
                right = (PATCH_LEFT + PATCH_SIDE).toFloat() / WIDTH,
                bottom = (PATCH_TOP + PATCH_SIDE).toFloat() / HEIGHT
            ),
            patchRelPath = patchRelPath,
            maskRelPath = maskRelPath,
            sha256 = sha256
        )

    /** Identity `.cube` document for a 2-sample grid; exercises the LUT path without recolouring. */
    fun identityLutText(): String = buildString {
        appendLine("TITLE \"golden_identity\"")
        appendLine("LUT_3D_SIZE 2")
        for (blue in 0..1) {
            for (green in 0..1) {
                for (red in 0..1) {
                    appendLine("$red $green $blue")
                }
            }
        }
    }

    /** Decodes an exported file into a mutable-free `ARGB_8888` bitmap owned by the caller. */
    fun decode(file: File): Bitmap = checkNotNull(
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        )
    ) { "exported image could not be decoded" }

    /** Alpha of one exported pixel. */
    fun alphaAt(bitmap: Bitmap, x: Int, y: Int): Int = bitmap.getPixel(x, y) ushr 24

    /** Red channel of an `ARGB` pixel. */
    fun pixelRed(pixel: Int): Int = channel(pixel, 16)

    /** Alpha channel of an `ARGB` pixel. */
    fun pixelAlpha(pixel: Int): Int = pixel ushr 24

    /** One channel of an `ARGB` pixel. */
    fun channel(pixel: Int, shift: Int): Int = (pixel shr shift) and 0xFF

    /** Mean RGB of a square region, decoded without allocating the whole frame. */
    fun regionAverage(file: File, left: Int, top: Int, size: Int): IntArray {
        val region = Rect(left, top, left + size, top + size)
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val decoder = try {
            checkNotNull(BitmapRegionDecoder.newInstance(descriptor)) {
                "region decoder unavailable"
            }
        } finally {
            descriptor.close()
        }
        val bitmap = try {
            checkNotNull(decoder.decodeRegion(region, BitmapFactory.Options()))
        } finally {
            decoder.recycle()
        }
        val pixels = IntArray(size * size)
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.recycle()
        var red = 0
        var green = 0
        var blue = 0
        for (pixel in pixels) {
            red += channel(pixel, 16)
            green += channel(pixel, 8)
            blue += channel(pixel, 0)
        }
        val count = pixels.size.coerceAtLeast(1)
        return intArrayOf(red / count, green / count, blue / count)
    }
}
