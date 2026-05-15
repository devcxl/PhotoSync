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
package cn.devcxl.photosync.ptp.usbcamera.eos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import android.widget.ImageView
import cn.devcxl.photosync.ptp.params.SyncParams
import cn.devcxl.photosync.ptp.usbcamera.BaselineInitiator
import cn.devcxl.photosync.ptp.usbcamera.Command
import cn.devcxl.photosync.ptp.usbcamera.Container
import cn.devcxl.photosync.ptp.usbcamera.Data
import cn.devcxl.photosync.ptp.usbcamera.DevicePropDesc
import cn.devcxl.photosync.ptp.usbcamera.PTPException
import cn.devcxl.photosync.ptp.usbcamera.PTPUnsupportedException
import cn.devcxl.photosync.ptp.usbcamera.Response
import java.io.ByteArrayInputStream

/**
 * This supports all standardized PTP-over-USB operations, including
 * operations (and modes) that are optional for all responders.
 * Filtering operations invoked on this class may be done on the device,
 * or may be emulated on the client side.
 * At this time, not all standardized operations are supported.
 *
 * @author David Brownell
 * @author devcxl
 */
open class EosInitiator @Throws(PTPException::class) constructor(
    dev: UsbDevice,
    connection: UsbDeviceConnection
) : BaselineInitiator(dev, connection) {

    companion object {
        const val CANON_VID: Int = 1193

        @JvmField
        var eventListenerRunning: Boolean = false
    }

    fun getDevicePropDesc(propcode: Int, desc: DevicePropDesc): Int {
        return transact1(Command.GetDevicePropDesc, desc, propcode).getCode()
    }

    @Throws(PTPException::class)
    fun checkEvents(): List<EosEvent> {
        val data = Data(this)
        val res = transact0(Command.EosGetEvent, data)
        showResponseCode(
            "  GetEvent: data length: ${data.getLength()} resLength: ${res.getLength()} EosGetEvent ",
            res.getCode()
        )

        //
        // We need to discard the initial 12 USB header bytes
        //
        val buf = ByteArray(data.getLength() - 12)
        System.arraycopy(data.data, 12, buf, 0, buf.size)

        val parser = EosEventParser(ByteArrayInputStream(buf))

        val events = ArrayList<EosEvent>()
        while (parser.hasEvents()) {
            try {
                val event = parser.getNextEvent()
                events.add(event)
            } catch (e: PTPUnsupportedException) {
                //
                // TODO: log this information?
                //
            }
        }

        return events
    }

    @Throws(PTPException::class)
    override fun initiateCapture(storageId: Int, formatCode: Int): Response {
        var resp: Response? = null
        //
        // Special initialization for EOS cameras
        //
        if (!info!!.supportsOperation(Command.EosRemoteRelease)) {
            Log.d(BaselineInitiator.TAG, "The camera does not support EOS capture")
            throw PTPException("The camera does not support EOS capture")
        }

        var ret: Int
        ret = transact1(Command.EosSetRemoteMode, null, 1).getCode()
        showResponseCode("  EosSetRemoteMode 1: ", ret)
        if (ret != Response.OK) {
            throw PTPException("Unable to set remote mode", ret)
        }

        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        checkEvents() // Prevents  EosRemoteRelease!
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        checkEvents() // Prevents  EosRemoteRelease!
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        checkEvents() // Prevents  EosRemoteRelease!

        resp = transact0(Command.EosRemoteRelease, null)
        ret = resp.getCode()
        Log.d(
            BaselineInitiator.TAG,
            "  EosRemoteRelease Response code: 0x${Integer.toHexString(ret)}  OK: ${ret == Response.OK}"
        )
        if (ret != Response.OK) {
            var msg = "Canon EOS Capture failed to release: Unknown error $ret , please report."
            if (ret == 1) {
                msg = "Canon EOS Capture failed to release: Perhaps no focus?"
            } else if (ret == 7) {
                msg = "Canon EOS Capture failed to release: Perhaps no more memory on card?"
            }
            Log.d(BaselineInitiator.TAG, msg)
            throw PTPException(msg, ret)
        }
        ret = transact1(Command.EosSetRemoteMode, null, 0).getCode()
        showResponseCode("  EosSetRemoteMode 0: ", ret)
        if (ret != Response.OK) {
            throw PTPException("Unable to set remote mode", ret)
        }
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        checkEvents() // Prevents  EosRemoteRelease!
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        checkEvents() // Prevents  EosRemoteRelease!
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        checkEvents() // Prevents  EosRemoteRelease!

        return resp
    }

    @Throws(PTPException::class)
    fun getPartialObject(oid: Int, offset: Int, size: Int, data: Data) {
        val ret = transact3(Command.EosGetPartialObject, data, oid, offset, size)

        if (ret.getCode() != Response.OK) {
            throw PTPException("Error reading new object", ret.getCode())
        }
    }

    @Throws(PTPException::class)
    fun transferComplete(oid: Int) {
        val ret = transact1(Command.EosTransferComplete, null, oid)

        if (ret.getCode() != Response.OK) {
            throw PTPException("Error reading new object", ret.getCode())
        }
    }

    @Throws(PTPException::class)
    override fun setDevicePropValueEx(property: Int, value: Int): Response {
        val buff = ByteArray(0x18)
        val data = Data(false, buff, buff.size, this)
        data.offset = 0
        data.putHeader(
            buff.size, Container.BLOCK_TYPE_DATA, Command.EosSetDevicePropValueEx,
            0 /*XID, dummy, will be overwritten*/
        )
        data.put32(0x0c) // Length: 12 bytes
        data.put32(property)
        data.put32(value)
        return transact0(Command.EosSetDevicePropValueEx, data)
    }

    @Throws(PTPException::class)
    fun getDevicePropValueEx(property: Int): Response {
        val buff = ByteArray(0x10)
        val data = Data(false, buff, buff.size, this)
        data.offset = 0
        data.putHeader(
            buff.size, Container.BLOCK_TYPE_DATA, Command.EosRequestDevicePropValue,
            0 /*XID, dummy, will be overwritten*/
        )
        data.put32(property)
        return transact0(Command.EosRequestDevicePropValue, data)
    }

    fun getShutterSpeed(): Response {
        return getDevicePropValueEx(Command.EOS_DPC_ShutterSpeed)
    }

    @Throws(PTPException::class)
    override fun setShutterSpeed(speed: Int): Response {
        return setDevicePropValueEx(Command.EOS_DPC_ShutterSpeed, speed)
    }

    @Throws(PTPException::class)
    fun setLiveView(on: Boolean): Response {
        if (on) {
            // turn on
            setDevicePropValueEx(Command.EOS_DPC_LiveView, 2)
            return setDevicePropValueEx(0xD1B3, 2)
        } else {
            // turn off
            return setDevicePropValueEx(Command.EOS_DPC_LiveView, 0)
        }
    }

    @Throws(PTPException::class)
    fun startBulbs(): Response {

        setDevicePropValueEx(Command.EOS_DPC_ShutterSpeed, EosEventConstants.SHUTTER_SPEED_BULB)

        return transact0(Command.EosBulbStart, null)
    }

    @Throws(PTPException::class)
    fun stopBulbs(): Response {

        return transact0(Command.EosBulbEnd, null)
    }

    fun GetDevicePropInfo() {
        // val command = 0x1014
    }

    @Throws(PTPException::class)
    override fun MoveFocus(step: Int): Response {

        return transact1(Command.EosDriveLens, null, step)
    }

    @Throws(PTPException::class)
    override fun setExposure(exposure: Int): Response {

        return setDevicePropValueEx(Command.EOS_DPC_ExposureCompensation, exposure)
    }

    @Throws(PTPException::class)
    override fun setISO(value: Int): Response {

        return setDevicePropValueEx(Command.EOS_DPC_Iso, value)
    }

    @Throws(PTPException::class)
    override fun setAperture(value: Int): Response {

        return setDevicePropValueEx(Command.EOS_DPC_Aperture, value)
    }


    @Throws(PTPException::class)
    override fun setPictureStyle(value: Int): Response {

        return setDevicePropValueEx(Command.EOS_DPC_PictureStyle, value)
    }

    @Throws(PTPException::class)
    override fun setWhiteBalance(value: Int): Response {

        return setDevicePropValueEx(Command.EOS_DPC_WhiteBalance, value)
    }

    @Throws(PTPException::class)
    override fun setDriveMode(value: Int): Response {

        return setDevicePropValueEx(Command.EOS_DPC_DriveMode, value)
    }

    @Throws(PTPException::class)
    override fun setMetering(value: Int): Response {

        return setDevicePropValueEx(Command.EOS_DPC_ExpMeterringMode, value)
    }

    @Throws(PTPException::class)
    override fun setImageQuality(value: Int): Response {

        return setDevicePropValueEx(Command.EOS_DPC_ExpMeterringMode, value)
    }

    @Throws(PTPException::class)
    override fun setupLiveview() {

        val command = Command(Command.EOS_OC_SetPCConnectMode, session, 1)
        write(command.data!!, command.length, DEFAULT_TIMEOUT)
        val buf = read(DEFAULT_TIMEOUT)

        val response = Response(buf, inMaxPS, this)

        setDevicePropValueEx(Command.EOS_DPC_LiveView, 2)
        setDevicePropValueEx(Command.EOS_DPC_LiveView, 1)
    }

    override fun getLiveView(imageView: ImageView) {
        val command = Command(Command.EOS_OC_GetLiveViewPicture, session, 0x00100000)
        write(command.data!!, command.length, DEFAULT_TIMEOUT)
        var buf = read(DEFAULT_TIMEOUT)
        val item = Data(true, buf, this)

        val totalLength = item.getLength()
        val left = totalLength - buf.size

        var needToRead = (left / inMaxPS)

        if ((left % inMaxPS) != 0)
            needToRead++

        val imageBuf = ByteArray(inMaxPS * (needToRead + 1))

        System.arraycopy(buf, 0, imageBuf, 0, 512)

        for (i in 0 until needToRead) {

            buf = read(DEFAULT_TIMEOUT)
            System.arraycopy(buf, 0, imageBuf, 512 * (i + 1), 512)
        }
        val completedData = Data(true, imageBuf, this)

        val bMap = BitmapFactory.decodeByteArray(completedData.data, 20, completedData.getLength() - 20)
        val scaled = Bitmap.createScaledBitmap(bMap, bMap.width / 10, bMap.height / 10, false)

        imageView.post(Runnable {
            imageView.setImageBitmap(bMap)
            imageView.invalidate()
        })
        val buf1 = read(DEFAULT_TIMEOUT)

        val response = Response(buf1, inMaxPS, this)
    }

    override fun setFocusPos(x: Int, y: Int) {

        val command = Command(EosEventConstants.PTP_OC_CANON_EOS_ZoomPosition, session, x, y)

        write(command.data!!, command.length, DEFAULT_TIMEOUT)

        val buf = read(DEFAULT_TIMEOUT)

        // tv3.setText("Received:")
        val response = Response(buf, inMaxPS, this)
        // tv3.append(response.toString())
    }

    override fun setZoom(zoomLevel: Int) {
        // zoomLevel = 5 or 10 or 1

        val command = Command(EosEventConstants.PTP_OC_CANON_EOS_Zoom, session, zoomLevel)

        write(command.data!!, command.length, DEFAULT_TIMEOUT)

        val buf = read(DEFAULT_TIMEOUT)

        // tv3.setText("Received:")
        val response = Response(buf, inMaxPS, this)
        // tv3.append(response.toString())
    }

    override fun doAutoFocus() {

        val command = Command(EosEventConstants.PTP_OC_CANON_EOS_DoAf, session)
        write(command.data!!, command.length, DEFAULT_TIMEOUT)
        val buf = read(DEFAULT_TIMEOUT)
        // tv3.setText("Received:")
        val response = Response(buf, inMaxPS, this)
        // tv3.append(response.toString())
    }

    override fun run() {
        if (syncTriggerMode == SyncParams.SYNC_TRIGGER_MODE_EVENT) {
            runEosCheckEventPoll()
        } else if (syncTriggerMode == SyncParams.SYNC_TRIGGER_MODE_POLL_LIST) {
            try {
                runPollListPoll()
            } catch (e: PTPException) {
                e.printStackTrace()
            }
        }
    }

    private fun runEosCheckEventPoll() {
        Log.v("PTP_EVENT", "开始event轮询")

        try {
            setEosRemoteMode()
            setEosEventMode()
        } catch (e: PTPException) {
            e.printStackTrace()
        }


        val buffer = ByteArray(intrMaxPS)
        while (isSessionActive()) {
            try {
                val events = checkEvents()
                for (event in events) {
                    Log.v("PTP_EVENT", event.toString())

                    if (event.code == EosEventConstants.EosEventObjectAddedEx) {
                        processFileAddEvent(event.getIntParam(1), event)
                    }
                }
            } catch (e: PTPException) {
                continue
            }
            try {
                Thread.sleep(200) // poll interval every 200 ms
            } catch (e: InterruptedException) {
                e.printStackTrace()
                return
            }

        }
        Log.v("PTP_EVENT", "结束轮询")
    }

    override fun pollListSetUp() {
        try {
            setEosRemoteMode()
        } catch (e: PTPException) {
            e.printStackTrace()
        }
    }

    @Throws(PTPException::class)
    private fun setEosEventMode() {
        Log.v("PTP_EVENT", "set EosSetEventMode 1 ")
        val ret = transact1(Command.EosSetEventMode, null, 1).getCode()
        if (ret != Response.OK) {
            Log.v("PTP_EVENT", "set failed")
        }
    }

    @Throws(PTPException::class)
    private fun setEosRemoteMode() {
        Log.v("PTP_EVENT", "set EosSetRemoteMode 1 ")
        val ret = transact1(Command.EosSetRemoteMode, null, 1).getCode()
        if (ret != Response.OK) {
            Log.v("PTP_EVENT", "set failed")
        }
    }
}
