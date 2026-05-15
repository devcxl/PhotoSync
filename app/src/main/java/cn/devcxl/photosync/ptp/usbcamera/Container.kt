package cn.devcxl.photosync.ptp.usbcamera

import java.io.PrintStream

/**
 * @author devcxl
 */
abstract class Container(buf: ByteArray, @JvmField internal var factory: NameFactory) : Buffer(buf, buf.size) {

    constructor(buf: ByteArray, len: Int, f: NameFactory) : this(buf, f) {
        length = len
    }

    fun putHeader(len: Int, type: Int, code: Int, xid: Int) {
        if (offset != 0)
            throw IllegalStateException()
        put32(len)
        put16(type)
        put16(code)
        put32(xid)
    }

    override fun toString(): String {
        val temp = StringBuilder()
        val type = getBlockTypeName(blockType)
        val code = getCode()
        temp.append("{ ")
        temp.append(type)
        temp.append("; len ")
        temp.append(getLength().toString())
        temp.append("; ")
        temp.append(getCodeName(code))
        temp.append("; xid ")
        temp.append(getXID().toString())
        if (this is ParamVector) {
            val vec = this as ParamVector
            val nparams = vec.numParams
            if (nparams > 0) {
                temp.append("; ")
                for (i in 0 until nparams) {
                    if (i != 0)
                        temp.append(" ")
                    temp.append("0x")
                    temp.append(Integer.toHexString(vec.getParam(i)))
                }
            }
        }
        temp.append(" }")
        return temp.toString()
    }

    open fun dump(out: PrintStream) {
        out.println(toString())
    }

    open fun parse() {
        offset = HDR_LEN
    }

    open fun getLength(): Int = getS32(0)

    val blockType: Int
        get() = getU16(4)

    fun getCode(): Int = getU16(6)

    open fun getCodeName(code: Int): String = getCodeString(code)

    fun getCodeString(): String = getCodeName(getCode()).intern()

    fun getXID(): Int = getS32(8)

    companion object {
        const val BLOCK_TYPE_COMMAND: Int = 1
        const val BLOCK_TYPE_DATA: Int = 2
        const val BLOCK_TYPE_RESPONSE: Int = 3
        const val BLOCK_TYPE_EVENT: Int = 4

        const val HDR_LEN: Int = 12

        @JvmStatic
        fun getBlockTypeName(type: Int): String = when (type) {
            1 -> "command"
            2 -> "data"
            3 -> "response"
            4 -> "event"
            else -> Integer.toHexString(type).intern()
        }

        @JvmStatic
        fun getCodeType(code: Int): String = when (code shr 12) {
            1 -> "OperationCode"
            2 -> "ResponseCode"
            3 -> "ObjectFormatCode"
            4 -> "EventCode"
            5 -> "DevicePropCode"
            8 + 1 -> "Vendor-OpCode"
            8 + 2 -> "Vendor-ResponseCode"
            8 + 3 -> "Vendor-FormatCode"
            8 + 4 -> "Vendor-EventCode"
            8 + 5 -> "Vendor-PropCode"
            else -> Integer.toHexString(code shr 12).intern()
        }

        @JvmStatic
        fun getCodeString(code: Int): String = Integer.toHexString(code).intern()
    }
}
