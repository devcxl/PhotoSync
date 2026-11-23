package cn.devcxl.photosync.ptp.camera

/** The handles one or more storages report, and whether every storage answered. */
internal data class ObjectHandles(
    val handles: List<Int>,
    val complete: Boolean,
)
