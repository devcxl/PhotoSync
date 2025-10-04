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

    ALOGI("============= thumb (%d) ", proc.thumbOK());

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

// Full decode to 8-bit RGB. Returns a byte[] with layout:
// [0..3] width (little-endian int32)
// [4..7] height (little-endian int32)
// [8..] interleaved RGB bytes length = width*height*3
// Returns null on failure.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_devcxl_photosync_wrapper_RawWrapper_decodeToRGB(JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    if(!jpath) return nullptr;
    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    if(!cpath) return nullptr;

    LibRaw proc;
    int ret = proc.open_file(cpath);
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("open_file failed (%d) %s", ret, cpath);
        env->ReleaseStringUTFChars(jpath, cpath);
        return nullptr;
    }
    ret = proc.unpack();
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("unpack failed (%d) %s", ret, cpath);
        proc.recycle();
        env->ReleaseStringUTFChars(jpath, cpath);
        return nullptr;
    }

    // 设置去马赛克算法
    // 0 线性插值（最快，质量最低）
    // 1 VNG 插值（良好的平衡）
    // 3 AHD插值（高质量）
    // 4 DCB 插值（质量最高，速度最慢）
    proc.imgdata.params.user_qual = 4;

    // output_bps 是用于设置输出图像的位深度的参数
    // 这个参数控制处理后图像的每个样本(每个颜色通道)使用多少位来表示:
    // 8 位(默认值):标准的 8 位输出
    // 16 位:高精度 16 位输出
    proc.imgdata.params.output_bps = 8;

    // 设置输出色彩空间
    // 要将输出色彩空间设置为 Adobe RGB,您需要设置 imgdata.params.output_color 参数为 1。 API-datastruct.html:653-656
    // 可用的色彩空间选项包括:
    // 0: raw (相机原始色彩空间)
    // 1: sRGB
    // 2: Adobe RGB
    // 3: Wide Gamut RGB
    // 4: ProPhoto RGB
    // 5: XYZ
    // 6: ACES
    // 7: DCI-P3
    // 8: Rec. 2020
    proc.imgdata.params.output_color = 1;

    // 降噪
    proc.imgdata.params.med_passes = 1;

    proc.imgdata.params.aber[0] = 1.0;      // Chromatic aberration correction R
    proc.imgdata.params.aber[1] = 1.0;      // Chromatic aberration correction G
    proc.imgdata.params.aber[2] = 1.0;      // Chromatic aberration correction B
    proc.imgdata.params.gamm[0] = 1.0/2.4;  // Gamma correction
    proc.imgdata.params.gamm[1] = 12.92;    // Gamma toe slope

    ret = proc.dcraw_process();
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("dcraw_process failed (%d) %s", ret, cpath);
        proc.recycle();
        env->ReleaseStringUTFChars(jpath, cpath);
        return nullptr;
    }
    libraw_processed_image_t* img = proc.dcraw_make_mem_image(&ret);
    if(!img || ret != LIBRAW_SUCCESS) {
        ALOGE("dcraw_make_mem_image failed (%d) %s", ret, cpath);
        if(img) libraw_dcraw_clear_mem(img);
        proc.recycle();
        env->ReleaseStringUTFChars(jpath, cpath);
        return nullptr;
    }
    if(img->bits != 8 || img->colors != 3) {
        ALOGE("Unexpected image format bits=%d colors=%d %s", img->bits, img->colors, cpath);
        libraw_dcraw_clear_mem(img);
        proc.recycle();
        env->ReleaseStringUTFChars(jpath, cpath);
        return nullptr;
    }
    uint32_t width = img->width;
    uint32_t height = img->height;
    size_t pixelBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
    size_t total = 8 + pixelBytes;
    if(pixelBytes == 0 || !img->data) {
        ALOGE("Invalid image data %s", cpath);
        libraw_dcraw_clear_mem(img);
        proc.recycle();
        env->ReleaseStringUTFChars(jpath, cpath);
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(total));
    if(!out) {
        ALOGE("Failed to allocate output array size=%zu", total);
        libraw_dcraw_clear_mem(img);
        proc.recycle();
        env->ReleaseStringUTFChars(jpath, cpath);
        return nullptr;
    }
    // Prepare buffer
    std::unique_ptr<unsigned char[]> buffer(new unsigned char[total]);
    // width little-endian
    buffer[0] = (unsigned char)(width & 0xFF);
    buffer[1] = (unsigned char)((width >> 8) & 0xFF);
    buffer[2] = (unsigned char)((width >> 16) & 0xFF);
    buffer[3] = (unsigned char)((width >> 24) & 0xFF);
    buffer[4] = (unsigned char)(height & 0xFF);
    buffer[5] = (unsigned char)((height >> 8) & 0xFF);
    buffer[6] = (unsigned char)((height >> 16) & 0xFF);
    buffer[7] = (unsigned char)((height >> 24) & 0xFF);
    // Copy RGB payload
    std::memcpy(buffer.get() + 8, img->data, pixelBytes);

    // LibRAW decoding complete - no denoising applied here
    // Denoising will be handled by OpenCV in the Java/Kotlin layer

    env->SetByteArrayRegion(out, 0, static_cast<jsize>(total), reinterpret_cast<jbyte*>(buffer.get()));

    libraw_dcraw_clear_mem(img);
    proc.recycle();
    env->ReleaseStringUTFChars(jpath, cpath);
    return out;
}


