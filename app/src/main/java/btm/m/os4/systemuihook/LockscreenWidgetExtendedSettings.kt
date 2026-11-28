// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal const val KEY_LOCKSCREEN_WIDGET_EXT_ACTIVE = "lockscreen_widget_ext_active"
internal const val KEY_LOCKSCREEN_WIDGET_EXT_SELECTION = "lockscreen_widget_ext_selection"
internal const val KEY_LOCKSCREEN_WIDGET_VERTICAL_OFFSET = "lockscreen_widget_vertical_offset"
internal const val KEY_LOCKSCREEN_WIDGET_SCALE = "lockscreen_widget_scale"
internal const val KEY_LOCKSCREEN_WIDGET_INTERACTIVE = "lockscreen_widget_interactive"
internal const val KEY_LOCKSCREEN_WIDGET_FOLLOW_CLOCK_BOTTOM = "lockscreen_widget_follow_clock_bottom"
internal const val KEY_LOCKSCREEN_WIDGET_WORLD_CLOCK_ZONE = "lockscreen_widget_world_clock_zone"
internal const val KEY_LOCKSCREEN_WIDGET_WORLD_CLOCK_LABEL = "lockscreen_widget_world_clock_label"
internal const val KEY_LOCKSCREEN_WIDGET_COUNTDOWN_TITLE = "lockscreen_widget_countdown_title"
internal const val KEY_LOCKSCREEN_WIDGET_COUNTDOWN_DATE = "lockscreen_widget_countdown_date"
internal const val KEY_LOCKSCREEN_WIDGET_MEMO_TITLE = "lockscreen_widget_memo_title"
internal const val KEY_LOCKSCREEN_WIDGET_MEMO_CONTENT = "lockscreen_widget_memo_content"

internal const val LOCKSCREEN_WIDGET_VERTICAL_OFFSET_MIN = -160
internal const val LOCKSCREEN_WIDGET_VERTICAL_OFFSET_MAX = 160
internal const val LOCKSCREEN_WIDGET_SCALE_MIN = 75
internal const val LOCKSCREEN_WIDGET_SCALE_MAX = 125
internal const val LOCKSCREEN_WIDGET_DEFAULT_WORLD_CLOCK_ZONE = "Asia/Tokyo"

/**
 * Stable string IDs for all lockscreen widgets (legacy 16 + 14 extended iOS/OneUI widgets).
 * String IDs avoid the 32-bit Int bitmask ceiling and keep HookSettings.kt untouched.
 */
internal object LockscreenWidgetIds {
    // Legacy 16 widgets (1:1 with HookSettings LOCKSCREEN_WIDGET_ITEM_* flags)
    const val DETAIL_WEATHER = "detail_weather"
    const val DETAIL_BATTERY = "detail_battery"
    const val DETAIL_WEATHER_BG = "detail_weather_bg"
    const val DETAIL_BATTERY_BG = "detail_battery_bg"
    const val COMPACT_WEATHER = "compact_weather"
    const val SUN = "sun"
    const val STEPS = "steps"
    const val STEPS_WIDE = "steps_wide"
    const val SIGNATURE = "signature"
    const val SCHEDULE = "schedule"
    const val HUMIDITY = "humidity"
    const val AQI = "aqi"
    const val FEELS_LIKE = "feels_like"
    const val WIND = "wind"
    const val STAND = "stand"
    const val ALARM = "alarm"

    // New iOS & OneUI inspired widgets (14 additions)
    const val UV_INDEX = "uv_index"
    const val RAIN_PROB = "rain_prob"
    const val PRESSURE = "pressure"
    const val BATTERY_RING = "battery_ring"
    const val BATTERY_TEMP = "battery_temp"
    const val BLUETOOTH_BATTERY = "bluetooth_battery"
    const val WORLD_CLOCK = "world_clock"
    const val CALENDAR_DATE = "calendar_date"
    const val COUNTDOWN = "countdown"
    const val CUSTOM_MEMO = "custom_memo"
    const val SLEEP = "sleep"
    const val ACTIVITY_RINGS = "activity_rings"
    const val SOUND_MODE = "sound_mode"
    const val FLASHLIGHT = "flashlight"

    val ALL: List<String> = listOf(
        DETAIL_WEATHER,
        DETAIL_BATTERY,
        DETAIL_WEATHER_BG,
        DETAIL_BATTERY_BG,
        COMPACT_WEATHER,
        SUN,
        STEPS,
        STEPS_WIDE,
        SIGNATURE,
        SCHEDULE,
        HUMIDITY,
        AQI,
        FEELS_LIKE,
        WIND,
        STAND,
        ALARM,
        UV_INDEX,
        RAIN_PROB,
        PRESSURE,
        BATTERY_RING,
        BATTERY_TEMP,
        BLUETOOTH_BATTERY,
        WORLD_CLOCK,
        CALENDAR_DATE,
        COUNTDOWN,
        CUSTOM_MEMO,
        SLEEP,
        ACTIVITY_RINGS,
        SOUND_MODE,
        FLASHLIGHT,
    )

    val ALL_SET: Set<String> = ALL.toSet()

    val WIDE_IDS: Set<String> = setOf(
        DETAIL_WEATHER,
        DETAIL_BATTERY,
        DETAIL_WEATHER_BG,
        DETAIL_BATTERY_BG,
        COMPACT_WEATHER,
        STEPS_WIDE,
        SIGNATURE,
        SCHEDULE,
        BLUETOOTH_BATTERY,
        COUNTDOWN,
        CUSTOM_MEMO,
        ACTIVITY_RINGS,
    )

    val LEGACY_FLAG_TO_ID: Map<Int, String> = linkedMapOf(
        LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER to DETAIL_WEATHER,
        LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY to DETAIL_BATTERY,
        LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER_BACKGROUND to DETAIL_WEATHER_BG,
        LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY_BACKGROUND to DETAIL_BATTERY_BG,
        LOCKSCREEN_WIDGET_ITEM_COMPACT_WEATHER to COMPACT_WEATHER,
        LOCKSCREEN_WIDGET_ITEM_SUN to SUN,
        LOCKSCREEN_WIDGET_ITEM_STEPS to STEPS,
        LOCKSCREEN_WIDGET_ITEM_STEPS_WIDE to STEPS_WIDE,
        LOCKSCREEN_WIDGET_ITEM_SIGNATURE to SIGNATURE,
        LOCKSCREEN_WIDGET_ITEM_SCHEDULE to SCHEDULE,
        LOCKSCREEN_WIDGET_ITEM_HUMIDITY to HUMIDITY,
        LOCKSCREEN_WIDGET_ITEM_AQI to AQI,
        LOCKSCREEN_WIDGET_ITEM_FEELS_LIKE to FEELS_LIKE,
        LOCKSCREEN_WIDGET_ITEM_WIND to WIND,
        LOCKSCREEN_WIDGET_ITEM_STAND to STAND,
        LOCKSCREEN_WIDGET_ITEM_ALARM to ALARM,
    )

    val ID_TO_LEGACY_FLAG: Map<String, Int> = LEGACY_FLAG_TO_ID.entries.associate { (k, v) -> v to k }

    fun isWide(id: String): Boolean = id in WIDE_IDS

    fun slotCost(id: String): Int = if (isWide(id)) 2 else 1

    fun counterpartOf(id: String): String? = when (id) {
        DETAIL_WEATHER -> DETAIL_WEATHER_BG
        DETAIL_WEATHER_BG -> DETAIL_WEATHER
        DETAIL_BATTERY -> DETAIL_BATTERY_BG
        DETAIL_BATTERY_BG -> DETAIL_BATTERY
        else -> null
    }
}

internal data class WorldClockCityOption(
    val zoneId: String,
    val code: String,
    val zhName: String,
    val enName: String,
    val jaName: String,
)

internal val WORLD_CLOCK_CITY_OPTIONS: List<WorldClockCityOption> = listOf(
    WorldClockCityOption("Asia/Tokyo", "TYO", "东京", "Tokyo", "東京"),
    WorldClockCityOption("Asia/Seoul", "SEL", "首尔", "Seoul", "ソウル"),
    WorldClockCityOption("Asia/Singapore", "SIN", "新加坡", "Singapore", "シンガポール"),
    WorldClockCityOption("Europe/London", "LON", "伦敦", "London", "ロンドン"),
    WorldClockCityOption("Europe/Paris", "PAR", "巴黎", "Paris", "パリ"),
    WorldClockCityOption("Europe/Berlin", "BER", "柏林", "Berlin", "ベルリン"),
    WorldClockCityOption("America/New_York", "NYC", "纽约", "New York", "ニューヨーク"),
    WorldClockCityOption("America/Los_Angeles", "LAX", "洛杉矶", "Los Angeles", "ロサンゼルス"),
    WorldClockCityOption("Australia/Sydney", "SYD", "悉尼", "Sydney", "シドニー"),
    WorldClockCityOption("Asia/Dubai", "DXB", "迪拜", "Dubai", "ドバイ"),
    WorldClockCityOption("Asia/Shanghai", "SHA", "北京/上海", "Beijing", "北京/上海"),
    WorldClockCityOption("UTC", "UTC", "UTC", "UTC", "UTC"),
)

internal fun resolveWorldClockShortLabel(zoneId: String, customLabel: String): String {
    val trimmed = customLabel.trim()
    if (trimmed.isNotEmpty()) return trimmed.take(8)
    val match = WORLD_CLOCK_CITY_OPTIONS.firstOrNull { it.zoneId == zoneId }
    if (match != null) {
        return when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
            "zh" -> match.zhName.substringBefore('/')
            "ja" -> match.jaName.substringBefore('/').take(5)
            else -> match.code
        }
    }
    return zoneId.substringAfterLast('/').replace('_', ' ').take(6).ifBlank { "UTC" }
}

internal fun defaultCountdownDateString(): String {
    val cal = Calendar.getInstance()
    val nextYear = cal.get(Calendar.YEAR) + 1
    return String.format(Locale.US, "%04d-01-01", nextYear)
}

internal fun parseCountdownDateMillis(dateText: String): Long? {
    val match = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").matchEntire(dateText.trim()) ?: return null
    val year = match.groupValues[1].toIntOrNull() ?: return null
    val month = match.groupValues[2].toIntOrNull() ?: return null
    val day = match.groupValues[3].toIntOrNull() ?: return null
    if (year !in 1970..2100 || month !in 1..12 || day !in 1..31) return null
    val cal = Calendar.getInstance().apply {
        isLenient = false
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, month - 1)
        set(Calendar.DAY_OF_MONTH, day)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return runCatching { cal.timeInMillis }.getOrNull()
}

internal fun computeCountdownDaysDelta(dateText: String): Int? {
    val effectiveDate = dateText.trim().ifBlank { defaultCountdownDateString() }
    val targetMillis = parseCountdownDateMillis(effectiveDate) ?: return null
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val diffMs = targetMillis - today
    return kotlin.math.round(diffMs.toDouble() / 86_400_000.0).toInt()
}

internal fun normalizeLockscreenWidgetSelection(ids: List<String>): List<String> {
    val result = ArrayList<String>(4)
    var usedSlots = 0
    for (raw in ids) {
        val id = raw.trim()
        if (id !in LockscreenWidgetIds.ALL_SET) continue
        if (id in result) continue
        val counterpart = LockscreenWidgetIds.counterpartOf(id)
        if (counterpart != null && counterpart in result) continue
        val cost = LockscreenWidgetIds.slotCost(id)
        if (usedSlots + cost > 4) continue
        result += id
        usedSlots += cost
    }
    return result
}

internal fun canAddLockscreenWidgetId(currentIds: List<String>, candidateId: String): Boolean {
    if (candidateId !in LockscreenWidgetIds.ALL_SET) return false
    val counterpart = LockscreenWidgetIds.counterpartOf(candidateId)
    val withoutCounterpart = currentIds.filter { it != counterpart && it != candidateId }
    val usedSlots = withoutCounterpart.sumOf(LockscreenWidgetIds::slotCost)
    return usedSlots + LockscreenWidgetIds.slotCost(candidateId) <= 4
}

internal data class LockscreenWidgetExtendedSettings(
    val extActive: Boolean = false,
    val selectedIdsRaw: String = "",
    val verticalOffsetDp: Int = 0,
    val scalePercent: Int = 100,
    val interactiveEnabled: Boolean = true,
    val followClockBottom: Boolean = false,
    val worldClockZoneId: String = LOCKSCREEN_WIDGET_DEFAULT_WORLD_CLOCK_ZONE,
    val worldClockLabel: String = "",
    val countdownTitle: String = "",
    val countdownDate: String = "",
    val memoTitle: String = "",
    val memoContent: String = "",
) {
    fun normalized(): LockscreenWidgetExtendedSettings {
        val parsedIds = normalizeLockscreenWidgetSelection(
            selectedIdsRaw.split(',').filter { it.isNotBlank() },
        )
        val validZone = worldClockZoneId.trim().takeIf {
            it.isNotEmpty() && TimeZone.getTimeZone(it).id == it
        } ?: LOCKSCREEN_WIDGET_DEFAULT_WORLD_CLOCK_ZONE
        val validDate = countdownDate.trim().takeIf {
            it.isEmpty() || parseCountdownDateMillis(it) != null
        }.orEmpty()
        return copy(
            extActive = extActive && parsedIds.isNotEmpty(),
            selectedIdsRaw = parsedIds.joinToString(","),
            verticalOffsetDp = verticalOffsetDp.coerceIn(
                LOCKSCREEN_WIDGET_VERTICAL_OFFSET_MIN,
                LOCKSCREEN_WIDGET_VERTICAL_OFFSET_MAX,
            ),
            scalePercent = scalePercent.coerceIn(
                LOCKSCREEN_WIDGET_SCALE_MIN,
                LOCKSCREEN_WIDGET_SCALE_MAX,
            ),
            worldClockZoneId = validZone,
            worldClockLabel = worldClockLabel.trim().take(12),
            countdownTitle = countdownTitle.trim().take(24),
            countdownDate = validDate,
            memoTitle = memoTitle.trim().take(24),
            memoContent = memoContent.trim().take(48),
        )
    }

    fun resolveSelectedIds(legacyMask: Int, legacyOrder: String): List<String> {
        if (extActive) {
            val parsed = normalizeLockscreenWidgetSelection(
                selectedIdsRaw.split(',').filter { it.isNotBlank() },
            )
            if (parsed.isNotEmpty()) return parsed
        }
        val legacyFlags = legacyOrder.split(',').mapNotNull(String::toIntOrNull)
            .filter { it in LockscreenWidgetIds.LEGACY_FLAG_TO_ID && legacyMask and it != 0 }
            .distinct()
        val missingFlags = LockscreenWidgetIds.LEGACY_FLAG_TO_ID.keys
            .filter { legacyMask and it != 0 && it !in legacyFlags }
        val ids = (legacyFlags + missingFlags).mapNotNull(LockscreenWidgetIds.LEGACY_FLAG_TO_ID::get)
        return normalizeLockscreenWidgetSelection(ids).ifEmpty {
            listOf(LockscreenWidgetIds.DETAIL_WEATHER, LockscreenWidgetIds.DETAIL_BATTERY)
        }
    }
}

internal fun readLockscreenWidgetExtendedSettings(
    preferences: SharedPreferences,
): LockscreenWidgetExtendedSettings = LockscreenWidgetExtendedSettings(
    extActive = preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_EXT_ACTIVE, false),
    selectedIdsRaw = preferences.getString(KEY_LOCKSCREEN_WIDGET_EXT_SELECTION, "").orEmpty(),
    verticalOffsetDp = preferences.getInt(KEY_LOCKSCREEN_WIDGET_VERTICAL_OFFSET, 0),
    scalePercent = preferences.getInt(KEY_LOCKSCREEN_WIDGET_SCALE, 100),
    interactiveEnabled = preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_INTERACTIVE, true),
    followClockBottom = preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_FOLLOW_CLOCK_BOTTOM, false),
    worldClockZoneId = preferences.getString(
        KEY_LOCKSCREEN_WIDGET_WORLD_CLOCK_ZONE,
        LOCKSCREEN_WIDGET_DEFAULT_WORLD_CLOCK_ZONE,
    ).orEmpty().ifBlank { LOCKSCREEN_WIDGET_DEFAULT_WORLD_CLOCK_ZONE },
    worldClockLabel = preferences.getString(KEY_LOCKSCREEN_WIDGET_WORLD_CLOCK_LABEL, "").orEmpty(),
    countdownTitle = preferences.getString(KEY_LOCKSCREEN_WIDGET_COUNTDOWN_TITLE, "").orEmpty(),
    countdownDate = preferences.getString(KEY_LOCKSCREEN_WIDGET_COUNTDOWN_DATE, "").orEmpty(),
    memoTitle = preferences.getString(KEY_LOCKSCREEN_WIDGET_MEMO_TITLE, "").orEmpty(),
    memoContent = preferences.getString(KEY_LOCKSCREEN_WIDGET_MEMO_CONTENT, "").orEmpty(),
).normalized()

internal fun readEffectiveLockscreenWidgetIds(preferences: SharedPreferences): List<String> {
    val ext = readLockscreenWidgetExtendedSettings(preferences)
    if (ext.extActive) {
        val parsed = normalizeLockscreenWidgetSelection(
            ext.selectedIdsRaw.split(',').filter { it.isNotBlank() },
        )
        if (parsed.isNotEmpty()) return parsed
    }
    val legacyMask = preferences.readLockscreenWidgetItems()
    val legacyOrder = preferences.readLockscreenWidgetOrder(legacyMask)
    val mapped = legacyOrder.mapNotNull(LockscreenWidgetIds.LEGACY_FLAG_TO_ID::get)
    return normalizeLockscreenWidgetSelection(mapped).ifEmpty {
        listOf(LockscreenWidgetIds.DETAIL_WEATHER, LockscreenWidgetIds.DETAIL_BATTERY)
    }
}

internal fun completeLockscreenWidgetExtendedPreset(values: JSONObject) {
    if (!values.has(KEY_LOCKSCREEN_WIDGET_EXT_ACTIVE)) {
        values.put(KEY_LOCKSCREEN_WIDGET_EXT_ACTIVE, JSONObject().put("type", "boolean").put("value", false))
    }
    if (!values.has(KEY_LOCKSCREEN_WIDGET_EXT_SELECTION)) {
        values.put(KEY_LOCKSCREEN_WIDGET_EXT_SELECTION, JSONObject().put("type", "string").put("value", ""))
    }
    if (!values.has(KEY_LOCKSCREEN_WIDGET_VERTICAL_OFFSET)) {
        values.put(KEY_LOCKSCREEN_WIDGET_VERTICAL_OFFSET, JSONObject().put("type", "int").put("value", 0))
    }
    if (!values.has(KEY_LOCKSCREEN_WIDGET_SCALE)) {
        values.put(KEY_LOCKSCREEN_WIDGET_SCALE, JSONObject().put("type", "int").put("value", 100))
    }
    if (!values.has(KEY_LOCKSCREEN_WIDGET_INTERACTIVE)) {
        values.put(KEY_LOCKSCREEN_WIDGET_INTERACTIVE, JSONObject().put("type", "boolean").put("value", true))
    }
    if (!values.has(KEY_LOCKSCREEN_WIDGET_FOLLOW_CLOCK_BOTTOM)) {
        values.put(KEY_LOCKSCREEN_WIDGET_FOLLOW_CLOCK_BOTTOM, JSONObject().put("type", "boolean").put("value", false))
    }
    if (!values.has(KEY_LOCKSCREEN_WIDGET_WORLD_CLOCK_ZONE)) {
        values.put(
            KEY_LOCKSCREEN_WIDGET_WORLD_CLOCK_ZONE,
            JSONObject().put("type", "string").put("value", LOCKSCREEN_WIDGET_DEFAULT_WORLD_CLOCK_ZONE),
        )
    }
    if (!values.has(KEY_LOCKSCREEN_WIDGET_WORLD_CLOCK_LABEL)) {
        values.put(KEY_LOCKSCREEN_WIDGET_WORLD_CLOCK_LABEL, JSONObject().put("type", "string").put("value", ""))
    }
    if (!values.has(KEY_LOCKSCREEN_WIDGET_COUNTDOWN_TITLE)) {
        values.put(KEY_LOCKSCREEN_WIDGET_COUNTDOWN_TITLE, JSONObject().put("type", "string").put("value", ""))
    }
    if (!values.has(KEY_LOCKSCREEN_WIDGET_COUNTDOWN_DATE)) {
        values.put(KEY_LOCKSCREEN_WIDGET_COUNTDOWN_DATE, JSONObject().put("type", "string").put("value", ""))
    }
    if (!values.has(KEY_LOCKSCREEN_WIDGET_MEMO_TITLE)) {
        values.put(KEY_LOCKSCREEN_WIDGET_MEMO_TITLE, JSONObject().put("type", "string").put("value", ""))
    }
    if (!values.has(KEY_LOCKSCREEN_WIDGET_MEMO_CONTENT)) {
        values.put(KEY_LOCKSCREEN_WIDGET_MEMO_CONTENT, JSONObject().put("type", "string").put("value", ""))
    }
}

internal class LockscreenWidgetExtendedSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: LockscreenWidgetExtendedSettings = readLockscreenWidgetExtendedSettings(local)
        private set

    fun reload() {
        settings = readLockscreenWidgetExtendedSettings(local)
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val hasLocal = local.contains(KEY_LOCKSCREEN_WIDGET_EXT_ACTIVE) ||
            local.contains(KEY_LOCKSCREEN_WIDGET_EXT_SELECTION) ||
            local.contains(KEY_LOCKSCREEN_WIDGET_VERTICAL_OFFSET) ||
            local.contains(KEY_LOCKSCREEN_WIDGET_SCALE) ||
            local.contains(KEY_LOCKSCREEN_WIDGET_INTERACTIVE) ||
            local.contains(KEY_LOCKSCREEN_WIDGET_FOLLOW_CLOCK_BOTTOM) ||
            local.contains(KEY_LOCKSCREEN_WIDGET_WORLD_CLOCK_ZONE) ||
            local.contains(KEY_LOCKSCREEN_WIDGET_COUNTDOWN_TITLE) ||
            local.contains(KEY_LOCKSCREEN_WIDGET_MEMO_TITLE)
        settings = if (hasLocal) {
            readLockscreenWidgetExtendedSettings(local)
        } else {
            readLockscreenWidgetExtendedSettings(remote)
        }
        write(local, settings)
        write(remote, settings)
    }

    fun update(
        service: XposedService?,
        transform: (LockscreenWidgetExtendedSettings) -> LockscreenWidgetExtendedSettings,
    ) {
        val updated = transform(settings).normalized()
        settings = updated
        write(local, updated)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, updated) }
    }

    private fun write(preferences: SharedPreferences, value: LockscreenWidgetExtendedSettings) {
        preferences.edit()
            .putBoolean(KEY_LOCKSCREEN_WIDGET_EXT_ACTIVE, value.extActive)
            .putString(KEY_LOCKSCREEN_WIDGET_EXT_SELECTION, value.selectedIdsRaw)
            .putInt(KEY_LOCKSCREEN_WIDGET_VERTICAL_OFFSET, value.verticalOffsetDp)
            .putInt(KEY_LOCKSCREEN_WIDGET_SCALE, value.scalePercent)
            .putBoolean(KEY_LOCKSCREEN_WIDGET_INTERACTIVE, value.interactiveEnabled)
            .putBoolean(KEY_LOCKSCREEN_WIDGET_FOLLOW_CLOCK_BOTTOM, value.followClockBottom)
            .putString(KEY_LOCKSCREEN_WIDGET_WORLD_CLOCK_ZONE, value.worldClockZoneId)
            .putString(KEY_LOCKSCREEN_WIDGET_WORLD_CLOCK_LABEL, value.worldClockLabel)
            .putString(KEY_LOCKSCREEN_WIDGET_COUNTDOWN_TITLE, value.countdownTitle)
            .putString(KEY_LOCKSCREEN_WIDGET_COUNTDOWN_DATE, value.countdownDate)
            .putString(KEY_LOCKSCREEN_WIDGET_MEMO_TITLE, value.memoTitle)
            .putString(KEY_LOCKSCREEN_WIDGET_MEMO_CONTENT, value.memoContent)
            .apply()
    }
}
