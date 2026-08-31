package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Persistent assistant profile.
 *
 * Provider and model ids are references to the existing provider database. A null value
 * deliberately means "inherit the current global selection"; credentials never belong to
 * an assistant profile and therefore are not duplicated or passed to hooked applications.
 */
@Serializable
data class AssistantProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val description: String = "",
    val providerId: String? = null,
    val modelId: String? = null,
    val systemPrompt: String = "",
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

object AssistantProfileDefaults {
    const val DEFAULT_ID = "eta-default-assistant"
    const val DEFAULT_NAME = "Основной ассистент"

    fun create(): AssistantProfile = AssistantProfile(
        id = DEFAULT_ID,
        name = DEFAULT_NAME,
        description = "Основной профиль Eta; использует текущую модель и настройки провайдера",
        sortOrder = 0,
    )
}
