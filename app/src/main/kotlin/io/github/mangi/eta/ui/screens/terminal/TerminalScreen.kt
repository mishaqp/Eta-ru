package io.github.mangi.eta.ui.screens.terminal

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentPaths
import io.github.mangi.eta.agent.terminal.RootShellTerminalController
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

@Composable
internal fun TerminalScreen(context: Context, onBack: () -> Unit) {
    val appContext = context.applicationContext
    val controller = remember(appContext) {
        RootShellTerminalController(
            logger = AndroidAgentLogger,
            linuxRootfsPath = AlpineEnvironmentPaths.rootfsDir(appContext).absolutePath,
        )
    }
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("codex --version") }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(controller) { onDispose { controller.closeAll() } }

    fun call(action: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { action() }.getOrElse { error ->
                    JSONObject().put("ok", false).put("message", error.message ?: error.javaClass.simpleName).toString()
                }
            }
            runCatching { JSONObject(result) }.getOrNull()?.let { json ->
                if (json.optBoolean("ok")) {
                    json.optString("session_id").takeIf { it.isNotBlank() }?.let { sessionId = it }
                }
            }
            output = result
            busy = false
        }
    }

    MiuixScaffoldPage(title = context.getString(R.string.terminal_title), onBack = onBack) {
        item(key = "terminal-info") {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                BasicComponent(
                    title = context.getString(R.string.terminal_session),
                    summary = context.getString(R.string.terminal_linux_root_hint),
                )
            }
        }
        item(key = "terminal-command") {
            TextField(
                value = command,
                onValueChange = { command = it },
                label = context.getString(R.string.terminal_command),
                enabled = !busy,
                singleLine = false,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp),
            )
        }
        item(key = "terminal-actions") {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                TextButton(
                    text = if (busy) context.getString(R.string.terminal_working)
                    else if (sessionId == null) context.getString(R.string.terminal_open_session)
                    else context.getString(R.string.terminal_execute),
                    enabled = !busy && command.isNotBlank(),
                    onClick = {
                        if (sessionId == null) {
                            call {
                                controller.terminalAction("open", "", null, 5_000, "root", true, null, null, false, 0, 16_000, false, "linux")
                            }
                        } else {
                            call {
                                controller.terminalAction("exec", command, null, 60_000, "root", true, sessionId, null, false, 0, 16_000, false, "linux")
                            }
                        }
                    },
                )
                TextButton(
                    text = context.getString(R.string.terminal_login_hint),
                    enabled = !busy && sessionId != null,
                    onClick = { command = "codex login --device-auth" },
                )
                TextButton(
                    text = context.getString(R.string.terminal_close_session),
                    enabled = !busy && sessionId != null,
                    onClick = {
                        val id = sessionId ?: return@TextButton
                        call {
                            controller.terminalAction("close", "", null, 5_000, "root", true, id, null, false, 0, 16_000, false, "linux")
                        }
                        sessionId = null
                    },
                )
            }
        }
        if (output.isNotBlank()) {
            item(key = "terminal-output") {
                BasicComponent(
                    title = context.getString(R.string.terminal_output),
                    summary = output.takeLast(16_000),
                )
            }
        }
    }
}
