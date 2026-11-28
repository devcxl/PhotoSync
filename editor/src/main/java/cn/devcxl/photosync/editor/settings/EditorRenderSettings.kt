package cn.devcxl.photosync.editor.settings

/**
 * Render-affecting settings resolved once when an editor session opens.
 *
 * Reading all four values from [EditorSettings] at one point keeps the originals as code defaults
 * while ensuring the editor cannot mix a value from one settings revision with another: the session
 * always uses one coherent snapshot, and the changes marked "next open" apply exactly then.
 *
 * @property previewMaxEdge Full-preview preset; the sky layer follows its ratio.
 * @property exportJpegQuality Bake/export JPEG quality.
 * @property previewCacheJpegQuality JPEG quality of the gallery preview cache.
 * @property undoDepth Maximum undo steps kept per photo.
 */
data class EditorRenderSettings(
    val previewMaxEdge: PreviewMaxEdge,
    val exportJpegQuality: Int,
    val previewCacheJpegQuality: Int,
    val undoDepth: Int
) {

    /** Full-preview long edge in pixels. */
    val previewMaxEdgePx: Int get() = previewMaxEdge.maxEdgePx

    /** Long edge of the drag proxy, capped by the preview edge itself. */
    val proxyMaxEdgePx: Int get() = minOf(PROXY_MAX_EDGE_PX, previewMaxEdgePx)

    /** Long edge of the sky layer, synced by [PreviewMaxEdge]. */
    val skyPreviewMaxEdgePx: Int get() = previewMaxEdge.skyPreviewMaxEdgePx

    companion object {

        /** Long edge of the editor drag proxy; the historical default. */
        const val PROXY_MAX_EDGE_PX = 1024

        /** Snapshots every render-affecting value of [settings]. */
        fun from(settings: EditorSettings): EditorRenderSettings = EditorRenderSettings(
            previewMaxEdge = settings.previewMaxEdge,
            exportJpegQuality = settings.exportJpegQuality,
            previewCacheJpegQuality = settings.previewCacheJpegQuality,
            undoDepth = settings.undoDepth
        )
    }
}
