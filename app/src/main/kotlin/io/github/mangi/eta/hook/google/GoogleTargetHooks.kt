package io.github.mangi.eta.hook.google

import android.app.Application
import io.github.libxposed.api.XposedModule
import io.github.mangi.eta.core.HookInstallation
import io.github.mangi.eta.core.HookRegistrar
import io.github.mangi.eta.core.HookSupport
import io.github.mangi.eta.core.ModuleLogger

/**
 * Stable entry hook for an app selected in LSPosed.
 *
 * Phone, Messages, Contacts and Calendar retain their supported Android
 * Provider/Intent integrations; this deliberately avoids private, version-
 * fragile Google implementation classes.
 */
internal object GoogleTargetHooks {
    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        packageName: String,
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "GoogleTarget")
        return hooks.install {
            val onCreate = HookSupport.findMethod(Application::class.java, "onCreate")
            if (onCreate == null) {
                hooks.missing(
                    id = "google-target.application-on-create",
                    description = "Application.onCreate",
                    detail = "Application.onCreate() unavailable",
                )
                return@install
            }
            hooks.intercept(
                id = "google-target.application-on-create",
                executable = onCreate,
                description = "Application.onCreate($packageName)",
            ) { chain ->
                val result = chain.proceed()
                val application = chain.getThisObject() as? Application
                if (application?.packageName == packageName) {
                    hooks.logger.info("Google target ready: $packageName")
                }
                result
            }
        }
    }
}
