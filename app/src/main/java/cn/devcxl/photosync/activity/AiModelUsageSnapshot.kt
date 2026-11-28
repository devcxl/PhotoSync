package cn.devcxl.photosync.activity

import cn.devcxl.photosync.editor.vision.AiModelCatalog
import cn.devcxl.photosync.editor.vision.AiModelDownloadManager
import cn.devcxl.photosync.editor.vision.AiModelProbeResult

/** On-disk and last-probe state of one catalog model as shown by the settings page. */
internal class AiModelState(
    val installed: Boolean,
    val partialBytes: Long,
    val lastProbe: AiModelProbeResult
)

/** Everything the settings page needs to render the usage line and the per-row actions. */
internal class AiModelUsageSnapshot(
    val models: Map<String, AiModelState>,
    val totalBytes: Long
) {
    companion object {

        /** Reads the disk state of every catalog model; blocking, call on an IO dispatcher. */
        fun load(downloadManager: AiModelDownloadManager): AiModelUsageSnapshot {
            val states = AiModelCatalog.onDemandModels.associate { spec ->
                spec.id to AiModelState(
                    installed = downloadManager.isInstalled(spec),
                    partialBytes = downloadManager.partFile(spec)
                        .takeIf { it.isFile }?.length() ?: 0L,
                    lastProbe = downloadManager.lastProbeResult(spec)
                )
            }
            return AiModelUsageSnapshot(states, downloadManager.totalBytesOnDisk())
        }
    }
}
