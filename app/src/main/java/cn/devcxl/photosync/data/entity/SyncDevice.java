package cn.devcxl.photosync.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.Ignore;

/**
 *
 *
 * device_uuid string  unique
 * device_name string
 * product_name string
 * manufacturer_name string
 * vendor_id integer
 * device_id integer
 * product_id integer
 * serial_number    string
 * version   string  (version of device)
 * sync_idlist_json text
 * is_syncing boolean
 * sync_at unix_timestamp
 * updated_at unix_timestamp
 * created_at unix_timestamp
 *
 * @author rainx
 */

@Entity(tableName = "sync_device")
public class SyncDevice {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "device_uuid")
    String deviceUUID;
    @ColumnInfo(name = "device_name")
    String deviceName;
    @ColumnInfo(name = "product_name")
    String productName;
    @ColumnInfo(name = "manufacturer_name")
    String manufacturerName;
    @ColumnInfo(name = "vendor_id")
    Integer vendorId;
    @ColumnInfo(name = "device_id")
    Integer deviceId;
    @ColumnInfo(name = "product_id")
    Integer productId;
    @ColumnInfo(name = "serial_number")
    String serialNumber;
    @ColumnInfo(name = "version")
    String version;
    @ColumnInfo(name = "sync_idlist_json")
    String syncIdList;
    @ColumnInfo(name = "is_syncing")
    boolean isSyncing;
    @ColumnInfo(name = "sync_at")
    Long syncAt;
    @ColumnInfo(name = "updated_at")
    Long updatedAt;
    @ColumnInfo(name = "created_at")
    Long createdAt;

    public SyncDevice() {
        // empty constructor
    }

    @Ignore
    public SyncDevice(@NonNull String deviceUUID, String deviceName, String productName, String manufacturerName, Integer vendorId, Integer deviceId, Integer productId, String serialNumber, String version, String syncIdList, boolean isSyncing, Long syncAt, Long updatedAt, Long createdAt) {
        this.deviceUUID = deviceUUID;
        this.deviceName = deviceName;
        this.productName = productName;
        this.manufacturerName = manufacturerName;
        this.vendorId = vendorId;
        this.deviceId = deviceId;
        this.productId = productId;
        this.serialNumber = serialNumber;
        this.version = version;
        this.syncIdList = syncIdList;
        this.isSyncing = isSyncing;
        this.syncAt = syncAt;
        this.updatedAt = updatedAt;
        this.createdAt = createdAt;
    }

    public String getDeviceUUID() {
        return deviceUUID;
    }

    public void setDeviceUUID(String deviceUUID) {
        this.deviceUUID = deviceUUID;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getManufacturerName() {
        return manufacturerName;
    }

    public void setManufacturerName(String manufacturerName) {
        this.manufacturerName = manufacturerName;
    }

    public Integer getVendorId() {
        return vendorId;
    }

    public void setVendorId(Integer vendorId) {
        this.vendorId = vendorId;
    }

    public Integer getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(Integer deviceId) {
        this.deviceId = deviceId;
    }

    public Integer getProductId() {
        return productId;
    }

    public void setProductId(Integer productId) {
        this.productId = productId;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getSyncIdList() {
        return syncIdList;
    }

    public void setSyncIdList(String syncIdList) {
        this.syncIdList = syncIdList;
    }

    public boolean isSyncing() {
        return isSyncing;
    }

    public void setSyncing(boolean syncing) {
        isSyncing = syncing;
    }

    public Long getSyncAt() {
        return syncAt;
    }

    public void setSyncAt(Long syncAt) {
        this.syncAt = syncAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "SyncDevice{" +
                "deviceUUID='" + deviceUUID + '\'' +
                ", deviceName='" + deviceName + '\'' +
                ", productName='" + productName + '\'' +
                ", manufacturerName='" + manufacturerName + '\'' +
                ", vendorId=" + vendorId +
                ", deviceId=" + deviceId +
                ", productId=" + productId +
                ", serialNumber='" + serialNumber + '\'' +
                ", version='" + version + '\'' +
                ", syncIdList='" + syncIdList + '\'' +
                ", isSyncing=" + isSyncing +
                ", syncAt=" + syncAt +
                ", updatedAt=" + updatedAt +
                ", createdAt=" + createdAt +
                '}';
    }
}
