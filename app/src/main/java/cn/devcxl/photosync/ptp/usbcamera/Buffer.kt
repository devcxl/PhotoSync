package cn.devcxl.photosync.ptp.usbcamera

/**
 * @author devcxl
 */
open class Buffer {
    @JvmField var data: ByteArray = ByteArray(0)
    @JvmField var length: Int = 0
    @JvmField var offset: Int = 0

    constructor(buf: ByteArray?) {
        this.data = buf ?: ByteArray(0)
        this.length = buf?.size ?: 0
        this.offset = 0
    }

    constructor(buf: ByteArray?, len: Int) {
        require(!(buf == null && len != 0))
        this.data = buf ?: ByteArray(0)
        this.length = len
        this.offset = 0
    }

    fun getS8(index: Int): Int = data[index].toInt()

    fun getU8(index: Int): Int = data[index].toInt() and 0xff

    protected fun put8(value: Int) {
        data[offset++] = value.toByte()
    }

    fun nextS8(): Int = data[offset++].toInt()

    fun nextU8(): Int = data[offset++].toInt() and 0xff

    fun nextS8Array(): IntArray {
        val len = nextS32()
        return IntArray(len) { nextS8() }
    }

    fun nextU8Array(): IntArray {
        val len = nextS32()
        return IntArray(len) { nextU8() }
    }

    fun getS16(index: Int): Int {
        var i = index
        var retval = data[i++].toInt() and 0xff
        retval = retval or (data[i].toInt() shl 8)
        return retval
    }

    fun getU16(index: Int): Int {
        var i = index
        var retval = data[i++].toInt() and 0xff
        retval = retval or (0xff00 and (data[i].toInt() shl 8))
        return retval
    }

    fun put16(value: Int) {
        data[offset++] = value.toByte()
        data[offset++] = (value shr 8).toByte()
    }

    fun nextS16(): Int {
        val retval = getS16(offset)
        offset += 2
        return retval
    }

    fun nextU16(): Int {
        val retval = getU16(offset)
        offset += 2
        return retval
    }

    fun nextS16Array(): IntArray {
        val len = nextS32()
        return IntArray(len) { nextS16() }
    }

    fun nextU16Array(): IntArray {
        val len = nextS32()
        return IntArray(len) { nextU16() }
    }

    fun getS32(index: Int): Int {
        var i = index
        var retval = data[i++].toInt() and 0xff
        retval = retval or ((data[i++].toInt() and 0xff) shl 8)
        retval = retval or ((data[i++].toInt() and 0xff) shl 16)
        retval = retval or (data[i].toInt() shl 24)
        return retval
    }

    fun put32(value: Int) {
        data[offset++] = value.toByte()
        data[offset++] = (value shr 8).toByte()
        data[offset++] = (value shr 16).toByte()
        data[offset++] = (value shr 24).toByte()
    }

    fun nextS32(): Int {
        val retval = getS32(offset)
        offset += 4
        return retval
    }

    fun nextS32Array(): IntArray {
        val len = nextS32()
        return IntArray(len) { nextS32() }
    }

    fun getS64(index: Int): Long {
        var retval = (0xffffffffL and getS32(index).toLong())
        retval = retval or (getS32(index + 4).toLong() shl 32)
        return retval
    }

    protected fun put64(value: Long) {
        put32(value.toInt())
        put32((value shr 32).toInt())
    }

    fun nextS64(): Long {
        val retval = getS64(offset)
        offset += 8
        return retval
    }

    fun nextS64Array(): LongArray {
        val len = nextS32()
        return LongArray(len) { nextS64() }
    }

    fun getString(index: Int): String? {
        val savedOffset = offset
        offset = index
        val retval = nextString()
        offset = savedOffset
        return retval
    }

    fun putString(s: String?) {
        if (s == null) {
            put8(0)
            return
        }
        val len = s.length
        require(len <= 254)
        put8(len + 1)
        for (i in 0 until len) {
            put16(s[i].code)
        }
        put16(0)
    }

    fun nextString(): String? {
        val len = nextU8()
        if (len == 0) return null
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            sb.append(nextU16().toChar())
        }
        sb.setLength(len - 1)
        return sb.toString()
    }

    fun dump() {
        println(data.joinToString(" ") { "%1$02X".format(it) })
        for (i in data.indices) {
            if (i % 8 == 0) {
                println()
            }
            print("%1$02X ".format(data[i]))
        }
    }

    protected fun getUS32(index: Int): Long {
        var i = index
        var retval = (data[i++].toInt() and 0xff).toLong()
        retval = retval or ((data[i++].toInt() and 0xff).toLong() shl 8)
        retval = retval or ((data[i++].toInt() and 0xff).toLong() shl 16)
        retval = retval or ((data[i].toInt() and 0xff).toLong() shl 24)
        return retval
    }

    protected fun nextUS32(): Long {
        val retval = getUS32(offset)
        offset += 4
        return retval
    }

    protected fun nextUS32Array(): LongArray {
        val len = nextS32()
        return LongArray(len) { nextUS32() }
    }
}
