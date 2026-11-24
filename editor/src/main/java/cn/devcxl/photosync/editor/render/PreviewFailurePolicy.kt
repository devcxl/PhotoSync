package cn.devcxl.photosync.editor.render

import java.io.IOException

import kotlinx.coroutines.CancellationException

import timber.log.Timber

/**
 * Failure policy of the background preview preparation (source analysis + LUT load).
 *
 * Preparation happens **before** the source lease is handed to the GPU job, so it owns the lease and
 * must release it on every failure path. The policy restores the contract the CPU-only renderer had:
 * an expected failure is logged and yields `null` (the caller keeps the previous preview instead of
 * crashing), while cancellation and unexpected `Error`s keep propagating.
 */
internal object PreviewFailurePolicy {

    /**
     * Runs [prepare] and releases [lease] unless it succeeds.
     *
     * @return The prepared request, or `null` when preparation failed recoverably. On `null` the lease
     *   has already been released; on success the caller transfers it to the GPU dispatch.
     * @throws CancellationException when the calling coroutine was cancelled (lease released).
     * @throws Throwable when the failure is not an expected resource problem (lease released).
     */
    suspend fun <T : Any> prepareOrRelease(lease: AutoCloseable, prepare: suspend () -> T): T? = try {
        prepare()
    } catch (cancelled: CancellationException) {
        lease.close()
        throw cancelled
    } catch (failure: Throwable) {
        lease.close()
        when (failure) {
            is OutOfMemoryError -> {
                Timber.e(failure, "OOM while preparing the background preview")
                null
            }
            is IOException -> {
                Timber.e(failure, "I/O failed while preparing the background preview")
                null
            }
            is RuntimeException -> {
                Timber.e(failure, "Background preview preparation failed")
                null
            }
            else -> throw failure
        }
    }
}
