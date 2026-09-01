package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/**
 * Independent assistant configuration. The profile deliberately stores references to
 * configured providers and models instead of copying secrets into another store.
 */
@Serializable
data class AssistantProfile(
    val id: String,
    val name: String,
    val avatar: String = DEFAULT_AVATAR,
    val description: String = "",
    val providerId: String? = null,
    val modelId: String? = null,
    val systemPrompt: String = "",
    val memoryEnabled: Boolean = true,
    val mcpEnabled: Boolean = true,
    val skillsEnabled: Boolean = true,
    val browserToolsEnabled: Boolean = true,
    val deviceToolsEnabled: Boolean = true,
    val sensitiveReadToolsEnabled: Boolean = true,
    val sensitiveActionToolsEnabled: Boolean = true,
    val terminalToolsEnabled: Boolean = true,
    val thinkingEnabled: Boolean = true,
    /** JSON object of extra headers. Values must be strings. */
    val requestHeadersJson: String = "",
    /** JSON object merged into the model request for this assistant only. */
    val requestBodyJson: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val DEFAULT_AVATAR = "✦"
    }
}

@Serializable
data class AssistantProfilesState(
    val activeProfileId: String = DEFAULT_ASSISTANT_PROFILE_ID,
    val profiles: List<AssistantProfile> = emptyList(),
)

const val DEFAULT_ASSISTANT_PROFILE_ID = "default"
