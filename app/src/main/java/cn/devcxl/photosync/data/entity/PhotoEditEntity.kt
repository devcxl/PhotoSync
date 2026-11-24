package cn.devcxl.photosync.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity storing the non-destructive [cn.devcxl.photosync.editor.domain.EditGraph]
 * JSON and rendered preview cache path for a single imported photo.
 *
 * Rows cascade-delete automatically when the parent [PhotoEntity] is removed.
 */
@Entity(
    tableName = "photo_edits",
    foreignKeys = [
        ForeignKey(
            entity = PhotoEntity::class,
            parentColumns = ["id"],
            childColumns = ["photo_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["photo_id"], unique = true)]
)
data class PhotoEditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "photo_id") val photoId: Long,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "graph_json") val graphJson: String,
    @ColumnInfo(name = "preview_cache_path") val previewCachePath: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
