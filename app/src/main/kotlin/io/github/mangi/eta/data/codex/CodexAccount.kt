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
)

@Serializable
internal data class CodexAccountState(
    val account: CodexAccount? = null,
)

internal sealed interface CodexOAuthStatus {
    data object Idle : CodexOAuthStatus
    data object WaitingForBrowser : CodexOAuthStatus
    data class SignedIn(val account: CodexAccount) : CodexOAuthStatus
    data class Failed(val message: String) : CodexOAuthStatus
}
