package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AndroidAgentLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a bounded provider view without deleting the canonical run transcript.
 *
 * Older complete turns are replaced by an anchored progress summary. The original
 * messages remain in [source], so UI persistence and recovery still receive the full
 * transcript. This class is intentionally scoped to one Agent run.
 */
internal class AgentContextCompactor(
    private val config: AgentModelClient.ModelConfig,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val runController: AgentRunController,
) {
    private var compactedThrough = -1
    private var summary = ""
    private var overflowRetryUsed = false

    fun prepare(source: JSONArray, force: Boolean = false): JSONArray {
        val contextWindow = config.contextWindow?.takeIf { it >= MIN_CONTEXT_WINDOW }
        if (!force && contextWindow == null) return source

        val currentView = buildView(source)
        val inputBudget = contextWindow
            ?.let { (it * TRIGGER_RATIO).toInt().coerceAtLeast(MIN_INPUT_BUDGET) }
            ?: FALLBACK_FORCE_BUDGET
        if (!force && estimateTokens(currentView, tools) <= inputBudget) return currentView

        val systemEnd = leadingSystemEnd(source)
        val tailStart = chooseTailStart(source, systemEnd, inputBudget, force)
        if (tailStart <= systemEnd || tailStart <= compactedThrough + 1) return currentView

        val start = maxOf(systemEnd, compactedThrough + 1)
        val segment = JSONArray()
        for (index in start until tailStart) segment.put(source.opt(index))
        if (segment.length() == 0) return currentView

        val updatedSummary = summarize(segment, summary)
        if (updatedSummary.isBlank()) return currentView
        summary = updatedSummary
        compactedThrough = tailStart - 1
        AndroidAgentLogger.info(
            "context_compacted messages=${segment.length()}, summary_chars=${summary.length}, " +
                "retained_messages=${source.length() - tailStart}"
        )
        return buildView(source)
    }

    fun canRetryOverflow(throwable: Throwable): Boolean {
        if (overflowRetryUsed || !isContextOverflow(throwable)) return false
        overflowRetryUsed = true
        AndroidAgentLogger.warn("context_overflow_retry scheduled")
        return true
    }

    private fun buildView(source: JSONArray): JSONArray {
        if (summary.isBlank() || compactedThrough < 0) return source
        val result = JSONArray()
        val systemEnd = leadingSystemEnd(source)
        for (index in 0 until systemEnd) result.put(source.opt(index))
        result.put(
            JSONObject()
                .put("role", "system")
                .put(
                    "content",
                    "Ниже находится автоматически созданная сводка уже выполненной части текущей задачи. " +
                        "Используй её как контекст продолжения, не повторяй завершённые действия и сохраняй " +
                        "указанные ограничения.\n\n<eta_progress_summary>\n$summary\n</eta_progress_summary>"
                )
        )
        for (index in maxOf(systemEnd, compactedThrough + 1) until source.length()) {
            result.put(source.opt(index))
        }
        return result
    }

    private fun summarize(segment: JSONArray, previousSummary: String): String {
        val input = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        "Сожми выполненную часть автономной задачи в точную рабочую сводку. Сохрани цель, " +
                            "решения пользователя, ограничения, проверенные факты, изменённые файлы, результаты " +
                            "инструментов, ошибки и следующие незавершённые шаги. Не добавляй фактов. Пиши кратко."
                    )
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "content",
                        buildString {
                            if (previousSummary.isNotBlank()) {
                                appendLine("Предыдущая сводка:")
                                appendLine(previousSummary)
                                appendLine()
                            }
                            appendLine("Новые сообщения для объединения:")
                            append(segment.toString())
                        }
                    )
            )
        val response = provider.complete(
            request = ProviderRequest(
                config = config.copy(hostedWebSearchEnabled = false),
                messages = input,
                tools = JSONArray(),
            ),
            runController = runController,
        )
        return response.assistantMessage.optString("content").trim()
    }

    private fun chooseTailStart(
        source: JSONArray,
        systemEnd: Int,
        inputBudget: Int,
        force: Boolean,
    ): Int {
        val target = if (force) inputBudget / 2 else (inputBudget * TARGET_RATIO).toInt()
        var tokens = estimateTokens(JSONArray(), tools)
        var candidate = source.length()
        for (index in source.length() - 1 downTo systemEnd) {
            tokens += estimateTokens(source.opt(index))
            if (tokens > target && source.length() - index >= MIN_TAIL_MESSAGES) break
            candidate = index
        }
        // A retained tail must start with a user message; this avoids orphan tool results.
        for (index in candidate until source.length()) {
            if (source.optJSONObject(index)?.optString("role") == "user") return index
        }
        return source.length()
    }

    private fun leadingSystemEnd(source: JSONArray): Int {
        var index = 0
        while (index < source.length() && source.optJSONObject(index)?.optString("role") == "system") {
            index++
        }
        return index
    }

    private fun estimateTokens(messages: JSONArray, tools: JSONArray): Int =
        estimateTokens(messages.toString()) + estimateTokens(tools.toString()) + REQUEST_OVERHEAD_TOKENS

    private fun estimateTokens(value: Any?): Int = estimateTokens(value?.toString().orEmpty())

    private fun estimateTokens(value: String): Int =
        ((value.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN).coerceAtLeast(1)

    companion object {
        private const val CHARS_PER_TOKEN = 3
        private const val REQUEST_OVERHEAD_TOKENS = 512
        private const val MIN_CONTEXT_WINDOW = 4_096
        private const val MIN_INPUT_BUDGET = 2_048
        private const val FALLBACK_FORCE_BUDGET = 16_384
        private const val MIN_TAIL_MESSAGES = 8
        private const val TRIGGER_RATIO = 0.80
        private const val TARGET_RATIO = 0.55

        internal fun isContextOverflow(throwable: Throwable): Boolean {
            val text = generateSequence(throwable) { it.cause }
                .joinToString(" ") { it.message.orEmpty() }
                .lowercase()
            return listOf(
                "context_length_exceeded",
                "context window",
                "maximum context length",
                "max context length",
                "prompt is too long",
                "too many tokens",
                "request too large",
                "input tokens exceed",
                "上下文长度",
                "上下文过长",
            ).any(text::contains)
        }
    }
}
