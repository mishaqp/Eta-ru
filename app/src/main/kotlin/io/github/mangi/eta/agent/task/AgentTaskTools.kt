package io.github.mangi.eta.agent.task

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.AgentTaskCatchup
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.db.AgentTaskModes
import io.github.mangi.eta.data.db.AgentTaskSchedules
import io.github.mangi.eta.data.repository.AgentTaskRepository
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Synchronous bridge from the model tool loop to the durable task repository. */
internal object AgentTaskTools {
    fun execute(
        context: Context,
        name: String,
        args: JSONObject,
    ): AgentModelClient.ToolResult =
        runCatching {
            runBlocking(Dispatchers.IO) {
                val repository = AgentTaskRepository(context.applicationContext)
                val scheduler = AgentTaskScheduler(context.applicationContext)
                when (name) {
                    AgentTaskToolNames.SCHEDULE -> schedule(repository, scheduler, args)
                    AgentTaskToolNames.LIST -> list(repository, args)
                    AgentTaskToolNames.HISTORY -> history(repository, args)
                    AgentTaskToolNames.DELETE -> delete(repository, scheduler, args)
                    AgentTaskToolNames.PAUSE -> pause(repository, scheduler, args)
                    AgentTaskToolNames.RESUME -> resume(repository, scheduler, args)
                    AgentTaskToolNames.TRIGGER -> trigger(repository, scheduler, args)
                    else -> failure("UNKNOWN_TOOL", "unknown task tool: $name")
                }
            }
        }.getOrElse { throwable ->
            failure("TASK_TOOL_ERROR", throwable.message ?: throwable.javaClass.simpleName)
        }.let(::text)

    private suspend fun schedule(
        repository: AgentTaskRepository,
        scheduler: AgentTaskScheduler,
        args: JSONObject,
    ): String {
        val now = System.currentTimeMillis()
        val name = args.requiredString("name", 1, 80)
        val description = args.optString("description").trim().takeIf(String::isNotBlank)
            ?.take(500)
        val mode = args.optString("mode").trim().also {
            require(it == AgentTaskModes.LLM || it == AgentTaskModes.DIRECT) {
                "mode must be llm or direct"
            }
        }
        val prompt = args.optString("prompt").trim().takeIf(String::isNotBlank)?.take(4_000)
        val actions = args.optJSONArray("actions")?.let { copyActions(it) }
        when (mode) {
            AgentTaskModes.LLM -> {
                require(!prompt.isNullOrBlank()) { "llm task requires prompt" }
                require(actions == null || actions.length() == 0) {
                    "llm task cannot contain direct actions"
                }
            }
            AgentTaskModes.DIRECT -> {
                val directActions = actions ?: error("direct task requires actions")
                require(directActions.length() > 0) { "direct task requires actions" }
                require(prompt.isNullOrBlank()) { "direct task cannot contain prompt" }
                validateActions(directActions)
            }
        }

        val scheduleType = args.optString("schedule_type").trim().also {
            require(it == AgentTaskSchedules.ONCE || it == AgentTaskSchedules.CRON) {
                "schedule_type must be once or cron"
            }
        }
        val at = args.optionalPositiveLong("at_unix_ms")
        val cron = args.optString("cron_expression").trim().takeIf(String::isNotBlank)
        require(
            (scheduleType == AgentTaskSchedules.ONCE && at != null && cron == null) ||
                (scheduleType == AgentTaskSchedules.CRON && at == null && cron != null)
        ) { "once requires at_unix_ms; cron requires cron_expression" }
        if (cron != null) {
            require(AgentTaskCronParser.parse(cron).isSuccess) {
                "invalid cron expression: $cron"
            }
        }
        val timezone = args.optString("timezone").trim()
            .takeIf(String::isNotBlank)
            ?: ZoneId.systemDefault().id
        runCatching { ZoneId.of(timezone) }.getOrElse {
            error("invalid IANA timezone: $timezone")
        }
        val startAt = args.optionalPositiveLong("start_at_unix_ms")
        val endAt = args.optionalPositiveLong("end_at_unix_ms")
        require(startAt == null || endAt == null || endAt >= startAt) {
            "end_at_unix_ms must be after start_at_unix_ms"
        }
        require(at == null || startAt == null || at >= startAt) {
            "at_unix_ms must be after start_at_unix_ms"
        }
        require(at == null || endAt == null || at <= endAt) {
            "at_unix_ms must be before end_at_unix_ms"
        }
        val maxRuns = if (args.has("max_runs") && !args.isNull("max_runs")) {
            args.optInt("max_runs").also { require(it in 1..1_000) }
        } else {
            null
        }
        val catchup = args.optString("catchup", AgentTaskCatchup.FIRE_ONCE).trim().also {
            require(it in CATCHUP_VALUES) { "catchup must be skip, fire_once, or fire_all" }
        }
        val task = AgentTaskEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            mode = mode,
            prompt = prompt,
            actionsJson = actions?.toString(),
            scheduleType = scheduleType,
            atUnixMs = at,
            cronExpression = cron,
            timezone = timezone,
            startAtUnixMs = startAt,
            endAtUnixMs = endAt,
            enabled = true,
            createdAt = now,
            updatedAt = now,
            maxRuns = maxRuns,
            catchup = catchup,
            notifyOnSuccess = args.optBoolean("notify_on_success", true),
        )
        val scheduled = scheduler.schedule(task)
        return JSONObject()
            .put("ok", true)
            .put("task_id", scheduled.id)
            .put("name", scheduled.name)
            .put("mode", scheduled.mode)
            .put("schedule_type", scheduled.scheduleType)
            .putNullable("next_run_at", scheduled.nextRunAt)
            .put("enabled", scheduled.enabled)
            .toString()
    }

    private suspend fun list(
        repository: AgentTaskRepository,
        args: JSONObject,
    ): String {
        val includeDisabled = args.optBoolean("include_disabled", true)
        val limit = args.optInt("limit", 100).coerceIn(1, 100)
        val items = JSONArray()
        repository.all()
            .asSequence()
            .filter { includeDisabled || it.enabled }
            .take(limit)
            .forEach { items.put(it.toJson()) }
        return JSONObject()
            .put("ok", true)
            .put("count", items.length())
            .put("items", items)
            .toString()
    }

    private suspend fun history(
        repository: AgentTaskRepository,
        args: JSONObject,
    ): String {
        val taskId = args.requiredTaskId()
        val task = repository.byId(taskId) ?: return failure("NOT_FOUND", "task not found: $taskId")
        val limit = args.optInt("limit", 20).coerceIn(1, 100)
        val runs = JSONArray()
        repository.recentRuns(taskId, limit).forEach { runs.put(it.toJson()) }
        return JSONObject()
            .put("ok", true)
            .put("task", task.toJson())
            .put("runs", runs)
            .toString()
    }

    private suspend fun delete(
        repository: AgentTaskRepository,
        scheduler: AgentTaskScheduler,
        args: JSONObject,
    ): String {
        val taskId = args.requiredTaskId()
        if (repository.byId(taskId) == null) return failure("NOT_FOUND", "task not found: $taskId")
        scheduler.cancel(taskId)
        repository.deleteWithHistory(taskId)
        return JSONObject().put("ok", true).put("deleted", taskId).toString()
    }

    private suspend fun pause(
        repository: AgentTaskRepository,
        scheduler: AgentTaskScheduler,
        args: JSONObject,
    ): String {
        val taskId = args.requiredTaskId()
        val task = repository.byId(taskId) ?: return failure("NOT_FOUND", "task not found: $taskId")
        scheduler.cancel(taskId)
        repository.update(task.copy(enabled = false, nextRunAt = null, updatedAt = System.currentTimeMillis()))
        return JSONObject().put("ok", true).put("paused", taskId).toString()
    }

    private suspend fun resume(
        repository: AgentTaskRepository,
        scheduler: AgentTaskScheduler,
        args: JSONObject,
    ): String {
        val taskId = args.requiredTaskId()
        val task = repository.byId(taskId) ?: return failure("NOT_FOUND", "task not found: $taskId")
        val resumed = scheduler.schedule(
            task.copy(enabled = true, nextRunAt = null, updatedAt = System.currentTimeMillis())
        )
        return JSONObject()
            .put("ok", true)
            .put("resumed", taskId)
            .putNullable("next_run_at", resumed.nextRunAt)
            .put("enabled", resumed.enabled)
            .toString()
    }

    private suspend fun trigger(
        repository: AgentTaskRepository,
        scheduler: AgentTaskScheduler,
        args: JSONObject,
    ): String {
        val taskId = args.requiredTaskId()
        val task = repository.byId(taskId) ?: return failure("NOT_FOUND", "task not found: $taskId")
        require(task.enabled) { "task is paused" }
        scheduler.triggerNow(taskId)
        return JSONObject().put("ok", true).put("triggered", taskId).toString()
    }

    private fun copyActions(source: JSONArray): JSONArray = JSONArray().also { target ->
        require(source.length() in 1..50) { "actions must contain 1..50 items" }
        for (index in 0 until source.length()) {
            val item = source.optJSONObject(index)
                ?: error("actions[$index] must be an object")
            val tool = item.optString("tool").trim()
            require(tool.isNotBlank()) { "actions[$index].tool is required" }
            val args = item.optJSONObject("args") ?: JSONObject()
            target.put(
                JSONObject()
                    .put("tool", tool)
                    .put("args", JSONObject(args.toString())),
            )
        }
    }

    private fun validateActions(actions: JSONArray) {
        for (index in 0 until actions.length()) {
            val action = actions.getJSONObject(index)
            val tool = action.getString("tool")
            require(tool in DIRECT_ACTION_TOOL_NAMES) {
                "unsupported direct action tool at index $index: $tool"
            }
            require(tool !in AgentTaskToolNames.ALL) {
                "task management tools cannot be nested"
            }
        }
    }

    private fun JSONObject.requiredTaskId(): String = requiredString("task_id", 1, 100)

    private fun JSONObject.requiredString(key: String, min: Int, max: Int): String =
        optString(key).trim().also {
            require(it.length in min..max) { "$key must contain $min..$max characters" }
        }

    private fun JSONObject.optionalPositiveLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return optLong(key).also { require(it > 0L) { "$key must be positive" } }
    }

    private fun AgentTaskEntity.toJson(): JSONObject = JSONObject()
        .put("task_id", id)
        .put("name", name)
        .put("description", description ?: JSONObject.NULL)
        .put("mode", mode)
        .put("schedule_type", scheduleType)
        .put("at_unix_ms", atUnixMs ?: JSONObject.NULL)
        .put("cron_expression", cronExpression ?: JSONObject.NULL)
        .put("timezone", timezone ?: JSONObject.NULL)
        .put("enabled", enabled)
        .put("next_run_at", nextRunAt ?: JSONObject.NULL)
        .put("last_run_at", lastRunAt ?: JSONObject.NULL)
        .put("last_outcome", lastOutcome ?: JSONObject.NULL)
        .put("last_error", lastError ?: JSONObject.NULL)
        .put("runs_so_far", runsSoFar)
        .put("max_runs", maxRuns ?: JSONObject.NULL)
        .put("catchup", catchup)

    private fun io.github.mangi.eta.data.db.AgentTaskRunEntity.toJson(): JSONObject = JSONObject()
        .put("run_id", id)
        .put("scheduled_at", scheduledAt)
        .put("started_at", startedAt)
        .put("finished_at", finishedAt ?: JSONObject.NULL)
        .put("outcome", outcome)
        .put("manual", manual)
        .put("result", result ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL)

    private fun JSONObject.putNullable(key: String, value: Long?): JSONObject =
        put(key, value ?: JSONObject.NULL)

    private fun text(content: String) = AgentModelClient.ToolResult(content)

    private fun failure(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .toString()

    private val CATCHUP_VALUES = setOf(
        AgentTaskCatchup.SKIP,
        AgentTaskCatchup.FIRE_ONCE,
        AgentTaskCatchup.FIRE_ALL,
    )

    private val DIRECT_ACTION_TOOL_NAMES = setOf(
        "get_current_context", "search_apps", "launch_app", "open_uri", "observe_screen",
        "input_text", "replace_text", "clear_text", "set_clipboard", "get_clipboard",
        "paste_text", "press_key", "wait", "wait_for_text", "wait_for_package", "tap",
        "tap_area", "tap_element", "long_press", "long_press_element", "swipe", "scroll",
        "scroll_element", "open_system_panel", "set_alarm", "set_timer", "device_status",
        "network_info", "top_memory_apps", "top_storage_apps", "media_control", "set_volume",
        "get_setting", "wifi_credentials", "recent_notifications", "search_notification_history",
        "recent_app_activity", "app_usage_summary", "get_current_location", "get_device_environment",
        "search_clipboard_history", "get_health_summary", "read_sms_code", "get_logcat", "search_media",
        "search_audio", "search_recordings", "search_files", "search_calendar_events", "search_contacts",
        "search_call_history", "search_messages", "search_downloads", "set_setting", "set_device_state",
        "app_state_control", "browser_use", "read_image", "terminal", "run_command", "read_file",
        "write_file", "list_directory", "memory_get", "memory_write",
    )
}
