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

package cn.devcxl.photosync.ptp.usbcamera.nikon

import android.util.Log

import java.io.IOException
import java.io.InputStream

import cn.devcxl.photosync.ptp.usbcamera.PTPException
import cn.devcxl.photosync.ptp.usbcamera.PTPUnsupportedException

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
class NikonEventParser(private var `is`: InputStream) {

    init {
        if (`is` == null) {
            throw IllegalArgumentException("The input stream cannot be null")
        }
    }

    /**
     * Returns true is there are events in the stream (and the stream is still
     * open), false otherwise.
     *
     * @return true is there are events in the stream (and the stream is still
     * open), false otherwise.
     */
    fun hasEvents(): Boolean {
        try {
            if (`is`.available() <= 0) {
                return false
            }
        } catch (e: IOException) {
            return false
        }

        return true
    }

    /**
     * Returns the next event in the stream.
     *
     * @return the next event in the stream.
     * @throws PTPException in case of errors
     */
    fun getNextEvent(): NikonEvent {
        val event = NikonEvent()

        try {
            val len = getNextS32() // len
            if (len < 0x8) {
                throw PTPUnsupportedException("Unsupported event (size<8 ???)")
            }
            val code = getNextS32()
            event.setCode(code)
            Log.d(
                "EventParser",
                "   Event len: $len, Code: 0x" + String.format("%04x", code) + " " + NikonEvent.getEventName(code)
            )
            parseParameters(event, len - 8)
            for (i in 1..event.paramCount) {
                Log.d(
                    "EventParser",
                    "          params $i: " + String.format("0x%04x  %d", event.getParam(i), event.getParam(i))
                )
            }
        } catch (e: IOException) {
            Log.d("EventParser", "   Error reading event stream")
            throw PTPException("Error reading event stream", e)
        }

        return event
    }

    // --------------------------------------------------------- Private methods

    @Throws(PTPException::class, IOException::class)
    private fun parseParameters(event: NikonEvent, len: Int) {
        val code = event.code

        if (code == NikonEventConstants.EosEventPropValueChanged) {
            parsePropValueChangedParameters(event)
        } else if (code == NikonEventConstants.EosEventShutdownTimerUpdated) {
        } else if (code == NikonEventConstants.EosEventCameraStatusChanged) {
            event.setParam(1, getNextS32())
        } else if (code == NikonEventConstants.EosEventObjectAddedEx) {
            parseEosEventObjectAddedEx(event)
        } else {
            `is`.skip(len.toLong())
            throw PTPUnsupportedException("Unsupported event")
        }
    }

    @Throws(IOException::class)
    private fun parsePropValueChangedParameters(event: NikonEvent) {
        val property = getNextS32()
        event.setParam(1, property) // property changed

        if (property >= NikonEventConstants.EosPropPictureStyleStandard &&
            property <= NikonEventConstants.EosPropPictureStyleUserSet3
        ) {
            var monochrome = property == NikonEventConstants.EosPropPictureStyleMonochrome
            val size = getNextS32()
            if (size > 0x1C) {
                monochrome = getNextS32() == NikonEventConstants.EosPropPictureStyleUserTypeMonochrome
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
            event.setParam(2, getNextS32())
        }
    }

    @Throws(IOException::class)
    private fun parseEosEventObjectAddedEx(event: NikonEvent) {
        event.setParam(1, getNextS32()) // object id
        event.setParam(2, getNextS32()) // storage id
        event.setParam(4, getNextS16()) // format
        `is`.skip(10)
        event.setParam(5, getNextS32()) // size
        event.setParam(3, getNextS32()) // parent object id
        `is`.skip(4) // unknown
        event.setParam(6, getNextString()) // file name
        `is`.skip(4)
    }

    /**
     * Reads and return the next signed 32 bit integer read from the input
     * stream.
     *
     * @return the next signed 32 bit integer in the stream
     * @throws IOException in case of IO errors
     */
    @Throws(IOException::class)
    private fun getNextS32(): Int {
        var retval: Int

        retval = (0xff and `is`.read())
        retval = retval or ((0xff and `is`.read()) shl 8)
        retval = retval or ((0xff and `is`.read()) shl 16)
        retval = retval or (`is`.read() shl 24)

        return retval
    }

    /**
     * Reads and return the next signed 16 bit integer read from the input
     * stream.
     *
     * @return the next signed 16 bit integer in the stream
     * @throws IOException in case of IO errors
     */
    @Throws(IOException::class)
    private fun getNextS16(): Int {
        var retval: Int

        retval = (0xff and `is`.read())
        retval = retval or ((0xff and `is`.read()) shl 8)

        return retval
    }

    /**
     * Reads and return the next string read from the input stream. Strings are
     * zero (32 bit) terminated string
     *
     * @return the next string in the stream
     * @throws IOException in case of IO errors
     */
    @Throws(IOException::class)
    private fun getNextString(): String {
        val retval = StringBuilder()

        var c: Char = 0.toChar()
        while (`is`.read().also { c = it.toChar() } != 0) {
            retval.append(c)
        }

        `is`.skip(3)

        return retval.toString()
    }
}
