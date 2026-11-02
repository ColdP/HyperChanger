// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.htmlviewer

import android.content.SharedPreferences
import btm.m.os4.systemuihook.HookModuleLogger
import btm.m.os4.systemuihook.ModuleLog as Log
import btm.m.os4.systemuihook.cloudcontrol.KEY_HTML_VIEWER_DISABLE_CLOUD_CONTROL
import btm.m.os4.systemuihook.cloudcontrol.isCloudControlEnabled
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule

/**
 * Ports HyperCeiler's HTML viewer switch: the viewer's job worker keeps pulling and writing
 * cloud data even with cloud services disabled, so its sync entry point is skipped.
 * The target class lives in the HTML viewer APK, not in the Settings package of the same name.
 * Like environment detection, the per-application switch only takes effect while the shared
 * cloud control master switch is on.
 */
internal class HtmlViewerHooks(private val module: XposedModule) {
    private var installed = false

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        if (installed) return
        if (!preferences.isCloudControlEnabled()) return
        if (!preferences.getBoolean(KEY_HTML_VIEWER_DISABLE_CLOUD_CONTROL, false)) return
        installed = true
        runCatching {
            val method = classLoader.loadClass(CLOUD_JOB_CLASS).getDeclaredMethod(CLOUD_SYNC_METHOD)
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:htmlviewer:disable-cloud-control")
                .intercept { null }
            HookModuleLogger.log(module, Log.INFO, TAG, "Installed HTML viewer cloud control hook")
        }.onFailure {
            HookModuleLogger.log(module, Log.WARN, TAG, "Could not install HTML viewer cloud control hook", it)
        }
    }

    private companion object {
        const val TAG = "HyperChangerHtmlViewer"
        const val CLOUD_JOB_CLASS = "com.android.settings.cloud.JobTask"
        const val CLOUD_SYNC_METHOD = "updateCloudAllData"
    }
}
