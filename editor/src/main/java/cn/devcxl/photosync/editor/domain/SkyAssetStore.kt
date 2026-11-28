package cn.devcxl.photosync.editor.domain

import java.io.File

import timber.log.Timber

/**
 * The resolved source of a `SkyOps` replacement sky.
 *
 * [Missing] is a normal, non-fatal outcome: the renderer keeps the original sky instead of failing
 * the frame, exactly like a preset whose referenced asset was deleted.
 */
sealed interface SkyAssetRef {

    /** One built-in analytic sky; no file is read. */
    data class Procedural(val preset: ProceduralSkyPreset) : SkyAssetRef

    /** A user-imported JPEG under `filesDir/skies/`. */
    data class Custom(
        val sha256: String,
        val relativePath: String,
        val file: File
    ) : SkyAssetRef

    /** A downloadable CC0 sky pack image, already verified by the shared model manager. */
    data class Pack(
        val packId: String,
        val file: File,
        val sha256: String
    ) : SkyAssetRef

    /** No usable asset; the graph's sky group must render as a no-op. */
    data object Missing : SkyAssetRef
}

/**
 * Resolves `SkyOps` to a concrete asset without decoding it.
 *
 * The store owns the custom (SAF) directory itself and receives a pack resolver so the `:editor`
 * module never depends on the model catalog or the download manager. The editor and the bake both
 * decode the returned bytes, so the same SHA-256 check guards both paths.
 *
 * @param filesDir App-private files directory; custom skies live under `skies/`.
 * @param packFileResolver Returns the verified file of a downloadable sky pack id, or `null` when
 *   that pack is not installed. Defaults to "no packs", which is the offline-only configuration.
 */
class SkyAssetStore(
    filesDir: File,
    private val packFileResolver: (String) -> File? = { null }
) {

    /** The store of user-imported skies. */
    val custom: SkyCustomAssetStore = SkyCustomAssetStore(filesDir)

    /**
     * Resolves [sky] to its asset.
     *
     * @return A [SkyAssetRef] whose file may still be missing from disk; [read] is the verifier.
     */
    fun resolve(sky: SkyOps): SkyAssetRef = when (sky.assetType) {
        SkyAssetType.NONE -> SkyAssetRef.Missing
        SkyAssetType.PROCEDURAL -> SkyAssetRef.Procedural(
            ProceduralSkyPreset.fromWireId(sky.assetIdOrRelPath) ?: ProceduralSkyPreset.DEFAULT
        )
        SkyAssetType.CUSTOM -> resolveCustom(sky)
        SkyAssetType.PACK -> resolvePack(sky)
    }

    /**
     * Reads the bytes of a non-procedural [sky] while verifying its digest.
     *
     * @return The JPEG bytes, or `null` for a procedural/missing asset or a digest failure.
     */
    fun read(sky: SkyOps): ByteArray? = when (val asset = resolve(sky)) {
        is SkyAssetRef.Custom -> custom.read(asset.sha256)
        is SkyAssetRef.Pack -> readPack(asset)
        is SkyAssetRef.Procedural, SkyAssetRef.Missing -> null
    }

    private fun resolveCustom(sky: SkyOps): SkyAssetRef {
        val digest = sky.assetSha256.trim()
        // A custom asset is only addressable through a full SHA-256. A blank digest must never skip
        // the integrity check, otherwise a hand-edited graph could point at arbitrary bytes.
        if (!isSha256Hex(digest)) return SkyAssetRef.Missing
        val file = custom.fileFor(digest) ?: return SkyAssetRef.Missing
        if (!file.isFile) return SkyAssetRef.Missing
        return SkyAssetRef.Custom(
            sha256 = digest.lowercase(),
            relativePath = custom.shaFromReference(digest)?.let { "skies/sky_$it.jpg" }.orEmpty(),
            file = file
        )
    }

    private fun resolvePack(sky: SkyOps): SkyAssetRef {
        val packId = sky.assetIdOrRelPath.trim()
        if (packId.isEmpty()) return SkyAssetRef.Missing
        val digest = sky.assetSha256.trim()
        if (!isSha256Hex(digest)) return SkyAssetRef.Missing
        val file = packFileResolver(packId) ?: return SkyAssetRef.Missing
        if (!file.isFile) return SkyAssetRef.Missing
        return SkyAssetRef.Pack(packId = packId, file = file, sha256 = digest)
    }

    private fun readPack(asset: SkyAssetRef.Pack): ByteArray? {
        if (asset.file.length() > MAX_PACK_BYTES) return null
        val payload = try {
            asset.file.readBytes()
        } catch (io: java.io.IOException) {
            Timber.e(io, "Sky pack could not be read")
            return null
        }
        val actual = LiquifyMeshCodec.sha256Hex(payload)
        if (!actual.equals(asset.sha256, ignoreCase = true)) {
            Timber.w("Sky pack rejected: sha256 digest mismatch")
            return null
        }
        return payload
    }

    private companion object {
        /** Hard ceiling of one downloadable sky image. */
        const val MAX_PACK_BYTES = 48L * 1024L * 1024L
    }
}
