package cn.devcxl.photosync.editor.domain

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Exact affine map from a column band's local FBO UV to the post-crop global UV of the final frame.
 *
 * Packed as `vec4(scaleX, scaleY, offsetX, offsetY)` for the `uTileUvTransform` uniform, so the
 * vertex/fragment stages compute `uv_global = uv_fbo * scale + offset`. Every pass that evaluates a
 * global-coordinate effect (Pre-pass guides, film grain, post-crop vignette, dither) shares it.
 */
data class TileUvTransform(
    val scaleX: Float,
    val scaleY: Float,
    val offsetX: Float,
    val offsetY: Float
) {

    /** Maps band-local [u] to post-crop global U. */
    fun mapU(u: Float): Float = u * scaleX + offsetX

    /** Maps band-local [v] to post-crop global V. */
    fun mapV(v: Float): Float = v * scaleY + offsetY

    /** Packs the transform into the `vec4(scaleX, scaleY, offsetX, offsetY)` uniform layout. */
    fun toVec4(): FloatArray = floatArrayOf(scaleX, scaleY, offsetX, offsetY)

    companion object {

        /** Transform of a frame that is already rendered in global coordinates. */
        val IDENTITY = TileUvTransform(1.0f, 1.0f, 0.0f, 0.0f)
    }
}

/**
 * One row strip of the export: `512` rows high, the last one possibly shorter.
 *
 * @property y0 First post-crop row of the strip, measured from the image top.
 * @property height Valid rows in this strip.
 */
data class RowStrip(val y0: Int, val height: Int)

/**
 * One column band of a row strip, including its `halo` on all four sides.
 *
 * @property x0 Left post-crop column of the valid region.
 * @property y0 Top post-crop row of the valid region.
 * @property validWidth Valid columns in this band (`<= 1920`).
 * @property validHeight Valid rows in this band (`<= 512`).
 * @property halo Halo width in pixels (`64`, `0` for a single full-frame render).
 * @property fboWidth Physical FBO width (`validWidth + 2 * halo`, `<= 2048`).
 * @property fboHeight Physical FBO height (`validHeight + 2 * halo`, `<= 640`).
 * @property tileUv Local FBO UV to post-crop global UV transform.
 */
data class ColumnBand(
    val x0: Int,
    val y0: Int,
    val validWidth: Int,
    val validHeight: Int,
    val halo: Int,
    val fboWidth: Int,
    val fboHeight: Int,
    val tileUv: TileUvTransform
)

/**
 * `glReadPixels` scatter contract that strips the halo and writes each valid row into the correct
 * column offset of the full-width `RGBA8` row buffer.
 *
 * @property readX Readback X inside the band FBO (`halo`).
 * @property readY Readback Y inside the band FBO (`halo`).
 * @property readWidth Valid band width.
 * @property readHeight Valid band height.
 * @property packRowLength `GL_PACK_ROW_LENGTH`, the full cropped frame width.
 * @property packSkipPixels `GL_PACK_SKIP_PIXELS`, the band's left column in the final frame.
 */
data class BandReadPlan(
    val readX: Int,
    val readY: Int,
    val readWidth: Int,
    val readHeight: Int,
    val packRowLength: Int,
    val packSkipPixels: Int,
    val packAlignment: Int = 4
)

/**
 * Splits a cropped full-resolution frame into `1920 x 512` column bands with a `64px` halo.
 *
 * The confinement of the FBO to `2048 x 640` is what lets a 61–100MP export run on any GLES 3.0
 * device regardless of `GL_MAX_TEXTURE_SIZE`, while the exact affine transform and the
 * `GL_PACK_ROW_LENGTH` scatter keep every band seam-free.
 */
object ColumnBandTiling {

    /** Valid rows rendered per row strip; a multiple of 16 so it aligns with 4:2:0 MCU rows. */
    const val STRIP_HEIGHT = 512

    /** Maximum valid band width; `1920 + 2 * 64 = 2048` stays inside every GLES 3.0 driver. */
    const val MAX_BAND_WIDTH = 1920

    /** Halo rendered around each band so spatial filters never read missing neighbours. */
    const val HALO_PX = 64

    /** Physical FBO width limit (`1920 + 2 * 64`). */
    const val MAX_FBO_WIDTH = MAX_BAND_WIDTH + 2 * HALO_PX

    /** Physical FBO height limit (`512 + 2 * 64`). */
    const val MAX_FBO_HEIGHT = STRIP_HEIGHT + 2 * HALO_PX

    /** Number of row strips covering [cropHeight]. */
    fun stripCount(cropHeight: Int): Int {
        require(cropHeight > 0) { "cropHeight must be positive" }
        return ceil(cropHeight.toDouble() / STRIP_HEIGHT).toInt()
    }

    /** Resolves row strip [stripIndex] of a frame [cropHeight] rows high. */
    fun rowStrip(cropHeight: Int, stripIndex: Int): RowStrip {
        require(stripIndex in 0 until stripCount(cropHeight)) { "stripIndex out of range" }
        val y0 = stripIndex * STRIP_HEIGHT
        return RowStrip(y0 = y0, height = minOf(STRIP_HEIGHT, cropHeight - y0))
    }

    /** Number of column bands covering [cropWidth]. */
    fun bandCount(cropWidth: Int): Int {
        require(cropWidth > 0) { "cropWidth must be positive" }
        return ceil(cropWidth.toDouble() / MAX_BAND_WIDTH).toInt()
    }

    /** Resolves column band [bandIndex] of [strip] inside a [cropWidth] x [cropHeight] frame. */
    fun columnBand(
        cropWidth: Int,
        cropHeight: Int,
        strip: RowStrip,
        bandIndex: Int
    ): ColumnBand {
        require(bandIndex in 0 until bandCount(cropWidth)) { "bandIndex out of range" }
        val x0 = bandIndex * MAX_BAND_WIDTH
        return buildBand(
            x0 = x0,
            y0 = strip.y0,
            validWidth = minOf(MAX_BAND_WIDTH, cropWidth - x0),
            validHeight = strip.height,
            cropWidth = cropWidth,
            cropHeight = cropHeight
        )
    }

    /**
     * Enumerates every column band of [strip].
     *
     * @param cropWidth Final cropped frame width.
     * @param cropHeight Final cropped frame height, needed for the UV transform denominator.
     */
    fun columnBands(cropWidth: Int, cropHeight: Int, strip: RowStrip): List<ColumnBand> =
        (0 until bandCount(cropWidth)).map { index ->
            val x0 = index * MAX_BAND_WIDTH
            buildBand(
                x0 = x0,
                y0 = strip.y0,
                validWidth = minOf(MAX_BAND_WIDTH, cropWidth - x0),
                validHeight = strip.height,
                cropWidth = cropWidth,
                cropHeight = cropHeight
            )
        }

    /** Enumerates every row strip of a frame [cropHeight] rows high. */
    fun rowStrips(cropHeight: Int): List<RowStrip> =
        (0 until stripCount(cropHeight)).map { rowStrip(cropHeight, it) }

    /**
     * One band covering the whole cropped frame with no halo, used by the reference full-frame
     * export and by the `1024px` Pre-pass.
     */
    fun fullFrame(cropWidth: Int, cropHeight: Int): ColumnBand {
        require(cropWidth > 0 && cropHeight > 0) { "Frame must be positive" }
        return ColumnBand(
            x0 = 0,
            y0 = 0,
            validWidth = cropWidth,
            validHeight = cropHeight,
            halo = 0,
            fboWidth = cropWidth,
            fboHeight = cropHeight,
            tileUv = TileUvTransform.IDENTITY
        )
    }

    /**
     * Readback scatter contract of [band] inside a frame [cropWidth] pixels wide.
     */
    fun readPlan(band: ColumnBand, cropWidth: Int): BandReadPlan {
        require(cropWidth >= band.x0 + band.validWidth) { "Band exceeds the cropped frame" }
        return BandReadPlan(
            readX = band.halo,
            readY = band.halo,
            readWidth = band.validWidth,
            readHeight = band.validHeight,
            packRowLength = cropWidth,
            packSkipPixels = band.x0
        )
    }

    /**
     * Converts a crop rectangle expressed in frame UV into the exported pixel size.
     *
     * The output width follows the cropped source width at full resolution; the height follows the
     * pipeline's exact post-crop aspect so straightening and perspective keep their geometry.
     *
     * @param sourceWidth Upright source width (before the discrete quarter turn).
     * @param sourceHeight Upright source height (before the discrete quarter turn).
     */
    fun outputSize(sourceWidth: Int, sourceHeight: Int, geometry: GeometryOps): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source must be positive" }
        val turns = ((geometry.rotate90 % TURNS) + TURNS) % TURNS
        val frameWidth = if (turns % 2 != 0) sourceHeight else sourceWidth
        val span = cropSpan(geometry.crop)
        val outWidth = maxOf(1, (frameWidth * span[CROP_WIDTH]).roundToInt())
        val aspect = GeometryMath.outputAspect(geometry, sourceWidth.toFloat() / sourceHeight)
        val outHeight = maxOf(1, (outWidth / aspect).roundToInt())
        return outWidth to outHeight
    }

    private fun buildBand(
        x0: Int,
        y0: Int,
        validWidth: Int,
        validHeight: Int,
        cropWidth: Int,
        cropHeight: Int
    ): ColumnBand {
        val halo = HALO_PX
        val fboWidth = validWidth + 2 * halo
        val fboHeight = validHeight + 2 * halo
        return ColumnBand(
            x0 = x0,
            y0 = y0,
            validWidth = validWidth,
            validHeight = validHeight,
            halo = halo,
            fboWidth = fboWidth,
            fboHeight = fboHeight,
            tileUv = TileUvTransform(
                scaleX = fboWidth.toFloat() / cropWidth.toFloat(),
                scaleY = fboHeight.toFloat() / cropHeight.toFloat(),
                offsetX = (x0 - halo).toFloat() / cropWidth.toFloat(),
                offsetY = (y0 - halo).toFloat() / cropHeight.toFloat()
            )
        )
    }

    private const val TURNS = 4
    private const val CROP_WIDTH = 2
    private const val MIN_CROP_SPAN = 0.05f

    /** Mirrors `PassAGeometryCompiler`'s crop clamping so export and preview agree. */
    private fun cropSpan(crop: CropRect): FloatArray {
        val left = crop.left.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f - MIN_CROP_SPAN) ?: 0.0f
        val top = crop.top.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f - MIN_CROP_SPAN) ?: 0.0f
        val right = crop.right.takeIf { it.isFinite() }
            ?.coerceIn(left + MIN_CROP_SPAN, 1.0f) ?: 1.0f
        val bottom = crop.bottom.takeIf { it.isFinite() }
            ?.coerceIn(top + MIN_CROP_SPAN, 1.0f) ?: 1.0f
        return floatArrayOf(left, top, right - left, bottom - top)
    }
}
