package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.task.AgentTaskToolNames
import org.json.JSONArray
import org.json.JSONObject

/** Schemas for durable tasks; execution lives in AgentTaskTools. */
internal object AgentTaskToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = AgentTaskToolNames.SCHEDULE,
                    description = "Create a persistent Eta background task. Use this for any request that must happen later or repeatedly; MEMORY.md is not a scheduler. mode=llm runs the prompt through Eta at fire time. mode=direct runs a fixed action list without spending model tokens. For a one-time task use schedule_type=once and at_unix_ms; for recurring tasks use schedule_type=cron with a UNIX five-field expression and timezone.",
                    parameters = scheduleParameters(),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = AgentTaskToolNames.LIST,
                    description = "List persistent Eta background tasks, including disabled tasks, next run time, last outcome, and run counters.",
                    parameters = objectParameters(
                        JSONObject()
                            .put("include_disabled", JSONObject().put("type", "boolean"))
                            .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 100)),
                    ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = AgentTaskToolNames.HISTORY,
                    description = "Read recent execution history for one persistent Eta task. Use this to verify whether a task was completed, failed, skipped during recovery, or lost with its process.",
                    parameters = objectParameters(
                        JSONObject()
                            .put("task_id", JSONObject().put("type", "string"))
                            .put("limit", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 100)),
                        required = JSONArray().put("task_id"),
                    ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = AgentTaskToolNames.DELETE,
                    description = "Delete a persistent Eta task and its execution history. Confirm the exact task_id from list_jobs before using this tool.",
                    parameters = objectParameters(
                        JSONObject().put("task_id", JSONObject().put("type", "string")),
                        required = JSONArray().put("task_id"),
                    ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = AgentTaskToolNames.PAUSE,
                    description = "Pause a persistent Eta task without deleting it or its history.",
                    parameters = objectParameters(
                        JSONObject().put("task_id", JSONObject().put("type", "string")),
                        required = JSONArray().put("task_id"),
                    ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = AgentTaskToolNames.RESUME,
                    description = "Resume a paused Eta task and rebuild its next WorkManager trigger.",
                    parameters = objectParameters(
                        JSONObject().put("task_id", JSONObject().put("type", "string")),
                        required = JSONArray().put("task_id"),
                    ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = AgentTaskToolNames.TRIGGER,
                    description = "Run an enabled Eta task immediately once without changing its normal schedule or run counter.",
                    parameters = objectParameters(
                        JSONObject().put("task_id", JSONObject().put("type", "string")),
                        required = JSONArray().put("task_id"),
                    ),
                ),
            )
    }

    private fun scheduleParameters(): JSONObject {
        val action = JSONObject()
            .put("type", "object")
            .put(
                "properties",
                JSONObject()
                    .put("tool", JSONObject().put("type", "string"))
                    .put(
                        "args",
                        JSONObject()
                            .put("type", "object")
                            .put("description", "Arguments passed unchanged to the selected Eta tool"),
                    ),
            )
            .put("required", JSONArray().put("tool"))
        return objectParameters(
            JSONObject()
                .put("name", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 80))
                .put("description", JSONObject().put("type", "string").put("maxLength", 500))
                .put("mode", JSONObject().put("type", "string").put("enum", JSONArray().put("llm").put("direct")))
                .put("prompt", JSONObject().put("type", "string").put("maxLength", 4_000))
                .put(
                    "actions",
                    JSONObject()
                        .put("type", "array")
                        .put("minItems", 1)
                        .put("maxItems", 50)
                        .put("items", action),
                )
                .put("schedule_type", JSONObject().put("type", "string").put("enum", JSONArray().put("once").put("cron")))
                .put("at_unix_ms", JSONObject().put("type", "integer").put("description", "Absolute UTC Unix time in milliseconds"))
                .put("cron_expression", JSONObject().put("type", "string").put("description", "UNIX cron: minute hour day-of-month month day-of-week"))
                .put("timezone", JSONObject().put("type", "string").put("description", "IANA timezone such as Europe/Moscow or Asia/Tokyo"))
                .put("start_at_unix_ms", JSONObject().put("type", "integer"))
                .put("end_at_unix_ms", JSONObject().put("type", "integer"))
                .put("max_runs", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 1_000))
                .put("catchup", JSONObject().put("type", "string").put("enum", JSONArray().put("skip").put("fire_once").put("fire_all")))
                .put("notify_on_success", JSONObject().put("type", "boolean")),
            required = JSONArray().put("name").put("mode").put("schedule_type"),
        )
    }

    private fun objectParameters(
        properties: JSONObject,
        required: JSONArray = JSONArray(),
    ): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", required)
}
