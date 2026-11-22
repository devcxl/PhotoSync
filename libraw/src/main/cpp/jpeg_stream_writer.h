#pragma once

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <memory>

namespace photosync::jpeg {

/**
 * Streaming baseline JPEG encoder that pulls full-width RGBA scanlines from the bake pipeline and
 * pushes encoded bytes into a Java `OutputStream`.
 *
 * The encoder is deliberately constrained to a single baseline scan with `optimize_coding = FALSE`:
 * libjpeg-turbo would otherwise allocate a full-image DCT coefficient array, which costs 69 / 143 /
 * 291 MiB at 24 / 50 / 100MP and has no backing-store fallback. Keeping O(1) encoder memory is what
 * makes the 512MB export budget reachable.
 */
class JpegStreamWriter {
public:
    JpegStreamWriter();
    ~JpegStreamWriter();

    JpegStreamWriter(const JpegStreamWriter&) = delete;
    JpegStreamWriter& operator=(const JpegStreamWriter&) = delete;

    /**
     * Configures libjpeg-turbo and writes the SOI/frame headers.
     *
     * @param env JNI environment of the calling thread.
     * @param outputStream Java `OutputStream`; a global reference is retained until release.
     * @param width Image width in pixels.
     * @param height Image height in pixels.
     * @param quality Baseline quality in `1..100`.
     * @return True when the stream is ready to accept scanlines.
     */
    bool begin(JNIEnv* env, jobject outputStream, uint32_t width, uint32_t height, int quality);

    /**
     * Encodes @p rows full-width `JCS_EXT_RGBA` scanlines.
     *
     * @param bottomUp True when @p rgba stores the strip's bottom row first (the `glReadPixels`
     *   order); the row pointer array is then built in reverse so no copy is needed.
     * @return The number of scanlines the encoder consumed.
     */
    uint32_t writeScanlines(JNIEnv* env, const uint8_t* rgba, uint32_t rows, bool bottomUp);

    /** Flushes the tail of the image and releases the Java stream reference. */
    bool finish(JNIEnv* env);

    /** Aborts the encode without finishing the image. Safe to call at any point. */
    void abort(JNIEnv* env);

    /** True between [begin] and [finish]/[abort]. */
    bool isActive() const { return active_; }

private:
    struct State;

    bool flushDestination(size_t length);
    void releaseReferences(JNIEnv* env);

    bool active_ = false;
    bool failed_ = false;
    JNIEnv* pendingEnv_ = nullptr;
    jobject outputStream_ = nullptr;
    jbyteArray chunkArray_ = nullptr;
    jmethodID writeMethod_ = nullptr;
    std::unique_ptr<State> state_;
};

}  // namespace photosync::jpeg
