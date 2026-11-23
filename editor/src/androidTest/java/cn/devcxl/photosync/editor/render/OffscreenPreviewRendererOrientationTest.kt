package cn.devcxl.photosync.editor.render

import java.io.File

import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import cn.devcxl.photosync.editor.domain.CropRect
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.GeometryOps

import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.CLOCKWISE_QUARTER_TURN_COLORS
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.GREEN
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.PREVIEW_MAX_EDGE_PX
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.RED
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.SOURCE_HEIGHT
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.SOURCE_WIDTH
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.YELLOW
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.assertColorNear
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.assertFramesAgree
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
 * Pixel-direction coverage for [OffscreenPreviewRenderer] on a real headless EGL context.
 *
 * A four-quadrant JPEG is asymmetric by design: every quadrant has a different colour, so a
 * vertically flipped, horizontally mirrored or quarter-turn-shifted read-back is immediately
 * visible instead of hiding in a symmetric gradient. The tests also decode the same source through
 * the CPU [PreviewCacheWriter] so a GPU frame that is upright but wrong is still caught.
 *
 * ## Device requirement
 * These tests need a headless EGL context on GLES 3.x, probed independently by [HeadlessGlProbe].
 * Only a device below that requirement skips ([requireHeadlessGpu]); once the probe passes, a
 * `null` render result is a shader/FBO/read-back regression and fails the test. The suite therefore
 * requires a higher device class than the app's CPU fallback path.
 */
@RunWith(AndroidJUnit4::class)
class OffscreenPreviewRendererOrientationTest {

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = newWorkspace("orientation")
        resetGpuRenderer()
    }

    @After
    fun tearDown() {
        resetGpuRenderer()
        workDir.deleteRecursively()
    }

    @Test
    fun shouldRenderUprightQuadrants_whenGraphIsIdentity() {
        val source = loadSource(createQuadrantJpeg(workDir, "identity.jpg").absolutePath)
        try {
            val output = renderOnGpuRequired(
                source,
                EditGraph(),
                File(workDir, "gpu-identity"),
                "identity graph"
            )
            val frame = loadFrame(output)

            assertEquals(PREVIEW_MAX_EDGE_PX, frame.longestEdge)
            assertEquals(
                SOURCE_WIDTH.toFloat() / SOURCE_HEIGHT.toFloat(),
                frame.aspect,
                ASPECT_TOLERANCE
            )
            assertQuadrants("identity", frame)
        } finally {
            source.release()
        }
    }

    @Test
    fun shouldMatchCpuWriter_whenGraphIsIdentity() {
        val source = loadSource(createQuadrantJpeg(workDir, "identity-cpu.jpg").absolutePath)
        try {
            val gpuFile = renderOnGpuRequired(
                source,
                EditGraph(),
                File(workDir, "gpu"),
                "identity graph (CPU comparison)"
            )
            val cpuFile = renderOnCpu(sourceBitmap(source), EditGraph(), File(workDir, "cpu"))
            val gpu = loadFrame(gpuFile)
            val cpu = loadFrame(cpuFile)

            assertQuadrants("cpu reference", cpu)
            assertFramesAgree(
                label = "identity GPU vs CPU",
                gpu = gpu,
                cpu = cpu,
                meanTolerance = IDENTITY_MEAN_TOLERANCE,
                outlierFraction = IDENTITY_OUTLIER_FRACTION,
                outlierDelta = IDENTITY_OUTLIER_DELTA
            )
        } finally {
            source.release()
        }
    }

    @Test
    fun shouldRenderUpright_whenExifOrientationRotatesTheJpeg() {
        val file = createQuadrantJpeg(
            workDir,
            "exif-rotate-90.jpg",
            orientation = ExifInterface.ORIENTATION_ROTATE_90
        )
        val source = loadSource(file.absolutePath)
        try {
            // The loader must have applied the EXIF quarter turn before the texture upload: the
            // stored 480x320 landscape frame arrives at Pass A as a 320x480 portrait frame.
            assertEquals(SOURCE_HEIGHT, source.width)
            assertEquals(SOURCE_WIDTH, source.height)

            val output = renderOnGpuRequired(
                source,
                EditGraph(),
                File(workDir, "gpu-exif"),
                "EXIF ROTATE_90"
            )
            val frame = loadFrame(output)

            assertEquals(PREVIEW_MAX_EDGE_PX, frame.longestEdge)
            assertTrue("EXIF-rotated preview must be portrait", frame.height > frame.width)
            assertQuadrants("exif rotate 90", frame, CLOCKWISE_QUARTER_TURN_COLORS)
        } finally {
            source.release()
        }
    }

    @Test
    fun shouldCropAfterRotating_whenPassAGeometryIsActive() {
        val source = loadSource(createQuadrantJpeg(workDir, "rotate-crop.jpg").absolutePath)
        val graph = EditGraph(
            geometry = GeometryOps(
                rotate90 = 1,
                // The top-right quadrant of the clockwise-rotated frame is the stored top-left
                // quadrant, i.e. a frame that must be uniformly red.
                crop = CropRect(left = 0.5f, top = 0.0f, right = 1.0f, bottom = 0.5f)
            )
        )
        try {
            val gpuFile = renderOnGpuRequired(
                source,
                graph,
                File(workDir, "gpu-rotate-crop"),
                "rotate90 + crop"
            )
            val cpuFile = renderOnCpu(sourceBitmap(source), graph, File(workDir, "cpu-rotate-crop"))
            val gpu = loadFrame(gpuFile)
            val cpu = loadFrame(cpuFile)

            assertEquals(PREVIEW_MAX_EDGE_PX, gpu.longestEdge)
            assertTrue("A quarter turn plus a 1:1 crop must stay portrait", gpu.height > gpu.width)
            assertColorNear("rotated crop centre", gpu, 0.5f, 0.5f, RED)
            assertColorNear("rotated crop top", gpu, 0.5f, 0.15f, RED)
            assertColorNear("rotated crop bottom", gpu, 0.5f, 0.85f, RED)
            // Quadrant boundary of the stored image becomes the crop edge; sampling one marker
            // colour is enough to prove that Pass A applied both the turn and the crop window.
            assertColorNear("rotated crop left lifted corner", gpu, 0.15f, 0.5f, RED)

            assertFramesAgree(
                label = "rotate + crop GPU vs CPU",
                gpu = gpu,
                cpu = cpu,
                meanTolerance = GEOMETRY_MEAN_TOLERANCE,
                outlierFraction = GEOMETRY_OUTLIER_FRACTION,
                outlierDelta = GEOMETRY_OUTLIER_DELTA
            )
        } finally {
            source.release()
        }
    }

    @Test
    fun shouldKeepQuadrantsUpright_whenGeometryOnlyMirrorsHorizontally() {
        val source = loadSource(createQuadrantJpeg(workDir, "flip-h.jpg").absolutePath)
        val graph = EditGraph(geometry = GeometryOps(flipH = true))
        try {
            val output = renderOnGpuRequired(
                source,
                graph,
                File(workDir, "gpu-flip"),
                "horizontal flip"
            )
            val frame = loadFrame(output)

            assertColorNear("flipH top-left", frame, 0.25f, 0.25f, GREEN)
            assertColorNear("flipH bottom-left", frame, 0.25f, 0.75f, YELLOW)
        } finally {
            source.release()
        }
    }

    private companion object {
        const val ASPECT_TOLERANCE = 0.01f

        // JPEG q92 plus the GPU output dither: the CPU and GPU frames only differ by codec noise.
        const val IDENTITY_MEAN_TOLERANCE = 3.0
        const val IDENTITY_OUTLIER_FRACTION = 0.02
        const val IDENTITY_OUTLIER_DELTA = 48

        // Pass A resampling differs in filter kernel between GL and the CPU bilinear sampler, so
        // the crop seams may carry a few more outliers than the identity comparison.
        const val GEOMETRY_MEAN_TOLERANCE = 6.0
        const val GEOMETRY_OUTLIER_FRACTION = 0.03
        const val GEOMETRY_OUTLIER_DELTA = 48
    }
}
