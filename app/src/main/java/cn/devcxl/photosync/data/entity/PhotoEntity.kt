package cn.devcxl.photosync.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a photo imported from a USB PTP device.
 */
@Entity(
    tableName = "photos",
    indices = [Index(value = ["path"], unique = true)]
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "name") val name: String? = null,
    @ColumnInfo(name = "uri_string") val uriString: String? = null,
    /**
     * PTP object handle this photo was imported from, or null for rows imported before
     * the handle was recorded.
     *
     * A handle is only meaningful inside the session that produced it, so a null value
     * means "this photo cannot be located on the camera again".
     */
    @ColumnInfo(name = "object_handle") val objectHandle: Int? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)