package cn.devcxl.photosync.editor

import java.io.File

import cn.devcxl.photosync.editor.vision.IdleReleaseScheduler
import cn.devcxl.photosync.editor.vision.OrtSessionFactory
import cn.devcxl.photosync.editor.vision.OrtSessionHandle

/**
 * Session doubles for the AI degradation matrix.
 *
 * They keep [cn.devcxl.photosync.editor.vision.OrtSessionManager] on the JVM: no `.so` is loaded, the
 * idle scheduler never creates a thread and every native handle records its own `close()`.
 */
internal class RecordingSessionFactory : OrtSessionFactory {

    /** Every handle this factory created, in creation order. */
    val created = mutableListOf<FakeSessionHandle>()

    /** Throwable the next `create` throws; `null` lets the factory succeed. */
    var failure: Throwable? = null

    override fun create(modelFile: File): OrtSessionHandle {
        failure?.let { throw it }
        return FakeSessionHandle(modelFile.absolutePath).also { created += it }
    }
}

/** [OrtSessionHandle] that remembers whether the manager closed it. */
internal class FakeSessionHandle(override val modelPath: String) : OrtSessionHandle {

    var closed: Boolean = false
        private set

    override fun close() {
        closed = true
    }
}

/** Scheduler that never runs a task; the LOW tier must not schedule anything at all. */
internal object NoopIdleScheduler : IdleReleaseScheduler {

    override fun schedule(delayMillis: Long, task: Runnable) = Unit

    override fun cancel() = Unit
}
