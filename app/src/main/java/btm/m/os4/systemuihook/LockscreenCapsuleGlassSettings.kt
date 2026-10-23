// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val LOCKSCREEN_CAPSULE_GLASS_PREFIX = "lockscreen_capsule_glass_v1_"
private const val USE_GLOBAL = "${LOCKSCREEN_CAPSULE_GLASS_PREFIX}use_global"

internal data class LockscreenCapsuleGlassSettings(
    val useGlobal: Boolean = false,
    val glass: SoftGlassSettings = SoftGlassSettings().resetParameters(),
)

internal fun readLockscreenCapsuleGlassSettings(p: SharedPreferences) = LockscreenCapsuleGlassSettings(
    useGlobal = p.getBoolean(USE_GLOBAL, false),
    glass = readSoftGlassSettings(p, LOCKSCREEN_CAPSULE_GLASS_PREFIX),
)

internal class LockscreenCapsuleGlassSettingsStore(private val local: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    var settings = readLockscreenCapsuleGlassSettings(local)
        private set

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        // Recover missing capsule keys without replacing edits saved before service connection.
        local.edit().apply {
            remote.all.forEach { (key, value) ->
                if (key.startsWith(LOCKSCREEN_CAPSULE_GLASS_PREFIX) && !local.contains(key)) {
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                    }
                }
            }
        }.apply()
        settings = readLockscreenCapsuleGlassSettings(local)
        write(local)
        write(remote)
    }

    fun update(service: XposedService?, transform: (LockscreenCapsuleGlassSettings) -> LockscreenCapsuleGlassSettings) {
        settings = transform(readLockscreenCapsuleGlassSettings(local)).let { it.copy(glass = it.glass.normalized()) }
        write(local)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }

    private fun write(p: SharedPreferences) {
        p.edit().apply {
            putBoolean(USE_GLOBAL, settings.useGlobal)
            putBoolean("${LOCKSCREEN_CAPSULE_GLASS_PREFIX}enabled", settings.glass.enabled)
            putInt("${LOCKSCREEN_CAPSULE_GLASS_PREFIX}refraction_blur", settings.glass.refractionBlur)
            putInt("${LOCKSCREEN_CAPSULE_GLASS_PREFIX}blur_percent", settings.glass.backgroundBlur)
            putInt("${LOCKSCREEN_CAPSULE_GLASS_PREFIX}scale_percent", settings.glass.backgroundScale)
            putInt("${LOCKSCREEN_CAPSULE_GLASS_PREFIX}opacity_percent", settings.glass.backgroundOpacity)
            softGlassParameters.forEach { definition ->
                val suffix = if (definition.key == "edge_feather") "edge_feather_inverse" else definition.key
                putInt(LOCKSCREEN_CAPSULE_GLASS_PREFIX + suffix, settings.glass.adjustments[definition.key]
                    ?: if (definition.key == "darker") -100 else 0)
            }
        }.apply()
    }
}
