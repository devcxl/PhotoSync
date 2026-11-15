package cn.devcxl.photosync.ptp.usbcamera.sony

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

import cn.devcxl.photosync.ptp.usbcamera.BaselineInitiator
import cn.devcxl.photosync.ptp.usbcamera.Command
import cn.devcxl.photosync.ptp.usbcamera.Data
import cn.devcxl.photosync.ptp.usbcamera.DevicePropDesc
import cn.devcxl.photosync.ptp.usbcamera.PTPException
import cn.devcxl.photosync.ptp.usbcamera.Response
import timber.log.Timber

/**
 * Sony cameras sync through the object-list poll: [pollListSetUp] and
 * [pollListAfterGetStorages] do the SDIO handshake the camera needs before it enumerates
 * its storages.
 *
 * There is deliberately no vendor event implementation here. The former one watched a
 * single "object in memory" property and only ever fetched one hard-coded pseudo-handle,
 * which covers captures held in camera memory and nothing shot to the card.
 *
 * @author devcxl
 */
class SonyInitiator(dev: UsbDevice, connection: UsbDeviceConnection) : BaselineInitiator(dev, connection) {

    protected var PTP_OC_SONY_GetDevicePropdesc: Int = 0x9203
    protected var PTP_OC_SONY_GetSDIOGetExtDeviceInfo: Int = 0x9202

    var sonyExtDeviceInfo: SonyExtDeviceInfo? = null

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
