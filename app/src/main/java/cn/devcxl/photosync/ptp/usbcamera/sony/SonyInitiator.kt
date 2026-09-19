package cn.devcxl.photosync.ptp.usbcamera.sony

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

import cn.devcxl.photosync.ptp.usbcamera.BaselineInitiator
import cn.devcxl.photosync.ptp.usbcamera.Command
import cn.devcxl.photosync.ptp.usbcamera.Data
import cn.devcxl.photosync.ptp.usbcamera.DevicePropDesc
import cn.devcxl.photosync.ptp.usbcamera.ObjectInfo
import cn.devcxl.photosync.ptp.usbcamera.PTPException
import cn.devcxl.photosync.ptp.usbcamera.Response
import cn.devcxl.photosync.ptp.usbcamera.Session
import timber.log.Timber

/**
 * @author devcxl
 */
class SonyInitiator(dev: UsbDevice, connection: UsbDeviceConnection) : BaselineInitiator(dev, connection) {

    protected override var OBJECT_ADDED_EVENT_CODE: Int = 0xc201
    protected var PTP_OC_SONY_GetAllDevicePropData: Int = 0x9209
    protected var PTP_DPC_SONY_ObjectInMemory: Int = 0xD215
    protected var PTP_OC_SONY_GetDevicePropdesc: Int = 0x9203
    protected var PTP_OC_SONY_GetSDIOGetExtDeviceInfo: Int = 0x9202

    var sonyExtDeviceInfo: SonyExtDeviceInfo? = null

    protected override fun getObjectAddedEventCode(): Int {
        return OBJECT_ADDED_EVENT_CODE
    }

    protected fun runEventPoll_NOTUSE() {
        Timber.tag("PTP_EVENT").v("开始event轮询")
        var loopTimes: Long = 0
        pollEventSetUp()
        val buffer = ByteArray(intrMaxPS)
        while (isSessionActive()) {
            loopTimes++
            if (!autoPollEvent || mConnection == null) {
                try {
                    Thread.sleep(DEFAULT_TIMEOUT.toLong())
                } catch (e: InterruptedException) {
                    Timber.w(e, "runEventPoll_NOTUSE failed")
                    return
                }
                continue
            }

            if (loopTimes % 100 == 0L) {
                try {
                    Thread.sleep(DEFAULT_TIMEOUT.toLong())
                } catch (e: InterruptedException) {
                    Timber.w(e, "runEventPoll_NOTUSE failed")
                    return
                }
            }

            getAllDevicePropDesc()
            val props = getAllDevicePropDesc()
            if (props == null) {
                return
            }
            for (prop in props) {
                if (prop.propertyCode == PTP_DPC_SONY_ObjectInMemory) {
                    if ((prop.getValue() as Int) > 0x8000) {
                        Timber.d("SONY ObjectInMemory count change seen, retrieving file")
                        val info = getObjectInfo(0xffffc001.toInt())
                        processFileAddEvent(0xffffc001.toInt(), info)
                    } else {
                        Timber.d(
                            "current prop.value of PTP_DPC_SONY_ObjectInMemory is %s",
                            Integer.toHexString(prop.getValue() as Int)
                        )
                    }
                }
            }
        }

        Timber.tag("PTP_EVENT").v("结束轮询")
    }

    protected override fun waitVendorSpecifiedFileReadySignal(): Any? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 15000) {
            getAllDevicePropDesc()
            val props = getAllDevicePropDesc()
            if (props == null) {
                return null
            }
            for (prop in props) {
                if (prop.propertyCode == PTP_DPC_SONY_ObjectInMemory) {
                    if ((prop.getValue() as Int) > 0x8000) {
                        try {
                            val info = getObjectInfo(0xffffc001.toInt())
                            return info
                        } catch (e: PTPException) {
                            Timber.w(e, "waitVendorSpecifiedFileReadySignal failed")
                        }
                        Timber.d("SONY ObjectInMemory count change seen, retrieving file")
                    } else {
                        Timber.d(
                            "current PTP_DPC_SONY_ObjectInMemory is %s",
                            Integer.toHexString(prop.getValue() as Int)
                        )
                    }
                }
            }
        }
        Timber.d("Sony waitVendorSpecifiedFileReadySignal timeout!")
        return null
    }

    protected fun waitVendorSpecifiedFileReadySignal1() {
        try {
            Thread.sleep(2000L)
        } catch (e: InterruptedException) {
            Timber.w(e, "waitVendorSpecifiedFileReadySignal1 failed")
        }
    }

    fun getAllDevicePropDesc(): List<DevicePropDesc>? {
        val data = Data(this)

        val props: MutableList<DevicePropDesc> = ArrayList()

        synchronized(session) {
            try {
                transact0(PTP_OC_SONY_GetAllDevicePropData, data)
                if (data == null) {
                    Timber.d("data is null")
                    return@getAllDevicePropDesc null
                }
                if (data.getLength() < 8) {
                    Timber.d("data length is short than 8")
                    return@getAllDevicePropDesc null
                }

                Timber.d(
                    "PTP_OC_SONY_GetAllDevicePropData recv data is : %s",
                    BaselineInitiator.byteArrayToHex(data.data)
                )

                data.offset = 12 + 8
                while (data.getLength() - data.offset > 0) {
                    val desc = SonyDevicePropDesc(this, data)
                    try {
                        desc.parse()
                    } catch (e: Exception) {
                        Timber.w(e, "getAllDevicePropDesc failed")
                        break
                    }
                    props.add(desc)
                }
                return@getAllDevicePropDesc props
            } catch (e: PTPException) {
                Timber.w(e, "getAllDevicePropDesc failed")
                return@getAllDevicePropDesc null
            }
        }
    }

    fun getDevicePropDesc(propcode: Int): DevicePropDesc? {
        val data = Data(this)
        try {
            transact1(PTP_OC_SONY_GetDevicePropdesc, data, propcode)
            data.toString()
            return null
        } catch (e: PTPException) {
            Timber.w(e, "getDevicePropDesc failed")
            return null
        }
    }

    fun setSDIOConnect(mode: Int): Response? {
        Timber.d("set setSDIOConnect :$mode")
        val data = Data(this)
        synchronized(session) {
            try {
                val response = transact1(Command.SONY_SDIOCOMMAND, data, mode)
                return@setSDIOConnect response
            } catch (e: PTPException) {
                Timber.w(e, "setSDIOConnect failed")
                return@setSDIOConnect null
            }
        }
    }

    @Throws(PTPException::class)
    override fun pollEventSetUp() {
        super.pollEventSetUp()
        try {
            getDeviceInfo()
        } catch (e: PTPException) {
            Timber.w(e, "pollEventSetUp failed")
        }
        setSDIOConnect(0x01)
        setSDIOConnect(0x02)
        sendSonyGetExtDeviceInfoCommand()
        setSDIOConnect(0x03)
    }

    private fun sendSonyGetExtDeviceInfoCommand() {
        val data = SonyExtDeviceInfo(this)
        try {
            transact1(PTP_OC_SONY_GetSDIOGetExtDeviceInfo, data, 0xc8)
            try {
                data.parse()
                sonyExtDeviceInfo = data
                Timber.d(sonyExtDeviceInfo.toString())
            } catch (e: Exception) {
                Timber.w(e, "sendSonyGetExtDeviceInfoCommand failed")
            }
        } catch (e: PTPException) {
            Timber.w(e, "sendSonyGetExtDeviceInfoCommand failed")
        }
    }

    override fun pollListSetUp() {
        super.pollListSetUp()
        try {
            getDeviceInfo()
        } catch (e: PTPException) {
            Timber.w(e, "pollListSetUp failed")
        }
        setSDIOConnect(0x01)
        setSDIOConnect(0x02)
        sendSonyGetExtDeviceInfoCommand()
    }

    override fun pollListAfterGetStorages(ids: IntArray) {
        Timber.v("pollListAfterGetStorages : get storages : ${ids?.contentToString()}")
        setSDIOConnect(0x03)
    }

    @Throws(PTPException::class)
    override fun openSession() {
        Timber.d("claimInterface")
        mConnection!!.claimInterface(intf!!, false)
        super.openSession()
    }

    @Throws(PTPException::class)
    override fun close() {
        super.close()
    }
}
