package cn.devcxl.photosync.editor.domain

/**
 * A parsed `.cube` 3D lookup table.
 *
 * [samples] holds `size^3 * 3` floats in the file's red-fastest, green, blue-slowest order.
 * [domainMin] and [domainMax] describe which input colour component values the first and last
 * grid slices represent, so a LUT authored for a non-`0..1` domain still samples correctly.
 */
internal class CubeLut(
    val title: String,
    val size: Int,
    val domainMin: FloatArray,
    val domainMax: FloatArray,
    val samples: FloatArray
) {

    /**
     * Maps a normalized `[0, 1]` colour component onto the `[0, 1]` grid coordinate this LUT
     * expects, honouring its declared domain.
     *
     * @param component `0` for red, `1` for green, `2` for blue.
     * @param normalized Display-referred colour component in `[0, 1]`.
     */
    fun gridCoordinate(component: Int, normalized: Float): Float {
        val low = domainMin[component]
        val span = domainMax[component] - low
        if (!span.isFinite() || span <= 0.0f) return 0.0f
        return ((normalized - low) / span).coerceIn(0.0f, 1.0f)
    }
}
