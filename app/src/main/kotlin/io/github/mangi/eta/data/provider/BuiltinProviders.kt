package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes

internal object BuiltinProviders {
    const val DEFAULT_SYSTEM_PROMPT = "Ты — мобильный AI Agent на Android. Отвечай кратко и выполняй задачу через доступные инструменты."
    const val OPENAI_ID = "builtin-openai"
    const val CODEX_ID = "builtin-codex"
    const val ANTHROPIC_ID = "builtin-anthropic"
    const val BAILIAN_ID = "builtin-dashscope"
    const val DEEPSEEK_ID = "builtin-deepseek"
    const val KIMI_ID = "builtin-kimi"
    const val MIMO_ID = "builtin-mimo"
    const val MINIMAX_ID = "builtin-minimax"
    const val STEPFUN_ID = "builtin-stepfun"
    const val SILICONFLOW_ID = "builtin-siliconflow"
    const val OPENROUTER_ID = "builtin-openrouter"

    val PROVIDERS: List<ProviderSetting> = listOf(
        OpenAiCompatibleProviderSetting(
            id = OPENAI_ID,
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            sourceType = ProviderSourceTypes.OPENAI,
            isBuiltIn = true,
            sortOrder = 0,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            endpointMode = OpenAiEndpointMode.RESPONSES,
        ),
        OpenAiCompatibleProviderSetting(
            id = CODEX_ID,
            name = "Codex",
            baseUrl = "https://chatgpt.com/backend-api/codex",
            sourceType = ProviderSourceTypes.CODEX,
            isBuiltIn = true,
            sortOrder = 1,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
            endpointMode = OpenAiEndpointMode.RESPONSES,
            models = listOf(
                Model(
                    id = "builtin-codex-default",
                    modelId = "gpt-5.1-codex",
                    displayName = "GPT-5.1 Codex",
                    isBuiltIn = true,
                    sortOrder = 0,
                ),
            ),
        ),
        AnthropicProviderSetting(
            id = ANTHROPIC_ID,
            name = "Anthropic",
            baseUrl = "https://api.anthropic.com",
            sourceType = ProviderSourceTypes.ANTHROPIC,
            isBuiltIn = true,
            sortOrder = 2,
            systemPrompt = DEFAULT_SYSTEM_PROMPT,
        ),
        OpenAiCompatibleProviderSetting(BAILIAN_ID, "阿里百炼", "https://dashscope.aliyuncs.com/compatible-mode/v1", ProviderSourceTypes.BAILIAN, isBuiltIn = true, sortOrder = 3, systemPrompt = DEFAULT_SYSTEM_PROMPT),
        OpenAiCompatibleProviderSetting(DEEPSEEK_ID, "DeepSeek", "https://api.deepseek.com", ProviderSourceTypes.DEEPSEEK, isBuiltIn = true, sortOrder = 4, systemPrompt = DEFAULT_SYSTEM_PROMPT),
        OpenAiCompatibleProviderSetting(KIMI_ID, "Kimi", "https://api.moonshot.cn/v1", ProviderSourceTypes.MOONSHOT, isBuiltIn = true, sortOrder = 5, systemPrompt = DEFAULT_SYSTEM_PROMPT),
        OpenAiCompatibleProviderSetting(MIMO_ID, "MiMo", "https://api.xiaomimimo.com/v1", ProviderSourceTypes.MIMO, isBuiltIn = true, sortOrder = 6, systemPrompt = DEFAULT_SYSTEM_PROMPT),
        OpenAiCompatibleProviderSetting(MINIMAX_ID, "MiniMax", "https://api.minimaxi.com/v1", ProviderSourceTypes.MINIMAX, isBuiltIn = true, sortOrder = 7, systemPrompt = DEFAULT_SYSTEM_PROMPT),
        OpenAiCompatibleProviderSetting(STEPFUN_ID, "StepFun", "https://api.stepfun.com/v1", ProviderSourceTypes.STEPFUN, isBuiltIn = true, sortOrder = 8, systemPrompt = DEFAULT_SYSTEM_PROMPT),
        OpenAiCompatibleProviderSetting(SILICONFLOW_ID, "硅基流动", "https://api.siliconflow.cn/v1", ProviderSourceTypes.SILICONFLOW, isBuiltIn = true, sortOrder = 9, systemPrompt = DEFAULT_SYSTEM_PROMPT),
        OpenAiCompatibleProviderSetting(OPENROUTER_ID, "OpenRouter", "https://openrouter.ai/api/v1", ProviderSourceTypes.OPENROUTER, isBuiltIn = true, sortOrder = 10, systemPrompt = DEFAULT_SYSTEM_PROMPT),
    )

    fun providerById(id: String): ProviderSetting? = PROVIDERS.firstOrNull { it.id == id }
}
