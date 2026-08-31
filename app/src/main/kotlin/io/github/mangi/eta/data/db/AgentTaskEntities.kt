package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.mangi.eta.data.model.AssistantProfileDefaults

/** Persistent user task. The task row is the source of truth; MEMORY.md is not a scheduler. */
@Entity(
    tableName = "agent_tasks",
    indices = [
        Index(value = ["enabled"]),
        Index(value = ["next_run_at"]),
        Index(value = ["assistant_id"]),
    ],
)
internal data class AgentTaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "assistant_id", defaultValue = "'eta-default-assistant'")
    val assistantId: String = AssistantProfileDefaults.DEFAULT_ID,
    val description: String? = null,
    val mode: String,
    val prompt: String? = null,
    @ColumnInfo(name = "actions_json") val actionsJson: String? = null,
    @ColumnInfo(name = "schedule_type") val scheduleType: String,
    @ColumnInfo(name = "at_unix_ms") val atUnixMs: Long? = null,
    @ColumnInfo(name = "cron_expression") val cronExpression: String? = null,
    val timezone: String? = null,
    @ColumnInfo(name = "start_at_unix_ms") val startAtUnixMs: Long? = null,
    @ColumnInfo(name = "end_at_unix_ms") val endAtUnixMs: Long? = null,
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_run_at") val lastRunAt: Long? = null,
    @ColumnInfo(name = "next_run_at") val nextRunAt: Long? = null,
    @ColumnInfo(name = "last_outcome") val lastOutcome: String? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "runs_so_far") val runsSoFar: Int = 0,
    @ColumnInfo(name = "max_runs") val maxRuns: Int? = null,
    val catchup: String = AgentTaskCatchup.FIRE_ONCE,
    @ColumnInfo(name = "notify_on_success") val notifyOnSuccess: Boolean = true,
)

@Entity(
    tableName = "agent_task_runs",
    indices = [
        Index(value = ["task_id", "started_at"]),
        Index(value = ["task_id", "outcome"]),
    ],
)
internal data class AgentTaskRunEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    val mode: String,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long? = null,
    val outcome: String,
    @ColumnInfo(name = "manual", defaultValue = "0") val manual: Boolean = false,
    @ColumnInfo(name = "runtime_run_id") val runtimeRunId: String? = null,
    val result: String? = null,
    val error: String? = null,
)

internal object AgentTaskModes {
    const val LLM = "llm"
    const val DIRECT = "direct"
}

internal object AgentTaskSchedules {
    const val ONCE = "once"
    const val CRON = "cron"
}

internal object AgentTaskCatchup {
    const val SKIP = "skip"
    const val FIRE_ONCE = "fire_once"
    const val FIRE_ALL = "fire_all"
}

internal object AgentTaskOutcomes {
    const val RUNNING = "running"
    const val SUCCESS = "success"
    const val FAILED = "failed"
    const val TIMED_OUT = "timed_out"
    const val PROCESS_LOST = "process_lost"
    const val SKIPPED_CATCHUP = "skipped_catchup"
    const val CONCURRENT_SKIP = "concurrent_skip"
}
