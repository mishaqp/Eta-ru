package io.github.mangi.eta.hook.system

import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.core.ModuleConfig

internal data class AssistantBinding(
    val target: PowerAssistantTarget,
    val packageName: String,
    val componentName: String,
    val displayName: String,
)

internal fun assistantBindingFor(target: PowerAssistantTarget): AssistantBinding? = when (target) {
    PowerAssistantTarget.SYSTEM -> null
    PowerAssistantTarget.GEMINI -> AssistantBinding(
        target = target,
        packageName = ModuleConfig.GOOGLE_APP_PACKAGE,
        componentName = ModuleConfig.GOOGLE_ASSISTANT_COMPONENT,
        displayName = "Gemini",
    )
    PowerAssistantTarget.ETA -> AssistantBinding(
        target = target,
        packageName = ModuleConfig.ETA_PACKAGE,
        componentName = ModuleConfig.ETA_VOICE_INTERACTION_COMPONENT,
        displayName = "Eta",
    )
}

internal fun shouldConfigureAssistant(
    autoConfigEnabled: Boolean,
    target: PowerAssistantTarget,
): Boolean = autoConfigEnabled && target != PowerAssistantTarget.SYSTEM

internal enum class AssistantSelectionAction {
    NONE,
    CONFIGURE_MANAGED,
    RESTORE_SYSTEM,
}

internal fun assistantSelectionAction(
    autoConfigEnabled: Boolean,
    target: PowerAssistantTarget,
): AssistantSelectionAction = when {
    target == PowerAssistantTarget.SYSTEM -> AssistantSelectionAction.RESTORE_SYSTEM
    shouldConfigureAssistant(autoConfigEnabled, target) ->
        AssistantSelectionAction.CONFIGURE_MANAGED
    else -> AssistantSelectionAction.NONE
}

internal fun isAssistantConfigurationCurrent(
    autoConfigEnabled: Boolean,
    expectedTarget: PowerAssistantTarget,
    currentTarget: PowerAssistantTarget,
): Boolean = shouldConfigureAssistant(autoConfigEnabled, currentTarget) &&
    expectedTarget == currentTarget
