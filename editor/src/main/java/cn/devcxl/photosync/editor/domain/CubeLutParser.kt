package cn.devcxl.photosync.editor.domain

import java.util.Locale

/**
 * Parses Adobe `.cube` 3D LUT text and pre-bakes it into the `33^3 RGBA16F` cache
 * described by [Lut16fCache].
 *
 * Every malformed input (missing header, wrong sample count, out-of-range size, inverted or
 * non-finite domain, non-numeric texel) yields `Result.failure` so the importer can show an error
 * instead of crashing on a community file.
 */
internal object CubeLutParser {

    /** Smallest legal `LUT_3D_SIZE`. */
    const val MIN_SIZE = 2

    /** Largest legal `LUT_3D_SIZE`; bigger tables are rejected rather than resampled blindly. */
    const val MAX_SIZE = 65

    /** Upper bound on the `.cube` text accepted from a SAF document. */
    const val MAX_TEXT_CHARS = 8 * 1024 * 1024

    const val DEFAULT_TITLE = "LUT"

    private const val COMPONENTS_PER_SAMPLE = 3
    private const val RGB_COMPONENTS = 3
    private const val GRID_STEPS = Lut16fCache.GRID_SIZE - 1
    private const val WHITESPACE = "\\s+"
    private const val COMMENT_PREFIX = '#'
    private const val TITLE_KEYWORD = "TITLE"
    private const val SIZE_KEYWORD = "LUT_3D_SIZE"
    private const val ONE_D_SIZE_KEYWORD = "LUT_1D_SIZE"
    private const val DOMAIN_MIN_KEYWORD = "DOMAIN_MIN"
    private const val DOMAIN_MAX_KEYWORD = "DOMAIN_MAX"

    private val DEFAULT_DOMAIN_MIN = floatArrayOf(0.0f, 0.0f, 0.0f)
    private val DEFAULT_DOMAIN_MAX = floatArrayOf(1.0f, 1.0f, 1.0f)

    /**
     * Parses [text] into a [CubeLut].
     *
     * @return Success with the parsed table, or failure carrying the rejection reason.
     */
    fun parse(text: String): Result<CubeLut> {
        if (text.length > MAX_TEXT_CHARS) return invalid("cube text exceeds $MAX_TEXT_CHARS characters")
        var title = ""
        var size = 0
        var domainMin = DEFAULT_DOMAIN_MIN.copyOf()
        var domainMax = DEFAULT_DOMAIN_MAX.copyOf()
        val samples = ArrayList<Float>()

        for ((lineNumber, rawLine) in text.lineSequence().withIndex()) {
            val line = rawLine.substringBefore(COMMENT_PREFIX).trim()
            if (line.isEmpty()) continue
            val keyword = line.takeWhile { !it.isWhitespace() }.uppercase(Locale.ROOT)
            when (keyword) {
                TITLE_KEYWORD -> title = line.substring(TITLE_KEYWORD.length).trim().removeSurrounding("\"")
                SIZE_KEYWORD -> {
                    if (size != 0) return invalid("duplicate LUT_3D_SIZE on line ${lineNumber + 1}")
                    size = parseSize(line) ?: return invalid("invalid LUT_3D_SIZE on line ${lineNumber + 1}")
                }
                ONE_D_SIZE_KEYWORD -> return invalid("1D LUTs are not supported")
                DOMAIN_MIN_KEYWORD -> {
                    domainMin = parseTriple(line, leadingTokens = 1)
                        ?: return invalid("invalid DOMAIN_MIN on line ${lineNumber + 1}")
                }
                DOMAIN_MAX_KEYWORD -> {
                    domainMax = parseTriple(line, leadingTokens = 1)
                        ?: return invalid("invalid DOMAIN_MAX on line ${lineNumber + 1}")
                }
                else -> {
                    val triple = parseTriple(line) ?: return invalid("invalid texel on line ${lineNumber + 1}")
                    if (samples.size + COMPONENTS_PER_SAMPLE > maxSampleFloats(size)) {
                        return invalid("more texels than LUT_3D_SIZE declares")
                    }
                    triple.forEach(samples::add)
                }
            }
        }

        if (size == 0) return invalid("missing LUT_3D_SIZE")
        if (!isDomainValid(domainMin, domainMax)) return invalid("DOMAIN_MIN must be lower than DOMAIN_MAX")
        if (samples.size != expectedFloatCount(size)) {
            return invalid("expected ${expectedFloatCount(size)} floats but found ${samples.size}")
        }
        return Result.success(
            CubeLut(
                title = title.ifEmpty { DEFAULT_TITLE },
                size = size,
                domainMin = domainMin,
                domainMax = domainMax,
                samples = samples.toFloatArray()
            )
        )
    }

    /**
     * Resamples [lut] onto the fixed `33^3` grid and serializes it as a `.lut16f` byte array.
     */
    fun encodeCache(lut: CubeLut): ByteArray {
        val cache = ByteArray(Lut16fCache.TOTAL_SIZE_BYTES)
        Lut16fCache.headerBytes().copyInto(cache)
        var offset = Lut16fCache.HEADER_SIZE_BYTES
        val rgb = FloatArray(RGB_COMPONENTS)
        for (index in 0 until Lut16fCache.GRID_SIZE * Lut16fCache.GRID_SIZE * Lut16fCache.GRID_SIZE) {
            val red = (index % Lut16fCache.GRID_SIZE).toFloat() / GRID_STEPS
            val green = ((index / Lut16fCache.GRID_SIZE) % Lut16fCache.GRID_SIZE).toFloat() / GRID_STEPS
            val blue = (index / (Lut16fCache.GRID_SIZE * Lut16fCache.GRID_SIZE)).toFloat() / GRID_STEPS
            PassDEffectsMath.sampleTetrahedral(
                lut = lut.samples,
                size = lut.size,
                red = lut.gridCoordinate(0, red),
                green = lut.gridCoordinate(1, green),
                blue = lut.gridCoordinate(2, blue),
                out = rgb
            )
            offset = Lut16fCache.writeHalfBits(cache, offset, HalfFloatBits.toBits(rgb[0].coerceIn(0.0f, 1.0f)))
            offset = Lut16fCache.writeHalfBits(cache, offset, HalfFloatBits.toBits(rgb[1].coerceIn(0.0f, 1.0f)))
            offset = Lut16fCache.writeHalfBits(cache, offset, HalfFloatBits.toBits(rgb[2].coerceIn(0.0f, 1.0f)))
            offset = Lut16fCache.writeHalfBits(cache, offset, HalfFloatBits.toBits(1.0f))
        }
        return cache
    }

    private fun parseSize(line: String): Int? {
        val tokens = line.split(Regex(WHITESPACE))
        if (tokens.size != 2) return null
        val parsed = tokens[1].toIntOrNull() ?: return null
        return parsed.takeIf { it in MIN_SIZE..MAX_SIZE }
    }

    private fun parseTriple(line: String, leadingTokens: Int = 0): FloatArray? {
        val tokens = line.split(Regex(WHITESPACE))
        if (tokens.size != leadingTokens + COMPONENTS_PER_SAMPLE) return null
        val values = FloatArray(COMPONENTS_PER_SAMPLE)
        for (index in 0 until COMPONENTS_PER_SAMPLE) {
            val value = tokens[leadingTokens + index].toFloatOrNull() ?: return null
            if (!value.isFinite()) return null
            values[index] = value
        }
        return values
    }

    private fun isDomainValid(domainMin: FloatArray, domainMax: FloatArray): Boolean =
        (0 until COMPONENTS_PER_SAMPLE).all { component ->
            val low = domainMin[component]
            val high = domainMax[component]
            low.isFinite() && high.isFinite() && high > low
        }

    private fun expectedFloatCount(size: Int): Int =
        size * size * size * COMPONENTS_PER_SAMPLE

    private fun maxSampleFloats(size: Int): Int =
        if (size == 0) MAX_SIZE * MAX_SIZE * MAX_SIZE * COMPONENTS_PER_SAMPLE else expectedFloatCount(size)

    private fun invalid(reason: String): Result<CubeLut> =
        Result.failure(IllegalArgumentException(reason))
}
