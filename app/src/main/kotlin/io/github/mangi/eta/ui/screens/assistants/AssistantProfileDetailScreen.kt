package io.github.mangi.eta.ui.screens.assistants

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.repository.AssistantProfileRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AssistantProfileDetailScreen(
    profileId: String,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by AssistantProfileRepository.stateFlow()
        .collectAsState(initial = io.github.mangi.eta.data.model.AssistantProfilesState())
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val profile = state.profiles.firstOrNull { it.id == profileId }
    if (profile == null) {
        LaunchedEffect(profileId) { onBack() }
        return
    }

    var draft by remember(profile.id) { mutableStateOf(profile) }
    var notice by remember(profile.id) { mutableStateOf<String?>(null) }
    var modelPickerOpen by remember(profile.id) { mutableStateOf(false) }
    val active = state.activeProfileId == profile.id
    val providerSummary = profile.modelSummary(providers)

    MiuixScaffoldPage(
        title = stringResource(R.string.route_assistant_profile),
        onBack = onBack,
    ) {
        item(key = "profile_identity") {
            SmallTitle(stringResource(R.string.assistant_profile_section_general))
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = draft.avatar,
                        onValueChange = { draft = draft.copy(avatar = it.take(8)) },
                        label = stringResource(R.string.assistant_profile_avatar),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        label = stringResource(R.string.assistant_profile_name),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                    TextField(
                        value = draft.description,
                        onValueChange = { draft = draft.copy(description = it) },
                        label = stringResource(R.string.assistant_profile_description),
                        minLines = 2,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }
        }

        item(key = "profile_model") {
            SmallTitle(stringResource(R.string.assistant_profile_section_model))
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.assistant_profile_model),
                    summary = providerSummary.ifBlank {
                        stringResource(R.string.settings_not_configured)
                    },
                    onClick = { modelPickerOpen = true },
                )
                AssistantProfileDivider()
                SwitchPreference(
                    title = stringResource(R.string.assistant_profile_thinking),
                    checked = draft.thinkingEnabled,
                    onCheckedChange = { draft = draft.copy(thinkingEnabled = it) },
                )
            }
        }

        item(key = "profile_prompt") {
            SmallTitle(stringResource(R.string.assistant_profile_section_prompt))
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.assistant_profile_prompt_summary),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    TextField(
                        value = draft.systemPrompt,
                        onValueChange = { draft = draft.copy(systemPrompt = it) },
                        label = stringResource(R.string.assistant_profile_system_prompt),
                        minLines = 5,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }
        }

        item(key = "profile_extensions") {
            SmallTitle(stringResource(R.string.assistant_profile_section_extensions))
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.assistant_profile_memory),
                    checked = draft.memoryEnabled,
                    onCheckedChange = { draft = draft.copy(memoryEnabled = it) },
                )
                AssistantProfileDivider()
                SwitchPreference(
                    title = stringResource(R.string.assistant_profile_skills),
                    checked = draft.skillsEnabled,
                    onCheckedChange = { draft = draft.copy(skillsEnabled = it) },
                )
                AssistantProfileDivider()
                ArrowPreference(
                    title = stringResource(R.string.route_skills),
                    summary = stringResource(R.string.assistant_profile_skills_summary),
                    enabled = draft.skillsEnabled,
                    onClick = { onNavigate(AppRoute.Skills) },
                )
                AssistantProfileDivider()
                SwitchPreference(
                    title = stringResource(R.string.assistant_profile_mcp),
                    checked = draft.mcpEnabled,
                    onCheckedChange = { draft = draft.copy(mcpEnabled = it) },
                )
                AssistantProfileDivider()
                ArrowPreference(
                    title = stringResource(R.string.route_mcp_servers),
                    summary = stringResource(R.string.assistant_profile_mcp_summary),
                    enabled = draft.mcpEnabled,
                    onClick = { onNavigate(AppRoute.McpServers) },
                )
            }
        }

        item(key = "profile_tools") {
            SmallTitle(stringResource(R.string.assistant_profile_section_tools))
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.assistant_profile_browser_tools),
                    checked = draft.browserToolsEnabled,
                    onCheckedChange = { draft = draft.copy(browserToolsEnabled = it) },
                )
                AssistantProfileDivider()
                SwitchPreference(
                    title = stringResource(R.string.assistant_profile_device_tools),
                    checked = draft.deviceToolsEnabled,
                    onCheckedChange = { draft = draft.copy(deviceToolsEnabled = it) },
                )
                AssistantProfileDivider()
                SwitchPreference(
                    title = stringResource(R.string.assistant_profile_sensitive_read_tools),
                    checked = draft.sensitiveReadToolsEnabled,
                    onCheckedChange = { draft = draft.copy(sensitiveReadToolsEnabled = it) },
                )
                AssistantProfileDivider()
                SwitchPreference(
                    title = stringResource(R.string.assistant_profile_sensitive_action_tools),
                    checked = draft.sensitiveActionToolsEnabled,
                    onCheckedChange = { draft = draft.copy(sensitiveActionToolsEnabled = it) },
                )
                AssistantProfileDivider()
                SwitchPreference(
                    title = stringResource(R.string.assistant_profile_terminal_tools),
                    checked = draft.terminalToolsEnabled,
                    onCheckedChange = { draft = draft.copy(terminalToolsEnabled = it) },
                )
            }
        }

        item(key = "profile_request") {
            SmallTitle(stringResource(R.string.assistant_profile_section_request))
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    TextField(
                        value = draft.requestHeadersJson,
                        onValueChange = { draft = draft.copy(requestHeadersJson = it) },
                        label = stringResource(R.string.assistant_profile_request_headers),
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = draft.requestBodyJson,
                        onValueChange = { draft = draft.copy(requestBodyJson = it) },
                        label = stringResource(R.string.assistant_profile_request_body),
                        minLines = 3,
                        maxLines = 7,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            }
        }

        item(key = "profile_actions") {
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                TextButton(
                    text = stringResource(R.string.action_save),
                    onClick = {
                        notice = AssistantProfileRepository.validateRequestOverrides(draft)
                        if (notice == null) {
                            scope.launch {
                                AssistantProfileRepository.update(draft)
                                if (active) {
                                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                }
                                onBack()
                            }
                        }
                    },
                    enabled = draft.name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!active) {
                    TextButton(
                        text = stringResource(R.string.assistant_profile_activate),
                        onClick = {
                            scope.launch {
                                AssistantProfileRepository.update(draft)
                                AssistantProfileRepository.activate(draft.id)
                                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                onBack()
                            }
                        },
                        enabled = draft.name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        }
    }

    notice?.let { message ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.assistant_profile_invalid_request),
            summary = message,
            onDismissRequest = { notice = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_confirm),
                onCancel = { notice = null },
                onConfirm = { notice = null },
            )
        }
    }

    if (modelPickerOpen) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.assistant_profile_model),
            summary = stringResource(R.string.assistant_profile_model_picker_summary),
            onDismissRequest = { modelPickerOpen = false },
        ) {
            Column {
                providers
                    .filter { it.isEnabled }
                    .forEach { provider ->
                        Text(
                            text = provider.name,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        provider.models
                            .filter { it.isEnabled }
                            .forEach { model ->
                                TextButton(
                                    text = model.displayName,
                                    onClick = {
                                        draft = draft.copy(
                                            providerId = provider.id,
                                            modelId = model.id,
                                        )
                                        modelPickerOpen = false
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                    }
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { modelPickerOpen = false },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}
