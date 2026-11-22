#pragma once

#include <jni.h>
#include "libraw.h"

namespace photosync::jni {

// RAII guard for JNI UTF string release
struct PathGuard {
    JNIEnv* env;
    jstring jpath;
    const char* cpath;
    ~PathGuard() {
        if (cpath) {
            env->ReleaseStringUTFChars(jpath, cpath);
        }
    }
};

// RAII guard for LibRaw instance lifecycle
struct LibRawGuard {
    LibRaw* proc;
    bool valid;
    explicit LibRawGuard(LibRaw* p) : proc(p), valid(true) {}
    void invalidate() { valid = false; }
    ~LibRawGuard() {
        if (proc && valid) {
            proc->recycle();
        }
    }
};

// RAII guard for dcraw_make_mem_image output
struct ImageGuard {
    libraw_processed_image_t* img;
    explicit ImageGuard(libraw_processed_image_t* i) : img(i) {}
    void release() { img = nullptr; }
    ~ImageGuard() {
        if (img) {
            LibRaw::dcraw_clear_mem(img);
        }
    }
};

}  // namespace photosync::jni
