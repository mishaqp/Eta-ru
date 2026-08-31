package io.github.mangi.eta.ui.screens.assistants

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.repository.AssistantProfileRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AssistantProfilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by AssistantProfileRepository.profilesFlow()
        .collectAsState(initial = emptyList())
    var editorProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    var isNewProfile by remember { mutableStateOf(false) }

    fun openNewProfile() {
        isNewProfile = true
        editorProfile = AssistantProfile()
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_assistants),
        onBack = onBack,
        actions = {
            IconButton(onClick = ::openNewProfile) {
                Icon(
                    painter = painterResource(LucideR.drawable.lucide_ic_plus),
                    contentDescription = stringResource(R.string.assistant_profile_add),
                )
            }
        },
    ) {
        item(key = "description") {
            BasicComponent(
                title = stringResource(R.string.route_assistants),
                summary = stringResource(R.string.assistant_profiles_description),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        if (profiles.isEmpty()) {
            item(key = "empty") {
                Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    BasicComponent(
                        title = stringResource(R.string.assistant_profiles_empty),
                        summary = stringResource(R.string.assistant_profiles_empty_summary),
                        onClick = ::openNewProfile,
                    )
                }
            }
        } else {
            item(key = "configured") {
                SmallTitle(stringResource(R.string.assistant_profiles_configured, profiles.size))
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    profiles.forEachIndexed { index, profile ->
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                        }
                        ArrowPreference(
                            title = profile.name,
                            summary = profile.description.takeIf(String::isNotBlank)
                                ?: stringResource(R.string.assistant_profile_inherit_model),
                            onClick = {
                                isNewProfile = false
                                editorProfile = profile
                            },
                        )
                    }
                }
            }
        }
    }

    editorProfile?.let { profile ->
        var name by remember(profile.id) { mutableStateOf(profile.name) }
        var description by remember(profile.id) { mutableStateOf(profile.description) }
        var systemPrompt by remember(profile.id) { mutableStateOf(profile.systemPrompt) }
        var enabled by remember(profile.id) { mutableStateOf(profile.enabled) }
        var error by remember(profile.id) { mutableStateOf<String?>(null) }
        var saving by remember(profile.id) { mutableStateOf(false) }

        WindowDialog(
            show = true,
            title = stringResource(
                if (isNewProfile) R.string.assistant_profile_add
                else R.string.assistant_profile_edit,
            ),
            onDismissRequest = { if (!saving) editorProfile = null },
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = stringResource(R.string.assistant_profile_name),
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = description,
                    onValueChange = { description = it },
                    label = stringResource(R.string.assistant_profile_description),
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = stringResource(R.string.assistant_profile_system_prompt),
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchPreference(
                    title = stringResource(R.string.assistant_profile_enabled),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                Text(
                    text = stringResource(R.string.assistant_profile_inherit_model),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                error?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                MiuixDialogActions(
                    confirmText = stringResource(R.string.action_save),
                    confirmEnabled = !saving,
                    onCancel = { if (!saving) editorProfile = null },
                    onConfirm = {
                        val trimmedName = name.trim()
                        if (trimmedName.isBlank()) {
                            error = context.getString(R.string.assistant_profile_name_required)
                            return@MiuixDialogActions
                        }
                        saving = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    if (isNewProfile) {
                                        AssistantProfileRepository.create(
                                            name = trimmedName,
                                            description = description.trim(),
                                        ).let { created ->
                                            AssistantProfileRepository.save(
                                                created.copy(
                                                    systemPrompt = systemPrompt.trim(),
                                                    enabled = enabled,
                                                )
                                            )
                                        }
                                    } else {
                                        AssistantProfileRepository.save(
                                            profile.copy(
                                                name = trimmedName,
                                                description = description.trim(),
                                                systemPrompt = systemPrompt.trim(),
                                                enabled = enabled,
                                            )
                                        )
                                    }
                                }
                            }
                            result.onSuccess {
                                editorProfile = null
                            }.onFailure {
                                error = context.getString(R.string.assistant_profile_save_failed)
                            }
                            saving = false
                        }
                    },
                )
            }
        }
    }
}
