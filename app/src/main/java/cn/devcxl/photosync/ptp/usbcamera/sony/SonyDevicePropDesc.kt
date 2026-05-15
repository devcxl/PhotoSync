package cn.devcxl.photosync.ptp.usbcamera.sony

import cn.devcxl.photosync.ptp.usbcamera.Buffer
import cn.devcxl.photosync.ptp.usbcamera.DevicePropDesc
import cn.devcxl.photosync.ptp.usbcamera.DevicePropValue
import cn.devcxl.photosync.ptp.usbcamera.NameFactory

/**
 * @author devcxl
 */
class SonyDevicePropDesc(f: NameFactory, buffer: Buffer) : DevicePropDesc(f) {

    protected var unknown: Int = 0
    private val buf: Buffer = buffer

    init {
        data = buffer.data
        offset = buffer.offset
    }

    override fun parse() {
        super.parse()
        propertyCode = nextU16()
        dataType = nextU16()
        writable = nextU8() != 0

        unknown = nextS8()

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

        buf.offset = offset
    }
}
