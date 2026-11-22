package cn.devcxl.photosync.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

import cn.devcxl.photosync.data.entity.UserPresetEntity

/**
 * DAO for the reusable `Preset` library.
 *
 * Built-in presets are seeded idempotently and protected at the SQL level: both mutating statements
 * carry `AND is_builtin = 0`, so no caller — including a future refactor that forgets to check
 * `isBuiltin` in Kotlin — can rewrite the curated library.
 */
@Dao
interface UserPresetDao {

    @Query("SELECT * FROM user_presets ORDER BY is_builtin DESC, created_at ASC")
    fun observeAllFlow(): Flow<List<UserPresetEntity>>

    @Query("SELECT * FROM user_presets ORDER BY is_builtin DESC, created_at ASC")
    suspend fun getAll(): List<UserPresetEntity>

    @Query("SELECT * FROM user_presets WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): UserPresetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserPresetEntity)

    /** Inserts every built-in preset without touching an existing row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(entities: List<UserPresetEntity>)

    @Query("UPDATE user_presets SET name = :newName WHERE id = :id AND is_builtin = 0")
    suspend fun rename(id: String, newName: String)

    @Query("DELETE FROM user_presets WHERE id = :id AND is_builtin = 0")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM user_presets")
    suspend fun deleteAll()

    companion object {
        /** Literal the mutating statements append to keep a built-in row read-only. */
        const val BUILTIN_GUARD = "AND is_builtin = 0"
    }
}
