package io.github.mangi.eta.data.db

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        ConversationEntity::class,
        ConversationContextCheckpointEntity::class,
        ConversationMessageEntity::class,
        ConversationStateEntity::class,
        ProviderEntity::class,
        ProviderModelEntity::class,
        RuntimeResultEntity::class,
        RuntimeArchiveRunEntity::class,
        RuntimeArchiveEventEntity::class,
        RuntimeInFlightRunEntity::class,
        RuntimeInFlightEventEntity::class,
        SkillRegistryEntity::class,
        McpServerEntity::class,
        AgentTaskEntity::class,
        AgentTaskRunEntity::class,
    ],
    version = 19,
    exportSchema = false,
)
internal abstract class EtaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun providerDao(): ProviderDao
    abstract fun runtimeRunDao(): RuntimeRunDao
    abstract fun skillDao(): SkillDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun agentTaskDao(): AgentTaskDao
    abstract fun agentTaskRunDao(): AgentTaskRunDao

    companion object {
        @Volatile
        private var instance: EtaDatabase? = null

        fun get(context: Context): EtaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EtaDatabase::class.java,
                    "eta.db",
                )
                    .addMigrations(
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                    )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }

        @VisibleForTesting
        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        internal val MIGRATION_6_7 = Migration(6, 7) { database ->
            database.execSQL(
                "ALTER TABLE runtime_results ADD COLUMN transcript_json TEXT NOT NULL DEFAULT '[]'"
            )
            database.execSQL(
                "ALTER TABLE runtime_archive_runs ADD COLUMN transcript_json TEXT NOT NULL DEFAULT '[]'"
            )
        }

        internal val MIGRATION_16_17 = Migration(16, 17) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS mcp_servers (" +
                    "id TEXT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "url TEXT NOT NULL, " +
                    "enabled INTEGER NOT NULL, " +
                    "protocol_mode TEXT NOT NULL, " +
                    "authorization_type TEXT NOT NULL, " +
                    "tools_json TEXT NOT NULL, " +
                    "enabled_tool_names_json TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "sort_order INTEGER NOT NULL, " +
                    "last_refreshed_at INTEGER, " +
                    "last_protocol_version TEXT, " +
                    "PRIMARY KEY(id))"
            )
        }

        internal val MIGRATION_17_18 = Migration(17, 18) { database ->
            database.execSQL("ALTER TABLE mcp_servers ADD COLUMN tools_expire_at INTEGER")
        }

        internal val MIGRATION_18_19 = Migration(18, 19) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS agent_tasks (" +
                    "id TEXT NOT NULL, " +
                    "name TEXT NOT NULL, " +
                    "description TEXT, " +
                    "mode TEXT NOT NULL, " +
                    "prompt TEXT, " +
                    "actions_json TEXT, " +
                    "schedule_type TEXT NOT NULL, " +
                    "at_unix_ms INTEGER, " +
                    "cron_expression TEXT, " +
                    "timezone TEXT, " +
                    "start_at_unix_ms INTEGER, " +
                    "end_at_unix_ms INTEGER, " +
                    "enabled INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, " +
                    "last_run_at INTEGER, " +
                    "next_run_at INTEGER, " +
                    "last_outcome TEXT, " +
                    "last_error TEXT, " +
                    "runs_so_far INTEGER NOT NULL, " +
                    "max_runs INTEGER, " +
                    "catchup TEXT NOT NULL, " +
                    "notify_on_success INTEGER NOT NULL, " +
                    "PRIMARY KEY(id))"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_agent_tasks_enabled " +
                    "ON agent_tasks(enabled)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_agent_tasks_next_run_at " +
                    "ON agent_tasks(next_run_at)"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS agent_task_runs (" +
                    "id TEXT NOT NULL, " +
                    "task_id TEXT NOT NULL, " +
                    "mode TEXT NOT NULL, " +
                    "scheduled_at INTEGER NOT NULL, " +
                    "started_at INTEGER NOT NULL, " +
                    "finished_at INTEGER, " +
                    "outcome TEXT NOT NULL, " +
                    "manual INTEGER NOT NULL DEFAULT 0, " +
                    "runtime_run_id TEXT, " +
                    "result TEXT, " +
                    "error TEXT, " +
                    "PRIMARY KEY(id))"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_agent_task_runs_task_id_started_at " +
                    "ON agent_task_runs(task_id, started_at)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_agent_task_runs_task_id_outcome " +
                    "ON agent_task_runs(task_id, outcome)"
            )
        }

        internal val MIGRATION_7_8 = Migration(7, 8) { database ->
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN " +
                    "applied_runtime_run_ids_json TEXT NOT NULL DEFAULT '[]'"
            )
        }

        internal val MIGRATION_8_9 = Migration(8, 9) { database ->
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN source TEXT NOT NULL DEFAULT 'manual'"
            )
            database.execSQL(
                "UPDATE provider_models SET source = 'catalog' WHERE is_built_in = 1"
            )
            // 旧版“添加自定义模型”会在打开编辑框时提前落下一条空记录。
            database.execSQL("DELETE FROM provider_models WHERE TRIM(model_id) = ''")
            // 只清理由旧版“新建对话”产生、且用户从未真正使用或命名过的占位记录。
            database.execSQL(
                "DELETE FROM conversations " +
                    "WHERE title = '新对话' " +
                    "AND TRIM(history_json) = '[]' " +
                    "AND TRIM(applied_runtime_run_ids_json) = '[]' " +
                    "AND NOT EXISTS (" +
                    "SELECT 1 FROM conversation_messages " +
                    "WHERE conversation_messages.conversation_id = conversations.id)"
            )
            database.execSQL(
                "DELETE FROM conversation_state WHERE selected_conversation_id NOT IN " +
                    "(SELECT id FROM conversations)"
            )
        }

        internal val MIGRATION_9_10 = Migration(9, 10) { database ->
            database.execSQL(
                "ALTER TABLE conversations ADD COLUMN " +
                    "reasoning_effort TEXT NOT NULL DEFAULT 'default'"
            )
            database.execSQL(
                "UPDATE conversations SET reasoning_effort = " +
                    "CASE WHEN thinking_enabled = 1 THEN 'default' ELSE 'off' END"
            )
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN " +
                    "reasoning_capabilities_json TEXT NOT NULL DEFAULT 'null'"
            )
        }

        internal val MIGRATION_10_11 = Migration(10, 11) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS conversation_context_checkpoints (" +
                    "conversation_id TEXT NOT NULL, " +
                    "history_json TEXT NOT NULL, " +
                    "PRIMARY KEY(conversation_id), " +
                    "FOREIGN KEY(conversation_id) REFERENCES conversations(id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL(
                "INSERT INTO conversation_context_checkpoints (conversation_id, history_json) " +
                    "SELECT id, CASE " +
                    "WHEN length(CAST(history_json AS BLOB)) <= 131072 THEN history_json " +
                    "ELSE '[]' END FROM conversations"
            )
            // 会话列表不再使用旧字段；及时清空可保证旧版留下的超大行不会继续占用数据库。
            database.execSQL("UPDATE conversations SET history_json = '[]'")
        }

        internal val MIGRATION_11_12 = Migration(11, 12) { database ->
            database.execSQL(
                "ALTER TABLE conversation_messages ADD COLUMN " +
                    "is_edited INTEGER NOT NULL DEFAULT 0"
            )
        }

        internal val MIGRATION_12_13 = Migration(12, 13) { database ->
            database.execSQL(
                "ALTER TABLE model_providers ADD COLUMN " +
                    "hosted_web_search_enabled INTEGER NOT NULL DEFAULT 0"
            )
        }

        internal val MIGRATION_13_14 = Migration(13, 14) { database ->
            database.execSQL(
                "ALTER TABLE runtime_archive_runs ADD COLUMN " +
                    "user_image_previews_json TEXT NOT NULL DEFAULT '[]'"
            )
        }

        internal val MIGRATION_14_15 = Migration(14, 15) { database ->
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN context_window_override INTEGER"
            )
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN reasoning_override INTEGER"
            )
            database.execSQL(
                "ALTER TABLE provider_models ADD COLUMN " +
                    "reasoning_capabilities_override_json TEXT NOT NULL DEFAULT 'null'"
            )
        }

        internal val MIGRATION_15_16 = Migration(15, 16) { database ->
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS runtime_inflight_runs (" +
                    "run_id TEXT NOT NULL, " +
                    "owner_instance_id TEXT NOT NULL, " +
                    "handoff_id TEXT NOT NULL, " +
                    "handoff_source TEXT NOT NULL, " +
                    "handoff_payload TEXT NOT NULL, " +
                    "dismiss_entry_surface INTEGER NOT NULL, " +
                    "created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, " +
                    "PRIMARY KEY(run_id))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS runtime_inflight_events (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "run_id TEXT NOT NULL, " +
                    "sort_index INTEGER NOT NULL, " +
                    "event_json TEXT NOT NULL, " +
                    "FOREIGN KEY(run_id) REFERENCES runtime_inflight_runs(run_id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_runtime_inflight_events_run_id " +
                    "ON runtime_inflight_events(run_id)"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "index_runtime_inflight_events_run_id_sort_index " +
                    "ON runtime_inflight_events(run_id, sort_index)"
            )
        }
    }
}
