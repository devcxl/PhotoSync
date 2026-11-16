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
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import timber.log.Timber

/**
 * This class parses a stream of bytes as a sequence of events accordingly
 * to how Canon EOS returns events.
 *
 * Each record in the batch is framed by its declared 4-byte length; reading each record's
 * payload slice up front ensures variable-length property changes or unsupported event
 * codes never desynchronize subsequent events in the same `EosGetEvent` response.
 *
 * @author devcxl
 */
class EosEventParser(private val inputStream: InputStream) {

    private companion object {
        /** Record header: length + event code. */
        const val PTP_EVENT_HEADER_BYTES = 8

        /** Defensive cap on a single event record payload. */
        const val MAX_EVENT_PAYLOAD_BYTES = 1024 * 1024

        /** The empty record an EOS body returns when it has nothing to report. */
        const val PTP_EVENT_CODE_NOTHING_HAPPENED = 0x0000
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
            var len = getNextS32(inputStream)
            if (len < PTP_EVENT_HEADER_BYTES) {
                throw PTPUnsupportedException("Unsupported event (size<8 ???)")
            }
            var code = getNextS32(inputStream)
            // The body frames "nothing happened" as an empty record. Treating it as an
            // unparsable event produced one log line and one throw per idle poll, which is
            // pure noise on a channel polled several times a second.
            while (code == PTP_EVENT_CODE_NOTHING_HAPPENED) {
                skipExact(inputStream, (len - PTP_EVENT_HEADER_BYTES).toLong())
                if (!hasEvents()) {
                    // The batch held nothing but the sentinel: there is no event to report.
                    return null
                }
                len = getNextS32(inputStream)
                if (len < PTP_EVENT_HEADER_BYTES) {
                    throw PTPUnsupportedException("Unsupported event (size<8 ???)")
                }
                code = getNextS32(inputStream)
            }
            val payloadLength = len - PTP_EVENT_HEADER_BYTES
            if (payloadLength > MAX_EVENT_PAYLOAD_BYTES) {
                throw PTPException("EOS event payload too large: $payloadLength")
            }
            // Slice exact record payload before parsing so variable-length or unsupported
            // events can never shift the outer stream away from the next record boundary.
            val recordBytes = readExactBytes(inputStream, payloadLength)
            val recordStream = ByteArrayInputStream(recordBytes)

            event.code = code
            Timber.tag("EventParser").d(
                "   Event len: %d, Code: 0x%04x %s",
                len, code, EosEvent.getEventName(code)
            )
            parseParameters(event, recordStream)
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
    private fun parseParameters(event: EosEvent, stream: InputStream) {
        when (event.code) {
            EosEventPropValueChanged -> parsePropValueChangedParameters(event, stream)
            EosEventShutdownTimerUpdated -> Unit
            EosEventCameraStatusChanged -> {
                if (stream.available() >= 4) {
                    event.setParam(1, getNextS32(stream))
                }
            }
            EosEventObjectAddedEx -> parseEosEventObjectAddedEx(event, stream)
            EosEventObjectAddedEx64 -> parseEosEventObjectAddedEx64(event, stream)
            Event.UnreportedStatus -> Unit
            else -> throw PTPUnsupportedException("Unsupported event")
        }
    }

    @Throws(IOException::class)
    private fun parsePropValueChangedParameters(event: EosEvent, stream: InputStream) {
        if (stream.available() < 4) return
        val property = getNextS32(stream)
        event.setParam(1, property)

        if (property in EosPropPictureStyleStandard..EosPropPictureStyleUserSet3) {
            if (stream.available() < 4) return
            var monochrome = property == EosPropPictureStyleMonochrome
            val size = getNextS32(stream)
            if (size > 0x1C && stream.available() >= 4) {
                monochrome = getNextS32(stream) == EosPropPictureStyleUserTypeMonochrome
            }
            event.setParam(2, if (monochrome) java.lang.Boolean.TRUE else java.lang.Boolean.FALSE)
            if (stream.available() >= 4) event.setParam(3, getNextS32(stream)) // contrast
            if (stream.available() >= 4) event.setParam(4, getNextS32(stream)) // sharpness
            if (monochrome) {
                if (stream.available() >= 8) {
                    skipExact(stream, 8)
                }
                if (stream.available() >= 4) event.setParam(5, getNextS32(stream)) // filter effect
                if (stream.available() >= 4) event.setParam(6, getNextS32(stream)) // toning effect
            } else {
                if (stream.available() >= 4) event.setParam(5, getNextS32(stream)) // saturation
                if (stream.available() >= 4) event.setParam(6, getNextS32(stream)) // color tone
            }
        } else if (stream.available() >= 4) {
            event.setParam(2, getNextS32(stream))
        }
    }

    /**
     * `ObjectAddedEx64`: the same announcement as [parseEosEventObjectAddedEx], laid out
     * with a 64-bit object-id slot. Offsets follow libgphoto2's `PTP_cee_OA64_*` table.
     */
    @Throws(IOException::class)
    private fun parseEosEventObjectAddedEx64(event: EosEvent, stream: InputStream) {
        event.setParam(1, getNextS32(stream)) // object handle    (0x08)
        event.setParam(2, getNextS32(stream)) // storage id       (0x0c)
        event.setParam(4, getNextS16(stream)) // object format    (0x10)
        skipExact(stream, 10)                 //                  (0x12..0x1b)
        event.setParam(5, getNextS32(stream)) // object size      (0x1c)
        skipExact(stream, 4)                  //                  (0x20..0x23)
        event.setParam(3, getNextS32(stream)) // parent handle    (0x24)
        skipExact(stream, 4)                  // second object id (0x28)
        event.setParam(6, getNextString(stream)) // file name     (0x2c)
    }

    @Throws(IOException::class)
    private fun parseEosEventObjectAddedEx(event: EosEvent, stream: InputStream) {
        event.setParam(1, getNextS32(stream)) // object id
        event.setParam(2, getNextS32(stream)) // storage id
        event.setParam(4, getNextS16(stream)) // format
        skipExact(stream, 10)
        event.setParam(5, getNextS32(stream)) // size
        event.setParam(3, getNextS32(stream)) // parent object id
        skipExact(stream, 4)                  // unknown
        event.setParam(6, getNextString(stream)) // file name
    }

    @Throws(IOException::class)
    private fun readExactBytes(stream: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = stream.read(buf, offset, count - offset)
            if (read < 0) {
                throw EOFException("Unexpected EOF reading $count event bytes (got $offset)")
            }
            offset += read
        }
        return buf
    }

    @Throws(IOException::class)
    private fun skipExact(stream: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (stream.read() < 0) {
                    throw EOFException("Unexpected EOF skipping $count bytes")
                }
                remaining--
            }
        }
    }

    @Throws(IOException::class)
    private fun getNextS32(stream: InputStream): Int {
        val b0 = stream.read()
        val b1 = stream.read()
        val b2 = stream.read()
        val b3 = stream.read()
        if ((b0 or b1 or b2 or b3) < 0) {
            throw EOFException("Unexpected EOF reading S32")
        }
        return (b0 and 0xff) or
            ((b1 and 0xff) shl 8) or
            ((b2 and 0xff) shl 16) or
            ((b3 and 0xff) shl 24)
    }

    @Throws(IOException::class)
    private fun getNextS16(stream: InputStream): Int {
        val b0 = stream.read()
        val b1 = stream.read()
        if ((b0 or b1) < 0) {
            throw EOFException("Unexpected EOF reading S16")
        }
        return (b0 and 0xff) or ((b1 and 0xff) shl 8)
    }

    @Throws(IOException::class)
    private fun getNextString(stream: InputStream): String {
        val retval = StringBuilder()
        var c: Int
        while (stream.read().also { c = it } > 0) {
            retval.append(c.toChar())
        }
        return retval.toString()
    }
}
