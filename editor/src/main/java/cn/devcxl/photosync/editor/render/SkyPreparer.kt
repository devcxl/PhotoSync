package cn.devcxl.photosync.editor.render

import java.io.File

import timber.log.Timber

import cn.devcxl.photosync.editor.domain.AiAssetStore
import cn.devcxl.photosync.editor.domain.EditGraph
import cn.devcxl.photosync.editor.domain.Mat3
import cn.devcxl.photosync.editor.domain.ProceduralSky
import cn.devcxl.photosync.editor.domain.ProceduralSkyPreset
import cn.devcxl.photosync.editor.domain.SkyAssetStore
import cn.devcxl.photosync.editor.domain.SkyBlendMath
import cn.devcxl.photosync.editor.domain.SkyBlendParams
import cn.devcxl.photosync.editor.domain.SkyCpuLayer
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.domain.SkyRelightMath
import cn.devcxl.photosync.editor.domain.SkyRelightStats
import cn.devcxl.photosync.editor.domain.Uv
import cn.devcxl.photosync.editor.domain.WarpDisplacementField
import cn.devcxl.photosync.editor.vision.AiModelDownloadManager
import cn.devcxl.photosync.editor.vision.SkyMask
import cn.devcxl.photosync.editor.vision.SkyMaskCodec
import cn.devcxl.photosync.editor.vision.SkyModelCatalog
import cn.devcxl.photosync.editor.vision.SubjectImage
import cn.devcxl.photosync.wrapper.RawWrapper

/**
 * One decoded, digest-verified sky replacement ready for the renderer.
 *
 * @property mask Source-space sky probability map of this photo.
 * @property asset Frame-aspect-cropped replacement image; `null` for a procedural sky.
 * @property procedural Analytic preset; `null` for a custom/pack texture.
 * @property blend Pass D spatial parameters.
 * @property stats The single Pre-pass OKLab delta every band applies.
 * @property ops The sanitized graph group that produced this layer.
 */
internal data class SkyLayerPixels(
    val mask: SkyMask,
    val asset: SkyAssetPixels?,
    val procedural: ProceduralSkyPreset?,
    val blend: SkyBlendParams,
    val stats: SkyRelightStats,
    val ops: SkyOps
) {

    /** True when Pass D has something to composite. */
    val isActive: Boolean
        get() = procedural != null || asset != null
}

/**
 * Builds a [SkyLayerPixels] from the persisted `SkyOps` of one photo.
 *
 * The mask is read through the content-addressed store with its SHA-256 verified, and the replacement
 * image is decoded at a bounded edge and centre-cropped to the output aspect so the Pass D shader can
 * sample it with plain frame UVs. The OKLab relight delta is derived here, once, from the photo's
 * `1024px` proxy and the replacement mean — every preview frame and every bake band then consumes
 * the same three numbers, which is what removes the column-band seam.
 */
internal object SkyPreparer {

    private const val CHANNELS = 4
    private const val EIGHT_BIT = 255.0f
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val PROXY_MAX_EDGE = 1024
    private const val PREVIEW_ASSET_MAX_EDGE = 2048
    private const val BAKE_ASSET_MAX_EDGE = 4096

    /**
     * Resolves the graph's sky for a render.
     *
     * @param source Current decoded source pixels.
     * @param outputWidth Cropped frame width in pixels.
     * @param outputHeight Cropped frame height in pixels.
     * @param packFileResolver Looks up an installed downloadable sky pack image.
     * @return The layer, or `null` when the graph has no sky, its mask is missing or its asset cannot
     *   be verified. A missing asset is a normal degraded frame, never a crash.
     */
    fun prepare(
        source: EditorSource,
        graph: EditGraph,
        filesDir: File,
        outputWidth: Int,
        outputHeight: Int,
        packFileResolver: (String) -> File? = { id -> installedPackFile(filesDir, id) },
        bake: Boolean = false,
        maskOverride: SkyMask? = null
    ): SkyLayerPixels? {
        val ops = graph.sky
        val blend = SkyBlendParams.from(graph) ?: return null
        if (ops.maskRef.isBlank() && maskOverride == null) return null
        val mask = maskOverride ?: loadMask(filesDir, ops.maskRef) ?: return null
        // The editor and the bake use the same threshold: a photo without an obvious sky must never
        // gain a destructive replacement because one path rounded the coverage differently.
        if (mask.computeCoverageRatio() < SkyMask.MIN_USABLE_COVERAGE) {
            Timber.i("Sky replacement skipped: the mask covers less than 3 %")
            return null
        }
        val assets = SkyAssetStore(filesDir, packFileResolver)
        val assetPixels = if (blend.procedural == null) {
            SkyAssetDecoder.load(assets, ops, if (bake) BAKE_ASSET_MAX_EDGE else PREVIEW_ASSET_MAX_EDGE)
                ?: return null
        } else {
            null
        }
        val aspect = outputWidth.toFloat() / outputHeight.coerceAtLeast(1).toFloat()
        val cropped = assetPixels?.let { SkyAssetDecoder.cropToAspect(it, aspect) }
        val stats = computeStats(source, mask, blend.procedural, cropped)
        return SkyLayerPixels(
            mask = mask,
            asset = cropped,
            procedural = blend.procedural,
            blend = blend,
            stats = stats,
            ops = ops
        )
    }

    /**
     * Builds the graph's sky layer from a source path for the bake and background-preview paths.
     *
     * RAW files are decoded through the same LibRaw linear path as the bake source textures and
     * JPEGs through the same EXIF-upright loader, so the mask, the replacement statistics and the
     * baked pixels can never disagree about orientation.
     */
    fun prepareFromPath(
        sourcePath: String,
        isRaw: Boolean,
        graph: EditGraph,
        filesDir: File,
        outputWidth: Int,
        outputHeight: Int
    ): SkyLayerPixels? {
        if (graph.sky.isIdentity()) return null
        val source = try {
            loadUprightProxy(sourcePath, isRaw)
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while decoding the sky statistics proxy")
            null
        } catch (error: RuntimeException) {
            Timber.w(error, "The sky statistics proxy could not be decoded")
            null
        } ?: return null
        return try {
            prepare(
                source = source,
                graph = graph,
                filesDir = filesDir,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                bake = true
            )
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while preparing the bake sky layer")
            null
        } finally {
            source.release()
        }
    }

    /**
     * Builds the frame-aligned CPU composite of [layer].
     *
     * @param inverseMatrix Output-UV to pre-crop-UV matrix.
     * @param warpField Composed Pass A displacement, already applied to the pixels.
     * @param referenceShortEdge Full-resolution source short edge that defines the `edgeShift` unit.
     */
    fun cpuLayer(
        layer: SkyLayerPixels?,
        inverseMatrix: Mat3,
        warpField: WarpDisplacementField?,
        width: Int,
        height: Int,
        referenceShortEdge: Int
    ): SkyCpuLayer? {
        if (layer == null || width <= 0 || height <= 0 || !layer.isActive) return null
        return try {
            val coverage = FloatArray(width * height)
            val colours = IntArray(width * height)
            val sky = FloatArray(3)
            val edgeScale = SkyBlendMath.edgeScale(layer.blend.edgeShift, referenceShortEdge)
            for (row in 0 until height) {
                val v = (row + 0.5f) / height
                for (column in 0 until width) {
                    val u = (column + 0.5f) / width
                    val shiftedU = 0.5f + (u - 0.5f) * edgeScale
                    val shiftedV = 0.5f + (v - 0.5f) * edgeScale
                    val mapped = inverseMatrix.map(shiftedU, shiftedV)
                    val warped = warpField?.let { field ->
                        val displacement = field.displacementAt(mapped.u, mapped.v)
                        Uv(mapped.u + displacement.u, mapped.v + displacement.v)
                    } ?: mapped
                    val index = row * width + column
                    coverage[index] = layer.mask.sample(warped.u, warped.v)
                    colours[index] = skyColour(layer, u, v, sky)
                }
            }
            SkyCpuLayer(width, height, coverage, colours, layer.blend, layer.stats, layer.ops.relight)
        } catch (oom: OutOfMemoryError) {
            null
        }
    }

    private fun loadUprightProxy(sourcePath: String, isRaw: Boolean): EditorSource? {
        if (isRaw) {
            val linear = RawWrapper.decodeToLinearRgba16F(sourcePath, PROXY_MAX_EDGE) ?: return null
            return EditorSource.LinearRawSource(
                full = linear,
                proxy = linear.downscaledTo(PROXY_MAX_EDGE)
            )
        }
        val full = EditorBitmapLoader.loadUprightPreviewBitmap(sourcePath, isRaw = false) ?: return null
        return EditorSource.BitmapSource(full = full, proxy = EditorBitmapLoader.createProxyBitmap(full))
    }

    /** Samples [layer] once; the horizon offset is applied here and nowhere else. */
    private fun skyColour(layer: SkyLayerPixels, u: Float, v: Float, out: FloatArray): Int {
        val preset = layer.procedural
        if (preset != null) {
            ProceduralSky.sampleSrgb(
                sky = preset,
                u = u,
                v = v,
                horizonOffset = layer.blend.horizonOffset,
                out = out,
                featherFraction = layer.blend.featherFraction
            )
            return pack(out)
        }
        val asset = layer.asset ?: return 0
        val worldV = v - layer.blend.horizonOffset
        val x = (u.coerceIn(0.0f, 0.999999f) * asset.width).toInt().coerceIn(0, asset.width - 1)
        val y = (worldV.coerceIn(0.0f, 0.999999f) * asset.height).toInt().coerceIn(0, asset.height - 1)
        return asset.argb[y * asset.width + x] and 0xFFFFFF
    }

    private fun loadMask(filesDir: File, maskRef: String): SkyMask? {
        val digest = SkyMaskCodec.digestFromRef(maskRef) ?: return null
        val payload = AiAssetStore(filesDir).read(maskRef, digest) ?: return null
        return SkyMaskCodec.decode(payload)
    }

    private fun computeStats(
        source: EditorSource,
        mask: SkyMask,
        procedural: ProceduralSkyPreset?,
        asset: SkyAssetPixels?
    ): SkyRelightStats {
        val proxy = SkySourceProxy.of(source) ?: return SkyRelightStats.NEUTRAL
        val original = SkyRelightMath.originalSkyMean(proxy, mask) ?: return SkyRelightStats.NEUTRAL
        val replacement = if (procedural != null) {
            ProceduralSky.meanLinear(procedural)
        } else {
            val pixels = asset ?: return SkyRelightStats.NEUTRAL
            SkyRelightMath.imageMean(SubjectImage(pixels.width, pixels.height, pixels.argb))
        }
        return SkyRelightMath.compute(original, replacement)
    }

    /**
     * Resolves a pack id only when the shared model manager reports it as installed.
     *
     * The catalog alone cannot prove integrity: an unpinned or partially downloaded pack must
     * degrade to the original sky exactly like a missing user asset.
     */
    private fun installedPackFile(filesDir: File, id: String): File? {
        val spec = SkyModelCatalog.packById(id) ?: return null
        val modelsRoot = File(filesDir, SkyModelCatalog.MODELS_DIR_NAME)
        if (!AiModelDownloadManager(modelsRoot).isInstalled(spec)) return null
        return SkyModelCatalog.packFile(modelsRoot, id)
    }

    private fun pack(srgb: FloatArray): Int =
        ((srgb[0].coerceIn(0.0f, 1.0f) * EIGHT_BIT + 0.5f).toInt().coerceIn(0, 255) shl RED_SHIFT) or
            ((srgb[1].coerceIn(0.0f, 1.0f) * EIGHT_BIT + 0.5f).toInt().coerceIn(0, 255) shl GREEN_SHIFT) or
            (srgb[2].coerceIn(0.0f, 1.0f) * EIGHT_BIT + 0.5f).toInt().coerceIn(0, 255)
}
