package io.github.mangi.eta.agent.model

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRequestMessagesTest {
    @Test
    fun responsesAskForRussianVisibleReasoningWhenUiIsRussian() {
        val source = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "Базовая инструкция"))
            .put(JSONObject().put("role", "developer").put("content", "Техническая инструкция"))

        val instructions = OpenAiRequestMessages.responsesInstructions(
            source,
            Locale.forLanguageTag("ru-RU"),
        )

        assertTrue(instructions.contains("Базовая инструкция"))
        assertTrue(instructions.contains("Техническая инструкция"))
        assertTrue(instructions.contains("reasoning summaries"))
        assertTrue(instructions.contains("пиши на русском языке"))
        assertTrue(instructions.contains("Названия API, команд, файлов, моделей"))
    }

    @Test
    fun nonRussianLocaleDoesNotInjectRussianLanguageRule() {
        val source = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "Base instruction"))

        val instructions = OpenAiRequestMessages.responsesInstructions(source, Locale.ENGLISH)

        assertTrue(instructions.contains("Base instruction"))
        assertFalse(instructions.contains("пиши на русском языке"))
    }

    @Test
    fun chatCompletionsGetsSameRussianVisibleOutputRule() {
        val source = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "Базовая инструкция"))
            .put(JSONObject().put("role", "user").put("content", "Привет"))

        val messages = OpenAiRequestMessages.forChatCompletions(
            source,
            Locale.forLanguageTag("ru-RU"),
        )

        assertTrue(messages.getJSONObject(0).getString("content").contains("пиши на русском языке"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("Привет"))
    }
}
