// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

fun main() {
    testControlCenterTopButtons()
    println("Control center top button settings regression checks passed")
}

internal fun testControlCenterTopButtons() {
    val disk = mutableMapOf<String, Any>(
        "add_control_center_top_buttons" to true,
        "show_control_center_top_buttons_in_landscape" to true,
        "control_center_top_buttons_background_mode" to 3,
        "control_center_top_buttons_icon_scale" to 1.25f,
        "control_center_top_buttons_soft_glass_background_radius" to 18f,
        "control_center_top_buttons_soft_glass_opacity" to 0,
        "control_center_top_buttons_soft_glass_luminance" to .4f,
        "soft_glass_parameters_v3_enabled" to false,
        "soft_glass_parameters_v3_refraction" to 80,
        "soft_glass_parameters_v3_blur_percent" to 175,
    )
    val local = memoryPreferences(disk)
    val store = ControlCenterTopButtonsGlassSettingsStore(local)
    check(store.settings.let { it.enabled && it.showInLandscape && it.backgroundMode == 3 &&
        it.iconScale == 1.25f && it.softRadius == 18f }) { "Retain existing button settings" }
    check(store.settings.glass.backgroundOpacity == 100 && store.settings.glass.adjustments["luminance"] == 0) {
        "Obsolete absolute glass values must not enter the new parameter model"
    }
    val custom = store.settings.glass.copy(refractionBlur = 160, backgroundBlur = 70,
        backgroundScale = 80, backgroundOpacity = 145,
        adjustments = softGlassParameters.associate { it.key to if (it.key == "darker") -100 else 35 })
    store.update(null) { it.copy(enabled = false, showInLandscape = false, iconScale = .8f,
        pureRadius = 16f, advancedColor = 123, advancedBlur = 20, glass = custom) }
    check(resolveControlCenterTopButtonsGlass(local) == custom.copy(enabled = true))
    store.update(null) { it.copy(useGlobal = true) }
    val resolved = resolveControlCenterTopButtonsGlass(local)
    check(resolved.enabled && resolved.adjustments["refraction"] == 80 && resolved.backgroundBlur == 175) {
        "Follow global values independently of the global master switch"
    }
    val remote = memoryPreferences(mutableMapOf("add_control_center_top_buttons" to true,
        CONTROL_CENTER_TOP_BUTTONS_SETTINGS_PREFIX + "enabled" to true,
        CONTROL_CENTER_TOP_BUTTONS_GLASS_PREFIX + "refraction_blur" to 10))
    store.syncPreferences(remote)
    val fresh = ControlCenterTopButtonsGlassSettingsStore(memoryPreferences(mutableMapOf()))
    fresh.syncPreferences(remote)
    check(fresh.settings == store.settings && fresh.settings.glass == custom && !fresh.settings.enabled) {
        "Recover all button and glass keys, including floats; saved local values override stale remote values"
    }
    fresh.update(null) { it.copy(useGlobal = false) }
    check(fresh.settings.glass == custom) { "Switching back retains custom parameters" }
    check(readSoftGlassSettings(local).adjustments["refraction"] == 80) { "Never overwrite global settings" }
    fresh.update(null) { it.copy(glass = it.glass.copy(refractionBlur = 999,
        adjustments = it.glass.adjustments + ("edge_feather" to -999))) }
    check(fresh.settings.glass.refractionBlur == 200 && fresh.settings.glass.adjustments["edge_feather"] == -200)
    val migrated = ControlCenterTopButtonsGlassSettingsStore(memoryPreferences(mutableMapOf()))
    migrated.syncPreferences(memoryPreferences(mutableMapOf("add_control_center_top_buttons" to true,
        "control_center_top_buttons_icon_scale" to 1.4f)))
    check(migrated.settings.enabled && migrated.settings.iconScale == 1.4f) { "Recover legacy-only remote settings" }
}

