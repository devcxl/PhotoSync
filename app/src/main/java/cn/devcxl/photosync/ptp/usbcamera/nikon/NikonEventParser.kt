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

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parses the event list returned by the Nikon `NK_OC_CheckEvent` (0x90C7) operation.
 *
 * The payload carries a `uint16` event count followed by that many fixed-size 6 byte
 * records:
 *
 * ```
 * offset 0  uint16  event count
 * offset 2  uint16  event code
 * offset 4  uint32  param1
 * ```
 *
 * Every Nikon event record holds exactly one 32 bit parameter, so this parser has no
 * notion of variable-length records; that shape belongs to the Canon EOS event format.
 *
 * Layout verified against libgphoto2 `ptp_unpack_Nikon_EC`. The caller is responsible
 * for removing the leading PTP data container header, matching how [EosEventParser]
 * is used.
 *
 * @param payload Event list bytes, with the PTP container header already stripped.
 */
class NikonEventParser(payload: ByteArray) {

    private val buffer: ByteBuffer = ByteBuffer
        .wrap(payload)
        .order(ByteOrder.LITTLE_ENDIAN)

    private val eventCount: Int = if (buffer.remaining() >= COUNT_LEN) {
        buffer.short.toInt() and 0xFFFF
    } else {
        0
    }

    private var nextEventIndex: Int = 0

    /**
     * Returns true while another complete event record remains unread.
     *
     * A truncated trailing record is treated as absent, so a partially received payload
     * still yields the events it did contain instead of throwing.
     */
    fun hasEvents(): Boolean {
        if (nextEventIndex >= eventCount) return false
        return buffer.remaining() >= EVENT_LEN
    }

    /**
     * Reads the next event record.
     *
     * @return The parsed event, carrying its code and a single parameter.
     * @throws IllegalStateException if called when [hasEvents] returns false.
     */
    fun getNextEvent(): NikonEvent {
        check(hasEvents()) { "No more events to read" }

        val event = NikonEvent()
        event.setCode(buffer.short.toInt() and 0xFFFF)
        event.setParam(1, buffer.int)
        nextEventIndex++
        return event
    }

    companion object {
        private const val COUNT_LEN = 2

        /** Size of one event record: a uint16 code plus a uint32 param1. */
        private const val EVENT_LEN = 6
    }
}
