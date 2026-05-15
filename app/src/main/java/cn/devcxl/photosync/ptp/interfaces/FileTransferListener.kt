package cn.devcxl.photosync.ptp.interfaces

import cn.devcxl.photosync.ptp.usbcamera.BaselineInitiator

/**
 * @author devcxl
 */
fun interface FileTransferListener {
    /**
     * @param totalByteLength 文件总长度
     * @param transterByteLength 文件已传输长度
     */
    fun onFileTranster(
        bi: BaselineInitiator,
        fileHandle: Int,
        totalByteLength: Int,
        transterByteLength: Int
    )
}
