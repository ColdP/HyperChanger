// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.graphics.Point
import kotlin.math.roundToInt

internal const val CLEAR_FROST_STYLE_SYSTEM = "system"
internal const val CLEAR_FROST_STYLE_CUSTOM = "custom"
internal const val CLEAR_FROST_DEFAULT_LIGHT_STYLE = "Colored_Thick_Light"
internal const val CLEAR_FROST_DEFAULT_DARK_STYLE = "Colored_Extra_Thin_Dark"

internal const val CLEAR_FROST_STROKE_SMALL = "small"
internal const val CLEAR_FROST_STROKE_MIDDLE = "middle"
internal const val CLEAR_FROST_STROKE_BIG = "big"
internal const val CLEAR_FROST_STROKE_ANGLE45 = "angle45"
internal const val CLEAR_FROST_STROKE_WIDE = "wide"
internal const val CLEAR_FROST_STROKE_ANGLE45_WIDE = "angle45_wide"
internal const val CLEAR_FROST_STROKE_CUSTOM = "custom"

internal const val CLEAR_FROST_CC_THEME_SYSTEM = 0
internal const val CLEAR_FROST_CC_THEME_LIGHT = 1
internal const val CLEAR_FROST_CC_THEME_DARK = 2
internal const val CLEAR_FROST_CC_THEME_INVERSE = 3

private const val BLEND_SRC_OVER = 3
private const val BLEND_OVERLAY = 15
private const val BLEND_COLOR_DODGE = 18
private const val BLEND_COLOR_BURN = 19
private const val BLEND_HARD_LIGHT = 20
private const val BLEND_SOFT_LIGHT = 21
private const val BLEND_LUMINOSITY = 28
private const val BLEND_PLUS_DARKER = 120
private const val BLEND_PLUS_LIGHTER = 121

internal const val CLEAR_FROST_DEFAULT_LIGHT_COLOR_1 = 0xE6747474.toInt()
internal const val CLEAR_FROST_DEFAULT_LIGHT_COLOR_2 = 0x99525252.toInt()
internal const val CLEAR_FROST_DEFAULT_LIGHT_COLOR_3 = 0x33D1D1D1
internal const val CLEAR_FROST_DEFAULT_DARK_COLOR_1 = 0xC7CCCCCC.toInt()
internal const val CLEAR_FROST_DEFAULT_DARK_COLOR_2 = 0x2E525252
internal const val CLEAR_FROST_DEFAULT_DARK_COLOR_3 = 0x00000000
internal const val CLEAR_FROST_DEFAULT_SHADE_BLEND_COLOR = 0x52808080

internal data class ClearFrostMaterialStyle(
    val id: String,
    val titleKey: String,
    val defaultTitle: String,
    val colors: IntArray,
    val modes: IntArray,
)

internal val clearFrostLightMaterialStyles: List<ClearFrostMaterialStyle> = listOf(
    ClearFrostMaterialStyle(CLEAR_FROST_STYLE_SYSTEM, "clearFrostStyleSystem", "跟随系统原值", intArrayOf(), intArrayOf()),
    ClearFrostMaterialStyle("Colored_Extra_Thin_Light", "clearFrostColoredExtraThin", "彩色 · 超轻", intArrayOf(-2140772762, 653390321, 449958353), intArrayOf(BLEND_OVERLAY, BLEND_COLOR_DODGE, BLEND_SRC_OVER)),
    ClearFrostMaterialStyle("Colored_Thin_Light", "clearFrostColoredThin", "彩色 · 轻盈", intArrayOf(-1719697537, -2130772226), intArrayOf(BLEND_OVERLAY, BLEND_SOFT_LIGHT)),
    ClearFrostMaterialStyle("Colored_Regular_Light", "clearFrostColoredRegular", "彩色 · 适中", intArrayOf(-2052675930, 486539263), intArrayOf(BLEND_OVERLAY, BLEND_PLUS_LIGHTER)),
    ClearFrostMaterialStyle("Colored_Thick_Light", "clearFrostColoredThick", "彩色 · 厚实", intArrayOf(-428575628, -1722658222, 869388753), intArrayOf(BLEND_OVERLAY, BLEND_COLOR_DODGE, BLEND_SRC_OVER)),
    ClearFrostMaterialStyle("Colored_Extra_Thick_Light", "clearFrostColoredExtraThick", "彩色 · 超厚", intArrayOf(1301254031, 1807794368), intArrayOf(BLEND_PLUS_LIGHTER, BLEND_COLOR_DODGE)),
    ClearFrostMaterialStyle("Pured_Extra_Thin_Light", "clearFrostPuredExtraThin", "纯色 · 超轻", intArrayOf(-855638017, 1593835519, 620756991), intArrayOf(BLEND_COLOR_BURN, BLEND_PLUS_LIGHTER, BLEND_SRC_OVER)),
    ClearFrostMaterialStyle("Pured_Thin_Light", "clearFrostPuredThin", "纯色 · 轻盈", intArrayOf(812477805, 1593835519, 1728053247), intArrayOf(BLEND_PLUS_LIGHTER, BLEND_PLUS_LIGHTER, BLEND_SRC_OVER)),
    ClearFrostMaterialStyle("Pured_Regular_Light", "clearFrostPuredRegular", "纯色 · 适中", intArrayOf(872020473, -1275068417), intArrayOf(BLEND_OVERLAY, BLEND_HARD_LIGHT)),
    ClearFrostMaterialStyle("Pured_Thick_Light", "clearFrostPuredThick", "纯色 · 厚实", intArrayOf(-1711276033, -1073741825), intArrayOf(BLEND_OVERLAY, BLEND_SRC_OVER)),
    ClearFrostMaterialStyle("Pured_Extra_Thick_Light", "clearFrostPuredExtraThick", "纯色 · 超厚", intArrayOf(1728053247, -1711933963), intArrayOf(BLEND_PLUS_LIGHTER, BLEND_SRC_OVER)),
    ClearFrostMaterialStyle("Pured_Thin_Glass_Light", "clearFrostPuredThinGlass", "清透玻璃 · 轻盈", intArrayOf(83886080, -1711276033, 1728053247), intArrayOf(BLEND_PLUS_DARKER, BLEND_SOFT_LIGHT, BLEND_HARD_LIGHT)),
    ClearFrostMaterialStyle("Pured_Regular_Glass_Light", "clearFrostPuredRegularGlass", "清透玻璃 · 适中", intArrayOf(83886080, -1711276033, -1711276033), intArrayOf(BLEND_PLUS_DARKER, BLEND_SOFT_LIGHT, BLEND_HARD_LIGHT)),
    ClearFrostMaterialStyle("Pured_Thick_Glass_Light", "clearFrostPuredThickGlass", "清透玻璃 · 厚实", intArrayOf(637534208, -855638017, -855638017), intArrayOf(BLEND_PLUS_DARKER, BLEND_SOFT_LIGHT, BLEND_HARD_LIGHT)),
    ClearFrostMaterialStyle("Overlay_Extra_Thin_Light", "clearFrostOverlayExtraThin", "叠加 · 超轻", intArrayOf(261724569), intArrayOf(BLEND_LUMINOSITY)),
    ClearFrostMaterialStyle("Overlay_Thin_Light", "clearFrostOverlayThin", "叠加 · 轻盈", intArrayOf(1301714582, 442918502), intArrayOf(BLEND_LUMINOSITY, BLEND_PLUS_DARKER)),
    ClearFrostMaterialStyle("Overlay_Regular_Light", "clearFrostOverlayRegular", "叠加 · 适中", intArrayOf(1301714582, 0x1A000000), intArrayOf(BLEND_LUMINOSITY, BLEND_PLUS_DARKER)),
    ClearFrostMaterialStyle("Overlay_Thick_Light", "clearFrostOverlayThick", "叠加 · 厚实", intArrayOf(-1474355425, -6645094), intArrayOf(BLEND_LUMINOSITY, BLEND_OVERLAY)),
    ClearFrostMaterialStyle("Overlay_Extra_Thick_Light", "clearFrostOverlayExtraThick", "叠加 · 超厚", intArrayOf(-866033311, 1291845632), intArrayOf(BLEND_LUMINOSITY, BLEND_COLOR_BURN)),
    ClearFrostMaterialStyle("Info_Extra_Thin_Light", "clearFrostInfoExtraThin", "信息 · 超轻", intArrayOf(1001501105), intArrayOf(BLEND_PLUS_DARKER)),
    ClearFrostMaterialStyle("Info_Thin_Light", "clearFrostInfoThin", "信息 · 轻盈", intArrayOf(-2137483112), intArrayOf(BLEND_PLUS_LIGHTER)),
    ClearFrostMaterialStyle("Info_Regular_Light", "clearFrostInfoRegular", "信息 · 适中", intArrayOf(-1281845096), intArrayOf(BLEND_PLUS_LIGHTER)),
    ClearFrostMaterialStyle("Info_Thick_Light", "clearFrostInfoThick", "信息 · 厚实", intArrayOf(-6776680), intArrayOf(BLEND_PLUS_LIGHTER)),
    ClearFrostMaterialStyle("Info_Colored_Regular", "clearFrostInfoColoredRegular", "信息 · 彩色适中", intArrayOf(-6579301, 268435455), intArrayOf(BLEND_COLOR_DODGE, BLEND_PLUS_LIGHTER)),
    ClearFrostMaterialStyle(CLEAR_FROST_STYLE_CUSTOM, "clearFrostStyleCustom", "自定义混色", intArrayOf(), intArrayOf()),
)

internal val clearFrostDarkMaterialStyles: List<ClearFrostMaterialStyle> = listOf(
    ClearFrostMaterialStyle(CLEAR_FROST_STYLE_SYSTEM, "clearFrostStyleSystem", "跟随系统原值", intArrayOf(), intArrayOf()),
    ClearFrostMaterialStyle("Colored_Extra_Thin_Dark", "clearFrostColoredExtraThin", "彩色 · 超轻", intArrayOf(-942879540, 777146962), intArrayOf(BLEND_COLOR_BURN, BLEND_SRC_OVER)),
    ClearFrostMaterialStyle("Colored_Thin_Dark", "clearFrostColoredThin", "彩色 · 轻盈", intArrayOf(442918502, 861427800), intArrayOf(BLEND_COLOR_BURN, BLEND_PLUS_DARKER)),
    ClearFrostMaterialStyle("Colored_Regular_Dark", "clearFrostColoredRegular", "彩色 · 适中", intArrayOf(1879048192, 335544320), intArrayOf(BLEND_OVERLAY, BLEND_SRC_OVER)),
    ClearFrostMaterialStyle("Colored_Thick_Dark", "clearFrostColoredThick", "彩色 · 厚实", intArrayOf(1719105399, 863270004, 855638016), intArrayOf(BLEND_COLOR_BURN, BLEND_OVERLAY, BLEND_SRC_OVER)),
    ClearFrostMaterialStyle("Colored_Extra_Thick_Dark", "clearFrostColoredExtraThick", "彩色 · 超厚", intArrayOf(1718973813, 1640022208), intArrayOf(BLEND_PLUS_DARKER, BLEND_COLOR_BURN)),
    ClearFrostMaterialStyle("Pured_Extra_Thin_Dark", "clearFrostPuredExtraThin", "纯色 · 超轻", intArrayOf(-428838800, -871099372), intArrayOf(BLEND_OVERLAY, BLEND_SRC_OVER)),
    ClearFrostMaterialStyle("Pured_Thin_Dark", "clearFrostPuredThin", "纯色 · 轻盈", intArrayOf(-1803715203, 1711276032), intArrayOf(BLEND_PLUS_DARKER, BLEND_SRC_OVER)),
    ClearFrostMaterialStyle("Pured_Regular_Dark", "clearFrostPuredRegular", "纯色 · 适中", intArrayOf(1962934272, 1375731712), intArrayOf(BLEND_COLOR_BURN, BLEND_SRC_OVER)),
    ClearFrostMaterialStyle("Pured_Thick_Dark", "clearFrostPuredThick", "纯色 · 厚实", intArrayOf(1291845632, -2146299374), intArrayOf(BLEND_COLOR_BURN, BLEND_SRC_OVER)),
    ClearFrostMaterialStyle("Pured_Extra_Thick_Dark", "clearFrostPuredExtraThick", "纯色 · 超厚", intArrayOf(-870770407, 1412510001), intArrayOf(BLEND_LUMINOSITY, BLEND_PLUS_LIGHTER)),
    ClearFrostMaterialStyle("Pured_Thin_Glass_Dark", "clearFrostPuredThinGlass", "清透玻璃 · 轻盈", intArrayOf(0x1A000000, 1716934230, -1723908289), intArrayOf(BLEND_PLUS_DARKER, BLEND_LUMINOSITY, BLEND_OVERLAY)),
    ClearFrostMaterialStyle("Pured_Regular_Glass_Dark", "clearFrostPuredRegularGlass", "清透玻璃 · 适中", intArrayOf(0x1A000000, -1720947604, -1727658490), intArrayOf(BLEND_PLUS_DARKER, BLEND_LUMINOSITY, BLEND_SOFT_LIGHT)),
    ClearFrostMaterialStyle("Pured_Thick_Glass_Dark", "clearFrostPuredThickGlass", "清透玻璃 · 厚实", intArrayOf(0x1A000000, -1722329257, -1727658490), intArrayOf(BLEND_PLUS_DARKER, BLEND_LUMINOSITY, BLEND_SOFT_LIGHT)),
    ClearFrostMaterialStyle("Overlay_Extra_Thin_Dark", "clearFrostOverlayExtraThin", "叠加 · 超轻", intArrayOf(1965500199), intArrayOf(BLEND_LUMINOSITY)),
    ClearFrostMaterialStyle("Overlay_Thick_Dark", "clearFrostOverlayThick", "叠加 · 厚实", intArrayOf(1723052979, -1637456282), intArrayOf(BLEND_LUMINOSITY, BLEND_PLUS_DARKER)),
    ClearFrostMaterialStyle("Info_Extra_Thin_Dark", "clearFrostInfoExtraThin", "信息 · 超轻", intArrayOf(1001501105), intArrayOf(BLEND_PLUS_LIGHTER)),
    ClearFrostMaterialStyle("Info_Thin_Dark", "clearFrostInfoThin", "信息 · 轻盈", intArrayOf(-2137483112), intArrayOf(BLEND_PLUS_DARKER)),
    ClearFrostMaterialStyle("Info_Regular_Dark", "clearFrostInfoRegular", "信息 · 适中", intArrayOf(-1281845096), intArrayOf(BLEND_PLUS_DARKER)),
    ClearFrostMaterialStyle("Info_Thick_Dark", "clearFrostInfoThick", "信息 · 厚实", intArrayOf(-6776680), intArrayOf(BLEND_PLUS_DARKER)),
    ClearFrostMaterialStyle(CLEAR_FROST_STYLE_CUSTOM, "clearFrostStyleCustom", "自定义混色", intArrayOf(), intArrayOf()),
)

internal val clearFrostSelectableLightStyles: List<ClearFrostMaterialStyle> =
    clearFrostLightMaterialStyles.filterNot { it.id == CLEAR_FROST_STYLE_CUSTOM }

internal val clearFrostSelectableDarkStyles: List<ClearFrostMaterialStyle> =
    clearFrostDarkMaterialStyles.filterNot { it.id == CLEAR_FROST_STYLE_CUSTOM }

private val clearFrostLightStyleMap = clearFrostLightMaterialStyles.associateBy { it.id }
private val clearFrostDarkStyleMap = clearFrostDarkMaterialStyles.associateBy { it.id }

internal val clearFrostStrokePresets: List<Pair<String, String>> = listOf(
    CLEAR_FROST_STROKE_SMALL to "clearFrostStrokeSmall",
    CLEAR_FROST_STROKE_MIDDLE to "clearFrostStrokeMiddle",
    CLEAR_FROST_STROKE_BIG to "clearFrostStrokeBig",
    CLEAR_FROST_STROKE_ANGLE45 to "clearFrostStrokeAngle45",
    CLEAR_FROST_STROKE_WIDE to "clearFrostStrokeWide",
    CLEAR_FROST_STROKE_ANGLE45_WIDE to "clearFrostStrokeAngle45Wide",
    CLEAR_FROST_STROKE_CUSTOM to "clearFrostStrokeCustom",
)

private val STROKE_SMALL_LIGHT = floatArrayOf(0.8f, 180.0f, 1.0f, 1.0f, 1.0f, 0.05f, 2.6f, 0.5f, 0.5f, -0.5f, 1.0f, 1.0f, 1.0f, 0.6f, 0.5f, 0.95f, -0.5f, 1.0f, 1.0f, 1.0f, 0.35f)
private val STROKE_MIDDLE_LIGHT = floatArrayOf(0.8f, 180.0f, 1.0f, 1.0f, 1.0f, 0.05f, 2.8f, 0.5f, 0.5f, -0.5f, 1.0f, 1.0f, 1.0f, 0.4f, 0.5f, 0.8f, -0.5f, 1.0f, 1.0f, 1.0f, 0.25f)
private val STROKE_BIG_LIGHT = floatArrayOf(0.8f, 180.0f, 1.0f, 1.0f, 1.0f, 0.05f, 3.5f, 0.5f, 0.5f, -0.5f, 1.0f, 1.0f, 1.0f, 0.3f, 0.5f, 0.6f, -0.5f, 1.0f, 1.0f, 1.0f, 0.2f)
private val STROKE_SMALL_DARK = floatArrayOf(0.8f, 180.0f, 1.0f, 1.0f, 1.0f, 0.08f, 2.3f, 0.5f, 0.5f, -0.5f, 1.0f, 1.0f, 1.0f, 0.6f, 0.5f, 0.95f, -0.36f, 1.0f, 1.0f, 1.0f, 0.25f)
private val STROKE_MIDDLE_DARK = floatArrayOf(0.8f, 180.0f, 1.0f, 1.0f, 1.0f, 0.06f, 2.0f, 0.5f, 0.5f, -0.5f, 1.0f, 1.0f, 1.0f, 0.5f, 0.5f, 0.8f, -0.5f, 1.0f, 1.0f, 1.0f, 0.25f)
private val STROKE_BIG_DARK = floatArrayOf(0.8f, 180.0f, 1.0f, 1.0f, 1.0f, 0.05f, 1.7f, 0.5f, 0.5f, -0.5f, 1.0f, 1.0f, 1.0f, 0.4f, 0.5f, 0.6f, -0.5f, 1.0f, 1.0f, 1.0f, 0.25f)

internal val CLEAR_FROST_EMPTY_STROKE = FloatArray(21)

internal data class ClearFrostSettings(
    val enabled: Boolean = false,
    val controlCenterThemeMode: Int = CLEAR_FROST_CC_THEME_SYSTEM,
    val separateScopes: Boolean = false,
    val lightStyle: String = "Colored_Thick_Light",
    val darkStyle: String = "Colored_Extra_Thin_Dark",
    val notificationLightStyle: String = "Colored_Thick_Light",
    val notificationDarkStyle: String = "Colored_Extra_Thin_Dark",
    val controlCenterLightStyle: String = "Colored_Thick_Light",
    val controlCenterDarkStyle: String = "Colored_Extra_Thin_Dark",
    val customLightColor1: Int = CLEAR_FROST_DEFAULT_LIGHT_COLOR_1,
    val customLightColor2: Int = CLEAR_FROST_DEFAULT_LIGHT_COLOR_2,
    val customLightColor3: Int = CLEAR_FROST_DEFAULT_LIGHT_COLOR_3,
    val customDarkColor1: Int = CLEAR_FROST_DEFAULT_DARK_COLOR_1,
    val customDarkColor2: Int = CLEAR_FROST_DEFAULT_DARK_COLOR_2,
    val customDarkColor3: Int = CLEAR_FROST_DEFAULT_DARK_COLOR_3,
    val elementOpacity: Int = 100,
    val strokeEnabled: Boolean = true,
    val strokePreset: String = CLEAR_FROST_STROKE_SMALL,
    val strokeNormalWidth: Float = 2.6f,
    val strokeBloomWidth: Float = 0.8f,
    val strokeBloomDegree: Int = 180,
    val strokeBloomAlpha: Int = 5,
    val strokeSource1Alpha: Int = 60,
    val strokeSource2Alpha: Int = 35,
    val strokeSource1X: Float = 0.5f,
    val strokeSource1Y: Float = 0.5f,
    val strokeSource1Z: Float = -0.5f,
    val strokeSource2X: Float = 0.5f,
    val strokeSource2Y: Float = 0.95f,
    val strokeSource2Z: Float = -0.5f,
    val shadowEnabled: Boolean = false,
    val shadowAlpha: Int = 35,
    val shadowOffsetY: Int = 30,
    val shadowRadius: Int = 26,
    val backgroundBlur: Int = 100,
    val backgroundScale: Int = 100,
    val backgroundOpacity: Int = 100,
    val customShadeBlendEnabled: Boolean = false,
    val customShadeBlendColor: Int = CLEAR_FROST_DEFAULT_SHADE_BLEND_COLOR,
) {
    fun normalized(): ClearFrostSettings = copy(
        controlCenterThemeMode = controlCenterThemeMode.coerceIn(CLEAR_FROST_CC_THEME_SYSTEM, CLEAR_FROST_CC_THEME_INVERSE),
        lightStyle = normalizeLightStyle(lightStyle),
        darkStyle = normalizeDarkStyle(darkStyle),
        notificationLightStyle = normalizeLightStyle(notificationLightStyle),
        notificationDarkStyle = normalizeDarkStyle(notificationDarkStyle),
        controlCenterLightStyle = normalizeLightStyle(controlCenterLightStyle),
        controlCenterDarkStyle = normalizeDarkStyle(controlCenterDarkStyle),
        elementOpacity = elementOpacity.coerceIn(0, 200),
        strokePreset = normalizeStrokePreset(strokePreset),
        strokeNormalWidth = strokeNormalWidth.coerceFiniteIn(0f, 8f, 2.6f),
        strokeBloomWidth = strokeBloomWidth.coerceFiniteIn(0f, 6f, 0.8f),
        strokeBloomDegree = strokeBloomDegree.coerceIn(0, 360),
        strokeBloomAlpha = strokeBloomAlpha.coerceIn(0, 100),
        strokeSource1Alpha = strokeSource1Alpha.coerceIn(0, 100),
        strokeSource2Alpha = strokeSource2Alpha.coerceIn(0, 100),
        strokeSource1X = strokeSource1X.coerceFiniteIn(-1f, 1f, 0.5f),
        strokeSource1Y = strokeSource1Y.coerceFiniteIn(-1f, 1f, 0.5f),
        strokeSource1Z = strokeSource1Z.coerceFiniteIn(-1f, 1f, -0.5f),
        strokeSource2X = strokeSource2X.coerceFiniteIn(-1f, 1f, 0.5f),
        strokeSource2Y = strokeSource2Y.coerceFiniteIn(-1f, 1f, 0.95f),
        strokeSource2Z = strokeSource2Z.coerceFiniteIn(-1f, 1f, -0.5f),
        shadowAlpha = shadowAlpha.coerceIn(0, 100),
        shadowOffsetY = shadowOffsetY.coerceIn(0, 80),
        shadowRadius = shadowRadius.coerceIn(0, 100),
        backgroundBlur = backgroundBlur.coerceIn(0, 200),
        backgroundScale = backgroundScale.coerceIn(0, 200),
        backgroundOpacity = backgroundOpacity.coerceIn(0, 200),
    )

    fun resetParameters(): ClearFrostSettings = ClearFrostSettings(enabled = enabled).normalized()

    fun usesCustomLightStyle(): Boolean =
        if (separateScopes) {
            notificationLightStyle == CLEAR_FROST_STYLE_CUSTOM || controlCenterLightStyle == CLEAR_FROST_STYLE_CUSTOM
        } else {
            lightStyle == CLEAR_FROST_STYLE_CUSTOM
        }

    fun usesCustomDarkStyle(): Boolean =
        if (separateScopes) {
            notificationDarkStyle == CLEAR_FROST_STYLE_CUSTOM || controlCenterDarkStyle == CLEAR_FROST_STYLE_CUSTOM
        } else {
            darkStyle == CLEAR_FROST_STYLE_CUSTOM
        }

    fun matchesParameters(other: ClearFrostSettings): Boolean =
        normalized().copy(enabled = false) == other.normalized().copy(enabled = false)
}

internal fun normalizeLightStyle(id: String): String =
    if (clearFrostLightStyleMap.containsKey(id)) id else "Colored_Thick_Light"

internal fun normalizeDarkStyle(id: String): String =
    if (clearFrostDarkStyleMap.containsKey(id)) id else "Colored_Extra_Thin_Dark"

internal fun normalizeStrokePreset(id: String): String =
    if (clearFrostStrokePresets.any { it.first == id }) id else CLEAR_FROST_STROKE_SMALL

private fun Float.coerceFiniteIn(min: Float, max: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(min, max) else fallback

internal fun resolveClearFrostIsLight(systemDark: Boolean, isControlCenter: Boolean, settings: ClearFrostSettings): Boolean {
    if (!isControlCenter) return !systemDark
    return when (settings.controlCenterThemeMode) {
        CLEAR_FROST_CC_THEME_LIGHT -> true
        CLEAR_FROST_CC_THEME_DARK -> false
        CLEAR_FROST_CC_THEME_INVERSE -> systemDark
        else -> !systemDark
    }
}

/**
 * Returns the blend points to apply for an element surface, or null when the configured style is
 * [CLEAR_FROST_STYLE_SYSTEM] so callers can keep the host's original blend list (scaled by
 * [ClearFrostSettings.elementOpacity]).
 */
internal fun resolveClearFrostElementBlendPoints(
    settings: ClearFrostSettings,
    isLight: Boolean,
    isControlCenter: Boolean,
): ArrayList<Point>? {
    val styleId = if (settings.separateScopes) {
        if (isControlCenter) {
            if (isLight) settings.controlCenterLightStyle else settings.controlCenterDarkStyle
        } else {
            if (isLight) settings.notificationLightStyle else settings.notificationDarkStyle
        }
    } else {
        if (isLight) settings.lightStyle else settings.darkStyle
    }
    if (styleId == CLEAR_FROST_STYLE_SYSTEM) return null
    val rawPoints = if (styleId == CLEAR_FROST_STYLE_CUSTOM) {
        buildCustomBlendPoints(settings, isLight)
    } else {
        val recipe = if (isLight) clearFrostLightStyleMap[styleId] else clearFrostDarkStyleMap[styleId]
        if (recipe == null || recipe.colors.isEmpty()) return null
        val count = minOf(recipe.colors.size, recipe.modes.size)
        ArrayList<Point>(count).apply {
            for (i in 0 until count) add(Point(recipe.colors[i], recipe.modes[i]))
        }
    }
    if (rawPoints.isEmpty()) return null
    val opacity = settings.elementOpacity
    if (opacity == 100) return rawPoints
    return ArrayList<Point>(rawPoints.size).apply {
        rawPoints.forEach { add(Point(softGlassColorAlpha(it.x, opacity), it.y)) }
    }
}

private fun buildCustomBlendPoints(settings: ClearFrostSettings, isLight: Boolean): ArrayList<Point> {
    val points = ArrayList<Point>(3)
    if (isLight) {
        if ((settings.customLightColor1 ushr 24) != 0) points.add(Point(settings.customLightColor1, BLEND_OVERLAY))
        if ((settings.customLightColor2 ushr 24) != 0) points.add(Point(settings.customLightColor2, BLEND_COLOR_DODGE))
        if ((settings.customLightColor3 ushr 24) != 0) points.add(Point(settings.customLightColor3, BLEND_SRC_OVER))
    } else {
        if ((settings.customDarkColor1 ushr 24) != 0) points.add(Point(settings.customDarkColor1, BLEND_COLOR_BURN))
        if ((settings.customDarkColor2 ushr 24) != 0) points.add(Point(settings.customDarkColor2, BLEND_SRC_OVER))
        if ((settings.customDarkColor3 ushr 24) != 0) points.add(Point(settings.customDarkColor3, BLEND_SRC_OVER))
    }
    return points
}

internal fun buildClearFrostBloomStrokePx(
    settings: ClearFrostSettings,
    isLight: Boolean,
    density: Float,
): FloatArray {
    if (!settings.strokeEnabled) return CLEAR_FROST_EMPTY_STROKE
    fun strokeVariant(diagonal45: Boolean, wideField: Boolean): FloatArray =
        (if (isLight) STROKE_SMALL_LIGHT else STROKE_SMALL_DARK).copyOf().apply {
            if (wideField) this[6] = 5.0f
            if (diagonal45) {
                this[7] = 0.0f
                this[8] = 0.0f
                this[9] = -0.5f
                this[14] = 1.0f
                this[15] = 1.0f
                this[16] = -0.5f
            }
        }
    val dpParams = when (settings.strokePreset) {
        CLEAR_FROST_STROKE_MIDDLE -> (if (isLight) STROKE_MIDDLE_LIGHT else STROKE_MIDDLE_DARK).copyOf()
        CLEAR_FROST_STROKE_BIG -> (if (isLight) STROKE_BIG_LIGHT else STROKE_BIG_DARK).copyOf()
        CLEAR_FROST_STROKE_ANGLE45 -> strokeVariant(diagonal45 = true, wideField = false)
        CLEAR_FROST_STROKE_WIDE -> strokeVariant(diagonal45 = false, wideField = true)
        CLEAR_FROST_STROKE_ANGLE45_WIDE -> strokeVariant(diagonal45 = true, wideField = true)
        CLEAR_FROST_STROKE_CUSTOM -> floatArrayOf(
            settings.strokeBloomWidth,
            settings.strokeBloomDegree.toFloat(),
            1.0f, 1.0f, 1.0f, settings.strokeBloomAlpha / 100f,
            settings.strokeNormalWidth,
            settings.strokeSource1X, settings.strokeSource1Y, settings.strokeSource1Z,
            1.0f, 1.0f, 1.0f, settings.strokeSource1Alpha / 100f,
            settings.strokeSource2X, settings.strokeSource2Y, settings.strokeSource2Z,
            1.0f, 1.0f, 1.0f, settings.strokeSource2Alpha / 100f,
        )
        else -> (if (isLight) STROKE_SMALL_LIGHT else STROKE_SMALL_DARK).copyOf()
    }
    val safeDensity = if (density.isFinite() && density > 0f) density else 3f
    dpParams[0] = ((dpParams[0] * safeDensity) + 0.5f).toInt().toFloat()
    dpParams[6] = ((dpParams[6] * safeDensity) + 0.5f).toInt().toFloat()
    return dpParams
}

internal fun resolveClearFrostShadowColor(settings: ClearFrostSettings): Int {
    val alpha = (settings.shadowAlpha.coerceIn(0, 100) * 255 / 100f).roundToInt().coerceIn(0, 255)
    return alpha shl 24
}

internal fun resolveClearFrostShadeBackdropPoints(
    original: ArrayList<Point>,
    settings: ClearFrostSettings,
): ArrayList<Point> {
    if (settings.customShadeBlendEnabled && original.isNotEmpty()) {
        // Preserve expansion animation progress: scale the custom tint by the ratio of the
        // host's current alpha relative to a full shade alpha, and honor backgroundOpacity.
        val maxAlpha = original.maxOf { (it.x ushr 24) and 0xFF }
        if (maxAlpha == 0) {
            return ArrayList<Point>(1).apply { add(Point(0, BLEND_LUMINOSITY)) }
        }
        val baseTintAlpha = (settings.customShadeBlendColor ushr 24) and 0xFF
        val referenceHostAlpha = 0x52.coerceAtLeast(maxAlpha)
        val fraction = (maxAlpha / referenceHostAlpha.toFloat()).coerceIn(0f, 1f)
        val animatedAlpha = (baseTintAlpha * fraction).roundToInt().coerceIn(0, 255)
        val animatedColor = (settings.customShadeBlendColor and 0x00FFFFFF) or (animatedAlpha shl 24)
        val finalColor = softGlassColorAlpha(animatedColor, settings.backgroundOpacity)
        return ArrayList<Point>(1).apply { add(Point(finalColor, BLEND_LUMINOSITY)) }
    }
    return ArrayList<Point>(original.size).apply {
        original.forEach { add(Point(softGlassColorAlpha(it.x, settings.backgroundOpacity), it.y)) }
    }
}
