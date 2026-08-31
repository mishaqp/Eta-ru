package io.github.mangi.eta.agent.task

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.AgentTaskOutcomes
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Executes a prevalidated deterministic action list without another model call. */
internal class AgentTaskActionRunner(private val context: Context) {
    data class Outcome(
        val outcome: String,
        val result: String? = null,
        val error: String? = null,
    )

    suspend fun run(actionsJson: String, runId: String): Outcome = withContext(Dispatchers.IO) {
        val actions = parse(actionsJson).getOrElse { throwable ->
            return@withContext Outcome(
                outcome = AgentTaskOutcomes.FAILED,
                error = throwable.message ?: "invalid actions JSON",
            )
        }
        if (actions.isEmpty()) {
            return@withContext Outcome(
                outcome = AgentTaskOutcomes.FAILED,
                error = "direct task has no actions",
            )
        }

        val executor = AgentLocalTools(
            context = context.applicationContext,
            logger = AndroidAgentLogger,
            browserRunId = runId,
        )
        try {
            val results = JSONArray()
            actions.forEachIndexed { index, action ->
                if (action.tool in AgentTaskToolNames.ALL) {
                    return@withContext Outcome(
                        outcome = AgentTaskOutcomes.FAILED,
                        error = "task management tool cannot be used as a direct action: ${action.tool}",
                    )
                }
                val toolResult = try {
                    withTimeout(ACTION_TIMEOUT_MS) {
                        executor.execute(
                            AgentModelClient.ToolCall(
                                id = "${runId}_action_$index",
                                name = action.tool,
                                argumentsJson = action.arguments.toString(),
                            )
                        )
                    }
                } catch (_: TimeoutCancellationException) {
                    return@withContext Outcome(
                        outcome = AgentTaskOutcomes.TIMED_OUT,
                        error = "action ${index + 1} timed out after ${ACTION_TIMEOUT_MS / 1000}s",
                    )
                }
                val content = if (toolResult.sensitive) {
                    "[sensitive result omitted]"
                } else {
                    toolResult.content.take(MAX_ACTION_RESULT_CHARS)
                }
                results.put(
                    JSONObject()
                        .put("index", index)
                        .put("tool", action.tool)
                        .put("result", content),
                )
                val failure = parseFailure(toolResult.content)
                if (failure != null) {
                    return@withContext Outcome(
                        outcome = AgentTaskOutcomes.FAILED,
                        result = results.toString(),
                        error = "${action.tool}: $failure",
                    )
                }
            }
            Outcome(
                outcome = AgentTaskOutcomes.SUCCESS,
                result = results.toString(),
            )
        } finally {
            executor.close()
        }
    }

    companion object {
        private const val ACTION_TIMEOUT_MS = 60_000L
        private const val MAX_ACTION_RESULT_CHARS = 8_000

        data class Action(val tool: String, val arguments: JSONObject)

        fun parse(raw: String): Result<List<Action>> = runCatching {
            val array = JSONArray(raw)
            require(array.length() in 1..50) { "actions must contain 1..50 items" }
            List(array.length()) { index ->
                val item = array.optJSONObject(index)
                    ?: error("actions[$index] must be an object")
                val tool = item.optString("tool").trim()
                require(tool.isNotBlank()) { "actions[$index].tool is required" }
                val arguments = item.optJSONObject("args") ?: JSONObject()
                Action(tool, JSONObject(arguments.toString()))
            }
        }

        private fun parseFailure(content: String): String? = runCatching {
            val json = JSONObject(content)
            if (json.has("ok") && !json.optBoolean("ok")) {
                json.optString("message")
                    .ifBlank { json.optString("error") }
                    .ifBlank { "tool returned ok=false" }
            } else {
                null
            }
        }.getOrNull()
    }
}
