package cn.devcxl.photosync.editor.export

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

import android.opengl.GLES20
import android.opengl.GLES30
import timber.log.Timber

import cn.devcxl.photosync.editor.domain.ColumnBand
import cn.devcxl.photosync.editor.domain.ColumnBandTiling
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.SourceSlicePlanner
import cn.devcxl.photosync.editor.domain.SourceSliceRect

/**
 * Streams one baked frame strip by strip into [sink].
 *
 * For every row strip the encoder renders each column band through [chain], reads its halo-free
 * inner region tightly-packed via `glReadPixels` (`GL_PACK_ROW_LENGTH = 0`) into a reusable
 * band buffer, and copies each row into the full-width `RGBA8` strip buffer at column `band.x0`.
 *
 * Avoiding `GL_PACK_ROW_LENGTH` / `GL_PACK_SKIP_PIXELS` on client-memory `glReadPixels` guarantees
 * identical pixel layout across all Android Adreno/Mali/PowerVR GLES 3.0 drivers, many of which
 * ignore non-zero pack-row-length when no PBO is bound.
 */
internal class BakeBandEncoder(
    private val chain: BakePassChain,
    private val source: BakeSourceTextures,
    private val sink: StripSink,
    private val frameTemplate: BakeFrame,
    private val sourceToPostCropInverse: Mat3,
    private val cropWidth: Int,
    private val cropHeight: Int
) {

    /** One band's source binding: the texture to sample and the slice rectangle it represents. */
    private data class SourceBinding(val textureId: Int, val sliceRect: SourceSliceRect)

    /**
     * Renders and encodes every strip.
     *
     * @param isActive Cancellation probe evaluated before every band; a `false` result aborts the
     *   bake with a [CancellationException] instead of leaving a half-written gallery entry.
     * @throws IOException when a band cannot be rendered or read back.
     */
    fun encode(isActive: () -> Boolean) {
        val stripRows = minOf(ColumnBandTiling.STRIP_HEIGHT, cropHeight)
        val maxBandWidth = minOf(ColumnBandTiling.MAX_BAND_WIDTH, cropWidth)
        val rowBuffer = ByteBuffer
            .allocateDirect(cropWidth * stripRows * BYTES_PER_RGBA_PIXEL)
            .order(ByteOrder.nativeOrder())
        val bandBuffer = ByteBuffer
            .allocateDirect(maxBandWidth * stripRows * BYTES_PER_RGBA_PIXEL)
            .order(ByteOrder.nativeOrder())
        val rowScratch = ByteArray(maxBandWidth * BYTES_PER_RGBA_PIXEL)
        var renderedBands = 0
        for (strip in ColumnBandTiling.rowStrips(cropHeight)) {
            for (band in ColumnBandTiling.columnBands(cropWidth, cropHeight, strip)) {
                if (!isActive()) {
                    throw CancellationException("Bake cancelled before band $renderedBands")
                }
                renderBand(band, bandBuffer, rowBuffer, rowScratch)
                renderedBands++
            }
            rowBuffer.rewind()
            sink.writeStrip(rowBuffer, strip.height)
        }
        Timber.d("Baked %d column bands at %dx%d", renderedBands, cropWidth, cropHeight)
    }

    private fun renderBand(
        band: ColumnBand,
        bandBuffer: ByteBuffer,
        rowBuffer: ByteBuffer,
        rowScratch: ByteArray
    ) {
        val binding = bindSource(band)
        if (binding.textureId == 0) {
            throw IOException("Bake could not upload the source of band ${band.x0}")
        }
        val frame = frameTemplate.copy(
            sourceTextureId = binding.textureId,
            srcSliceRect = binding.sliceRect,
            band = band
        )
        val framebuffer = chain.renderBand(frame)
        if (framebuffer == 0) {
            throw IOException("Bake could not render band ${band.x0}")
        }
        readBand(band, framebuffer, bandBuffer, rowBuffer, rowScratch)
    }

    private fun bindSource(band: ColumnBand): SourceBinding {
        if (!source.usesSliceUploads) {
            return SourceBinding(source.fullTexture, SourceSliceRect.FULL)
        }
        val request = SourceSlicePlanner.plan(
            postCropToPreCrop = sourceToPostCropInverse,
            band = band,
            cropWidth = cropWidth,
            cropHeight = cropHeight,
            sourceWidth = source.width,
            sourceHeight = source.height,
            maxEdgePx = source.maxSliceEdgePx,
            extraMarginUv = frameTemplate.maxWarpDisplacementUv
        )
        if (request.exceedsUploadLimit) {
            val reduced = source.uploadReducedFullFrame(source.maxSliceEdgePx)
            if (reduced != 0) {
                Timber.w("Band %d needs an oversized slice; using the reduced frame", band.x0)
                return SourceBinding(reduced, SourceSliceRect.FULL)
            }
        }
        return SourceBinding(source.uploadSlice(request), request.rect)
    }

    private fun readBand(
        band: ColumnBand,
        framebuffer: Int,
        bandBuffer: ByteBuffer,
        rowBuffer: ByteBuffer,
        rowScratch: ByteArray
    ) {
        val plan = ColumnBandTiling.readPlan(band, cropWidth)
        bandBuffer.rewind()
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, plan.packAlignment)
        GLES20.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0)
        GLES20.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, 0)
        GLES20.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, 0)
        GLES20.glReadPixels(
            plan.readX,
            plan.readY,
            plan.readWidth,
            plan.readHeight,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            bandBuffer
        )
        val error = GLES20.glGetError()
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (error != GLES20.GL_NO_ERROR) {
            throw IOException("glReadPixels failed for band ${band.x0} (0x${error.toString(16)})")
        }
        scatterBandIntoStrip(
            bandBuffer = bandBuffer,
            rowBuffer = rowBuffer,
            rowScratch = rowScratch,
            bandWidth = plan.readWidth,
            bandHeight = plan.readHeight,
            bandX0 = band.x0,
            cropWidth = cropWidth
        )
    }

    internal companion object {
        const val BYTES_PER_RGBA_PIXEL = 4

        /**
         * Copies a tightly-packed [bandWidth] x [bandHeight] `RGBA8` band into its column slot
         * `[bandX0, bandX0 + bandWidth)` inside the full-width [cropWidth] strip buffer.
         */
        fun scatterBandIntoStrip(
            bandBuffer: ByteBuffer,
            rowBuffer: ByteBuffer,
            rowScratch: ByteArray,
            bandWidth: Int,
            bandHeight: Int,
            bandX0: Int,
            cropWidth: Int
        ) {
            val bandRowBytes = bandWidth * BYTES_PER_RGBA_PIXEL
            val stripRowBytes = cropWidth * BYTES_PER_RGBA_PIXEL
            val columnByteOffset = bandX0 * BYTES_PER_RGBA_PIXEL
            val src = bandBuffer.duplicate()
            val dst = rowBuffer.duplicate()
            src.position(0)
            for (row in 0 until bandHeight) {
                src.get(rowScratch, 0, bandRowBytes)
                dst.position(row * stripRowBytes + columnByteOffset)
                dst.put(rowScratch, 0, bandRowBytes)
            }
        }
    }
}
