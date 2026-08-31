package io.github.mangi.eta.ui.screens.tasks

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.task.AgentTaskScheduler
import io.github.mangi.eta.data.db.AgentTaskEntity
import io.github.mangi.eta.data.repository.AgentTaskRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AgentTasksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { AgentTaskRepository(context) }
    val scheduler = remember(context) { AgentTaskScheduler(context) }
    val tasks by repository.observeAll().collectAsState(initial = emptyList())
    var taskToDelete by remember { mutableStateOf<AgentTaskEntity?>(null) }
    var notificationsEnabled by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsEnabled = granted }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_tasks),
        onBack = onBack,
    ) {
        item(key = "description") {
            BasicComponent(
                title = stringResource(R.string.tasks_title),
                summary = stringResource(R.string.tasks_description),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (!notificationsEnabled) {
                TextButton(
                    text = stringResource(R.string.tasks_enable_notifications),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
        if (tasks.isEmpty()) {
            item(key = "empty") {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    BasicComponent(
                        title = stringResource(R.string.tasks_empty),
                        summary = stringResource(R.string.tasks_empty_summary),
                    )
                }
            }
        } else {
            item(key = "configured") {
                SmallTitle(stringResource(R.string.tasks_configured, tasks.size))
            }
            items(tasks, key = { it.id }) { task ->
                Card(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    SwitchPreference(
                        title = task.name,
                        summary = taskSummary(task),
                        checked = task.enabled,
                        onCheckedChange = { enabled ->
                            scope.launch(Dispatchers.IO) {
                                if (enabled) {
                                    scheduler.schedule(
                                        task.copy(
                                            enabled = true,
                                            nextRunAt = null,
                                            updatedAt = System.currentTimeMillis(),
                                        )
                                    )
                                } else {
                                    scheduler.cancel(task.id)
                                    repository.update(
                                        task.copy(
                                            enabled = false,
                                            nextRunAt = null,
                                            updatedAt = System.currentTimeMillis(),
                                        )
                                    )
                                }
                            }
                        },
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = stringResource(R.string.tasks_run_now),
                            enabled = task.enabled,
                            onClick = {
                                scope.launch(Dispatchers.IO) { scheduler.triggerNow(task.id) }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            text = stringResource(R.string.action_delete),
                            onClick = { taskToDelete = task },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    taskToDelete?.let { task ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.tasks_delete_title),
            summary = stringResource(R.string.tasks_delete_summary, task.name),
            onDismissRequest = { taskToDelete = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { taskToDelete = null },
                onConfirm = {
                    taskToDelete = null
                    scope.launch(Dispatchers.IO) {
                        scheduler.cancel(task.id)
                        repository.deleteWithHistory(task.id)
                    }
                },
            )
        }
    }
}

private fun taskSummary(task: AgentTaskEntity): String {
    val schedule = if (task.scheduleType == "cron") {
        "cron: ${task.cronExpression.orEmpty()}"
    } else {
        "once"
    }
    val next = task.nextRunAt?.let { formatTime(it) } ?: "—"
    val outcome = task.lastOutcome ?: "—"
    return "$schedule · next: $next · last: $outcome · runs: ${task.runsSoFar}"
}

private fun formatTime(value: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
