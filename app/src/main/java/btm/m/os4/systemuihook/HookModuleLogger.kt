// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface

/** Hook-only adapter. The app logger must load without the compile-only Xposed API. */
object HookModuleLogger {
    @JvmStatic
    fun attach(api: XposedInterface, prefs: SharedPreferences) {
        ModuleLogger.attach(prefs) { priority, tag, message, error -> api.log(priority, tag, message, error) }
    }

    @JvmStatic @JvmOverloads
    fun log(api: XposedInterface, priority: Int, tag: String, message: String, error: Throwable? = null) {
        // The process sink is attached by its lifecycle callback. The explicit API
        // parameter preserves call-site ownership without exposing API types to the app.
        ModuleLogger.emit(priority, tag, message, error)
    }
}
