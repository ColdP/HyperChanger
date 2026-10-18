// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences

private const val KEY_REFRACTION_BLUR = "soft_glass_parameters_v3_refraction_blur_percent"

/** Retain the previous dual-radius multiplier when upgrading existing installations. */
internal fun readSoftGlassRefractionBlur(preferences: SharedPreferences): Int = preferences.getInt(
    KEY_REFRACTION_BLUR,
    preferences.getInt("soft_glass_parameters_v3_blur_percent", preferences.getInt("soft_glass_parameters_v3_element_blur", 100)),
).coerceIn(0, 200)

/** Called by the settings store for both local and Xposed remote preferences. */
internal fun SharedPreferences.Editor.putSoftGlassRefractionBlur(percent: Int) {
    putInt(KEY_REFRACTION_BLUR, percent.coerceIn(0, 200))
}
