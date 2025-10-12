#include <jni.h>
#include <string>
#include <android/log.h>
#include "libraw.h"
#include <cstring>
#include <memory>

#define LOG_TAG "LibRawJNI"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_cn_devcxl_photosync_wrapper_RawWrapper_version(JNIEnv* env, jobject /*thiz*/) {
    const char* v = libraw_version();
    ALOGI("LibRaw Version is  %s", v);
    return env->NewStringUTF(v ? v : "unknown");
}

// 解码缩略图，返回 JPEG
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_devcxl_photosync_wrapper_RawWrapper_decodeThumbnail(JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    if(!jpath) return nullptr;
    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    if(!cpath) return nullptr;
    LibRaw proc;
    int ret = proc.open_file(cpath);
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("open_file for thumbnail failed (%d) %s", ret, cpath);
        env->ReleaseStringUTFChars(jpath, cpath);
        return nullptr;
    }
    ret = proc.unpack_thumb();
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("unpack_thumb failed (%d) %s", ret, cpath);
        proc.recycle();
        env->ReleaseStringUTFChars(jpath, cpath);
        return nullptr;
    }
    if(proc.imgdata.thumbnail.tformat != LIBRAW_THUMBNAIL_JPEG) {
        ALOGE("Thumbnail not JPEG (format=%d) %s", proc.imgdata.thumbnail.tformat, cpath);
        proc.recycle();
        env->ReleaseStringUTFChars(jpath, cpath);
        return nullptr;
    }
    // libraw 定义 thumb 为 char*，这里按字节使用需转成 unsigned char* 以避免编译器类型不匹配
    unsigned char* data = reinterpret_cast<unsigned char*>(proc.imgdata.thumbnail.thumb);
    unsigned int len = proc.imgdata.thumbnail.tlength;
    if(!data || len == 0) {
        ALOGE("Empty thumbnail data %s", cpath);
        proc.recycle();
        env->ReleaseStringUTFChars(jpath, cpath);
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(len));
    if(out) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(len), reinterpret_cast<jbyte*>(data));
    }
    proc.recycle();
    env->ReleaseStringUTFChars(jpath, cpath);
    return out;
}

// 完整解码为8位RGB格式。返回字节数组布局：
// [0..3] width (little-endian int32)
// [4..7] height (little-endian int32)
// [8..] 交错RGB字节，长度 = width*height*3
// 失败时返回null
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_devcxl_photosync_wrapper_RawWrapper_decodeToRGB(JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    // 输入验证
    if(!jpath) {
        ALOGE("Input path is null");
        return nullptr;
    }

    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    if(!cpath) {
        ALOGE("Failed to get UTF chars from path");
        return nullptr;
    }

    // RAII包装器用于路径清理
    struct PathGuard {
        JNIEnv* env;
        jstring jpath;
        const char* cpath;
        ~PathGuard() { if(cpath) env->ReleaseStringUTFChars(jpath, cpath); }
    } pathGuard{env, jpath, cpath};

    LibRaw proc;

    // 增强的错误处理与描述性消息
    auto handleError = [&](int ret, const char* operation) -> jbyteArray {
        ALOGE("%s failed (%d): %s for file %s", operation, ret, libraw_strerror(ret), cpath);
        proc.recycle();
        return nullptr;
    };

    // 打开文件，更好的错误报告
    int ret = proc.open_file(cpath);
    if(ret != LIBRAW_SUCCESS) {
        return handleError(ret, "open_file");
    }

    // 解包，带错误处理
    ret = proc.unpack();
    if(ret != LIBRAW_SUCCESS) {
        return handleError(ret, "unpack");
    }

    // 基于LibRaw最佳实践的优化参数设置
    libraw_output_params_t& params = proc.imgdata.params;

    // 高质量去马赛克算法（AHD插值 - 质量与速度的更好平衡）
    params.user_qual = 3; // AHD interpolation - better balance of quality vs speed than DCB

    // 输出格式设置
    params.output_bps = 8;      // 8位输出
    params.output_color = 1;    // sRGB色彩空间
    params.gamm[0] = 1.0/2.4;   // 标准sRGB伽马
    params.gamm[1] = 12.92;     // sRGB toe slope

    // 质量改进
    params.med_passes = 1;      // 中值滤波器用于降噪
    params.use_auto_wb = 0;     // 禁用自动白平衡以保持一致性
    params.use_camera_wb = 1;   // 使用相机白平衡

    // 色差校正
    params.aber[0] = 1.0;       // 红色
    params.aber[1] = 1.0;       // 绿色
    params.aber[2] = 1.0;       // 蓝色

    // 降噪设置
    params.threshold = 0.0;     // 无噪声阈值
    params.fbdd_noiserd = 0;    // 禁用FBDD降噪以提高速度

    // 处理优化
    params.no_auto_bright = 1;  // 禁用自动亮度以获得一致结果
    params.output_tiff = 0;     // 确保PPM输出用于内存操作

    ALOGI("Processing RAW image: %dx%d, ISO: %d, Camera: %s %s",
          proc.imgdata.sizes.width, proc.imgdata.sizes.height,
          (int)proc.imgdata.other.iso_speed,
          proc.imgdata.idata.make, proc.imgdata.idata.model);

    // 处理RAW数据（内部会执行 raw2image_ex 和预处理步骤）
    ret = proc.dcraw_process();
    if(ret != LIBRAW_SUCCESS) {
        return handleError(ret, "dcraw_process");
    }

    // 创建内存图像，带错误处理
    libraw_processed_image_t* img = proc.dcraw_make_mem_image(&ret);
    if(!img || ret != LIBRAW_SUCCESS) {
        ALOGE("dcraw_make_mem_image failed (%d): %s for file %s",
              ret, libraw_strerror(ret), cpath);
        if(img) libraw_dcraw_clear_mem(img);
        proc.recycle();
        return nullptr;
    }

    // RAII包装器用于图像清理
    struct ImageGuard {
        libraw_processed_image_t* img;
        ~ImageGuard() { if(img) libraw_dcraw_clear_mem(img); }
    } imageGuard{img};

    // 验证图像格式
    if(img->bits != 8 || img->colors != 3) {
        ALOGE("Unexpected image format: bits=%d colors=%d (expected 8-bit RGB) for file %s",
              img->bits, img->colors, cpath);
        proc.recycle();
        return nullptr;
    }

    // 验证图像尺寸并防止溢出
    const uint32_t width = img->width;
    const uint32_t height = img->height;

    if(width == 0 || height == 0 || width > 65535 || height > 65535) {
        ALOGE("Invalid image dimensions: %dx%d for file %s", width, height, cpath);
        proc.recycle();
        return nullptr;
    }

    // 计算大小，带溢出保护
    const size_t pixelBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
    const size_t totalBytes = 8 + pixelBytes;

    // 检查溢出和合理的大小限制（例如，最大500MP）
    if(pixelBytes / 3 != static_cast<size_t>(width) * static_cast<size_t>(height) ||
       totalBytes < pixelBytes ||
       pixelBytes > 500 * 1024 * 1024 * 3) {
        ALOGE("Image too large or overflow detected: %zu bytes for file %s", totalBytes, cpath);
        proc.recycle();
        return nullptr;
    }

    if(!img->data) {
        ALOGE("Image data is null for file %s", cpath);
        proc.recycle();
        return nullptr;
    }

    // 分配输出数组
    jbyteArray out = env->NewByteArray(static_cast<jsize>(totalBytes));
    if(!out) {
        ALOGE("Failed to allocate output array of size %zu bytes", totalBytes);
        proc.recycle();
        return nullptr;
    }

    // 准备缓冲区，优化分配
    std::unique_ptr<unsigned char[]> buffer;
    try {
        buffer = std::make_unique<unsigned char[]>(totalBytes);
    } catch(const std::bad_alloc&) {
        ALOGE("Failed to allocate buffer of size %zu bytes", totalBytes);
        proc.recycle();
        return nullptr;
    }

    // 以小端格式打包尺寸（显式写入LE以避免主机端序差异）
    buffer[0] = static_cast<unsigned char>((width >> 0) & 0xFF);
    buffer[1] = static_cast<unsigned char>((width >> 8) & 0xFF);
    buffer[2] = static_cast<unsigned char>((width >> 16) & 0xFF);
    buffer[3] = static_cast<unsigned char>((width >> 24) & 0xFF);
    buffer[4] = static_cast<unsigned char>((height >> 0) & 0xFF);
    buffer[5] = static_cast<unsigned char>((height >> 8) & 0xFF);
    buffer[6] = static_cast<unsigned char>((height >> 16) & 0xFF);
    buffer[7] = static_cast<unsigned char>((height >> 24) & 0xFF);

    // 高效复制RGB数据
    std::memcpy(buffer.get() + 8, img->data, pixelBytes);

    // 设置数组区域，带错误检查
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(totalBytes),
                           reinterpret_cast<const jbyte*>(buffer.get()));

    // 检查JNI异常
    if(env->ExceptionCheck()) {
        ALOGE("JNI exception occurred while setting byte array region");
        proc.recycle();
        return nullptr;
    }

    // 清理由RAII守卫处理
    proc.recycle();

    ALOGI("Successfully decoded RAW image %dx%d (%zu bytes) from %s",
          width, height, totalBytes, cpath);

    return out;
}
