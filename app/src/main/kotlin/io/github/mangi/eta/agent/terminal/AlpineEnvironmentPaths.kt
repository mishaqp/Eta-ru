package io.github.mangi.eta.agent.terminal

import android.content.Context
import java.io.File

/** Eta-managed Linux tool environment paths. */
internal object AlpineEnvironmentPaths {
    const val READY_MARKER = ".eta-environment-ready"
    const val COMMON_TOOLS_MARKER = ".eta-common-tools-ready"
    const val APK_ANALYSIS_MARKER = ".eta-apk-analysis-ready"
    const val PYTHON_TOOLS_MARKER = ".eta-python-tools-ready"
    const val NODE_TOOLS_MARKER = ".eta-node-tools-ready"
    const val SSH_TOOLS_MARKER = ".eta-ssh-tools-ready"
    const val TOOLSET_REVISION = 3
    const val APK_ANALYSIS_REVISION = 1
    const val PYTHON_TOOLS_REVISION = 1
    const val NODE_TOOLS_REVISION = 2
    const val SSH_TOOLS_REVISION = 1

    fun environmentDir(context: Context): File = File(context.filesDir, "terminal/alpine")
    fun rootfsDir(context: Context): File = File(environmentDir(context), "rootfs")
    fun artifactDir(context: Context): File = File(context.cacheDir, "linux-installer/artifacts")
    fun profileStagingDir(context: Context, profile: String): File =
        File(context.cacheDir, "linux-installer/profiles/$profile.installing")

    fun rootfsReady(rootfsPath: String?): Boolean {
        if (rootfsPath.isNullOrBlank()) return false
        val rootfs = File(rootfsPath)
        return File(rootfs, READY_MARKER).isFile && File(rootfs, "bin/busybox").isFile
    }

    fun commonToolsReady(rootfsPath: String?): Boolean {
        if (!rootfsReady(rootfsPath)) return false
        val marker = File(rootfsPath, COMMON_TOOLS_MARKER)
        if (!marker.isFile) return false
        return runCatching {
            marker.useLines { lines -> lines.any { it.trim() == "toolset=$TOOLSET_REVISION" } }
        }.getOrDefault(false)
    }

    fun packageProfileReady(rootfsPath: String?, profile: AlpinePackageProfile): Boolean {
        if (!commonToolsReady(rootfsPath)) return false
        val rootfs = File(rootfsPath ?: return false)
        val marker = File(rootfs, profile.markerName)
        val markerReady = marker.isFile && runCatching {
            marker.useLines { lines ->
                lines.any { line -> line.trim() == "profile=${profile.revision}" }
            }
        }.getOrDefault(false)
        if (markerReady) return true
        return profile.legacyBinaries.isNotEmpty() &&
            profile.legacyBinaries.all { path -> File(rootfs, path).isFile }
    }

    fun apkAnalysisReady(rootfsPath: String?): Boolean {
        if (!commonToolsReady(rootfsPath)) return false
        val rootfs = File(rootfsPath ?: return false)
        val marker = File(rootfs, APK_ANALYSIS_MARKER)
        if (!marker.isFile) return false
        val current = File(rootfs, "opt/eta/apk-analysis/current")
        val expectedFiles = listOf(
            "bin/java",
            "jadx/bin/jadx",
            "bin/apktool",
            "bin/smali",
            "bin/baksmali",
        )
        if (expectedFiles.any { relativePath -> !File(current, relativePath).isFile }) return false
        return runCatching {
            marker.useLines { lines ->
                lines.any { line -> line.trim() == "profile=$APK_ANALYSIS_REVISION" }
            }
        }.getOrDefault(false)
    }
}
