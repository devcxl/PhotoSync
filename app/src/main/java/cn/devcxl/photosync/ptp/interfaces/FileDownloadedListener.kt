package cn.devcxl.photosync.ptp.interfaces

import cn.devcxl.photosync.ptp.usbcamera.BaselineInitiator
import java.io.File

/**
 * @author devcxl
 */
fun interface FileDownloadedListener {
    /**
     * @param fileHandle 相机的 file handle
     * @param localFile 下载到本地的文件
     * @param timeduring 所花的时间
     */
    fun onFileDownloaded(bi: BaselineInitiator, fileHandle: Int, localFile: File, timeduring: Long)
}
