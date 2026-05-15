package cn.devcxl.photosync.ptp.usbcamera

/**
 * @author devcxl
 */
class PTPBusyException : PTPException {

    constructor() : super("Device is busy")
}
