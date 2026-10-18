// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import java.lang.reflect.Proxy

const val REMOTE_PREFERENCE_GROUP = "hyper_system_ui_hook"

/** Exercise separate page stores and a stale remote snapshot using the real store. */
internal fun testPresetPersistence() {
    val disk = mutableMapOf<String, Any>()
    val local = memoryPreferences(disk)
    val remoteDisk = mutableMapOf<String, Any>()
    val remote = memoryPreferences(remoteDisk)
    val oldPage = SoftGlassSettingsStore(local)
    oldPage.syncPreferences(remote)
    val editorPage = SoftGlassSettingsStore(local)
    val saved = builtInSoftGlassPresets().first().copy(id = "saved", name = "我的预设", builtIn = false)
    editorPage.savePreset(null, saved)
    val imported = SoftGlassPresetCodec.decode(SoftGlassPresetCodec.encode(saved.copy(name = "Imported")))
    editorPage.savePreset(null, imported)
    editorPage.update(null, imported::applyTo)
    oldPage.update(null) { it.copy(enabled = true) }
    fun reopen(): SoftGlassSettingsStore = SoftGlassSettingsStore(memoryPreferences(disk.toMutableMap()))
    check(reopen().presets.map { it.id } == listOf(saved.id, imported.id)) {
        "An older parameter page must not overwrite newly saved/imported presets"
    }
    val returnPage = SoftGlassSettingsStore(local)
    returnPage.syncPreferences(remote)
    check(returnPage.presets.map { it.id } == listOf(saved.id, imported.id)) {
        "Returning to presets must not replace the library with stale remote data"
    }
    check(SoftGlassSettingsStore(remote).presets.size == 2)
    editorPage.update(null) { it.copy(enabled = true) }
    editorPage.update(null) { it.resetParameters() }
    val reset = reopen().settings
    check(reset.enabled && reset.adjustments["darker"] == -100) { "reset enabled=${reset.enabled} darker=${reset.adjustments["darker"]}" }
    check(!reset.copy(enabled = false).resetParameters().enabled)
    check(reset.adjustments.filterKeys { it != "darker" }.values.all { it == 0 })
    check(reset.refractionBlur == 100 && reset.backgroundBlur == 100 && reset.backgroundScale == 100 && reset.backgroundOpacity == 100)
    check(reopen().presets.size == 2) { "Resetting material parameters must retain custom presets" }
    oldPage.deletePreset(null, saved.id)
    check(reopen().presets.single().id == imported.id)
    editorPage.deletePreset(null, imported.id)
    returnPage.syncPreferences(remote)
    check(reopen().presets.isEmpty()) { "An explicitly empty library must not resurrect deleted presets" }
    val restored = SoftGlassSettingsStore(memoryPreferences(mutableMapOf()))
    remoteDisk.clear()
    remoteDisk["soft_glass_custom_presets_v1"] = SoftGlassPresetCodec.encodeLibrary(listOf(saved))
    restored.syncPreferences(remote)
    check(restored.presets.single().id == saved.id) { "Recover remote presets on a fresh local store" }
}

private fun memoryPreferences(values: MutableMap<String, Any>): SharedPreferences {
    return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "contains" -> values.containsKey(args!![0])
            "getAll" -> values.toMap()
            "getBoolean", "getInt", "getFloat", "getString" -> values[args!![0]] ?: args[1]
            "edit" -> {
                val changes = mutableMapOf<String, Any?>()
                Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, setter, input ->
                    when (setter.name) {
                        "putBoolean", "putInt", "putFloat", "putString" -> { changes[input!![0] as String] = input[1]; proxy }
                        "remove" -> { changes[input!![0] as String] = null; proxy }
                        "apply", "commit" -> {
                            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                            if (setter.name == "commit") true else null
                        }
                        else -> error("Unexpected editor call: ${setter.name}")
                    }
                }
            }
            else -> error("Unexpected preferences call: ${method.name}")
        }
    } as SharedPreferences
}

