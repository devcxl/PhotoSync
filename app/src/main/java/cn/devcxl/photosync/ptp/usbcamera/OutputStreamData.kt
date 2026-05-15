package cn.devcxl.photosync.ptp.usbcamera

import java.io.IOException
import java.io.OutputStream

/**
 * @author devcxl
 */
class OutputStreamData(private val out: OutputStream, f: NameFactory) : Data(true, ByteArray(0), 0, f) {

    init {
        data = ByteArray(0)
        length = 0
    }

    @Throws(IOException::class)
    fun write(buf: ByteArray, off: Int, len: Int) {
        out.write(buf, off, len)
    }

    @Throws(IOException::class)
    fun close() {
        out.close()
    }

    final override fun parse() {}
}
