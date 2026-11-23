package cn.devcxl.photosync.editor.render

import java.io.File

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import cn.devcxl.photosync.editor.domain.BuiltinPresets
import cn.devcxl.photosync.editor.domain.ColorOps
import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.DetailOps
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EffectsOps
import cn.devcxl.photosync.editor.domain.GeometryOps
import cn.devcxl.photosync.editor.domain.GrainOps
import cn.devcxl.photosync.editor.domain.SharpenOps
import cn.devcxl.photosync.editor.domain.ToneOps
import cn.devcxl.photosync.editor.domain.VignetteOps

import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.CLOCKWISE_QUARTER_TURN_COLORS
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.PREVIEW_MAX_EDGE_PX
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.assertFramesAgree
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.assertFramesDiffer
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.assertQuadrants
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.createQuadrantJpeg
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.loadFrame
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.loadSource
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.newWorkspace
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.renderOnCpu
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.renderOnGpuRequired
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.resetGpuRenderer
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.sourceBitmap

/**
 * Graph coverage of the offscreen GPU preview: every built-in look, one graph that activates all
 * four physical passes, and repeated renders of the same request.
 *
 * Presets and their CPU references are compared on decoded `1280px` frames. Because both writers
 * run the same Pass A/B/C/D contract and the same JPEG quality, a mean channel delta is the useful
 * acceptance signal; the outlier budget only absorbs ringing at the four-colour seams and the
 * server-side grain hash differing by a fraction of a 8-bit step.
 *
 * ## Device requirement
 * A headless EGL context on GLES 3.x is required, probed independently by [HeadlessGlProbe]. Only a
 * device below that requirement skips; on a capable device every preset and pass combination must
 * render, so a shader/FBO/read-back regression fails the named graph instead of skipping.
 */
@RunWith(AndroidJUnit4::class)
class OffscreenPreviewRendererGraphTest {

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = newWorkspace("graph")
        resetGpuRenderer()
    }

    @After
    fun tearDown() {
        resetGpuRenderer()
        workDir.deleteRecursively()
    }

    @Test
    fun shouldRenderEveryBuiltinPreset_whenEachLookIsSelected() {
        val source = loadSource(createQuadrantJpeg(workDir, "presets.jpg").absolutePath)
        try {
            val bitmap = sourceBitmap(source)
            val identity = loadFrame(
                renderOnGpuRequired(
                    source,
                    EditGraph(),
                    File(workDir, "gpu-identity"),
                    "preset identity baseline"
                )
            )
            for (spec in BuiltinPresets.specs()) {
                val graph = checkNotNull(BuiltinPresets.graphFor(spec.id)) {
                    "Built-in preset ${spec.id} has no graph"
                }
                val gpuFile = renderOnGpuRequired(
                    source,
                    graph,
                    File(workDir, "gpu-${spec.id}"),
                    "built-in preset ${spec.id}"
                )
                val cpuFile = renderOnCpu(bitmap, graph, File(workDir, "cpu-${spec.id}"))
                val gpu = loadFrame(gpuFile)
                val cpu = loadFrame(cpuFile)

                assertEquals("${spec.id}: 1280px preview", PREVIEW_MAX_EDGE_PX, gpu.longestEdge)
                assertFramesDiffer("${spec.id}: preset must change the identity frame", gpu, identity, 0.5)
                assertFramesAgree(
                    label = "${spec.id} GPU vs CPU",
                    gpu = gpu,
                    cpu = cpu,
                    meanTolerance = PRESET_MEAN_TOLERANCE,
                    outlierFraction = PRESET_OUTLIER_FRACTION,
                    outlierDelta = PRESET_OUTLIER_DELTA
                )
                assertLookDirection(spec.id, gpu, identity)
            }
        } finally {
            source.release()
        }
    }

    @Test
    fun shouldMatchCpu_whenAGraphActivatesAllFourPasses() {
        val source = loadSource(createQuadrantJpeg(workDir, "all-passes.jpg").absolutePath)
        val graph = EditGraph(
            // Pass A: quarter turn plus a crop, so orientation and aspect both change.
            geometry = GeometryOps(
                rotate90 = 1,
                crop = CropRect(left = 0.0f, top = 0.0f, right = 1.0f, bottom = 0.6f)
            ),
            // Pass B: tone curve, white balance, colour and fade.
            tone = ToneOps(
                exposure = 0.35f,
                contrast = 18,
                glow = 10,
                highlights = -20,
                shadows = 15
            ),
            color = ColorOps(temp = 12, tint = -6, saturation = 10, vibrance = 14, fade = 4),
            // Pass C: the Pre-pass plus clarity, texture, dehaze and sharpening.
            detail = DetailOps(
                clarity = 25,
                texture = 12,
                dehaze = 8,
                sharpen = SharpenOps(amount = 20, radius = 1.0f, threshold = 8, masking = 20)
            ),
            // Pass D: grain and a post-crop vignette.
            effects = EffectsOps(
                grain = GrainOps(amount = 22, size = 30, roughness = 50, seed = 9182),
                vignette = VignetteOps(amount = -16, midpoint = 48, feather = 60, roundness = 10)
            )
        )
        try {
            val gpuFile = renderOnGpuRequired(
                source,
                graph,
                File(workDir, "gpu-all-passes"),
                "Pass A+B+C+D"
            )
            val cpuFile = renderOnCpu(sourceBitmap(source), graph, File(workDir, "cpu-all-passes"))
            val gpu = loadFrame(gpuFile)
            val cpu = loadFrame(cpuFile)

            assertEquals(PREVIEW_MAX_EDGE_PX, gpu.longestEdge)
            assertTrue("A 0.6 crop after a quarter turn must stay landscape", gpu.width > gpu.height)
            assertFramesAgree(
                label = "all-pass GPU vs CPU",
                gpu = gpu,
                cpu = cpu,
                meanTolerance = ALL_PASSES_MEAN_TOLERANCE,
                outlierFraction = ALL_PASSES_OUTLIER_FRACTION,
                outlierDelta = ALL_PASSES_OUTLIER_DELTA
            )
        } finally {
            source.release()
        }
    }

    @Test
    fun shouldKeepQuadrantOrientation_whenPassCAndPassDAreActive() {
        val source = loadSource(createQuadrantJpeg(workDir, "pass-cd-orientation.jpg").absolutePath)
        val graph = EditGraph(
            tone = ToneOps(exposure = 0.2f, contrast = 10),
            detail = DetailOps(clarity = 30, texture = 10),
            effects = EffectsOps(
                grain = GrainOps(amount = 12, size = 30, roughness = 48, seed = 4242),
                vignette = VignetteOps(amount = -8, midpoint = 50, feather = 60)
            )
        )
        try {
            val gpu = loadFrame(
                renderOnGpuRequired(
                    source,
                    graph,
                    File(workDir, "gpu-pass-cd"),
                    "Pass C+D orientation"
                )
            )
            val cpu = loadFrame(renderOnCpu(sourceBitmap(source), graph, File(workDir, "cpu-pass-cd")))

            // Pass C and Pass D each render into their own FBO. A second vertical flip anywhere in
            // that chain would move the red marker from the top-left to the bottom-left quadrant.
            assertQuadrants("pass C/D orientation", gpu, tolerance = PASS_CD_MARKER_TOLERANCE)
            assertFramesAgree(
                label = "pass C/D GPU vs CPU",
                gpu = gpu,
                cpu = cpu,
                meanTolerance = PASS_CD_MEAN_TOLERANCE,
                outlierFraction = PASS_CD_OUTLIER_FRACTION,
                outlierDelta = PASS_CD_OUTLIER_DELTA
            )
        } finally {
            source.release()
        }
    }

    @Test
    fun shouldKeepRotatedQuadrants_whenAllFourPassesRunOnARotatedFrame() {
        val source = loadSource(createQuadrantJpeg(workDir, "rotated-all-passes.jpg").absolutePath)
        val graph = EditGraph(
            geometry = GeometryOps(rotate90 = 1),
            tone = ToneOps(exposure = 0.2f, contrast = 12, shadows = 12),
            detail = DetailOps(clarity = 20, dehaze = 6),
            effects = EffectsOps(
                grain = GrainOps(amount = 10, size = 30, roughness = 48, seed = 2048),
                vignette = VignetteOps(amount = -8, midpoint = 50, feather = 60)
            )
        )
        try {
            val gpu = loadFrame(
                renderOnGpuRequired(
                    source,
                    graph,
                    File(workDir, "gpu-rotated-all"),
                    "rotate90 + Pass A+B+C+D"
                )
            )
            val cpu = loadFrame(renderOnCpu(sourceBitmap(source), graph, File(workDir, "cpu-rotated-all")))

            // A quarter turn plus Pass B/C/D FBOs: the read-back must still be top-down.
            assertQuadrants(
                "rotated all-pass orientation",
                gpu,
                CLOCKWISE_QUARTER_TURN_COLORS,
                PASS_CD_MARKER_TOLERANCE
            )
            assertFramesAgree(
                label = "rotated all-pass GPU vs CPU",
                gpu = gpu,
                cpu = cpu,
                meanTolerance = PASS_CD_MEAN_TOLERANCE,
                outlierFraction = PASS_CD_OUTLIER_FRACTION,
                outlierDelta = PASS_CD_OUTLIER_DELTA
            )
        } finally {
            source.release()
        }
    }

    @Test
    fun shouldChangePixels_whenPassCAndPassDParametersBecomeActive() {
        val source = loadSource(createQuadrantJpeg(workDir, "passes-contribute.jpg").absolutePath)
        val base = EditGraph(tone = ToneOps(exposure = 0.2f))
        val detailed = base.copy(
            detail = DetailOps(clarity = 55, texture = 20, sharpen = SharpenOps(amount = 40)),
            effects = EffectsOps(
                grain = GrainOps(amount = 40, size = 45, roughness = 60, seed = 777_777)
            )
        )
        try {
            val baseFrame = loadFrame(
                renderOnGpuRequired(
                    source,
                    base,
                    File(workDir, "gpu-base"),
                    "Pass B baseline"
                )
            )
            val detailedFrame = loadFrame(
                renderOnGpuRequired(
                    source,
                    detailed,
                    File(workDir, "gpu-detailed"),
                    "Pass C+D active"
                )
            )

            // Without this, a chain that silently drops Pass C or Pass D would still pass the
            // parity assertions whenever both the GPU and the CPU skipped the same pass.
            assertFramesDiffer("Pass C/D must contribute", detailedFrame, baseFrame, 1.0)
        } finally {
            source.release()
        }
    }

    @Test
    fun shouldWriteDistinctIdenticalFrames_whenTheSameRequestIsRenderedTwice() {
        val source = loadSource(createQuadrantJpeg(workDir, "repeat.jpg").absolutePath)
        val graph = checkNotNull(BuiltinPresets.graphFor(BuiltinPresets.WARM_SUNSET_ID))
        try {
            val outDir = File(workDir, "repeat")
            val first = renderOnGpuRequired(source, graph, outDir, "warm sunset repeat #1")
            val second = renderOnGpuRequired(source, graph, outDir, "warm sunset repeat #2")

            assertNotEquals("Every render must publish a new preview file", first, second)
            assertTrue(first.isFile)
            assertTrue(second.isFile)
            assertEquals("No preview may be overwritten", 2, outDir.listFiles()?.size ?: 0)

            val firstFrame = loadFrame(first)
            val secondFrame = loadFrame(second)
            assertEquals(firstFrame.width to firstFrame.height, secondFrame.width to secondFrame.height)
            assertTrue(
                "Repeated GPU renders of one request must be deterministic, but the mean channel " +
                    "delta was ${firstFrame.meanChannelDelta(secondFrame)}/255",
                firstFrame.meanChannelDelta(secondFrame) <= REPEAT_MEAN_TOLERANCE
            )
        } finally {
            source.release()
        }
    }

    /** Directional sanity checks that are independent of the exact CPU/GPU parity. */
    private fun assertLookDirection(
        presetId: String,
        frame: OffscreenFrame,
        identity: OffscreenFrame
    ) {
        when (presetId) {
            BuiltinPresets.MONO_SILVER_ID -> assertTrue(
                "The mono look must desaturate, but the mean channel spread was " +
                    "${frame.meanChannelSpread()}/255",
                frame.meanChannelSpread() <= MONO_MAX_CHANNEL_SPREAD
            )
            BuiltinPresets.WARM_SUNSET_ID -> {
                val warmth = frame.meanChannel(RED_SHIFT) - frame.meanChannel(BLUE_SHIFT)
                val identityWarmth = identity.meanChannel(RED_SHIFT) - identity.meanChannel(BLUE_SHIFT)
                assertTrue(
                    "The warm look must lift red above blue relative to the identity frame " +
                        "($warmth vs $identityWarmth)",
                    warmth > identityWarmth + LOOK_CHANNEL_LEAD
                )
            }
            BuiltinPresets.COOL_NORDIC_ID -> {
                val warmth = frame.meanChannel(RED_SHIFT) - frame.meanChannel(BLUE_SHIFT)
                val identityWarmth = identity.meanChannel(RED_SHIFT) - identity.meanChannel(BLUE_SHIFT)
                assertTrue(
                    "The cool look must push blue above red relative to the identity frame " +
                        "($warmth vs $identityWarmth)",
                    warmth < identityWarmth - LOOK_CHANNEL_LEAD
                )
            }
        }
    }

    private companion object {
        const val PRESET_MEAN_TOLERANCE = 10.0
        const val PRESET_OUTLIER_FRACTION = 0.10
        const val PRESET_OUTLIER_DELTA = 64

        const val ALL_PASSES_MEAN_TOLERANCE = 10.0
        const val ALL_PASSES_OUTLIER_FRACTION = 0.08
        const val ALL_PASSES_OUTLIER_DELTA = 64

        const val PASS_CD_MARKER_TOLERANCE = 40
        const val PASS_CD_MEAN_TOLERANCE = 10.0
        const val PASS_CD_OUTLIER_FRACTION = 0.10
        const val PASS_CD_OUTLIER_DELTA = 64

        const val REPEAT_MEAN_TOLERANCE = 1.0

        const val MONO_MAX_CHANNEL_SPREAD = 6.0
        const val LOOK_CHANNEL_LEAD = 4.0

        /** Bit shifts of the red and blue channels in a packed ARGB pixel. */
        const val RED_SHIFT = 16
        const val BLUE_SHIFT = 0
    }
}
