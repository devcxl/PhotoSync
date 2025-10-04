package cn.devcxl.photosync.utils

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.photo.Photo

object OpenCVDenoiser {
    private const val TAG = "OpenCVDenoiser"

    /**
     * 使用OpenCV对图像进行降噪处理
     * @param inputBitmap 输入的原始Bitmap
     * @return 降噪后的Bitmap，如果处理失败则返回原始Bitmap
     */
    fun denoiseBitmap(inputBitmap: Bitmap): Bitmap {
        return try {
            // 将Bitmap转换为OpenCV Mat
            val inputMat = Mat()
            Utils.bitmapToMat(inputBitmap, inputMat)

            // 创建输出Mat
            val outputMat = Mat()

            // 应用Non-local Means降噪算法
            // 参数说明：
            // h = 3.0f: 过滤强度，值越大降噪效果越强但可能过度平滑
            // hColor = 3.0f: 颜色分量的过滤强度
            // templateWindowSize = 7: 模板窗口大小，应为奇数
            // searchWindowSize = 21: 搜索窗口大小，应为奇数
            Photo.fastNlMeansDenoisingColored(
                inputMat,
                outputMat,
                100.0f,  // h - 过滤强度
                100.0f,  // hColor - 颜色过滤强度
                7,     // templateWindowSize - 模板窗口大小
                21     // searchWindowSize - 搜索窗口大小
            )

            // 将处理后的Mat转换回Bitmap
            val resultBitmap = Bitmap.createBitmap(
                outputMat.cols(),
                outputMat.rows(),
                Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(outputMat, resultBitmap)

            // 释放Mat资源
            inputMat.release()
            outputMat.release()

            Log.d(TAG, "图像降噪处理完成")
            resultBitmap

        } catch (e: Exception) {
            Log.e(TAG, "降噪处理失败，返回原始图像", e)
            inputBitmap
        }
    }

    /**
     * 使用更温和的降噪参数
     * @param inputBitmap 输入的原始Bitmap
     * @return 轻度降噪后的Bitmap
     */
    fun gentleDenoiseBitmap(inputBitmap: Bitmap): Bitmap {
        return try {
            val inputMat = Mat()
            Utils.bitmapToMat(inputBitmap, inputMat)

            val outputMat = Mat()

            // 使用更温和的参数设置
            Photo.fastNlMeansDenoisingColored(
                inputMat,
                outputMat,
                1.5f,  // 较小的h值，降噪效果温和
                1.5f,  // 较小的hColor值
                7,     // 模板窗口大小
                21     // 搜索窗口大小
            )

            val resultBitmap = Bitmap.createBitmap(
                outputMat.cols(),
                outputMat.rows(),
                Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(outputMat, resultBitmap)

            inputMat.release()
            outputMat.release()

            Log.d(TAG, "温和降噪处理完成")
            resultBitmap

        } catch (e: Exception) {
            Log.e(TAG, "温和降噪处理失败，返回原始图像", e)
            inputBitmap
        }
    }
}
