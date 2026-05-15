package cn.devcxl.photosync.ptp.interfaces

import cn.devcxl.photosync.ptp.usbcamera.BaselineInitiator

/**
 * @author devcxl
 */
fun interface FileAddedListener {
    /**
     * 当相机有新的文件被添加时触发。
     *
     * @param bi BaselineInitiator 的子类
     * @param fileHandle 文件句柄
     * @param data 对应的 Event（尼康）或者 Response（佳能）数据
     */
    fun onFileAdded(bi: BaselineInitiator, fileHandle: Int, data: Any?)
}
