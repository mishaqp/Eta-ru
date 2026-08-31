package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.data.db.AgentTaskDao
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.AgentTaskRunDao
import io.github.mangi.eta.data.db.AgentTaskRunEntity
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.flow.Flow

internal class AgentTaskRepository(context: Context) {
    private val appContext = context.applicationContext

    private fun database(): EtaDatabase = EtaDatabase.get(appContext)
    private fun tasks(): AgentTaskDao = database().agentTaskDao()
    private fun runs(): AgentTaskRunDao = database().agentTaskRunDao()

    fun observeAll(): Flow<List<AgentTaskEntity>> = tasks().observeAll()
    suspend fun all(): List<AgentTaskEntity> = tasks().all()
    suspend fun enabled(): List<AgentTaskEntity> = tasks().enabled()
    suspend fun byId(id: String): AgentTaskEntity? = tasks().byId(id)
    suspend fun upsert(task: AgentTaskEntity) = tasks().upsert(task)
    suspend fun update(task: AgentTaskEntity) = tasks().update(task)

    suspend fun deleteWithHistory(id: String) {
        database().withTransaction {
            runs().deleteForTask(id)
            tasks().deleteById(id)
        }
    }

    suspend fun recentRuns(taskId: String, limit: Int = 20): List<AgentTaskRunEntity> =
        runs().recent(taskId, limit.coerceIn(1, 100))

    suspend fun mostRecentRun(taskId: String): AgentTaskRunEntity? = runs().mostRecent(taskId)
    suspend fun findScheduledRun(taskId: String, scheduledAt: Long): AgentTaskRunEntity? =
        runs().findScheduled(taskId, scheduledAt)
    suspend fun strandedRuns(cutoff: Long): List<AgentTaskRunEntity> = runs().stranded(cutoff)
    suspend fun successfulCount(taskId: String): Int = runs().successfulCount(taskId)
    suspend fun insertRun(run: AgentTaskRunEntity) = runs().insert(run)
    suspend fun updateRun(run: AgentTaskRunEntity) = runs().update(run)
    suspend fun trimRuns(taskId: String, keep: Int = HISTORY_LIMIT) = runs().trim(taskId, keep)

    suspend fun markProcessLost(run: AgentTaskRunEntity, now: Long = System.currentTimeMillis()) {
        if (run.finishedAt != null) return
        runs().update(
            run.copy(
                finishedAt = now,
                outcome = io.github.mangi.eta.data.db.AgentTaskOutcomes.PROCESS_LOST,
                error = "worker process ended before completion",
            )
        )
    }

    companion object {
        const val HISTORY_LIMIT = 100
    }
}
