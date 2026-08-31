package io.github.mangi.eta.agent.task

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.AgentTaskOutcomes
import io.github.mangi.eta.data.db.AgentTaskRunEntity
import io.github.mangi.eta.data.repository.AgentTaskRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Durable task execution boundary. Every scheduled fire gets a terminal history row. */
internal class AgentTaskWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    private val context = appContext.applicationContext
    private val repository = AgentTaskRepository(context)
    private val scheduler = AgentTaskScheduler(context)

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID)?.trim().orEmpty()
        if (taskId.isBlank()) return Result.failure()
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        return locks.getOrPut(taskId) { Mutex() }.withLock {
            executeLocked(taskId, manual)
        }
    }

    private suspend fun executeLocked(taskId: String, manual: Boolean): Result {
        val task = repository.byId(taskId) ?: return Result.success()
        if (!task.enabled) return Result.success()

        val now = System.currentTimeMillis()
        if (!manual && isOutsideBounds(task, now)) {
            finalizeDisabled(task, now, "task schedule has ended")
            return Result.success()
        }
        if (!manual && task.maxRuns != null && task.runsSoFar >= task.maxRuns) {
            finalizeDisabled(task, now, "maximum scheduled runs reached")
            return Result.success()
        }

        val scheduledAt = inputData.getLong(KEY_SCHEDULED_AT, 0L).takeIf { it > 0L }
            ?: task.nextRunAt
            ?: now
        if (!manual) {
            when (val previous = repository.findScheduledRun(task.id, scheduledAt)) {
                null -> Unit
                else -> {
                    if (previous.outcome == AgentTaskOutcomes.RUNNING) return Result.retry()
                    return Result.success()
                }
            }
        }

        // AgentRuntimeService is single-flight and replaces its current session. Never let a
        // background task cancel a foreground request or another task.
        val active = AgentRuntimeClient(context, AndroidAgentLogger).queryActiveRun()
        if (active is AgentRuntimeClient.ActiveRunQuery.Unavailable) return Result.retry()
        if (active is AgentRuntimeClient.ActiveRunQuery.Known && active.runId != null) {
            return Result.retry()
        }

        val runId = UUID.randomUUID().toString()
        val run = AgentTaskRunEntity(
            id = UUID.randomUUID().toString(),
            taskId = task.id,
            mode = task.mode,
            scheduledAt = scheduledAt,
            startedAt = now,
            outcome = AgentTaskOutcomes.RUNNING,
            manual = manual,
            runtimeRunId = runId.takeIf { task.mode == io.github.mangi.eta.data.db.AgentTaskModes.LLM },
        )
        repository.insertRun(run)

        return try {
            val outcome = when (task.mode) {
                io.github.mangi.eta.data.db.AgentTaskModes.DIRECT ->
                    AgentTaskActionRunner(context).run(task.actionsJson.orEmpty(), runId)
                io.github.mangi.eta.data.db.AgentTaskModes.LLM -> runLlm(task, runId)
                else -> AgentTaskActionRunner.Outcome(
                    outcome = AgentTaskOutcomes.FAILED,
                    error = "unsupported task mode: ${task.mode}",
                )
            }
            val finishedAt = System.currentTimeMillis()
            val finishedRun = run.copy(
                finishedAt = finishedAt,
                outcome = outcome.outcome,
                result = outcome.result,
                error = outcome.error,
            )
            repository.updateRun(finishedRun)
            finalizeTask(task, finishedRun, manual)
            maybeNotify(task, outcome.outcome, outcome.result ?: outcome.error)
            repository.trimRuns(task.id)
            Result.success()
        } catch (cancelled: CancellationException) {
            // Leave RUNNING as a recoverable journal row. Boot/app recovery marks it as lost.
            throw cancelled
        } catch (throwable: Throwable) {
            val finishedAt = System.currentTimeMillis()
            val finishedRun = run.copy(
                finishedAt = finishedAt,
                outcome = AgentTaskOutcomes.FAILED,
                error = throwable.message ?: throwable.javaClass.simpleName,
            )
            runCatching { repository.updateRun(finishedRun) }
            runCatching { finalizeTask(task, finishedRun, manual) }
            maybeNotify(task, AgentTaskOutcomes.FAILED, finishedRun.error)
            Result.success()
        }
    }

    private suspend fun runLlm(task: AgentTaskEntity, runId: String): AgentTaskActionRunner.Outcome =
        withContext(Dispatchers.IO) {
            val config = RuntimeConfigRepository.currentRuntimeConfig(task.assistantId)
                ?: return@withContext AgentTaskActionRunner.Outcome(
                    outcome = AgentTaskOutcomes.FAILED,
                    error = "no model is configured",
                )
            val prompt = buildString {
                appendLine("Это автономная фоновая задача Eta.")
                appendLine("Выполни запрос один раз и верни короткий результат пользователю.")
                appendLine("Не создавай и не изменяй другие фоновые задачи, если это явно не требуется запросом.")
                appendLine()
                append(task.prompt.orEmpty())
            }
            val request = AgentRuntimeWire.RunRequest(
                runId = runId,
                prompt = prompt,
                config = config,
                images = emptyList(),
                history = emptyList(),
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = "eta-task-${task.id}-$runId",
                    source = TASK_HANDOFF_SOURCE,
                    payload = JSONObject()
                        .put("task_id", task.id)
                        .put("task_name", task.name)
                        .put("assistant_id", task.assistantId)
                        .toString(),
                    dismissEntrySurfaceOnForegroundOperation = false,
                ),
            )
            val result = AgentRuntimeClient(context, AndroidAgentLogger).run(request) { }
            AgentRuntimeClient(context, AndroidAgentLogger).ackResult(result.runId.ifBlank { runId })
            if (result.ok) {
                AgentTaskActionRunner.Outcome(
                    outcome = AgentTaskOutcomes.SUCCESS,
                    result = result.content.take(MAX_RESULT_CHARS),
                )
            } else {
                AgentTaskActionRunner.Outcome(
                    outcome = if (result.error?.contains("超时", ignoreCase = true) == true) {
                        AgentTaskOutcomes.TIMED_OUT
                    } else {
                        AgentTaskOutcomes.FAILED
                    },
                    error = result.error ?: "runtime returned a failure",
                )
            }
        }

    private suspend fun finalizeTask(
        original: AgentTaskEntity,
        run: AgentTaskRunEntity,
        manual: Boolean,
    ) {
        val current = repository.byId(original.id) ?: return
        if (manual) return
        val nextRuns = (current.runsSoFar + 1).coerceAtMost(Int.MAX_VALUE)
        val updated = current.copy(
            runsSoFar = nextRuns,
            lastRunAt = run.finishedAt,
            lastOutcome = run.outcome,
            lastError = run.error,
            nextRunAt = null,
            updatedAt = System.currentTimeMillis(),
        )
        repository.update(updated)
        scheduler.schedule(updated)
    }

    private suspend fun finalizeDisabled(task: AgentTaskEntity, now: Long, reason: String) {
        repository.update(
            task.copy(
                enabled = false,
                nextRunAt = null,
                lastOutcome = task.lastOutcome,
                lastError = reason,
                updatedAt = now,
            )
        )
        scheduler.cancel(task.id)
    }

    private fun isOutsideBounds(task: AgentTaskEntity, now: Long): Boolean =
        task.endAtUnixMs?.let { now > it } == true

    private fun maybeNotify(task: AgentTaskEntity, outcome: String, detail: String?) {
        if (outcome == AgentTaskOutcomes.SUCCESS && !task.notifyOnSuccess) return
        AgentTaskNotifications.show(context, task, outcome, detail)
    }

    companion object {
        const val KEY_TASK_ID = "eta_task_id"
        const val KEY_MANUAL = "eta_task_manual"
        const val KEY_SCHEDULED_AT = "eta_task_scheduled_at"
        const val KEY_CATCHUP = "eta_task_catchup"
        const val TASK_HANDOFF_SOURCE = "eta_task"
        private const val MAX_RESULT_CHARS = 12_000
        private val locks = ConcurrentHashMap<String, Mutex>()
    }
}
