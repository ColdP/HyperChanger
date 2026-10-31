// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

internal const val KEY_APP_MONET_PALETTE = "app_theme_monet_palette"

internal enum class AppMonetPalette(
    val id: String,
    val labelKey: String,
    val label: String,
    val style: ThemePaletteStyle,
) {
    TONAL_SPOT("tonal_spot", "monet_palette_tonal_spot", "柔和彩调", ThemePaletteStyle.TonalSpot),
    NEUTRAL("neutral", "monet_palette_neutral", "中性", ThemePaletteStyle.Neutral),
    VIBRANT("vibrant", "monet_palette_vibrant", "鲜艳", ThemePaletteStyle.Vibrant),
    EXPRESSIVE("expressive", "monet_palette_expressive", "丰富表现", ThemePaletteStyle.Expressive),
    RAINBOW("rainbow", "monet_palette_rainbow", "彩虹", ThemePaletteStyle.Rainbow),
    FRUIT_SALAD("fruit_salad", "monet_palette_fruit_salad", "水果沙拉", ThemePaletteStyle.FruitSalad),
    MONOCHROME("monochrome", "monet_palette_monochrome", "单色", ThemePaletteStyle.Monochrome),
    FIDELITY("fidelity", "monet_palette_fidelity", "保真", ThemePaletteStyle.Fidelity),
    CONTENT("content", "monet_palette_content", "内容", ThemePaletteStyle.Content),
}

internal fun readAppMonetPalette(p: SharedPreferences): AppMonetPalette? {
    val id = p.getString(KEY_APP_MONET_PALETTE, null)
    // The former combined option is now a palette plus an independent color specification.
    return if (id == "tonal_spot_2025") AppMonetPalette.TONAL_SPOT
        else AppMonetPalette.entries.firstOrNull { it.id == id }
}

/** Owns only the app's palette selection; an absent value preserves the existing theme. */
internal class AppMonetPaletteSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var selection by mutableStateOf(readAppMonetPalette(local)); private set

    fun effectivePalette(colorTheme: String): AppMonetPalette = selection
        ?: if (colorTheme == APP_COLOR_MONO) AppMonetPalette.MONOCHROME else AppMonetPalette.TONAL_SPOT

    fun observe(): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_APP_MONET_PALETTE) selection = readAppMonetPalette(local)
        }
        local.registerOnSharedPreferenceChangeListener(listener)
        selection = readAppMonetPalette(local)
        return { local.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        selection = readAppMonetPalette(if (remote.contains(KEY_APP_MONET_PALETTE)) remote else local)
        write(local, selection)
        write(remote, selection)
    }

    fun update(service: XposedService?, value: AppMonetPalette) {
        write(local, value)
        selection = value
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, value) }
    }

    private fun write(p: SharedPreferences, value: AppMonetPalette?) {
        p.edit().apply {
            if (value == null) remove(KEY_APP_MONET_PALETTE) else putString(KEY_APP_MONET_PALETTE, value.id)
        }.apply()
    }
}
