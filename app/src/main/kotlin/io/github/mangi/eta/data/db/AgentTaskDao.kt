package io.github.mangi.eta.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AgentTaskDao {
    @Query("SELECT * FROM agent_tasks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<AgentTaskEntity>>

    @Query("SELECT * FROM agent_tasks ORDER BY created_at DESC")
    suspend fun all(): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE enabled = 1 ORDER BY next_run_at ASC")
    suspend fun enabled(): List<AgentTaskEntity>

    @Query("SELECT * FROM agent_tasks WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): AgentTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: AgentTaskEntity)

    @Update
    suspend fun update(task: AgentTaskEntity)

    @Query("DELETE FROM agent_tasks WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
internal interface AgentTaskRunDao {
    @Query("SELECT * FROM agent_task_runs WHERE task_id = :taskId ORDER BY started_at DESC LIMIT :limit")
    suspend fun recent(taskId: String, limit: Int): List<AgentTaskRunEntity>

    @Query("SELECT * FROM agent_task_runs WHERE task_id = :taskId ORDER BY started_at DESC LIMIT 1")
    suspend fun mostRecent(taskId: String): AgentTaskRunEntity?

    @Query(
        "SELECT * FROM agent_task_runs WHERE task_id = :taskId " +
            "AND scheduled_at = :scheduledAt AND manual = 0 " +
            "ORDER BY started_at DESC LIMIT 1"
    )
    suspend fun findScheduled(taskId: String, scheduledAt: Long): AgentTaskRunEntity?

    @Query("SELECT * FROM agent_task_runs WHERE finished_at IS NULL AND started_at < :cutoff")
    suspend fun stranded(cutoff: Long): List<AgentTaskRunEntity>

    @Query("SELECT COUNT(*) FROM agent_task_runs WHERE task_id = :taskId AND outcome = 'success' AND manual = 0")
    suspend fun successfulCount(taskId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(run: AgentTaskRunEntity)

    @Update
    suspend fun update(run: AgentTaskRunEntity)

    @Query("DELETE FROM agent_task_runs WHERE task_id = :taskId")
    suspend fun deleteForTask(taskId: String)

    @Query(
        "DELETE FROM agent_task_runs WHERE task_id = :taskId " +
            "AND id NOT IN (SELECT id FROM agent_task_runs WHERE task_id = :taskId " +
            "ORDER BY started_at DESC LIMIT :keep)"
    )
    suspend fun trim(taskId: String, keep: Int)
}
