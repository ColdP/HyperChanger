// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook


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

    val bar = VolumePanelLayoutSettings(cornerEnabled = true, corner = 12)
    for (name in listOf("o3_miui_volume_radius", "o3_miui_tiny_volume_radius", "miui_volume_bg_radius",
        "o3_miui_volume_bg_radius", "miui_volume_bg_radius_expanded", "miui_volume_blur_bg_radius",
        "volume_dialog_background_corner_radius", "volume_dialog_background_square_corner_radius")) {
        check(volumePanelLayoutDimensionDp(name, 24f, bar) == 36f) { "Shared radius missing: $name" }
        check(volumePanelLayoutDimensionDp(name, 10f, bar.copy(corner = -40)) == 0f)
        check(volumePanelLayoutDimensionDp(name, 24f, bar.copy(corner = 0)) == null)
        check(volumePanelLayoutDimensionDp(name, 24f, bar.copy(cornerEnabled = false)) == null)
    }
    check(volumePanelLayoutDimensionDp("unrelated", 24f, bar) == null)
    println("Volume panel footer visibility and shared corner checks passed")
}
