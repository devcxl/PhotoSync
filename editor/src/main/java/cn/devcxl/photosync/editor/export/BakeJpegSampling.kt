package cn.devcxl.photosync.editor.export

/**
 * Smallest power-of-two `BitmapFactory` sample size whose decoded long edge fits [maxLongEdge].
 *
 * Unlike the preview loader (which accepts up to `2x` overshoot), a bake must never upload a
 * texture larger than the device limit, so the loop halves until the edge actually fits.
 *
 * @param maxLongEdge Upload budget; `<= 0` keeps the source resolution.
 */
internal fun jpegSampleSizeFor(width: Int, height: Int, maxLongEdge: Int): Int {
    if (maxLongEdge <= 0 || width <= 0 || height <= 0) return 1
    val longEdge = maxOf(width, height)
    var sampleSize = 1
    while (longEdge / sampleSize > maxLongEdge) {
        sampleSize *= 2
    }
    return sampleSize
}
