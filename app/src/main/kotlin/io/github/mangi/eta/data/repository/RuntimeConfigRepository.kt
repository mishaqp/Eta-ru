package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.codex.CodexAccountRepository
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.provider.ReasoningCapabilityResolver
import io.github.libxposed.service.XposedService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.combine

internal object RuntimeConfigRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun selectedProviderIdFlow() = combine(
        SettingsDataStore.selectedProviderIdFlow(),
        AssistantProfileRepository.activeProfileFlow(),
    ) { selectedProviderId, profile ->
        profile?.providerId ?: selectedProviderId
    }

    fun selectedModelIdFlow() = combine(
        SettingsDataStore.selectedModelIdFlow(),
        AssistantProfileRepository.activeProfileFlow(),
    ) { selectedModelId, profile ->
        profile?.modelId ?: selectedModelId
    }

    suspend fun selectedProvider(): ProviderSetting? {
        AssistantProfileRepository.ensureDefaultProfile()
        val settings = ProviderRepository.repairSelection()
        val profile = AssistantProfileRepository.activeProfile()
        val providerId = profile?.providerId ?: settings.selectedProviderId
        return providerId?.let { ProviderRepository.providerById(it) }
    }

    suspend fun setSelectedProviderId(id: String?) {
        val settings = SettingsDataStore.settings()
        val provider = id?.let { ProviderRepository.providerById(it) }
            ?.takeIf { it.isEnabled }
        val activeModel = provider
            ?.takeIf { it.id == settings.selectedProviderId }
            ?.models
            ?.firstOrNull { it.id == settings.selectedModelId && it.isEnabled }
        val rememberedModelId = provider?.let {
            SettingsDataStore.selectedModelIdForProvider(it.id)
        }
        val model = activeModel ?: provider?.selectedOrFirstModel(rememberedModelId)
        AssistantProfileRepository.updateActiveSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun setSelectedModelId(id: String?) {
        val provider = id?.let { ProviderRepository.providerByModelId(it) }
            ?.takeIf { it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == id && it.isEnabled }
        AssistantProfileRepository.updateActiveSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun currentRuntimeConfig(): AgentModelClient.ModelConfig? {
        AssistantProfileRepository.ensureDefaultProfile()
        ProviderRepository.ensureBuiltInsMerged()
        val settings = ProviderRepository.repairSelection()
        val profile = AssistantProfileRepository.activeProfile()
        val providerId = profile?.providerId ?: settings.selectedProviderId
        val selectedModelId = profile?.modelId ?: settings.selectedModelId
        val provider = providerId?.let { ProviderRepository.providerById(it) } ?: return null
        val model = provider.selectedOrFirstModel(selectedModelId) ?: return null
        val config = buildRuntimeConfig(provider, model, profile)
        if (ProviderSourceRegistry.resolve(provider) != ProviderSourceTypes.CODEX) {
            return config
        }

        val account = CodexAccountRepository.nextEnabledAccount() ?: return config
        val accountHeaders = config.customHeaders
            .filterNot { it.name.equals("ChatGPT-Account-ID", ignoreCase = true) } +
            CustomHeader("ChatGPT-Account-ID", account.chatgptAccountId)
        return config.copy(
            apiKey = account.accessToken,
            customHeaders = accountHeaders,
        )
    }

    suspend fun syncToRemotePreferences(service: XposedService?): Boolean {
        val prefs = Prefs.remotePreferencesForUi(service) ?: return false
        val config = currentRuntimeConfig() ?: return clearRuntimeConfig(prefs)
        return writeRuntimeConfig(prefs, config)
    }

    suspend fun ensureDefaults(service: XposedService?) {
        AssistantProfileRepository.ensureDefaultProfile()
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.repairSelection()
        syncToRemotePreferences(service)
    }

    fun runtimeConfigJson(config: AgentModelClient.ModelConfig): String =
        json.encodeToString(config)

    fun buildRuntimeConfig(
        provider: ProviderSetting,
        model: Model,
        profile: AssistantProfile? = null,
    ): AgentModelClient.ModelConfig {
        val systemPrompt = profile?.systemPrompt
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: provider.systemPrompt
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: BuiltinProviders.DEFAULT_SYSTEM_PROMPT
        val sourceType = ProviderSourceRegistry.resolve(provider)
        val endpointMode = when (provider) {
            is OpenAiCompatibleProviderSetting -> provider.endpointMode
            is CustomProviderSetting -> provider.endpointMode
            is AnthropicProviderSetting -> ""
        }
        val inferOpenAiCatalog = sourceType == ProviderSourceTypes.CUSTOM &&
            endpointMode == OpenAiEndpointMode.RESPONSES
        val reasoningCapabilities = ReasoningCapabilityResolver.resolve(
            sourceType = if (inferOpenAiCatalog || sourceType == ProviderSourceTypes.CODEX) {
                ProviderSourceTypes.OPENAI
            } else {
                sourceType
            },
            model = model,
            inferExactCatalogModel = inferOpenAiCatalog,
        )
        return AgentModelClient.ModelConfig(
            providerId = provider.id,
            providerName = provider.name,
            providerType = provider.runtimeProviderType,
            providerSourceType = sourceType,
            baseUrl = provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim(),
            model = model.modelId.trim(),
            modelDisplayName = model.displayName.trim(),
            contextWindow = model.effectiveContextWindow,
            systemPrompt = systemPrompt,
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
            openAiEndpointMode = endpointMode,
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
            terminalTools = profile?.terminalToolsEnabled ?: true,
            browserTools = profile?.browserToolsEnabled ?: true,
            deviceDirectTools = profile?.deviceToolsEnabled ?: true,
            deviceSensitiveReadTools = profile?.sensitiveReadToolsEnabled ?: true,
            deviceSensitiveActionTools = profile?.sensitiveActionToolsEnabled ?: true,
            skillsEnabled = profile?.skillsEnabled ?: true,
            mcpEnabled = profile?.mcpEnabled ?: true,
            thinkingEnabled = reasoningCapabilities != null,
            reasoningEffort = reasoningCapabilities?.let { ReasoningEffort.DEFAULT }
                ?: ReasoningEffort.OFF,
            reasoningCapabilities = reasoningCapabilities,
            extraBodyJson = profile?.requestBodyJson.orEmpty(),
            customHeaders = provider.customHeaders + model.customHeaders +
                profile?.let(AssistantProfileRepository::profileHeaders).orEmpty(),
            customBody = provider.customBody + model.customBody,
        )
    }

    private fun writeRuntimeConfig(
        prefs: SharedPreferences,
        config: AgentModelClient.ModelConfig,
    ): Boolean =
        runCatching {
            prefs.edit()
                .putString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON, runtimeConfigJson(config))
                .commit()
        }.getOrDefault(false)

    private fun clearRuntimeConfig(prefs: SharedPreferences): Boolean =
        runCatching {
            prefs.edit()
                .remove(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
                .commit()
        }.getOrDefault(false)
}
