package cn.devcxl.photosync.editor.vision

import java.io.File

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession

/**
 * [OrtSessionFactory] backed by ONNX Runtime's CPU and XNNPACK execution providers.
 *
 * The environment is a process-wide singleton owned by the library; this factory only borrows it.
 * NNAPI and QNN are never registered (see [OrtSessionManager]).
 *
 * @param intraOpThreads Thread count for both ORT and XNNPACK; defaults to two, which keeps a
 *   single inference from starving the UI thread on big.LITTLE phones.
 */
class OnnxRuntimeSessionFactory(
    private val intraOpThreads: Int = DEFAULT_INTRA_OP_THREADS
) : OrtSessionFactory {

    override fun create(modelFile: File): OrtSessionHandle {
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        try {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(intraOpThreads)
            options.addXnnpack(mapOf("intra_op_num_threads" to intraOpThreads.toString()))
            val session = environment.createSession(modelFile.absolutePath, options)
            return OnnxRuntimeSessionHandle(modelFile.absolutePath, session)
        } finally {
            options.close()
        }
    }

    private companion object {
        const val DEFAULT_INTRA_OP_THREADS = 2
    }
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
