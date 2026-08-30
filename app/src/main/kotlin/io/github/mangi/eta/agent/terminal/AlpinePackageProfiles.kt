package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal enum class PackageProfileInstallStage {
    CHECKING,
    INSTALLING,
    VERIFYING,
    COMPLETE,
}

internal data class PackageProfileInstallProgress(
    val stage: PackageProfileInstallStage,
)

internal sealed interface PackageProfileInstallResult {
    data object AlreadyReady : PackageProfileInstallResult
    data object EnvironmentNotReady : PackageProfileInstallResult
    data object Installed : PackageProfileInstallResult
    data class Failed(val stage: PackageProfileInstallStage) : PackageProfileInstallResult
}

/** Optional tool profile composed of Alpine packages and an idempotent setup script. */
internal data class AlpinePackageProfile(
    val id: String,
    val markerName: String,
    val revision: Int,
    val packages: List<String>,
    val verifyCommands: List<String>,
    val setupScript: String? = null,
    val legacyBinaries: List<String> = emptyList(),
)

internal object AlpinePackageProfiles {
    val PYTHON = AlpinePackageProfile(
        id = "python",
        markerName = AlpineEnvironmentPaths.PYTHON_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.PYTHON_TOOLS_REVISION,
        packages = listOf("pipx", "py3-pip", "py3-virtualenv", "python3", "ruff", "uv"),
        verifyCommands = listOf("python3 --version", "uv --version"),
        setupScript = "ln -sf /usr/bin/python3 /usr/local/bin/python",
        legacyBinaries = listOf("usr/bin/python3", "usr/bin/uv"),
    )

    val NODE = AlpinePackageProfile(
        id = "node",
        markerName = AlpineEnvironmentPaths.NODE_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.NODE_TOOLS_REVISION,
        packages = listOf("nodejs", "npm"),
        verifyCommands = listOf("node --version", "npm --version"),
    )

    val CODEX = AlpinePackageProfile(
        id = "codex",
        markerName = AlpineEnvironmentPaths.CODEX_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.CODEX_TOOLS_REVISION,
        packages = listOf("nodejs", "npm"),
        verifyCommands = listOf("node --version", "npm --version", "codex --version"),
        setupScript = """
            npm install --global @openai/codex
            mkdir -p /root/.codex
        """.trimIndent(),
    )

    val SSH = AlpinePackageProfile(
        id = "ssh",
        markerName = AlpineEnvironmentPaths.SSH_TOOLS_MARKER,
        revision = AlpineEnvironmentPaths.SSH_TOOLS_REVISION,
        packages = listOf("openssh"),
        verifyCommands = listOf("command -v sshd", "command -v ssh-keygen"),
        setupScript = "ssh-keygen -A >/dev/null 2>&1 || true",
    )

    val ALL = listOf(PYTHON, NODE, CODEX, SSH)
}

/** Installs one optional profile and writes the completion marker only after verification. */
internal class AlpinePackageProfileInstaller(
    private val context: Context,
    private val profile: AlpinePackageProfile,
) {
    fun isReady(): Boolean =
        AlpineEnvironmentPaths.packageProfileReady(
            AlpineEnvironmentPaths.rootfsDir(context).absolutePath,
            profile,
        )

    suspend fun install(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit = {},
    ): PackageProfileInstallResult {
        installMutex.lock()
        return try {
            installLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installLocked(
        onProgress: suspend (PackageProfileInstallProgress) -> Unit,
    ): PackageProfileInstallResult = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext PackageProfileInstallResult.AlreadyReady
        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.CHECKING))
        if (!AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath)) {
            return@withContext PackageProfileInstallResult.EnvironmentNotReady
        }

        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.INSTALLING))
        val installScript = buildString {
            append("apk add --no-cache ").append(profile.packages.joinToString(" "))
            profile.setupScript?.let { script -> append('\n').append(script) }
        }
        val installResult = InstallerShellRunner.run(
            command = installScript,
            timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Package profile action=install profile=${profile.id} " +
                "outcome=${if (installResult.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${installResult.exitCode} outputChars=${installResult.output.length}",
        )
        if (installResult.exitCode != 0) {
            return@withContext PackageProfileInstallResult.Failed(PackageProfileInstallStage.INSTALLING)
        }

        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.VERIFYING))
        if (!verifyAndMark(rootfs)) {
            return@withContext PackageProfileInstallResult.Failed(PackageProfileInstallStage.VERIFYING)
        }

        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.COMPLETE))
        PackageProfileInstallResult.Installed
    }

    private suspend fun verifyAndMark(rootfs: File): Boolean {
        val checks = profile.verifyCommands.mapIndexed { index, command ->
            "$command >/dev/null 2>&1 || exit ${81 + index}"
        }.joinToString("\n")
        val command = """
            rm -f /${profile.markerName}
            $checks
            cat > /${profile.markerName} <<'ETA_PROFILE_EOF'
            profile=${profile.revision}
            ETA_PROFILE_EOF
            chmod 0644 /${profile.markerName} || exit 95
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 60,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Package profile action=verify profile=${profile.id} " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 600L
        private val installMutex = Mutex()
    }
}
