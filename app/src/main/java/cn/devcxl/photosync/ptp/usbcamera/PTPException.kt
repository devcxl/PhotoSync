package cn.devcxl.photosync.ptp.usbcamera

/**
 * @author devcxl
 */
open class PTPException : Exception {

    var errorCode: Int

    constructor() : super() {
        errorCode = Response.Undefined
    }

    constructor(errorCode: Int) : super() {
        this.errorCode = errorCode
    }

    constructor(string: String) : super(string) {
        this.errorCode = Response.Undefined
    }

    constructor(string: String, errorCode: Int) : super(string) {
        this.errorCode = errorCode
    }

    constructor(string: String, t: Throwable?) : super(string, t) {
        this.errorCode = Response.Undefined
    }

    constructor(string: String, t: Throwable?, errorCode: Int) : super(string, t) {
        this.errorCode = errorCode
    }
}
