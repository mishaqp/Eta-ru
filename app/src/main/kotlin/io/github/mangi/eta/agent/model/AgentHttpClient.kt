package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.codex.CodexAccountRepository
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 模块全局 OkHttp 客户端。
 *
 * 由所有 Provider 实现共享，避免重复创建连接池。超时设置与历史
 * HttpURLConnection 配置保持一致。
 */
internal object AgentHttpClient {

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 60_000L
    private const val WRITE_TIMEOUT_MS = 30_000L

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val isCodexModels = original.url.host == "chatgpt.com" &&
                    original.method == "GET" &&
                    original.url.encodedPath == "/backend-api/codex/models"

                val request = if (isCodexModels && original.header("Authorization") == null) {
                    CodexAccountRepository.firstEnabledAccount()?.let { account ->
                        original.newBuilder()
                            .header("Authorization", "Bearer ${account.accessToken}")
                            .header("ChatGPT-Account-ID", account.chatgptAccountId)
                            .header("originator", "codex_cli_rs")
                            .build()
                    } ?: original
                } else {
                    original
                }

                val response = chain.proceed(request)
                if (isCodexModels && response.isSuccessful) {
                    normalizeCodexModelsResponse(response)
                } else {
                    response
                }
            }
            .build()
    }

    /**
     * Codex returns {"models":[...]} rather than the OpenAI-compatible {"data":[...]}.
     * Normalize only this one endpoint so the existing model repository can stay provider-agnostic.
     */
    private fun normalizeCodexModelsResponse(response: Response): Response {
        val body = response.body
        val mediaType = body.contentType()
        val raw = body.string()
        val normalized = runCatching {
            val root = JSONObject(raw)
            if (root.has("data")) return@runCatching raw
            val models = root.optJSONArray("models") ?: return@runCatching raw
            val data = JSONArray()
            for (index in 0 until models.length()) {
                val source = models.optJSONObject(index) ?: continue
                val slug = source.optString("slug").ifBlank { source.optString("id") }
                if (slug.isBlank()) continue

                val model = JSONObject()
                    .put("id", slug)
                    .put(
                        "display_name",
                        source.optString("display_name").ifBlank { slug },
                    )
                    .put("owned_by", "openai")
                    .put("tool_call", true)
                    .put("output_modalities", JSONArray().put("text"))

                val contextWindow = when {
                    source.optInt("max_context_window", 0) > 0 ->
                        source.optInt("max_context_window")
                    source.optInt("context_window", 0) > 0 ->
                        source.optInt("context_window")
                    else -> 0
                }
                if (contextWindow > 0) model.put("context_window", contextWindow)
                source.optJSONArray("input_modalities")?.let {
                    model.put("input_modalities", it)
                }

                val levels = source.optJSONArray("supported_reasoning_levels")
                if (levels != null) {
                    val efforts = JSONArray()
                    for (levelIndex in 0 until levels.length()) {
                        val effort = levels.optJSONObject(levelIndex)?.optString("effort")
                            ?.takeIf(String::isNotBlank)
                            ?: levels.optString(levelIndex).takeIf(String::isNotBlank)
                        if (effort != null) efforts.put(effort)
                    }
                    model.put(
                        "reasoning",
                        JSONObject()
                            .put("supported_efforts", efforts)
                            .put(
                                "default_effort",
                                source.optString("default_reasoning_level"),
                            ),
                    )
                } else {
                    model.put("reasoning", true)
                }
                data.put(model)
            }
            JSONObject().put("data", data).toString()
        }.getOrDefault(raw)

        return response.newBuilder()
            .body(normalized.toResponseBody(mediaType))
            .build()
    }
}
