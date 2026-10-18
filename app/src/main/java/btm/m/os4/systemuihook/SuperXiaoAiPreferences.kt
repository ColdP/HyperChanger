// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

/**
 * Preferences for the Super XiaoAI tweaks: the function restrictions, the keyboard appearance and
 * the global search entry.
 *
 * Moved out of `HookSettings` so this feature owns its own storage. Every key keeps its original
 * name, so existing installs keep their values across the move.
 */
internal const val KEY_SUPER_XIAOAI_GLOBAL_SEARCH_APPEARANCE = "super_xiaoai_global_search_appearance"
internal const val KEY_SUPER_XIAOAI_BLACKLIST_UNBLOCKED = "super_xiaoai_blacklist_unblocked"
internal const val KEY_SUPER_XIAOAI_CLIPBOARD_UNBLOCKED = "super_xiaoai_clipboard_unblocked"
internal const val KEY_SUPER_XIAOAI_AI_SAFETY_UNBLOCKED = "super_xiaoai_ai_safety_unblocked"
internal const val KEY_SUPER_XIAOAI_VOICE_SAFETY_UNBLOCKED = "super_xiaoai_voice_safety_unblocked"
internal const val KEY_SUPER_XIAOAI_KEYBOARD_STYLE_ENABLED = "super_xiaoai_keyboard_style_enabled"
internal const val KEY_SUPER_XIAOAI_KEYBOARD_COLOR_MODE = "super_xiaoai_keyboard_color_mode"
internal const val KEY_SUPER_XIAOAI_KEYBOARD_CORNER_RADIUS = "super_xiaoai_keyboard_corner_radius"
internal const val KEY_SUPER_XIAOAI_KEYBOARD_OPACITY = "super_xiaoai_keyboard_opacity"
internal const val KEY_SUPER_XIAOAI_KEYBOARD_BLUR = "super_xiaoai_keyboard_blur"
internal const val KEY_SUPER_XIAOAI_KEYBOARD_HIGHLIGHT = "super_xiaoai_keyboard_highlight"
internal const val KEY_SUPER_XIAOAI_KEYBOARD_SHADOW = "super_xiaoai_keyboard_shadow"
internal const val KEY_SUPER_XIAOAI_KEYBOARD_STROKE_WIDTH = "super_xiaoai_keyboard_stroke_width"
internal const val KEY_SUPER_XIAOAI_KEYBOARD_BOTTOM_HIGHLIGHT = "super_xiaoai_keyboard_bottom_highlight"

internal data class SuperXiaoAiPreferences(
    val globalSearchAppearance: Boolean = false,
    val blacklistUnblocked: Boolean = false,
    val clipboardUnblocked: Boolean = false,
    val aiSafetyUnblocked: Boolean = false,
    val voiceSafetyUnblocked: Boolean = false,
    val keyboardStyleEnabled: Boolean = false,
    val keyboardColorMode: Int = 0,
    val keyboardCornerRadius: Float = 24f,
    val keyboardOpacity: Int = 56,
    val keyboardBlur: Float = 15f,
    val keyboardHighlight: Int = 100,
    val keyboardShadow: Int = 10,
    val keyboardStrokeWidth: Float = 2f,
    val keyboardBottomHighlight: Int = 100,
) {
    fun normalized() = copy(
        keyboardColorMode = keyboardColorMode.coerceIn(0, 2),
        keyboardCornerRadius = keyboardCornerRadius.coerceIn(0f, 48f),
        keyboardOpacity = keyboardOpacity.coerceIn(0, 100),
        keyboardBlur = keyboardBlur.coerceIn(0f, 45f),
        keyboardHighlight = keyboardHighlight.coerceIn(0, 100),
        keyboardShadow = keyboardShadow.coerceIn(0, 100),
        keyboardStrokeWidth = keyboardStrokeWidth.coerceIn(0f, 4f),
        keyboardBottomHighlight = keyboardBottomHighlight.coerceIn(0, 100),
    )
}

private fun SharedPreferences.getFloatCompat(key: String, default: Float): Float =
    runCatching { getFloat(key, default) }.getOrElse {
        runCatching { getInt(key, default.toInt()).toFloat() }.getOrDefault(default)
    }
internal fun readSuperXiaoAiPreferences(p: SharedPreferences): SuperXiaoAiPreferences =
    SuperXiaoAiPreferences(
        globalSearchAppearance = p.getBoolean(KEY_SUPER_XIAOAI_GLOBAL_SEARCH_APPEARANCE, false),
        blacklistUnblocked = p.getBoolean(KEY_SUPER_XIAOAI_BLACKLIST_UNBLOCKED, false),
        clipboardUnblocked = p.getBoolean(KEY_SUPER_XIAOAI_CLIPBOARD_UNBLOCKED, false),
        aiSafetyUnblocked = p.getBoolean(KEY_SUPER_XIAOAI_AI_SAFETY_UNBLOCKED, false),
        voiceSafetyUnblocked = p.getBoolean(KEY_SUPER_XIAOAI_VOICE_SAFETY_UNBLOCKED, false),
        keyboardStyleEnabled = p.getBoolean(KEY_SUPER_XIAOAI_KEYBOARD_STYLE_ENABLED, false),
        keyboardColorMode = p.getInt(KEY_SUPER_XIAOAI_KEYBOARD_COLOR_MODE, 0),
        keyboardCornerRadius = p.getFloatCompat(KEY_SUPER_XIAOAI_KEYBOARD_CORNER_RADIUS, 24f),
        keyboardOpacity = p.getInt(KEY_SUPER_XIAOAI_KEYBOARD_OPACITY, 56),
        keyboardBlur = p.getFloatCompat(KEY_SUPER_XIAOAI_KEYBOARD_BLUR, 15f),
        keyboardHighlight = p.getInt(KEY_SUPER_XIAOAI_KEYBOARD_HIGHLIGHT, 100),
        keyboardShadow = p.getInt(KEY_SUPER_XIAOAI_KEYBOARD_SHADOW, 10),
        keyboardStrokeWidth = p.getFloatCompat(KEY_SUPER_XIAOAI_KEYBOARD_STROKE_WIDTH, 2f),
        keyboardBottomHighlight = p.getInt(KEY_SUPER_XIAOAI_KEYBOARD_BOTTOM_HIGHLIGHT, 100),
    ).normalized()

internal class SuperXiaoAiPreferencesStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readSuperXiaoAiPreferences(local); private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = readSuperXiaoAiPreferences(
            if (remote.contains(KEY_SUPER_XIAOAI_GLOBAL_SEARCH_APPEARANCE)) remote else local,
        )
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (SuperXiaoAiPreferences) -> SuperXiaoAiPreferences) {
        settings = transform(settings).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, s: SuperXiaoAiPreferences) {
        p.edit()
            .putBoolean(KEY_SUPER_XIAOAI_GLOBAL_SEARCH_APPEARANCE, s.globalSearchAppearance)
            .putBoolean(KEY_SUPER_XIAOAI_BLACKLIST_UNBLOCKED, s.blacklistUnblocked)
            .putBoolean(KEY_SUPER_XIAOAI_CLIPBOARD_UNBLOCKED, s.clipboardUnblocked)
            .putBoolean(KEY_SUPER_XIAOAI_AI_SAFETY_UNBLOCKED, s.aiSafetyUnblocked)
            .putBoolean(KEY_SUPER_XIAOAI_VOICE_SAFETY_UNBLOCKED, s.voiceSafetyUnblocked)
            .putBoolean(KEY_SUPER_XIAOAI_KEYBOARD_STYLE_ENABLED, s.keyboardStyleEnabled)
            .putInt(KEY_SUPER_XIAOAI_KEYBOARD_COLOR_MODE, s.keyboardColorMode)
            .putFloat(KEY_SUPER_XIAOAI_KEYBOARD_CORNER_RADIUS, s.keyboardCornerRadius)
            .putInt(KEY_SUPER_XIAOAI_KEYBOARD_OPACITY, s.keyboardOpacity)
            .putFloat(KEY_SUPER_XIAOAI_KEYBOARD_BLUR, s.keyboardBlur)
            .putInt(KEY_SUPER_XIAOAI_KEYBOARD_HIGHLIGHT, s.keyboardHighlight)
            .putInt(KEY_SUPER_XIAOAI_KEYBOARD_SHADOW, s.keyboardShadow)
            .putFloat(KEY_SUPER_XIAOAI_KEYBOARD_STROKE_WIDTH, s.keyboardStrokeWidth)
            .putInt(KEY_SUPER_XIAOAI_KEYBOARD_BOTTOM_HIGHLIGHT, s.keyboardBottomHighlight)
            .apply()
    }
}
