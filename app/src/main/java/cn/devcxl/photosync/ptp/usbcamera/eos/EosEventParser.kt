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

import android.util.Log
import cn.devcxl.photosync.ptp.usbcamera.PTPException
import cn.devcxl.photosync.ptp.usbcamera.PTPUnsupportedException
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventCameraStatusChanged
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventObjectAddedEx
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventPropValueChanged
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventShutdownTimerUpdated
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosPropPictureStyleMonochrome
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosPropPictureStyleStandard
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosPropPictureStyleUserSet3
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosPropPictureStyleUserTypeMonochrome
import java.io.IOException
import java.io.InputStream

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
 * @author stefano fornari
 * @author devcxl
 */
class EosEventParser(private val inputStream: InputStream) {

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
    fun getNextEvent(): EosEvent {
        val event = EosEvent()

        try {
            val len = getNextS32() // len
            if (len < 0x8) {
                throw PTPUnsupportedException("Unsupported event (size<8 ???)")
            }
            val code = getNextS32()
            event.code = code
            Log.d(
                "EventParser", "   Event len: $len, Code: 0x${String.format("%04x", code)} ${
                    EosEvent.getEventName(
                        code
                    )
                }"
            )
            parseParameters(event, len - 8)
            for (i in 1..event.paramCount) {
                val p = event.getParam(i)
                when (p) {
                    is String -> Log.d("EventParser", "          params $i: ${String.format("%s", p)}")
                    is java.lang.Boolean -> Log.d("EventParser", "          params $i: ${String.format("%b", p)}")
                    else -> Log.d("EventParser", "          params $i: ${String.format("0x%04x  %d", p, p)}")
                }
            }
        } catch (e: IOException) {
            Log.d("EventParser", "   Error reading event stream")
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
