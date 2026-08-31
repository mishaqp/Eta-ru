package io.github.mangi.eta.agent.task

import android.content.Context
import androidx.work.Data
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.AgentTaskSchedules
import io.github.mangi.eta.data.repository.AgentTaskRepository
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.max

internal class AgentTaskScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val repository = AgentTaskRepository(appContext)
    private val workManager get() = WorkManager.getInstance(appContext)

    suspend fun schedule(task: AgentTaskEntity): AgentTaskEntity {
        val now = System.currentTimeMillis()
        val next = nextRunAt(task, now)
        if (next == null) {
            val terminal = task.copy(
                enabled = false,
                nextRunAt = null,
                updatedAt = now,
            )
            repository.update(terminal)
            cancel(task.id)
            return terminal
        }
        val updated = task.copy(nextRunAt = next, updatedAt = now)
        repository.update(updated)
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInitialDelay(max(0L, next - now), TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putString(AgentTaskWorker.KEY_TASK_ID, task.id)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30L,
                TimeUnit.SECONDS,
            )
            .build()
        workManager.enqueueUniqueWork(
            regularWorkName(task.id),
            ExistingWorkPolicy.REPLACE,
            request,
        )
        return updated
    }

    suspend fun scheduleAllEnabled() {
        repository.enabled().forEach { task -> schedule(task) }
    }

    suspend fun triggerNow(taskId: String) {
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInitialDelay(0L, TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putString(AgentTaskWorker.KEY_TASK_ID, taskId)
                    .putBoolean(AgentTaskWorker.KEY_MANUAL, true)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30L,
                TimeUnit.SECONDS,
            )
            .build()
        workManager.enqueueUniqueWork(
            manualWorkName(taskId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    suspend fun enqueueCatchup(taskId: String, scheduledAt: Long, sequence: Int) {
        val request = OneTimeWorkRequestBuilder<AgentTaskWorker>()
            .setInitialDelay(0L, TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putString(AgentTaskWorker.KEY_TASK_ID, taskId)
                    .putLong(AgentTaskWorker.KEY_SCHEDULED_AT, scheduledAt)
                    .putBoolean(AgentTaskWorker.KEY_CATCHUP, true)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                30L,
                TimeUnit.SECONDS,
            )
            .build()
        workManager.enqueueUniqueWork(
            "eta_task_${taskId}_catchup_$sequence",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(taskId: String) {
        workManager.cancelUniqueWork(regularWorkName(taskId))
        workManager.cancelUniqueWork(manualWorkName(taskId))
    }

    companion object {
        fun nextRunAt(task: AgentTaskEntity, now: Long): Long? {
            if (!task.enabled) return null
            if (task.maxRuns != null && task.runsSoFar >= task.maxRuns) return null
            if (task.endAtUnixMs != null && now > task.endAtUnixMs) return null
            return when (task.scheduleType) {
                AgentTaskSchedules.ONCE -> {
                    val at = task.atUnixMs ?: return null
                    if (task.lastRunAt != null) null else at
                }
                AgentTaskSchedules.CRON -> {
                    val expression = task.cronExpression ?: return null
                    val cron = AgentTaskCronParser.parse(expression).getOrNull() ?: return null
                    val zone = task.timezone
                        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                        ?: ZoneId.systemDefault()
                    val basis = max(now, task.startAtUnixMs ?: 0L) - 1L
                    val next = AgentTaskCronParser.nextExecution(
                        cron,
                        Instant.ofEpochMilli(basis).atZone(zone),
                    )?.toInstant()?.toEpochMilli() ?: return null
                    if (task.endAtUnixMs != null && next > task.endAtUnixMs) null else next
                }
                else -> null
            }
        }

        private fun regularWorkName(taskId: String) = "eta_task_$taskId"
        private fun manualWorkName(taskId: String) = "eta_task_${taskId}_manual"
    }
}
