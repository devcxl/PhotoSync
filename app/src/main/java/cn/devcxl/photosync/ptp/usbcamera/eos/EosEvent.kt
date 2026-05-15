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

package cn.devcxl.photosync.ptp.usbcamera.eos

import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventAfResult
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventAvailListChanged
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventBulbExposureTime
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventCameraStatusChanged
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventObjectAddedEx
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventObjectContentChanged
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventObjectInfoChangedEx
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventObjectRemoved
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventPropValueChanged
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventRecordingTime
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventRequestCancelTransfer
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventRequestCancelTransferDT
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventRequestGetEvent
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventRequestGetObjectInfoEx
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventRequestObjectTransfer
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventRequestObjectTransferDT
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventRequestObjectTransferTS
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventShutdownTimerUpdated
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventStorageInfoChanged
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventStorageStatusChanged
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventStoreAdded
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventStoreRemoved
import cn.devcxl.photosync.ptp.usbcamera.eos.EosEventConstants.EosEventWillSoonShutdown

/**
 *
 * @author ste
 * @author devcxl
 */
class EosEvent {

    /**
     * Event code
     */
    @JvmField
    var code: Int = 0

    /**
     * Params
     */
    private val params: MutableList<Any?> = ArrayList()

    fun setParam(i: Int, value: Any?) {
        if (i < 0) {
            throw IllegalArgumentException("param index cannot be < 0")
        }
        while (params.size < i) {
            params.add(null)
        }
        params[i - 1] = value
    }

    fun getParam(i: Int): Any? {
        if (i < 1 || i > paramCount) {
            throw IllegalArgumentException(
                "index $i out of range (0-$paramCount)"
            )
        }
        return params[i - 1]
    }

    @Suppress("UNCHECKED_CAST")
    fun getIntParam(i: Int): Int = getParam(i) as Int

    @Suppress("UNCHECKED_CAST")
    fun getStringParam(i: Int): String = getParam(i) as String

    val paramCount: Int get() = params.size

    override fun toString(): String {
        return "event name is : ${getEventName(code)} first event param is ${getIntParam(1)}"
    }

    companion object {
        fun getEventName(code: Int): String = when (code) {
            EosEventRequestGetEvent -> "EosEventRequestGetEvent"
            EosEventObjectAddedEx -> "EosEventObjectAddedEx"
            EosEventObjectRemoved -> "EosEventObjectRemoved"
            EosEventRequestGetObjectInfoEx -> "EosEventRequestGetObjectInfoEx"
            EosEventStorageStatusChanged -> "EosEventStorageStatusChanged"
            EosEventStorageInfoChanged -> "EosEventStorageInfoChanged"
            EosEventRequestObjectTransfer -> "EosEventRequestObjectTransfer"
            EosEventObjectInfoChangedEx -> "EosEventObjectInfoChangedEx"
            EosEventObjectContentChanged -> "EosEventObjectContentChanged"
            EosEventPropValueChanged -> "EosEventPropValueChanged"
            EosEventAvailListChanged -> "EosEventAvailListChanged"
            EosEventCameraStatusChanged -> "EosEventCameraStatusChanged"
            EosEventWillSoonShutdown -> "EosEventWillSoonShutdown"
            EosEventShutdownTimerUpdated -> "EosEventShutdownTimerUpdated"
            EosEventRequestCancelTransfer -> "EosEventRequestCancelTransfer"
            EosEventRequestObjectTransferDT -> "EosEventRequestObjectTransferDT"
            EosEventRequestCancelTransferDT -> "EosEventRequestCancelTransferDT"
            EosEventStoreAdded -> "EosEventStoreAdded"
            EosEventStoreRemoved -> "EosEventStoreRemoved"
            EosEventBulbExposureTime -> "EosEventBulbExposureTime"
            EosEventRecordingTime -> "EosEventRecordingTime"
            EosEventAfResult -> "EosEventRequestObjectTransferTS"
            EosEventRequestObjectTransferTS -> "EosEventAfResult"
            else -> "0x${Integer.toHexString(code)}"
        }
    }
}
