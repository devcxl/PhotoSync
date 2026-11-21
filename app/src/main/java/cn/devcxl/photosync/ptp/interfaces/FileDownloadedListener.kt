package cn.devcxl.photosync.ptp.interfaces

import java.io.File

/**
 * Called once per object that was fully written to local storage.
 */
fun interface FileDownloadedListener {
    /**
     * @param fileHandle handle of the object on the camera
     * @param localFile the completed local file
     * @param durationMs how long the transfer took
     */
    fun onFileDownloaded(fileHandle: Int, localFile: File, durationMs: Long)
}
