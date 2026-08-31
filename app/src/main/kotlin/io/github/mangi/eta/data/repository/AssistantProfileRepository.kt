package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.db.AssistantProfileDao
import io.github.mangi.eta.data.db.AssistantProfileEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.model.AssistantProfileDefaults
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Persistent store for assistant profiles. Behavior wiring is added in the next phase. */
internal object AssistantProfileRepository {
    @Volatile
    private lateinit var applicationContext: Context

    fun init(context: Context) {
        if (!::applicationContext.isInitialized) {
            applicationContext = context.applicationContext
        }
    }

    fun profilesFlow(): Flow<List<AssistantProfile>> = dao().profilesFlow().map { profiles ->
        profiles.map(AssistantProfileEntity::toDomain)
    }

    suspend fun profiles(): List<AssistantProfile> = dao().profiles().map(AssistantProfileEntity::toDomain)

    suspend fun profileById(id: String): AssistantProfile? = dao().profileById(id)?.toDomain()

    suspend fun ensureDefault(): AssistantProfile {
        val existing = dao().profileById(AssistantProfileDefaults.DEFAULT_ID)
        if (existing != null) return existing.toDomain()

        val default = AssistantProfileDefaults.create()
        dao().upsert(default.toEntity())
        return default
    }

    suspend fun save(profile: AssistantProfile): AssistantProfile {
        require(profile.name.isNotBlank()) { "Имя ассистента не может быть пустым" }
        val normalized = profile.copy(
            name = profile.name.trim(),
            description = profile.description.trim(),
            systemPrompt = profile.systemPrompt.trim(),
            updatedAt = System.currentTimeMillis(),
        )
        dao().upsert(normalized.toEntity())
        return normalized
    }

    suspend fun create(
        name: String,
        description: String = "",
    ): AssistantProfile {
        val profile = AssistantProfile(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            sortOrder = (dao().maxSortOrder() + 1).coerceAtLeast(0),
        )
        return save(profile)
    }

    suspend fun delete(id: String): Boolean {
        if (id == AssistantProfileDefaults.DEFAULT_ID) return false
        val existing = dao().profileById(id) ?: return false
        dao().deleteById(existing.id)
        return true
    }

    suspend fun duplicate(id: String): AssistantProfile? {
        val source = dao().profileById(id)?.toDomain() ?: return null
        return save(
            source.copy(
                id = UUID.randomUUID().toString(),
                name = "${source.name} (копия)",
                sortOrder = (dao().maxSortOrder() + 1).coerceAtLeast(0),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun dao(): AssistantProfileDao {
        check(::applicationContext.isInitialized) {
            "AssistantProfileRepository.init(context) must be called in Application.onCreate()"
        }
        return EtaDatabase.get(applicationContext).assistantProfileDao()
    }
}
