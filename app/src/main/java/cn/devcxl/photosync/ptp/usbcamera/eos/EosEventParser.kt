/* Copyright 2010 by Stefano Fornari
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package cn.devcxl.photosync.ptp.usbcamera.eos

import cn.devcxl.photosync.ptp.usbcamera.Event
import cn.devcxl.photosync.ptp.usbcamera.PTPException
import cn.devcxl.photosync.ptp.usbcamera.PTPUnsupportedException
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventCameraStatusChanged
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventObjectAddedEx
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventObjectAddedEx64
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventPropValueChanged
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventShutdownTimerUpdated
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosPropPictureStyleMonochrome
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosPropPictureStyleStandard
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosPropPictureStyleUserSet3
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosPropPictureStyleUserTypeMonochrome
import java.io.IOException
import java.io.InputStream
import timber.log.Timber

/**
 * This class parses a stream of bytes as a sequence of events accordingly
 * to how Canon EOS returns events.
 *
 * The event information is returned in a standard PTP data packet as a number
 * of records followed by an empty record at the end of the packet. Each record
 * consists of multiple four-byte fields and always starts with record length
 * field. Further structure of the record depends on the device property code
 * which always goes in the third field. The empty record consists of the size
 * field and four byte empty field, which is always zero.
 *
 * @author devcxl
 */
class EosEventParser(private val inputStream: InputStream) {

    private companion object {
        /** Bytes of the `ObjectAddedEx64` record before the file name, minus its header. */
        const val OBJECT_ADDED_EX64_NAME_OFFSET = 36

        /** Record header: length + event code. */
        const val PTP_EVENT_HEADER_BYTES = 8

        /** The empty record an EOS body returns when it has nothing to report. */
        const val PTP_EVENT_CODE_NOTHING_HAPPENED = 0x0000
    }

    init {
        if (inputStream == null) {
            throw IllegalArgumentException("The input stream cannot be null")
        }
    }

    fun hasEvents(): Boolean {
        return try {
            inputStream.available() > 0
        } catch (e: IOException) {
            false
        }
    }

    @Throws(PTPException::class)
    fun getNextEvent(): EosEvent? {
        val event = EosEvent()

        try {
            var len = getNextS32() // len
            if (len < 0x8) {
                throw PTPUnsupportedException("Unsupported event (size<8 ???)")
            }
            var code = getNextS32()
            // The body frames "nothing happened" as an empty record. Treating it as an
            // unparsable event produced one log line and one throw per idle poll, which is
            // pure noise on a channel polled several times a second.
            while (code == PTP_EVENT_CODE_NOTHING_HAPPENED) {
                inputStream.skip((len - PTP_EVENT_HEADER_BYTES).toLong())
                if (!hasEvents()) {
                    // The batch held nothing but the sentinel: there is no event to report.
                    return null
                }
                len = getNextS32()
                if (len < 0x8) {
                    throw PTPUnsupportedException("Unsupported event (size<8 ???)")
                }
                code = getNextS32()
            }
            event.code = code
            Timber.tag("EventParser").d(
                "   Event len: %d, Code: 0x%04x %s",
                len, code, EosEvent.getEventName(code)
            )
            parseParameters(event, len - 8)
            for (i in 1..event.paramCount) {
                val p = event.getParam(i)
                when (p) {
                    is Int -> Timber.tag("EventParser").d("          params %d: 0x%04x  %d", i, p, p)
                    else -> Timber.tag("EventParser").d("          params %d: %s", i, p)
                }
            }
        } catch (e: IOException) {
            Timber.tag("EventParser").d("   Error reading event stream")
            throw PTPException("Error reading event stream", e)
        }

        return event
    }


    // --------------------------------------------------------- Private methods

    @Throws(PTPException::class, IOException::class)
    private fun parseParameters(event: EosEvent, len: Int) {
        val code = event.code

        when (code) {
            EosEventPropValueChanged -> parsePropValueChangedParameters(event)
            EosEventShutdownTimerUpdated -> {
                //
                // No parameters
                //
            }
            EosEventCameraStatusChanged -> event.setParam(1, getNextS32())
            EosEventObjectAddedEx -> parseEosEventObjectAddedEx(event)
            EosEventObjectAddedEx64 -> parseEosEventObjectAddedEx64(event, len)
            Event.UnreportedStatus -> {
                //
                // The standard "I may have missed events" signal. It is not an EOS event,
                // but a body can put a standard code in its batch. Skip its payload and
                // let it through instead of throwing: the caller turns it into a
                // reconcile, while a throw here would look like a channel failure.
                //
                inputStream.skip(len.toLong())
            }
            else -> {
                inputStream.skip(len.toLong())
                throw PTPUnsupportedException("Unsupported event")
            }
        }
    }

    @Throws(IOException::class)
    private fun parsePropValueChangedParameters(event: EosEvent) {
        val property = getNextS32()
        event.setParam(1, property) // property changed

        if ((property >= EosPropPictureStyleStandard) &&
            (property <= EosPropPictureStyleUserSet3)
        ) {
            var monochrome = (property == EosPropPictureStyleMonochrome)
            val size = getNextS32()
            if (size > 0x1C) {
                //
                // It is a EosPropPictureStyleUserXXX, let's read the type (then
                // we do not use it)
                //
                monochrome = (getNextS32() == EosPropPictureStyleUserTypeMonochrome)
            }
            event.setParam(2, if (monochrome) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE)
            event.setParam(3, getNextS32()) // contrast
            event.setParam(4, getNextS32()) // sharpness
            if (monochrome) {
                getNextS32()
                getNextS32()
                event.setParam(5, getNextS32()) // filter effect
                event.setParam(6, getNextS32()) // toning effect
            } else {
                event.setParam(5, getNextS32()) // saturation
                event.setParam(6, getNextS32()) // color tone
                getNextS32()
                getNextS32()
            }
        } else {
            //
            // default
            //
            event.setParam(2, getNextS32())
        }
    }

    @Throws(IOException::class)
    /**
     * `ObjectAddedEx64`: the same announcement as [parseEosEventObjectAddedEx], laid out
     * with a 64-bit object-id slot. Offsets follow libgphoto2's `PTP_cee_OA64_*` table.
     *
     * Cameras send this instead of `0xC181` depending on firmware and file-name rules; a
     * body that only ever sends this code used to look like it never announced a capture.
     */
    private fun parseEosEventObjectAddedEx64(event: EosEvent, payloadLength: Int) {
        event.setParam(1, getNextS32()) // object handle    (0x08)
        event.setParam(2, getNextS32()) // storage id       (0x0c)
        event.setParam(4, getNextS16()) // object format    (0x10)
        inputStream.skip(10)            //                  (0x12..0x1b)
        event.setParam(5, getNextS32()) // object size      (0x1c)
        inputStream.skip(4)             //                  (0x20..0x23)
        event.setParam(3, getNextS32()) // parent handle    (0x24)
        inputStream.skip(4)             // second object id (0x28)
        val name = getNextString()      // file name        (0x2c)
        event.setParam(6, name)
        // The tail pads the record to its declared length; deriving it keeps the stream
        // aligned for the next event instead of assuming a fixed four bytes.
        val paddedNameBytes = ((name.toByteArray(Charsets.UTF_8).size + 1 + 3) / 4) * 4
        val remainder = payloadLength - (OBJECT_ADDED_EX64_NAME_OFFSET + paddedNameBytes)
        if (remainder > 0) {
            inputStream.skip(remainder.toLong())
        }
    }

    private fun parseEosEventObjectAddedEx(event: EosEvent) {
        event.setParam(1, getNextS32()) // object id
        event.setParam(2, getNextS32()) // storage id
        event.setParam(4, getNextS16()) // format
        inputStream.skip(10)
        event.setParam(5, getNextS32()) // size
        event.setParam(3, getNextS32()) // parent object id
        inputStream.skip(4) // unknown
        event.setParam(6, getNextString()) // file name
        inputStream.skip(4)
    }

    @Throws(IOException::class)
    private fun getNextS32(): Int {
        var retval: Int

        retval = (0xff and inputStream.read())
        retval = retval or ((0xff and inputStream.read()) shl 8)
        retval = retval or ((0xff and inputStream.read()) shl 16)
        retval = retval or (inputStream.read() shl 24)

        return retval
    }

    @Throws(IOException::class)
    private fun getNextS16(): Int {
        var retval: Int

        retval = (0xff and inputStream.read())
        retval = retval or ((0xff and inputStream.read()) shl 8)

        return retval
    }

    @Throws(IOException::class)
    private fun getNextString(): String {
        val retval = StringBuilder()

        var c: Int
        while (inputStream.read().also { c = it } != 0) {
            retval.append(c.toChar())
        }

        //
        // At this point we read the string and one zero. We need to read the
        // remaining 3 zeros
        //
        inputStream.skip(3)

        return retval.toString()
    }
}
