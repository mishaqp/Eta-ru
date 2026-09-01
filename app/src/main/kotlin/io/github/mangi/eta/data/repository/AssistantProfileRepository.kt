package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.model.AssistantProfilesState
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.DEFAULT_ASSISTANT_PROFILE_ID
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * Profile data is kept separately from provider credentials. Activating a profile mirrors
 * its runtime permissions into the existing local Agent preference store, so Root and
 * Xposed entry points retain the same security boundary as before profiles existed.
 */
internal object AssistantProfileRepository {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun stateFlow(): Flow<AssistantProfilesState> =
        SettingsDataStore.assistantProfilesStateFlow().map(::normalized)

    fun profilesFlow(): Flow<List<AssistantProfile>> =
        stateFlow().map(AssistantProfilesState::profiles)

    fun activeProfileFlow(): Flow<AssistantProfile?> =
        stateFlow().map { state ->
            state.profiles.firstOrNull { it.id == state.activeProfileId }
        }

    suspend fun activeProfile(): AssistantProfile? {
        ensureInitialized()
        ensureDefaultProfile()
        val state = normalized(SettingsDataStore.assistantProfilesState())
        return state.profiles.firstOrNull { it.id == state.activeProfileId }
    }

    suspend fun ensureDefaultProfile(): AssistantProfile {
        ensureInitialized()
        val legacyDefault = legacyDefaultProfile()
        var resolved: AssistantProfile? = null
        SettingsDataStore.updateAssistantProfilesState { raw ->
            val state = if (raw.profiles.isEmpty()) {
                AssistantProfilesState(
                    activeProfileId = DEFAULT_ASSISTANT_PROFILE_ID,
                    profiles = listOf(legacyDefault),
                )
            } else {
                normalized(raw)
            }
            val profile = state.profiles.firstOrNull { it.id == state.activeProfileId }
                ?: state.profiles.first()
            resolved = profile
            state.copy(activeProfileId = profile.id)
        }
        return requireNotNull(resolved)
    }

    suspend fun createProfile(
        name: String = "Новый ассистент",
        copyFromActive: Boolean = true,
    ): AssistantProfile {
        ensureInitialized()
        ensureDefaultProfile()
        val state = normalized(SettingsDataStore.assistantProfilesState())
        val active = state.profiles.firstOrNull { it.id == state.activeProfileId }
        val now = System.currentTimeMillis()
        val profile = if (copyFromActive && active != null) {
            active.copy(
                id = UUID.randomUUID().toString(),
                name = name,
                createdAt = now,
                updatedAt = now,
            )
        } else {
            defaultProfile(name = name, now = now)
        }
        SettingsDataStore.updateAssistantProfilesState { raw ->
            val current = normalized(raw)
            current.copy(profiles = current.profiles + profile)
        }
        return profile
    }

    suspend fun update(profile: AssistantProfile): AssistantProfile {
        ensureInitialized()
        val normalizedProfile = profile.normalized()
        require(validateRequestOverrides(normalizedProfile) == null) {
            "Заголовки и тело запроса должны быть JSON-объектами"
        }
        var updated: AssistantProfile? = null
        var shouldApply = false
        SettingsDataStore.updateAssistantProfilesState { raw ->
            val state = normalized(raw)
            require(state.profiles.any { it.id == normalizedProfile.id }) { "Профиль не найден" }
            updated = normalizedProfile.copy(updatedAt = System.currentTimeMillis())
            shouldApply = state.activeProfileId == normalizedProfile.id
            state.copy(
                profiles = state.profiles.map { existing ->
                    if (existing.id == normalizedProfile.id) requireNotNull(updated) else existing
                },
            )
        }
        return requireNotNull(updated).also { profileToApply ->
            if (shouldApply) applyRuntimeSettings(profileToApply)
        }
    }

    suspend fun activate(profileId: String): AssistantProfile {
        ensureInitialized()
        var selected: AssistantProfile? = null
        SettingsDataStore.updateAssistantProfilesState { raw ->
            val state = normalized(raw)
            selected = state.profiles.firstOrNull { it.id == profileId }
                ?: throw IllegalArgumentException("Профиль не найден")
            require(validateRequestOverrides(requireNotNull(selected)) == null) {
                "Заголовки и тело запроса должны быть JSON-объектами"
            }
            state.copy(activeProfileId = profileId)
        }
        return requireNotNull(selected).also(::applyRuntimeSettings)
    }

    suspend fun delete(profileId: String): AssistantProfilesState {
        ensureInitialized()
        require(profileId != DEFAULT_ASSISTANT_PROFILE_ID) {
            "Основной профиль нельзя удалить"
        }
        var next: AssistantProfilesState? = null
        SettingsDataStore.updateAssistantProfilesState { raw ->
            val state = normalized(raw)
            require(state.profiles.size > 1) { "Нельзя удалить единственный профиль" }
            val remaining = state.profiles.filterNot { it.id == profileId }
            require(remaining.size != state.profiles.size) { "Профиль не найден" }
            val activeId = state.activeProfileId.takeIf { it != profileId } ?: remaining.first().id
            next = state.copy(activeProfileId = activeId, profiles = remaining)
            requireNotNull(next)
        }
        val result = requireNotNull(next)
        result.profiles.firstOrNull { it.id == result.activeProfileId }
            ?.let(::applyRuntimeSettings)
        return result
    }

    /** Used by the normal model picker, so its selection stays isolated per profile. */
    suspend fun updateActiveSelection(providerId: String?, modelId: String?) {
        val active = activeProfile() ?: return
        update(active.copy(providerId = providerId, modelId = modelId))
    }

    fun profileHeaders(profile: AssistantProfile): List<CustomHeader> {
        val raw = profile.requestHeadersJson.trim()
        if (raw.isBlank()) return emptyList()
        val objectValue = JSONObject(raw)
        return objectValue.keys().asSequence()
            .mapNotNull { key ->
                val value = objectValue.opt(key)
                key.trim().takeIf(String::isNotBlank)?.let { name ->
                    when (value) {
                        null, JSONObject.NULL -> null
                        else -> CustomHeader(name = name, value = value.toString())
                    }
                }
            }
            .toList()
    }

    fun validateRequestOverrides(profile: AssistantProfile): String? = runCatching {
        profile.requestHeadersJson.trim().takeIf(String::isNotBlank)?.let(::JSONObject)
        profile.requestBodyJson.trim().takeIf(String::isNotBlank)?.let(::JSONObject)
        null
    }.getOrElse { "Заголовки и тело запроса должны быть JSON-объектами" }

    private suspend fun applyRuntimeSettings(profile: AssistantProfile) {
        SettingsDataStore.setSelection(profile.providerId, profile.modelId)
        SettingsDataStore.setMemoryEnabled(profile.memoryEnabled)
        val preferences = Prefs.localAgentPreferences() ?: return
        preferences.edit()
            .putBoolean(Prefs.Keys.AGENT_BROWSER_TOOLS, profile.browserToolsEnabled)
            .putBoolean(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS, profile.deviceToolsEnabled)
            .putBoolean(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS, profile.sensitiveReadToolsEnabled)
            .putBoolean(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS, profile.sensitiveActionToolsEnabled)
            .putBoolean(Prefs.Keys.AGENT_TERMINAL_TOOLS, profile.terminalToolsEnabled)
            .putBoolean(Prefs.Keys.AGENT_THINKING_ENABLED, profile.thinkingEnabled)
            .commit()
    }

    private fun normalized(state: AssistantProfilesState): AssistantProfilesState {
        val distinct = state.profiles
            .map(AssistantProfile::normalized)
            .distinctBy(AssistantProfile::id)
            .toMutableList()
        if (distinct.none { it.id == DEFAULT_ASSISTANT_PROFILE_ID }) {
            distinct.add(0, defaultProfile())
        }
        val active = state.activeProfileId.takeIf { id -> distinct.any { it.id == id } }
            ?: distinct.first().id
        return AssistantProfilesState(activeProfileId = active, profiles = distinct)
    }

    private fun defaultProfile(
        name: String = "Основной ассистент",
        now: Long = System.currentTimeMillis(),
    ): AssistantProfile = AssistantProfile(
            id = DEFAULT_ASSISTANT_PROFILE_ID,
            name = name,
            createdAt = now,
            updatedAt = now,
        )

    private suspend fun legacyDefaultProfile(): AssistantProfile {
        val settings = SettingsDataStore.settings()
        return defaultProfile().copy(
            providerId = settings.selectedProviderId,
            modelId = settings.selectedModelId,
            memoryEnabled = settings.memoryEnabled,
            browserToolsEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_BROWSER_TOOLS),
            deviceToolsEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
            sensitiveReadToolsEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
            sensitiveActionToolsEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
            terminalToolsEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            thinkingEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED),
        )
    }

    private fun AssistantProfile.normalized(): AssistantProfile = copy(
        id = id.trim().ifBlank { throw IllegalArgumentException("У профиля нет идентификатора") },
        name = name.trim().take(80).ifBlank { "Ассистент" },
        avatar = avatar.trim().take(8).ifBlank { AssistantProfile.DEFAULT_AVATAR },
        description = description.trim().take(240),
        providerId = providerId?.trim()?.takeIf(String::isNotBlank),
        modelId = modelId?.trim()?.takeIf(String::isNotBlank),
        systemPrompt = systemPrompt.trim(),
        requestHeadersJson = requestHeadersJson.trim(),
        requestBodyJson = requestBodyJson.trim(),
    )

    private fun ensureInitialized() {
        check(appContext != null) { "AssistantProfileRepository.init(context) must be called in Application.onCreate()" }
    }
}
