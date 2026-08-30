package io.github.mangi.eta

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.ModuleLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.hook.google.GoogleAppHooks
import io.github.mangi.eta.hook.google.GoogleEligibilityHooks
import io.github.mangi.eta.hook.google.GoogleTargetHooks
import io.github.mangi.eta.hook.system.SystemServerHooks

class ModuleMain : XposedModule() {

    private val logger = ModuleLogger(this)
    private var currentProcessName: String? = null
    // 当前未启用热重载；保留句柄用于未来显式 unhook/replace，而不是维持 Hook 生效。
    private val hookHandles = mutableListOf<HookHandle>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        currentProcessName = param.processName
        if (!shouldKeepLifecycleCallbacks(param)) {
            detach()
            return
        }
        // 缓存框架提供的只读 remote preferences，供所有 hook 拦截回调即时读取。
        // getRemotePreferences 是 XposedInterface 的方法，XposedModule 继承自其 Wrapper 可直接调用。
        // 调用失败时保留历史默认行为，但必须留下可诊断日志，不能伪装成配置同步正常。
        val remotePreferences = try {
            getRemotePreferences(Prefs.GROUP)
        } catch (exception: Exception) {
            logger.warn("RemotePreferences 不可用，将使用兼容默认值: ${exception.safeLogType()}")
            null
        }
        Prefs.attachRemote(remotePreferences)
        logger.debug {
            "模块已加载 process=${param.processName}, framework=$frameworkName($frameworkVersionCode), api=$apiVersion"
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        recordInstallation(SystemServerHooks.install(this, logger, param.classLoader))
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            ModuleConfig.GOOGLE_APP_PACKAGE -> {
                if (isCurrentPackageProcess(ModuleConfig.GOOGLE_APP_PACKAGE)) {
                    recordInstallation(
                        HookInstallation.combine(
                            group = "GoogleApp",
                            installations = listOf(
                                GoogleEligibilityHooks.install(this, logger, param.classLoader),
                                GoogleAppHooks.install(this, logger, param.classLoader),
                                GoogleTargetHooks.install(this, logger, ModuleConfig.GOOGLE_APP_PACKAGE),
                            )
                        )
                    )
                }
            }

            in ModuleConfig.GOOGLE_TARGET_PACKAGES -> {
                if (isCurrentPackageProcess(param.packageName)) {
                    recordInstallation(GoogleTargetHooks.install(this, logger, param.packageName))
                }
            }
        }
    }

    private fun recordInstallation(installation: HookInstallation) {
        hookHandles += installation.handles
        logger.scoped(installation.report.group).info(installation.report.summary())
    }

    private fun isCurrentPackageProcess(packageName: String): Boolean {
        val processName = currentProcessName ?: return false
        return isPackageProcess(processName, packageName)
    }

    private fun shouldKeepLifecycleCallbacks(param: ModuleLoadedParam): Boolean {
        if (param.isSystemServer) return true
        val processName = param.processName
        return processName == ModuleConfig.SYSTEM_UI_PACKAGE ||
            ModuleConfig.isGoogleTargetProcess(processName)
    }

    private fun isPackageProcess(processName: String, packageName: String): Boolean =
        processName == packageName || processName.startsWith("$packageName:")
}
