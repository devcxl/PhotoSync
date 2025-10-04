package cn.devcxl.photosync.ptp.interfaces;

import cn.devcxl.photosync.ptp.usbcamera.BaselineInitiator;
import cn.devcxl.photosync.ptp.usbcamera.Container;

/**
 * Created by rainx on 2017/5/7.
 */

public interface FileTransferListener {
    /**
     *
     * @param bi
     * @param fileHandle
     * @param totalByteLength 文件总长度
     * @param transterByteLength 文件已传输长度
     */
    void onFileTranster(BaselineInitiator bi, int fileHandle, int totalByteLength, int transterByteLength);
}
