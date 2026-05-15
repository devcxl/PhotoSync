package cn.devcxl.photosync.ptp.usbcamera

import android.widget.TextView
import java.io.PrintStream

/**
 * @author devcxl
 */
open class DevicePropValue(tc: Int, f: NameFactory) : Data(true, ByteArray(0), 0, f) {

    var typecode: Int = tc
    var value: Any? = null
        private set

    private constructor(tc: Int, obj: Any, f: NameFactory) : this(tc, f) {
        typecode = tc
        value = obj
    }

    fun getTypeCode(): Int = typecode

    override fun parse() {
        value = get(typecode, this)
    }

    override fun dump(out: PrintStream) {
        out.print("Type: ")
        out.print(getTypeName(typecode))
        out.print(", Value: ")
        out.println(value.toString())
    }

    internal fun showInTextView(tv: TextView) {
        tv.text = "Type: "
        tv.append(getTypeName(typecode))
        tv.append(", Value: ")
        tv.append("\n")
        tv.append(value.toString())
    }

    override fun getCodeName(code: Int): String = getTypeName(code)

    companion object {
        @JvmField val s8: Int = 0x0001
        @JvmField val u8: Int = 0x0002
        @JvmField val s16: Int = 0x0003
        @JvmField val u16: Int = 0x0004
        @JvmField val s32: Int = 0x0005
        @JvmField val u32: Int = 0x0006
        @JvmField val s64: Int = 0x0007
        @JvmField val u64: Int = 0x0008
        @JvmField val s128: Int = 0x0009
        @JvmField val u128: Int = 0x000a
        @JvmField val s8array: Int = 0x4001
        @JvmField val u8array: Int = 0x4002
        @JvmField val s16array: Int = 0x4003
        @JvmField val u16array: Int = 0x4004
        @JvmField val s32array: Int = 0x4005
        @JvmField val u32array: Int = 0x4006
        @JvmField val s64array: Int = 0x4007
        @JvmField val u64array: Int = 0x4008
        @JvmField val s128array: Int = 0x4009
        @JvmField val u128array: Int = 0x400a
        @JvmField val string: Int = 0xffff

        @JvmStatic
        fun get(code: Int, buf: Buffer): Any = when (code) {
            s8 -> buf.nextS8()
            u8 -> buf.nextU8()
            s16 -> buf.nextS16()
            u16 -> buf.nextU16()
            s32 -> buf.nextS32()
            u32 -> 0x0ffFFffFFL and buf.nextS32().toLong()
            s64 -> buf.nextS64()
            u64 -> buf.nextS64()
            s8array -> buf.nextS8Array()
            u8array -> buf.nextU8Array()
            s16array -> buf.nextS16Array()
            u16array -> buf.nextU16Array()
            u32array -> buf.nextS32Array()
            s32array -> buf.nextS32Array()
            u64array -> buf.nextS64Array()
            s64array -> buf.nextS64Array()
            string -> buf.nextString() ?: ""
            else -> throw IllegalArgumentException()
        }

        @JvmStatic
        fun getTypeName(code: Int): String = when (code) {
            s8 -> "s8"
            u8 -> "u8"
            s16 -> "s16"
            u16 -> "u16"
            s32 -> "s32"
            u32 -> "u32"
            s64 -> "s64"
            u64 -> "u64"
            s128 -> "s128"
            u128 -> "u128"
            s8array -> "s8array"
            u8array -> "u8array"
            s16array -> "s16array"
            u16array -> "u16array"
            s32array -> "s32array"
            u32array -> "u32array"
            s64array -> "s64array"
            u64array -> "u64array"
            s128array -> "s128array"
            u128array -> "u128array"
            string -> "string"
            else -> Container.getCodeString(code)
        }
    }
}
