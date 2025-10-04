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
    return env->NewStringUTF(v ? v : "unknown");
}


// Decode embedded thumbnail (JPEG) and return as byte[]; returns null on failure.
// Only handles JPEG thumbnails; if not JPEG returns null.
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
    // Force 8-bit output
    proc.imgdata.params.output_bps = 8;
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
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(total), reinterpret_cast<jbyte*>(buffer.get()));

    libraw_dcraw_clear_mem(img);
    proc.recycle();
    env->ReleaseStringUTFChars(jpath, cpath);
    return out;
}

// Decode thumbnail from in-memory bytes using LibRaw::open_buffer
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_devcxl_photosync_wrapper_RawWrapper_decodeThumbnailFromBytes(JNIEnv* env, jobject /*thiz*/, jbyteArray jbytes) {
    if(!jbytes) return nullptr;
    jsize len = env->GetArrayLength(jbytes);
    if(len <= 0) return nullptr;
    jboolean isCopy = JNI_FALSE;
    jbyte* dataPtr = env->GetByteArrayElements(jbytes, &isCopy);
    if(!dataPtr) return nullptr;

    LibRaw proc;
    int ret = proc.open_buffer(reinterpret_cast<void*>(dataPtr), static_cast<size_t>(len));
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("open_buffer for thumb failed (%d)", ret);
        env->ReleaseByteArrayElements(jbytes, dataPtr, JNI_ABORT);
        return nullptr;
    }
    ret = proc.unpack_thumb();
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("unpack_thumb (buffer) failed (%d)", ret);
        proc.recycle();
        env->ReleaseByteArrayElements(jbytes, dataPtr, JNI_ABORT);
        return nullptr;
    }
    if(proc.imgdata.thumbnail.tformat != LIBRAW_THUMBNAIL_JPEG) {
        ALOGE("Thumbnail (buffer) not JPEG, format=%d", proc.imgdata.thumbnail.tformat);
        proc.recycle();
        env->ReleaseByteArrayElements(jbytes, dataPtr, JNI_ABORT);
        return nullptr;
    }
    unsigned char* tdata = reinterpret_cast<unsigned char*>(proc.imgdata.thumbnail.thumb);
    unsigned int tlen = proc.imgdata.thumbnail.tlength;
    if(!tdata || tlen == 0) {
        proc.recycle();
        env->ReleaseByteArrayElements(jbytes, dataPtr, JNI_ABORT);
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(tlen));
    if(out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(tlen), reinterpret_cast<jbyte*>(tdata));

    proc.recycle();
    env->ReleaseByteArrayElements(jbytes, dataPtr, JNI_ABORT);
    return out;
}

// Decode full image to 8-bit RGB from in-memory bytes using LibRaw::open_buffer
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_devcxl_photosync_wrapper_RawWrapper_decodeToRGBFromBytes(JNIEnv* env, jobject /*thiz*/, jbyteArray jbytes) {
    if(!jbytes) return nullptr;
    jsize len = env->GetArrayLength(jbytes);
    if(len <= 0) return nullptr;
    jboolean isCopy = JNI_FALSE;
    jbyte* dataPtr = env->GetByteArrayElements(jbytes, &isCopy);
    if(!dataPtr) return nullptr;

    LibRaw proc;
    int ret = proc.open_buffer(reinterpret_cast<void*>(dataPtr), static_cast<size_t>(len));
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("open_buffer failed (%d)", ret);
        env->ReleaseByteArrayElements(jbytes, dataPtr, JNI_ABORT);
        return nullptr;
    }
    ret = proc.unpack();
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("unpack (buffer) failed (%d)", ret);
        proc.recycle();
        env->ReleaseByteArrayElements(jbytes, dataPtr, JNI_ABORT);
        return nullptr;
    }
    proc.imgdata.params.output_bps = 8;
    ret = proc.dcraw_process();
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("dcraw_process (buffer) failed (%d)", ret);
        proc.recycle();
        env->ReleaseByteArrayElements(jbytes, dataPtr, JNI_ABORT);
        return nullptr;
    }
    libraw_processed_image_t* img = proc.dcraw_make_mem_image(&ret);
    if(!img || ret != LIBRAW_SUCCESS) {
        ALOGE("dcraw_make_mem_image (buffer) failed (%d)", ret);
        if(img) libraw_dcraw_clear_mem(img);
        proc.recycle();
        env->ReleaseByteArrayElements(jbytes, dataPtr, JNI_ABORT);
        return nullptr;
    }
    if(img->bits != 8 || img->colors != 3) {
        ALOGE("Unexpected (buffer) bits=%d colors=%d", img->bits, img->colors);
        libraw_dcraw_clear_mem(img);
        proc.recycle();
        env->ReleaseByteArrayElements(jbytes, dataPtr, JNI_ABORT);
        return nullptr;
    }
    uint32_t width = img->width;
    uint32_t height = img->height;
    size_t pixelBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
    size_t total = 8 + pixelBytes;
    if(pixelBytes == 0 || !img->data) {
        libraw_dcraw_clear_mem(img);
        proc.recycle();
        env->ReleaseByteArrayElements(jbytes, dataPtr, JNI_ABORT);
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(total));
    if(!out) {
        libraw_dcraw_clear_mem(img);
        proc.recycle();
        env->ReleaseByteArrayElements(jbytes, dataPtr, JNI_ABORT);
        return nullptr;
    }
    std::unique_ptr<unsigned char[]> buffer(new unsigned char[total]);
    buffer[0] = (unsigned char)(width & 0xFF);
    buffer[1] = (unsigned char)((width >> 8) & 0xFF);
    buffer[2] = (unsigned char)((width >> 16) & 0xFF);
    buffer[3] = (unsigned char)((width >> 24) & 0xFF);
    buffer[4] = (unsigned char)(height & 0xFF);
    buffer[5] = (unsigned char)((height >> 8) & 0xFF);
    buffer[6] = (unsigned char)((height >> 16) & 0xFF);
    buffer[7] = (unsigned char)((height >> 24) & 0xFF);
    std::memcpy(buffer.get() + 8, img->data, pixelBytes);
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(total), reinterpret_cast<jbyte*>(buffer.get()));

    libraw_dcraw_clear_mem(img);
    proc.recycle();
    env->ReleaseByteArrayElements(jbytes, dataPtr, JNI_ABORT);
    return out;
}
