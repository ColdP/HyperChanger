// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

data class SettingsAppearanceSource(
    val slot: String,
    val uri: Uri,
    val mime: String,
    val size: Long,
    val modified: Long,
    val enabled: Boolean,
    val opacity: Int,
    val blur: Float,
    val fontMode: Int,
    val scale: Int,
    val logoMode: Int,
    val lightCardOpacity: Int,
    val tutorialCardEnabled: Boolean,
    val tutorialCardTitle: String,
    val tutorialCardSlogan: String,
    val tutorialCardAuthor: String,
    val tutorialCardImageScale: Int,
    val tutorialCardLogoScale: Int,
    val tutorialCardLogoVerticalOffset: Int,
    val tutorialCardImageLogoSpacing: Int,
    val tutorialCardTextSpacing: Int,
    val tutorialCardInfoCardsEnabled: Boolean,
    val tutorialCardBackgroundBlur: Float,
    val tutorialCardBackgroundVerticalOffset: Int,
    val tutorialCardBackgroundHorizontalOffset: Int,
    val tutorialCardBackgroundScale: Int,
    val deviceInterfaceStyle: Int,
    val style2ImageScale: Int,
    val style2LogoVerticalOffset: Int,
    val style2ImageLogoSpacing: Int,
    val style2LogoHorizontalOffset: Int,
    val style2LogoAlignment: Int,
    val style2LogoVersionSpacing: Int,
    val style2TextEnabled: Boolean,
    val style2Text: String,
    val style2TextIndependent: Boolean,
    val style2TextScale: Int,
    val style2TextPosition: Int,
    val style2TextSpacingAbove: Int,
    val style2TextSpacingBelow: Int,
    val style2TextAlignment: Int,
    val style2TextHorizontalOffset: Int,
    val style2TextHorizontalOffsetLeft: Int,
    val style2TextHorizontalOffsetCenter: Int,
    val style2TextHorizontalOffsetRight: Int,
    val style2TextVerticalOffset: Int,
    val style2TextVerticalOffsetLeft: Int,
    val style2TextVerticalOffsetCenter: Int,
    val style2TextVerticalOffsetRight: Int,
    val style2LogoColorMode: Int,
    val style2VersionColorMode: Int,
    val style2TextColorMode: Int,
    val style2BackgroundBlur: Float,
    val style2BackgroundVerticalOffset: Int,
    val style2BackgroundHorizontalOffset: Int,
    val style2BackgroundScale: Int,
) {
    val exists: Boolean get() = enabled && size >= 0L
    val isVideo: Boolean get() = mime.startsWith("video/")
    fun cacheKey(): String = "$slot:$mime:$size:$modified:$enabled:$opacity:$blur:$fontMode:$scale:$logoMode:$lightCardOpacity:$tutorialCardEnabled:$tutorialCardTitle:$tutorialCardSlogan:$tutorialCardAuthor:$tutorialCardImageScale:$tutorialCardLogoScale:$tutorialCardLogoVerticalOffset:$tutorialCardImageLogoSpacing:$tutorialCardTextSpacing:$tutorialCardInfoCardsEnabled:$tutorialCardBackgroundBlur:$tutorialCardBackgroundVerticalOffset:$tutorialCardBackgroundHorizontalOffset:$tutorialCardBackgroundScale:$deviceInterfaceStyle:$style2ImageScale:$style2LogoVerticalOffset:$style2ImageLogoSpacing:$style2LogoHorizontalOffset:$style2LogoAlignment:$style2LogoVersionSpacing:$style2TextEnabled:$style2Text:$style2TextIndependent:$style2TextScale:$style2TextPosition:$style2TextSpacingAbove:$style2TextSpacingBelow:$style2TextAlignment:$style2TextHorizontalOffset:$style2TextHorizontalOffsetLeft:$style2TextHorizontalOffsetCenter:$style2TextHorizontalOffsetRight:$style2TextVerticalOffset:$style2TextVerticalOffsetLeft:$style2TextVerticalOffsetCenter:$style2TextVerticalOffsetRight:$style2LogoColorMode:$style2VersionColorMode:$style2TextColorMode:$style2BackgroundBlur:$style2BackgroundVerticalOffset:$style2BackgroundHorizontalOffset:$style2BackgroundScale"
}

fun SettingsAppearanceSource.style2TextHorizontalOffsetForAlignment(): Int = when (style2TextAlignment.coerceIn(0, 2)) {
    1 -> style2TextHorizontalOffsetLeft
    2 -> style2TextHorizontalOffsetRight
    else -> style2TextHorizontalOffsetCenter
}

fun SettingsAppearanceSource.style2TextVerticalOffsetForAlignment(): Int = when (style2TextAlignment.coerceIn(0, 2)) {
    1 -> style2TextVerticalOffsetLeft
    2 -> style2TextVerticalOffsetRight
    else -> style2TextVerticalOffsetCenter
}

object SettingsAppearanceSources {
    private data class CacheEntry(val source: SettingsAppearanceSource, val loadedAt: Long)

    // Settings appearance hooks run on the Settings main thread. Avoid doing
    // provider IPC for every resource and view callback, while keeping updates
    // visible shortly after the module settings are changed.
    private const val CACHE_TTL_MS = 750L
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    fun uri(slot: String) = Uri.Builder()
        .scheme("content")
        .authority(SETTINGS_APPEARANCE_AUTHORITY)
        .appendPath(slot)
        .build()

    fun lockscreenScheduleUri() = uri(SettingsAppearanceProvider.LOCKSCREEN_SCHEDULE_PATH)

    fun query(context: Context, slot: String): SettingsAppearanceSource {
        val key = "${context.packageName}:$slot"
        val now = SystemClock.elapsedRealtime()
        cache[key]?.let { entry ->
            if (now - entry.loadedAt < CACHE_TTL_MS) return entry.source
        }
        val uri = uri(slot)
        val source = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val mime = cursor.string(SettingsAppearanceProvider.COLUMN_MIME)
                SettingsAppearanceSource(
                    slot = slot,
                    uri = uri,
                    mime = mime,
                    size = cursor.long(SettingsAppearanceProvider.COLUMN_SIZE),
                    modified = cursor.long(SettingsAppearanceProvider.COLUMN_MODIFIED),
                    enabled = cursor.int(SettingsAppearanceProvider.COLUMN_ENABLED) != 0,
                    opacity = cursor.int(SettingsAppearanceProvider.COLUMN_OPACITY).coerceIn(0, 100),
                    blur = cursor.float(SettingsAppearanceProvider.COLUMN_BLUR).coerceIn(0f, 20f),
                    fontMode = cursor.int(SettingsAppearanceProvider.COLUMN_FONT).coerceIn(0, 2),
                    scale = cursor.int(SettingsAppearanceProvider.COLUMN_SCALE).coerceIn(50, 200),
                    logoMode = cursor.int(SettingsAppearanceProvider.COLUMN_LOGO_MODE).coerceIn(LOGO_MODE_SYSTEM, LOGO_MODE_KEEP_ADVANCED_MATERIAL),
                    lightCardOpacity = cursor.int(SettingsAppearanceProvider.COLUMN_LIGHT_CARD_OPACITY).coerceIn(0, 100),
                    tutorialCardEnabled = cursor.int(SettingsAppearanceProvider.COLUMN_TUTORIAL_CARD_ENABLED) != 0,
                    tutorialCardTitle = cursor.string(SettingsAppearanceProvider.COLUMN_TUTORIAL_CARD_TITLE),
                    tutorialCardSlogan = cursor.string(SettingsAppearanceProvider.COLUMN_TUTORIAL_CARD_SLOGAN),
                    tutorialCardAuthor = cursor.string(SettingsAppearanceProvider.COLUMN_TUTORIAL_CARD_AUTHOR),
                    tutorialCardImageScale = cursor.int(SettingsAppearanceProvider.COLUMN_TUTORIAL_CARD_IMAGE_SCALE).coerceIn(40, 200),
                    tutorialCardLogoScale = cursor.int(SettingsAppearanceProvider.COLUMN_TUTORIAL_CARD_LOGO_SCALE).coerceIn(40, 200),
                    tutorialCardLogoVerticalOffset = cursor.int(SettingsAppearanceProvider.COLUMN_TUTORIAL_CARD_LOGO_VERTICAL_OFFSET).coerceIn(-120, 120),
                    tutorialCardImageLogoSpacing = cursor.int(SettingsAppearanceProvider.COLUMN_TUTORIAL_CARD_IMAGE_LOGO_SPACING).coerceIn(-120, 120),
                    tutorialCardTextSpacing = cursor.int(SettingsAppearanceProvider.COLUMN_TUTORIAL_CARD_TEXT_SPACING).coerceIn(-120, 120),
                    tutorialCardInfoCardsEnabled = cursor.int(SettingsAppearanceProvider.COLUMN_TUTORIAL_CARD_INFO_CARDS_ENABLED) != 0,
                    tutorialCardBackgroundBlur = cursor.float(SettingsAppearanceProvider.COLUMN_STYLE1_BACKGROUND_BLUR).coerceIn(0f, 25f),
                    tutorialCardBackgroundVerticalOffset = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE1_BACKGROUND_VERTICAL_OFFSET).coerceIn(-120, 120),
                    tutorialCardBackgroundHorizontalOffset = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE1_BACKGROUND_HORIZONTAL_OFFSET).coerceIn(-120, 120),
                    tutorialCardBackgroundScale = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE1_BACKGROUND_SCALE).coerceIn(40, 200),
                    deviceInterfaceStyle = cursor.int(SettingsAppearanceProvider.COLUMN_DEVICE_INTERFACE_STYLE).coerceIn(DEVICE_INTERFACE_STYLE_SYSTEM, DEVICE_INTERFACE_STYLE_TWO),
                    style2ImageScale = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_IMAGE_SCALE).coerceIn(40, 200),
                    style2LogoVerticalOffset = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_LOGO_VERTICAL_OFFSET).coerceIn(-120, 120),
                    style2ImageLogoSpacing = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_IMAGE_LOGO_SPACING).coerceIn(-50, 50),
                    style2LogoHorizontalOffset = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_LOGO_HORIZONTAL_OFFSET).coerceIn(-120, 120),
                    style2LogoAlignment = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_LOGO_ALIGNMENT).coerceIn(0, 2),
                    style2LogoVersionSpacing = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_LOGO_VERSION_SPACING).coerceIn(-120, 120),
                    style2TextEnabled = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_ENABLED) != 0,
                    style2Text = cursor.string(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT),
                    style2TextIndependent = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_INDEPENDENT) != 0,
                    style2TextScale = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_SCALE).coerceIn(40, 200),
                    style2TextPosition = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_POSITION).coerceIn(0, 1),
                    style2TextSpacingAbove = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_SPACING_ABOVE).coerceIn(-120, 120),
                    style2TextSpacingBelow = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_SPACING_BELOW).coerceIn(-120, 120),
                    style2TextAlignment = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_ALIGNMENT).coerceIn(0, 2),
                    style2TextHorizontalOffset = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET).coerceIn(-120, 120),
                    style2TextHorizontalOffsetLeft = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET_LEFT).coerceIn(-120, 120),
                    style2TextHorizontalOffsetCenter = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET_CENTER).coerceIn(-120, 120),
                    style2TextHorizontalOffsetRight = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_HORIZONTAL_OFFSET_RIGHT).coerceIn(-120, 120),
                    style2TextVerticalOffset = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_VERTICAL_OFFSET).coerceIn(-120, 120),
                    style2TextVerticalOffsetLeft = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_VERTICAL_OFFSET_LEFT).coerceIn(-120, 120),
                    style2TextVerticalOffsetCenter = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_VERTICAL_OFFSET_CENTER).coerceIn(-120, 120),
                    style2TextVerticalOffsetRight = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_VERTICAL_OFFSET_RIGHT).coerceIn(-120, 120),
                    style2LogoColorMode = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_LOGO_COLOR_MODE).coerceIn(0, 2),
                    style2VersionColorMode = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_VERSION_COLOR_MODE).coerceIn(0, 2),
                    style2TextColorMode = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_TEXT_COLOR_MODE).coerceIn(0, 2),
                    style2BackgroundBlur = cursor.float(SettingsAppearanceProvider.COLUMN_STYLE2_BACKGROUND_BLUR).coerceIn(0f, 25f),
                    style2BackgroundVerticalOffset = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_BACKGROUND_VERTICAL_OFFSET).coerceIn(-120, 120),
                    style2BackgroundHorizontalOffset = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_BACKGROUND_HORIZONTAL_OFFSET).coerceIn(-120, 120),
                    style2BackgroundScale = cursor.int(SettingsAppearanceProvider.COLUMN_STYLE2_BACKGROUND_SCALE).coerceIn(40, 200),
                )
            } ?: missing(slot, uri)
        }.getOrElse { missing(slot, uri) }
        cache[key] = CacheEntry(source, now)
        return source
    }

    fun invalidate(context: Context? = null, slot: String? = null) {
        when {
            context == null && slot == null -> cache.clear()
            context != null && slot != null -> cache.remove("${context.packageName}:$slot")
            context != null -> cache.keys.removeIf { it.startsWith("${context.packageName}:") }
            slot != null -> cache.keys.removeIf { it.endsWith(":$slot") }
        }
    }

    private fun missing(slot: String, uri: Uri) = SettingsAppearanceSource(
        slot = slot, uri = uri, mime = "", size = -1L, modified = -1L, enabled = false,
        opacity = 100, blur = 0f, fontMode = 0, scale = 100, logoMode = LOGO_MODE_SYSTEM,
        lightCardOpacity = 100, tutorialCardEnabled = false, tutorialCardTitle = "",
        tutorialCardSlogan = "", tutorialCardAuthor = "", tutorialCardImageScale = 100, tutorialCardLogoScale = 100,
        tutorialCardLogoVerticalOffset = 0, tutorialCardImageLogoSpacing = 0,
        tutorialCardTextSpacing = 0, tutorialCardInfoCardsEnabled = false,
        tutorialCardBackgroundBlur = 0f, tutorialCardBackgroundVerticalOffset = 0,
        tutorialCardBackgroundHorizontalOffset = 0, tutorialCardBackgroundScale = 100,
        deviceInterfaceStyle = DEVICE_INTERFACE_STYLE_SYSTEM, style2ImageScale = 100,
        style2LogoVerticalOffset = 0, style2ImageLogoSpacing = 0, style2LogoHorizontalOffset = 0,
        style2LogoAlignment = 1, style2LogoVersionSpacing = 0, style2TextEnabled = false,
        style2Text = "", style2TextIndependent = false, style2TextScale = 100,
        style2TextPosition = 0, style2TextSpacingAbove = 0, style2TextSpacingBelow = 0,
        style2TextAlignment = 0, style2TextHorizontalOffset = 0,
        style2TextHorizontalOffsetLeft = 0, style2TextHorizontalOffsetCenter = 0,
        style2TextHorizontalOffsetRight = 0, style2TextVerticalOffset = 0,
        style2TextVerticalOffsetLeft = 0, style2TextVerticalOffsetCenter = 0,
        style2TextVerticalOffsetRight = 0, style2BackgroundBlur = 0f,
        style2LogoColorMode = 0, style2VersionColorMode = 0, style2TextColorMode = 0,
        style2BackgroundVerticalOffset = 0, style2BackgroundHorizontalOffset = 0,
        style2BackgroundScale = 100,
    )

    private fun android.database.Cursor.index(name: String) = getColumnIndex(name)
    private fun android.database.Cursor.string(name: String): String = getString(index(name)).orEmpty()
    private fun android.database.Cursor.int(name: String): Int = getInt(index(name))
    private fun android.database.Cursor.float(name: String): Float = getFloat(index(name))
    private fun android.database.Cursor.long(name: String): Long = getLong(index(name))
}
