// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.core.graphics.PathParser
import io.github.libxposed.service.XposedService
import java.io.ByteArrayOutputStream
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal const val KEY_CC_FOCUS_MODE_ENABLED = "control_center_focus_mode_enabled"
internal const val KEY_CC_FOCUS_MODE_LIST = "control_center_focus_mode_list_v2"
internal const val KEY_CC_FOCUS_MODE_ACTIVE_ID = "control_center_focus_mode_active_id"

internal const val FOCUS_ICON_MOON = "moon"
internal const val FOCUS_ICON_BED = "bed"
internal const val FOCUS_ICON_CAR = "car"
internal const val FOCUS_ICON_SPARKLES = "sparkles"
internal const val FOCUS_ICON_PERSON = "person"
internal const val FOCUS_ICON_WORK = "work"
internal const val FOCUS_ICON_BOOK = "book"
internal const val FOCUS_ICON_GAME = "game"
internal const val FOCUS_ICON_HEART = "heart"
internal const val FOCUS_ICON_STAR = "star"
internal const val FOCUS_ICON_CUSTOM = "custom"

data class CcFocusModeItem(
    val id: String,
    val name: String,
    val subtitle: String = "",
    val iconKey: String = FOCUS_ICON_MOON,
    val accentColor: Int = 0xFF5E5CE6.toInt(),
    val customIconBase64: String = "",
    val customIconName: String = "",
    val tintCustomIcon: Boolean = true,
)

data class ControlCenterFocusModeSettings(
    val enabled: Boolean = true,
    val modes: List<CcFocusModeItem> = CcFocusModeDefaults.createDefaultModes(),
    val activeId: String = "dnd",
)

internal object CcFocusModeDefaults {
    val accentPalette: List<Int> = listOf(
        0xFF5E5CE6.toInt(), // Indigo / Do Not Disturb
        0xFF5856D6.toInt(), // Deep Indigo
        0xFF32ADE6.toInt(), // Sky / Sleep
        0xFF30D158.toInt(), // Work Green
        0xFFAF52DE.toInt(), // Personal Purple
        0xFFFF9F0A.toInt(), // Warm Orange
        0xFFFF375F.toInt(), // Rose Pink
        0xFF64D2FF.toInt(), // Ice Blue
    )

    val builtInIcons: List<Pair<String, String>> = listOf(
        FOCUS_ICON_MOON to "cc_focus_icon_moon",
        FOCUS_ICON_BED to "cc_focus_icon_bed",
        FOCUS_ICON_CAR to "cc_focus_icon_car",
        FOCUS_ICON_SPARKLES to "cc_focus_icon_sparkles",
        FOCUS_ICON_PERSON to "cc_focus_icon_person",
        FOCUS_ICON_WORK to "cc_focus_icon_work",
        FOCUS_ICON_BOOK to "cc_focus_icon_book",
        FOCUS_ICON_GAME to "cc_focus_icon_game",
        FOCUS_ICON_HEART to "cc_focus_icon_heart",
        FOCUS_ICON_STAR to "cc_focus_icon_star",
    )

    /** App-localized default modes (uses HyperChanger's active language table via tr()). */
    fun createLocalizedDefaultModes(): List<CcFocusModeItem> = listOf(
        CcFocusModeItem(
            id = "dnd",
            name = tr("cc_focus_default_dnd_name", "勿扰模式"),
            subtitle = tr("cc_focus_default_dnd_sub", "静音所有通知"),
            iconKey = FOCUS_ICON_MOON,
            accentColor = 0xFF5E5CE6.toInt(),
        ),
        CcFocusModeItem(
            id = "sleep",
            name = tr("cc_focus_default_sleep_name", "睡眠"),
            subtitle = "",
            iconKey = FOCUS_ICON_BED,
            accentColor = 0xFF32ADE6.toInt(),
        ),
        CcFocusModeItem(
            id = "driving",
            name = tr("cc_focus_default_driving_name", "驾驶"),
            subtitle = "",
            iconKey = FOCUS_ICON_CAR,
            accentColor = 0xFF5856D6.toInt(),
        ),
        CcFocusModeItem(
            id = "reduce",
            name = tr("cc_focus_default_reduce_name", "减少干扰"),
            subtitle = tr("cc_focus_default_reduce_sub", "仅允许重要通知提醒"),
            iconKey = FOCUS_ICON_SPARKLES,
            accentColor = 0xFF64D2FF.toInt(),
        ),
        CcFocusModeItem(
            id = "personal",
            name = tr("cc_focus_default_personal_name", "个人"),
            subtitle = tr("cc_focus_default_personal_sub", "关闭工作通知，专注个人生活"),
            iconKey = FOCUS_ICON_PERSON,
            accentColor = 0xFFAF52DE.toInt(),
        ),
        CcFocusModeItem(
            id = "work",
            name = tr("cc_focus_default_work_name", "工作"),
            subtitle = tr("cc_focus_default_work_sub", "高效完成工作"),
            iconKey = FOCUS_ICON_WORK,
            accentColor = 0xFF30D158.toInt(),
        ),
    )

    /** Safe locale-based default modes usable inside SystemUI hook process without app UI state. */
    fun createDefaultModes(locale: Locale = Locale.getDefault()): List<CcFocusModeItem> {
        val lang = locale.language.lowercase(Locale.ROOT)
        return when (lang) {
            "ja" -> listOf(
                CcFocusModeItem("dnd", "おやすみモード", "すべての通知を消音", FOCUS_ICON_MOON, 0xFF5E5CE6.toInt()),
                CcFocusModeItem("sleep", "睡眠", "", FOCUS_ICON_BED, 0xFF32ADE6.toInt()),
                CcFocusModeItem("driving", "運転", "", FOCUS_ICON_CAR, 0xFF5856D6.toInt()),
                CcFocusModeItem("reduce", "さまたげ低減", "重要な通知のみ許可", FOCUS_ICON_SPARKLES, 0xFF64D2FF.toInt()),
                CcFocusModeItem("personal", "パーソナル", "仕事の通知をオフ", FOCUS_ICON_PERSON, 0xFFAF52DE.toInt()),
                CcFocusModeItem("work", "仕事", "仕事に集中", FOCUS_ICON_WORK, 0xFF30D158.toInt()),
            )
            "en" -> listOf(
                CcFocusModeItem("dnd", "Do Not Disturb", "Silence all notifications", FOCUS_ICON_MOON, 0xFF5E5CE6.toInt()),
                CcFocusModeItem("sleep", "Sleep", "", FOCUS_ICON_BED, 0xFF32ADE6.toInt()),
                CcFocusModeItem("driving", "Driving", "", FOCUS_ICON_CAR, 0xFF5856D6.toInt()),
                CcFocusModeItem("reduce", "Reduce Interruptions", "Limit interruptions to only important notifications", FOCUS_ICON_SPARKLES, 0xFF64D2FF.toInt()),
                CcFocusModeItem("personal", "Personal", "Turn off work, focus on you", FOCUS_ICON_PERSON, 0xFFAF52DE.toInt()),
                CcFocusModeItem("work", "Work", "Get things done", FOCUS_ICON_WORK, 0xFF30D158.toInt()),
            )
            else -> listOf(
                CcFocusModeItem("dnd", "勿扰模式", "静音所有通知", FOCUS_ICON_MOON, 0xFF5E5CE6.toInt()),
                CcFocusModeItem("sleep", "睡眠", "", FOCUS_ICON_BED, 0xFF32ADE6.toInt()),
                CcFocusModeItem("driving", "驾驶", "", FOCUS_ICON_CAR, 0xFF5856D6.toInt()),
                CcFocusModeItem("reduce", "减少干扰", "仅允许重要通知提醒", FOCUS_ICON_SPARKLES, 0xFF64D2FF.toInt()),
                CcFocusModeItem("personal", "个人", "关闭工作通知，专注个人生活", FOCUS_ICON_PERSON, 0xFFAF52DE.toInt()),
                CcFocusModeItem("work", "工作", "高效完成工作", FOCUS_ICON_WORK, 0xFF30D158.toInt()),
            )
        }
    }

    fun offTitle(locale: Locale = Locale.getDefault()): String = when (locale.language.lowercase(Locale.ROOT)) {
        "ja" -> "集中モード"
        "en" -> "Focus"
        else -> "专注模式"
    }

    fun activeOnLabel(locale: Locale = Locale.getDefault()): String = when (locale.language.lowercase(Locale.ROOT)) {
        "ja" -> "オン"
        "en" -> "On"
        else -> "已开启"
    }

    fun encode(modes: List<CcFocusModeItem>): String = JSONArray().apply {
        modes.forEach { m ->
            put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.name)
                    .put("subtitle", m.subtitle)
                    .put("iconKey", m.iconKey)
                    .put("accentColor", m.accentColor)
                    .put("customIconBase64", m.customIconBase64)
                    .put("customIconName", m.customIconName)
                    .put("tintCustomIcon", m.tintCustomIcon),
            )
        }
    }.toString()

    fun decode(raw: String?, fallback: List<CcFocusModeItem>): List<CcFocusModeItem> {
        if (raw.isNullOrBlank()) return fallback
        return runCatching {
            val array = JSONArray(raw)
            val parsed = mutableListOf<CcFocusModeItem>()
            val seen = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim().ifEmpty { "focus_$i" }
                if (!seen.add(id)) continue
                val name = obj.optString("name").trim().ifEmpty { "专注模式" }
                parsed += CcFocusModeItem(
                    id = id,
                    name = name,
                    subtitle = obj.optString("subtitle").trim(),
                    iconKey = obj.optString("iconKey", FOCUS_ICON_MOON).ifBlank { FOCUS_ICON_MOON },
                    accentColor = obj.optInt("accentColor", 0xFF5E5CE6.toInt()),
                    customIconBase64 = obj.optString("customIconBase64", ""),
                    customIconName = obj.optString("customIconName", ""),
                    tintCustomIcon = obj.optBoolean("tintCustomIcon", true),
                )
            }
            parsed.ifEmpty { fallback }
        }.getOrElse { fallback }
    }
}

internal fun readControlCenterFocusModeSettings(
    preferences: SharedPreferences,
    locale: Locale = Locale.getDefault(),
): ControlCenterFocusModeSettings {
    val fallback = CcFocusModeDefaults.createDefaultModes(locale)
    val modes = CcFocusModeDefaults.decode(preferences.getString(KEY_CC_FOCUS_MODE_LIST, null), fallback)
    val savedActive = preferences.getString(KEY_CC_FOCUS_MODE_ACTIVE_ID, null).orEmpty()
    val resolvedActive = modes.firstOrNull { it.id == savedActive }?.id ?: modes.first().id
    return ControlCenterFocusModeSettings(
        enabled = preferences.getBoolean(KEY_CC_FOCUS_MODE_ENABLED, true),
        modes = modes,
        activeId = resolvedActive,
    )
}

internal fun readControlCenterFocusModeSettingsForApp(
    preferences: SharedPreferences,
): ControlCenterFocusModeSettings {
    val fallback = CcFocusModeDefaults.createLocalizedDefaultModes()
    val modes = CcFocusModeDefaults.decode(preferences.getString(KEY_CC_FOCUS_MODE_LIST, null), fallback)
    val savedActive = preferences.getString(KEY_CC_FOCUS_MODE_ACTIVE_ID, null).orEmpty()
    val resolvedActive = modes.firstOrNull { it.id == savedActive }?.id ?: modes.first().id
    return ControlCenterFocusModeSettings(
        enabled = preferences.getBoolean(KEY_CC_FOCUS_MODE_ENABLED, true),
        modes = modes,
        activeId = resolvedActive,
    )
}

/** Keep exported and imported presets complete even when Focus Mode settings were untouched. */
internal fun completeControlCenterFocusModePreset(values: JSONObject) {
    if (!values.has(KEY_CC_FOCUS_MODE_ENABLED)) {
        values.put(KEY_CC_FOCUS_MODE_ENABLED, JSONObject().put("type", "boolean").put("value", true))
    }
    if (!values.has(KEY_CC_FOCUS_MODE_LIST)) {
        values.put(
            KEY_CC_FOCUS_MODE_LIST,
            JSONObject().put(
                "type",
                "string",
            ).put("value", CcFocusModeDefaults.encode(CcFocusModeDefaults.createLocalizedDefaultModes())),
        )
    }
    if (!values.has(KEY_CC_FOCUS_MODE_ACTIVE_ID)) {
        values.put(KEY_CC_FOCUS_MODE_ACTIVE_ID, JSONObject().put("type", "string").put("value", "dnd"))
    }
}

class ControlCenterFocusModeSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: ControlCenterFocusModeSettings = readControlCenterFocusModeSettingsForApp(local)
        private set

    fun syncRemote(service: XposedService) {
        settings = readControlCenterFocusModeSettingsForApp(local)
        write(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP), settings)
    }

    fun update(
        service: XposedService?,
        transform: (ControlCenterFocusModeSettings) -> ControlCenterFocusModeSettings,
    ) {
        val next = transform(settings)
        val safeModes = next.modes.ifEmpty { CcFocusModeDefaults.createLocalizedDefaultModes() }
        val safeActive = safeModes.firstOrNull { it.id == next.activeId }?.id ?: safeModes.first().id
        settings = next.copy(modes = safeModes, activeId = safeActive)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(preferences: SharedPreferences, value: ControlCenterFocusModeSettings) {
        preferences.edit()
            .putBoolean(KEY_CC_FOCUS_MODE_ENABLED, value.enabled)
            .putString(KEY_CC_FOCUS_MODE_LIST, CcFocusModeDefaults.encode(value.modes))
            .putString(KEY_CC_FOCUS_MODE_ACTIVE_ID, value.activeId)
            .apply()
    }
}

/**
 * Official Material Symbols Rounded (FILL=1, weight 400, viewBox 0 -960 960 960) vector icons +
 * exact ArrowUpDown.svg path + compact cross-process Base64 custom icon codec for Focus Mode.
 */
internal object CcFocusIcons {
    // Exact path from D:\Downloads\ArrowUpDown.svg (viewBox 0 0 10 16).
    private const val ARROW_UP_DOWN_PATH =
        "M2.397 4.7384 L4.5688 2.5665 L5.0075 2.1278 L5.4266 2.5469 L7.5985 4.7187 L8.531 5.6512 " +
            "C8.8282 5.9485 9.3102 5.9485 9.6075 5.6512 C9.9047 5.354 9.9047 4.872 9.6075 4.5747 " +
            "L8.675 3.6423 L6.5031 1.4704 L5.5706 0.5379 C5.3595 0.3267 5.0551 0.2656 4.7899 0.3544 " +
            "C4.6561 0.3855 4.5291 0.4532 4.4248 0.5575 L3.4924 1.49 L1.3205 3.6619 L0.388 4.5943 " +
            "C0.0907 4.8916 0.0907 5.3736 0.388 5.6708 C0.6853 5.9681 1.1672 5.9681 1.4645 5.6708 " +
            "L2.397 4.7384 Z M2.397 11.257 L4.5688 13.4289 L5.0075 13.8675 L5.4266 13.4485 " +
            "L7.5985 11.2766 L8.531 10.3441 C8.8282 10.0468 9.3102 10.0468 9.6075 10.3441 " +
            "C9.9047 10.6414 9.9047 11.1233 9.6075 11.4206 L8.675 12.3531 L6.5031 14.525 " +
            "L5.5706 15.4574 C5.3594 15.6686 5.0551 15.7298 4.7899 15.6409 C4.6561 15.6098 " +
            "4.5291 15.5421 4.4248 15.4378 L3.4924 14.5053 L1.3205 12.3335 L0.388 11.401 " +
            "C0.0907 11.1037 0.0907 10.6217 0.388 10.3245 C0.6853 10.0272 1.1672 10.0272 1.4645 10.3245 " +
            "L2.397 11.257 Z"

    // Official Google Material Symbols Rounded (FILL=1, weight 400, viewBox 0 -960 960 960).
    private val materialSymbolPaths: Map<String, String> = mapOf(
        // bedtime-fill
        FOCUS_ICON_MOON to
            "M484-80q-84 0-157.5-32t-128-86.5Q144-253 112-326.5T80-484q0-132 77.5-238T361-868q17-5 31 5t12 27" +
            "q-7 88 23.5 169.5T521-522q63 63 145 94t170 24q17-2 26.5 13t4.5 32q-40 125-145.5 202T484-80Z",
        // bed-fill
        FOCUS_ICON_BED to
            "M80-230v-225q0-25 10-47t30-36v-116q0-45 30.5-75.5T226-760h180q22 0 41 10t33 27q14-17 32.5-27t40.5-10" +
            "h180q45 0 76 30.5t31 75.5v116q20 14 30 36t10 47v225q0 13-8.5 21.5T850-200q-13 0-21.5-8.5T820-230v-50" +
            "H140v50q0 13-8.5 21.5T110-200q-13 0-21.5-8.5T80-230Zm430-325h270v-99q0-20-13.5-33T733-700H550" +
            "q-17 0-28.5 14T510-654v99Zm-330 0h270v-99q0-18-11.5-32T410-700H226q-19 0-32.5 13.5T180-654v99Z",
        // directions_car-fill
        FOCUS_ICON_CAR to
            "M200-204v44q0 16.67-11.74 28.33Q176.53-120 159.76-120q-16.76 0-28.26-11.67Q120-143.33 120-160v-304" +
            "q0-4.67.5-9.33.5-4.67 2.5-9.67l78-236q6-19 21.75-30T258-760h444q19.5 0 35.25 11T759-719l78 236" +
            "q2 5 2.5 9.67.5 4.66.5 9.33v304q0 16.67-11.74 28.33Q816.53-120 799.76-120 783-120 771-131.96T759-161" +
            "v-43H200Zm3-330h554l-55-166H258l-55 166Zm82.76 220q23.24 0 38.74-15.75Q340-345.5 340-368" +
            "q0-23.33-15.75-39.67Q308.5-424 286-424q-23.33 0-39.67 16.26Q230-391.47 230-368.24q0 23.24 16.26 38.74" +
            " 16.27 15.5 39.5 15.5ZM675-314q23.33 0 39.67-15.75Q731-345.5 731-368q0-23.33-16.26-39.67" +
            "Q698.47-424 675.24-424q-23.24 0-38.74 16.26-15.5 16.27-15.5 39.5 0 23.24 15.75 38.74Q652.5-314 675-314Z",
        // filter_vintage-fill
        FOCUS_ICON_SPARKLES to
            "M381.5-118.5Q340-157 331-220q-59 23-110.5 4T140-283q-29-48-19-105t68-92q-60-36-69-92.5T140-677" +
            "q29-48 80.5-67t110.5 4q9-63 50.5-101.5T479-880q56 0 97.5 38.5T627-740q59-23 110.5-4t80.5 67" +
            "q29 48 20 104.5T769-480q58 35 67.5 92.5T818-283q-29 48-80.5 67T627-220q-9 63-50.5 101.5T479-80" +
            "q-56 0-97.5-38.5ZM479-313q69 0 117.5-48.5T645-480q0-70-48.5-118.5T479-647q-69 0-115.5 48.5T317-480" +
            "q0 70 46.5 118.5T479-313Z",
        // person-fill
        FOCUS_ICON_PERSON to
            "M372-523q-42-42-42-108t42-108q42-42 108-42t108 42q42 42 42 108t-42 108q-42 42-108 42t-108-42Z" +
            "M160-220v-34q0-38 19-65t49-41q67-30 128.5-45T480-420q62 0 123 15.5T731-360q31 14 50 41t19 65v34" +
            "q0 25-17.5 42.5T740-160H220q-25 0-42.5-17.5T160-220Z",
        // work-fill
        FOCUS_ICON_WORK to
            "M140-120q-24 0-42-18t-18-42v-480q0-24 18-42t42-18h180v-100q0-24 18-42t42-18h200q24 0 42 18t18 42" +
            "v100h180q24 0 42 18t18 42v480q0 24-18 42t-42 18H140Zm240-600h200v-100H380v100Z",
        // menu_book-fill
        FOCUS_ICON_BOOK to
            "M512-250q50-25 98-37.5T712-300q38 0 78.5 6t69.5 16v-429q-34-17-72-25t-76-8q-54 0-104.5 16.5T512-677" +
            "v427Zm-44.5 77.5Q461-174 456-178q-47-29-100-45t-108-16q-37 0-72 9t-70 22q-23 11-44.5-3T40-251v-463" +
            "q0-15 7-27.5T68-761q42-20 87.5-29.5T248-800q63 0 122.5 17T482-731q51-35 109.5-52T712-800q47 0 92 9.5" +
            "t87 29.5q14 7 21.5 19.5T920-714v463q0 28-22.5 42.5t-44.5.5q-34-14-69-22.5t-72-8.5q-54 0-106 16t-98 45" +
            "q-5 4-11.5 5.5T482-171q-8 0-14.5-1.5ZM560-608q0-6 4-11.5t9-7.5q30-11 61.5-17t65.5-6q22 0 43 2.5t41 7.5" +
            "q6 2 11 7.5t5 12.5q0 11-7.5 17t-18.5 3q-17-5-35.5-7.5T700-610q-29 0-56 5.5T591-588q-14 5-22.5-.5" +
            "T560-608Zm0 220q0-6 4-12t9-8q30-11 61.5-16.5T700-430q22 0 43 2.5t41 7.5q6 2 11 7.5t5 12.5q0 11-7.5 17" +
            "t-18.5 3q-17-5-35.5-7.5T700-390q-29 0-56 5t-53 16q-14 5-22.5 0t-8.5-19Zm0-110q0-6 4-11.5t9-7.5" +
            "q30-11 61.5-17t65.5-6q22 0 43 2.5t41 7.5q6 2 11 7.5t5 12.5q0 11-7.5 17t-18.5 3q-17-5-35.5-7.5T700-500" +
            "q-29 0-56 5.5T591-478q-14 5-22.5-.5T560-498Z",
        // sports_esports-fill
        FOCUS_ICON_GAME to
            "M147-200q-38 0-59.5-25.5T72-296l48-335q8-53 49.5-91t94.5-38h433q53 0 94.5 38t49.5 91l47 335" +
            "q6 45-15.5 70.5T813-200q-23 0-39-7.5T747-226L643-330H317L213-226q-11 11-27 18.5t-39 7.5Zm581-252" +
            "q12-12 12-28t-12-28q-12-12-28-12t-28 12q-12 12-12 28t12 28q12 12 28 12t28-12Zm-85-130q12-12 12-28" +
            "t-12-28q-12-12-28-12t-28 12q-12 12-12 28t12 28q12 12 28 12t28-12Zm-343 62v50q0 11 7 18t18 7" +
            "q11 0 18-7t7-18v-50h50q11 0 18-7t7-18q0-11-7-18t-18-7h-50v-50q0-11-7-18t-18-7q-11 0-18 7t-7 18v50" +
            "h-50q-11 0-18 7t-7 18q0 11 7 18t18 7h50Z",
        // favorite-fill
        FOCUS_ICON_HEART to
            "M458-144q-11-4-19-12l-53-49Q262-320 171-424.5T80-643q0-90 60.5-150.5T290-854q51 0 101 24.5t89 80.5" +
            "q44-56 91-80.5t99-24.5q89 0 149.5 60.5T880-643q0 114-91 218.5T574-205l-53 49q-8 8-19 12t-22 4" +
            "q-11 0-22-4Z",
        // star-fill
        FOCUS_ICON_STAR to
            "M480-269 294-157q-8 5-17 4.5t-16-5.5q-7-5-10.5-13t-1.5-18l49-212-164-143q-8-7-9.5-15.5t.5-16.5" +
            "q2-8 9-13.5t17-6.5l217-19 84-200q4-9 12-13.5t16-4.5q8 0 16 4.5t12 13.5l84 200 217 19q10 1 17 6.5" +
            "t9 13.5q2 8 .5 16.5T826-544L662-401l49 212q2 10-1.5 18T699-158q-7 5-16 5.5t-17-4.5L480-269Z",
    )

    private val parsedPaths = mutableMapOf<String, Path>()
    private val customDrawableCache = LruCache<String, ByteArray>(16)

    private fun parsedPath(key: String, data: String): Path = synchronized(parsedPaths) {
        parsedPaths.getOrPut(key) {
            runCatching { PathParser.createPathFromPathData(data) }.getOrElse { Path() }
        }
    }

    fun arrowUpDownDrawable(color: Int): Drawable =
        VectorPathDrawable(parsedPath("__arrow_up_down__", ARROW_UP_DOWN_PATH), 0f, 0f, 10f, 16f, color)

    fun builtInDrawable(iconKey: String, color: Int): Drawable {
        val key = if (iconKey in materialSymbolPaths) iconKey else FOCUS_ICON_MOON
        val data = materialSymbolPaths.getValue(key)
        return VectorPathDrawable(parsedPath(key, data), 0f, -960f, 960f, 960f, color)
    }

    fun modeDrawable(context: Context, mode: CcFocusModeItem, tintColor: Int): Drawable {
        if (mode.iconKey == FOCUS_ICON_CUSTOM && mode.customIconBase64.isNotBlank()) {
            val bytes = decodeBase64Cached(mode.customIconBase64)
            val loaded = bytes?.let { runCatching { LogoDrawableLoader.loadBytes(context, it) }.getOrNull() }
            if (loaded != null) {
                val mutated = loaded.mutate()
                if (mode.tintCustomIcon) {
                    mutated.colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
                } else {
                    mutated.clearColorFilter()
                }
                return mutated
            }
        }
        return builtInDrawable(mode.iconKey, tintColor)
    }

    fun decodeBase64ToBitmap(context: Context, base64: String, maxPx: Int = 128): Bitmap? {
        if (base64.isBlank()) return null
        val bytes = decodeBase64Cached(base64) ?: return null
        val drawable = runCatching { LogoDrawableLoader.loadBytes(context, bytes) }.getOrNull() ?: return null
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: maxPx
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: maxPx
        val scale = minOf(1f, maxPx.toFloat() / maxOf(w, h))
        val outW = (w * scale).toInt().coerceAtLeast(1)
        val outH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also { bmp ->
            drawable.setBounds(0, 0, outW, outH)
            drawable.draw(Canvas(bmp))
        }
    }

    fun encodeCustomIconFromUri(context: Context, uri: Uri): String? = runCatching {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
        if (rawBytes.isEmpty()) return@runCatching null
        val head = rawBytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        val isVector = head.startsWith("<svg", true) || head.contains("<svg", true) ||
            head.startsWith("<vector", true) || (head.startsWith("<?xml", true) && head.contains("<vector", true))
        if (isVector && rawBytes.size <= 32 * 1024 && LogoDrawableLoader.loadBytes(context, rawBytes) != null) {
            return@runCatching Base64.encodeToString(rawBytes, Base64.NO_WRAP)
        }
        val drawable = LogoDrawableLoader.loadBytes(context, rawBytes) ?: return@runCatching null
        val srcW = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
        val srcH = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
        val scale = minOf(1f, 128f / maxOf(srcW, srcH))
        val outW = (srcW * scale).toInt().coerceAtLeast(1)
        val outH = (srcH * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, outW, outH)
            drawable.draw(Canvas(bmp))
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            bmp.recycle()
        }
    }.getOrNull()

    private fun decodeBase64Cached(base64: String): ByteArray? {
        synchronized(customDrawableCache) {
            customDrawableCache.get(base64)?.let { return it }
        }
        val decoded = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
        synchronized(customDrawableCache) {
            customDrawableCache.put(base64, decoded)
        }
        return decoded
    }

    private class VectorPathDrawable(
        private val sourcePath: Path,
        private val viewportMinX: Float,
        private val viewportMinY: Float,
        private val viewportW: Float,
        private val viewportH: Float,
        color: Int,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }
        private val transformed = Path()
        private val matrix = Matrix()

        override fun draw(canvas: Canvas) {
            val b: Rect = bounds
            if (b.isEmpty) return
            val scale = minOf(b.width() / viewportW, b.height() / viewportH)
            val dx = b.left + (b.width() - viewportW * scale) * 0.5f
            val dy = b.top + (b.height() - viewportH * scale) * 0.5f
            matrix.reset()
            matrix.postTranslate(-viewportMinX, -viewportMinY)
            matrix.postScale(scale, scale)
            matrix.postTranslate(dx, dy)
            sourcePath.transform(matrix, transformed)
            transformed.fillType = Path.FillType.EVEN_ODD
            canvas.drawPath(transformed, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = 96
        override fun getIntrinsicHeight(): Int = 96
    }
}
