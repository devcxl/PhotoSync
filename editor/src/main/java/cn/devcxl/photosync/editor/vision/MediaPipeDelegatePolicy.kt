package cn.devcxl.photosync.editor.vision

import android.content.Context
import android.content.SharedPreferences

import com.google.mediapipe.framework.MediaPipeException
import com.google.mediapipe.tasks.core.Delegate
import timber.log.Timber

/**
 * Tier 4 of the GPU fallback matrix: decides whether MediaPipe may use its GPU delegate.
 *
 * Defaults to [Delegate.CPU] and only offers [Delegate.GPU] when the device reports OpenGL ES 3.1
 * (`reqGlEsVersion >= 0x30001`, the hard requirement of MediaPipe's ML inference) **and** this
 * device has never failed a GPU task. A failure of either kind (a `MediaPipeException` from the
 * graph initialisation or any other `RuntimeException` from the delegate) permanently blacklists
 * the device in [SharedPreferences], so the next visit to the Portrait tab starts on CPU instead of
 * throwing again.
 */
class MediaPipeDelegatePolicy(
    private val preferences: SharedPreferences,
    private val glEsVersion: Int
) {

    /** Convenience constructor that reads the device GLES level and the app preferences. */
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        resolveGlEsVersion(context)
    )

    /** True when this device failed a GPU delegate and must stay on CPU forever. */
    val isGpuBlacklisted: Boolean
        get() = preferences.getBoolean(KEY_GPU_BLACKLISTED, false)

    /** The delegate the next MediaPipe task should be created with. */
    fun preferredDelegate(): Delegate =
        if (supportsGpuDelegate() && !isGpuBlacklisted) Delegate.GPU else Delegate.CPU

    /**
     * Creates a MediaPipe task with the preferred delegate, rebuilding it on CPU if the GPU path
     * fails.
     *
     * @return The created task, or `null` when even the CPU path failed.
     */
    fun <T : Any> createTask(factory: (Delegate) -> T): T? {
        if (preferredDelegate() == Delegate.GPU) {
            try {
                return factory(Delegate.GPU)
            } catch (e: MediaPipeException) {
                recordGpuFailure(e)
            } catch (e: RuntimeException) {
                recordGpuFailure(e)
            }
        }
        return try {
            factory(Delegate.CPU)
        } catch (e: MediaPipeException) {
            Timber.e(e, "MediaPipe task could not be created on the CPU delegate")
            null
        } catch (e: RuntimeException) {
            Timber.e(e, "MediaPipe task could not be created on the CPU delegate")
            null
        }
    }

    /** Records the permanent CPU fallback after a GPU delegate failure. */
    fun recordGpuFailure(cause: Throwable) {
        Timber.w(cause, "MediaPipe GPU delegate failed; permanently falling back to CPU")
        preferences.edit().putBoolean(KEY_GPU_BLACKLISTED, true).apply()
    }

    /** True when the device advertises the OpenGL ES 3.1 MediaPipe's GPU inference requires. */
    fun supportsGpuDelegate(): Boolean = glEsVersion >= MIN_GPU_GLES_VERSION

    companion object {
        /** `reqGlEsVersion` of OpenGL ES 3.1, the minimum for MediaPipe ML GPU inference. */
        const val MIN_GPU_GLES_VERSION = 0x30001

        /** Preferences file holding the delegate blacklist. */
        const val PREFERENCES_NAME = "mediapipe_delegate"

        /** Key of the permanent GPU blacklist flag. */
        const val KEY_GPU_BLACKLISTED = "gpu_blacklisted"

        private const val ES3_VERSION = 0x30000

        private fun resolveGlEsVersion(context: Context): Int {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager ?: return ES3_VERSION
            return manager.deviceConfigurationInfo.reqGlEsVersion
        }
    }
}
