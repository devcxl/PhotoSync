package cn.devcxl.photosync.ptp.usbcamera

/**
 * @author devcxl
 */
class PTPUnsupportedException : PTPException {

    constructor(string: String) : super(string)

    constructor(string: String, t: Throwable?) : super(string, t)
}
