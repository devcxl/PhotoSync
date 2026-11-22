#include "native_rgb16.h"

#include <android/log.h>
#include <cstring>
#include <inttypes.h>
#include <memory>
#include <new>

#include "jni_raii_guards.h"
#include "native_handle_registry.h"
#include "raw_autobright.h"
#include "raw_decode_config.h"
#include "raw_linear16.h"
#include "raw_log.h"

#define LOG_TAG "NativeRgb16"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace photosync::raw {

namespace {

constexpr uint32_t kSourceChannels = 3;
constexpr size_t kBytesPerSample = sizeof(uint16_t);
constexpr size_t kBytesPerTexel = 8;
constexpr uint32_t kMaxSourceEdge = 30000;
constexpr jlong kMaxImageBytes = 512LL * 1024 * 1024;
constexpr float kInv65535 = 1.0f / 65535.0f;
constexpr uint16_t kHalfOne = 0x3C00;

bool validateDecodedImage(const libraw_processed_image_t& image) {
    if (image.type != LIBRAW_IMAGE_BITMAP || image.bits != 16 || image.colors < 3) {
        ALOGE("Unexpected RGB16 image type=%d bits=%d colors=%d",
              image.type, image.bits, image.colors);
        return false;
    }
    if (image.width == 0 || image.height == 0 ||
        image.width > kMaxSourceEdge || image.height > kMaxSourceEdge) {
        ALOGE("Invalid RGB16 dimensions %ux%u", image.width, image.height);
        return false;
    }
    const int64_t bytes = static_cast<int64_t>(image.width) * image.height *
        static_cast<int64_t>(kSourceChannels) * kBytesPerSample;
    if (bytes > kMaxImageBytes) {
        ALOGE("Refusing %" PRId64 " byte native RGB16 buffer", bytes);
        return false;
    }
    return true;
}

/** Converts one row of a sub-rectangle into RGBA16F texels. */
void convertSliceRow(
    const uint8_t* sourceData,
    uint32_t sourceWidth,
    uint32_t sourceX0,
    uint32_t width,
    uint8_t* destinationRow
) {
    for (uint32_t column = 0; column < width; ++column) {
        const size_t offset =
            (static_cast<size_t>(sourceX0 + column) * kSourceChannels) * kBytesPerSample;
        uint16_t samples[kSourceChannels] = {0, 0, 0};
        std::memcpy(samples, sourceData + offset, sizeof(samples));
        const uint16_t texel[4] = {
            halfFromFloat(static_cast<float>(samples[0]) * kInv65535),
            halfFromFloat(static_cast<float>(samples[1]) * kInv65535),
            halfFromFloat(static_cast<float>(samples[2]) * kInv65535),
            kHalfOne
        };
        std::memcpy(destinationRow + static_cast<size_t>(column) * kBytesPerTexel, texel,
                    sizeof(texel));
    }
}

}  // namespace

Rgb16Image* decodeRgb16(const char* path, bool halfSize) {
    if (path == nullptr) {
        return nullptr;
    }
    LibRaw proc;
    photosync::jni::LibRawGuard procGuard(&proc);
    libraw_output_params_t& params = proc.imgdata.params;
    configureLinearDecode(params);
    if (halfSize) {
        params.half_size = 1;
    }
    int result = proc.open_file(path);
    if (result != LIBRAW_SUCCESS) {
        ALOGE("open_file failed (%d): %s", result, safeLibRawError(result));
        return nullptr;
    }
    result = proc.unpack();
    if (result != LIBRAW_SUCCESS) {
        ALOGE("unpack failed (%d): %s", result, safeLibRawError(result));
        return nullptr;
    }
    result = proc.dcraw_process();
    if (result != LIBRAW_SUCCESS) {
        ALOGE("dcraw_process failed (%d): %s", result, safeLibRawError(result));
        return nullptr;
    }
    libraw_output_params_t metadataParams = params;
    const float gain = clampAutoBrightGain(proc, metadataParams);
    const float baseIso =
        proc.imgdata.other.iso_speed > 0.0f ? proc.imgdata.other.iso_speed : 100.0f;

    int imageResult = LIBRAW_SUCCESS;
    libraw_processed_image_t* image = proc.dcraw_make_mem_image(&imageResult);
    if (image == nullptr || imageResult != LIBRAW_SUCCESS) {
        ALOGE("dcraw_make_mem_image failed (%d): %s",
              imageResult, safeLibRawError(imageResult));
        if (image != nullptr) {
            LibRaw::dcraw_clear_mem(image);
        }
        return nullptr;
    }
    if (!validateDecodedImage(*image)) {
        LibRaw::dcraw_clear_mem(image);
        return nullptr;
    }
    auto* retained = new (std::nothrow) Rgb16Image();
    if (retained == nullptr) {
        LibRaw::dcraw_clear_mem(image);
        return nullptr;
    }
    retained->image = image;
    retained->autoBrightGain = gain;
    retained->effectiveIso = baseIso * gain;
    return retained;
}

bool extractRgba16fSlice(
    const Rgb16Image& source,
    uint32_t x0,
    uint32_t y0,
    uint32_t width,
    uint32_t height,
    uint8_t* target
) {
    if (target == nullptr || source.image == nullptr || width == 0 || height == 0) {
        return false;
    }
    if (x0 >= source.width() || y0 >= source.height() ||
        x0 + width > source.width() || y0 + height > source.height()) {
        ALOGE("Slice [%u,%u %ux%u] outside %ux%u",
              x0, y0, width, height, source.width(), source.height());
        return false;
    }
    const uint8_t* data = source.image->data;
    const size_t sourceStride =
        static_cast<size_t>(source.width()) * kSourceChannels * kBytesPerSample;
    const size_t targetStride = static_cast<size_t>(width) * kBytesPerTexel;
#if defined(_OPENMP)
#pragma omp parallel for schedule(static) if (height >= 64)
#endif
    for (uint32_t row = 0; row < height; ++row) {
        const uint8_t* sourceRow = data + static_cast<size_t>(y0 + row) * sourceStride;
        convertSliceRow(
            sourceRow, source.width(), x0, width, target + static_cast<size_t>(row) * targetStride);
    }
    return true;
}

}  // namespace photosync::raw

namespace {

using photosync::native::HandleRegistry;
using photosync::raw::Rgb16Image;

HandleRegistry<Rgb16Image> gImages;

/** Builds the Kotlin `NativeRgb16Image` around a retained native image. */
jobject createKotlinHandle(JNIEnv* env, jlong handle, const Rgb16Image& image) {
    jclass imageClass = env->FindClass("cn/devcxl/photosync/wrapper/NativeRgb16Image");
    if (imageClass == nullptr) {
        return nullptr;
    }
    jmethodID constructor = env->GetMethodID(imageClass, "<init>", "(JIIFF)V");
    jobject result = nullptr;
    if (constructor != nullptr) {
        result = env->NewObject(
            imageClass,
            constructor,
            handle,
            static_cast<jint>(image.width()),
            static_cast<jint>(image.height()),
            static_cast<jfloat>(image.autoBrightGain),
            static_cast<jfloat>(image.effectiveIso)
        );
    }
    env->DeleteLocalRef(imageClass);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return nullptr;
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_cn_devcxl_photosync_wrapper_NativeRgb16_nativeDecode(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring path,
    jboolean halfSize
) {
    if (path == nullptr) {
        return nullptr;
    }
    const char* rawPath = env->GetStringUTFChars(path, nullptr);
    if (rawPath == nullptr) {
        return nullptr;
    }
    photosync::jni::PathGuard pathGuard{env, path, rawPath};
    Rgb16Image* image = photosync::raw::decodeRgb16(rawPath, halfSize == JNI_TRUE);
    if (image == nullptr) {
        return nullptr;
    }
    const jlong handle = gImages.add(std::unique_ptr<Rgb16Image>(image));
    jobject result = createKotlinHandle(env, handle, *image);
    if (result == nullptr) {
        gImages.remove(handle);
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_devcxl_photosync_wrapper_NativeRgb16_nativeExtractSlice(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jint x0,
    jint y0,
    jint width,
    jint height,
    jobject destination
) {
    Rgb16Image* image = gImages.find(handle);
    if (image == nullptr || destination == nullptr) {
        return JNI_FALSE;
    }
    auto* pixels = static_cast<uint8_t*>(env->GetDirectBufferAddress(destination));
    if (pixels == nullptr) {
        return JNI_FALSE;
    }
    const jlong required = static_cast<jlong>(width) * height * 8;
    if (env->GetDirectBufferCapacity(destination) < required) {
        ALOGE("Slice buffer too small: %lld < %lld",
              static_cast<long long>(env->GetDirectBufferCapacity(destination)),
              static_cast<long long>(required));
        return JNI_FALSE;
    }
    const bool ok = photosync::raw::extractRgba16fSlice(
        *image,
        static_cast<uint32_t>(x0),
        static_cast<uint32_t>(y0),
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height),
        pixels
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_devcxl_photosync_wrapper_NativeRgb16_nativeExtractScaled(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jint targetWidth,
    jint targetHeight,
    jobject destination
) {
    Rgb16Image* image = gImages.find(handle);
    if (image == nullptr || destination == nullptr || targetWidth <= 0 || targetHeight <= 0) {
        return JNI_FALSE;
    }
    auto* pixels = static_cast<uint8_t*>(env->GetDirectBufferAddress(destination));
    if (pixels == nullptr) {
        return JNI_FALSE;
    }
    const jlong required = static_cast<jlong>(targetWidth) * targetHeight * 8;
    if (env->GetDirectBufferCapacity(destination) < required) {
        return JNI_FALSE;
    }
    const photosync::raw::LinearTargetSize target{
        static_cast<uint32_t>(targetWidth), static_cast<uint32_t>(targetHeight)};
    const bool ok = photosync::raw::writeLinearRgba16f(
        image->image->data,
        image->width(),
        image->height(),
        target,
        pixels
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_cn_devcxl_photosync_wrapper_NativeRgb16_nativeRelease(
    JNIEnv* /*env*/,
    jclass /*clazz*/,
    jlong handle
) {
    gImages.remove(handle);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_cn_devcxl_photosync_wrapper_NativeRgb16_nativeProbeDimensions(
    JNIEnv* env,
    jclass /*clazz*/,
    jstring path
) {
    if (path == nullptr) {
        return nullptr;
    }
    const char* rawPath = env->GetStringUTFChars(path, nullptr);
    if (rawPath == nullptr) {
        return nullptr;
    }
    photosync::jni::PathGuard pathGuard{env, path, rawPath};
    LibRaw proc;
    photosync::jni::LibRawGuard procGuard(&proc);
    const int result = proc.open_file(rawPath);
    if (result != LIBRAW_SUCCESS) {
        return nullptr;
    }
    const jint dimensions[2] = {
        static_cast<jint>(proc.imgdata.sizes.width),
        static_cast<jint>(proc.imgdata.sizes.height)
    };
    if (dimensions[0] <= 0 || dimensions[1] <= 0) {
        return nullptr;
    }
    jintArray output = env->NewIntArray(2);
    if (output != nullptr) {
        env->SetIntArrayRegion(output, 0, 2, dimensions);
    }
    return output;
}
