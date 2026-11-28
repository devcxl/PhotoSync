package cn.devcxl.photosync.editor.vision

import android.app.ActivityManager
import android.content.Context

/**
 * Coarse RAM class of the device that decides how long an ORT session may stay resident.
 *
 * The tier only controls the *idle* lifetime of a session. Both tiers always serialize inference
 * through [OrtSessionManager] and close the old session before loading a different model.
 */
enum class DeviceMemoryTier {

    /** At most 4 GiB of physical RAM: an ORT session is closed as soon as inference finishes. */
    LOW,

    /** More than 4 GiB: one warm session may stay resident until it has been idle for 30 s. */
    STANDARD;

    companion object {

        /** Devices whose total RAM is at or below 4 GiB are treated as [LOW]. */
        const val LOW_TIER_MAX_TOTAL_MEMORY_BYTES: Long = 4L * 1024L * 1024L * 1024L

        /**
         * Classifies a device from its total physical memory.
         *
         * An unknown reading (`0`) is treated as [LOW]: assuming the smaller budget only costs a
         * session reload, while assuming [STANDARD] could leave a session resident on a device
         * that cannot afford it.
         */
        fun fromTotalMemoryBytes(totalMemoryBytes: Long): DeviceMemoryTier =
            if (totalMemoryBytes <= LOW_TIER_MAX_TOTAL_MEMORY_BYTES) LOW else STANDARD

        /**
         * Reads [ActivityManager.MemoryInfo.totalMem].
         *
         * Falls back to [LOW] when the activity manager is unavailable, for the reason documented
         * on [fromTotalMemoryBytes].
         */
        fun detect(context: Context): DeviceMemoryTier {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return LOW
            val memoryInfo = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(memoryInfo)
            return fromTotalMemoryBytes(memoryInfo.totalMem)
        }
    }
}
