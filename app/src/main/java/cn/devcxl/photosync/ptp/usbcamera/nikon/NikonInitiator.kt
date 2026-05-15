// Copyright 2000 by David Brownell <dbrownell@users.sourceforge.net>
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
package cn.devcxl.photosync.ptp.usbcamera.nikon

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log

import cn.devcxl.photosync.ptp.usbcamera.BaselineInitiator
import cn.devcxl.photosync.ptp.usbcamera.Command
import cn.devcxl.photosync.ptp.usbcamera.Container
import cn.devcxl.photosync.ptp.usbcamera.Data
import cn.devcxl.photosync.ptp.usbcamera.DevicePropDesc
import cn.devcxl.photosync.ptp.usbcamera.PTPException
import cn.devcxl.photosync.ptp.usbcamera.Response

/**
 * This supports all standardized PTP-over-USB operations, including
 * operations (and modes) that are optional for all responders.
 * Filtering operations invoked on this class may be done on the device,
 * or may be emulated on the client side.
 * At this time, not all standardized operations are supported.
 *
 * @author devcxl
 */
class NikonInitiator(dev: UsbDevice, connection: UsbDeviceConnection) :
    BaselineInitiator(dev, connection) {

    companion object {
        const val NIKON_VID = 1200

        @JvmField
        var eventListenerRunning: Boolean = false
    }

    /**
     * Fills out the provided device property description.
     *
     * @param propcode code identifying the property of interest
     * @param desc description to be filled; it may be a subtype
     *  associated with with domain-specific methods
     * @return response code
     */
    @Throws(PTPException::class)
    fun getDevicePropDesc(propcode: Int, desc: DevicePropDesc): Int {
        return transact1(Command.GetDevicePropDesc, desc, propcode).getCode()
    }

    /**
     * Starts the capture of one (or more) new
     * data objects, according to current device properties.
     * The capture will complete without issuing further commands.
     *
     * @param storageId Where to store the object(s), or zero to
     *  let the device choose.
     * @param formatCode Type of object(s) to capture, or zero to
     *  use the device default.
     * @return status code indicating whether capture started;
     *  CaptureComplete events provide capture status, and
     *  ObjectAdded events provide per-object status.
     */
    @Throws(PTPException::class)
    override fun initiateCapture(storageId: Int, formatCode: Int): Response? {
        var resp: Response? = null

        if (!info!!.supportsOperation(Command.InitiateCapture)) {
            Log.d(BaselineInitiator.TAG, "The camera does not support Nikon capture")
            throw PTPException("The camera does not support Nikon capture")
        }

        var ret: Int

        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        resp = transact0(Command.InitiateCapture, null)
        ret = resp.getCode()
        Log.d(
            BaselineInitiator.TAG,
            "  NK_OC_Capture Response code: 0x" + Integer.toHexString(ret) + "  OK: " + (ret == Response.OK)
        )
        if (ret != Response.OK) {
            val msg = "NK_OC_Capture  Capture failed to release: Unknown error " +
                ret +
                " , please report."
            Log.d(BaselineInitiator.TAG, msg)
            throw PTPException(msg, ret)
        }

        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        return resp
    }

    /**
     * Retrieves a chunk of the object identified by the given object id.
     *
     * @param oid object id
     * @param offset the offset to start from
     * @param size the number of bytes to transfer
     * @param data the Data object receiving the object
     * @throws PTPException in case of errors
     */
    @Throws(PTPException::class)
    fun getPartialObject(oid: Int, offset: Int, size: Int, data: Data) {
        val ret =
            transact3(Command.EosGetPartialObject, data, oid, offset, size)

        if (ret.getCode() != Response.OK) {
            throw PTPException("Error reading new object", ret.getCode())
        }
    }

    @Throws(PTPException::class)
    fun transferComplete(oid: Int) {
        val ret =
            transact1(Command.EosTransferComplete, null, oid)

        if (ret.getCode() != Response.OK) {
            throw PTPException("Error reading new object", ret.getCode())
        }
    }

    @Throws(PTPException::class)
    fun setDevicePropValueNikon(property: Int, value: Int): Response {
        val buff = ByteArray(0x14)
        val data = Data(false, buff, buff.size, this)
        data.offset = 0
        data.putHeader(buff.size, Container.BLOCK_TYPE_DATA, Command.SetDevicePropValue, 0)
        data.put32(value)
        val command = Command(Command.SetDevicePropValue, session, property)
        writeExtraData(command, data, BaselineInitiator.DEFAULT_TIMEOUT)

        val buf = ByteArray(inMaxPS)
        val len = mConnection!!.bulkTransfer(epIn, buf, inMaxPS, BaselineInitiator.DEFAULT_TIMEOUT)
        val response = Response(buf, len, this)
        return response
    }

    @Throws(PTPException::class)
    fun getDevicePropValue(propcode: Int, desc: DevicePropDesc): Int {
        return transact1(Command.GetDevicePropValue, desc, propcode).getCode()
    }

    @Throws(PTPException::class)
    override fun getPropValue(value: Int): DevicePropDesc {
        val command = Command(Command.GetDevicePropDesc, session, value)
        mConnection!!.bulkTransfer(epOut, command.data, command.length, BaselineInitiator.DEFAULT_TIMEOUT)

        var buf = ByteArray(inMaxPS)
        var lengthOfBytes = mConnection!!.bulkTransfer(epIn, buf, inMaxPS, BaselineInitiator.DEFAULT_TIMEOUT)

        val info = DevicePropDesc(this)
        info.data = buf
        info.length = info.getLength()
        info.parse()

        val response1 = Response(buf, inMaxPS, this)

        buf = ByteArray(inMaxPS)
        lengthOfBytes = mConnection!!.bulkTransfer(epIn, buf, inMaxPS, BaselineInitiator.DEFAULT_TIMEOUT)

        val response2 = Response(buf, inMaxPS, this)

        return info
    }

    @Throws(PTPException::class)
    fun moveFocus(step: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    override fun setExposure(exposure: Int): Response {
        return setDevicePropValueNikon(NikonEventConstants.PTP_DPC_ExposureBiasCompensation, exposure)
    }

    @Throws(PTPException::class)
    override fun setISO(value: Int): Response {
        return setDevicePropValueNikon(NikonEventConstants.PTP_DPC_ExposureIndex, value)
    }

    @Throws(PTPException::class)
    override fun setAperture(value: Int): Response {
        return setDevicePropValueNikon(NikonEventConstants.PTP_DPC_FNumber, value)
    }

    @Throws(PTPException::class)
    override fun setPictureStyle(value: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    override fun setWhiteBalance(value: Int): Response {
        return setDevicePropValueNikon(NikonEventConstants.PTP_DPC_WhiteBalance, value)
    }

    @Throws(PTPException::class)
    override fun setDriveMode(value: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    override fun setMetering(value: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    override fun setImageQuality(value: Int): Response? {
        return null
    }

    @Throws(PTPException::class)
    override fun setShutterSpeed(speed: Int): Response {
        return setDevicePropValueNikon(NikonEventConstants.PTP_DPC_ExposureTime, speed)
    }

    fun setPropValue(property: Int, value: String): Response {
        val lengthString = value.length + 1
        val buff = ByteArray(0x12 + lengthString + lengthString + 1)
        val data = Data(false, buff, buff.size, this)
        data.offset = 0
        data.putHeader(buff.size, Container.BLOCK_TYPE_DATA, Command.SetDevicePropValue, 0)

        data.putString(value)
        val command = Command(Command.SetDevicePropValue, session, property)
        writeExtraData(command, data, BaselineInitiator.DEFAULT_TIMEOUT)

        val buf = ByteArray(inMaxPS)
        val len = mConnection!!.bulkTransfer(epIn, buf, inMaxPS, BaselineInitiator.DEFAULT_TIMEOUT)
        val response = Response(buf, len, this)
        return response
    }
}
