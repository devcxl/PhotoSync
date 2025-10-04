package cn.devcxl.photosync.utils;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;

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
}