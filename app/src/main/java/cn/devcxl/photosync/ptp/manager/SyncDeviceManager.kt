package cn.devcxl.photosync.ptp.manager

import android.hardware.usb.UsbDevice
import cn.devcxl.photosync.App
import cn.devcxl.photosync.data.AppDatabase
import cn.devcxl.photosync.data.entity.SyncDevice
import cn.devcxl.photosync.ptp.detect.CameraDetector
import timber.log.Timber
import java.util.Date

/**
 * !!! Not thread safe !!!
 * @author devcxl
 */
class SyncDeviceManager(private var device: UsbDevice) {

    private val dao = AppDatabase.getInstance(App.get()!!).syncDeviceDao()

    fun updateDeviceInfo(): SyncDevice? {
        val uuid = getUUIDFromDevice(device)
        var syncDevice = getSyncDevice(device)
        val now = Date().time
        if (syncDevice == null) {
            val serial = try {
                device.serialNumber
            } catch (se: SecurityException) {
                Timber.tag(TAG).w(se, "No permission to read serial number when updating device info; using empty")
                ""
            }
            syncDevice = SyncDevice(
                deviceUUID = uuid,
                deviceName = device.deviceName,
                productName = device.productName,
                manufacturerName = device.manufacturerName,
                vendorId = device.vendorId,
                deviceId = device.deviceId,
                productId = device.productId,
                serialNumber = serial,
                version = device.version,
                syncIdList = "",
                isSyncing = false,
                syncAt = null,
                updatedAt = now,
                createdAt = now
            )
            dao.insert(syncDevice)
            Timber.tag(TAG).d("device recording $syncDevice")
        } else {
            syncDevice.updatedAt = now
            dao.update(syncDevice)
            Timber.tag(TAG).d("device already recorded")
        }
        return syncDevice
    }

    fun removeDevice(): Boolean {
        val syncDevice = getSyncDevice(device)
        return if (syncDevice != null) {
            dao.delete(syncDevice) > 0
        } else {
            false
        }
    }

    fun startSync() {
        val syncDevice = getSyncDevice(device) ?: return
        if (syncDevice.syncAt == null || syncDevice.syncAt == 0L) {
            syncDevice.syncAt = Date().time
        }
        syncDevice.updatedAt = Date().time
        syncDevice.isSyncing = true
        dao.update(syncDevice)
    }

    fun stopSync() {
        val syncDevice = getSyncDevice(device) ?: return
        syncDevice.updatedAt = Date().time
        syncDevice.isSyncing = false
        dao.update(syncDevice)
    }

    fun updateIdList(ids: List<Int>?) {
        val sb = StringBuilder()
        if (ids != null) {
            var i = 1
            for (id in ids) {
                sb.append(id)
                if (i++ != ids.size) {
                    sb.append(",")
                }
            }
        }
        val strIdList = sb.toString()
        val syncDevice = getSyncDevice(device) ?: return
        syncDevice.syncIdList = strIdList
        syncDevice.syncAt = Date().time
        syncDevice.updatedAt = Date().time
        dao.update(syncDevice)
    }

    fun getIdList(): List<Int> {
        val syncDevice = getSyncDevice(device) ?: return emptyList()
        return getIdList(syncDevice)
    }

    fun getIdList(syncDevice: SyncDevice): List<Int> {
        val strIdList = syncDevice.syncIdList ?: ""
        if (strIdList.isEmpty()) {
            return emptyList()
        }
        return strIdList.split(",").map { it.toInt() }
    }

    fun getSyncDevice(device: UsbDevice): SyncDevice? {
        val uuid = getUUIDFromDevice(device)
        Timber.tag(TAG).d("获取数据库中的同步设备ORM:$uuid")
        val found = dao.getByUuid(uuid)
        if (found != null) {
            return found
        } else {
            Timber.tag(TAG).d("没有找到对应的设备")
            return null
        }
    }

    fun getDevice(): UsbDevice = device

    fun setDevice(device: UsbDevice) {
        this.device = device
    }

    fun getAllSyncDevices(): List<SyncDevice> = dao.getAll()

    private fun getUUIDFromDevice(device: UsbDevice): String {
        val cd = CameraDetector(device)
        return cd.getDeviceUniqName()
    }

    companion object {
        const val TAG = "SyncDeviceManager"
    }
}
