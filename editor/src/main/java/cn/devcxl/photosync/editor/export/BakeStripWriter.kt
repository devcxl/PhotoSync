package cn.devcxl.photosync.editor.export

import java.io.OutputStream

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.wrapper.JpegStreamWriter

/**
 * Encodes the column-band bake stream into the container requested by a [BakeRequest].
 *
 * Both writers share [BakeBandEncoder] and only differ in the [StripSink] and the trailing
 * container chores: `libjpeg-turbo` uses `finish()` on the native encoder, while the PNG path
 * deflates the tail and writes `IEND`.
 */
internal object BakeStripWriter {

    /**
     * Streams every band of [frame] into [output] in [request]'s format.
     *
     * @param isActive Cancellation probe checked before every band and PNG row.
     */
    fun write(
        output: OutputStream,
        request: BakeRequest,
        graph: EditGraph,
        chain: BakePassChain,
        source: BakeSourceTextures,
        frame: BakeFrame,
        outWidth: Int,
        outHeight: Int,
        isActive: () -> Boolean
    ) {
        when (request.outputFormat) {
            BakeOutputFormat.JPEG -> writeJpeg(
                output, request, graph, chain, source, frame, outWidth, outHeight, isActive
            )
            BakeOutputFormat.PNG_RGBA -> writePng(
                output, request, graph, chain, source, frame, outWidth, outHeight, isActive
            )
        }
    }

    private fun writeJpeg(
        output: OutputStream,
        request: BakeRequest,
        graph: EditGraph,
        chain: BakePassChain,
        source: BakeSourceTextures,
        frame: BakeFrame,
        outWidth: Int,
        outHeight: Int,
        isActive: () -> Boolean
    ) {
        JpegStreamWriter(output, outWidth, outHeight, request.jpegQuality).use { jpeg ->
            encodeBands(chain, source, JpegStripSink(jpeg), frame, graph, outWidth, outHeight, isActive)
            jpeg.finish()
        }
    }

    private fun writePng(
        output: OutputStream,
        request: BakeRequest,
        graph: EditGraph,
        chain: BakePassChain,
        source: BakeSourceTextures,
        frame: BakeFrame,
        outWidth: Int,
        outHeight: Int,
        isActive: () -> Boolean
    ) {
        PngStripSink(output, outWidth, outHeight, isActive = isActive).use { png ->
            PngExifPayload.fromSource(request.sourcePath, request.isRaw, outWidth, outHeight)
                ?.let(png::writeExifChunk)
            encodeBands(chain, source, png, frame, graph, outWidth, outHeight, isActive)
            png.finish()
        }
    }

    private fun encodeBands(
        chain: BakePassChain,
        source: BakeSourceTextures,
        sink: StripSink,
        frame: BakeFrame,
        graph: EditGraph,
        outWidth: Int,
        outHeight: Int,
        isActive: () -> Boolean
    ) {
        BakeBandEncoder(
            chain = chain,
            source = source,
            sink = sink,
            frameTemplate = frame,
            sourceToPostCropInverse = BakeFrameFactory.inverseMatrix(graph, source),
            cropWidth = outWidth,
            cropHeight = outHeight
        ).encode(isActive)
    }
}
