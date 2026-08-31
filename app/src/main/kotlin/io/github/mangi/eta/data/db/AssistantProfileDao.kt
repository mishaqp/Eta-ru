package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AssistantProfileDao {
    @Query(
        "SELECT * FROM assistant_profiles " +
            "ORDER BY sort_order ASC, created_at ASC"
    )
    fun profilesFlow(): Flow<List<AssistantProfileEntity>>

    @Query(
        "SELECT * FROM assistant_profiles " +
            "ORDER BY sort_order ASC, created_at ASC"
    )
    suspend fun profiles(): List<AssistantProfileEntity>

    @Query("SELECT * FROM assistant_profiles WHERE id = :id LIMIT 1")
    suspend fun profileById(id: String): AssistantProfileEntity?

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM assistant_profiles")
    suspend fun maxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: AssistantProfileEntity)

    @Query("DELETE FROM assistant_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}
