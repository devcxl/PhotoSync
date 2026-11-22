#include "jpeg_stream_writer.h"

#include <jpeglib.h>

#include <android/log.h>
#include <csetjmp>
#include <vector>

#include "raw_log.h"

#define LOG_TAG "JpegStreamWriter"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace photosync::jpeg {

namespace {

/** 64 KiB output chunk: small enough to stay irrelevant, large enough to avoid call storms. */
constexpr size_t kDestinationBufferSize = 64 * 1024;

/** Error manager that turns libjpeg's `exit()` into a `longjmp` back into the calling function. */
struct JavaJpegErrorMgr {
    jpeg_error_mgr pub;
    std::jmp_buf jump;
};

/** Destination manager writing into a Java `OutputStream`. */
struct JavaDestinationMgr {
    jpeg_destination_mgr pub;
    JpegStreamWriter* owner;
};

void onErrorExit(j_common_ptr cinfo) {
    auto* error = reinterpret_cast<JavaJpegErrorMgr*>(cinfo->err);
    char message[JMSG_LENGTH_MAX];
    (*cinfo->err->format_message)(cinfo, message);
    ALOGE("libjpeg: %s", message);
    std::longjmp(error->jump, 1);
}

void onEmitMessage(j_common_ptr /*cinfo*/, int /*level*/) {
    // Warnings would otherwise print to stderr, which Android discards anyway.
}

}  // namespace

struct JpegStreamWriter::State {
    jpeg_compress_struct cinfo{};
    JavaJpegErrorMgr error{};
    JavaDestinationMgr destination{};
    std::vector<uint8_t> buffer;
    std::vector<JSAMPROW> rowPointers;
    bool initialized = false;
};

JpegStreamWriter::JpegStreamWriter() : state_(std::make_unique<State>()) {}

JpegStreamWriter::~JpegStreamWriter() = default;

bool JpegStreamWriter::begin(
    JNIEnv* env,
    jobject outputStream,
    uint32_t width,
    uint32_t height,
    int quality
) {
    if (active_ || outputStream == nullptr || width == 0 || height == 0) {
        return false;
    }
    active_ = true;
    failed_ = false;
    State* state = state_.get();
    state->buffer.assign(kDestinationBufferSize, 0);
    state->rowPointers.reserve(height < 512 ? height : 512);
    state->cinfo.err = jpeg_std_error(&state->error.pub);
    state->error.pub.error_exit = onErrorExit;
    state->error.pub.emit_message = onEmitMessage;
    if (setjmp(state->error.jump) != 0) {
        jpeg_destroy_compress(&state->cinfo);
        releaseReferences(env);
        active_ = false;
        return false;
    }
    jpeg_create_compress(&state->cinfo);
    state->destination.owner = this;

    jclass streamClass = env->GetObjectClass(outputStream);
    if (streamClass == nullptr) {
        abort(env);
        return false;
    }
    writeMethod_ = env->GetMethodID(streamClass, "write", "([BII)V");
    env->DeleteLocalRef(streamClass);
    if (writeMethod_ == nullptr) {
        env->ExceptionClear();
        ALOGE("OutputStream.write([BII) not found");
        abort(env);
        return false;
    }
    outputStream_ = env->NewGlobalRef(outputStream);
    jbyteArray localChunk = env->NewByteArray(static_cast<jsize>(kDestinationBufferSize));
    if (localChunk != nullptr) {
        chunkArray_ = static_cast<jbyteArray>(env->NewGlobalRef(localChunk));
        env->DeleteLocalRef(localChunk);
    }
    if (outputStream_ == nullptr || chunkArray_ == nullptr) {
        env->ExceptionClear();
        ALOGE("Failed to retain the Java output stream");
        abort(env);
        return false;
    }

    state->destination.pub.init_destination = [](j_compress_ptr cinfo) {
        auto* destination = reinterpret_cast<JavaDestinationMgr*>(cinfo->dest);
        JpegStreamWriter* writer = destination->owner;
        cinfo->dest->next_output_byte = writer->state_->buffer.data();
        cinfo->dest->free_in_buffer = writer->state_->buffer.size();
    };
    state->destination.pub.empty_output_buffer = [](j_compress_ptr cinfo) -> boolean {
        auto* destination = reinterpret_cast<JavaDestinationMgr*>(cinfo->dest);
        JpegStreamWriter* writer = destination->owner;
        const boolean ok = writer->flushDestination(kDestinationBufferSize) ? TRUE : FALSE;
        cinfo->dest->next_output_byte = writer->state_->buffer.data();
        cinfo->dest->free_in_buffer = writer->state_->buffer.size();
        return ok;
    };
    state->destination.pub.term_destination = [](j_compress_ptr cinfo) {
        auto* destination = reinterpret_cast<JavaDestinationMgr*>(cinfo->dest);
        JpegStreamWriter* writer = destination->owner;
        const size_t used = writer->state_->buffer.size() - cinfo->dest->free_in_buffer;
        writer->flushDestination(used);
    };

    state->cinfo.image_width = width;
    state->cinfo.image_height = height;
    state->cinfo.input_components = 4;
    state->cinfo.in_color_space = JCS_EXT_RGBA;
    jpeg_set_defaults(&state->cinfo);
    jpeg_set_quality(&state->cinfo, quality, TRUE);
    // Single baseline scan, sequential Huffman: keeps the encoder at O(1) memory.
    state->cinfo.optimize_coding = FALSE;
    state->cinfo.arith_code = FALSE;
    state->cinfo.dct_method = JDCT_ISLOW;
    state->cinfo.dest = reinterpret_cast<jpeg_destination_mgr*>(&state->destination);

    pendingEnv_ = env;
    state->initialized = true;
    jpeg_start_compress(&state->cinfo, TRUE);
    pendingEnv_ = nullptr;
    if (failed_ || !state->initialized) {
        abort(env);
        return false;
    }
    ALOGI("Streaming baseline JPEG %ux%u q=%d", width, height, quality);
    return true;
}

uint32_t JpegStreamWriter::writeScanlines(
    JNIEnv* env,
    const uint8_t* rgba,
    uint32_t rows,
    bool bottomUp
) {
    if (!active_ || failed_ || rgba == nullptr || rows == 0) {
        return 0;
    }
    State* state = state_.get();
    if (!state->initialized) {
        return 0;
    }
    const JDIMENSION remaining = state->cinfo.image_height - state->cinfo.next_scanline;
    if (rows > remaining) {
        rows = remaining;
    }
    if (rows == 0) {
        return 0;
    }
    if (setjmp(state->error.jump) != 0) {
        failed_ = true;
        return 0;
    }
    state->rowPointers.clear();
    const size_t stride = static_cast<size_t>(state->cinfo.image_width) * 4u;
    for (uint32_t row = 0; row < rows; row++) {
        const uint32_t sourceRow = bottomUp ? (rows - 1 - row) : row;
        state->rowPointers.push_back(const_cast<JSAMPROW>(rgba + sourceRow * stride));
    }
    pendingEnv_ = env;
    const JDIMENSION written = jpeg_write_scanlines(&state->cinfo, state->rowPointers.data(), rows);
    pendingEnv_ = nullptr;
    if (written != rows) {
        failed_ = true;
    }
    return written;
}

bool JpegStreamWriter::finish(JNIEnv* env) {
    if (!active_) {
        return false;
    }
    State* state = state_.get();
    volatile bool ok = !failed_ && state->initialized;
    if (ok && setjmp(state->error.jump) != 0) {
        ok = false;
    }
    if (ok) {
        pendingEnv_ = env;
        jpeg_finish_compress(&state->cinfo);
        pendingEnv_ = nullptr;
        ok = !failed_;
    }
    if (state->initialized) {
        jpeg_destroy_compress(&state->cinfo);
        state->initialized = false;
    }
    releaseReferences(env);
    active_ = false;
    return ok;
}

void JpegStreamWriter::abort(JNIEnv* env) {
    if (state_ != nullptr && state_->initialized) {
        jpeg_destroy_compress(&state_->cinfo);
        state_->initialized = false;
    }
    releaseReferences(env);
    active_ = false;
}

bool JpegStreamWriter::flushDestination(size_t length) {
    JNIEnv* env = pendingEnv_;
    if (env == nullptr || outputStream_ == nullptr || chunkArray_ == nullptr) {
        ALOGE("Destination flush without a usable JNI environment");
        failed_ = true;
        return false;
    }
    const size_t bounded = length > state_->buffer.size() ? state_->buffer.size() : length;
    if (bounded == 0) {
        return true;
    }
    env->SetByteArrayRegion(
        chunkArray_, 0, static_cast<jsize>(bounded),
        reinterpret_cast<const jbyte*>(state_->buffer.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        ALOGE("Failed to stage %zu output bytes into the Java chunk array", bounded);
        failed_ = true;
        return false;
    }
    env->CallVoidMethod(outputStream_, writeMethod_, chunkArray_, 0, static_cast<jint>(bounded));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        ALOGE("OutputStream.write failed for %zu bytes", bounded);
        failed_ = true;
        return false;
    }
    return true;
}

void JpegStreamWriter::releaseReferences(JNIEnv* env) {
    if (env == nullptr) {
        outputStream_ = nullptr;
        chunkArray_ = nullptr;
        return;
    }
    if (chunkArray_ != nullptr) {
        env->DeleteGlobalRef(chunkArray_);
        chunkArray_ = nullptr;
    }
    if (outputStream_ != nullptr) {
        env->DeleteGlobalRef(outputStream_);
        outputStream_ = nullptr;
    }
}

}  // namespace photosync::jpeg
