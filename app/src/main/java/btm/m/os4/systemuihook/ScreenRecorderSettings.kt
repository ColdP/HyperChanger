// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

const val SCREEN_RECORDER_FRAME_RATES = "screenrecorder_more_frame_rates"
const val SCREEN_RECORDER_BIT_RATES = "screenrecorder_more_bit_rates"
const val SCREEN_RECORDER_CONFIG = "screenrecorder_config"
const val SCREEN_RECORDER_SAVE_PATH = "screenrecorder_save_path"

data class ScreenRecorderSettings(
    val moreFrameRates: Boolean = false,
    val moreBitRates: Boolean = false,
    val savePath: String = "",
)

class ScreenRecorderSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: ScreenRecorderSettings = read(local)
        private set

    fun reload() { settings = read(local) }

    fun update(service: XposedService?, next: ScreenRecorderSettings) {
        settings = next
        write(local, next)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, next) }
    }

    private fun read(prefs: SharedPreferences): ScreenRecorderSettings {
        val legacyConfig = prefs.getBoolean(SCREEN_RECORDER_CONFIG, false)
        val hasSeparateOptions = prefs.contains(SCREEN_RECORDER_FRAME_RATES) ||
            prefs.contains(SCREEN_RECORDER_BIT_RATES)
        return ScreenRecorderSettings(
            moreFrameRates = if (hasSeparateOptions) {
                prefs.getBoolean(SCREEN_RECORDER_FRAME_RATES, false)
            } else legacyConfig,
            moreBitRates = if (hasSeparateOptions) {
                prefs.getBoolean(SCREEN_RECORDER_BIT_RATES, false)
            } else legacyConfig,
            savePath = prefs.getString(SCREEN_RECORDER_SAVE_PATH, "").orEmpty(),
        )
    }

    private fun write(prefs: SharedPreferences, value: ScreenRecorderSettings) {
        prefs.edit()
            .putBoolean(SCREEN_RECORDER_FRAME_RATES, value.moreFrameRates)
            .putBoolean(SCREEN_RECORDER_BIT_RATES, value.moreBitRates)
            .remove(SCREEN_RECORDER_CONFIG)
            .putString(SCREEN_RECORDER_SAVE_PATH, value.savePath)
            .apply()
    }
}
