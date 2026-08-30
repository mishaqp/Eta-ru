package io.github.mangi.eta.agent.model

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** 将 Eta 会话消息投影为 OpenAI-compatible 请求所需的系统指令结构。 */
internal object OpenAiRequestMessages {
    fun forChatCompletions(
        source: JSONArray,
        locale: Locale = Locale.getDefault(),
    ): JSONArray {
        val system = collectInstructions(source, SYSTEM_ROLES)
            .withVisibleOutputLanguageInstruction(locale)
        return JSONArray().also { messages ->
            if (system.isNotBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", system))
            }
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in SYSTEM_ROLES) messages.put(message)
            }
        }
    }

    fun responsesInstructions(
        source: JSONArray,
        locale: Locale = Locale.getDefault(),
    ): String = collectInstructions(source, RESPONSES_INSTRUCTION_ROLES)
        .withVisibleOutputLanguageInstruction(locale)

    private fun collectInstructions(source: JSONArray, roles: Set<String>): String =
        buildList {
            for (index in 0 until source.length()) {
                val message = source.optJSONObject(index) ?: continue
                if (message.optString("role") !in roles) continue
                providerMessageText(message.opt("content"))
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?.let(::add)
            }
        }.joinToString("\n\n")

    private fun String.withVisibleOutputLanguageInstruction(locale: Locale): String {
        val languageInstruction = when (locale.language.lowercase(Locale.ROOT)) {
            "ru" -> RUSSIAN_VISIBLE_OUTPUT_INSTRUCTION
            else -> return this
        }
        return listOf(trim(), languageInstruction)
            .filter(String::isNotEmpty)
            .joinToString("\n\n")
    }

    private val SYSTEM_ROLES = setOf("system")
    private val RESPONSES_INSTRUCTION_ROLES = setOf("system", "developer")

    private const val RUSSIAN_VISIBLE_OUTPUT_INSTRUCTION =
        "Все видимые пользователю ответы, краткие сводки рассуждений (reasoning summaries), " +
            "планы и промежуточные пояснения пиши на русском языке. Названия API, команд, файлов, " +
            "моделей и фрагменты кода не переводи. Если пользователь явно просит другой язык, " +
            "следуй его просьбе."
}
