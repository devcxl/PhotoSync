package cn.devcxl.photosync.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity storing a reusable user-defined or built-in edit preset.
 */
@Entity(tableName = "user_presets")
data class UserPresetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "graph_subset_json") val graphSubsetJson: String,
    @ColumnInfo(name = "lut_file_path") val lutFilePath: String? = null,
    @ColumnInfo(name = "is_builtin") val isBuiltin: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
