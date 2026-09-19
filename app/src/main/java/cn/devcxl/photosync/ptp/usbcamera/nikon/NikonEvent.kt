/* Copyright 2010 by Stefano Fornari
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
*/

package cn.devcxl.photosync.ptp.usbcamera.nikon

/**
 * One Nikon vendor event, as returned by `NK_OC_CheckEvent`.
 *
 * Nikon event records carry a single 32 bit parameter, exposed here as parameter 1.
 *
 * @author devcxl
 */
class NikonEvent {

    /**
     * Event code
     */
    @JvmField
    var code: Int = 0

    private var params: MutableList<Any?> = ArrayList()

    fun setCode(code: Int) {
        this.code = code
    }

    fun getCode(): Int = code

    /**
     * @param i the parameter index, starting at 1
     * @param value the param to set
     */
    fun setParam(i: Int, value: Any?) {
        if (i < 1) {
            throw IllegalArgumentException("param index cannot be < 1")
        }
        while (params.size < i) {
            params.add(null)
        }
        params[i - 1] = value
    }

    fun setParam(i: Int, value: Int) {
        setParam(i, value as Any?)
    }

    /**
     * @param i the parameter index, starting at 1
     * @return the parameter, or null when the event did not carry one
     */
    fun getParam(i: Int): Any? {
        if (i < 1 || i > paramCount) {
            throw IllegalArgumentException("index $i out of range (1-$paramCount)")
        }
        return params[i - 1]
    }

    fun getIntParam(i: Int): Int = getParam(i) as Int

    /**
     * @return the number of parameters carried by this event
     */
    val paramCount: Int
        get() = params.size

    override fun toString(): String {
        val name = getEventName(code)
        return if (paramCount >= 1) {
            "$name (0x${Integer.toHexString(code)}), param1=0x${Integer.toHexString(getIntParam(1))}"
        } else {
            "$name (0x${Integer.toHexString(code)})"
        }
    }

    companion object {

        /**
         * Maps a Nikon event code to its printable name.
         *
         * @param code event code reported by the camera
         * @return the constant name, or the raw hex code when it is not a known event
         */
        fun getEventName(code: Int): String = when (code) {
            NikonEventConstants.NK_EC_ObjectAddedInSDRAM -> "ObjectAddedInSDRAM"
            NikonEventConstants.NK_EC_CaptureOverflow -> "CaptureOverflow"
            NikonEventConstants.NK_EC_AdvancedTransfer -> "AdvancedTransfer"
            else -> "0x" + Integer.toHexString(code)
        }
    }
}
