package cn.devcxl.photosync.editor.domain

/**
 * Source-residency strategy used by a full-resolution `Bake`.
 *
 * The tier decides how the decoded source pixels reach the GPU, never how the image is graded:
 * the shaders are identical for every tier.
 */
enum class SourceResidencyTier {
    /** Whole upright source uploaded once into a single `GL_RGBA16F` texture. */
    FULL_RESIDENCY,

    /** Whole source kept in a native `RGB16` buffer and uploaded as per-band sub-slices. */
    NATIVE_SLICE,

    /** Source decoded at half resolution (LibRaw `half_size = 1`) before any GPU upload. */
    HALF_SIZE
}

/**
 * Pixel dimensions of the upright, orientation-normalized source frame.
 */
data class SourceDimensions(val width: Int, val height: Int) {

    init {
        require(width > 0 && height > 0) { "Source dimensions must be positive" }
    }

    /** Total pixel count; `Long` keeps 100MP sensors well inside range. */
    val pixels: Long get() = width.toLong() * height.toLong()

    /** Longest edge in pixels. */
    val longEdge: Int get() = maxOf(width, height)

    /** Width/height ratio of the upright frame. */
    val aspect: Float get() = width.toFloat() / height.toFloat()
}

/**
 * Device limits the residency decision depends on.
 *
 * @property glMaxTextureSize `GL_MAX_TEXTURE_SIZE` of the export context.
 * @property totalRamBytes `ActivityManager.MemoryInfo.totalMem`.
 */
data class DeviceGlBudget(val glMaxTextureSize: Int, val totalRamBytes: Long)

/**
 * Resolved residency decision.
 *
 * @property tier Chosen [SourceResidencyTier].
 * @property decodeHalfSize True when the RAW decoder must run with LibRaw `half_size = 1`.
 * @property uploadsFullFrame True when the source fits one GPU texture and is uploaded once.
 * @property textureEdgeCapPx Longest edge a full-frame texture may have on this device.
 * @property maxSliceEdgePx Longest edge of a Tier 2 sub-slice upload.
 */
data class SourceResidencyPlan(
    val tier: SourceResidencyTier,
    val decodeHalfSize: Boolean,
    val uploadsFullFrame: Boolean,
    val textureEdgeCapPx: Int,
    val maxSliceEdgePx: Int
)

/**
 * Chooses the source-residency tier for a full-resolution export.
 *
 * The thresholds mirror the pipeline spec §7: full GPU residency only for frames that fit both the
 * device texture limit and the `6144px` designer cap, native `RGB16` residency (with per-band slice
 * uploads) for 26–65MP frames on devices with at least 6GB of RAM, and a half-resolution LibRaw
 * decode for anything larger or for low-memory devices.
 */
object SourceResidencyPlanner {

    /** Upper bound of the full-GPU-residency tier. */
    const val TIER1_MAX_PIXELS = 26_000_000L

    /** Upper bound of the native-slice tier; above it the source is decoded at half size. */
    const val TIER2_MAX_PIXELS = 65_000_000L

    /** Longest edge a full-frame `RGBA16F` texture may have even when the driver allows more. */
    const val FULL_RESIDENCY_EDGE_CAP_PX = 6144

    /** Longest edge of a Tier 2 source sub-slice upload. */
    const val MAX_SLICE_EDGE_PX = 2560

    /** Minimum RAM for the full-GPU-residency tier. */
    const val TIER1_MIN_RAM_BYTES = 4L * 1024L * 1024L * 1024L

    /** Minimum RAM for the native-slice tier. */
    const val TIER2_MIN_RAM_BYTES = 6L * 1024L * 1024L * 1024L

    /**
     * Resolves the residency plan for [source] on [device].
     *
     * @throws IllegalArgumentException when the device reports a non-positive texture limit.
     */
    fun plan(source: SourceDimensions, device: DeviceGlBudget): SourceResidencyPlan {
        require(device.glMaxTextureSize > 0) { "GL_MAX_TEXTURE_SIZE must be positive" }
        val edgeCap = minOf(device.glMaxTextureSize, FULL_RESIDENCY_EDGE_CAP_PX)
        // A Tier 2 slice must also fit the driver's texture limit, not just the 2560px budget.
        val sliceEdgeCap = minOf(MAX_SLICE_EDGE_PX, device.glMaxTextureSize)
        val fitsFullFrame = source.longEdge <= edgeCap
        if (fitsFullFrame &&
            source.pixels <= TIER1_MAX_PIXELS &&
            device.totalRamBytes >= TIER1_MIN_RAM_BYTES
        ) {
            return SourceResidencyPlan(
                tier = SourceResidencyTier.FULL_RESIDENCY,
                decodeHalfSize = false,
                uploadsFullFrame = true,
                textureEdgeCapPx = edgeCap,
                maxSliceEdgePx = sliceEdgeCap
            )
        }
        val nativeSliceEligible = source.pixels <= TIER2_MAX_PIXELS &&
            device.totalRamBytes >= TIER2_MIN_RAM_BYTES
        if (nativeSliceEligible) {
            return SourceResidencyPlan(
                tier = SourceResidencyTier.NATIVE_SLICE,
                decodeHalfSize = false,
                uploadsFullFrame = false,
                textureEdgeCapPx = edgeCap,
                maxSliceEdgePx = sliceEdgeCap
            )
        }
        return SourceResidencyPlan(
            tier = SourceResidencyTier.HALF_SIZE,
            decodeHalfSize = true,
            uploadsFullFrame = true,
            textureEdgeCapPx = edgeCap,
            maxSliceEdgePx = sliceEdgeCap
        )
    }
}
