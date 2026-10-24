// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import io.github.libxposed.api.XposedModule

object HookModuleSafety {
    @JvmStatic
    fun enter(module: XposedModule, packageName: String): Boolean {
        val prefs = module.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        HookModuleLogger.attach(module, prefs)
        return ModuleSafetyRuntime.initialize(prefs, packageName)
    }
}
