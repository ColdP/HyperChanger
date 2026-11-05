// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.util.Collections

/** SecurityCenter 13.6.3: synchronize the selector capacity and the rail's edit slots. */
internal class SidebarIconCountHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val logTag: String,
) {
    private var installed = false
    private val comparisonScope = ThreadLocal<Boolean>()

    fun install(loader: ClassLoader, preferences: SharedPreferences) {
        if (installed) return
        val limit = readSidebarIconCountSettings(preferences).limit ?: return
        installed = true
        runCatching {
            val helper = loader.loadClass("com.miui.gamebooster.windowmanager.newbox.r0")
            val edit = helper.getDeclaredMethod("c", List::class.java, Int::class.javaPrimitiveType)
            val add = helper.getDeclaredMethod("d", List::class.java,
                loader.loadClass("com.miui.gamebooster.windowmanager.newbox.y"), loader.loadClass("a8.i"), Int::class.javaPrimitiveType)
            val viewModel = loader.loadClass("com.miui.dock.allapps.g0")
            val count = viewModel.getDeclaredMethod("J")
            val callers = listOf("x", "y", "z").map { viewModel.getDeclaredMethod(it, List::class.java) } +
                loader.loadClass("com.miui.dock.allapps.g0\$i").getDeclaredMethod("invokeSuspend", Any::class.java)
            val app = loader.loadClass("a8.c")
            val shortcut = loader.loadClass("c8.c")
            val getApp = app.getDeclaredMethod("g")
            val appKind = loader.loadClass("z7.c").getDeclaredField("e").apply { isAccessible = true }
            // Resolve the entire version-specific boundary before registering any hook.
            callers.forEach { method ->
                hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("sidebar:count:scope:${method.declaringClass.name}:${method.name}").intercept { chain ->
                        val previous = comparisonScope.get()
                        comparisonScope.set(true)
                        try { chain.proceed() } finally {
                            if (previous == null) comparisonScope.remove() else comparisonScope.set(previous)
                        }
                    }
            }
            hookDeoptimized(count).setExceptionMode(ExceptionMode.PROTECTIVE).setId("sidebar:count:selection-capacity").intercept { chain ->
                val result = chain.proceed()
                // All scoped callers use J() only for size()/16 predicates, as checked in smali.
                // Actual models, persistence, adapter counts and ordinary List.size stay intact.
                if (comparisonScope.get() == true && result is List<*>)
                    Collections.nCopies(sidebarNativeComparisonCount(result.size, limit), null)
                else result
            }
            hookDeoptimized(edit).setExceptionMode(ExceptionMode.PROTECTIVE).setId("sidebar:count:edit-slots").intercept { chain ->
                val source = chain.getArg(0) as? List<*>
                // Keep existing pins when lowering the limit. Saving an unchanged edit session
                // must not silently delete the user's previously saved applications.
                val existing = source?.count { item -> shortcut.isInstance(item) ||
                    app.isInstance(item) && appKind.getInt(getApp.invoke(item)) == 0 } ?: 0
                chain.proceed(arrayOf(chain.getArg(0), maxOf(limit, existing)))
            }
            hookDeoptimized(add).setExceptionMode(ExceptionMode.PROTECTIVE).setId("sidebar:count:add-slot").intercept { chain ->
                chain.proceed(arrayOf(chain.getArg(0), chain.getArg(1), chain.getArg(2), limit))
            }
        }.onFailure { HookModuleLogger.log(module, ModuleLog.WARN, logTag, "Could not install sidebar icon capacity", it) }
    }
}
