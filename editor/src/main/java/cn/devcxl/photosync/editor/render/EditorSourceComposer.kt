package cn.devcxl.photosync.editor.render

/**
 * Produces the Pre-Pass A copy of an [EditorSource] with the graph's repair patches blended in.
 *
 * The copy owns its pixels, so the caller keeps the untouched decode for the Before/After
 * comparison and for every later recomposition (an undone erase must not re-read the RAW).
 */
internal object EditorSourceComposer {

    /**
     * Composes [overlay] into a private copy of [source].
     *
     * @return The composed source, or `null` when the pixels cannot be copied; the caller then keeps
     *   showing the original instead of a half-blended frame.
     */
    fun compose(source: EditorSource, overlay: InpaintOverlay): EditorSource? =
        when (source) {
            is EditorSource.BitmapSource -> {
                val composed = overlay.applyToBitmapCopy(source.full) ?: return null
                EditorSource.BitmapSource(
                    full = composed,
                    proxy = EditorBitmapLoader.createProxyBitmap(composed)
                )
            }
            is EditorSource.LinearRawSource -> {
                val composed = overlay.applyToLinearCopy(source.full) ?: return null
                EditorSource.LinearRawSource(
                    full = composed,
                    proxy = composed.downscaledTo(EditorSourceLoader.LINEAR_PROXY_MAX_EDGE_PX)
                )
            }
        }
}
