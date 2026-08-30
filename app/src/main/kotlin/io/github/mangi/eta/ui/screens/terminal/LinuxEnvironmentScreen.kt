package io.github.mangi.eta.ui.screens.terminal
import io.github.mangi.eta.R
import androidx.compose.ui.res.stringResource

import android.content.Context
import android.icu.text.ListFormatter
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentInstaller
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentHealth
import io.github.mangi.eta.agent.terminal.AlpineApkAnalysisInstaller
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentState
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentStatus
import io.github.mangi.eta.agent.terminal.AlpineInstallProgress
import io.github.mangi.eta.agent.terminal.AlpineInstallResult
import io.github.mangi.eta.agent.terminal.AlpineInstallStage
import io.github.mangi.eta.agent.terminal.AlpinePackageProfile
import io.github.mangi.eta.agent.terminal.AlpinePackageProfileInstaller
import io.github.mangi.eta.agent.terminal.AlpinePackageProfiles
import io.github.mangi.eta.agent.terminal.ApkAnalysisInstallProgress
import io.github.mangi.eta.agent.terminal.ApkAnalysisInstallResult
import io.github.mangi.eta.agent.terminal.ApkAnalysisInstallStage
import io.github.mangi.eta.agent.terminal.PackageProfileInstallProgress
import io.github.mangi.eta.agent.terminal.PackageProfileInstallResult
import io.github.mangi.eta.agent.terminal.PackageProfileInstallStage
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton

private enum class InstallTarget {
    BASE,
    APK_ANALYSIS,
    PYTHON,
    NODE,
    SSH,
}

private data class PackageProfileUi(
    val target: InstallTarget,
    val profile: AlpinePackageProfile,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    @param:StringRes val readyRes: Int,
)

@Composable
internal fun LinuxEnvironmentScreen(
    context: Context,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val installer = remember(context.applicationContext) {
        AlpineEnvironmentInstaller(context.applicationContext)
    }
    val apkAnalysisInstaller = remember(context.applicationContext) {
        AlpineApkAnalysisInstaller(context.applicationContext)
    }
    val packageProfileUis = remember {
        listOf(
            PackageProfileUi(
                target = InstallTarget.PYTHON,
                profile = AlpinePackageProfiles.PYTHON,
                titleRes = R.string.linux_python_tools,
                summaryRes = R.string.linux_python_tools_summary,
                readyRes = R.string.linux_python_tools_ready,
            ),
            PackageProfileUi(
                target = InstallTarget.NODE,
                profile = AlpinePackageProfiles.NODE,
                titleRes = R.string.linux_node_tools,
                summaryRes = R.string.linux_node_tools_summary,
                readyRes = R.string.linux_node_tools_ready,
            ),
            PackageProfileUi(
                target = InstallTarget.SSH,
                profile = AlpinePackageProfiles.SSH,
                titleRes = R.string.linux_ssh_tools,
                summaryRes = R.string.linux_ssh_tools_summary,
                readyRes = R.string.linux_ssh_tools_ready,
            ),
        )
    }
    val profileInstallers = remember(context.applicationContext) {
        packageProfileUis.associate { profileUi ->
            profileUi.target to AlpinePackageProfileInstaller(context.applicationContext, profileUi.profile)
        }
    }
    val coroutineScope = rememberCoroutineScope()
    var status by remember { mutableStateOf(installer.status()) }
    var busyTarget by remember { mutableStateOf<InstallTarget?>(null) }
    var checkingHealth by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<AlpineInstallProgress?>(null) }
    var profileProgressSummary by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var health by remember { mutableStateOf<AlpineEnvironmentHealth?>(null) }
    var profileReady by remember {
        mutableStateOf(packageProfileUis.associate { it.target to profileInstallers.getValue(it.target).isReady() })
    }
    var apkAnalysisReady by remember { mutableStateOf(apkAnalysisInstaller.isReady()) }
    var apkAnalysisProgress by remember { mutableStateOf<ApkAnalysisInstallProgress?>(null) }

    MiuixScaffoldPage(
        title = stringResource(R.string.ui_linux_tool_environment_314d22),
        onBack = onBack,
    ) {
        item(key = "terminal-entry-card") {
            Card(
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = stringResource(R.string.terminal_title),
                    summary = stringResource(R.string.terminal_summary),
                    endActions = {
                        TextButton(
                            text = stringResource(R.string.terminal_open),
                            onClick = onOpenTerminal,
                        )
                    },
                )
            }
        }
        item(key = "status-title") { SmallTitle(stringResource(R.string.ui_environmental_status_5b32a1)) }
        item(key = "status-card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = status.title(context),
                    summary = progress?.summary(context) ?: status.summary(context),
                    endActions = {
                        TextButton(
                            text = when {
                                busyTarget == InstallTarget.BASE -> context.getString(R.string.linux_installing)
                                status.state == AlpineEnvironmentState.READY -> context.getString(R.string.linux_ready)
                                status.state == AlpineEnvironmentState.BASE_READY && status.version != null -> context.getString(R.string.linux_upgrade_tools)
                                status.state == AlpineEnvironmentState.BASE_READY -> context.getString(R.string.linux_continue_installation)
                                else -> context.getString(R.string.linux_download_install)
                            },
                            enabled = busyTarget == null && status.state != AlpineEnvironmentState.READY,
                            onClick = {
                                if (busyTarget != null) return@TextButton
                                busyTarget = InstallTarget.BASE
                                resultMessage = null
                                coroutineScope.launch {
                                    val result = installer.install { update ->
                                        withContext(Dispatchers.Main.immediate) {
                                            progress = update
                                        }
                                    }
                                    status = installer.status()
                                    profileReady = packageProfileUis.associate {
                                        it.target to profileInstallers.getValue(it.target).isReady()
                                    }
                                    apkAnalysisReady = apkAnalysisInstaller.isReady()
                                    health = null
                                    progress = null
                                    busyTarget = null
                                    resultMessage = result.toMessage(context)
                                }
                            },
                        )
                    },
                )
                if (status.state == AlpineEnvironmentState.READY) {
                    BasicComponent(
                        title = health?.title(context) ?: context.getString(R.string.linux_not_checked),
                        summary = health?.summary(context) ?: context.getString(R.string.linux_health_summary),
                        endActions = {
                            val repairNeeded = health?.healthy == false
                            TextButton(
                                text = when {
                                    busyTarget == InstallTarget.BASE -> context.getString(R.string.linux_busy)
                                    checkingHealth -> context.getString(R.string.linux_checking)
                                    repairNeeded -> context.getString(R.string.linux_repair)
                                    else -> context.getString(R.string.linux_check)
                                },
                                enabled = !checkingHealth && busyTarget == null,
                                onClick = {
                                    if (checkingHealth || busyTarget != null) return@TextButton
                                    if (repairNeeded) {
                                        busyTarget = InstallTarget.BASE
                                        health = null
                                        resultMessage = null
                                        coroutineScope.launch {
                                            val result = installer.install(forceToolInstall = true) { update ->
                                                withContext(Dispatchers.Main.immediate) {
                                                    progress = update
                                                }
                                            }
                                            status = installer.status()
                                            progress = null
                                            busyTarget = null
                                            resultMessage = result.toMessage(context)
                                        }
                                    } else {
                                        checkingHealth = true
                                        coroutineScope.launch {
                                            health = installer.inspectHealth()
                                            checkingHealth = false
                                        }
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }

        if (status.state == AlpineEnvironmentState.READY) {
            item(key = "optional-tools-title") { SmallTitle(stringResource(R.string.ui_optional_tools_3097d6)) }
            item(key = "optional-tools-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    packageProfileUis.forEach { profileUi ->
                        val ready = profileReady[profileUi.target] == true
                        BasicComponent(
                            title = stringResource(profileUi.titleRes),
                            summary = if (busyTarget == profileUi.target) {
                                profileProgressSummary ?: stringResource(profileUi.summaryRes)
                            } else if (ready) {
                                stringResource(profileUi.readyRes)
                            } else {
                                stringResource(profileUi.summaryRes)
                            },
                            endActions = {
                                TextButton(
                                    text = when {
                                        ready -> stringResource(R.string.linux_installed)
                                        busyTarget == profileUi.target -> stringResource(R.string.linux_installing)
                                        else -> stringResource(R.string.linux_install)
                                    },
                                    enabled = busyTarget == null && !ready,
                                    onClick = {
                                        if (busyTarget != null || ready) return@TextButton
                                        busyTarget = profileUi.target
                                        resultMessage = null
                                        val profileTitle = context.getString(profileUi.titleRes)
                                        coroutineScope.launch {
                                            val profileInstaller = profileInstallers.getValue(profileUi.target)
                                            val result = profileInstaller.install { update ->
                                                withContext(Dispatchers.Main.immediate) {
                                                    profileProgressSummary = update.summary(context, profileTitle)
                                                }
                                            }
                                            profileReady = profileReady +
                                                (profileUi.target to profileInstaller.isReady())
                                            profileProgressSummary = null
                                            busyTarget = null
                                            resultMessage = result.toMessage(context, profileTitle)
                                        }
                                    },
                                )
                            },
                        )
                    }
                    BasicComponent(
                        title = stringResource(R.string.ui_apk_analysis_95ad17),
                        summary = apkAnalysisProgress?.summary(context) ?: if (apkAnalysisReady) {
                            context.getString(R.string.linux_apk_tools_ready)
                        } else {
                            context.getString(R.string.linux_apk_tools_summary)
                        },
                        endActions = {
                            TextButton(
                                text = when {
                                    apkAnalysisReady -> context.getString(R.string.linux_installed)
                                    busyTarget == InstallTarget.APK_ANALYSIS -> context.getString(R.string.linux_installing)
                                    else -> context.getString(R.string.linux_install)
                                },
                                enabled = busyTarget == null && !apkAnalysisReady,
                                onClick = {
                                    if (busyTarget != null || apkAnalysisReady) return@TextButton
                                    busyTarget = InstallTarget.APK_ANALYSIS
                                    resultMessage = null
                                    coroutineScope.launch {
                                        val result = apkAnalysisInstaller.install { update ->
                                            withContext(Dispatchers.Main.immediate) {
                                                apkAnalysisProgress = update
                                            }
                                        }
                                        apkAnalysisReady = apkAnalysisInstaller.isReady()
                                        apkAnalysisProgress = null
                                        busyTarget = null
                                        resultMessage = result.toMessage(context)
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }

        resultMessage?.let { message ->
            item(key = "result-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(title = message)
                }
            }
        }
    }
}

private fun AlpineEnvironmentStatus.title(context: Context): String = when (state) {
    AlpineEnvironmentState.NOT_INSTALLED -> context.getString(R.string.linux_not_installed)
    AlpineEnvironmentState.BASE_READY -> context.getString(R.string.linux_base_ready)
    AlpineEnvironmentState.READY -> context.getString(R.string.linux_alpine_ready, version.orEmpty()).trim()
}

private fun AlpineEnvironmentStatus.summary(context: Context): String = when (state) {
    AlpineEnvironmentState.NOT_INSTALLED -> context.getString(R.string.linux_requirements)
    AlpineEnvironmentState.BASE_READY -> if (version == null) {
        context.getString(R.string.linux_tools_incomplete)
    } else {
        context.getString(R.string.linux_tools_upgrade_summary)
    }
    AlpineEnvironmentState.READY -> context.getString(R.string.linux_agent_ready_summary)
}

private fun AlpineEnvironmentHealth.title(context: Context): String = when {
    healthy -> context.getString(R.string.linux_health_ok)
    missingTools.isNotEmpty() -> context.resources.getQuantityString(
        R.plurals.linux_missing_core_commands,
        missingTools.size,
        missingTools.size,
    )
    !workspaceReady -> context.getString(R.string.linux_workspace_error)
    else -> context.getString(R.string.linux_health_needs_check)
}

private fun AlpineEnvironmentHealth.summary(context: Context): String {
    val details = buildList {
        if (missingTools.isNotEmpty()) {
            add(context.getString(R.string.linux_missing_tools, ListFormatter.getInstance().format(missingTools)))
        }
        add(context.getString(if (workspaceReady) R.string.linux_workspace_available else R.string.linux_workspace_unavailable))
        add(context.getString(if (sharedStorageReady) R.string.linux_sdcard_available else R.string.linux_sdcard_unavailable))
        add(context.getString(R.string.linux_space_remaining, availableBytes.toReadableSize(context)))
    }
    return ListFormatter.getInstance().format(details)
}

private fun Long.toReadableSize(context: Context): String = Formatter.formatShortFileSize(context, this)

private fun AlpineInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != AlpineInstallStage.DOWNLOADING || totalBytes <= 0L) {
        return stageName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    return context.getString(R.string.linux_progress_percent, stageName, percent)
}

private fun PackageProfileInstallProgress.summary(context: Context, profileTitle: String): String =
    when (stage) {
        PackageProfileInstallStage.CHECKING -> context.getString(R.string.linux_profile_stage_checking)
        PackageProfileInstallStage.INSTALLING ->
            context.getString(R.string.linux_profile_stage_installing, profileTitle)
        PackageProfileInstallStage.VERIFYING ->
            context.getString(R.string.linux_profile_stage_verifying, profileTitle)
        PackageProfileInstallStage.COMPLETE -> context.getString(R.string.linux_profile_stage_complete)
    }

private fun ApkAnalysisInstallProgress.summary(context: Context): String {
    val stageName = stage.displayName(context)
    if (stage != ApkAnalysisInstallStage.DOWNLOADING || totalBytes <= 0L) {
        return stageName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    val name = when (artifactName) {
        "jadx" -> "JADX"
        "apktool" -> "Apktool"
        "smali" -> "smali"
        "baksmali" -> "baksmali"
        else -> context.getString(R.string.linux_tool)
    }
    return context.getString(R.string.linux_tool_progress_percent, stageName, name, percent)
}

private fun AlpineInstallResult.toMessage(context: Context): String = when (this) {
    AlpineInstallResult.AlreadyReady -> context.getString(R.string.linux_already_ready)
    is AlpineInstallResult.Installed -> context.getString(R.string.linux_install_complete, version)
    is AlpineInstallResult.UnsupportedAbi -> context.getString(R.string.linux_unsupported_abi, abi)
    AlpineInstallResult.RootUnavailable -> context.getString(R.string.linux_root_unavailable)
    AlpineInstallResult.BusyBoxUnavailable -> context.getString(R.string.linux_busybox_unavailable)
    AlpineInstallResult.EnvironmentUnavailable -> context.getString(R.string.linux_environment_unavailable)
    is AlpineInstallResult.Failed -> context.getString(R.string.linux_stage_failed, stage.displayName(context))
}

private fun PackageProfileInstallResult.toMessage(context: Context, profileTitle: String): String =
    when (this) {
        PackageProfileInstallResult.AlreadyReady ->
            context.getString(R.string.linux_profile_already_ready, profileTitle)
        PackageProfileInstallResult.EnvironmentNotReady -> context.getString(R.string.linux_base_required)
        PackageProfileInstallResult.Installed ->
            context.getString(R.string.linux_profile_installed, profileTitle)
        is PackageProfileInstallResult.Failed -> context.getString(
            R.string.linux_profile_stage_failed,
            PackageProfileInstallProgress(stage).summary(context, profileTitle),
        )
    }

private fun ApkAnalysisInstallResult.toMessage(context: Context): String = when (this) {
    ApkAnalysisInstallResult.AlreadyReady -> context.getString(R.string.linux_apk_analysis_ready)
    ApkAnalysisInstallResult.EnvironmentNotReady -> context.getString(R.string.linux_base_required)
    is ApkAnalysisInstallResult.InsufficientSpace ->
        context.getString(
            R.string.linux_insufficient_space,
            requiredBytes.toReadableSize(context),
            availableBytes.toReadableSize(context),
        )
    ApkAnalysisInstallResult.Installed -> context.getString(R.string.linux_apk_analysis_installed)
    is ApkAnalysisInstallResult.Failed -> context.getString(R.string.linux_apk_stage_failed, stage.displayName(context))
}

private fun AlpineInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        AlpineInstallStage.CHECKING -> R.string.linux_stage_checking
        AlpineInstallStage.DOWNLOADING -> R.string.linux_stage_downloading
        AlpineInstallStage.EXTRACTING -> R.string.linux_stage_extracting
        AlpineInstallStage.INSTALLING_TOOLS -> R.string.linux_stage_installing_tools
        AlpineInstallStage.COMPLETE -> R.string.linux_stage_complete
    },
)

private fun ApkAnalysisInstallStage.displayName(context: Context): String = context.getString(
    when (this) {
        ApkAnalysisInstallStage.CHECKING -> R.string.linux_apk_stage_checking
        ApkAnalysisInstallStage.DOWNLOADING -> R.string.linux_apk_stage_downloading
        ApkAnalysisInstallStage.PREPARING -> R.string.linux_apk_stage_preparing
        ApkAnalysisInstallStage.INSTALLING_JAVA -> R.string.linux_apk_stage_installing_java
        ApkAnalysisInstallStage.ACTIVATING -> R.string.linux_apk_stage_activating
        ApkAnalysisInstallStage.VERIFYING -> R.string.linux_apk_stage_verifying
        ApkAnalysisInstallStage.COMPLETE -> R.string.linux_apk_stage_complete
    },
)
