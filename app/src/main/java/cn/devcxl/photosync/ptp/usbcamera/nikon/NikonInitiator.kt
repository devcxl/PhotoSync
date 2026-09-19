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

import cn.devcxl.photosync.ptp.params.SyncParams
import cn.devcxl.photosync.ptp.usbcamera.BaselineInitiator
import cn.devcxl.photosync.ptp.usbcamera.Command
import cn.devcxl.photosync.ptp.usbcamera.Container
import cn.devcxl.photosync.ptp.usbcamera.Data
import cn.devcxl.photosync.ptp.usbcamera.DevicePropDesc
import cn.devcxl.photosync.ptp.usbcamera.PTPException
import cn.devcxl.photosync.ptp.usbcamera.PTPUnsupportedException
import cn.devcxl.photosync.ptp.usbcamera.Response
import timber.log.Timber

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

        /** Length of the PTP data container header preceding an event payload. */
        private const val EVENT_HEADER_LEN = 12

        /** How long the event loop waits between `NK_OC_CheckEvent` calls. */
        private const val EVENT_POLL_INTERVAL_MS = 200L

        @JvmField
        var eventListenerRunning: Boolean = false
    }

    /**
     * The object handle Nikon reports for an in-SDRAM capture whose real handle cannot be
     * resolved yet. libgphoto2 substitutes this same value when a `0xC101` event arrives
     * with a zero parameter, because `GetObjectInfo(0)` would fail.
     */
    protected var PTP_NIKON_SDRAM_OBJECT_HANDLE: Int = 0xffff0001.toInt()

    override fun getObjectAddedEventCode(): Int = NikonEventConstants.NK_EC_ObjectAddedInSDRAM

    /**
     * Resolves the handle to download for an object-added event.
     *
     * Some cameras announce an in-SDRAM capture with a zero parameter instead of a real
     * object handle; those resolve to a placeholder that the camera understands.
     *
     * @param event the object-added event
     * @return the handle to pass to the object download path.
     */
    protected fun resolveObjectAddedHandle(event: NikonEvent): Int {
        val handle = event.getIntParam(1)
        return if (handle == 0) PTP_NIKON_SDRAM_OBJECT_HANDLE else handle
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
     * Drains the camera's Nikon vendor event queue via `NK_OC_CheckEvent` (0x90C7).
     *
     * The operation answers with a data phase holding a count followed by fixed-size
     * records, which is why this reads a whole payload rather than a single response.
     *
     * @return the events the camera reported, or an empty list when it reported none.
     * @throws PTPException when the device rejects the operation or the transfer fails.
     */
    @Throws(PTPException::class)
    fun checkEvents(): List<NikonEvent> {
        if (!info!!.supportsOperation(Command.NK_OC_CheckEvent)) {
            throw PTPUnsupportedException("Device does not support NK_OC_CheckEvent")
        }

        val data = Data(this)
        transact0(Command.NK_OC_CheckEvent, data)

        val length = data.getLength()
        if (length <= EVENT_HEADER_LEN) {
            return emptyList()
        }

        // Discard the PTP data container header; the parser starts at the event count.
        val payload = ByteArray(length - EVENT_HEADER_LEN)
        System.arraycopy(data.data, EVENT_HEADER_LEN, payload, 0, payload.size)

        val parser = NikonEventParser(payload)
        val events = ArrayList<NikonEvent>()
        while (parser.hasEvents()) {
            events.add(parser.getNextEvent())
        }
        return events
    }

    override fun run() {
        if (syncTriggerMode == SyncParams.SYNC_TRIGGER_MODE_EVENT) {
            runNikonEventPoll()
        } else if (syncTriggerMode == SyncParams.SYNC_TRIGGER_MODE_POLL_LIST) {
            try {
                runPollListPoll()
            } catch (e: PTPException) {
                Timber.w(e, "runPollListPoll failed")
            }
        }
    }

    /**
     * Event-driven transfer loop: polls `NK_OC_CheckEvent` and downloads each object the
     * camera reports as newly added.
     *
     * Cameras that reject `NK_OC_CheckEvent` fall back to the poll-list loop, mirroring
     * libgphoto2's `event90c7works` handling, so enabling event mode cannot leave a device
     * without a working transfer loop.
     */
    private fun runNikonEventPoll() {
        Timber.tag("PTP_EVENT").v("开始 Nikon event 轮询")
        pollThreadRunning = true
        pollEventSetUp()
        try {
            while (pollThreadRunning && isSessionActive() && autoPollEvent) {
                val events = try {
                    checkEvents()
                } catch (e: PTPUnsupportedException) {
                    Timber.w(e, "NK_OC_CheckEvent unsupported, falling back to poll-list")
                    runPollListPoll()
                    return
                } catch (e: PTPException) {
                    Timber.w(e, "checkEvents failed")
                    emptyList()
                }

                for (event in events) {
                    Timber.tag("PTP_EVENT").v(NikonEventFormat.format(event))
                    if (event.code == getObjectAddedEventCode()) {
                        processFileAddEvent(resolveObjectAddedHandle(event), event)
                    }
                }

                try {
                    Thread.sleep(EVENT_POLL_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    Timber.w(e, "event poll interrupted, stopping")
                    return
                }
            }
        } finally {
            pollThreadRunning = false
            Timber.tag("PTP_EVENT").v("结束 Nikon event 轮询")
        }
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
            Timber.d("The camera does not support Nikon capture")
            throw PTPException("The camera does not support Nikon capture")
        }

        var ret: Int

        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            Timber.w(e, "initiateCapture failed")
        }
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            Timber.w(e, "initiateCapture failed")
        }

        resp = transact0(Command.InitiateCapture, null)
        ret = resp.getCode()
        Timber.d("  NK_OC_Capture Response code: 0x%s  OK: %b", Integer.toHexString(ret), ret == Response.OK)
        if (ret != Response.OK) {
            val msg = "NK_OC_Capture  Capture failed to release: Unknown error " +
                ret +
                " , please report."
            Timber.d(msg)
            throw PTPException(msg, ret)
        }

        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            Timber.w(e, "initiateCapture failed")
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
