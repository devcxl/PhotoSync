#include <jni.h>
#include <string>
#include <android/log.h>
#include "libraw.h"
#include <cstring>
#include <memory>
#include <cstdint>

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

// 完整解码为8位RGB格式。返回字节数组布局：
// [0..3] width  (little-endian uint32)
// [4..7] height (little-endian uint32)
// [8..]  交错RGB字节，长度 = width*height*3
// 失败时返回null
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cn_devcxl_photosync_wrapper_RawWrapper_decodeToRGB(JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    if(!jpath) {
        ALOGE("Input path is null");
        return nullptr;
    }

    const char* cpath = env->GetStringUTFChars(jpath, nullptr);
    if(!cpath) {
        ALOGE("Failed to get UTF chars from path");
        return nullptr;
    }
    PathGuard pathGuard{env, jpath, cpath};

    LibRaw proc;
    LibRawGuard procGuard(&proc);

    // ── Configure processing params BEFORE open_file ─────────────────────────
    // Per official LibRaw samples, params should be set before open_file().
    // See: https://context7.com/libraw/libraw/llms.txt
    libraw_output_params_t& params = proc.imgdata.params;

    // Output format
    params.output_bps   = 8;   // 8-bit output
    params.output_color = 1;   // sRGB色彩空间
    params.output_tiff  = 0;   // PPM output (in-memory)

    // Gamma – standard sRGB gamma curve
    params.gamm[0] = 1.0 / 2.4;
    params.gamm[1] = 12.92;

    // White balance
    params.use_camera_wb = 1;  // Use camera WB
    params.use_auto_wb   = 0;  // Disable auto WB

    // Demosaicing quality: AHD (3) – good balance of quality vs speed
    params.user_qual = 3;

    // Noise & detail
    params.med_passes    = 1;   // Median filter pass for noise reduction
    params.fbdd_noiserd  = 0;   // Disable FBDD for speed
    params.threshold     = 0.0;  // No wavelet denoising threshold

    // Highlight handling (0=clip, 2=blend) – blend preserves highlight detail
    params.highlight = 2;

    // Aberration correction – disabled (1.0 = no correction)
    params.aber[0] = 1.0;
    params.aber[1] = 1.0;
    params.aber[2] = 1.0;

    // Brightness – no exposure shift; disable auto-bright for consistent output
    params.no_auto_bright = 1;
    params.bright        = 1.0;

    // ── Open and unpack ───────────────────────────────────────────────────────
    int ret = proc.open_file(cpath);
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("open_file failed (%d): %s for %s", ret, safe_strerror(ret), cpath);
        return nullptr;
    }

    ret = proc.unpack();
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("unpack failed (%d): %s for %s", ret, safe_strerror(ret), cpath);
        return nullptr;
    }

    ALOGI("Processing RAW: %dx%d ISO:%d %s %s",
          proc.imgdata.sizes.width, proc.imgdata.sizes.height,
          static_cast<int>(proc.imgdata.other.iso_speed),
          proc.imgdata.idata.make, proc.imgdata.idata.model);

    // ── Process RAW → RGB ─────────────────────────────────────────────────────
    ret = proc.dcraw_process();
    if(ret != LIBRAW_SUCCESS) {
        ALOGE("dcraw_process failed (%d): %s for %s", ret, safe_strerror(ret), cpath);
        return nullptr;
    }

    // ── Get processed image from memory ───────────────────────────────────────
    int imgRet = LIBRAW_SUCCESS;
    libraw_processed_image_t* img = proc.dcraw_make_mem_image(&imgRet);
    if(!img) {
        ALOGE("dcraw_make_mem_image returned null for %s", cpath);
        return nullptr;
    }
    ImageGuard imgGuard(img);

    if(imgRet != LIBRAW_SUCCESS) {
        ALOGE("dcraw_make_mem_image failed (%d): %s for %s",
              imgRet, safe_strerror(imgRet), cpath);
        return nullptr;
    }

    // Validate image type – should always be BITMAP with our params
    if(img->type != LIBRAW_IMAGE_BITMAP) {
        ALOGE("Unexpected image type %d (expected %d=LIBRAW_IMAGE_BITMAP) for %s",
              img->type, LIBRAW_IMAGE_BITMAP, cpath);
        return nullptr;
    }

    // Validate bit depth and color count
    if(img->bits != 8) {
        ALOGE("Unsupported bit depth %d (expected 8) for %s", img->bits, cpath);
        return nullptr;
    }

    if(img->colors != 3) {
        // CMYK or other multi-channel – try to warn and handle gracefully
        ALOGI("Non-RGB colors=%d for %s, attempting to extract first 3 channels",
              img->colors, cpath);
        // Fall through: we handle up to 4 channels by taking RGB only
    }

    const uint32_t width  = img->width;
    const uint32_t height = img->height;

    // Validate dimensions – protect against absurd values (LibRaw itself caps at INT_MAX)
    if(width == 0 || height == 0 || width > 30000 || height > 30000) {
        ALOGE("Invalid image dimensions %ux%u for %s", width, height, cpath);
        return nullptr;
    }

    // Compute sizes
    const size_t pixelBytes = static_cast<size_t>(width) *
                              static_cast<size_t>(height) * 3UL;
    const size_t totalBytes = 8 + pixelBytes;

    // Overflow protection
    if(pixelBytes / 3 != static_cast<size_t>(width) * static_cast<size_t>(height) ||
       totalBytes < pixelBytes ||
       pixelBytes > MAX_PIXEL_BYTES) {
        ALOGE("Image too large or overflow: %zu bytes (>%zu limit) for %s",
              pixelBytes, MAX_PIXEL_BYTES, cpath);
        return nullptr;
    }

    if(!img->data) {
        ALOGE("Image data is null for %s", cpath);
        return nullptr;
    }

    // Verify data_size matches expectation for bitmap
    const size_t expectedDataSize = static_cast<size_t>(width) *
                                    static_cast<size_t>(height) *
                                    (img->bits / 8) *
                                    static_cast<size_t>(img->colors);
    if(img->data_size < expectedDataSize) {
        ALOGE("data_size %u < expected %zu for %s",
              img->data_size, expectedDataSize, cpath);
        return nullptr;
    }

    // Allocate output
    jbyteArray out = env->NewByteArray(static_cast<jsize>(totalBytes));
    if(!out) {
        ALOGE("Failed to allocate output array of %zu bytes for %s", totalBytes, cpath);
        return nullptr;
    }

    std::unique_ptr<unsigned char[]> buffer;
    try {
        buffer = std::make_unique<unsigned char[]>(totalBytes);
    } catch(const std::bad_alloc&) {
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

    // Copy RGB data – handle both 3-channel and 4-channel (CMYK) sources
    if(img->colors == 3) {
        std::memcpy(buffer.get() + 8, img->data, pixelBytes);
    } else {
        // CMYK or RGBA: extract first 3 channels (R, G, B)
        const unsigned bytesPerPixel = (img->bits / 8) * static_cast<unsigned>(img->colors);
        unsigned char* dst = buffer.get() + 8;
        for(uint32_t y = 0; y < height; ++y) {
            const unsigned char* srcRow = img->data + static_cast<size_t>(y) * width * bytesPerPixel;
            for(uint32_t x = 0; x < width; ++x) {
                const unsigned char* srcPx = srcRow + x * bytesPerPixel;
                *dst++ = srcPx[0]; // R
                *dst++ = srcPx[1]; // G
                *dst++ = srcPx[2]; // B
            }
        }
    }

    env->SetByteArrayRegion(out, 0, static_cast<jsize>(totalBytes),
                             reinterpret_cast<const jbyte*>(buffer.get()));

    if(env->ExceptionCheck()) {
        ALOGE("JNI exception while setting byte array region for %s", cpath);
        return nullptr;
    }

    // Release guards before success return (destructors will run)
    imgGuard.release();
    procGuard.invalidate();

    ALOGI("Successfully decoded RAW %ux%u (%" PRIu64 " bytes) from %s",
          width, height, static_cast<uint64_t>(totalBytes), cpath);

    return out;
}
