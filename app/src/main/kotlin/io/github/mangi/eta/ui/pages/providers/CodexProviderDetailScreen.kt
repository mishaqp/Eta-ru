package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.codex.CodexAccount
import io.github.mangi.eta.data.codex.CodexAccountRepository
import io.github.mangi.eta.data.codex.CodexOAuthManager
import io.github.mangi.eta.data.codex.CodexOAuthStatus
import io.github.mangi.eta.data.codex.CodexRateLimitWindow
import io.github.mangi.eta.data.codex.CodexUsageStatus
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CodexProviderDetailScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val oauth = remember(context, scope) { CodexOAuthManager(context, scope) }
    val oauthStatus by oauth.status.collectAsState()
    val accountState by CodexAccountRepository.state.collectAsState()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val provider = providers.firstOrNull { it.id == BuiltinProviders.CODEX_ID }
        ?: BuiltinProviders.providerById(BuiltinProviders.CODEX_ID)
        ?: return
    val usage = remember { mutableStateMapOf<String, CodexUsageStatus>() }
    val errors = remember { mutableStateMapOf<String, String>() }
    var currentTab by remember { mutableIntStateOf(0) }

    fun syncRuntime() {
        scope.launch(Dispatchers.IO) {
            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
        }
    }

    fun refreshAccount(account: CodexAccount) {
        scope.launch(Dispatchers.IO) {
            runCatching { CodexAccountRepository.fetchUsageStatus(account) }
                .onSuccess { result ->
                    withContext(Dispatchers.Main) {
                        usage[account.chatgptAccountId] = result
                        errors.remove(account.chatgptAccountId)
                    }
                }
                .onFailure { failure ->
                    withContext(Dispatchers.Main) {
                        errors[account.chatgptAccountId] = failure.message ?: "Не удалось получить статус"
                    }
                }
        }
    }

    fun refreshAll() {
        accountState.allAccounts().forEach(::refreshAccount)
    }

    LaunchedEffect(oauthStatus) {
        if (oauthStatus is CodexOAuthStatus.SignedIn) {
            CodexAccountRepository.reload()
            syncRuntime()
            (oauthStatus as CodexOAuthStatus.SignedIn).account.let(::refreshAccount)
        }
    }

    LaunchedEffect(accountState.enabled, accountState.accounts) {
        syncRuntime()
    }

    MiuixScaffold(title = "Codex", onBack = onBack) { paddingValues, scrollBehavior, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            TabRow(
                tabs = listOf("Конфигурация", "Модели"),
                selectedTabIndex = currentTab,
                onTabSelected = { currentTab = it },
                modifier = Modifier.padding(horizontal = sidePadding + 12.dp, vertical = 8.dp),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (currentTab == 0) {
                    CodexConfigurationTab(
                        accounts = accountState.allAccounts(),
                        codexEnabled = accountState.enabled,
                        oauthStatus = oauthStatus,
                        usage = usage,
                        errors = errors,
                        onLogin = oauth::startLogin,
                        onToggleCodex = { enabled ->
                            CodexAccountRepository.setEnabled(enabled)
                            syncRuntime()
                        },
                        onToggleAccount = { id, enabled ->
                            CodexAccountRepository.setAccountEnabled(id, enabled)
                            syncRuntime()
                        },
                        onRefreshAll = ::refreshAll,
                        onRefresh = ::refreshAccount,
                        onRelogin = oauth::startLogin,
                        onDelete = { id ->
                            CodexAccountRepository.remove(id)
                            usage.remove(id)
                            errors.remove(id)
                            syncRuntime()
                        },
                        scrollBehavior = scrollBehavior,
                        sidePadding = sidePadding,
                    )
                } else {
                    ProviderModelsTab(
                        provider = provider,
                        scope = scope,
                        scrollBehavior = scrollBehavior,
                        contentSidePadding = sidePadding,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodexConfigurationTab(
    accounts: List<CodexAccount>,
    codexEnabled: Boolean,
    oauthStatus: CodexOAuthStatus,
    usage: Map<String, CodexUsageStatus>,
    errors: Map<String, String>,
    onLogin: () -> Unit,
    onToggleCodex: (Boolean) -> Unit,
    onToggleAccount: (String, Boolean) -> Unit,
    onRefreshAll: () -> Unit,
    onRefresh: (CodexAccount) -> Unit,
    onRelogin: () -> Unit,
    onDelete: (String) -> Unit,
    scrollBehavior: ScrollBehavior,
    sidePadding: androidx.compose.ui.unit.Dp,
) {
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = sidePadding, end = sidePadding, bottom = 28.dp),
    ) {
        item(key = "codex_intro") {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Text(
                    text = "Codex",
                    style = MiuixTheme.textStyles.title1,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Использует OpenAI Codex OAuth и Responses API. Интеграция зависит от совместимости с сервисом Codex.",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(modifier = Modifier.height(18.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = when (oauthStatus) {
                            CodexOAuthStatus.WaitingForBrowser -> "Ожидание входа в браузере…"
                            else -> "Войти через OpenAI OAuth"
                        },
                        enabled = oauthStatus !is CodexOAuthStatus.WaitingForBrowser,
                        onClick = onLogin,
                    )
                }
                if (oauthStatus is CodexOAuthStatus.Failed) {
                    Text(
                        text = oauthStatus.message,
                        color = MiuixTheme.colorScheme.error,
                        style = MiuixTheme.textStyles.footnote2,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        item(key = "codex_enabled") {
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                SwitchPreference(
                    title = "Включить Codex",
                    summary = "Использовать включённые аккаунты по кругу.",
                    checked = codexEnabled,
                    onCheckedChange = onToggleCodex,
                )
            }
        }

        item(key = "accounts_header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Аккаунты (${accounts.size})", style = MiuixTheme.textStyles.title3)
                TextButton(text = "Проверить статус", onClick = onRefreshAll)
            }
        }

        accounts.forEach { account ->
            item(key = "codex_account_${account.chatgptAccountId}") {
                CodexAccountCard(
                    account = account,
                    status = usage[account.chatgptAccountId],
                    error = errors[account.chatgptAccountId],
                    onToggle = { onToggleAccount(account.chatgptAccountId, it) },
                    onRefresh = { onRefresh(account) },
                    onRelogin = onRelogin,
                    onDelete = { onDelete(account.chatgptAccountId) },
                )
            }
        }

        if (accounts.isEmpty()) {
            item(key = "no_accounts") {
                Text(
                    text = "Аккаунтов пока нет. Войдите через OpenAI OAuth.",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun CodexAccountCard(
    account: CodexAccount,
    status: CodexUsageStatus?,
    error: String?,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRelogin: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        SwitchPreference(
            title = account.name.ifBlank { "ChatGPT" },
            summary = account.email.ifBlank { account.chatgptAccountId },
            checked = account.enabled,
            onCheckedChange = onToggle,
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp)) {
            Text(
                text = if (account.accessToken.isNotBlank() && account.expiresAt > System.currentTimeMillis()) {
                    "Токен доступен"
                } else {
                    "Требуется повторный вход"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
            error?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote2)
            }
            status?.primary?.let {
                Spacer(modifier = Modifier.height(16.dp))
                CodexLimitRow("Лимит на 5 часов", it)
            }
            status?.secondary?.let {
                Spacer(modifier = Modifier.height(16.dp))
                CodexLimitRow("Недельный лимит", it)
            }
            HorizontalDivider(modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(text = "Обновить", onClick = onRefresh)
                TextButton(text = "Повторный вход", onClick = onRelogin)
                TextButton(text = "Удалить", onClick = onDelete)
            }
        }
    }
}

@Composable
private fun CodexLimitRow(title: String, window: CodexRateLimitWindow) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MiuixTheme.textStyles.body2)
        Text("Осталось ${window.remainingPercent}%", style = MiuixTheme.textStyles.body2)
    }
    Spacer(modifier = Modifier.height(7.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .background(MiuixTheme.colorScheme.secondaryContainer, RoundedCornerShape(99.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(window.remainingPercent / 100f)
                .height(5.dp)
                .background(MiuixTheme.colorScheme.primary, RoundedCornerShape(99.dp)),
        )
    }
    Spacer(modifier = Modifier.height(7.dp))
    Text(
        text = "Сброс: ${formatReset(window.resetAt)}",
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

private fun formatReset(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return "—"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        .format(Date(epochSeconds * 1000L))
}
