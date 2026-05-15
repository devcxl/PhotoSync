package cn.devcxl.photosync.ptp.usbcamera.sony

import android.util.Log

import cn.devcxl.photosync.ptp.usbcamera.Data
import cn.devcxl.photosync.ptp.usbcamera.NameFactory

/**
 * @author devcxl
 */
class SonyExtDeviceInfo(f: NameFactory) : Data(true, ByteArray(0), 0, f) {

    var operationsSupported: IntArray = IntArray(0)
    var eventsSupported: IntArray = IntArray(0)
    var propertiesSupported: IntArray = IntArray(0)
    var allSupported: IntArray = IntArray(0)

    fun supportsOperation(opCode: Int): Boolean {
        return supports(operationsSupported, opCode)
    }

    fun supportsEvent(eventCode: Int): Boolean {
        return supports(eventsSupported, eventCode)
    }

    fun supportsProperty(propCode: Int): Boolean {
        return supports(propertiesSupported, propCode)
    }

    private fun supports(supported: IntArray, code: Int): Boolean {
        for (i in supported.indices) {
            if (code == supported[i])
                return true
        }
        return false
    }

    override fun parse() {
        super.parse()
        nextU16()
        allSupported = nextU16Array()

        if (allSupported.size * 2 + 2 + 4 < getLength()) {
            val p2 = nextU16Array()
            val oldLen = allSupported.size
            allSupported = allSupported.copyOf(oldLen + p2.size)

            for (i in p2.indices) {
                allSupported[oldLen + i] = p2[i]
            }
        }

        var opcodes = 0
        var propcodes = 0
        var events = 0
        var j = 0
        var k = 0
        var l = 0

        for (op in allSupported) {
            when (op and 0x7000) {
                0x1000 -> opcodes++
                0x4000 -> events++
                0x5000 -> propcodes++
                else -> Log.d(TAG, "ptp_sony_get_vendorpropcodes() unknown opcode $op")
            }
        }

        operationsSupported = IntArray(opcodes)
        eventsSupported = IntArray(events)
        propertiesSupported = IntArray(propcodes)

        for (op in allSupported) {
            when (op and 0x7000) {
                0x1000 -> operationsSupported[k++] = op
                0x4000 -> eventsSupported[l++] = op
                0x5000 -> propertiesSupported[j++] = op
                else -> {}
            }
        }
    }

    override fun toString(): String {
        var result = "DeviceInfo:\n"
        result += "\n\nOperations Supported:"
        for (op in operationsSupported) {
            result += "\n\t" + factory.getOpcodeString(op)
        }

        result += "\n\nEvents Supported:"
        for (event in eventsSupported) {
            result += "\n\t" + factory.getEventString(event)
        }

        result += "\n\nDevice Properties Supported:\n"
        for (prop in propertiesSupported) {
            result += "\t" + factory.getPropertyName(prop)
        }
        return result
    }

    companion object {
        private const val TAG = "SonyExtDeviceInfo"
    }
}
