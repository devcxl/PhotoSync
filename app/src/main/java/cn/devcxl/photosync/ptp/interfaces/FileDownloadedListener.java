package cn.devcxl.photosync.ptp.interfaces;

import java.io.File;

import cn.devcxl.photosync.ptp.usbcamera.BaselineInitiator;

/**
 * @author devcxl
 */
public interface FileDownloadedListener {
    /**
     *
     * @param bi
     * @param fileHandle 相机的file handle
     * @param localFile 下载到本地额文件
     * @param timeduring 所花的时间
     */
    void onFileDownloaded(BaselineInitiator bi, int fileHandle, File localFile, long timeduring);
}
