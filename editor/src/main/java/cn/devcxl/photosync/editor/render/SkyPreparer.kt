package cn.devcxl.photosync.editor.render

import timber.log.Timber

import cn.devcxl.photosync.editor.domain.ProceduralSky
import cn.devcxl.photosync.editor.domain.ProceduralSkyPreset
import cn.devcxl.photosync.editor.domain.SkyAssetStore
import cn.devcxl.photosync.editor.domain.SkyBlendParams
import cn.devcxl.photosync.editor.domain.SkyCpuLayer
import cn.devcxl.photosync.editor.domain.SkyOps
import cn.devcxl.photosync.editor.domain.SkyRelightMath
import cn.devcxl.photosync.editor.domain.SkyRelightStats
import cn.devcxl.photosync.editor.vision.SkyMask
import cn.devcxl.photosync.editor.vision.SkyMaskCodec
import cn.devcxl.photosync.editor.vision.SkyModelCatalog
import cn.devcxl.photosync.editor.vision.SubjectImage

import kotlin.math.min
import kotlin.math.roundToInt

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

    /** Stable key of the decoded pixels, so the canvas re-uploads only on a real change. */
    val key: String
        get() = buildString {
            append(ops.assetType.wireName).append('|').append(ops.assetIdOrRelPath).append('|')
            append(ops.assetSha256).append('|').append(mask.width).append('x').append(mask.height)
            append('|').append(mask.coverageRatio)
        }

    /** True when Pass D has something to composite. */
    val isActive: Boolean
        get() = procedural != null || asset != null
}

/** Packed ARGB pixels of the frame-aspect-cropped replacement sky. */
internal data class SkyAssetPixels(
    val argb: IntArray,
    val width: Int,
    val height: Int
) {

    init {
        require(argb.size == width * height) { "Sky asset pixels must match its dimensions" }
    }
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
        graph: cn.devcxl.photosync.editor.domain.EditGraph,
        filesDir: java.io.File,
        outputWidth: Int,
        outputHeight: Int,
        packFileResolver: (String) -> java.io.File? = { id ->
            SkyModelCatalog.packFile(java.io.File(filesDir, SkyModelCatalog.MODELS_DIR_NAME), id)
        },
        bake: Boolean = false,
        maskOverride: SkyMask? = null
    ): SkyLayerPixels? {
        val ops = graph.sky
        val blend = SkyBlendParams.from(graph) ?: return null
        if (ops.maskRef.isBlank() && maskOverride == null) return null
        val mask = maskOverride ?: loadMask(filesDir, ops.maskRef) ?: return null
        val assets = SkyAssetStore(filesDir, packFileResolver)
        val assetPixels = if (blend.procedural == null) {
            loadAsset(assets, ops, if (bake) BAKE_ASSET_MAX_EDGE else PREVIEW_ASSET_MAX_EDGE)
                ?: return null
        } else {
            null
        }
        val aspect = outputWidth.toFloat() / outputHeight.coerceAtLeast(1).toFloat()
        val cropped = assetPixels?.let { cropToAspect(it, aspect) }
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
     * The `1024px` proxy is decoded once; every column band of the bake consumes the resulting
     * [SkyLayerPixels.stats] instead of re-deriving local statistics, which is what prevents a
     * column-band colour seam.
     */
    fun prepareFromPath(
        sourcePath: String,
        isRaw: Boolean,
        graph: cn.devcxl.photosync.editor.domain.EditGraph,
        filesDir: java.io.File,
        outputWidth: Int,
        outputHeight: Int
    ): SkyLayerPixels? {
        if (graph.sky.isIdentity()) return null
        val bitmap = try {
            if (isRaw) {
                cn.devcxl.photosync.wrapper.RawWrapper.decodeThumbnailBitmap(sourcePath, PROXY_MAX_EDGE)
            } else {
                EditorBitmapLoader.loadUprightPreviewBitmap(sourcePath, isRaw = false)
            }
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while decoding the sky statistics proxy")
            null
        } catch (error: RuntimeException) {
            Timber.w(error, "The sky statistics proxy could not be decoded")
            null
        } ?: return null
        var proxy: android.graphics.Bitmap? = null
        return try {
            proxy = if (maxOf(bitmap.width, bitmap.height) > PROXY_MAX_EDGE) {
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * PROXY_MAX_EDGE / maxOf(bitmap.width, bitmap.height)).coerceAtLeast(1),
                    (bitmap.height * PROXY_MAX_EDGE / maxOf(bitmap.width, bitmap.height)).coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            val source = EditorSource.BitmapSource(full = bitmap, proxy = proxy)
            prepare(source, graph, filesDir, outputWidth, outputHeight, bake = true)
        } catch (oom: OutOfMemoryError) {
            Timber.e(oom, "OOM while preparing the bake sky layer")
            null
        } finally {
            if (proxy != null && proxy !== bitmap && !proxy.isRecycled) proxy.recycle()
            bitmap.recycle()
        }
    }

    /**
     * Builds the frame-aligned CPU composite of [layer].
     *
     * @param inverseMatrix Output-UV to pre-crop-UV matrix.
     * @param warpField Composed Pass A displacement, already applied to the pixels.
     */
    fun cpuLayer(
        layer: SkyLayerPixels?,
        inverseMatrix: cn.devcxl.photosync.editor.domain.Mat3,
        warpField: cn.devcxl.photosync.editor.domain.WarpDisplacementField?,
        width: Int,
        height: Int
    ): SkyCpuLayer? {
        if (layer == null || width <= 0 || height <= 0 || !layer.isActive) return null
        return try {
            val coverage = FloatArray(width * height)
            val colours = IntArray(width * height)
            val sky = FloatArray(3)
            val shortEdge = minOf(width, height)
            val edgeScale = 1.0f + layer.blend.edgeShift * 2.0f / shortEdge
            for (row in 0 until height) {
                val v = (row + 0.5f) / height
                for (column in 0 until width) {
                    val u = (column + 0.5f) / width
                    val shiftedU = 0.5f + (u - 0.5f) * edgeScale
                    val shiftedV = 0.5f + (v - 0.5f) * edgeScale
                    val mapped = inverseMatrix.map(shiftedU, shiftedV)
                    val warped = warpField?.let { field ->
                        val displacement = field.displacementAt(mapped.u, mapped.v)
                        cn.devcxl.photosync.editor.domain.Uv(
                            mapped.u + displacement.u,
                            mapped.v + displacement.v
                        )
                    } ?: mapped
                    val index = row * width + column
                    coverage[index] = layer.mask.sample(warped.u, warped.v)
                    colours[index] = skyColour(layer, u, v - layer.blend.horizonOffset, sky)
                }
            }
            SkyCpuLayer(width, height, coverage, colours, layer.blend, layer.stats, layer.ops.relight)
        } catch (oom: OutOfMemoryError) {
            null
        }
    }

    private fun skyColour(layer: SkyLayerPixels, u: Float, v: Float, out: FloatArray): Int {
        val preset = layer.procedural
        if (preset != null) {
            ProceduralSky.sampleSrgb(preset, u, v, 0.0f, out)
            return pack(out)
        }
        val asset = layer.asset ?: return 0
        val x = (u.coerceIn(0.0f, 0.999999f) * asset.width).toInt().coerceIn(0, asset.width - 1)
        val y = (v.coerceIn(0.0f, 0.999999f) * asset.height).toInt().coerceIn(0, asset.height - 1)
        return asset.argb[y * asset.width + x] and 0xFFFFFF
    }

    private fun loadMask(filesDir: java.io.File, maskRef: String): SkyMask? {
        val digest = SkyMaskCodec.digestFromRef(maskRef) ?: return null
        val payload = cn.devcxl.photosync.editor.domain.AiAssetStore(filesDir)
            .read(maskRef, digest) ?: return null
        return SkyMaskCodec.decode(payload)
    }

    private fun loadAsset(
        assets: SkyAssetStore,
        ops: SkyOps,
        maxEdge: Int
    ): SkyAssetPixels? {
        val payload = assets.read(ops) ?: return null
        val bitmap = decodeBounded(payload, maxEdge) ?: return null
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            SkyAssetPixels(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeBounded(payload: ByteArray, maxEdge: Int): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(payload, 0, payload.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        return android.graphics.BitmapFactory.decodeByteArray(payload, 0, payload.size, options)
    }

    private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > maxEdge) sample *= 2
        return sample
    }

    private fun cropToAspect(asset: SkyAssetPixels, aspect: Float): SkyAssetPixels {
        if (!aspect.isFinite() || aspect <= 0.0f) return asset
        val sourceAspect = asset.width.toFloat() / asset.height.toFloat()
        val targetWidth: Int
        val targetHeight: Int
        val offsetX: Int
        val offsetY: Int
        if (sourceAspect > aspect) {
            targetHeight = asset.height
            targetWidth = (asset.height * aspect).roundToInt().coerceIn(1, asset.width)
            offsetX = (asset.width - targetWidth) / 2
            offsetY = 0
        } else {
            targetWidth = asset.width
            targetHeight = (asset.width / aspect).roundToInt().coerceIn(1, asset.height)
            offsetX = 0
            offsetY = (asset.height - targetHeight) / 2
        }
        if (targetWidth == asset.width && targetHeight == asset.height) return asset
        val out = IntArray(targetWidth * targetHeight)
        for (row in 0 until targetHeight) {
            val sourceRow = row + offsetY
            for (column in 0 until targetWidth) {
                out[row * targetWidth + column] =
                    asset.argb[sourceRow * asset.width + column + offsetX]
            }
        }
        return SkyAssetPixels(out, targetWidth, targetHeight)
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
            SkyRelightMath.imageMean(asset?.let { SubjectImage(it.width, it.height, it.argb) }
                ?: return SkyRelightStats.NEUTRAL)
        }
        return SkyRelightMath.compute(original, replacement)
    }

    private fun pack(srgb: FloatArray): Int =
        ((srgb[0].coerceIn(0.0f, 1.0f) * EIGHT_BIT + 0.5f).toInt().coerceIn(0, 255) shl RED_SHIFT) or
            ((srgb[1].coerceIn(0.0f, 1.0f) * EIGHT_BIT + 0.5f).toInt().coerceIn(0, 255) shl GREEN_SHIFT) or
            (srgb[2].coerceIn(0.0f, 1.0f) * EIGHT_BIT + 0.5f).toInt().coerceIn(0, 255)
}

/**
 * Decodes the `1024px` proxy of an [EditorSource] into the packed image the relight statistics read.
 */
internal object SkySourceProxy {

    private const val CHANNELS = 4
    private const val EIGHT_BIT = 255.0f
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    fun of(source: EditorSource): SubjectImage? = when (source) {
        is EditorSource.BitmapSource -> fromBitmap(source.proxy)
        is EditorSource.LinearRawSource -> fromLinear(source.proxy)
    }

    private fun fromBitmap(bitmap: android.graphics.Bitmap): SubjectImage? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return SubjectImage(bitmap.width, bitmap.height, pixels)
    }

    private fun fromLinear(buffer: cn.devcxl.photosync.wrapper.LinearRawBuffer): SubjectImage? {
        if (buffer.width <= 0 || buffer.height <= 0) return null
        val pixels = IntArray(buffer.width * buffer.height)
        val sample = FloatArray(CHANNELS)
        for (index in pixels.indices) {
            buffer.readPixel(index, sample)
            pixels[index] = (0xFF shl 24) or
                (encode(sample[0]) shl RED_SHIFT) or
                (encode(sample[1]) shl GREEN_SHIFT) or
                encode(sample[2])
        }
        return SubjectImage(buffer.width, buffer.height, pixels)
    }

    private fun encode(linear: Float): Int =
        (cn.devcxl.photosync.editor.domain.ExposureShaderMath.encodeSrgb(linear.coerceIn(0.0f, 1.0f)) *
            EIGHT_BIT + 0.5f).toInt().coerceIn(0, 255)
}
