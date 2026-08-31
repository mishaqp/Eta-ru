package io.github.mangi.eta.agent.task

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.AgentTaskCatchup
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.AgentTaskOutcomes
import io.github.mangi.eta.data.db.AgentTaskRunEntity
import io.github.mangi.eta.data.repository.AgentTaskRepository
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Rebuilds WorkManager jobs after boot, update, or an app-process restart. */
internal class AgentTaskBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in RECOVERY_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                recover(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val recoveryLock = Mutex()
        private val RECOVERY_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )

        /** Also called at Application startup because WorkManager may start before BOOT_COMPLETED. */
        suspend fun recover(context: Context) {
            if (!recoveryLock.tryLock()) return
            try {
                val appContext = context.applicationContext
                val repository = AgentTaskRepository(appContext)
                val scheduler = AgentTaskScheduler(appContext)
                val now = System.currentTimeMillis()
                repository.strandedRuns(now - STRANDED_RUN_CUTOFF_MS).forEach { run ->
                    repository.markProcessLost(run, now)
                }
                repository.enabled().forEach { task ->
                    recoverTask(repository, scheduler, task, now)
                }
            } catch (throwable: Throwable) {
                AndroidAgentLogger.warn(
                    "Agent task recovery failed: ${throwable.message ?: throwable.javaClass.simpleName}"
                )
            } finally {
                recoveryLock.unlock()
            }
        }

        private suspend fun recoverTask(
            repository: AgentTaskRepository,
            scheduler: AgentTaskScheduler,
            task: AgentTaskEntity,
            now: Long,
        ) {
            val dueAt = task.nextRunAt ?: return scheduler.schedule(task)
            if (dueAt > now) return scheduler.schedule(task)
            val missed = missedSlots(task, now)
            if (missed.isEmpty()) return scheduler.schedule(task)

            when (task.catchup) {
                AgentTaskCatchup.SKIP -> {
                    missed.forEach { scheduledAt ->
                        repository.insertRun(
                            AgentTaskRunEntity(
                                id = UUID.randomUUID().toString(),
                                taskId = task.id,
                                mode = task.mode,
                                scheduledAt = scheduledAt,
                                startedAt = now,
                                finishedAt = now,
                                outcome = AgentTaskOutcomes.SKIPPED_CATCHUP,
                                error = "missed while Eta was not running",
                            )
                        )
                    }
                    val updated = task.copy(
                        lastRunAt = missed.last(),
                        lastOutcome = AgentTaskOutcomes.SKIPPED_CATCHUP,
                        lastError = "${missed.size} scheduled run(s) skipped during recovery",
                        nextRunAt = null,
                        updatedAt = now,
                    )
                    repository.update(updated)
                    scheduler.schedule(updated)
                }

                AgentTaskCatchup.FIRE_ONCE -> {
                    val scheduledAt = missed.last()
                    scheduler.enqueueCatchup(task.id, scheduledAt, 0)
                    prepareAfterCatchup(repository, scheduler, task, now, missed, fireOnce = true)
                }

                AgentTaskCatchup.FIRE_ALL -> {
                    missed.forEachIndexed { index, scheduledAt ->
                        scheduler.enqueueCatchup(task.id, scheduledAt, index)
                    }
                    prepareAfterCatchup(repository, scheduler, task, now, missed, fireOnce = false)
                }

                else -> scheduler.schedule(task)
            }
        }

        private suspend fun prepareAfterCatchup(
            repository: AgentTaskRepository,
            scheduler: AgentTaskScheduler,
            task: AgentTaskEntity,
            now: Long,
            missed: List<Long>,
            fireOnce: Boolean,
        ) {
            if (task.scheduleType == io.github.mangi.eta.data.db.AgentTaskSchedules.ONCE && fireOnce) {
                // Keep the due timestamp until the catch-up worker commits success/failure. If the
                // process dies again, the next recovery pass can enqueue it safely; run history
                // suppresses a duplicate after a completed attempt.
                repository.update(task.copy(nextRunAt = now, updatedAt = now))
                return
            }
            val future = AgentTaskScheduler.nextRunAt(
                task.copy(nextRunAt = null),
                now,
            )
            repository.update(
                task.copy(
                    nextRunAt = future ?: missed.last(),
                    updatedAt = now,
                )
            )
            if (future != null) scheduler.schedule(task.copy(nextRunAt = future))
        }

        private fun missedSlots(task: AgentTaskEntity, now: Long): List<Long> {
            val available = task.maxRuns?.let { (it - task.runsSoFar).coerceAtLeast(0) }
            val limit = minOf(MAX_CATCHUP_RUNS, available ?: MAX_CATCHUP_RUNS)
            if (limit == 0) return emptyList()
            return when (task.scheduleType) {
                io.github.mangi.eta.data.db.AgentTaskSchedules.ONCE ->
                    listOfNotNull(task.atUnixMs)
                        .filter { it <= now && task.lastRunAt == null }
                        .take(limit)

                io.github.mangi.eta.data.db.AgentTaskSchedules.CRON -> {
                    val expression = task.cronExpression ?: return emptyList()
                    val cron = AgentTaskCronParser.parse(expression).getOrNull() ?: return emptyList()
                    val zone = task.timezone
                        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                        ?: ZoneId.systemDefault()
                    var cursor = task.nextRunAt ?: task.startAtUnixMs ?: now
                    val result = ArrayList<Long>(limit)
                    repeat(limit) {
                        val next = AgentTaskCronParser.nextExecution(
                            cron,
                            Instant.ofEpochMilli(cursor - 1L).atZone(zone),
                        )?.toInstant()?.toEpochMilli() ?: return@repeat
                        if (next > now) return@repeat
                        if (task.endAtUnixMs != null && next > task.endAtUnixMs) return@repeat
                        result += next
                        cursor = next
                    }
                    result
                }

                else -> emptyList()
            }
        }

        private const val STRANDED_RUN_CUTOFF_MS = 30 * 60 * 1_000L
        private const val MAX_CATCHUP_RUNS = 20
    }
}
