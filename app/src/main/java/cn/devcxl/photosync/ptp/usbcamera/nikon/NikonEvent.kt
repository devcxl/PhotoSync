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

import java.util.ArrayList

/**
 * @author devcxl
 */
class NikonEvent {

    /**
     * Event code
     */
    @JvmField
    var code: Int = 0

    /**
     * Params
     */
    private var params: MutableList<Any?> = ArrayList()

    fun setCode(code: Int) {
        this.code = code
    }

    fun getCode(): Int {
        return code
    }

    /**
     * @param i the parameter index
     * @param value the param to set
     */
    fun setParam(i: Int, value: Any?) {
        if (i < 0) {
            throw IllegalArgumentException("param index cannot be < 0")
        }
        if (params.size <= i) {
            val newParams = ArrayList<Any?>(i)
            newParams.addAll(params)
            params = newParams
            for (j in params.size until i) {
                params.add(null)
            }
        }
        params[i - 1] = value
    }

    fun setParam(i: Int, value: Int) {
        setParam(i, Integer(value) as Any?)
    }

    fun getParam(i: Int): Any? {
        if (i < 1 || i > paramCount) {
            throw IllegalArgumentException(
                "index $i out of range (0-$paramCount)"
            )
        }
        return params[i - 1]
    }

    fun getIntParam(i: Int): Int {
        return getParam(i) as Int
    }

    fun getStringParam(i: Int): String {
        return getParam(i) as String
    }

    /**
     * @return the number of parameters in this event
     */
    val paramCount: Int
        get() = params.size

    companion object {
        fun getEventName(code: Int): String {
            when (code) {
                NikonEventConstants.EosEventRequestGetEvent -> return "EosEventRequestGetEvent"
                NikonEventConstants.EosEventObjectAddedEx -> return "EosEventObjectAddedEx"
                NikonEventConstants.EosEventObjectRemoved -> return "EosEventObjectRemoved"
                NikonEventConstants.EosEventRequestGetObjectInfoEx -> return "EosEventRequestGetObjectInfoEx"
                NikonEventConstants.EosEventStorageStatusChanged -> return "EosEventStorageStatusChanged"
                NikonEventConstants.EosEventStorageInfoChanged -> return "EosEventStorageInfoChanged"
                NikonEventConstants.EosEventRequestObjectTransfer -> return "EosEventRequestObjectTransfer"
                NikonEventConstants.EosEventObjectInfoChangedEx -> return "EosEventObjectInfoChangedEx"
                NikonEventConstants.EosEventObjectContentChanged -> return "EosEventObjectContentChanged"
                NikonEventConstants.EosEventPropValueChanged -> return "EosEventPropValueChanged"
                NikonEventConstants.EosEventAvailListChanged -> return "EosEventAvailListChanged"
                NikonEventConstants.EosEventCameraStatusChanged -> return "EosEventCameraStatusChanged"
                NikonEventConstants.EosEventWillSoonShutdown -> return "EosEventWillSoonShutdown"
                NikonEventConstants.EosEventShutdownTimerUpdated -> return "EosEventShutdownTimerUpdated"
                NikonEventConstants.EosEventRequestCancelTransfer -> return "EosEventRequestCancelTransfer"
                NikonEventConstants.EosEventRequestObjectTransferDT -> return "EosEventRequestObjectTransferDT"
                NikonEventConstants.EosEventRequestCancelTransferDT -> return "EosEventRequestCancelTransferDT"
                NikonEventConstants.EosEventStoreAdded -> return "EosEventStoreAdded"
                NikonEventConstants.EosEventStoreRemoved -> return "EosEventStoreRemoved"
                NikonEventConstants.EosEventBulbExposureTime -> return "EosEventBulbExposureTime"
                NikonEventConstants.EosEventRecordingTime -> return "EosEventRecordingTime"
                NikonEventConstants.EosEventAfResult -> return "EosEventRequestObjectTransferTS"
                NikonEventConstants.EosEventRequestObjectTransferTS -> return "EosEventAfResult"
            }
            return "0x" + Integer.toHexString(code)
        }
    }
}
