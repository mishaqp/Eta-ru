package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable bridge between an orphaned Runtime checkpoint and the next model request.
 *
 * The in-flight checkpoint already contains a safe projection of assistant text and tool activity.
 * Previously Eta restored that projection only to the visible chat and then deleted it, so the
 * next model request could not know which steps had actually happened.  Before an orphaned
 * checkpoint is removed we preserve a compact progress ledger here and, best-effort, append the
 * same ledger to the persisted conversation history.
 */
internal object AgentInterruptedProgressStore {
    private const val PREFS_NAME = "agent_interrupted_progress"
    private const val KEY_SEPARATOR = "|"
    private const val MAX_ENTRIES_PER_CONVERSATION = 6
    private const val MAX_PROGRESS_CHARS = 24_000
    private const val MAX_TOOL_RECORDS = 40
    private const val MAX_RECORD_FIELD_CHARS = 210
    private const val MAX_PARTIAL_TEXT_CHARS = 2_400
    private const val MAX_SUPPLEMENTS = 4
    private const val MAX_SUPPLEMENT_CHARS = 400

    private data class StoredEntry(
        val conversationId: String,
        val runId: String,
        val content: String,
        val updatedAt: Long,
    )

    /** Archive only UI checkpoints that belong to a real persisted conversation. */
    fun archiveBeforeRemoval(
        context: Context,
        checkpoint: AgentRunCheckpointStore.Checkpoint,
    ) {
        if (checkpoint.handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return
        val conversationId = AgentUiHandoffPayload.from(checkpoint.handoff.payload)
            .conversationId
            .takeIf(String::isNotBlank)
            ?: return
        val appContext = context.applicationContext
        val entry = runBlocking(Dispatchers.IO) {
            val database = EtaDatabase.get(appContext)
            val conversationDao = database.conversationDao()
            val metadata = conversationDao.conversations()
                .firstOrNull { it.id == conversationId }
                ?: return@runBlocking null
            if (checkpoint.runId in metadata.appliedRuntimeRunIdsJson.toStringSet()) {
                return@runBlocking null
            }

            val message = buildProgressMessage(checkpoint)
            val currentCheckpoint = conversationDao.contextCheckpoint(conversationId)
            val currentHistory = AgentConversationCodec.decodeTranscript(currentCheckpoint?.historyJson)
            if (currentHistory.none { it.containsProgressMarker(checkpoint.runId) }) {
                val updatedHistory = currentHistory + message
                conversationDao.insertContextCheckpoints(
                    listOf(
                        ConversationContextCheckpointEntity(
                            conversationId = conversationId,
                            historyJson = AgentConversationCodec.encodeConversationCheckpoint(updatedHistory),
                        )
                    )
                )
            }
            StoredEntry(
                conversationId = conversationId,
                runId = checkpoint.runId,
                content = message.content,
                updatedAt = checkpoint.updatedAt,
            )
        } ?: return

        persistEntry(appContext, entry)
    }

    /**
     * Add any recovery ledgers that the in-memory UI history has not loaded yet.
     *
     * Once the normal conversation checkpoint already contains a marker, the side-store copy is
     * redundant and is removed.  Until then it remains available across process restarts, so a
     * later UI save cannot accidentally erase the only surviving progress record.
     */
    fun augmentRequest(
        context: Context,
        request: AgentRuntimeWire.RunRequest,
    ): AgentRuntimeWire.RunRequest {
        if (request.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return request
        val conversationId = request.handoff
            ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
            ?.takeIf(String::isNotBlank)
            ?: return request
        val appContext = context.applicationContext
        val entries = loadEntries(appContext, conversationId)
        if (entries.isEmpty()) return request

        val additions = mutableListOf<AgentModelClient.ConversationMessage>()
        val redundantKeys = mutableListOf<String>()
        entries.forEach { entry ->
            if (request.history.any { it.containsProgressMarker(entry.runId) }) {
                redundantKeys += entryKey(entry.conversationId, entry.runId)
            } else {
                additions += AgentModelClient.ConversationMessage(
                    role = "system",
                    content = entry.content,
                )
            }
        }
        if (redundantKeys.isNotEmpty()) {
            val editor = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            redundantKeys.forEach(editor::remove)
            editor.apply()
        }
        return if (additions.isEmpty()) request else request.copy(history = request.history + additions)
    }

    private fun persistEntry(context: Context, entry: StoredEntry) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(
                entryKey(entry.conversationId, entry.runId),
                JSONObject()
                    .put("conversation_id", entry.conversationId)
                    .put("run_id", entry.runId)
                    .put("content", entry.content)
                    .put("updated_at", entry.updatedAt)
                    .toString(),
            )
            .apply()

        val allForConversation = loadEntries(context, entry.conversationId)
        if (allForConversation.size > MAX_ENTRIES_PER_CONVERSATION) {
            val editor = prefs.edit()
            allForConversation
                .sortedBy { it.updatedAt }
                .dropLast(MAX_ENTRIES_PER_CONVERSATION)
                .forEach { old -> editor.remove(entryKey(old.conversationId, old.runId)) }
            editor.apply()
        }
    }

    private fun loadEntries(context: Context, conversationId: String): List<StoredEntry> {
        val prefix = "$conversationId$KEY_SEPARATOR"
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .all
            .asSequence()
            .filter { (key, _) -> key.startsWith(prefix) }
            .mapNotNull { (_, value) ->
                (value as? String)?.let(::decodeEntry)
            }
            .sortedBy { it.updatedAt }
            .toList()
    }

    private fun decodeEntry(raw: String): StoredEntry? = runCatching {
        JSONObject(raw).let { json ->
            StoredEntry(
                conversationId = json.optString("conversation_id"),
                runId = json.optString("run_id"),
                content = json.optString("content"),
                updatedAt = json.optLong("updated_at"),
            )
        }.takeIf {
            it.conversationId.isNotBlank() && it.runId.isNotBlank() && it.content.isNotBlank()
        }
    }.getOrNull()

    private fun entryKey(conversationId: String, runId: String): String =
        "$conversationId$KEY_SEPARATOR$runId"

    private fun String.toStringSet(): Set<String> = runCatching {
        JSONArray(this).let { array ->
            buildSet {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }.getOrDefault(emptySet())

    private fun AgentModelClient.ConversationMessage.containsProgressMarker(runId: String): Boolean =
        content.contains(progressMarker(runId))

    private fun progressMarker(runId: String): String =
        "[ETA_AGENT_PROGRESS_CHECKPOINT run_id=$runId]"

    private data class ToolRecord(
        val order: Int,
        val round: Int,
        val name: String,
        val argsPreview: String = "",
        val command: String? = null,
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

    private fun buildProgressMessage(
        checkpoint: AgentRunCheckpointStore.Checkpoint,
    ): AgentModelClient.ConversationMessage {
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
            appendLine(progressMarker(checkpoint.runId))
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
                    val line = buildString {
                        append(index + 1)
                        append(". round=${record.round} tool=${record.name.ifBlank { "unknown_tool" }}")
                        append(" status=${record.statusLabel()}")
                        record.argsPreview.takeIf(String::isNotBlank)?.let {
                            append(" | target=${it.singleLine(MAX_RECORD_FIELD_CHARS)}")
                        }
                        record.command.takeIf { !it.isNullOrBlank() }?.let {
                            append(" | command=${it.singleLine(MAX_RECORD_FIELD_CHARS)}")
                        }
                        record.resultSummary.takeIf { !it.isNullOrBlank() }?.let {
                            append(" | result=${it.singleLine(MAX_RECORD_FIELD_CHARS)}")
                        }
                    }
                    appendLine(line.take(MAX_RECORD_FIELD_CHARS * 2))
                }
                if (omittedRecords > 0) {
                    appendLine("... $omittedRecords промежуточных tool-call записей опущено только ради лимита checkpoint-контекста.")
                }
            }

            if (partialText.isNotBlank()) {
                appendLine()
                appendLine("Последний сохранённый текст ответа агента:")
                appendLine(partialText.takeLast(MAX_PARTIAL_TEXT_CHARS))
            }

            if (supplements.isNotEmpty()) {
                appendLine()
                appendLine("Последние дополнения пользователя во время run:")
                supplements.forEach { supplement ->
                    appendLine("- ${supplement.text.singleLine(MAX_SUPPLEMENT_CHARS)}")
                }
            }

            failure?.takeIf(String::isNotBlank)?.let {
                appendLine()
                appendLine("Последняя зафиксированная ошибка run: ${it.singleLine(800)}")
            }

            appendLine()
            appendLine("Правила продолжения:")
            appendLine("- SUCCESS/FAILED означает, что вызов инструмента завершился и его сохранённый итог указан выше.")
            appendLine("- UNKNOWN_OUTCOME означает: инструмент был запущен, но событие завершения не сохранилось; не утверждай ни успех, ни провал без новой проверки.")
            appendLine("- Не повторяй уже успешно завершённые действия с побочными эффектами. Read-only проверку повторяй только если нужны детали, которых нет в журнале.")
            appendLine("- Продолжай задачу с последнего подтверждённого состояния, а не начинай её заново.")
            append("[END_ETA_AGENT_PROGRESS_CHECKPOINT]")
        }.boundedProgress()

        return AgentModelClient.ConversationMessage(role = "system", content = content)
    }

    private fun collectToolRecords(events: List<AgentEvent>): List<ToolRecord> {
        val records = mutableListOf<ToolRecord>()
        val activeById = linkedMapOf<String, ToolRecord>()
        events.forEachIndexed { eventIndex, event ->
            when (event) {
                is AgentEvent.ToolStarted -> {
                    val record = ToolRecord(
                        order = eventIndex,
                        round = event.round,
                        name = event.name,
                        argsPreview = event.argsPreview,
                        command = event.command,
                    )
                    records += record
                    if (event.toolCallId.isNotBlank()) activeById[event.toolCallId] = record
                }
                is AgentEvent.ToolFinished -> {
                    val record = event.toolCallId.takeIf(String::isNotBlank)?.let(activeById::get)
                        ?: ToolRecord(eventIndex, event.round, event.name).also(records::add)
                    record.resultSummary = event.resultSummary
                    record.success = event.success
                    record.finished = true
                }
                is AgentEvent.HostedToolStarted -> {
                    val record = ToolRecord(eventIndex, event.round, event.name)
                    records += record
                    if (event.toolCallId.isNotBlank()) activeById[event.toolCallId] = record
                }
                is AgentEvent.HostedToolFinished -> {
                    val record = event.toolCallId.takeIf(String::isNotBlank)?.let(activeById::get)
                        ?: ToolRecord(eventIndex, event.round, event.name).also(records::add)
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
        return records.take(headSize) + records.takeLast(MAX_TOOL_RECORDS - headSize)
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
                    event.kind == AgentEvent.AssistantBlockKind.TEXT && event.replacementContent != null
                ) {
                    blocks[event.round to event.index] = StringBuilder(event.replacementContent)
                }
                else -> Unit
            }
        }
        return blocks.values.joinToString("\n") { it.toString() }
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
            .let { if (it.length <= maxChars) it else it.take(maxChars) + "…" }

    private fun String.boundedProgress(): String {
        if (length <= MAX_PROGRESS_CHARS) return this
        val suffix = "\n[checkpoint journal clipped]\n[END_ETA_AGENT_PROGRESS_CHECKPOINT]"
        return take((MAX_PROGRESS_CHARS - suffix.length).coerceAtLeast(0)) + suffix
    }
}
