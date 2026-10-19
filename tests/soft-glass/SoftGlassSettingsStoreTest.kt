// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import java.lang.reflect.Proxy

const val REMOTE_PREFERENCE_GROUP = "hyper_system_ui_hook"

/** Exercise separate page stores and a stale remote snapshot using the real store. */
internal fun testPresetPersistence() {
    testControlCenterRadius()
    testVolumePanelLayout()
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


private fun testControlCenterRadius() {
    val local = memoryPreferences(mutableMapOf("top_buttons_radius_enabled" to true, "top_buttons_radius" to 60f))
    val remote = memoryPreferences(mutableMapOf())
    val defaults = ControlCenterRadiusPreferences.read(local)
    check(defaults == ControlCenterRadiusSettings()) { "Legacy absolute radii must not become new offsets" }
    check(adjustedControlCenterRadius(72f, 80, 3f, false) == 72f)
    check(adjustedControlCenterRadius(72f, 0, 3f, true) == 72f)
    check(adjustedControlCenterRadius(72f, -40, 3f, true) == 0f)
    check(adjustedControlCenterRadius(72f, 80, 3f, true) == 312f)
    val settings = ControlCenterRadiusSettings(true, -40, -20, 10, 20, 40, 80)
    ControlCenterRadiusPreferences.write(local, settings)
    check(ControlCenterRadiusPreferences.sync(local, remote) == settings)
    check(ControlCenterRadiusPreferences.read(remote) == settings)
    val fresh = memoryPreferences(mutableMapOf())
    check(ControlCenterRadiusPreferences.sync(fresh, remote) == settings)
    ControlCenterRadiusPreferences.write(local, settings.copy(topButtons = -99, expandedPanel = 999))
    check(ControlCenterRadiusPreferences.read(local).topButtons == -40)
    check(ControlCenterRadiusPreferences.read(local).expandedPanel == 80)
    val baseline = ControlCenterRadiusBaseline()
    check(baseline.resolve(72f, 10, 3f, true) == 102f)
    repeat(100) { check(baseline.resolve(102f, 10, 3f, true) == 102f) }
    check(baseline.resolve(102f, 20, 3f, true) == 132f)
    check(baseline.resolve(132f, 20, 3f, false) == 72f)
    check(baseline.resolve(90f, -10, 3f, true) == 60f) { "A new system baseline must replace the old baseline" }
    check(baseline.resolve(60f, 0, 3f, true) == 90f)
    val clipped = ControlCenterRadiusBaseline()
    check(clipped.resolve(72f, -40, 3f, true) == 0f)
    check(clipped.resolve(0f, 0, 3f, true) == 72f) { "Reset from square corners must restore the unclamped native radius" }
}

private fun testVolumePanelLayout() {
    val disk = mutableMapOf<String, Any>("volume_panel_corner_radius" to 60f)
    val local = memoryPreferences(disk)
    val remote = memoryPreferences(mutableMapOf())
    val defaults = VolumePanelLayoutPreferences.read(local)
    check(defaults == VolumePanelLayoutSettings()) { "Retired absolute radius must not become an offset" }
    val custom = VolumePanelLayoutSettings(true, -30, 96, true, true, true, -40, true)
    VolumePanelLayoutPreferences.write(local, custom)
    check(VolumePanelLayoutPreferences.sync(local, remote) == custom)
    check(VolumePanelLayoutPreferences.read(memoryPreferences(disk)) == custom)
    val fresh = memoryPreferences(mutableMapOf())
    check(VolumePanelLayoutPreferences.sync(fresh, remote) == custom)
    check(!defaults.longPressExpansionEnabled)
    check(custom.longPressExpansionEnabled)
    check(!custom.copy(hideExpand = false).longPressExpansionEnabled)
    check(!custom.copy(longPressExpand = false).longPressExpansionEnabled)
    val collapsedWidths = listOf("miui_volume_column_width", "flip_tiny_miui_volume_column_width", "o3_miui_ringer_btn_width")
    collapsedWidths.forEach { resource ->
        check(volumePanelLayoutDimensionDp(resource, 58f, custom) == 28f)
        check(volumePanelLayoutDimensionDp(resource, 58f, custom.copy(width = 60)) == 118f)
        check(volumePanelLayoutDimensionDp(resource, 58f, custom.copy(width = 0)) == null)
        check(volumePanelLayoutDimensionDp(resource, 58f, custom.copy(sizeEnabled = false)) == null)
    }
    listOf("miui_volume_column_height", "flip_tiny_miui_volume_column_height").forEach { resource ->
        check(volumePanelLayoutDimensionDp(resource, 160f, custom) == 256f)
        check(volumePanelLayoutDimensionDp(resource, 160f, custom.copy(height = -64)) == 96f)
        check(volumePanelLayoutDimensionDp(resource, 40f, custom.copy(height = -64)) == 1f)
    }
    val stockOnly = listOf("o3_miui_volume_expend_width", "o3_miui_volume_notify_width", "o3_miui_volume_expend_height",
        "o3_miui_ringer_btn_width_expended", "o3_miui_volume_cc_notify_width", "o3_miui_volume_cc_width", "o3_miui_volume_cc_height", "o3_miui_ringer_btn_width_cc")
    // Expand/collapse transitions must always return to the system's original expanded
    // dimensions, including the single-notification and Control Center variants.
    repeat(50) {
        check(volumePanelLayoutDimensionDp("miui_volume_column_width", 58f, custom) == 28f)
        stockOnly.forEach { resource -> check(volumePanelLayoutDimensionDp(resource, 160f, custom) == null) { resource } }
    }
    check(volumePanelLayoutDimensionDp("o3_miui_volume_radius", 24f, custom) == 0f)
    check(volumePanelLayoutDimensionDp("o3_miui_volume_radius", 24f, custom.copy(corner = 80)) == 104f)
    check(volumePanelLayoutDimensionDp("o3_miui_volume_radius", 24f, custom.copy(corner = 0)) == null)
    check(volumePanelLayoutDimensionDp("o3_miui_volume_radius", 24f, custom.copy(cornerEnabled = false)) == null)
    // Radius remains independent of size and material controls.
    check(volumePanelLayoutDimensionDp("o3_miui_volume_radius", 24f, custom.copy(sizeEnabled = false)) == 0f)
    check(volumePanelLayoutDimensionDp("o3_miui_cc_volume_radius", 24f, custom) == null)
    VolumePanelLayoutPreferences.write(local, custom.copy(width = 999, height = -999, corner = 999))
    check(VolumePanelLayoutPreferences.read(local).let { it.width == 60 && it.height == -64 && it.corner == 80 })
    check(volumePanelLayoutDimensionDp("unrelated", 24f, custom) == null)
}
