#pragma once

#include <jni.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <unordered_map>

namespace photosync::native {

/**
 * Small handle table that keeps Java-facing `long` handles detached from raw C++ pointers.
 *
 * A monotonically increasing id means a stale or double-released handle is simply "not found"
 * instead of undefined behaviour, which matters because cancellation can race a release.
 */
template <typename T>
class HandleRegistry {
public:
    /** Stores @p value and returns its new handle. */
    jlong add(std::unique_ptr<T> value) {
        const jlong handle = nextHandle_.fetch_add(1);
        std::lock_guard<std::mutex> lock(mutex_);
        entries_.emplace(handle, std::move(value));
        return handle;
    }

    /** Returns the stored value, or null when the handle is unknown. */
    T* find(jlong handle) {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto it = entries_.find(handle);
        return it == entries_.end() ? nullptr : it->second.get();
    }

    /** Removes and destroys the stored value, returning whether it existed. */
    bool remove(jlong handle) {
        std::unique_ptr<T> victim;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            const auto it = entries_.find(handle);
            if (it == entries_.end()) {
                return false;
            }
            victim = std::move(it->second);
            entries_.erase(it);
        }
        return true;
    }

private:
    std::mutex mutex_;
    std::atomic<jlong> nextHandle_{1};
    std::unordered_map<jlong, std::unique_ptr<T>> entries_;
};

}  // namespace photosync::native
