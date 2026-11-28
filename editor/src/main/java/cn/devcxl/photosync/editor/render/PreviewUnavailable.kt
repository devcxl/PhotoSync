package cn.devcxl.photosync.editor.render

import timber.log.Timber

/**
 * Logs why the offscreen preview must not publish and returns `false` for expression use.
 *
 * Every caller answers a `null` write with the CPU renderer, so a reason string is the whole
 * contract between the GPU stages and their orchestrator.
 */
internal fun previewUnavailable(reason: String): Boolean {
    Timber.w("Offscreen preview: %s; using the CPU renderer", reason)
    return false
}
