// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * Vendor audio tuning switch and the badge shown at the bottom of the sound effect screen.
 *
 * `com.miui.misound` decides whether to expose the BOSE tuning by reading
 * `ro.vendor.audio.bose.effect.enable` (via `z.m.B()` in the decompiled build). The badge on that
 * page is a plain `ImageView` with `android:src="@drawable/bose_logo"`, so swapping the drawable it
 * receives rebrands it.
 *
 * Harman Kardon is intentionally not offered as a tuning profile: its menu entry lives in the
 * headset page and is additionally gated on "no headset connected". It is only a badge choice here.
 */
internal const val AUDIO_PROP_BOSE = "ro.vendor.audio.bose.effect.enable"

internal const val AUDIO_LOGO_NONE = 0
internal const val AUDIO_LOGO_BOSE = 1
internal const val AUDIO_LOGO_XIAOMI = 2
internal const val AUDIO_LOGO_HARMAN = 3
internal const val AUDIO_LOGO_DOLBY = 4
internal const val AUDIO_LOGO_CUSTOM = 5
internal const val AUDIO_LOGO_MAX = AUDIO_LOGO_CUSTOM

private const val KEY_AUDIO_BOSE_ENABLED = "audio_bose_enabled"
private const val KEY_AUDIO_LOGO_MODE = "audio_logo_mode"
// Retired keys; migrated on first read.
private const val KEY_AUDIO_PROFILE = "audio_profile"
private const val KEY_AUDIO_LEGACY_LOGO_MODE = "audio_logo_mode_v1"

internal data class SoundEffectPreferences(
    val boseEnabled: Boolean = false,
    val logoMode: Int = AUDIO_LOGO_BOSE,
) {
    fun normalized() = copy(logoMode = logoMode.coerceIn(0, AUDIO_LOGO_MAX))

    /** True when a custom badge should be drawn for this mode. */
    val needsCustomLogo: Boolean get() = logoMode == AUDIO_LOGO_CUSTOM

    /** True when the badge is enabled at all. */
    val showsLogo: Boolean get() = logoMode != AUDIO_LOGO_NONE
}

internal fun readSoundEffectPreferences(p: SharedPreferences): SoundEffectPreferences {
    val mode = when {
        p.contains(KEY_AUDIO_LOGO_MODE) -> p.getInt(KEY_AUDIO_LOGO_MODE, AUDIO_LOGO_BOSE)
        // v1 had 0=BOSE..3=custom; shift it onto the current numbering.
        p.contains(KEY_AUDIO_LEGACY_LOGO_MODE) -> when (p.getInt(KEY_AUDIO_LEGACY_LOGO_MODE, 0)) {
            0 -> AUDIO_LOGO_BOSE
            1 -> AUDIO_LOGO_HARMAN
            2 -> AUDIO_LOGO_DOLBY
            else -> AUDIO_LOGO_CUSTOM
        }
        else -> AUDIO_LOGO_BOSE
    }
    return SoundEffectPreferences(
        boseEnabled = if (p.contains(KEY_AUDIO_BOSE_ENABLED)) {
            p.getBoolean(KEY_AUDIO_BOSE_ENABLED, false)
        } else {
            p.getInt(KEY_AUDIO_PROFILE, 0) == 1
        },
        logoMode = mode,
    ).normalized()
}

internal class SoundEffectPreferencesStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readSoundEffectPreferences(local); private set

    init {
        if (local.contains(KEY_AUDIO_PROFILE) || local.contains(KEY_AUDIO_LEGACY_LOGO_MODE)) {
            write(local, settings)
        }
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        // Local wins: the UI writes locally first and may do so before the binding exists.
        settings = if (local.contains(KEY_AUDIO_BOSE_ENABLED)) {
            readSoundEffectPreferences(local)
        } else {
            readSoundEffectPreferences(remote)
        }
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (SoundEffectPreferences) -> SoundEffectPreferences) {
        settings = transform(settings).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, s: SoundEffectPreferences) {
        p.edit()
            .putBoolean(KEY_AUDIO_BOSE_ENABLED, s.boseEnabled)
            .putInt(KEY_AUDIO_LOGO_MODE, s.logoMode)
            .remove(KEY_AUDIO_PROFILE)
            .remove(KEY_AUDIO_LEGACY_LOGO_MODE)
            .apply()
    }
}
