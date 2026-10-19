// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import java.lang.reflect.Proxy

private fun preferences(values: MutableMap<String, Any>): SharedPreferences =
    Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "contains" -> values.containsKey(args!![0])
            "getBoolean", "getInt" -> values[args!![0]] ?: args[1]
            "edit" -> {
                val pending = mutableMapOf<String, Any>()
                Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, setter, input ->
                    when (setter.name) {
                        "putInt" -> { pending[input!![0] as String] = input[1]!!; proxy }
                        "apply" -> { values.putAll(pending); null }
                        else -> error(setter.name)
                    }
                }
            }
            else -> error(method.name)
        }
    } as SharedPreferences

fun main() {
    val state = VolumePanelRingerVisibility()
    check(state.resolve(0, true, true, false, false) == 8)
    repeat(50) {
        check(state.resolve(8, false, true, true, false) == 0) { "Expansion must restore the system footer" }
        check(state.resolve(0, false, true, false, false) == 8) { "Collapsing must reapply suppression" }
    }
    check(state.resolve(8, false, false, false, false) == 0) { "Turning off must restore the footer" }
    check(state.resolve(8, true, true, false, false) == 8)
    check(state.resolve(8, false, true, true, false) == 8) { "Keep a system-hidden footer hidden" }
    check(state.resolve(4, true, true, false, false) == 8)
    check(state.resolve(8, false, true, true, false) == 4) { "Retain INVISIBLE as well as GONE" }
    check(state.resolve(0, true, true, false, true) == 0) { "Do not hide Control Center controls" }
    // A system footer refresh while expanded must take precedence over old suppression.
    check(state.resolve(0, true, true, false, false) == 8)
    check(state.resolve(8, true, true, true, false) == 8)

    val disk = mutableMapOf<String, Any>()
    val local = preferences(disk)
    val remote = preferences(mutableMapOf())
    check(readVolumePanelExpandedCorner(local) == 0)
    check(VolumePanelExpandedCornerPreferences.sync(local, remote) == 0)
    VolumePanelExpandedCornerPreferences.write(local, -100)
    check(readVolumePanelExpandedCorner(local) == -20)
    VolumePanelExpandedCornerPreferences.write(remote, 100)
    check(VolumePanelExpandedCornerPreferences.sync(local, remote) == 30)
    check(readVolumePanelExpandedCorner(preferences(disk)) == 30) { "Reopening must retain the setting" }

    val bar = VolumePanelLayoutSettings(cornerEnabled = true, corner = 12)
    for (name in listOf("o3_miui_volume_radius", "o3_miui_tiny_volume_radius", "miui_volume_bg_radius")) {
        check(volumePanelLayoutDimensionDp(name, 24f, bar) == 36f)
        check(volumePanelExpandedCornerDimensionDp(name, 24f, -20, true) == null)
    }
    for (name in listOf("o3_miui_volume_bg_radius", "miui_volume_bg_radius_expanded", "miui_volume_blur_bg_radius",
        "volume_dialog_background_corner_radius", "volume_dialog_background_square_corner_radius")) {
        check(volumePanelLayoutDimensionDp(name, 36f, bar) == null) { "Bar radius must not leak to expanded background: $name" }
        check(volumePanelExpandedCornerDimensionDp(name, 36f, -20, true) == 16f)
        check(volumePanelExpandedCornerDimensionDp(name, 36f, 30, true) == 66f)
        check(volumePanelExpandedCornerDimensionDp(name, 10f, -20, true) == 0f)
        check(volumePanelExpandedCornerDimensionDp(name, 36f, 0, true) == null)
        check(volumePanelExpandedCornerDimensionDp(name, 36f, 30, false) == null)
    }
    check(volumePanelExpandedCornerDimensionDp("unrelated", 24f, 30, true) == null)
    println("Volume panel footer visibility, independent corners and persistence checks passed")
}
