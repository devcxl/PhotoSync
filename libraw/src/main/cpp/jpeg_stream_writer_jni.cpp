#include <jni.h>

#include <android/log.h>
#include <cstdint>

#include "jpeg_stream_writer.h"
#include "native_handle_registry.h"
#include "raw_log.h"

#define LOG_TAG "JpegStreamWriterJni"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using photosync::jpeg::JpegStreamWriter;
using photosync::native::HandleRegistry;

/** Process-wide writer table; handles are opaque `long`s handed to Kotlin. */
HandleRegistry<JpegStreamWriter> gWriters;

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_cn_devcxl_photosync_wrapper_NativeJpegEncoder_nativeBegin(
    JNIEnv* env,
    jclass /*clazz*/,
    jobject outputStream,
    jint width,
    jint height,
    jint quality
) {
    if (outputStream == nullptr || width <= 0 || height <= 0 || quality <= 0 || quality > 100) {
        ALOGE("Invalid encoder configuration %dx%d q=%d", width, height, quality);
        return 0;
    }
    auto writer = std::make_unique<JpegStreamWriter>();
    if (!writer->begin(
            env,
            outputStream,
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            quality)) {
        return 0;
    }
    return gWriters.add(std::move(writer));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_devcxl_photosync_wrapper_NativeJpegEncoder_nativeWrite(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle,
    jobject rgbaBuffer,
    jint rows,
    jboolean bottomUp
) {
    JpegStreamWriter* writer = gWriters.find(handle);
    if (writer == nullptr || rgbaBuffer == nullptr || rows <= 0) {
        return 0;
    }
    auto* pixels = static_cast<uint8_t*>(env->GetDirectBufferAddress(rgbaBuffer));
    if (pixels == nullptr) {
        ALOGE("The scanline buffer is not a direct buffer");
        return 0;
    }
    return static_cast<jint>(
        writer->writeScanlines(
            env, pixels, static_cast<uint32_t>(rows), bottomUp == JNI_TRUE));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_devcxl_photosync_wrapper_NativeJpegEncoder_nativeFinish(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle
) {
    JpegStreamWriter* writer = gWriters.find(handle);
    if (writer == nullptr) {
        return JNI_FALSE;
    }
    const bool finished = writer->finish(env);
    gWriters.remove(handle);
    return finished ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_cn_devcxl_photosync_wrapper_NativeJpegEncoder_nativeAbort(
    JNIEnv* env,
    jclass /*clazz*/,
    jlong handle
) {
    JpegStreamWriter* writer = gWriters.find(handle);
    if (writer != nullptr) {
        writer->abort(env);
    }
    gWriters.remove(handle);
}
