// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import io.github.libxposed.service.XposedService
import java.io.File
import java.lang.reflect.Proxy

private fun service(name: String, version: String, failName: Boolean = false): XposedService {
    val binderType = Class.forName("io.github.libxposed.service.IXposedService")
    val binder = Proxy.newProxyInstance(binderType.classLoader, arrayOf(binderType)) { _, method, _ ->
        when (method.name) {
            "getFrameworkName" -> if (failName) error("Disconnected") else name
            "getFrameworkVersion" -> version
            "getApiVersion" -> 102
            else -> error("Unexpected service call: ${method.name}")
        }
    }
    return XposedService::class.java.getDeclaredConstructor(binderType).apply { isAccessible = true }
        .newInstance(binder)
}

fun main(args: Array<String>) {
    check(readHookFrameworkInfo(null) == null)
    val vector = readHookFrameworkInfo(service(" Vector ", "2.2 (3114) eaffc816"))!!
    check(vector.name == "Vector" && vector.version == "2.2 (3114) eaffc816" && vector.api == 102)
    check(vector.framework == HookFramework.VECTOR)
    check(readHookFrameworkInfo(service("LSPosed", "1.10.2"))?.framework == HookFramework.LSPOSED)
    val partial = readHookFrameworkInfo(service("Vector", "2.2", failName = true))!!
    check(partial.name == null && partial.version == "2.2" && partial.api == 102 && partial.framework == null)
    val unknown = readHookFrameworkInfo(service("Other framework", "custom"))!!
    check(unknown.name == "Other framework" && unknown.version == "custom" && unknown.framework == null)
    check(readHookFrameworkInfo(service("  ", "  "))?.let { it.name == null && it.version == null } == true)
    check(identifyHookFramework("vector canary") == HookFramework.VECTOR)
    check(identifyHookFramework("VectorDrawable") == null)
    check(offlineHookFramework("VECTOR\r\nVECTOR\n") == HookFramework.VECTOR)
    check(offlineHookFramework("LSPOSED\n") == HookFramework.LSPOSED)
    check(offlineHookFramework("VECTOR\nLSPOSED\n") == null)
    check(offlineHookFramework("permission denied\n") == null)

    // Exercise the actual Android shell probe using file fixtures, including disabled/removal
    // markers. Only its root directory is substituted; the production shell body is unchanged.
    val bash = args[0]
    val output = File(args[1]).apply { mkdirs() }
    val modules = File(output, "modules").apply { mkdirs() }
    fun module(directory: String, properties: String, marker: String? = null): File =
        File(modules, directory).apply {
            mkdirs()
            File(this, "module.prop").writeText(properties)
            marker?.let { File(this, it).writeText("") }
        }
    module("unrelated", "id=other\nname=Something\n")
    val rootPath = modules.absolutePath.replace('\\', '/')
    val script = File(output, "probe.sh")
    script.writeText(activeHookFrameworkProbe.replace("/data/adb/modules", "'$rootPath'"))
    fun probe(): HookFramework? {
        val process = ProcessBuilder(bash, script.absolutePath).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { text }
        return offlineHookFramework(text)
    }
    check(probe() == null)
    val vectorModule = module("vector", "id=zygisk_vector\nname=Vector\nversion=2.2\n")
    check(probe() == HookFramework.VECTOR)
    File(vectorModule, "disable").writeText("")
    check(probe() == null)
    check(File(vectorModule, "disable").delete())
    File(vectorModule, "remove").writeText("")
    check(probe() == null)
    check(File(vectorModule, "remove").delete())
    val lsposedModule = module("lsposed", "id=zygisk_lsposed\nname=LSPosed\n")
    check(probe() == null) // Ambiguous offline installations must not choose a parasitic category.
    File(vectorModule, "disable").writeText("")
    check(probe() == HookFramework.LSPOSED)
    File(lsposedModule, "disable").writeText("")
    check(probe() == null)
    println("Framework identity, service failure, offline ambiguity, and shell probe checks passed.")
}
