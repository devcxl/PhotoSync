package cn.devcxl.photosync.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "photos",
    indices = [Index(value = ["path"], unique = true)]
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val name: String? = null,
    val uriString: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)