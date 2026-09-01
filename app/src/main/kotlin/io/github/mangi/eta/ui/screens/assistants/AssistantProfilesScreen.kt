package io.github.mangi.eta.ui.screens.assistants

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup

@Composable
internal fun AssistantProfilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profiles by AssistantProfileRepository.profilesFlow()
        .collectAsState(initial = emptyList())
    val providers by ProviderRepository.providersFlow()
        .collectAsState(initial = emptyList())
    val providersById = remember(providers) { providers.associateBy { it.id } }
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
                            summary = buildString {
                                val provider = profile.providerId?.let(providersById::get)
                                if (profile.description.isNotBlank()) append(profile.description)
                                if (isNotEmpty()) append(" · ")
                                append(
                                    provider?.name
                                        ?: stringResource(R.string.assistant_profile_inherit_global_provider)
                                )
                                append(" / ")
                                append(
                                    provider?.models
                                        ?.firstOrNull { it.id == profile.modelId && it.isEnabled }
                                        ?.displayName
                                        ?: stringResource(
                                            if (profile.providerId == null) {
                                                R.string.assistant_profile_inherit_global_model
                                            } else {
                                                R.string.assistant_profile_first_enabled_model
                                            }
                                        )
                                )
                            },
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
        var providerId by remember(profile.id) { mutableStateOf(profile.providerId) }
        var modelId by remember(profile.id) { mutableStateOf(profile.modelId) }
        var enabled by remember(profile.id) { mutableStateOf(profile.enabled) }
        var error by remember(profile.id) { mutableStateOf<String?>(null) }
        var saving by remember(profile.id) { mutableStateOf(false) }
        var showProviderPopup by remember(profile.id) { mutableStateOf(false) }
        var showModelPopup by remember(profile.id) { mutableStateOf(false) }

        val enabledProviders = providers.filter { it.isEnabled }
        val selectedProvider = enabledProviders.firstOrNull { it.id == providerId }
        val enabledModels = selectedProvider?.models?.filter { it.isEnabled }.orEmpty()
        val selectedModel = enabledModels.firstOrNull { it.id == modelId }
        val providerSummary = selectedProvider?.name
            ?: stringResource(R.string.assistant_profile_inherit_global_provider)
        val modelSummary = when {
            selectedProvider == null -> stringResource(R.string.assistant_profile_inherit_global_model)
            selectedModel != null -> selectedModel.displayName
            enabledModels.isEmpty() -> stringResource(R.string.assistant_profile_no_models)
            else -> stringResource(R.string.assistant_profile_first_enabled_model)
        }

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
                Box(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = stringResource(R.string.assistant_profile_provider),
                        summary = providerSummary,
                        onClick = { showProviderPopup = true },
                    )
                    WindowListPopup(
                        show = showProviderPopup,
                        popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                        alignment = PopupPositionProvider.Align.BottomEnd,
                        onDismissRequest = { showProviderPopup = false },
                    ) {
                        ListPopupColumn {
                            val options: List<Pair<String?, String>> = listOf(
                                null to stringResource(R.string.assistant_profile_inherit_global_provider),
                            ) + enabledProviders.map { it.id to it.name }
                            options.forEachIndexed { index, (id, label) ->
                                DropdownImpl(
                                    text = label,
                                    optionSize = options.size,
                                    isSelected = id == providerId,
                                    index = index,
                                    onSelectedIndexChange = {
                                        providerId = id
                                        modelId = null
                                        showProviderPopup = false
                                    },
                                )
                            }
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    BasicComponent(
                        title = stringResource(R.string.assistant_profile_model),
                        summary = modelSummary,
                        onClick = {
                            if (selectedProvider != null && enabledModels.isNotEmpty()) {
                                showModelPopup = true
                            }
                        },
                    )
                    WindowListPopup(
                        show = showModelPopup && selectedProvider != null,
                        popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
                        alignment = PopupPositionProvider.Align.BottomEnd,
                        onDismissRequest = { showModelPopup = false },
                    ) {
                        ListPopupColumn {
                            val options: List<Pair<String?, String>> = listOf(
                                null to stringResource(R.string.assistant_profile_first_enabled_model),
                            ) + enabledModels.map { it.id to it.displayName }
                            options.forEachIndexed { index, (id, label) ->
                                DropdownImpl(
                                    text = label,
                                    optionSize = options.size,
                                    isSelected = id == modelId,
                                    index = index,
                                    onSelectedIndexChange = {
                                        modelId = id
                                        showModelPopup = false
                                    },
                                )
                            }
                        }
                    }
                }
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
                    text = stringResource(R.string.assistant_profile_selection_hint),
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
                                                    providerId = providerId,
                                                    modelId = modelId,
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
                                                providerId = providerId,
                                                modelId = modelId,
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
