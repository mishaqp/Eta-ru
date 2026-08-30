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

/** Interactive Linux command runner. Async jobs are used so commands such as Codex login can wait. */
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
    var jobId by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(controller) { onDispose { controller.closeAll() } }

    fun displayResult(raw: String): String {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
        if (!json.optBoolean("ok")) return json.optString("message").ifBlank { raw }
        val stdout = json.optString("stdout")
        val stderr = json.optString("stderr")
        val exit = if (json.has("exit_code") && !json.optBoolean("running")) {
            "\n[exit=${json.optInt("exit_code")}]"
        } else ""
        return (stdout + if (stderr.isNotBlank()) "\n[stderr]\n$stderr" else "" + exit)
            .replace(Regex("\u001B\\[[;\\d?]*[ -/]*[@-~]"), "")
            .trim()
            .ifBlank { context.getString(R.string.terminal_session_ready) }
    }

    fun call(action: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching { action() }.getOrElse { error ->
                    JSONObject().put("ok", false)
                        .put("message", error.message ?: error.javaClass.simpleName).toString()
                }
            }
            runCatching { JSONObject(raw) }.getOrNull()?.let { json ->
                if (json.optBoolean("ok")) {
                    json.optString("job_id").takeIf { it.isNotBlank() }?.let { jobId = it }
                }
            }
            output = displayResult(raw)
            busy = false
        }
    }

    fun readOutput() {
        val id = jobId ?: return
        call {
            controller.terminalAction(
                action = "read_async_result",
                command = "",
                cwd = null,
                timeoutMs = 5_000,
                identity = "root",
                mergeStderr = true,
                sessionId = null,
                jobId = id,
                async = false,
                offsetChars = 0,
                maxChars = 16_000,
                closeIfDone = false,
                environment = "linux",
            )
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
                    else context.getString(R.string.terminal_execute),
                    enabled = !busy && command.isNotBlank(),
                    onClick = {
                        call {
                            val started = controller.terminalAction(
                                action = "open_and_exec",
                                command = command,
                                cwd = null,
                                timeoutMs = 180_000,
                                identity = "root",
                                mergeStderr = true,
                                sessionId = null,
                                jobId = null,
                                async = true,
                                offsetChars = 0,
                                maxChars = 16_000,
                                closeIfDone = false,
                                environment = "linux",
                            )
                            Thread.sleep(800)
                            val id = JSONObject(started).optString("job_id")
                            if (id.isBlank()) started else controller.terminalAction(
                                action = "read_async_result",
                                command = "",
                                cwd = null,
                                timeoutMs = 5_000,
                                identity = "root",
                                mergeStderr = true,
                                sessionId = null,
                                jobId = id,
                                async = false,
                                offsetChars = 0,
                                maxChars = 16_000,
                                closeIfDone = false,
                                environment = "linux",
                            )
                        }
                    },
                )
                TextButton(
                    text = context.getString(R.string.terminal_login_hint),
                    enabled = !busy,
                    onClick = { command = "codex login --device-auth" },
                )
                TextButton(
                    text = context.getString(R.string.terminal_refresh),
                    enabled = !busy && jobId != null,
                    onClick = ::readOutput,
                )
                TextButton(
                    text = context.getString(R.string.terminal_close_session),
                    enabled = !busy && jobId != null,
                    onClick = {
                        val id = jobId ?: return@TextButton
                        call {
                            controller.terminalAction(
                                action = "close",
                                command = "",
                                cwd = null,
                                timeoutMs = 5_000,
                                identity = "root",
                                mergeStderr = true,
                                sessionId = null,
                                jobId = id,
                                async = false,
                                offsetChars = 0,
                                maxChars = 16_000,
                                closeIfDone = false,
                                environment = "linux",
                            )
                        }
                        jobId = null
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
