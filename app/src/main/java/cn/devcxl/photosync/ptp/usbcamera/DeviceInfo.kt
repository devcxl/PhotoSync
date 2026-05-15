package cn.devcxl.photosync.ptp.usbcamera

import java.io.PrintStream

/**
 * @author devcxl
 */
open class DeviceInfo(f: NameFactory) : Data(true, ByteArray(0), 0, f) {

    var standardVersion: Int = 0
    @JvmField var vendorExtensionId: Int = 0
    var vendorExtensionVersion: Int = 0
    var vendorExtensionDesc: String? = null

    var functionalMode: Int = 0
    var operationsSupported: IntArray = intArrayOf()
    var eventsSupported: IntArray = intArrayOf()
    var propertiesSupported: IntArray = intArrayOf()

    var captureFormats: IntArray = intArrayOf()
    var imageFormats: IntArray = intArrayOf()
    var manufacturer: String? = null
    var model: String? = null

    var deviceVersion: String? = null
    var serialNumber: String? = null

    private fun supports(supported: IntArray, code: Int): Boolean {
        for (s in supported) {
            if (code == s) return true
        }
        return false
    }

    fun supportsOperation(opCode: Int): Boolean = supports(operationsSupported, opCode)

    fun supportsEvent(eventCode: Int): Boolean = supports(eventsSupported, eventCode)

    fun supportsProperty(propCode: Int): Boolean = supports(propertiesSupported, propCode)

    fun supportsCaptureFormat(formatCode: Int): Boolean = supports(captureFormats, formatCode)

    fun supportsImageFormat(formatCode: Int): Boolean = supports(imageFormats, formatCode)

    private fun addString(out: PrintStream, last: Int, s: String): Int {
        var l = last + s.length
        l++
        return if (l < 80) {
            out.print(s)
            out.print(" ")
            l
        } else {
            out.println()
            out.print("\t")
            out.print(s)
            out.print(" ")
            8 + s.length + 1
        }
    }

    override fun parse() {
        super.parse()
        standardVersion = nextU16()
        vendorExtensionId = nextS32()
        vendorExtensionVersion = nextU16()
        vendorExtensionDesc = nextString()

        functionalMode = nextU16()
        operationsSupported = nextU16Array()
        eventsSupported = nextU16Array()
        propertiesSupported = nextU16Array()

        captureFormats = nextU16Array()
        imageFormats = nextU16Array()
        manufacturer = nextString()
        model = nextString()

        deviceVersion = nextString()
        serialNumber = nextString()
    }

    internal fun lines(out: PrintStream) {
        if (manufacturer != null) out.println("Manufacturer: $manufacturer")
        if (model != null) out.println("Model: $model")
        if (deviceVersion != null) out.println("Device Version: $deviceVersion")
        if (serialNumber != null) out.println("Serial Number: $serialNumber")

        if (functionalMode != 0) {
            out.print("Functional Mode: ")
            out.println(funcMode(functionalMode))
        }

        if (vendorExtensionId != 0) {
            out.print("Extensions (")
            out.print(vendorExtensionId.toString())
            out.print(")")
            if (vendorExtensionDesc != null) {
                out.print(": ")
                out.print(vendorExtensionDesc)
            }
            out.println()
        }
    }

    override fun toString(): String {
        if (operationsSupported.isEmpty()) {
            return "... device info uninitted"
        }
        var result = "DeviceInfo:\n"
        result += "PTP Version: " + (standardVersion / 100) + "." + (standardVersion % 100)

        result += "\n\nOperations Supported:"
        for (op in operationsSupported) {
            result += "\n\t" + factory.getOpcodeString(op)
        }

        result += "\n\nEvents Supported:"
        for (ev in eventsSupported) {
            result += "\n\t" + factory.getEventString(ev)
        }

        result += "\n\nDevice Properties Supported:\n"
        for (prop in propertiesSupported) {
            result += "\t" + factory.getPropertyName(prop)
        }

        result += "\n\nCapture Formats Supported:\n"
        for (fmt in captureFormats) {
            result += "\t" + factory.getFormatString(fmt)
        }

        result += "\n\nImage Formats Supported:\n"
        for (fmt in imageFormats) {
            result += "\t" + factory.getFormatString(fmt)
        }

        if (vendorExtensionId != 0) {
            result += "\n\nVendor Extension, id "
            result += vendorExtensionId.toString()
            result += ", version "
            result += (standardVersion / 100).toString()
            result += "."
            result += (standardVersion % 100).toString()

            if (vendorExtensionDesc != null) {
                result += "\nDescription: $vendorExtensionDesc"
            }
        }
        return result
    }

    companion object {
        fun funcMode(functionalMode: Int): String = when (functionalMode) {
            0 -> "standard"
            1 -> "sleeping"
            else -> {
                val buf = StringBuilder()
                if ((functionalMode and 0x8000) == 0)
                    buf.append("reserved 0x")
                else
                    buf.append("vendor 0x")
                buf.append(Integer.toHexString(functionalMode and 0x8000.inv()))
                buf.toString()
            }
        }
    }
}
