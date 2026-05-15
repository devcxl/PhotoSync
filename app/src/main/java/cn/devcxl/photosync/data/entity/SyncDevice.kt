package cn.devcxl.photosync.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * @author devcxl
 */
@Entity(tableName = "sync_device")
data class SyncDevice(
    @PrimaryKey
    @ColumnInfo(name = "device_uuid")
    var deviceUUID: String = "",
    @ColumnInfo(name = "device_name")
    var deviceName: String? = null,
    @ColumnInfo(name = "product_name")
    var productName: String? = null,
    @ColumnInfo(name = "manufacturer_name")
    var manufacturerName: String? = null,
    @ColumnInfo(name = "vendor_id")
    var vendorId: Int? = null,
    @ColumnInfo(name = "device_id")
    var deviceId: Int? = null,
    @ColumnInfo(name = "product_id")
    var productId: Int? = null,
    @ColumnInfo(name = "serial_number")
    var serialNumber: String? = null,
    @ColumnInfo(name = "version")
    var version: String? = null,
    @ColumnInfo(name = "sync_idlist_json")
    var syncIdList: String? = null,
    @ColumnInfo(name = "is_syncing")
    var isSyncing: Boolean = false,
    @ColumnInfo(name = "sync_at")
    var syncAt: Long? = null,
    @ColumnInfo(name = "updated_at")
    var updatedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    var createdAt: Long? = null
)
