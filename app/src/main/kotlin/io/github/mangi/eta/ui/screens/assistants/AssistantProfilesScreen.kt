package io.github.mangi.eta.ui.screens.assistants

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.model.AssistantProfilesState
import io.github.mangi.eta.data.repository.AssistantProfileRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Assistant profiles are a small layer over ETA's existing provider/runtime stores. */
@Composable
internal fun AssistantProfilesScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state by AssistantProfileRepository.stateFlow()
        .collectAsState(initial = AssistantProfilesState())
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())

    LaunchedEffect(Unit) {
        AssistantProfileRepository.ensureDefaultProfile()
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_assistant_profiles),
        onBack = onBack,
    ) {
        item(key = "assistant_profiles_intro") {
            Text(
                text = stringResource(R.string.assistant_profiles_summary),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        item(key = "assistant_profiles_list") {
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                state.profiles.forEachIndexed { index, profile ->
                    if (index > 0) AssistantProfileDivider()
                    AssistantProfileRow(
                        profile = profile,
                        active = profile.id == state.activeProfileId,
                        modelSummary = profile.modelSummary(providers),
                        onOpen = { onNavigate(AppRoute.AssistantProfileDetail(profile.id)) },
                        onActivate = {
                            scope.launch {
                                AssistantProfileRepository.activate(profile.id)
                                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            }
                        },
                    )
                }
            }
        }
        item(key = "assistant_profiles_new") {
            TextButton(
                text = stringResource(R.string.assistant_profile_create),
                onClick = {
                    scope.launch {
                        val profile = AssistantProfileRepository.createProfile()
                        onNavigate(AppRoute.AssistantProfileDetail(profile.id))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun AssistantProfileRow(
    profile: AssistantProfile,
    active: Boolean,
    modelSummary: String,
    onOpen: () -> Unit,
    onActivate: () -> Unit,
) {
    ArrowPreference(
        title = "${profile.avatar}  ${profile.name}",
        summary = listOf(profile.description, modelSummary)
            .filter(String::isNotBlank)
            .joinToString(" · "),
        onClick = onOpen,
        endActions = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(
                    text = stringResource(
                        if (active) R.string.assistant_profile_active else R.string.assistant_profile_activate,
                    ),
                    onClick = onActivate,
                    enabled = !active,
                )
            }
        },
    )
}

@Composable
internal fun AssistantProfileDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
    )
}

internal fun AssistantProfile.modelSummary(
    providers: List<io.github.mangi.eta.data.model.ProviderSetting>,
): String {
    val provider = providers.firstOrNull { it.id == providerId } ?: return ""
    val model = provider.models.firstOrNull { it.id == modelId }
    return if (model == null) provider.name else "${provider.name} / ${model.displayName}"
}
