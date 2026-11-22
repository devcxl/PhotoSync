package cn.devcxl.photosync.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceResidencyPlannerTest {

    private val twentyFourMp = SourceDimensions(6000, 4000)
    private val fiftyMp = SourceDimensions(8160, 6120)
    private val sixtyOneMp = SourceDimensions(9504, 6336)
    private val hundredMp = SourceDimensions(11648, 8736)

    private val gpu8Gb = DeviceGlBudget(glMaxTextureSize = 8192, totalRamBytes = RAM_8_GB)
    private val gpu4K = DeviceGlBudget(glMaxTextureSize = 4096, totalRamBytes = RAM_8_GB)

    @Test
    fun shouldChooseFullResidency_when24MpFitsTextureEdgeAndRam() {
        val plan = SourceResidencyPlanner.plan(twentyFourMp, gpu8Gb)
        assertEquals(SourceResidencyTier.FULL_RESIDENCY, plan.tier)
        assertTrue(plan.uploadsFullFrame)
        assertFalse(plan.decodeHalfSize)
        assertEquals(6144, plan.textureEdgeCapPx)
    }

    @Test
    fun shouldKeepFullResidency_whenPixelCountIsJustBelowTierOneLimit() {
        val almost26Mp = SourceDimensions(6144, 4231)
        val plan = SourceResidencyPlanner.plan(
            almost26Mp,
            DeviceGlBudget(glMaxTextureSize = 8192, totalRamBytes = RAM_4_GB)
        )
        assertEquals(SourceResidencyTier.FULL_RESIDENCY, plan.tier)
    }

    @Test
    fun shouldChooseNativeSlice_whenPixelCountJustExceedsTierOneLimit() {
        val justOver26Mp = SourceDimensions(6144, 4232)
        val plan = SourceResidencyPlanner.plan(
            justOver26Mp,
            DeviceGlBudget(glMaxTextureSize = 8192, totalRamBytes = RAM_8_GB)
        )
        assertEquals(SourceResidencyTier.NATIVE_SLICE, plan.tier)
    }

    @Test
    fun shouldChooseNativeSlice_when50MpExceedsTextureEdgeAt4K() {
        val plan = SourceResidencyPlanner.plan(fiftyMp, gpu4K)
        assertEquals(SourceResidencyTier.NATIVE_SLICE, plan.tier)
        assertFalse(plan.uploadsFullFrame)
        assertFalse(plan.decodeHalfSize)
        assertEquals(SourceResidencyPlanner.MAX_SLICE_EDGE_PX, plan.maxSliceEdgePx)
    }

    @Test
    fun shouldCapSliceUploadAtDriverTextureLimit_whenDriverIsSmallerThan2560() {
        val plan = SourceResidencyPlanner.plan(
            fiftyMp,
            DeviceGlBudget(glMaxTextureSize = 2048, totalRamBytes = RAM_8_GB)
        )
        assertEquals(SourceResidencyTier.NATIVE_SLICE, plan.tier)
        assertEquals(2048, plan.maxSliceEdgePx)
    }

    @Test
    fun shouldChooseNativeSlice_when61MpIsWithinTierTwoRange() {
        val plan = SourceResidencyPlanner.plan(sixtyOneMp, gpu8Gb)
        assertEquals(SourceResidencyTier.NATIVE_SLICE, plan.tier)
    }

    @Test
    fun shouldChooseNativeSlice_when24MpLongEdgeExceedsTextureLimit() {
        val panoramic = SourceDimensions(8000, 3000)
        val plan = SourceResidencyPlanner.plan(panoramic, gpu4K)
        assertEquals(SourceResidencyTier.NATIVE_SLICE, plan.tier)
    }

    @Test
    fun shouldChooseHalfSize_whenSourceExceedsTierTwoPixelLimit() {
        val plan = SourceResidencyPlanner.plan(hundredMp, gpu8Gb)
        assertEquals(SourceResidencyTier.HALF_SIZE, plan.tier)
        assertTrue(plan.decodeHalfSize)
    }

    @Test
    fun shouldChooseHalfSize_whenRamIsBelowTierTwoMinimum() {
        val plan = SourceResidencyPlanner.plan(
            fiftyMp,
            DeviceGlBudget(glMaxTextureSize = 8192, totalRamBytes = RAM_4_GB)
        )
        assertEquals(SourceResidencyTier.HALF_SIZE, plan.tier)
        assertTrue(plan.decodeHalfSize)
    }

    @Test
    fun shouldChooseHalfSize_whenGpuTextureLimitForcesSlicingButRamIsLow() {
        val plan = SourceResidencyPlanner.plan(
            twentyFourMp,
            DeviceGlBudget(glMaxTextureSize = 2048, totalRamBytes = RAM_3_GB)
        )
        assertEquals(SourceResidencyTier.HALF_SIZE, plan.tier)
        assertEquals(2048, plan.textureEdgeCapPx)
    }

    @Test
    fun shouldCapTextureEdgeAtDriverLimit_whenDriverIsBelowDesignerLimit() {
        val plan = SourceResidencyPlanner.plan(
            twentyFourMp,
            DeviceGlBudget(glMaxTextureSize = 2048, totalRamBytes = RAM_8_GB)
        )
        assertEquals(SourceResidencyTier.NATIVE_SLICE, plan.tier)
        assertEquals(2048, plan.textureEdgeCapPx)
    }

    @Test(expected = IllegalArgumentException::class)
    fun shouldRejectInvalidTextureLimit_whenDeviceBudgetIsZero() {
        SourceResidencyPlanner.plan(
            twentyFourMp,
            DeviceGlBudget(glMaxTextureSize = 0, totalRamBytes = RAM_8_GB)
        )
    }

    private companion object {
        private const val RAM_3_GB = 3L * 1024L * 1024L * 1024L
        private const val RAM_4_GB = 4L * 1024L * 1024L * 1024L
        private const val RAM_8_GB = 8L * 1024L * 1024L * 1024L
    }
}
