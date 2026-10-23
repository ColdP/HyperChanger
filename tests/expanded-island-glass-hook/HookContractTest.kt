// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import miui.systemui.dynamicisland.window.DynamicIslandWindowView

private const val KEY_EXPANDED_ISLAND_BACKGROUND_ENABLED = "expanded_island_background_enabled"
private const val MI_BACKGROUND_STYLE_CLASS = "btm.m.os4.systemuihook.NativeStyle"
private const val TAG = "test"
private class Prefs(val enabled: Boolean, val blur: Int) : SharedPreferences {
    override fun getBoolean(key: String, default: Boolean) = enabled
}
private fun resolveExpandedIslandGlass(p: SharedPreferences) = SoftGlassSettings(enabled = true, refractionBlur = (p as Prefs).blur)

object NativeStyle {
    var calls = 0
    var fail = false
    var arguments: List<Any?> = emptyList()
    @JvmStatic fun setBackgroundBlurRadius(view: View, backdrop: Int, small: Int, large: Int): String {
        calls++
        arguments = listOf(view, backdrop, small, large)
        if (fail) error("native failure")
        return "native result"
    }
}

private class Host {
    val expandedIslandMaterialApplying = ThreadLocal<Boolean>()
    lateinit var callback: XposedInterface.Hooker
    lateinit var executable: Method
    fun hookDeoptimized(method: Method): Builder {
        check(Modifier.isStatic(method.modifiers))
        executable = method
        return Builder()
    }
    inner class Builder {
        fun setExceptionMode(mode: ExceptionMode) = this
        fun setId(id: String) = this
        fun intercept(hooker: XposedInterface.Hooker) { callback = hooker }
    }
    fun log(level: Int, tag: String, message: String, error: Throwable? = null) {
        if (error != null) throw AssertionError(message, error)
    }
    // PRODUCTION_INSTALLER
    fun install(p: SharedPreferences) = installDynamicIslandGlassBlurHook(javaClass.classLoader, p)
}

private class StaticCall(val executable: Method, val original: Array<Any?>) {
    var replaced = false
    val chain = Proxy.newProxyInstance(XposedInterface.Chain::class.java.classLoader,
        arrayOf(XposedInterface.Chain::class.java)) { _, method, args ->
        when (method.name) {
            "getExecutable" -> executable
            "getThisObject" -> null // A static method has no instance receiver.
            "getArgs" -> original.toList()
            "getArg" -> original[args!![0] as Int]
            "proceedWith" -> {
                if (args?.get(0) == null) throw NullPointerException("thisObject cannot be null")
                error("A static hook must not replace its receiver")
            }
            "proceed" -> {
                val values = if (method.parameterCount == 0) original else {
                    replaced = true
                    @Suppress("UNCHECKED_CAST")
                    (args!![0] as Array<Any?>)
                }
                try { executable.invoke(null, *values) }
                catch (error: java.lang.reflect.InvocationTargetException) { throw error.cause!! }
            }
            else -> error("Unexpected Chain method: ${method.name}")
        }
    } as XposedInterface.Chain
}

fun main() {
    val host = Host()
    val window = DynamicIslandWindowView()
    fun run(enabled: Boolean, percent: Int, view: View, small: Int, large: Int,
            expectedSmall: Int, expectedLarge: Int, expectedReplacement: Boolean) {
        host.install(Prefs(enabled, percent))
        val call = StaticCall(host.executable, arrayOf(view, 87, small, large))
        // Reproduce the API contract behind the device's exact exception.
        val before = runCatching {
            XposedInterface.Chain::class.java.getMethod("proceedWith", Any::class.java, Array<Any>::class.java)
                .invoke(call.chain, null, call.original)
        }.exceptionOrNull().let { (it as? java.lang.reflect.InvocationTargetException)?.cause }
        check(before is NullPointerException && before.message == "thisObject cannot be null")
        NativeStyle.calls = 0
        check(host.callback.intercept(call.chain) == "native result")
        check(NativeStyle.calls == 1)
        check(NativeStyle.arguments == listOf(view, 87, expectedSmall, expectedLarge))
        check(call.replaced == expectedReplacement)
        check(host.expandedIslandMaterialApplying.get() == null)
    }
    run(true, 50, window, 50, 500, 25, 250, true)
    run(true, 0, window, 50, 500, 0, 0, true)
    run(true, 150, window, 0, 0, 0, 0, true)
    run(true, 100, window, 50, 500, 50, 500, true)
    run(false, 50, window, 50, 500, 50, 500, false)
    run(true, 50, View(), 50, 500, 50, 500, false)
    host.install(Prefs(true, 50))
    NativeStyle.calls = 0
    NativeStyle.fail = true
    val failure = runCatching { host.callback.intercept(StaticCall(host.executable, arrayOf(window, 87, 50, 500)).chain) }.exceptionOrNull()
    check(failure?.message == "native failure")
    check(NativeStyle.calls == 1 && host.expandedIslandMaterialApplying.get() == null)
    println("Expanded island production hook passed: static null receiver, arguments/results, once-only original call, disabled/foreign bypass, zero samples and exception cleanup")
}
