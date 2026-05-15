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

import java.lang.reflect.Field

/**
 * This class formats an EosEvent to a string
 *
 * @author devcxl
 */
object NikonEventFormat {

    @JvmStatic
    fun format(e: NikonEvent): String {
        val sb = StringBuilder()

        val eventCode = e.code

        sb.append(getEventName(eventCode))
        sb.append(" [ ")
        if (eventCode == NikonEventConstants.EosEventPropValueChanged) {
            val propCode = e.getIntParam(1)
            sb.append(getPropertyName(propCode))
                .append(": ")

            if (propCode >= NikonEventConstants.EosPropPictureStyleStandard &&
                propCode <= NikonEventConstants.EosPropPictureStyleUserSet3
            ) {
                sb.append("(Sharpness: ")
                    .append(e.getIntParam(4))
                    .append(", Contrast: ")
                    .append(e.getIntParam(3))
                if ((e.getParam(2) as Boolean?) == true) {
                    sb.append(", Filter effect: ")
                        .append(getFilterEffect(e.getIntParam(5)))
                        .append(", Toning effect: ")
                        .append(getToningEffect(e.getIntParam(6)))
                } else {
                    sb.append(", Saturation: ")
                        .append(e.getIntParam(5))
                        .append(", Color tone: ")
                        .append(e.getIntParam(6))
                }
                sb.append(")")
            } else {
                if (e.paramCount > 1) {
                    sb.append(e.getIntParam(2))
                }
            }
        } else if (eventCode == NikonEventConstants.EosEventObjectAddedEx) {
            sb.append(formatEosEventObjectAddedEx(e))
        }
        sb.append(" ]")

        return sb.toString()
    }

    /**
     * Returns the printable name of the given event
     *
     * @param code event code
     * @return the printable name of the given event
     */
    @JvmStatic
    fun getEventName(code: Int): String {
        val fields = NikonEventConstants::class.java.declaredFields

        for (f in fields) {
            val name = f.name
            if (name.startsWith("EosEvent")) {
                try {
                    if (f.getInt(null) == code) {
                        return name
                    }
                } catch (e: Exception) {
                }
            }
        }
        return "Unknown"
    }

    /**
     * Returns the printable name of the given property
     *
     * @param code property code
     * @return the printable name of the given property
     */
    @JvmStatic
    fun getPropertyName(code: Int): String {
        return getCodeName("EosProp", code)
    }

    /**
     * Returns the printable name of the given image format
     *
     * @param code image format code
     * @return the printable name of the given image format
     */
    @JvmStatic
    fun getImageFormatName(code: Int): String {
        return getCodeName("ImageFormat", code)
    }

    /**
     * Returns the filter effect name given the code. Names are: <br></br>
     * 0:None, 1:Yellow, 2:Orange, 3:Red, 4:Green
     *
     * @param code the filter effect code (0-4)
     * @return the filter effect name
     */
    @JvmStatic
    fun getFilterEffect(code: Int): String {
        if (code < 0 || code > 4) {
            throw IllegalArgumentException("code must be in he range 0-4")
        }

        return when (code) {
            0 -> "None"
            1 -> "Yellow"
            2 -> "Orange"
            3 -> "Red"
            4 -> "Green"
            else -> "Unknown"
        }
    }

    /**
     * Returns the toning effect name given the code. Names are: <br></br>
     * 0:None, 1:Sepia, 2:Blue, 3:Purple, 4:Green
     *
     * @param code the toning effect code (0-4)
     * @return the toning effect name
     */
    @JvmStatic
    fun getToningEffect(code: Int): String {
        if (code < 0 || code > 4) {
            throw IllegalArgumentException("code must be in he range 0-4")
        }

        return when (code) {
            0 -> "None"
            1 -> "Sepia"
            2 -> "Blue"
            3 -> "Purple"
            4 -> "Green"
            else -> "Unknown"
        }
    }

    // --------------------------------------------------------- Private methods

    private fun formatEosEventObjectAddedEx(event: NikonEvent): String {
        return String.format(
            "Filename: %s, Size(bytes): %d, ObjectID: 0x%08X, StorageID: 0x%08X, ParentID: 0x%08X, Format: %s",
            event.getStringParam(6),
            event.getIntParam(5),
            event.getIntParam(1),
            event.getIntParam(2),
            event.getIntParam(3),
            getImageFormatName(event.getIntParam(4))
        )
    }

    /**
     * Looks up and returns the name of the code give if there is a constant
     * field in EosEventConstants which starts with the given prefix
     *
     * @param prefix the field name prefix
     * @param code image format code
     * @return the printable name of the given code
     */
    private fun getCodeName(prefix: String, code: Int): String {
        val fields = NikonEventConstants::class.java.declaredFields

        for (f in fields) {
            val name = f.name
            if (name.startsWith(prefix)) {
                try {
                    if (f.getInt(null) == code) {
                        return name.substring(prefix.length)
                    }
                } catch (e: Exception) {
                }
            }
        }
        return "Unknown"
    }
}
