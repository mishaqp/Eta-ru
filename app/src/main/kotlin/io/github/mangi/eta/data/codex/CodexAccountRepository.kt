package io.github.mangi.eta.data.codex

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Runtime source of truth for Codex OAuth accounts.
 *
 * Credentials remain in [CodexCredentialStore]. The repository exposes only the
 * account state required by UI and chooses enabled accounts round-robin for requests.
 */
internal object CodexAccountRepository {
    private const val USAGE_URL = "https://chatgpt.com/backend-api/codex/wham/usage"

    @Volatile
    private var store: CodexCredentialStore? = null
    private val http = OkHttpClient()
    private val cursor = AtomicInteger(0)
    private val mutableState = MutableStateFlow(CodexAccountState())

    val state: StateFlow<CodexAccountState> = mutableState.asStateFlow()

    fun init(context: Context) {
        if (store == null) {
            synchronized(this) {
                if (store == null) {
                    store = CodexCredentialStore(context.applicationContext)
                    reload()
                }
            }
        }
    }

    fun reload(): CodexAccountState {
        val loaded = requireStore().read().normalized()
        mutableState.value = loaded
        return loaded
    }

    fun setEnabled(enabled: Boolean) {
        persist(mutableState.value.normalized().copy(enabled = enabled))
    }

    fun setAccountEnabled(chatgptAccountId: String, enabled: Boolean) {
        val current = mutableState.value.normalized()
        persist(
            current.copy(
                accounts = current.accounts.map { account ->
                    if (account.chatgptAccountId == chatgptAccountId) {
                        account.copy(enabled = enabled)
                    } else {
                        account
                    }
                },
            ),
        )
    }

    fun upsert(account: CodexAccount) {
        val current = mutableState.value.normalized()
        val previous = current.accounts.firstOrNull {
            it.chatgptAccountId == account.chatgptAccountId
        }
        val merged = if (previous != null) account.copy(enabled = previous.enabled) else account
        persist(
            current.copy(
                accounts = current.accounts
                    .filterNot { it.chatgptAccountId == account.chatgptAccountId } + merged,
            ),
        )
    }

    fun remove(chatgptAccountId: String) {
        val current = mutableState.value.normalized()
        persist(
            current.copy(
                accounts = current.accounts.filterNot {
                    it.chatgptAccountId == chatgptAccountId
                },
            ),
        )
    }

    fun clear() {
        requireStore().clear()
        cursor.set(0)
        mutableState.value = CodexAccountState()
    }

    /** Returns the next enabled account, rotating between accounts on each call. */
    fun nextEnabledAccount(): CodexAccount? {
        val current = mutableState.value.normalized()
        if (!current.enabled) return null
        val enabledAccounts = current.accounts.filter(CodexAccount::enabled)
        if (enabledAccounts.isEmpty()) return null
        val index = Math.floorMod(cursor.getAndIncrement(), enabledAccounts.size)
        return enabledAccounts[index]
    }

    /** Reads the same usage windows used by the official Codex client. */
    fun fetchUsageStatus(account: CodexAccount): CodexUsageStatus {
        val request = Request.Builder()
            .url(USAGE_URL)
            .header("Authorization", "Bearer ${account.accessToken}")
            .header("ChatGPT-Account-ID", account.chatgptAccountId)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                error("Codex status HTTP ${response.code}: ${body.take(300)}")
            }
            val root = JSONObject(body)
            val rateLimit = root.optJSONObject("rate_limit")
            return CodexUsageStatus(
                planType = root.optString("plan_type"),
                primary = rateLimit?.optJSONObject("primary_window")?.toWindow(),
                secondary = rateLimit?.optJSONObject("secondary_window")?.toWindow(),
            )
        }
    }

    private fun JSONObject.toWindow(): CodexRateLimitWindow = CodexRateLimitWindow(
        usedPercent = optInt("used_percent", 0).coerceIn(0, 100),
        limitWindowSeconds = optInt("limit_window_seconds", 0),
        resetAfterSeconds = optInt("reset_after_seconds", 0),
        resetAt = optLong("reset_at", 0L),
    )

    private fun persist(state: CodexAccountState) {
        val normalized = state.normalized()
        requireStore().write(normalized)
        mutableState.value = normalized
    }

    private fun requireStore(): CodexCredentialStore =
        checkNotNull(store) { "CodexAccountRepository.init(context) must be called first" }
}
