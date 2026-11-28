package cn.devcxl.photosync.editor.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seam under test: [OrtSessionCachePolicy] — the shipping session options must not re-enable ONNX
 * Runtime's cross-run caches.
 *
 * The Redmi K20 Pro measurements in `docs/research/2026-11-26-p0-ai-foundation-gate-record.md`
 * show the MI-GAN generator peaking ~300 MiB higher (1.09 GiB vs 0.78 GiB) when both caches are on.
 */
class OrtSessionCachePolicyTest {

    @Test
    fun shouldKeepBothCrossRunCachesDisabled_whenUsingTheShippingPolicy() {
        val shipping = OrtSessionCachePolicy.DISABLED

        assertFalse("the CPU arena must stay off", shipping.cpuMemArena)
        assertFalse("the memory pattern must stay off", shipping.memoryPattern)
    }

    @Test
    fun shouldMirrorOrtDefaults_whenUsingTheReferencePolicy() {
        val reference = OrtSessionCachePolicy.ORT_DEFAULT

        assertTrue(reference.cpuMemArena)
        assertTrue(reference.memoryPattern)
    }

    @Test
    fun shouldShipTheCacheFreePolicy_whenTheFactoryUsesItsDefaults() {
        val factory = OnnxRuntimeSessionFactory()

        assertSame(OrtSessionCachePolicy.DISABLED, factory.cachePolicy)
    }
}
