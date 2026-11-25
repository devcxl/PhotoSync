package cn.devcxl.photosync.editor.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/** Seam under test: [DeviceMemoryTier.fromTotalMemoryBytes] — the 4 GiB boundary and unknown RAM. */
class DeviceMemoryTierTest {

    @Test
    fun shouldUseLowTier_whenTotalMemoryIsFourGiBOrLess() {
        assertEquals(
            DeviceMemoryTier.LOW,
            DeviceMemoryTier.fromTotalMemoryBytes(DeviceMemoryTier.LOW_TIER_MAX_TOTAL_MEMORY_BYTES)
        )
        assertEquals(
            DeviceMemoryTier.LOW,
            DeviceMemoryTier.fromTotalMemoryBytes(2L * 1024L * 1024L * 1024L)
        )
    }

    @Test
    fun shouldUseStandardTier_whenTotalMemoryIsAboveFourGiB() {
        assertEquals(
            DeviceMemoryTier.STANDARD,
            DeviceMemoryTier.fromTotalMemoryBytes(
                DeviceMemoryTier.LOW_TIER_MAX_TOTAL_MEMORY_BYTES + 1L
            )
        )
    }

    @Test
    fun shouldUseLowTier_whenTotalMemoryIsUnknown() {
        assertEquals(DeviceMemoryTier.LOW, DeviceMemoryTier.fromTotalMemoryBytes(0L))
    }
}
