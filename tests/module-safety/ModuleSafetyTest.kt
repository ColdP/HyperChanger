// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import java.util.zip.ZipFile

private fun prefs(values: MutableMap<String, Any>): SharedPreferences = Proxy.newProxyInstance(
    SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
) { _, method, args ->
    when (method.name) {
        "contains" -> values.containsKey(args!![0])
        "getBoolean", "getInt", "getLong", "getString", "getStringSet" -> values[args!![0]] ?: args[1]
        "getAll" -> values.toMap()
        else -> error("Unexpected preference operation ${method.name}")
    }
} as SharedPreferences

fun main(args: Array<String>) {
    val packages = listOf("system", "com.android.systemui", "miui.systemui.plugin", "com.miui.home", "com.android.settings",
        "com.xiaomi.misettings", "com.miui.aod", "com.miui.miwallpaper", "com.android.camera", "com.miui.gallery", "com.android.updater")
    check(packages.all { ModuleSafetySettings().allowsPackage(it) })
    check(packages.none { ModuleSafetySettings(enabled = false).allowsPackage(it) })
    check(SafetySection.entries.none { ModuleSafetySettings(enabled = false).allows(it) })
    val uiSafe = ModuleSafetySettings(systemUiSafe = true)
    check(!uiSafe.allowsPackage("com.android.systemui") && !uiSafe.allowsPackage("miui.systemui.plugin"))
    check(uiSafe.allowsPackage("system") && uiSafe.allowsPackage("com.miui.home"))
    // Every combination: selecting one feature must not silently disable a sibling.
    repeat(1 shl SafetySection.entries.size) { mask ->
        val selected = SafetySection.entries.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
        val settings = ModuleSafetySettings(partialSafe = true, sections = selected)
        SafetySection.entries.forEach { check(settings.allows(it) == (it !in selected)) }
        check(ModuleSafetySettings(sections = selected).allows(SafetySection.GESTURE_HANDLE))
    }
    check(SafetySection.entries.indexOf(SafetySection.DESKTOP) < SafetySection.entries.indexOf(SafetySection.STATUS_BAR))
    check(SafetySection.GESTURE_HANDLE != SafetySection.NAVIGATION)
    val values = mutableMapOf<String, Any>()
    val p = prefs(values)
    check(readModuleSafety(p) == ModuleSafetySettings())
    values[KEY_SAFE_SECTIONS] = setOf("DESKTOP", "obsolete", "GESTURE_HANDLE")
    values[KEY_PARTIAL_SAFE] = true
    values[KEY_LOG_RETENTION] = 999
    check(readModuleSafety(p).sections == setOf(SafetySection.DESKTOP, SafetySection.GESTURE_HANDLE))
    check(readModuleSafety(p).retentionDays == 7)
    check(moduleSafetyNativeFlags(p) == 1)
    values[KEY_LOG_ENABLED] = false; values[KEY_LOG_DETAIL] = true
    check(moduleSafetyNativeFlags(p) == 7)
    values[KEY_PARTIAL_SAFE] = false
    check(moduleSafetyNativeFlags(p) == 6)
    values[KEY_MODULE_ENABLED] = false
    check(moduleSafetyNativeFlags(p) == 7)

    val raw = """
        ignored tail fragment
        [ 2026-10-24T12:00:00.123 1000: 42: 42 I/LSPosedFramework ] Framework ready
        [ 2026-10-24T12:00:01.123 1000: 42: 42 E/HyperChanger ] [HyperChanger][CRASH] FATAL EXCEPTION: main
        java.lang.IllegalStateException: 示例
            at test.Example.run(Example.kt:1)
        [ 2026-10-24T12:00:02.123 1000: 42: 42 D/HyperChanger ] debug line
        ${ModuleLogParser.BOUNDARY}
        ignored second file fragment
        2026-10-24 12:00:03.123  1234  5678 W HyperChangerFolder: native warning
        [ 2026-10-24T12:00:04.123 1000: 42: 42 V/LSPosedFramework ] verbose line
    """.trimIndent()
    val entries = ModuleLogParser.parse(raw)
    check(entries.size == 5)
    check(entries[0].source == 1 && entries[0].priority == 4)
    check(entries[1].source == 0 && entries[1].priority == 7 && entries[1].tag == "CRASH")
    check("at test.Example" in entries[1].text && "示例" in entries[1].text)
    check(entries[2].priority == 3 && "fragment" !in entries[2].text)
    check(entries[3].source == 0 && entries[3].priority == 5)
    check(entries[4].priority == 2)
    check(ModuleLogParser.parse(raw).map { it.id } == entries.map { it.id })
    check(entries.filter { ModuleLogParser.matches(it, 5, true) } == listOf(entries[1]))
    check(entries.filter { ModuleLogParser.matches(it, 5, false) }.size == 2)
    val long = ModuleLogParser.parse("[ 2026-10-24T12:00:00.123 I/HyperChanger ] " + "x".repeat(50000))
    check(long.single().text.length == 32768)
    val now = 200L * 86400000
    listOf(3, 7, 14, 30, 120).forEach { days ->
        val cutoff = ModuleLogRetention.cutoff(now, days)
        check(ModuleLogRetention.accepts(cutoff, cutoff, 0))
        check(!ModuleLogRetention.accepts(cutoff - 1, cutoff, 0))
        check(!ModuleLogRetention.accepts(now, cutoff, now))
        check(ModuleLogRetention.accepts(now + 1, cutoff, now))
    }
    val dir = File(args[0]).apply { mkdirs() }
    val archive = File(dir, "roundtrip.zip")
    ModuleLogArchive.write(archive, "device=test") { source -> entries.filter { it.source == source }.asReversed() }
    ZipFile(archive).use { zip ->
        check(zip.entries().asSequence().map { it.name }.toSet() == setOf("environment.txt", "module.log", "lsposed.log"))
        val module = zip.getInputStream(zip.getEntry("module.log")).reader().readText()
        val framework = zip.getInputStream(zip.getEntry("lsposed.log")).reader().readText()
        check("示例" in module && "Framework ready" !in module)
        check("Framework ready" in framework && "native warning" !in framework)
    }
    val broken = File(dir, "broken.zip")
    check(runCatching { ModuleLogArchive.write(broken, "test") { error("read failure") } }.isFailure)
    check(!broken.exists())

    // The app-visible logger/runtime must load with NO Xposed API on the classpath.
    Class.forName("btm.m.os4.systemuihook.ModuleLogger").declaredMethods
    Class.forName("btm.m.os4.systemuihook.ModuleSafetyRuntime").declaredMethods
    values.clear()
    val output = mutableListOf<String>()
    ModuleLogger.attach(p) { priority, tag, text, error -> output += "$priority/$tag $text ${error?.message}" }
    ModuleLogger.emit(3, "test", "hidden debug")
    ModuleLogger.emit(4, "test", "info")
    check(output.size == 1 && "[HyperChanger][test] info" in output.single())
    values[KEY_LOG_DETAIL] = true
    ModuleLogger.emit(2, "test", "verbose")
    check(output.size == 2)
    values[KEY_LOG_ENABLED] = false
    ModuleLogger.emit(6, "test", "hidden error")
    check(output.size == 2)
    values[KEY_PARTIAL_SAFE] = true; values[KEY_SAFE_SECTIONS] = setOf("GESTURE_HANDLE")
    check(ModuleSafetyRuntime.initialize(p, "com.android.systemui"))
    check(!ModuleSafetyRuntime.allows(SafetySection.GESTURE_HANDLE) && ModuleSafetyRuntime.allows(SafetySection.NAVIGATION))
    values[KEY_SAFE_SECTIONS] = emptySet<String>()
    check(ModuleSafetyRuntime.initialize(p, "miui.systemui.plugin"))
    check(!ModuleSafetyRuntime.allows(SafetySection.GESTURE_HANDLE)) // frozen until restart
    println("Passed safety combinations, independent gesture policy, preference defaults, native flags, multiline/crash/native parsing, severity filters, retention/clear boundaries, ZIP roundtrip/failure cleanup, live logger levels, and app loading without Xposed API")
}
