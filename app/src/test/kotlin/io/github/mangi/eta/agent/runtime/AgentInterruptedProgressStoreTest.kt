package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentInterruptedProgressStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        context.getSharedPreferences("agent_interrupted_progress", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun interruptedCheckpointIsInjectedIntoNextModelRequest() {
        seedConversation("conversation-1")
        val oldRequest = request("run-old", "conversation-1")
        assertTrue(
            AgentRunCheckpointStore.start(
                context = context,
                request = oldRequest,
                ownerInstanceId = "old-runtime",
                now = 1_000L,
            )
        )
        AgentRunCheckpointStore.append(
            context,
            "run-old",
            0,
            AgentEvent.RoundStarted(round = 1, messageCount = 2),
            now = 1_100L,
        )
        AgentRunCheckpointStore.append(
            context,
            "run-old",
            1,
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "tool-1",
                name = "device_status",
                argsPreview = "查看设备状态",
            ),
            now = 1_200L,
        )
        AgentRunCheckpointStore.append(
            context,
            "run-old",
            2,
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "tool-1",
                name = "device_status",
                resultSummary = "完成",
                imageCount = 0,
                imageBytes = 0,
                success = true,
            ),
            now = 1_300L,
        )
        AgentRunCheckpointStore.append(
            context,
            "run-old",
            3,
            AgentEvent.ToolStarted(
                round = 2,
                toolCallId = "tool-2",
                name = "run_command",
                argsPreview = "执行命令 · Android · root",
                command = "uname -a",
            ),
            now = 1_400L,
        )
        AgentRunCheckpointStore.append(
            context,
            "run-old",
            4,
            AgentEvent.AssistantBlockDelta(
                round = 2,
                kind = AgentEvent.AssistantBlockKind.TEXT,
                index = 0,
                deltaChars = 17,
                delta = "Продолжаю проверку",
            ),
            now = 1_500L,
        )

        AgentRunCheckpointStore.remove(context, "run-old")
        assertTrue(AgentRunCheckpointStore.list(context).isEmpty())

        val nextRequest = request("run-next", "conversation-1").copy(
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "Проведи полный тест")
            )
        )
        val augmented = AgentInterruptedProgressStore.augmentRequest(context, nextRequest)
        val progress = augmented.history.last()

        assertEquals("system", progress.role)
        assertTrue(progress.content.contains("ETA_AGENT_PROGRESS_CHECKPOINT run_id=run-old"))
        assertTrue(progress.content.contains("tool=device_status status=SUCCESS"))
        assertTrue(progress.content.contains("tool=run_command status=UNKNOWN_OUTCOME"))
        assertTrue(progress.content.contains("uname -a"))
        assertTrue(progress.content.contains("Продолжаю проверку"))
        assertTrue(progress.content.contains("не начинай её заново"))

        val persistedHistory = runBlocking {
            AgentConversationCodec.decodeTranscript(
                EtaDatabase.get(context)
                    .conversationDao()
                    .contextCheckpoint("conversation-1")
                    ?.historyJson
            )
        }
        assertTrue(
            persistedHistory.any {
                it.content.contains("ETA_AGENT_PROGRESS_CHECKPOINT run_id=run-old")
            }
        )

        val alreadyDurable = AgentInterruptedProgressStore.augmentRequest(
            context,
            nextRequest.copy(history = persistedHistory),
        )
        assertEquals(
            1,
            alreadyDurable.history.count {
                it.content.contains("ETA_AGENT_PROGRESS_CHECKPOINT run_id=run-old")
            },
        )
    }

    @Test
    fun completedAppliedRunIsNotArchivedAgain() {
        seedConversation(
            conversationId = "conversation-2",
            appliedRunIdsJson = "[\"run-applied\"]",
        )
        val oldRequest = request("run-applied", "conversation-2")
        assertTrue(
            AgentRunCheckpointStore.start(
                context = context,
                request = oldRequest,
                ownerInstanceId = "old-runtime",
            )
        )
        AgentRunCheckpointStore.append(
            context,
            "run-applied",
            0,
            AgentEvent.ToolStarted(1, "tool-a", "device_status", "查看设备状态"),
        )

        AgentRunCheckpointStore.remove(context, "run-applied")

        val next = AgentInterruptedProgressStore.augmentRequest(
            context,
            request("run-new", "conversation-2"),
        )
        assertFalse(
            next.history.any {
                it.content.contains("ETA_AGENT_PROGRESS_CHECKPOINT run_id=run-applied")
            }
        )
    }

    private fun seedConversation(
        conversationId: String,
        appliedRunIdsJson: String = "[]",
    ) = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(
            listOf(
                ConversationEntity(
                    id = conversationId,
                    title = "Test",
                    thinkingEnabled = false,
                    appliedRuntimeRunIdsJson = appliedRunIdsJson,
                    createdAt = 1L,
                    updatedAt = 1L,
                )
            )
        )
        dao.insertContextCheckpoints(
            listOf(
                ConversationContextCheckpointEntity(
                    conversationId = conversationId,
                    historyJson = "[]",
                )
            )
        )
    }

    private fun request(runId: String, conversationId: String): AgentRuntimeWire.RunRequest =
        AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = "Продолжить",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.com/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
            ),
            images = emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                payload = conversationId,
            ),
        )
}
