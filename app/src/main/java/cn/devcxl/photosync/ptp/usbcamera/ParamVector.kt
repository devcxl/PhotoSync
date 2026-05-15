package cn.devcxl.photosync.ptp.usbcamera

import java.io.PrintStream

/**
 * @author devcxl
 */
open class ParamVector(buf: ByteArray, f: NameFactory) : Container(buf, buf.size, f) {

    constructor(buf: ByteArray, len: Int, f: NameFactory) : this(buf, f) {
        length = len
    }

    val param1: Int get() = getS32(12)
    val param2: Int get() = getS32(16)
    val param3: Int get() = getS32(20)

    val numParams: Int get() = (length - MIN_LEN) / 4

    internal fun getParam(i: Int): Int = getS32(12 + (4 * i))

    override fun dump(out: PrintStream) {
        out.print(toString())
    }

    companion object {
        const val MIN_LEN: Int = Container.HDR_LEN
        const val MAX_LEN: Int = 32
    }
}
