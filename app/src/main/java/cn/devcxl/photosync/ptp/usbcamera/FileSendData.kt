package cn.devcxl.photosync.ptp.usbcamera

import java.io.IOException
import java.io.InputStream
import java.net.URLConnection

/**
 * @author devcxl
 */
class FileSendData(data: URLConnection, f: NameFactory) : Data(false, ByteArray(128 * 1024), f) {
    private val input: InputStream = data.getInputStream()
    private val filesize: Int = data.contentLength

    init {
        `in` = false
    }

    override fun getLength(): Int = Container.HDR_LEN + filesize

    @Throws(IOException::class)
    fun read(buf: ByteArray, off: Int, len: Int): Int = input.read(buf, off, len)

    @Throws(IOException::class)
    fun close() {
        input.close()
    }
}
