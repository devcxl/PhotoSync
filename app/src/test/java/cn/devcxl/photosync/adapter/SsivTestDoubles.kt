package cn.devcxl.photosync.adapter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import java.util.ArrayDeque
import java.util.concurrent.Executor

/** Test doubles shared by the Robolectric SSIV regression tests. */

/** Single-threaded queue used as SSIV's executor so tests own the ordering. */
internal class SsivManualExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    val pendingCount: Int
        get() = tasks.size

    override fun execute(command: Runnable) {
        tasks.add(command)
    }

    fun runNext(): Boolean {
        val task = tasks.poll() ?: return false
        task.run()
        return true
    }
}

internal class SsivRecordingListener : SubsamplingScaleImageView.DefaultOnImageEventListener() {
    val errors = mutableListOf<Exception>()
    var readyCount = 0
    var imageLoadedCount = 0

    override fun onReady() {
        readyCount++
    }

    override fun onImageLoaded() {
        imageLoadedCount++
    }

    override fun onImageLoadError(e: Exception) {
        errors += e
    }
}

internal class SsivFakeRegionDecoderFactory(
    private val width: Int,
    private val height: Int
) : DecoderFactory<ImageRegionDecoder> {

    private val decoders = mutableListOf<SsivFakeRegionDecoder>()

    val initUris = mutableListOf<Uri>()
    val makeCount: Int
        get() = decoders.size

    override fun make(): ImageRegionDecoder {
        return SsivFakeRegionDecoder(width, height) { initUris += it }.also { decoders += it }
    }

    fun only(): SsivFakeRegionDecoder {
        check(decoders.size == 1) { "expected exactly one decoder, got ${decoders.size}" }
        return decoders.single()
    }
}

internal class SsivFakeRegionDecoder(
    private val width: Int,
    private val height: Int,
    private val onInit: (Uri) -> Unit
) : ImageRegionDecoder {

    var initUri: Uri? = null
        private set
    var recycled: Boolean = false
        private set
    private var ready = false

    override fun init(context: Context, uri: Uri): Point {
        initUri = uri
        onInit(uri)
        ready = true
        return Point(width, height)
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    override fun isReady(): Boolean = ready && !recycled

    override fun recycle() {
        recycled = true
        ready = false
    }
}

internal class SsivFakeBitmapDecoderFactory(
    private val size: Int
) : DecoderFactory<ImageDecoder> {

    var makeCount: Int = 0
        private set
    var lastDecodeUri: Uri? = null
        private set

    override fun make(): ImageDecoder {
        makeCount++
        return object : ImageDecoder {
            override fun decode(context: Context, source: Uri): Bitmap {
                lastDecodeUri = source
                return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            }
        }
    }
}

/** Keeps `getMaxBitmapDimensions` deterministic across graphics modes. */
internal class SsivFixedMaxCanvas(
    bitmap: Bitmap,
    private val maxWidth: Int,
    private val maxHeight: Int
) : Canvas(bitmap) {

    override fun getMaximumBitmapWidth(): Int = maxWidth

    override fun getMaximumBitmapHeight(): Int = maxHeight
}
