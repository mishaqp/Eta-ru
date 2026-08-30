package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore

/**
 * Converts an orphaned Runtime checkpoint into durable model context.
 *
 * Runtime checkpoints intentionally contain only the safe UI projection of tool activity.  When
 * both the UI and Runtime processes disappear mid-run, that projection used to be restored only
 * to the visible chat.  The next model request therefore knew the original user request but not
 * what the previous agent had already done.  This builder turns the same persisted events into a
 * compact, deterministic progress ledger that can be appended to conversation history.
 */
internal object AgentInterruptedProgressBuilder {
    private const val MAX_PROGRESS_CHARS = 24_000
    private const val MAX_TOOL_RECORDS = 40
    private const val MAX_RECORD_CHARS = 420
    private const val MAX_PARTIAL_TEXT_CHARS = 2_400
    private const val MAX_SUPPLEMENTS = 4
    private const val MAX_SUPPLEMENT_CHARS = 400

    fun build(checkpoint: AgentRunCheckpointStore.Checkpoint): AgentModelClient.ConversationMessage {
        val events = checkpoint.events
        val records = collectToolRecords(events)
        val selectedRecords = selectRecords(records)
        val omittedRecords = (records.size - selectedRecords.size).coerceAtLeast(0)
        val maxRound = events.mapNotNull(::roundOf).maxOrNull()
        val partialText = collectAssistantText(events)
        val supplements = events
            .filterIsInstance<AgentEvent.UserSupplementReceived>()
            .takeLast(MAX_SUPPLEMENTS)
        val failure = events.filterIsInstance<AgentEvent.RunFailed>().lastOrNull()?.reason

        val content = buildString {
            appendLine("[ETA_AGENT_PROGRESS_CHECKPOINT run_id=${checkpoint.runId}]")
            appendLine("Предыдущий Agent-run был прерван до получения надёжного финального transcript.")
            appendLine(
                "Ниже — авторитетный локально сохранённый журнал уже наблюдавшихся действий. " +
                    "Не считай отсутствующие из обычной истории шаги невыполненными."
            )
            append("checkpoint_events=${events.size}")
            maxRound?.let { append("; last_round=$it") }
            append("; tool_calls=${records.size}")
            appendLine()

            if (selectedRecords.isNotEmpty()) {
                appendLine()
                appendLine("Журнал инструментов:")
                selectedRecords.forEachIndexed { index, record ->
                    append(index + 1)
                    append(". round=")
                    append(record.round)
                    append(" tool=")
                    append(record.name.ifBlank { "unknown_tool" })
                    append(" status=")
                    append(record.statusLabel())
                    record.argsPreview.takeIf(String::isNotBlank)?.let {
                        append(" | target=")
                        append(it.singleLine(MAX_RECORD_CHARS / 2))
                    }
                    record.command.takeIf { !it.isNullOrBlank() }?.let {
                        append(" | command=")
                        append(it.singleLine(MAX_RECORD_CHARS / 2))
                    }
                    record.resultSummary.takeIf { !it.isNullOrBlank() }?.let {
                        append(" | result=")
                        append(it.singleLine(MAX_RECORD_CHARS / 2))
                    }
                    appendLine()
                }
                if (omittedRecords > 0) {
                    appendLine("... $omittedRecords промежуточных tool-call записей опущено только ради лимита checkpoint-контекста.")
                }
            }

            if (partialText.isNotBlank()) {
                appendLine()
                appendLine("Последний сохранённый пользовательский текст ответа агента:")
                appendLine(partialText.takeLast(MAX_PARTIAL_TEXT_CHARS))
            }

            if (supplements.isNotEmpty()) {
                appendLine()
                appendLine("Последние дополнения пользователя во время run:")
                supplements.forEach { supplement ->
                    append("- ")
                    appendLine(supplement.text.singleLine(MAX_SUPPLEMENT_CHARS))
                }
            }

            failure?.takeIf(String::isNotBlank)?.let {
                appendLine()
                append("Последняя зафиксированная ошибка run: ")
                appendLine(it.singleLine(800))
            }

            appendLine()
            appendLine("Правила продолжения:")
            appendLine("- SUCCESS/FAILED означает, что вызов инструмента завершился и его сохранённый итог указан выше.")
            appendLine("- UNKNOWN_OUTCOME означает: инструмент был запущен, но событие завершения не сохранилось; не утверждай ни успех, ни провал без новой проверки.")
            appendLine("- Не повторяй уже успешно завершённые действия с побочными эффектами. Read-only проверку повторяй только если для продолжения нужны детали, которых нет в журнале.")
            appendLine("- Продолжай задачу с последнего подтверждённого состояния, а не начинай её заново.")
            append("[END_ETA_AGENT_PROGRESS_CHECKPOINT]")
        }.boundedProgress()

        return AgentModelClient.ConversationMessage(
            role = "system",
            content = content,
        )
    }

    private data class ToolRecord(
        val order: Int,
        val round: Int,
        val id: String,
        val name: String,
        val argsPreview: String = "",
        val command: String? = null,
        val hosted: Boolean = false,
        var resultSummary: String? = null,
        var success: Boolean? = null,
        var finished: Boolean = false,
    ) {
        fun statusLabel(): String = when {
            !finished -> "UNKNOWN_OUTCOME"
            success == false -> "FAILED"
            success == true -> "SUCCESS"
            resultSummary.orEmpty().contains("失败") -> "FAILED"
            else -> "SUCCESS"
        }
    }

    private fun collectToolRecords(events: List<AgentEvent>): List<ToolRecord> {
        val records = mutableListOf<ToolRecord>()
        val activeById = linkedMapOf<String, ToolRecord>()

        fun key(id: String, name: String, order: Int): String =
            id.takeIf(String::isNotBlank) ?: "$name#$order"

        events.forEachIndexed { eventIndex, event ->
            when (event) {
                is AgentEvent.ToolStarted -> {
                    val record = ToolRecord(
                        order = eventIndex,
                        round = event.round,
                        id = event.toolCallId,
                        name = event.name,
                        argsPreview = event.argsPreview,
                        command = event.command,
                    )
                    records += record
                    activeById[key(event.toolCallId, event.name, eventIndex)] = record
                    if (event.toolCallId.isNotBlank()) activeById[event.toolCallId] = record
                }

                is AgentEvent.ToolFinished -> {
                    val record = event.toolCallId.takeIf(String::isNotBlank)?.let(activeById::get)
                        ?: ToolRecord(
                            order = eventIndex,
                            round = event.round,
                            id = event.toolCallId,
                            name = event.name,
                        ).also(records::add)
                    record.resultSummary = event.resultSummary
                    record.success = event.success
                    record.finished = true
                }

                is AgentEvent.HostedToolStarted -> {
                    val record = ToolRecord(
                        order = eventIndex,
                        round = event.round,
                        id = event.toolCallId,
                        name = event.name,
                        hosted = true,
                    )
                    records += record
                    if (event.toolCallId.isNotBlank()) activeById[event.toolCallId] = record
                }

                is AgentEvent.HostedToolFinished -> {
                    val record = event.toolCallId.takeIf(String::isNotBlank)?.let(activeById::get)
                        ?: ToolRecord(
                            order = eventIndex,
                            round = event.round,
                            id = event.toolCallId,
                            name = event.name,
                            hosted = true,
                        ).also(records::add)
                    record.resultSummary = if (event.success) "hosted tool completed" else "hosted tool failed"
                    record.success = event.success
                    record.finished = true
                }

                else -> Unit
            }
        }
        return records.sortedBy { it.order }
    }

    private fun selectRecords(records: List<ToolRecord>): List<ToolRecord> {
        if (records.size <= MAX_TOOL_RECORDS) return records
        val headSize = MAX_TOOL_RECORDS / 2
        val tailSize = MAX_TOOL_RECORDS - headSize
        return records.take(headSize) + records.takeLast(tailSize)
    }

    private fun collectAssistantText(events: List<AgentEvent>): String {
        val blocks = linkedMapOf<Pair<Int, Int>, StringBuilder>()
        events.forEach { event ->
            when (event) {
                is AgentEvent.AssistantBlockDelta -> if (
                    event.kind == AgentEvent.AssistantBlockKind.TEXT && event.delta.isNotEmpty()
                ) {
                    blocks.getOrPut(event.round to event.index, ::StringBuilder).append(event.delta)
                }

                is AgentEvent.AssistantBlockEnd -> if (
                    event.kind == AgentEvent.AssistantBlockKind.TEXT &&
                    event.replacementContent != null
                ) {
                    blocks[event.round to event.index] = StringBuilder(event.replacementContent)
                }

                else -> Unit
            }
        }
        return blocks.values
            .joinToString("\n") { it.toString() }
            .trim()
            .takeLast(MAX_PARTIAL_TEXT_CHARS)
    }

    private fun roundOf(event: AgentEvent): Int? = when (event) {
        is AgentEvent.RoundStarted -> event.round
        is AgentEvent.ProviderRequestStarted -> event.round
        is AgentEvent.ProviderResponseStarted -> event.round
        is AgentEvent.AssistantBlockStart -> event.round
        is AgentEvent.AssistantBlockDelta -> event.round
        is AgentEvent.AssistantBlockEnd -> event.round
        is AgentEvent.AssistantReceived -> event.round
        is AgentEvent.UsageReceived -> event.round
        is AgentEvent.ToolStarted -> event.round
        is AgentEvent.ToolFinished -> event.round
        is AgentEvent.HostedToolStarted -> event.round
        is AgentEvent.HostedToolFinished -> event.round
        is AgentEvent.ToolImagesAttached -> event.round
        is AgentEvent.RunFinished -> event.round
        is AgentEvent.RunStarted,
        is AgentEvent.UserSupplementReceived,
        is AgentEvent.RunFailed,
        -> null
    }

    private fun String.singleLine(maxChars: Int): String =
        replace(Regex("\\s+"), " ")
            .trim()
            .let { value -> if (value.length <= maxChars) value else value.take(maxChars) + "…" }

    private fun String.boundedProgress(): String {
        if (length <= MAX_PROGRESS_CHARS) return this
        val suffix = "\n[checkpoint journal clipped to ${MAX_PROGRESS_CHARS} chars]\n[END_ETA_AGENT_PROGRESS_CHECKPOINT]"
        return take((MAX_PROGRESS_CHARS - suffix.length).coerceAtLeast(0)) + suffix
    }
}
