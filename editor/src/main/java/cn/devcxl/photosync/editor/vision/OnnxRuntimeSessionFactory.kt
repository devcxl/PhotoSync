package cn.devcxl.photosync.editor.vision

import java.io.File

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession

/**
 * Cross-run caches ONNX Runtime may keep for the lifetime of one session.
 *
 * Both caches make repeated inference cheaper by reusing reserved memory, but they also keep that
 * memory in the process after the session closes. On a Redmi K20 Pro, MI-GAN 512 peaked at 1.09 GiB
 * with both enabled and 0.78 GiB with both disabled, warm inference stayed within run-to-run noise
 * (~1%) and the process kept ~300 MiB less after close, so [DISABLED] is what ships; see
 * `docs/research/2026-11-26-p0-ai-foundation-gate-record.md` §2.2.
 */
data class OrtSessionCachePolicy(
    val cpuMemArena: Boolean,
    val memoryPattern: Boolean
) {
    companion object {

        /** ONNX Runtime's own defaults, i.e. the configuration measured before the P2 memory work. */
        val ORT_DEFAULT = OrtSessionCachePolicy(cpuMemArena = true, memoryPattern = true)

        /** Shipping policy: no cross-run caches, the lowest peak and retained RSS measured. */
        val DISABLED = OrtSessionCachePolicy(cpuMemArena = false, memoryPattern = false)
    }
}

/**
 * [OrtSessionFactory] backed by ONNX Runtime's CPU and XNNPACK execution providers.
 *
 * The environment is a process-wide singleton owned by the library; this factory only borrows it.
 * NNAPI and QNN are never registered (see [OrtSessionManager]).
 *
 * @param cachePolicy Cross-run caches ORT may keep; defaults to [OrtSessionCachePolicy.DISABLED]
 *   because peak/retained RSS is the binding constraint for on-device inpainting.
 */
class OnnxRuntimeSessionFactory(
    internal val cachePolicy: OrtSessionCachePolicy = OrtSessionCachePolicy.DISABLED
) : OrtSessionFactory {

    override fun create(modelFile: File, intraOpThreads: Int): OrtSessionHandle {
        val threads = intraOpThreads.coerceIn(
            OrtSessionRuntimeConfig.MIN_INTRA_OP_THREADS,
            OrtSessionRuntimeConfig.MAX_INTRA_OP_THREADS
        )
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        try {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(threads)
            options.setCPUArenaAllocator(cachePolicy.cpuMemArena)
            options.setMemoryPatternOptimization(cachePolicy.memoryPattern)
            options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            val session = environment.createSession(modelFile.absolutePath, options)
            return OnnxRuntimeSessionHandle(modelFile.absolutePath, session)
        } finally {
            options.close()
        }
    }
}

/**
 * The ONNX Runtime version shipped in this build.
 *
 * The AAR exposes no version constant that can be read without creating an
 * [ai.onnxruntime.OrtEnvironment], and the diagnostics page must never initialize the native runtime
 * just to display a number. Keep [VERSION] in sync with `onnxruntime-android` in
 * `gradle/libs.versions.toml`; `OnnxRuntimeInfoTest` fails when the two drift apart.
 */
object OnnxRuntimeInfo {

    /** Pinned ONNX Runtime version; see `gradle/libs.versions.toml`. */
    const val VERSION = "1.22.0"
}

/**
 * [OrtSessionHandle] that owns a native [OrtSession].
 *
 * Inference engines in `:editor` access [session] directly; callers outside the module should only
 * treat it as an [OrtSessionHandle] and let [OrtSessionManager] own its lifetime.
 */
class OnnxRuntimeSessionHandle(
    override val modelPath: String,
    val session: OrtSession
) : OrtSessionHandle {

    override fun close() = session.close()
}
