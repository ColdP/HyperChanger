// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import top.yukonga.miuix.kmp.theme.ThemeColorSpec

internal const val KEY_APP_MONET_COLOR_SPEC = "app_theme_monet_color_spec"

internal enum class AppMonetColorSpec(val id: String, val labelKey: String, val label: String, val spec: ThemeColorSpec) {
    MATERIAL_3("2021", "monet_color_spec_2021", "Material 3 (2021)", ThemeColorSpec.Spec2021),
    EXPRESSIVE("2025", "monet_color_spec_2025", "Expressive (2025)", ThemeColorSpec.Spec2025),
}

private fun hasLegacyMonet2025(p: SharedPreferences) =
    p.getString(KEY_APP_MONET_PALETTE, null) == "tonal_spot_2025"

internal fun readAppMonetColorSpec(p: SharedPreferences): AppMonetColorSpec? {
    if (!p.contains(KEY_APP_MONET_COLOR_SPEC)) {
        return if (hasLegacyMonet2025(p)) AppMonetColorSpec.EXPRESSIVE else null
    }
    return AppMonetColorSpec.entries.firstOrNull { it.id == p.getString(KEY_APP_MONET_COLOR_SPEC, null) }
}

/** Owns color-spec persistence and synchronization independently of HookSettings and palettes. */
internal class AppMonetColorSpecSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var selection by mutableStateOf(readAppMonetColorSpec(local)); private set
    val effectiveSpec: AppMonetColorSpec get() = selection ?: AppMonetColorSpec.MATERIAL_3

    init {
        // Save the old combined selection before a later palette change can replace its id.
        if (!local.contains(KEY_APP_MONET_COLOR_SPEC) && hasLegacyMonet2025(local)) write(local, selection)
    }

    fun observe(): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_APP_MONET_COLOR_SPEC) selection = readAppMonetColorSpec(local)
        }
        local.registerOnSharedPreferenceChangeListener(listener)
        selection = readAppMonetColorSpec(local)
        return { local.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        selection = readAppMonetColorSpec(
            if (remote.contains(KEY_APP_MONET_COLOR_SPEC) || hasLegacyMonet2025(remote)) remote else local,
        )
        write(local, selection)
        write(remote, selection)
    }

    fun update(service: XposedService?, value: AppMonetColorSpec) {
        write(local, value)
        selection = value
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, value) }
    }

    private fun write(p: SharedPreferences, value: AppMonetColorSpec?) {
        p.edit().apply {
            if (value == null) remove(KEY_APP_MONET_COLOR_SPEC) else putString(KEY_APP_MONET_COLOR_SPEC, value.id)
        }.apply()
    }
}
