package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.mangi.eta.data.model.AssistantProfile

@Entity(
    tableName = "assistant_profiles",
    indices = [
        Index(value = ["enabled"]),
        Index(value = ["sort_order", "created_at"]),
    ],
)
internal data class AssistantProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    @ColumnInfo(name = "provider_id") val providerId: String?,
    @ColumnInfo(name = "model_id") val modelId: String?,
    @ColumnInfo(name = "system_prompt") val systemPrompt: String,
    val enabled: Boolean,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

internal fun AssistantProfileEntity.toDomain(): AssistantProfile = AssistantProfile(
    id = id,
    name = name,
    description = description,
    providerId = providerId,
    modelId = modelId,
    systemPrompt = systemPrompt,
    enabled = enabled,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun AssistantProfile.toEntity(): AssistantProfileEntity = AssistantProfileEntity(
    id = id,
    name = name,
    description = description,
    providerId = providerId,
    modelId = modelId,
    systemPrompt = systemPrompt,
    enabled = enabled,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
