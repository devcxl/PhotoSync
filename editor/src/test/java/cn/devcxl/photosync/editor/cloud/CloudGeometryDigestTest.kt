package cn.devcxl.photosync.editor.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import cn.devcxl.photosync.editor.domain.CloudEditLayerRef
import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.domain.LiquifyRef

/**
 * Seam under test: [CloudGeometryDigest] — the binding between a cloud result and one Pass A
 * framing. A digest that ignores a geometry axis would let a paid-for layer be pasted onto a frame
 * it was never generated for.
 */
class CloudGeometryDigestTest {

    @Test
    fun shouldReturnStableLowercaseSha256_whenGeometryIsUnchanged() {
        val geometry = GeometryOps(rotate90 = 1, flipH = true, straighten = 2.5f, crop = CROP)

        val first = CloudGeometryDigest.digest(geometry)
        val second = CloudGeometryDigest.digest(geometry.copy())

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun shouldChangeDigest_whenAnyCropEdgeMoves() {
        val base = CloudGeometryDigest.digest(GeometryOps(crop = CROP))

        val digests = listOf(
            CloudGeometryDigest.digest(GeometryOps(crop = CROP.copy(left = 0.01f))),
            CloudGeometryDigest.digest(GeometryOps(crop = CROP.copy(top = 0.01f))),
            CloudGeometryDigest.digest(GeometryOps(crop = CROP.copy(right = 0.99f))),
            CloudGeometryDigest.digest(GeometryOps(crop = CROP.copy(bottom = 0.99f)))
        )

        digests.forEach { digest -> assertFalse("Crop edge must change the digest", digest == base) }
        assertEquals(4, digests.toSet().size)
    }

    @Test
    fun shouldChangeDigest_whenDiscreteOrContinuousGeometryMoves() {
        val base = CloudGeometryDigest.digest(GeometryOps())
        val meshA = "a".repeat(64)
        val meshB = "b".repeat(64)
        val variants = listOf(
            GeometryOps(rotate90 = 1),
            GeometryOps(flipH = true),
            GeometryOps(flipV = true),
            GeometryOps(straighten = 0.5f),
            GeometryOps(perspective = listOf(0.1f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)),
            GeometryOps(liquify = LiquifyRef("edits/1/liquify_128x128_$meshA.bin", 128, meshA)),
            GeometryOps(liquify = LiquifyRef("edits/1/liquify_128x128_$meshB.bin", 128, meshB))
        )

        variants.forEach { variant ->
            assertFalse(
                "Every geometry axis must change the digest",
                CloudGeometryDigest.digest(variant) == base
            )
        }
    }

    @Test
    fun shouldTreatNegativeZeroAsNeutral_whenStraightenIsZero() {
        val positive = CloudGeometryDigest.digest(GeometryOps(straighten = 0.0f))
        val negative = CloudGeometryDigest.digest(GeometryOps(straighten = -0.0f))

        assertEquals(positive, negative)
    }

    @Test
    fun shouldMatchOnlyTheBoundGeometry_whenCheckingALayer() {
        val geometry = GeometryOps(crop = CROP, rotate90 = 3)
        val layer = layerFor(geometry)

        assertTrue(CloudGeometryDigest.matches(layer, geometry))
        assertFalse(CloudGeometryDigest.matches(layer, GeometryOps(crop = CROP)))
        assertTrue(CloudGeometryDigest.isMismatch(layer, GeometryOps()))
        assertFalse(CloudGeometryDigest.isMismatch(null, geometry))
    }

    @Test
    fun shouldNeverMatch_whenLayerCarriesNoDigest() {
        val layer = CloudEditLayerRef(imageRelPath = "edits/1/cloud_layer_x.png")
        val geometry = GeometryOps()

        assertFalse(CloudGeometryDigest.matches(layer, geometry))
        assertTrue(CloudGeometryDigest.isMismatch(layer, geometry))
    }

    private fun layerFor(geometry: GeometryOps): CloudEditLayerRef = CloudEditLayerRef(
        imageRelPath = "edits/1/cloud_layer_${"c".repeat(64)}.png",
        sha256 = "c".repeat(64),
        geometryDigest = CloudGeometryDigest.digest(geometry)
    )

    private companion object {
        val CROP = CropRect(left = 0.05f, top = 0.1f, right = 0.9f, bottom = 0.85f)
    }
}
