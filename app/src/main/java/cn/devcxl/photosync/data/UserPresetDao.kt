package cn.devcxl.photosync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

import cn.devcxl.photosync.data.entity.UserPresetEntity

/**
 * DAO for the reusable `Preset` library.
 *
 * Built-in presets are seeded idempotently and protected at the SQL level: every mutating
 * statement carries `AND is_builtin = 0`, and [upsertUserPreset] rejects built-in targets before
 * touching the table.
 */
@Dao
interface UserPresetDao {

    @Query("SELECT * FROM user_presets ORDER BY is_builtin DESC, created_at ASC")
    fun observeAllFlow(): Flow<List<UserPresetEntity>>

    @Query("SELECT * FROM user_presets ORDER BY is_builtin DESC, created_at ASC")
    suspend fun getAll(): List<UserPresetEntity>

    @Query("SELECT * FROM user_presets WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): UserPresetEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: UserPresetEntity): Long

    /** Inserts every built-in preset without touching an existing row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entities: List<UserPresetEntity>)

    @Query(
        "UPDATE user_presets SET name = :name, graph_subset_json = :graphJson, " +
            "lut_file_path = :lutPath WHERE id = :id AND is_builtin = 0"
    )
    suspend fun updateUserRow(id: String, name: String, graphJson: String, lutPath: String?)

    /** Updates the localized display name of a seeded built-in row. */
    @Query("UPDATE user_presets SET name = :name WHERE id = :id AND is_builtin = 1")
    suspend fun updateBuiltinName(id: String, name: String)

    /**
     * Inserts or updates a user-created preset inside one transaction while keeping built-in rows
     * immutable even when a caller supplies a colliding built-in id.
     */
    @Transaction
    suspend fun upsertUserPreset(entity: UserPresetEntity) {
        if (entity.isBuiltin || getById(entity.id)?.isBuiltin == true) return
        val inserted = insertIgnore(entity)
        if (inserted == -1L) {
            updateUserRow(entity.id, entity.name, entity.graphSubsetJson, entity.lutFilePath)
        }
    }

    @Query("UPDATE user_presets SET name = :newName WHERE id = :id AND is_builtin = 0")
    suspend fun rename(id: String, newName: String)

    @Query("DELETE FROM user_presets WHERE id = :id AND is_builtin = 0")
    suspend fun deleteById(id: String)
}
