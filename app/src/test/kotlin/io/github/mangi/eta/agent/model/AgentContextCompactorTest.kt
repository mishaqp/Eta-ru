package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextCompactorTest {
    @Test
    fun compactionKeepsCanonicalTranscriptAndAddsSummaryToProviderView() {
        val source = longConversation()
        val original = source.toString()
        val provider = SummaryProvider()
        val compactor = AgentContextCompactor(
            config = config(contextWindow = 4_096),
            tools = JSONArray(),
            provider = provider,
            runController = AgentRunController(),
        )

        val view = compactor.prepare(source)

        assertEquals(original, source.toString())
        assertTrue(view.length() < source.length())
        assertTrue(view.toString().contains("eta_progress_summary"))
        assertTrue(view.toString().contains("проверенная сводка"))
        assertEquals("user", view.getJSONObject(2).getString("role"))
        assertEquals(1, provider.calls)
    }

    @Test
    fun smallConversationDoesNotCallSummaryProvider() {
        val source = JSONArray()
            .put(message("system", "rules"))
            .put(message("user", "hello"))
        val provider = SummaryProvider()
        val compactor = AgentContextCompactor(
            config = config(contextWindow = 32_000),
            tools = JSONArray(),
            provider = provider,
            runController = AgentRunController(),
        )

        assertTrue(compactor.prepare(source) === source)
        assertEquals(0, provider.calls)
    }

    @Test
    fun overflowDetectionIsBoundedToOneRetry() {
        val compactor = AgentContextCompactor(
            config = config(contextWindow = null),
            tools = JSONArray(),
            provider = SummaryProvider(),
            runController = AgentRunController(),
        )
        val overflow = IllegalStateException("HTTP 400: context_length_exceeded")

        assertTrue(compactor.canRetryOverflow(overflow))
        assertFalse(compactor.canRetryOverflow(overflow))
        assertFalse(compactor.canRetryOverflow(IllegalStateException("HTTP 500")))
    }

    private fun longConversation(): JSONArray = JSONArray().apply {
        put(message("system", "rules"))
        repeat(18) { turn ->
            put(message("user", "задача-$turn " + "x".repeat(420)))
            put(message("assistant", "результат-$turn " + "y".repeat(420)))
        }
    }

    private fun message(role: String, content: String) =
        JSONObject().put("role", role).put("content", content)

    private fun config(contextWindow: Int?) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.test/v1",
        apiKey = "test",
        model = "test-model",
        contextWindow = contextWindow,
        systemPrompt = "rules",
    )

    private class SummaryProvider : AgentProviderClient {
        var calls = 0
        override val id = "summary-test"
        override val capabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = false,
            imageInput = false,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            calls++
            return ProviderResponse(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", "проверенная сводка")
                    .put("finish_reason", "stop")
            )
        }
    }
}
