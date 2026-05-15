package cn.devcxl.photosync.utils

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * @author devcxl
 */
object ImageDenoiseUtils {

    @JvmStatic
    fun psLikeDenoise(
        inputPath: String,
        outputPath: String,
        luminanceStrength: Int,
        colorStrength: Int
    ) {
        val img = Imgcodecs.imread(inputPath)
        if (img.empty()) {
            throw IllegalArgumentException("无法读取图像: $inputPath")
        }
        val result = denoiseLabImage(img, luminanceStrength, colorStrength, 7, 21)
        Imgcodecs.imwrite(outputPath, result)
        releaseMats(img, result)
    }

    @JvmStatic
    fun multiScaleDenoise(input: Bitmap, luminanceStrength: Int, colorStrength: Int): Bitmap {
        val img = Mat()
        Utils.bitmapToMat(input, img)
        if (img.empty()) {
            throw IllegalArgumentException("无法读取图像")
        }

        val imgSmall = Mat()
        val imgMedium = Mat()
        Imgproc.resize(img, imgSmall, Size(img.cols() / 2.0, img.rows() / 2.0))
        Imgproc.resize(img, imgMedium, Size(img.cols() / 1.5, img.rows() / 1.5))

        val denoisedSmall = denoiseSingleScale(imgSmall, luminanceStrength, colorStrength)
        val denoisedMedium = denoiseSingleScale(imgMedium, luminanceStrength, colorStrength)
        val denoisedOriginal = denoiseSingleScale(img, luminanceStrength / 3, colorStrength / 3)

        val denoisedSmallUp = Mat()
        val denoisedMediumUp = Mat()
        Imgproc.resize(denoisedSmall, denoisedSmallUp, img.size())
        Imgproc.resize(denoisedMedium, denoisedMediumUp, img.size())

        val result = Mat()
        Core.addWeighted(denoisedOriginal, 0.5, denoisedMediumUp, 0.3, 0.0, result)
        Core.addWeighted(result, 1.0, denoisedSmallUp, 0.2, 0.0, result)

        val bitmap = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, bitmap)

        releaseMats(
            img, imgSmall, imgMedium,
            denoisedSmall, denoisedMedium, denoisedOriginal,
            denoisedSmallUp, denoisedMediumUp, result
        )
        return bitmap
    }

    /**
     * 类似 PS 的明度降噪和色彩降噪（从 Bitmap 输入）。
     */
    @JvmStatic
    fun denoise(input: Bitmap, luminanceStrength: Int, colorStrength: Int): Bitmap {
        val img = Mat()
        Utils.bitmapToMat(input, img)
        if (img.empty()) {
            throw IllegalArgumentException("无法读取图像")
        }
        val result = denoiseLabImage(img, luminanceStrength, colorStrength, 7, 21)
        val bitmap = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, bitmap)
        releaseMats(img, result)
        return bitmap
    }

    /**
     * 类似 PS 的明度降噪和色彩降噪（从文件路径输入）。
     */
    @JvmStatic
    fun denoiseFile(inputPath: String, luminanceStrength: Int, colorStrength: Int): Bitmap {
        val img = Imgcodecs.imread(inputPath)
        if (img.empty()) {
            throw IllegalArgumentException("无法读取图像: $inputPath")
        }
        val result = denoiseLabImage(img, luminanceStrength, colorStrength, 7, 21)
        val bitmap = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, bitmap)
        releaseMats(img, result)
        return bitmap
    }

    private fun denoiseLabImage(
        img: Mat,
        luminanceStrength: Int,
        colorStrength: Int,
        templateWindowSize: Int,
        searchWindowSize: Int
    ): Mat {
        val lab = Mat()
        Imgproc.cvtColor(img, lab, Imgproc.COLOR_BGR2Lab)

        val channels = mutableListOf<Mat>()
        Core.split(lab, channels)

        val lDenoised = Mat()
        val aDenoised = Mat()
        val bDenoised = Mat()
        Photo.fastNlMeansDenoising(
            channels[0], lDenoised, luminanceStrength.toFloat(),
            templateWindowSize, searchWindowSize
        )
        Photo.fastNlMeansDenoising(
            channels[1], aDenoised, colorStrength.toFloat(),
            templateWindowSize, searchWindowSize
        )
        Photo.fastNlMeansDenoising(
            channels[2], bDenoised, colorStrength.toFloat(),
            templateWindowSize, searchWindowSize
        )

        val labDenoised = Mat()
        Core.merge(listOf(lDenoised, aDenoised, bDenoised), labDenoised)

        val result = Mat()
        Imgproc.cvtColor(labDenoised, result, Imgproc.COLOR_Lab2BGR)

        releaseMats(lab, labDenoised, lDenoised, aDenoised, bDenoised)
        channels.forEach { it.release() }

        return result
    }

    private fun denoiseSingleScale(img: Mat, lumStrength: Int, colorStrength: Int): Mat {
        return denoiseLabImage(img, lumStrength, colorStrength, 5, 11)
    }

    private fun releaseMats(vararg mats: Mat?) {
        for (mat in mats) {
            mat?.release()
        }
    }
}
