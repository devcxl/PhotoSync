package cn.devcxl.photosync.ptp.usbcamera

import java.io.PrintStream
import java.util.Vector

/**
 * @author devcxl
 */
open class DevicePropDesc(f: NameFactory) : Data(true, ByteArray(0), 0, f) {

    @JvmField var propertyCode: Int = 0
    @JvmField var dataType: Int = 0
    @JvmField var writable: Boolean = false
    @JvmField var factoryDefault: Any? = null
    @JvmField var currentValue: Any? = null
    @JvmField var formType: Int = 0
    @JvmField var constraints: Any? = null

    init {
        data = ByteArray(0)
        length = 0
    }

    override fun parse() {
        super.parse()
        propertyCode = nextU16()
        dataType = nextU16()
        writable = nextU8() != 0

        factoryDefault = DevicePropValue.get(dataType, this)
        currentValue = DevicePropValue.get(dataType, this)

        formType = nextU8()
        when (formType) {
            0 -> {}
            1 -> {
                constraints = Range(dataType, this)
            }
            2 -> {
                constraints = parseEnumeration()
            }
            else -> {
                System.err.println("ILLEGAL prop desc form, $formType")
                formType = 0
            }
        }
    }

    override fun toString(): String {
        val tv = StringBuilder(factory.getPropertyName(propertyCode))
        tv.append(" = ")
        tv.append(currentValue.toString())
        if (!writable)
            tv.append(", read-only")
        tv.append(", ")
        tv.append(DevicePropValue.getTypeName(dataType))
        when (formType) {
            0 -> {}
            1 -> {
                val r = constraints as Range
                tv.append(" from ")
                tv.append(r.minimum.toString())
                tv.append(" to ")
                tv.append(r.maximum.toString())
                tv.append(" by ")
                tv.append(r.increment.toString())
            }
            2 -> {
                val v = constraints as Vector<*>
                tv.append(" { ")
                for (i in 0 until v.size) {
                    if (i != 0) {
                        tv.append(", ")
                    }
                    tv.append(v.elementAt(i).toString())
                }
                tv.append(" }")
            }
            else -> {
                tv.append(" form ")
                tv.append(formType.toString())
                tv.append(" (error)")
            }
        }
        tv.append(", default ")
        tv.append("\n")
        tv.append("Factory Default:${factoryDefault}")
        return tv.toString()
    }

    override fun dump(out: PrintStream) {
        super.dump(out)
        out.print(factory.getPropertyName(propertyCode))
        out.print(" = ")
        out.print(currentValue)
        if (!writable)
            out.print(", read-only")
        out.print(", ")
        out.print(DevicePropValue.getTypeName(dataType))
        when (formType) {
            0 -> {}
            1 -> {
                val r = constraints as Range
                out.print(" from ")
                out.print(r.minimum)
                out.print(" to ")
                out.print(r.maximum)
                out.print(" by ")
                out.print(r.increment)
            }
            2 -> {
                val v = constraints as Vector<*>
                out.print(" { ")
                for (i in 0 until v.size) {
                    if (i != 0) out.print(", ")
                    out.print(v.elementAt(i))
                }
                out.print(" }")
            }
            else -> {
                out.print(" form ")
                out.print(formType)
                out.print(" (error)")
            }
        }
        out.print(", default ")
        out.println(factoryDefault)
    }

    fun isWritable(): Boolean = writable

    fun getValue(): Any? = currentValue

    fun getDefault(): Any? = factoryDefault

    override fun getCodeName(code: Int): String = factory.getPropertyName(code)

    fun getRange(): Range? = if (formType == 1) constraints as? Range else null

    fun getEnumeration(): Vector<*>? = if (formType == 2) constraints as? Vector<*> else null

    protected fun parseEnumeration(): Vector<Any> {
        var len = nextU16()
        val retval = Vector<Any>(len)
        while (len-- > 0)
            retval.addElement(DevicePropValue.get(dataType, this))
        return retval
    }

    class Range(dataType: Int, desc: DevicePropDesc) {
        val minimum: Any? = DevicePropValue.get(dataType, desc)
        val maximum: Any? = DevicePropValue.get(dataType, desc)
        val increment: Any? = DevicePropValue.get(dataType, desc)
    }

    companion object {
        @JvmField val BatteryLevel: Int = 0x5001
        @JvmField val FunctionalMode: Int = 0x5002
        @JvmField val ImageSize: Int = 0x5003
        @JvmField val CompressionSetting: Int = 0x5004
        @JvmField val WhiteBalance: Int = 0x5005
        @JvmField val RGBGain: Int = 0x5006
        @JvmField val FStop: Int = 0x5007
        @JvmField val FocalLength: Int = 0x5008
        @JvmField val FocusDistance: Int = 0x5009
        @JvmField val FocusMode: Int = 0x500a
        @JvmField val ExposureMeteringMode: Int = 0x500b
        @JvmField val FlashMode: Int = 0x500c
        @JvmField val ExposureTime: Int = 0x500d
        @JvmField val ExposureProgramMode: Int = 0x500e
        @JvmField val ExposureIndex: Int = 0x500f
        @JvmField val ExposureBiasCompensation: Int = 0x5010
        @JvmField val DateTime: Int = 0x5011
        @JvmField val CaptureDelay: Int = 0x5012
        @JvmField val StillCaptureMode: Int = 0x5013
        @JvmField val Contrast: Int = 0x5014
        @JvmField val Sharpness: Int = 0x5015
        @JvmField val DigitalZoom: Int = 0x5016
        @JvmField val EffectMode: Int = 0x5017
        @JvmField val BurstNumber: Int = 0x5018
        @JvmField val BurstInterval: Int = 0x5019
        @JvmField val TimelapseNumber: Int = 0x501a
        @JvmField val TimelapseInterval: Int = 0x501b
        @JvmField val FocusMeteringMode: Int = 0x501c
        @JvmField val UploadURL: Int = 0x501d
        @JvmField val Artist: Int = 0x501e
        @JvmField val CopyrightInfo: Int = 0x501f

        private data class NameMap(val value: Int, val name: String)

        private val names = arrayOf(
            NameMap(BatteryLevel, "BatteryLevel"),
            NameMap(FunctionalMode, "FunctionalMode"),
            NameMap(ImageSize, "ImageSize"),
            NameMap(CompressionSetting, "CompressionSetting"),
            NameMap(WhiteBalance, "WhiteBalance"),
            NameMap(RGBGain, "RGBGain"),
            NameMap(FStop, "FStop"),
            NameMap(FocalLength, "FocalLength"),
            NameMap(FocusDistance, "FocusDistance"),
            NameMap(FocusMode, "FocusMode"),
            NameMap(ExposureMeteringMode, "ExposureMeteringMode"),
            NameMap(FlashMode, "FlashMode"),
            NameMap(ExposureTime, "ExposureTime"),
            NameMap(ExposureProgramMode, "ExposureProgramMode"),
            NameMap(ExposureIndex, "ExposureIndex"),
            NameMap(ExposureBiasCompensation, "ExposureBiasCompensation"),
            NameMap(DateTime, "DateTime"),
            NameMap(CaptureDelay, "CaptureDelay"),
            NameMap(StillCaptureMode, "StillCaptureMode"),
            NameMap(Contrast, "Contrast"),
            NameMap(Sharpness, "Sharpness"),
            NameMap(DigitalZoom, "DigitalZoom"),
            NameMap(EffectMode, "EffectMode"),
            NameMap(BurstNumber, "BurstNumber"),
            NameMap(BurstInterval, "BurstInterval"),
            NameMap(TimelapseNumber, "TimelapseNumber"),
            NameMap(TimelapseInterval, "TimelapseInterval"),
            NameMap(FocusMeteringMode, "FocusMeteringMode"),
            NameMap(UploadURL, "UploadURL"),
            NameMap(Artist, "Artist"),
            NameMap(CopyrightInfo, "CopyrightInfo")
        )

        @JvmStatic
        fun _getPropertyName(code: Int): String {
            for (nm in names)
                if (nm.value == code)
                    return nm.name
            return Container.getCodeString(code)
        }

        @JvmStatic
        fun getPropertyCode(name: String): Int {
            for (nm in names)
                if (nm.name.equals(name, ignoreCase = true))
                    return nm.value
            return Integer.parseInt(name, 16)
        }
    }
}
