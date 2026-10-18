// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * Switches for the "System Framework" page.
 *
 * Everything here targets `system_server` (the `android` / `system` package), apart from the device
 * name check which also touches `com.android.settings`. These are internal flags the platform never
 * exposes, so each one is best effort and may not apply on every ROM build.
 */
private const val KEY_FW_MULTI_FREEFORM = "fw_allow_multiple_freeform"
private const val KEY_FW_FORCE_FREEFORM = "fw_force_allow_freeform"
private const val KEY_FW_REMEMBER_FREEFORM = "fw_remember_freeform_state"
private const val KEY_FW_FREEFORM_BACKGROUND = "fw_freeform_background_activity"
private const val KEY_FW_VOLUME_SAFE_MODE = "fw_volume_safe_mode"
private const val KEY_FW_VOLUME_INSTANT = "fw_volume_instant_adjust"
private const val KEY_FW_FORCE_DARK_ALL = "fw_force_dark_all_apps"
private const val KEY_FW_RECENTS_VISIBILITY = "fw_enhance_recents_visibility"
private const val KEY_FW_NATIVE_INSTALLER = "fw_use_native_installer"
private const val KEY_FW_PIN_72H = "fw_disable_pin_verify_72h"
private const val KEY_FW_DEVICE_NAME = "fw_disable_device_name_check"
private const val KEY_FW_QUICK_SCREENSHOT = "fw_quick_screenshot"
private const val KEY_FW_SCREENSHOT_ANYWHERE = "fw_screenshot_anywhere"

/** Value for [FrameworkPreferences.volumeSafeMode]. */
internal const val VOLUME_SAFE_DEFAULT = 0
internal const val VOLUME_SAFE_ALWAYS = 1
internal const val VOLUME_SAFE_HEADSET_ONLY = 2

internal data class FrameworkPreferences(
    val multipleFreeform: Boolean = false,
    val forceFreeform: Boolean = false,
    val rememberFreeform: Boolean = false,
    val freeformBackground: Boolean = false,
    val volumeSafeMode: Int = VOLUME_SAFE_DEFAULT,
    val volumeInstant: Boolean = false,
    val forceDarkAllApps: Boolean = false,
    val recentsVisibility: Boolean = false,
    val nativeInstaller: Boolean = false,
    val disablePin72h: Boolean = false,
    val disableDeviceNameCheck: Boolean = false,
    val quickScreenshot: Boolean = false,
    val screenshotAnywhere: Boolean = false,
) {
    fun normalized() = copy(volumeSafeMode = volumeSafeMode.coerceIn(0, VOLUME_SAFE_HEADSET_ONLY))
}

internal fun readFrameworkPreferences(p: SharedPreferences) = FrameworkPreferences(
    multipleFreeform = p.getBoolean(KEY_FW_MULTI_FREEFORM, false),
    forceFreeform = p.getBoolean(KEY_FW_FORCE_FREEFORM, false),
    rememberFreeform = p.getBoolean(KEY_FW_REMEMBER_FREEFORM, false),
    freeformBackground = p.getBoolean(KEY_FW_FREEFORM_BACKGROUND, false),
    volumeSafeMode = p.getInt(KEY_FW_VOLUME_SAFE_MODE, VOLUME_SAFE_DEFAULT),
    volumeInstant = p.getBoolean(KEY_FW_VOLUME_INSTANT, false),
    forceDarkAllApps = p.getBoolean(KEY_FW_FORCE_DARK_ALL, false),
    recentsVisibility = p.getBoolean(KEY_FW_RECENTS_VISIBILITY, false),
    nativeInstaller = p.getBoolean(KEY_FW_NATIVE_INSTALLER, false),
    disablePin72h = p.getBoolean(KEY_FW_PIN_72H, false),
    disableDeviceNameCheck = p.getBoolean(KEY_FW_DEVICE_NAME, false),
    quickScreenshot = p.getBoolean(KEY_FW_QUICK_SCREENSHOT, false),
    screenshotAnywhere = p.getBoolean(KEY_FW_SCREENSHOT_ANYWHERE, false),
).normalized()

internal class FrameworkPreferencesStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readFrameworkPreferences(local); private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        // Local wins: the UI writes locally first and may do so before the binding exists.
        settings = if (local.contains(KEY_FW_MULTI_FREEFORM)) {
            readFrameworkPreferences(local)
        } else {
            readFrameworkPreferences(remote)
        }
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (FrameworkPreferences) -> FrameworkPreferences) {
        settings = transform(settings).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, s: FrameworkPreferences) {
        p.edit()
            .putBoolean(KEY_FW_MULTI_FREEFORM, s.multipleFreeform)
            .putBoolean(KEY_FW_FORCE_FREEFORM, s.forceFreeform)
            .putBoolean(KEY_FW_REMEMBER_FREEFORM, s.rememberFreeform)
            .putBoolean(KEY_FW_FREEFORM_BACKGROUND, s.freeformBackground)
            .putInt(KEY_FW_VOLUME_SAFE_MODE, s.volumeSafeMode)
            .putBoolean(KEY_FW_VOLUME_INSTANT, s.volumeInstant)
            .putBoolean(KEY_FW_FORCE_DARK_ALL, s.forceDarkAllApps)
            .putBoolean(KEY_FW_RECENTS_VISIBILITY, s.recentsVisibility)
            .putBoolean(KEY_FW_NATIVE_INSTALLER, s.nativeInstaller)
            .putBoolean(KEY_FW_PIN_72H, s.disablePin72h)
            .putBoolean(KEY_FW_DEVICE_NAME, s.disableDeviceNameCheck)
            .putBoolean(KEY_FW_QUICK_SCREENSHOT, s.quickScreenshot)
            .putBoolean(KEY_FW_SCREENSHOT_ANYWHERE, s.screenshotAnywhere)
            .apply()
    }
}
