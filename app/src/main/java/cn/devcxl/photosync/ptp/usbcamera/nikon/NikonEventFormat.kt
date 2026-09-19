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

/**
 * Renders Nikon vendor events for logging.
 *
 * @author devcxl
 */
object NikonEventFormat {

    /**
     * Formats one event as a single log-friendly line.
     *
     * @param e the event to describe
     * @return A description naming the event and its parameter, e.g.
     *   `ObjectAddedInSDRAM [ handle: 0xffff0001 ]`.
     */
    @JvmStatic
    fun format(e: NikonEvent): String {
        val sb = StringBuilder()
        sb.append(NikonEvent.getEventName(e.code))
        sb.append(" [ ")
        if (e.paramCount >= 1) {
            sb.append(paramLabel(e.code)).append(": 0x").append(Integer.toHexString(e.getIntParam(1)))
        }
        sb.append(" ]")
        return sb.toString()
    }

    /**
     * Names the single parameter carried by the given event code.
     *
     * Nikon event records only ever carry one 32 bit parameter, whose meaning depends on
     * the event code.
     *
     * @param code event code
     * @return the parameter label, or `"param1"` for events not catalogued here
     */
    @JvmStatic
    fun paramLabel(code: Int): String = when (code) {
        NikonEventConstants.NK_EC_ObjectAddedInSDRAM -> "handle"
        NikonEventConstants.NK_EC_AdvancedTransfer -> "transfer"
        NikonEventConstants.NK_EC_CaptureOverflow -> "overflow"
        else -> "param1"
    }
}
