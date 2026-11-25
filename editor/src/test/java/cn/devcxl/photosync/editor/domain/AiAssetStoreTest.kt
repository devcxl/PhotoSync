package cn.devcxl.photosync.editor.domain

import java.io.File

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Seam under test: [AiAssetStore] — the content-addressed storage contract of the `EditGraph` v2
 * pixel payloads.
 *
 * A committed graph row verifies its payload by SHA-256, so the store must never overwrite bytes a
 * row already points at, must reject tampered files, and must clean a photo's directory together
 * with its edit row while offline GC only touches provably unreferenced payloads.
 */
class AiAssetStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val root: File by lazy { temporaryFolder.root }

    private fun store(): AiAssetStore = AiAssetStore(root)

    @Test
    fun shouldWriteContentAddressedPayload_whenWritingAnAsset() {
        val payload = byteArrayOf(1, 2, 3, 4)

        val ref = checkNotNull(store().write(7L, AiAssetKind.SUBJECT_MASK, payload))

        assertEquals(
            "edits/7/subject_mask_${LiquifyMeshCodec.sha256Hex(payload)}.png",
            ref.relativePath
        )
        assertEquals(64, ref.sha256.length)
        assertTrue(File(root, ref.relativePath).isFile)
        assertArrayEquals(payload, store().read(ref))
        assertTrue(photoFiles(7L).none { it.name.endsWith(".tmp") })
    }

    @Test
    fun shouldKeepTheOldPayloadReadable_whenWritingAnotherPayloadForTheSamePhoto() {
        val first = checkNotNull(store().write(7L, AiAssetKind.SUBJECT_MASK, byteArrayOf(1, 2, 3)))
        val second = checkNotNull(store().write(7L, AiAssetKind.SUBJECT_MASK, byteArrayOf(9, 9, 9)))

        assertNotEquals(first.relativePath, second.relativePath)
        assertArrayEquals(byteArrayOf(1, 2, 3), store().read(first))
        assertArrayEquals(byteArrayOf(9, 9, 9), store().read(second))
        assertEquals(2, photoFiles(7L).size)
    }

    @Test
    fun shouldReuseTheSameFile_whenWritingIdenticalPayloadTwice() {
        val payload = byteArrayOf(5, 6, 7, 8)

        val first = checkNotNull(store().write(7L, AiAssetKind.INPAINT_PATCH, payload))
        val second = checkNotNull(store().write(7L, AiAssetKind.INPAINT_PATCH, payload))

        assertEquals(first, second)
        assertEquals(1, photoFiles(7L).size)
    }

    @Test
    fun shouldRejectTamperedPayload_whenReadingAnAsset() {
        val ref = checkNotNull(store().write(7L, AiAssetKind.CLOUD_LAYER, byteArrayOf(1, 2, 3)))
        File(root, ref.relativePath).writeBytes(byteArrayOf(1, 2, 4))

        assertNull(store().read(ref))
    }

    @Test
    fun shouldRejectMalformedDigestAndEscapingPath_whenReading() {
        val rootFile = File(root, "secret.txt")
        rootFile.writeBytes(byteArrayOf(42))
        val digest = LiquifyMeshCodec.sha256Hex(byteArrayOf(42))

        assertNull(store().read("secret.txt", digest))
        assertNull(store().read("edits/../secret.txt", digest))
        assertNull(store().read("edits/7/none.png", "not-a-digest"))
    }

    @Test
    fun shouldRejectEmptyPayloadOrUnsupportedExtension_whenWriting() {
        assertNull(store().write(7L, AiAssetKind.SUBJECT_MASK, ByteArray(0)))
        assertNull(store().write(0L, AiAssetKind.SUBJECT_MASK, byteArrayOf(1)))
        assertNull(store().write(7L, AiAssetKind.CLOUD_LAYER, byteArrayOf(1), extension = "exe"))
    }

    @Test
    fun shouldUseTheRequestedExtension_whenWritingAJpegCloudLayer() {
        val ref = checkNotNull(
            store().write(7L, AiAssetKind.CLOUD_LAYER, byteArrayOf(1), extension = "JPG")
        )

        assertTrue(ref.relativePath.endsWith(".jpg"))
        assertArrayEquals(byteArrayOf(1), store().read(ref))
    }

    @Test
    fun shouldDeleteTheWholePhotoDirectory_whenDeletingPhotoAssets() {
        val kept = checkNotNull(store().write(8L, AiAssetKind.SKY_MASK, byteArrayOf(1)))
        checkNotNull(store().write(7L, AiAssetKind.SUBJECT_MASK, byteArrayOf(2)))
        checkNotNull(store().write(7L, AiAssetKind.INPAINT_PATCH, byteArrayOf(3)))

        assertTrue(store().deletePhotoAssets(7L))

        assertFalse(File(root, "edits/7").exists())
        assertArrayEquals(byteArrayOf(1), store().read(kept))
    }

    @Test
    fun shouldSkipActivePhotosAndDeleteOnlyUnreferencedAssets_whenCollectingOrphans() {
        val referenced = checkNotNull(store().write(7L, AiAssetKind.SUBJECT_MASK, byteArrayOf(1, 1)))
        val orphanOnReferencedPhoto = checkNotNull(
            store().write(7L, AiAssetKind.INPAINT_PATCH, byteArrayOf(2, 2))
        )
        val orphanOnIdlePhoto = checkNotNull(
            store().write(8L, AiAssetKind.CLOUD_LAYER, byteArrayOf(3, 3, 3))
        )
        val activePhotoAsset = checkNotNull(
            store().write(9L, AiAssetKind.SKY_ASSET, byteArrayOf(4))
        )

        val report = store().collectOrphanAssets(
            referencedRelativePathsProvider = { setOf(referenced.relativePath) },
            activePhotoIds = setOf(9L)
        )

        assertEquals(3, report.scannedPhotoDirectories)
        assertEquals(1, report.skippedActivePhotoDirectories)
        assertEquals(2, report.deletedFiles)
        assertEquals(5L, report.reclaimedBytes)
        assertArrayEquals(byteArrayOf(1, 1), store().read(referenced))
        assertNull(store().read(orphanOnReferencedPhoto))
        assertNull(store().read(orphanOnIdlePhoto))
        assertArrayEquals(byteArrayOf(4), store().read(activePhotoAsset))
        assertFalse(File(root, "edits/8").exists())
        assertTrue(File(root, "edits/9").isDirectory)
    }

    @Test
    fun shouldLeaveLiquifyMeshesAlone_whenCollectingAiOrphans() {
        val aiAsset = checkNotNull(store().write(7L, AiAssetKind.SUBJECT_MASK, byteArrayOf(1)))
        val liquifyMesh = File(root, "edits/7/liquify_128x128_${"c".repeat(64)}.bin")
        liquifyMesh.parentFile?.mkdirs()
        liquifyMesh.writeBytes(byteArrayOf(7, 7))

        val report = store().collectOrphanAssets(
            referencedRelativePathsProvider = { emptySet() },
            activePhotoIds = emptySet()
        )

        assertEquals(1, report.deletedFiles)
        assertNull(store().read(aiAsset))
        assertTrue("liquify payloads are owned by LiquifyMeshStore", liquifyMesh.isFile)
        assertTrue("the directory still holds the liquify mesh", File(root, "edits/7").isDirectory)
    }

    @Test
    fun shouldDeleteNothing_whenTheReferenceProviderFails() {
        val asset = checkNotNull(store().write(7L, AiAssetKind.SUBJECT_MASK, byteArrayOf(1)))

        val report = store().collectOrphanAssets(
            referencedRelativePathsProvider = { throw IllegalStateException("database closed") },
            activePhotoIds = emptySet()
        )

        assertEquals(0, report.deletedFiles)
        assertArrayEquals(byteArrayOf(1), store().read(asset))
    }

    private fun photoFiles(photoId: Long): List<File> =
        File(root, "edits/$photoId").listFiles()?.toList().orEmpty()
}
