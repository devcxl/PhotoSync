package cn.devcxl.photosync.utils;

import android.graphics.Bitmap;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ImageDenoiseUtils {

    /**
     * 类似PS的明度降噪和色彩降噪
     * @param inputPath 输入图片路径
     * @param outputPath 输出图片路径
     * @param luminanceStrength 明度降噪强度 (0-30)
     * @param colorStrength 色彩降噪强度 (0-50)
     */
    public static void psLikeDenoise(String inputPath, String outputPath,
                                     int luminanceStrength, int colorStrength) {
        // 读取图像
        Mat img = Imgcodecs.imread(inputPath);
        if (img.empty()) {
            throw new IllegalArgumentException("无法读取图像: " + inputPath);
        }

        // 转换到LAB颜色空间
        Mat lab = new Mat();
        Imgproc.cvtColor(img, lab, Imgproc.COLOR_BGR2Lab);

        // 分离通道
        java.util.List<Mat> labChannels = new java.util.ArrayList<>();
        Core.split(lab, labChannels);

        // 亮度通道
        Mat l = labChannels.get(0);
        // A通道
        Mat a = labChannels.get(1);
        // B通道
        Mat b = labChannels.get(2);

        // 明度降噪 - 只对L通道进行降噪
        Mat lDenoised = new Mat();
        Photo.fastNlMeansDenoising(l, lDenoised, luminanceStrength, 7, 21);

        // 色彩降噪 - 对A和B通道进行降噪
        Mat aDenoised = new Mat();
        Mat bDenoised = new Mat();
        Photo.fastNlMeansDenoising(a, aDenoised, colorStrength, 7, 21);
        Photo.fastNlMeansDenoising(b, bDenoised, colorStrength, 7, 21);

        // 合并通道
        java.util.List<Mat> labDenoisedChannels = new java.util.ArrayList<>();
        labDenoisedChannels.add(lDenoised);
        labDenoisedChannels.add(aDenoised);
        labDenoisedChannels.add(bDenoised);

        Mat labDenoised = new Mat();
        Core.merge(labDenoisedChannels, labDenoised);

        // 转换回BGR
        Mat result = new Mat();
        Imgproc.cvtColor(labDenoised, result, Imgproc.COLOR_Lab2BGR);

        // 保存结果
        Imgcodecs.imwrite(outputPath, result);

        // 释放内存
        img.release();
        lab.release();
        l.release();
        a.release();
        b.release();
        lDenoised.release();
        aDenoised.release();
        bDenoised.release();
        labDenoised.release();
    }


    public static Bitmap multiScaleDenoise(Bitmap input,
                                         int luminanceStrength, int colorStrength) {
        // 读取图像
        Mat img = new Mat();
        Utils.bitmapToMat(input, img);
        if (img.empty()) {
            throw new IllegalArgumentException("无法读取图像");
        }

        // 创建不同尺度的图像
        Mat imgSmall = new Mat();
        Mat imgMedium = new Mat();

        // 下采样
        Imgproc.resize(img, imgSmall, new Size(img.cols()/2, img.rows()/2));
        Imgproc.resize(img, imgMedium, new Size(img.cols()/1.5, img.rows()/1.5));

        // 在不同尺度上降噪
        Mat denoisedSmall = denoiseSingleScale(imgSmall, luminanceStrength, colorStrength);
        Mat denoisedMedium = denoiseSingleScale(imgMedium, luminanceStrength, colorStrength);
        Mat denoisedOriginal = denoiseSingleScale(img, luminanceStrength/3, colorStrength/3);

        // 上采样并融合
        Mat denoisedSmallUp = new Mat();
        Mat denoisedMediumUp = new Mat();

        Imgproc.resize(denoisedSmall, denoisedSmallUp, img.size());
        Imgproc.resize(denoisedMedium, denoisedMediumUp, img.size());

        // 权重融合
        Mat result = new Mat();
        List<Mat> sources = Arrays.asList(denoisedOriginal, denoisedMediumUp, denoisedSmallUp);
        List<Double> weights = Arrays.asList(0.5, 0.3, 0.2); // 原图权重最高以保留细节

        Core.addWeighted(sources.get(0), weights.get(0), sources.get(1), weights.get(1), 0, result);
        Core.addWeighted(result, 1.0, sources.get(2), weights.get(2), 0, result);

        Bitmap bitmap = Bitmap.createBitmap(
                result.cols(),
                result.rows(),
                Bitmap.Config.ARGB_8888
        );
        Utils.matToBitmap(result, bitmap);

        releaseMats(img, imgSmall, imgMedium, denoisedSmall, denoisedMedium,
                denoisedOriginal, denoisedSmallUp, denoisedMediumUp, result);
        return bitmap;
    }

    private static Mat denoiseSingleScale(Mat img, int lumStrength, int colorStrength) {
        Mat lab = new Mat();
        Imgproc.cvtColor(img, lab, Imgproc.COLOR_BGR2Lab);

        List<Mat> channels = new ArrayList<>();
        Core.split(lab, channels);

        Mat lDenoised = new Mat();
        Mat aDenoised = new Mat();
        Mat bDenoised = new Mat();

        Photo.fastNlMeansDenoising(channels.get(0), lDenoised, lumStrength, 5, 11);
        Photo.fastNlMeansDenoising(channels.get(1), aDenoised, colorStrength, 7, 21);
        Photo.fastNlMeansDenoising(channels.get(2), bDenoised, colorStrength, 7, 21);

        List<Mat> denoisedChannels = Arrays.asList(lDenoised, aDenoised, bDenoised);
        Mat labDenoised = new Mat();
        Core.merge(denoisedChannels, labDenoised);

        Mat result = new Mat();
        Imgproc.cvtColor(labDenoised, result, Imgproc.COLOR_Lab2BGR);

        return result;
    }
    /**
     * 类似PS的明度降噪和色彩降噪
     * @param input 输入图片
     * @param luminanceStrength 明度降噪强度 (0-30)
     * @param colorStrength 色彩降噪强度 (0-50)
     */
    public static Bitmap  denoise(Bitmap input, int luminanceStrength, int colorStrength) {
        // 读取图像
        Mat img = new Mat();
        Utils.bitmapToMat(input, img);
        if (img.empty()) {
            throw new IllegalArgumentException("无法读取图像");
        }

        // 转换到LAB颜色空间
        Mat lab = new Mat();
        Imgproc.cvtColor(img, lab, Imgproc.COLOR_BGR2Lab);

        // 分离通道
        java.util.List<Mat> labChannels = new java.util.ArrayList<>();
        Core.split(lab, labChannels);

        // 亮度通道
        Mat l = labChannels.get(0);
        // A通道
        Mat a = labChannels.get(1);
        // B通道
        Mat b = labChannels.get(2);

        // 明度降噪 - 只对L通道进行降噪
        Mat lDenoised = new Mat();
        Photo.fastNlMeansDenoising(l, lDenoised, luminanceStrength, 7, 21);

        // 色彩降噪 - 对A和B通道进行降噪
        Mat aDenoised = new Mat();
        Mat bDenoised = new Mat();
        Photo.fastNlMeansDenoising(a, aDenoised, colorStrength, 7, 21);
        Photo.fastNlMeansDenoising(b, bDenoised, colorStrength, 7, 21);

        // 合并通道
        java.util.List<Mat> labDenoisedChannels = new java.util.ArrayList<>();
        labDenoisedChannels.add(lDenoised);
        labDenoisedChannels.add(aDenoised);
        labDenoisedChannels.add(bDenoised);

        Mat labDenoised = new Mat();
        Core.merge(labDenoisedChannels, labDenoised);

        // 转换回BGR
        Mat result = new Mat();
        Imgproc.cvtColor(labDenoised, result, Imgproc.COLOR_Lab2BGR);


        Bitmap bitmap = Bitmap.createBitmap(
                result.cols(),
                result.rows(),
                Bitmap.Config.ARGB_8888
        );
        Utils.matToBitmap(result, bitmap);

        // 释放内存
        img.release();
        lab.release();
        l.release();
        a.release();
        b.release();
        lDenoised.release();
        aDenoised.release();
        bDenoised.release();
        labDenoised.release();

        return bitmap;
    }



    /**
     * 类似PS的明度降噪和色彩降噪
     * @param inputPath 输入图片
     * @param luminanceStrength 明度降噪强度 (0-30)
     * @param colorStrength 色彩降噪强度 (0-50)
     */
    public static Bitmap denoiseFile(String inputPath, int luminanceStrength, int colorStrength) {
        // 读取图像
        Mat img = Imgcodecs.imread(inputPath);
        if (img.empty()) {
            throw new IllegalArgumentException("无法读取图像: " + inputPath);
        }


        // 转换到LAB颜色空间
        Mat lab = new Mat();
        Imgproc.cvtColor(img, lab, Imgproc.COLOR_BGR2Lab);

        // 分离通道
        java.util.List<Mat> labChannels = new java.util.ArrayList<>();
        Core.split(lab, labChannels);

        // 亮度通道
        Mat l = labChannels.get(0);
        // A通道
        Mat a = labChannels.get(1);
        // B通道
        Mat b = labChannels.get(2);

        // 明度降噪 - 只对L通道进行降噪
        Mat lDenoised = new Mat();
        Photo.fastNlMeansDenoising(l, lDenoised, luminanceStrength, 7, 21);

        // 色彩降噪 - 对A和B通道进行降噪
        Mat aDenoised = new Mat();
        Mat bDenoised = new Mat();
        Photo.fastNlMeansDenoising(a, aDenoised, colorStrength, 7, 21);
        Photo.fastNlMeansDenoising(b, bDenoised, colorStrength, 7, 21);

        // 合并通道
        java.util.List<Mat> labDenoisedChannels = new java.util.ArrayList<>();
        labDenoisedChannels.add(lDenoised);
        labDenoisedChannels.add(aDenoised);
        labDenoisedChannels.add(bDenoised);

        Mat labDenoised = new Mat();
        Core.merge(labDenoisedChannels, labDenoised);

        // 转换回BGR
        Mat result = new Mat();
        Imgproc.cvtColor(labDenoised, result, Imgproc.COLOR_Lab2BGR);


        Bitmap bitmap = Bitmap.createBitmap(
                result.cols(),
                result.rows(),
                Bitmap.Config.ARGB_8888
        );
        Utils.matToBitmap(result, bitmap);

        // 释放内存
        img.release();
        lab.release();
        l.release();
        a.release();
        b.release();
        lDenoised.release();
        aDenoised.release();
        bDenoised.release();
        labDenoised.release();

        return bitmap;
    }



    private static void releaseMats(Mat... mats) {
        for (Mat mat : mats) {
            if (mat != null) {
                mat.release();
            }
        }
    }
}