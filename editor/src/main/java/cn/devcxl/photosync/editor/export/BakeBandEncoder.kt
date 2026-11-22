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
import cn.devcxl.photosync.editor.domain.SourceSliceRect
import cn.devcxl.photosync.editor.domain.SourceSlicePlanner

/**
 * Streams one baked frame strip by strip into [sink].
 *
 * For every row strip the encoder loops the column bands, renders each one through [chain] and
 * scatters the halo-free inner region into a full-width `RGBA8` row buffer with
 * `GL_PACK_ROW_LENGTH = cropWidth` and `GL_PACK_SKIP_PIXELS = band.x0`. Once a strip is complete the
 * buffer goes straight to `jpeg_write_scanlines`, so peak CPU memory is one crowded strip.
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
        val rowBuffer = ByteBuffer
            .allocateDirect(cropWidth * ColumnBandTiling.STRIP_HEIGHT * BYTES_PER_RGBA_PIXEL)
            .order(ByteOrder.nativeOrder())
        var renderedBands = 0
        for (strip in ColumnBandTiling.rowStrips(cropHeight)) {
            for (band in ColumnBandTiling.columnBands(cropWidth, cropHeight, strip)) {
                if (!isActive()) {
                    throw CancellationException("Bake cancelled before band $renderedBands")
                }
                renderBand(band, rowBuffer)
                renderedBands++
            }
            sink.writeStrip(rowBuffer, strip.height)
        }
        Timber.d("Baked %d column bands at %dx%d", renderedBands, cropWidth, cropHeight)
    }

    private fun renderBand(band: ColumnBand, rowBuffer: ByteBuffer) {
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
        readBand(band, framebuffer, rowBuffer)
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
            maxEdgePx = source.maxSliceEdgePx
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

    private fun readBand(band: ColumnBand, framebuffer: Int, rowBuffer: ByteBuffer) {
        val plan = ColumnBandTiling.readPlan(band, cropWidth)
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, plan.packAlignment)
        GLES20.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, plan.packRowLength)
        GLES20.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, plan.packSkipPixels)
        GLES20.glPixelStorei(GLES30.GL_PACK_SKIP_ROWS, 0)
        GLES20.glReadPixels(
            plan.readX,
            plan.readY,
            plan.readWidth,
            plan.readHeight,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            rowBuffer
        )
        val error = GLES20.glGetError()
        GLES20.glPixelStorei(GLES30.GL_PACK_ROW_LENGTH, 0)
        GLES20.glPixelStorei(GLES30.GL_PACK_SKIP_PIXELS, 0)
        GLES20.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (error != GLES20.GL_NO_ERROR) {
            throw IOException("glReadPixels failed for band ${band.x0} (0x${error.toString(16)})")
        }
    }

    private companion object {
        const val BYTES_PER_RGBA_PIXEL = 4
    }
}
