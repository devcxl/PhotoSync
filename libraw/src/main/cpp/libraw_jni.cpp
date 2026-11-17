#include <jni.h>
#include <string>
#include <android/bitmap.h>
#include <android/log.h>
#include "libraw.h"
#include <cstring>
#include <ctime>
#include <memory>
#include <cstdint>
#include <inttypes.h>

#define LOG_TAG "LibRawJNI"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

// Maximum supported pixel count (≈ 800 MP) to prevent OOM on low-memory Android devices.
// This is not a LibRaw limit; it's a safety guard for the JNI allocation path.
constexpr size_t MAX_PIXEL_BYTES = 800UL * 1024 * 1024;

extern "C" JNIEXPORT jstring JNICALL
Java_cn_devcxl_photosync_wrapper_RawWrapper_version(JNIEnv* env, jobject /*thiz*/) {
    const char* v = libraw_version();
    ALOGI("LibRaw Version is %s", v ? v : "unknown");
    return env->NewStringUTF(v ? v : "unknown");
}

// RAII guard for JNI string release
struct PathGuard {
    JNIEnv* env;
    jstring jpath;
    const char* cpath;
    ~PathGuard() { if(cpath) env->ReleaseStringUTFChars(jpath, cpath); }
};

// RAII guard for LibRaw instance lifecycle
struct LibRawGuard {
    LibRaw* proc;
    bool valid;
    explicit LibRawGuard(LibRaw* p) : proc(p), valid(true) {}
    void invalidate() { valid = false; }
    ~LibRawGuard() { if(proc && valid) proc->recycle(); }
};

// RAII guard for dcraw_make_mem_image output
struct ImageGuard {
    libraw_processed_image_t* img;
    explicit ImageGuard(libraw_processed_image_t* i) : img(i) {}
    void release() { img = nullptr; }
    ~ImageGuard() { if(img) LibRaw::dcraw_clear_mem(img); }
};

// Safe libraw_strerror wrapper – returns a static string on null
static const char* safe_strerror(int ret) {
    const char* s = libraw_strerror(ret);
    return s ? s : "unknown error";
}

// 解码缩略图。JPEG 格式直接返回原始字节，BITMAP 格式返回 [w:4][h:4][R,G,B...]
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_devcxl_photosync_wrapper_RawWrapper_decodeThumbnail(JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    if(!jpath) return nullptr;

    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    if(!cpath) return nullptr;
    PathGuard pathGuard{env, jpath, cpath};

    LibRaw proc;
    LibRawGuard procGuard(&proc);

    int ret = proc.open_file(cpath);
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("open_file failed (%d): %s for %s", ret, safe_strerror(ret), cpath);
        return nullptr;
    }

    ret = proc.unpack_thumb();
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("unpack_thumb failed (%d): %s for %s", ret, safe_strerror(ret), cpath);
        return nullptr;
    }

    unsigned char* data = reinterpret_cast<unsigned char*>(proc.imgdata.thumbnail.thumb);
    unsigned int len = proc.imgdata.thumbnail.tlength;
    if(!data || len == 0) {
        ALOGE("Empty thumbnail data for %s", cpath);
        return nullptr;
    }

    if(proc.imgdata.thumbnail.tformat == LIBRAW_THUMBNAIL_JPEG) {
        jbyteArray out = env->NewByteArray(static_cast<jsize>(len));
        if(out) {
            env->SetByteArrayRegion(out, 0, static_cast<jsize>(len),
                                     reinterpret_cast<jbyte*>(data));
        }
        ALOGI("JPEG thumbnail %ux%u from %s",
              proc.imgdata.thumbnail.twidth, proc.imgdata.thumbnail.theight, cpath);
        return out;
    }

    // BITMAP format: convert raw pixel data to [w:4][h:4][R,G,B...]
    const uint32_t tw = proc.imgdata.thumbnail.twidth;
    const uint32_t th = proc.imgdata.thumbnail.theight;
    if(tw == 0 || th == 0 || tw > 8000 || th > 8000) {
        ALOGE("Invalid thumbnail dimensions %ux%u for %s", tw, th, cpath);
        return nullptr;
    }

    const size_t pixelCount = static_cast<size_t>(tw) * static_cast<size_t>(th);
    const size_t rgbBytes = pixelCount * 3UL;
    const size_t totalBytes = 8 + rgbBytes;

    jbyteArray out = env->NewByteArray(static_cast<jsize>(totalBytes));
    if(!out) return nullptr;

    std::unique_ptr<unsigned char[]> buffer;
    try { buffer = std::make_unique<unsigned char[]>(totalBytes); }
    catch(const std::bad_alloc&) { return nullptr; }

    buffer[0] = static_cast<unsigned char>((tw >>  0) & 0xFF);
    buffer[1] = static_cast<unsigned char>((tw >>  8) & 0xFF);
    buffer[2] = static_cast<unsigned char>((tw >> 16) & 0xFF);
    buffer[3] = static_cast<unsigned char>((tw >> 24) & 0xFF);
    buffer[4] = static_cast<unsigned char>((th >>  0) & 0xFF);
    buffer[5] = static_cast<unsigned char>((th >>  8) & 0xFF);
    buffer[6] = static_cast<unsigned char>((th >> 16) & 0xFF);
    buffer[7] = static_cast<unsigned char>((th >> 24) & 0xFF);

    unsigned char* dst = buffer.get() + 8;
    for(size_t i = 0; i < pixelCount; i++) {
        *dst++ = data[i * 3 + 0]; // R
        *dst++ = data[i * 3 + 1]; // G
        *dst++ = data[i * 3 + 2]; // B
    }

    env->SetByteArrayRegion(out, 0, static_cast<jsize>(totalBytes),
                             reinterpret_cast<const jbyte*>(buffer.get()));
    ALOGI("BITMAP thumbnail %ux%u (fmt=%d) from %s",
          tw, th, static_cast<int>(proc.imgdata.thumbnail.tformat), cpath);
    return out;
}

static int64_t monotonicMillis() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
}

/**
 * Configures LibRaw output parameters, unpacks, and runs dcraw_process() into an
 * 8-bit sRGB in-memory bitmap image. Caller owns the returned pointer (usually via ImageGuard).
 */
static libraw_processed_image_t* processRawToMemImage(LibRaw& proc, const char* cpath) {
    libraw_output_params_t& params = proc.imgdata.params;

    // Output format
    params.output_bps   = 8;   // 8-bit output
    params.output_color = 1;   // sRGB color space
    params.output_tiff  = 0;   // PPM/BITMAP output (in-memory)

    // Gamma – standard sRGB gamma curve
    params.gamm[0] = 1.0 / 2.4;
    params.gamm[1] = 12.92;

    // White balance
    params.use_camera_wb = 1;  // Use camera WB
    params.use_auto_wb   = 0;  // Disable auto WB

    // Demosaicing quality: PPG (2) is 2-3x faster than AHD (3) with crisp edges on high-MP sensors
    params.user_qual = 2;

    // Skip extra full-frame median filter pass and FBDD denoising for export speed
    params.med_passes    = 0;
    params.fbdd_noiserd  = 0;
    params.threshold     = 0.0;

    // Highlight handling (0=clip, 2=blend) – blend preserves highlight detail
    params.highlight = 2;

    // Aberration correction – disabled (1.0 = no correction)
    params.aber[0] = 1.0;
    params.aber[1] = 1.0;
    params.aber[2] = 1.0;

    // Brightness – no exposure shift; disable auto-bright for consistent output
    params.no_auto_bright = 1;
    params.bright         = 1.0;

    int ret = proc.open_file(cpath);
    if (ret != LIBRAW_SUCCESS) {
        ALOGE("open_file failed (%d): %s for %s", ret, safe_strerror(ret), cpath);
        return nullptr;
    }

    ret = proc.unpack();
    if (ret != LIBRAW_SUCCESS) {
        ALOGE("unpack failed (%d): %s for %s", ret, safe_strerror(ret), cpath);
        return nullptr;
    }

    ALOGI("Processing RAW: %dx%d ISO:%d %s %s",
          proc.imgdata.sizes.width, proc.imgdata.sizes.height,
          static_cast<int>(proc.imgdata.other.iso_speed),
          proc.imgdata.idata.make, proc.imgdata.idata.model);

    ret = proc.dcraw_process();
    if (ret != LIBRAW_SUCCESS) {
        ALOGE("dcraw_process failed (%d): %s for %s", ret, safe_strerror(ret), cpath);
        return nullptr;
    }

    int imgRet = LIBRAW_SUCCESS;
    libraw_processed_image_t* img = proc.dcraw_make_mem_image(&imgRet);
    if (!img || imgRet != LIBRAW_SUCCESS) {
        ALOGE("dcraw_make_mem_image failed (%d): %s for %s",
              imgRet, safe_strerror(imgRet), cpath);
        if (img) LibRaw::dcraw_clear_mem(img);
        return nullptr;
    }

    if (img->type != LIBRAW_IMAGE_BITMAP || img->bits != 8 || (img->colors != 1 && img->colors < 3)) {
        ALOGE("Unexpected processed image type=%d bits=%d colors=%d for %s",
              img->type, img->bits, img->colors, cpath);
        LibRaw::dcraw_clear_mem(img);
        return nullptr;
    }

    const uint32_t width  = img->width;
    const uint32_t height = img->height;
    if (width == 0 || height == 0 || width > 30000 || height > 30000) {
        ALOGE("Invalid image dimensions %ux%u for %s", width, height, cpath);
        LibRaw::dcraw_clear_mem(img);
        return nullptr;
    }

    const size_t pixelBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 3UL;
    if (pixelBytes / 3 != static_cast<size_t>(width) * static_cast<size_t>(height) ||
        pixelBytes > MAX_PIXEL_BYTES) {
        ALOGE("Image too large or overflow: %zu bytes (>%zu limit) for %s",
              pixelBytes, MAX_PIXEL_BYTES, cpath);
        LibRaw::dcraw_clear_mem(img);
        return nullptr;
    }

    const size_t expectedDataSize = static_cast<size_t>(width) *
                                    static_cast<size_t>(height) *
                                    (img->bits / 8) *
                                    static_cast<size_t>(img->colors);
    if (img->data_size < expectedDataSize) {
        ALOGE("data_size %u < expected %zu for %s", img->data_size, expectedDataSize, cpath);
        LibRaw::dcraw_clear_mem(img);
        return nullptr;
    }

    return img;
}

// 完整解码为8位RGB格式。返回字节数组布局：
// [0..3] width  (little-endian uint32)
// [4..7] height (little-endian uint32)
// [8..]  交错RGB字节，长度 = width*height*3
// 失败时返回null
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_devcxl_photosync_wrapper_RawWrapper_decodeToRGB(JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    if (!jpath) {
        ALOGE("Input path is null");
        return nullptr;
    }

    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    if (!cpath) {
        ALOGE("Failed to get UTF chars from path");
        return nullptr;
    }
    PathGuard pathGuard{env, jpath, cpath};

    const int64_t tStart = monotonicMillis();
    LibRaw proc;
    LibRawGuard procGuard(&proc);

    libraw_processed_image_t* img = processRawToMemImage(proc, cpath);
    if (!img) return nullptr;
    ImageGuard imgGuard(img);

    const uint32_t width  = img->width;
    const uint32_t height = img->height;
    const size_t pixelBytes = static_cast<size_t>(width) * static_cast<size_t>(height) * 3UL;
    const size_t totalBytes = 8 + pixelBytes;

    // Allocate output
    jbyteArray out = env->NewByteArray(static_cast<jsize>(totalBytes));
    if (!out) {
        ALOGE("Failed to allocate output array of %zu bytes for %s", totalBytes, cpath);
        return nullptr;
    }

    std::unique_ptr<unsigned char[]> buffer;
    try {
        buffer = std::make_unique<unsigned char[]>(totalBytes);
    } catch (const std::bad_alloc&) {
        ALOGE("Failed to allocate buffer of %zu bytes for %s", totalBytes, cpath);
        return nullptr;
    }

    // Pack width/height as little-endian
    buffer[0] = static_cast<unsigned char>((width >>  0) & 0xFF);
    buffer[1] = static_cast<unsigned char>((width >>  8) & 0xFF);
    buffer[2] = static_cast<unsigned char>((width >> 16) & 0xFF);
    buffer[3] = static_cast<unsigned char>((width >> 24) & 0xFF);
    buffer[4] = static_cast<unsigned char>((height >>  0) & 0xFF);
    buffer[5] = static_cast<unsigned char>((height >>  8) & 0xFF);
    buffer[6] = static_cast<unsigned char>((height >> 16) & 0xFF);
    buffer[7] = static_cast<unsigned char>((height >> 24) & 0xFF);

    if (img->colors == 3) {
        std::memcpy(buffer.get() + 8, img->data, pixelBytes);
    } else if (img->colors == 1) {
        const size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
        unsigned char* dst = buffer.get() + 8;
        for (size_t i = 0; i < pixelCount; ++i) {
            const unsigned char v = img->data[i];
            *dst++ = v;
            *dst++ = v;
            *dst++ = v;
        }
    } else {
        const unsigned bytesPerPixel = (img->bits / 8) * static_cast<unsigned>(img->colors);
        unsigned char* dst = buffer.get() + 8;
        for (uint32_t y = 0; y < height; ++y) {
            const unsigned char* srcRow = img->data + static_cast<size_t>(y) * width * bytesPerPixel;
            for (uint32_t x = 0; x < width; ++x) {
                const unsigned char* srcPx = srcRow + x * bytesPerPixel;
                *dst++ = srcPx[0];
                *dst++ = srcPx[1];
                *dst++ = srcPx[2];
            }
        }
    }

    env->SetByteArrayRegion(out, 0, static_cast<jsize>(totalBytes),
                             reinterpret_cast<const jbyte*>(buffer.get()));

    if (env->ExceptionCheck()) {
        ALOGE("JNI exception while setting byte array region for %s", cpath);
        return nullptr;
    }

    ALOGI("Successfully decoded RAW %ux%u (%" PRIu64 " bytes) in %" PRId64 " ms from %s",
          width, height, static_cast<uint64_t>(totalBytes), monotonicMillis() - tStart, cpath);

    return out;
}

// 直接解码为 Android ARGB_8888 Bitmap，避免中间 ByteArray/IntArray 拷贝和 Kotlin 逐像素循环
extern "C" JNIEXPORT jobject JNICALL
Java_cn_devcxl_photosync_wrapper_RawWrapper_decodeToBitmapNative(JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    if (!jpath) return nullptr;

    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    if (!cpath) return nullptr;
    PathGuard pathGuard{env, jpath, cpath};

    const int64_t tStart = monotonicMillis();
    LibRaw proc;
    LibRawGuard procGuard(&proc);

    libraw_processed_image_t* img = processRawToMemImage(proc, cpath);
    if (!img) return nullptr;
    ImageGuard imgGuard(img);

    const uint32_t width  = img->width;
    const uint32_t height = img->height;

    jclass bitmapCls = env->FindClass("android/graphics/Bitmap");
    jclass configCls = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmapCls || !configCls) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }

    jfieldID argbField = env->GetStaticFieldID(configCls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jmethodID createMethod = env->GetStaticMethodID(
        bitmapCls, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );
    if (!argbField || !createMethod) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return nullptr;
    }

    jobject argbConfig = env->GetStaticObjectField(configCls, argbField);
    jobject bitmap = env->CallStaticObjectMethod(
        bitmapCls, createMethod, static_cast<jint>(width), static_cast<jint>(height), argbConfig
    );
    env->DeleteLocalRef(argbConfig);
    env->DeleteLocalRef(configCls);
    env->DeleteLocalRef(bitmapCls);

    if (env->ExceptionCheck() || !bitmap) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        ALOGE("Bitmap.createBitmap(%ux%u) failed for %s", width, height, cpath);
        return nullptr;
    }

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        ALOGE("Unexpected AndroidBitmap format=%d for %s", info.format, cpath);
        return nullptr;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || !pixels) {
        ALOGE("AndroidBitmap_lockPixels failed for %s", cpath);
        return nullptr;
    }

    const unsigned srcChannels = static_cast<unsigned>(img->colors);
    const size_t srcStride = static_cast<size_t>(width) * srcChannels;
    for (uint32_t y = 0; y < height; ++y) {
        const unsigned char* srcRow = img->data + static_cast<size_t>(y) * srcStride;
        uint8_t* dstRow = static_cast<uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride;
        if (srcChannels == 1) {
            for (uint32_t x = 0; x < width; ++x) {
                const uint8_t v = srcRow[x];
                uint8_t* d = dstRow + x * 4;
                d[0] = v;
                d[1] = v;
                d[2] = v;
                d[3] = 0xFF;
            }
        } else {
            for (uint32_t x = 0; x < width; ++x) {
                const unsigned char* s = srcRow + x * srcChannels;
                uint8_t* d = dstRow + x * 4;
                d[0] = s[0]; // R
                d[1] = s[1]; // G
                d[2] = s[2]; // B
                d[3] = 0xFF; // A
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);

    ALOGI("Successfully decoded RAW Bitmap %ux%u in %" PRId64 " ms from %s",
          width, height, monotonicMillis() - tStart, cpath);
    return bitmap;
}
