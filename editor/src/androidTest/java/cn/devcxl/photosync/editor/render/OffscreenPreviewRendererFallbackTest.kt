package cn.devcxl.photosync.editor.render

import java.io.File

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import cn.devcxl.photosync.editor.domain.EditGraph

import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.assertQuadrants
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.createQuadrantJpeg
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.loadFrame
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.loadSource
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.newWorkspace
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.renderOnGpuNullable
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.renderOnCpu
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.resetGpuRenderer
import cn.devcxl.photosync.editor.render.OffscreenPreviewTestSupport.sourceBitmap

/**
 * Failure-path coverage of [OffscreenPreviewRenderer].
 *
 * These tests never require a working GL driver: they verify that a GPU render which cannot publish
 * returns `null` (never a partial frame, never an exception) and that the CPU writer still produces
 * the preview the caller falls back to. The failing renders are induced through an output path the
 * writer cannot turn into a directory, which is driver independent.
 */
@RunWith(AndroidJUnit4::class)
class OffscreenPreviewRendererFallbackTest {

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = newWorkspace("fallback")
        resetGpuRenderer()
    }

    @After
    fun tearDown() {
        resetGpuRenderer()
        workDir.deleteRecursively()
    }

    @Test
    fun shouldReturnNullAndKeepTheBlocker_whenThePreviewCannotBeWritten() {
        val source = loadSource(createQuadrantJpeg(workDir, "blocked.jpg").absolutePath)
        val blocker = File(workDir, "not-a-directory").apply { writeText("keep") }
        try {
            val rendered = renderOnGpuNullable(source, EditGraph(), blocker)

            assertNull("An unwritable output must yield the CPU fallback, not a file", rendered)
            assertTrue("The blocker must stay a file", blocker.isFile)
            assertEquals("A failed render must not touch existing files", "keep", blocker.readText())
        } finally {
            source.release()
        }
    }

    @Test
    fun shouldDisableTheGpuPathAndKeepTheCpuFallback_whenRendersKeepFailing() {
        val source = loadSource(createQuadrantJpeg(workDir, "fallback.jpg").absolutePath)
        val blocker = File(workDir, "not-a-directory").apply { writeText("keep") }
        try {
            repeat(OffscreenPreviewBreaker.DEFAULT_FAILURE_LIMIT) { attempt ->
                assertNull(
                    "Render $attempt against an unwritable output must return null",
                    renderOnGpuNullable(source, EditGraph(), blocker)
                )
            }

            assertFalse("The circuit breaker must stop the GPU path", OffscreenPreviewRenderer.isEnabled)
            // With the breaker open the renderer must not attempt GL; the call still returns the
            // CPU-fallback signal instead of throwing.
            val disabledDir = File(workDir, "disabled")
            assertNull(renderOnGpuNullable(source, EditGraph(), disabledDir))
            assertFalse("A disabled GPU path must not create output", disabledDir.exists())

            val cpuFile = renderOnCpu(sourceBitmap(source), EditGraph(), File(workDir, "cpu"))
            assertQuadrants("CPU fallback", loadFrame(cpuFile))
        } finally {
            source.release()
        }
    }

    @Test
    fun shouldRecoverLazily_whenTheRendererIsResetAfterFailures() {
        val source = loadSource(createQuadrantJpeg(workDir, "recover.jpg").absolutePath)
        val blocker = File(workDir, "not-a-directory").apply { writeText("keep") }
        try {
            repeat(OffscreenPreviewBreaker.DEFAULT_FAILURE_LIMIT) {
                assertNull(renderOnGpuNullable(source, EditGraph(), blocker))
            }
            assertFalse(OffscreenPreviewRenderer.isEnabled)

            // Production tears the worker down on low memory and lets the next preview lazily
            // restart it; the same path must re-arm the fault policy too.
            resetGpuRenderer()

            assertTrue("A reset must re-arm the GPU path", OffscreenPreviewRenderer.isEnabled)
            val cpuFile = renderOnCpu(sourceBitmap(source), EditGraph(), File(workDir, "cpu-recovered"))
            assertQuadrants("CPU preview after reset", loadFrame(cpuFile))
        } finally {
            source.release()
        }
    }
}
