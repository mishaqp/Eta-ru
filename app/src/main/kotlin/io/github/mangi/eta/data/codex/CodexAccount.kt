package io.github.mangi.eta.data.codex

import kotlinx.serialization.Serializable

@Serializable
internal data class CodexAccount(
    val chatgptAccountId: String,
    val userId: String = "",
    val name: String = "ChatGPT",
    val email: String = "",
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val expiresAt: Long,
    val enabled: Boolean = true,
)

@Serializable
internal data class CodexAccountState(
    /** Legacy single-account field kept for seamless migration. */
    val account: CodexAccount? = null,
    val accounts: List<CodexAccount> = emptyList(),
    val enabled: Boolean = true,
    val roundRobinCursor: Int = 0,
) {
    fun allAccounts(): List<CodexAccount> =
        if (accounts.isNotEmpty()) accounts else listOfNotNull(account)

    fun normalized(): CodexAccountState = copy(
        account = null,
        accounts = allAccounts().distinctBy(CodexAccount::chatgptAccountId),
    )
}

@Serializable
internal data class CodexRateLimitWindow(
    val usedPercent: Int,
    val limitWindowSeconds: Int,
    val resetAfterSeconds: Int,
    val resetAt: Long,
) {
    val remainingPercent: Int
        get() = (100 - usedPercent).coerceIn(0, 100)
}

@Serializable
internal data class CodexUsageStatus(
    val planType: String = "",
    val primary: CodexRateLimitWindow? = null,
    val secondary: CodexRateLimitWindow? = null,
)

internal sealed interface CodexOAuthStatus {
    data object Idle : CodexOAuthStatus
    data object WaitingForBrowser : CodexOAuthStatus
    data class SignedIn(val account: CodexAccount) : CodexOAuthStatus
    data class Failed(val message: String) : CodexOAuthStatus
}
