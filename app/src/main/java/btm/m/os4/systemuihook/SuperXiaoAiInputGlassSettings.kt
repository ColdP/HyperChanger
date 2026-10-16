// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

internal const val XIAOAI_INPUT_GLASS_PREFIX = "super_xiaoai_floating_input_"
internal const val KEY_XIAOAI_INPUT_GLASS_ENABLED = "${XIAOAI_INPUT_GLASS_PREFIX}glass"

/**
 * Retired key. It used to scale the shader's layer alpha, which made `u81.f.draw()` bail out as soon
 * as the value dropped, losing the whole glass layer. The value is fixed at 100% now and this key is
 * actively removed on write so a stale low value can never dim the glass again.
 */
internal const val KEY_XIAOAI_INPUT_GLASS_OPACITY = "${XIAOAI_INPUT_GLASS_PREFIX}opacity"

internal data class XiaoAiGlassParameters(
    val blur: Int = 100,
    val refraction: Int = 100,
    val softLight: Int = 100,
    val saturation: Int = 100,
    val brightness: Int = 100,
) {
    fun normalized() = copy(
        blur = blur.coerceIn(0, 200), refraction = refraction.coerceIn(0, 200),
        softLight = softLight.coerceIn(0, 200), saturation = saturation.coerceIn(0, 200),
        brightness = brightness.coerceIn(0, 200),
    )
}

/** Settings for the soft glass layer added to the floating input box only. */
internal data class SuperXiaoAiInputGlassSettings(
    val enabled: Boolean = false,
    val parameters: XiaoAiGlassParameters = XiaoAiGlassParameters(),
) {
    fun normalized() = copy(parameters = parameters.normalized())
}

internal fun readSuperXiaoAiInputGlassSettings(p: SharedPreferences): SuperXiaoAiInputGlassSettings =
    SuperXiaoAiInputGlassSettings(
        enabled = p.getBoolean(KEY_XIAOAI_INPUT_GLASS_ENABLED, false),
        parameters = XiaoAiGlassParameters(
            blur = p.getInt("${XIAOAI_INPUT_GLASS_PREFIX}blur", 100),
            refraction = p.getInt("${XIAOAI_INPUT_GLASS_PREFIX}refraction", 100),
            softLight = p.getInt("${XIAOAI_INPUT_GLASS_PREFIX}soft_light", 100),
            saturation = p.getInt("${XIAOAI_INPUT_GLASS_PREFIX}saturation", 100),
            brightness = p.getInt("${XIAOAI_INPUT_GLASS_PREFIX}brightness", 100),
        ),
    ).normalized()

internal class SuperXiaoAiInputGlassSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readSuperXiaoAiInputGlassSettings(local); private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = readSuperXiaoAiInputGlassSettings(
            if (remote.contains(KEY_XIAOAI_INPUT_GLASS_ENABLED)) remote else local,
        )
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (SuperXiaoAiInputGlassSettings) -> SuperXiaoAiInputGlassSettings) {
        settings = transform(settings).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, s: SuperXiaoAiInputGlassSettings) {
        val e = p.edit().putBoolean(KEY_XIAOAI_INPUT_GLASS_ENABLED, s.enabled)
        e.putInt("${XIAOAI_INPUT_GLASS_PREFIX}blur", s.parameters.blur)
            .putInt("${XIAOAI_INPUT_GLASS_PREFIX}refraction", s.parameters.refraction)
            .putInt("${XIAOAI_INPUT_GLASS_PREFIX}soft_light", s.parameters.softLight)
            .putInt("${XIAOAI_INPUT_GLASS_PREFIX}saturation", s.parameters.saturation)
            .putInt("${XIAOAI_INPUT_GLASS_PREFIX}brightness", s.parameters.brightness)
            .remove(KEY_XIAOAI_INPUT_GLASS_OPACITY)
        e.apply()
    }
}
