#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <climits>
#include <cstdint>
#include <inttypes.h>

#include "libraw.h"
#include "jni_raii_guards.h"
#include "raw_autobright.h"
#include "raw_linear16.h"
#include "raw_log.h"

#define LOG_TAG "LibRawLinear16"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

using photosync::jni::ImageGuard;
using photosync::jni::LibRawGuard;
using photosync::jni::PathGuard;
using photosync::raw::LinearTargetSize;

namespace {

constexpr uint32_t kMaxSourceEdge = 30000;
constexpr jlong kBytesPerTexel = 8;
// Editor textures never need more than this; rejecting larger buffers keeps a runaway
// allocation from being OOM-killed on low-memory devices.
constexpr jlong kMaxBufferBytes = 512LL * 1024 * 1024;

/** Applies the spec §4 16-bit linear sRGB decode configuration. */
void configureLinearOutput(libraw_output_params_t& params) {
    params.output_bps = 16;
    params.output_color = 1;   // sRGB primaries
    params.output_tiff = 0;    // PPM/BITMAP output (in-memory)
    params.gamm[0] = 1.0;      // Linear transfer function (no sRGB encode)
    params.gamm[1] = 1.0;

    params.use_camera_wb = 1;
    params.use_auto_wb = 0;
    // Auto-brightening is reproduced on the GPU so the raw linear headroom survives.
    params.no_auto_bright = 1;
    params.bright = 1.0f;

    params.user_qual = 2;
    params.med_passes = 0;
    params.fbdd_noiserd = 0;
    params.threshold = 0.0;
    params.highlight = 2;

    params.aber[0] = 1.0;
    params.aber[1] = 1.0;
    params.aber[2] = 1.0;
}

/** True when LibRaw can halve the demosaic without losing the requested preview resolution. */
bool shouldUseHalfSize(const LibRaw& proc, jint maxLongEdge) {
    if (maxLongEdge <= 0) {
        return false;
    }
    const uint32_t width = static_cast<uint32_t>(proc.imgdata.sizes.width);
    const uint32_t height = static_cast<uint32_t>(proc.imgdata.sizes.height);
    return static_cast<uint32_t>(maxLongEdge) <= std::max(width, height) / 2;
}

bool validateLinearImage(const libraw_processed_image_t& img) {
    if (img.type != LIBRAW_IMAGE_BITMAP || img.bits != 16 || img.colors < 3) {
        ALOGE("Unexpected linear image type=%d bits=%d colors=%d", img.type, img.bits, img.colors);
        return false;
    }
    if (img.width == 0 || img.height == 0 || img.width > kMaxSourceEdge || img.height > kMaxSourceEdge) {
        ALOGE("Invalid linear image dimensions %ux%u", img.width, img.height);
        return false;
    }
    const size_t expected = static_cast<size_t>(img.width) * img.height * 3UL * sizeof(uint16_t);
    if (img.data_size < expected) {
        ALOGE("Linear image data_size %u < expected %zu", img.data_size, expected);
        return false;
    }
    return true;
}

/**
 * Decodes @p path into a 16-bit linear sRGB in-memory image and reports the auto-bright metadata
 * the browse page would have applied.
 */
libraw_processed_image_t* processLinearImage(
    LibRaw& proc,
    const char* path,
    jint maxLongEdge,
    float& outAutoBrightGain,
    float& outEffectiveIso
) {
    outAutoBrightGain = 1.0f;
    outEffectiveIso = 0.0f;
    libraw_output_params_t& params = proc.imgdata.params;
    configureLinearOutput(params);

    int ret = proc.open_file(path);
    if (ret != LIBRAW_SUCCESS) {
        ALOGE("open_file failed (%d): %s", ret, photosync::raw::safeLibRawError(ret));
        return nullptr;
    }
    if (shouldUseHalfSize(proc, maxLongEdge)) {
        params.half_size = 1;
    }
    ret = proc.unpack();
    if (ret != LIBRAW_SUCCESS) {
        ALOGE("unpack failed (%d): %s", ret, photosync::raw::safeLibRawError(ret));
        return nullptr;
    }
    ret = proc.dcraw_process();
    if (ret != LIBRAW_SUCCESS) {
        ALOGE("dcraw_process failed (%d): %s", ret, photosync::raw::safeLibRawError(ret));
        return nullptr;
    }

    // LibRaw divides the gamma-table input scale by `bright`, so mutating it here would
    // bake the auto-bright gain into the supposedly linear pixels and double-apply it on the
    // GPU. Read the metadata through a throwaway copy instead.
    libraw_output_params_t metadataParams = params;
    outAutoBrightGain = photosync::raw::clampAutoBrightGain(proc, metadataParams);
    const float baseIso = proc.imgdata.other.iso_speed > 0.0f ? proc.imgdata.other.iso_speed : 100.0f;
    outEffectiveIso = baseIso * outAutoBrightGain;

    int imageRet = LIBRAW_SUCCESS;
    libraw_processed_image_t* image = proc.dcraw_make_mem_image(&imageRet);
    if (!image || imageRet != LIBRAW_SUCCESS) {
        ALOGE("dcraw_make_mem_image failed (%d): %s",
              imageRet, photosync::raw::safeLibRawError(imageRet));
        if (image) {
            LibRaw::dcraw_clear_mem(image);
        }
        return nullptr;
    }
    if (!validateLinearImage(*image)) {
        LibRaw::dcraw_clear_mem(image);
        return nullptr;
    }
    return image;
}

/** Allocates a JVM-owned direct buffer so the GC reclaims the pixels after upload. */
jobject allocateDirectByteBuffer(JNIEnv* env, jlong capacity) {
    jclass bufferClass = env->FindClass("java/nio/ByteBuffer");
    if (!bufferClass) {
        return nullptr;
    }
    jmethodID allocateDirect = env->GetStaticMethodID(
        bufferClass, "allocateDirect", "(I)Ljava/nio/ByteBuffer;");
    jobject buffer = nullptr;
    if (allocateDirect) {
        buffer = env->CallStaticObjectMethod(
            bufferClass, allocateDirect, static_cast<jint>(capacity));
    }
    env->DeleteLocalRef(bufferClass);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return buffer;
}

/** Builds the `LinearRawBuffer` Kotlin data class around @p buffer. */
jobject createLinearRawBuffer(
    JNIEnv* env,
    const LinearTargetSize& size,
    float autoBrightGain,
    float effectiveIso,
    jobject buffer
) {
    jclass bufferClass = env->FindClass("cn/devcxl/photosync/wrapper/LinearRawBuffer");
    if (!bufferClass) {
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(
        bufferClass, "<init>", "(IIFFLjava/nio/ByteBuffer;)V");
    jobject result = nullptr;
    if (constructor) {
        result = env->NewObject(
            bufferClass, constructor,
            static_cast<jint>(size.width),
            static_cast<jint>(size.height),
            static_cast<jfloat>(autoBrightGain),
            static_cast<jfloat>(effectiveIso),
            buffer
        );
    }
    env->DeleteLocalRef(bufferClass);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

}  // namespace

/**
 * Decodes a RAW file into 16-bit linear-light `RGBA16F` texels for the editor GPU pipeline.
 *
 * @return A `LinearRawBuffer` whose pixel buffer is GC-owned, or null on decode failure.
 */
extern "C" JNIEXPORT jobject JNICALL
Java_cn_devcxl_photosync_wrapper_RawWrapper_decodeToLinearRgba16F(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring jpath,
    jint maxLongEdge
) {
    if (!jpath) {
        return nullptr;
    }
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) {
        return nullptr;
    }
    PathGuard pathGuard{env, jpath, path};

    const int64_t startMillis = photosync::raw::monotonicMillis();
    LibRaw proc;
    LibRawGuard procGuard(&proc);

    float autoBrightGain = 1.0f;
    float effectiveIso = 0.0f;
    libraw_processed_image_t* image =
        processLinearImage(proc, path, maxLongEdge, autoBrightGain, effectiveIso);
    if (!image) {
        return nullptr;
    }
    ImageGuard imageGuard(image);

    const uint32_t requestedEdge = maxLongEdge > 0 ? static_cast<uint32_t>(maxLongEdge) : 0u;
    const LinearTargetSize target = photosync::raw::fitLongEdge(
        image->width, image->height, std::min(requestedEdge, kMaxSourceEdge));
    const jlong byteCount =
        static_cast<jlong>(target.width) * static_cast<jlong>(target.height) * kBytesPerTexel;
    if (byteCount <= 0 || byteCount > kMaxBufferBytes || byteCount > static_cast<jlong>(INT_MAX)) {
        ALOGE("Refusing %lld byte linear buffer (%ux%u from %ux%u)",
              static_cast<long long>(byteCount), target.width, target.height,
              image->width, image->height);
        return nullptr;
    }

    jobject buffer = allocateDirectByteBuffer(env, byteCount);
    if (!buffer) {
        ALOGE("Failed to allocate %lld byte direct buffer", static_cast<long long>(byteCount));
        return nullptr;
    }
    void* pixels = env->GetDirectBufferAddress(buffer);
    bool written = false;
    if (pixels) {
        written = photosync::raw::writeLinearRgba16f(
            image->data, image->width, image->height, target, static_cast<uint8_t*>(pixels));
    }
    if (!written) {
        ALOGE("Linear RGBA16F conversion failed");
        env->DeleteLocalRef(buffer);
        return nullptr;
    }

    jobject result =
        createLinearRawBuffer(env, target, autoBrightGain, effectiveIso, buffer);
    env->DeleteLocalRef(buffer);
    if (!result) {
        return nullptr;
    }
    ALOGI("Linear RAW %ux%u (source %ux%u, gain=%.2fx, ISO=%.0f) in %" PRId64 " ms",
          target.width, target.height, image->width, image->height,
          autoBrightGain, effectiveIso,
          photosync::raw::monotonicMillis() - startMillis);
    return result;
}
