package cn.devcxl.photosync.editor.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.EditorUndoController
import cn.devcxl.photosync.editor.domain.ProceduralSkyPreset
import cn.devcxl.photosync.editor.domain.SkyAssetType
import cn.devcxl.photosync.editor.domain.SkyCustomAssetStore
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.lut.CubeLutStore
import cn.devcxl.photosync.editor.render.EditorGlRenderer
import cn.devcxl.photosync.editor.render.EditorPreviewSession
import cn.devcxl.photosync.editor.render.EditorSource
import cn.devcxl.photosync.editor.ui.panels.AiInferenceBarController
import cn.devcxl.photosync.editor.vision.SkyMask
import cn.devcxl.photosync.editor.vision.SkySegmentation
import cn.devcxl.photosync.editor.vision.SkySegmenter
import cn.devcxl.photosync.editor.vision.SubjectImage

/**
 * Seam under test: [EditorSkyController] — a `< 3 %` segmentation restores the neutral sky instead
 * of keeping a half-built group, and deleting an imported asset clears the graph that referenced it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorSkyControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var application: Application
    private lateinit var scope: CoroutineScope
    private lateinit var session: EditorPreviewSession

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        session = EditorPreviewSession(
            renderer = EditorGlRenderer(),
            cacheDir = temporaryFolder.newFolder("cache"),
            photoId = 1L,
            lutStore = CubeLutStore(temporaryFolder.root),
            filesDir = temporaryFolder.root
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun shouldRestoreTheNeutralSky_whenSegmentationFindsLessThanThreePercent() {
        val undo = EditorUndoController(
            EditGraph(
                sky = SkyOps(
                    assetType = SkyAssetType.PROCEDURAL,
                    assetIdOrRelPath = ProceduralSkyPreset.CLEAR_BLUE.wireId
                )
            )
        )
        val controller = controller(
            undo = undo,
            segmenter = FixedSkySegmenter(maskOf(0.01f)),
            sourceProvider = { tinySource() }
        )

        val applied = runBlocking { controller.applySkyWhenMaskAvailable(proceduralSky()) }

        assertFalse(applied)
        assertEquals(SkyOps(), undo.currentGraph.sky)
    }

    @Test
    fun shouldDeleteTheImportedAssetAndClearTheGraph_whenDeletingACustomSky() {
        val store = SkyCustomAssetStore(temporaryFolder.root)
        val asset = checkNotNull(store.importJpeg(jpegOf(64, 64), 64, 64))
        val undo = EditorUndoController(
            EditGraph(
                sky = SkyOps(
                    assetType = SkyAssetType.CUSTOM,
                    assetIdOrRelPath = asset.relativePath,
                    assetSha256 = asset.sha256
                )
            )
        )
        val controller = controller(undo = undo, segmenter = FixedSkySegmenter(maskOf(0.0f)))

        controller.deleteCustom(asset.sha256)

        assertFalse(store.contains(asset.sha256))
        assertEquals(SkyOps(), undo.currentGraph.sky)
    }

    private fun controller(
        undo: EditorUndoController,
        segmenter: SkySegmenter,
        sourceProvider: () -> EditorSource? = { null }
    ): EditorSkyController = EditorSkyController(
        context = application,
        filesDir = temporaryFolder.root,
        photoId = 1L,
        undoController = undo,
        previewSession = session,
        inferenceBar = AiInferenceBarController(
            host = object : AiInferenceBarController.Host {
                override fun setVisible(visible: Boolean) = Unit
                override fun setLabel(label: CharSequence) = Unit
                override fun setProgress(percent: Int) = Unit
            },
            onCancelRequested = {}
        ),
        scope = scope,
        onGraphEdited = { _, _ -> },
        onGraphCommitted = { _, _ -> },
        onUiRefresh = {},
        segmenter = segmenter,
        sourceProvider = sourceProvider
    )

    private fun proceduralSky(): SkyOps = SkyOps(
        assetType = SkyAssetType.PROCEDURAL,
        assetIdOrRelPath = ProceduralSkyPreset.CLEAR_BLUE.wireId
    )

    private fun tinySource(): EditorSource {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(80, 140, 210))
        }
        return EditorSource.BitmapSource(full = bitmap, proxy = bitmap)
    }

    private fun maskOf(skyFraction: Float): SkyMask {
        val width = 8
        val height = 8
        val skyRows = (height * skyFraction).toInt()
        val probability = FloatArray(width * height) { index ->
            if (index / width < skyRows) 1.0f else 0.0f
        }
        return SkyMask(width, height, probability, SkyMask.coverageOf(probability))
    }

    /** Minimal JPEG: SOI, one SOF0 carrying [width] x [height], EOI. */
    private fun jpegOf(width: Int, height: Int): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xC0.toByte(),
        0x00, 0x11,
        0x08,
        (height ushr 8).toByte(), height.toByte(),
        (width ushr 8).toByte(), width.toByte(),
        0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
        0xFF.toByte(), 0xD9.toByte()
    )

    private class FixedSkySegmenter(private val mask: SkyMask) : SkySegmenter {
        override fun segment(image: SubjectImage): Result<SkySegmentation> =
            Result.success(SkySegmentation(mask, mask.coverageRatio >= SkyMask.MIN_USABLE_COVERAGE))
    }
}
