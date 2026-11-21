package cn.devcxl.photosync.ptp.interfaces

/**
 * Progress of one object transfer, reported in coarse steps rather than per USB packet.
 */
fun interface FileTransferListener {
    /**
     * @param fileHandle handle of the object being transferred
     * @param totalBytes size the camera declared for the object
     * @param transferredBytes bytes written to local storage so far
     */
    fun onFileTransfer(fileHandle: Int, totalBytes: Int, transferredBytes: Int)
}
