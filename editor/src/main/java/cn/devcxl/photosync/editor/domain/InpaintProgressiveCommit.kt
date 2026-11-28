package cn.devcxl.photosync.editor.domain

/**
 * Stroke handling of a progressively committed inpaint run (spec §7.3 / §7.4).
 *
 * A run commits one batch at a time. When it is cancelled or a later batch fails, the user must
 * repaint only what is still missing: strokes whose painted capsule lies entirely inside committed
 * tiles are dropped, while any stroke that still intersects an uncommitted tile core is kept.
 * Keeping a partially committed stroke is the safe side of the trade-off: it may re-run an already
 * repaired pixel, but it never leaves a visible hole unpainted.
 */
object InpaintProgressiveCommit {

    /**
     * Keeps only the strokes that still cover an uncommitted tile.
     *
     * @param strokes Strokes in pre-crop normalized coordinates, oldest first.
     * @param remainingCores Uncommitted tile cores in source pixels, in execution order.
     * @param sourceWidth Pre-crop source width the strokes resolve against.
     * @param sourceHeight Pre-crop source height.
     * @return Every stroke that touches a remaining core; empty when the whole plan committed.
     */
    fun retainUncommitted(
        strokes: List<InpaintStroke>,
        remainingCores: List<InpaintPixelRect>,
        sourceWidth: Int,
        sourceHeight: Int
    ): List<InpaintStroke> {
        if (remainingCores.isEmpty()) return emptyList()
        if (sourceWidth <= 0 || sourceHeight <= 0) return strokes
        return strokes.filter { stroke ->
            val bounds = stroke.bounds(sourceWidth, sourceHeight)
            !bounds.isEmpty && remainingCores.any { core -> core.intersects(bounds) }
        }
    }
}
