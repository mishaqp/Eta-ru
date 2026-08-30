package io.github.mangi.eta.data.codex

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/** Native ChatGPT OAuth PKCE flow compatible with Codex device accounts. */
internal class CodexOAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: CodexCredentialStore = CodexCredentialStore(context),
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val sessions = ConcurrentHashMap<String, OAuthSession>()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-codex-oauth-callback").apply { isDaemon = true }
    }
    @Volatile private var callbackServer: ServerSocket? = null
    @Volatile private var callbackPort: Int? = null

    private val _status = MutableStateFlow<CodexOAuthStatus>(
        store.read().allAccounts().firstOrNull()?.let(CodexOAuthStatus::SignedIn)
            ?: CodexOAuthStatus.Idle,
    )
    val status: StateFlow<CodexOAuthStatus> = _status.asStateFlow()

    fun startLogin() {
        val state = randomUrlSafe(32)
        runCatching {
            val verifier = randomUrlSafe(64)
            val challenge = Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            val port = ensureCallbackServer()
            // The Codex OAuth client has localhost registered as its loopback redirect host.
            // Keep the listener bound to 127.0.0.1, but send the exact registered host in OAuth.
            val redirectUri = "http://localhost:$port/auth/callback"
            sessions[state] = OAuthSession(verifier, redirectUri)
            _status.value = CodexOAuthStatus.WaitingForBrowser
            val url = Uri.parse(AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("id_token_add_organizations", "true")
                .appendQueryParameter("codex_cli_simplified_flow", "true")
                .appendQueryParameter("originator", "codex_cli_rs")
                .build()
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { error ->
            sessions.remove(state)
            _status.value = CodexOAuthStatus.Failed(error.message ?: "Не удалось открыть вход ChatGPT")
        }
    }

    fun logout() {
        store.clear()
        CodexAccountRepository.reload()
        _status.value = CodexOAuthStatus.Idle
    }

    private fun ensureCallbackServer(): Int {
        callbackPort?.let { return it }
        val port = CALLBACK_PORT
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            }
        } catch (error: Throwable) {
            throw IllegalStateException("Не удалось открыть локальный OAuth callback на порту $port", error)
        }
        callbackServer = socket
        callbackPort = port
        executor.execute {
            while (!socket.isClosed) {
                runCatching { socket.accept() }.getOrNull()?.use(::handleCallback)
            }
        }
        return port
    }

    private fun handleCallback(socket: java.net.Socket) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine().orEmpty()
        while (reader.readLine()?.isNotEmpty() == true) Unit
        val target = requestLine.split(' ').getOrNull(1).orEmpty()
        val uri = Uri.parse("http://localhost$target")
        val state = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        val session = state?.let(sessions::remove)
        writeCallbackPage(socket, session != null && code != null && error.isNullOrBlank())
        when {
            session == null -> _status.value = CodexOAuthStatus.Failed("OAuth state не совпадает")
            !error.isNullOrBlank() -> _status.value = CodexOAuthStatus.Failed(error)
            code.isNullOrBlank() -> _status.value = CodexOAuthStatus.Failed("Не получен OAuth code")
            else -> scope.launch(Dispatchers.IO) {
                runCatching { exchangeCode(code, session) }
                    .onSuccess { account ->
                        store.write(CodexAccountState(account = account))
                        CodexAccountRepository.reload()
                        _status.value = CodexOAuthStatus.SignedIn(account)
                    }
                    .onFailure { failure ->
                        _status.value = CodexOAuthStatus.Failed(
                            failure.message ?: "Не удалось завершить вход ChatGPT",
                        )
                    }
            }
        }
    }

    private fun exchangeCode(code: String, session: OAuthSession): CodexAccount {
        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("client_id", CLIENT_ID)
                    .add("code", code)
                    .add("redirect_uri", session.redirectUri)
                    .add("code_verifier", session.verifier)
                    .build(),
            )
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            check(response.isSuccessful) { "Обмен OAuth token завершился кодом ${response.code}" }
            val token = json.parseToJsonElement(body).jsonObject
            val idToken = token["id_token"]?.jsonPrimitive?.contentOrNull
                ?: error("OAuth не вернул ID token")
            val claims = decodeJwtClaims(idToken)
            val auth = claims["https://api.openai.com/auth"]?.jsonObject
                ?: error("В ID token нет ChatGPT account")
            val accountId = auth["chatgpt_account_id"]?.jsonPrimitive?.contentOrNull
                ?: error("В ID token нет ChatGPT Account ID")
            return CodexAccount(
                chatgptAccountId = accountId,
                userId = auth["chatgpt_user_id"]?.jsonPrimitive?.contentOrNull
                    ?: claims["sub"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                name = claims["name"]?.jsonPrimitive?.contentOrNull
                    ?: claims["email"]?.jsonPrimitive?.contentOrNull
                    ?: "ChatGPT",
                email = claims["email"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
                    ?: error("OAuth не вернул access token"),
                refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
                    ?: error("OAuth не вернул refresh token"),
                idToken = idToken,
                expiresAt = System.currentTimeMillis() +
                    (token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L) * 1000,
            )
        }
    }

    private fun decodeJwtClaims(token: String) = json.parseToJsonElement(
        Base64.decode(token.split('.').getOrNull(1) ?: error("Некорректный ID token"), Base64.URL_SAFE)
            .decodeToString(),
    ).jsonObject

    private fun writeCallbackPage(socket: java.net.Socket, success: Boolean) {
        val body = if (success) {
            "<h2>Вход завершён</h2><p>Вернитесь в Eta.</p>"
        } else {
            "<h2>Вход не завершён</h2><p>Вернитесь в Eta и повторите попытку.</p>"
        }
        OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8).use { writer ->
            writer.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n")
            writer.write("<!doctype html><html><body>$body</body></html>")
            writer.flush()
        }
    }

    private fun randomUrlSafe(size: Int): String {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private data class OAuthSession(val verifier: String, val redirectUri: String)

    private companion object {
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
        const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        const val SCOPES = "openid profile email offline_access"
        const val CALLBACK_PORT = 1455
    }
}
