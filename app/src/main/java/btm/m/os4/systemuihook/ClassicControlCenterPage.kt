// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import btm.m.os4.systemuihook.InterfaceSwitchPreference as SwitchPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog as WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

private data class ClassicCcColorEditTarget(
    val title: String,
    val color: Int,
    val defaultColor: Int,
    val onSave: (Int) -> Unit,
)

@Composable
internal fun ClassicControlCenterPage(openPresets: () -> Unit, back: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    var colorEdit by remember { mutableStateOf<ClassicCcColorEditTarget?>(null) }

    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val changed = LocalNotificationSettingsChanged.current
    val store = remember(context) { ClassicControlCenterSettingsStore(context) }
    var value by remember { mutableStateOf(store.settings) }

    LaunchedEffect(service) {
        store.reload()
        service?.let(store::syncRemote)
        value = store.settings
    }

    DisposableEffect(store) {
        val preferences = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith(CLASSIC_CC_PREFIX)) {
                store.reload()
                value = store.settings
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val update: ((ClassicControlCenterSettings) -> ClassicControlCenterSettings) -> Unit = { transform ->
        store.update(service, transform)
        value = store.settings
        changed()
    }

    val updateSoftGlass: ((SoftGlassSettings) -> SoftGlassSettings) -> Unit = { transform ->
        update { it.copy(softGlass = transform(it.softGlass).copy(enabled = true).normalized()) }
    }

    val updateClearFrost: ((ClearFrostSettings) -> ClearFrostSettings) -> Unit = { transform ->
        update { it.copy(clearFrost = transform(it.clearFrost).copy(enabled = true).normalized()) }
    }

    var lastCfLightStyle by rememberSaveable {
        mutableStateOf(
            value.clearFrost.lightStyle.takeUnless { it == CLEAR_FROST_STYLE_CUSTOM } ?: CLEAR_FROST_DEFAULT_LIGHT_STYLE,
        )
    }
    var lastCfDarkStyle by rememberSaveable {
        mutableStateOf(
            value.clearFrost.darkStyle.takeUnless { it == CLEAR_FROST_STYLE_CUSTOM } ?: CLEAR_FROST_DEFAULT_DARK_STYLE,
        )
    }
    LaunchedEffect(value.clearFrost) {
        if (value.clearFrost.lightStyle != CLEAR_FROST_STYLE_CUSTOM) lastCfLightStyle = value.clearFrost.lightStyle
        if (value.clearFrost.darkStyle != CLEAR_FROST_STYLE_CUSTOM) lastCfDarkStyle = value.clearFrost.darkStyle
    }

    val cfCustomBlendEnabled =
        value.clearFrost.lightStyle == CLEAR_FROST_STYLE_CUSTOM || value.clearFrost.darkStyle == CLEAR_FROST_STYLE_CUSTOM

    val defaultTag = tr("clearFrostDefaultTag", "默认")
    val cfLightStyleLabels = clearFrostSelectableLightStyles.map { style ->
        val label = tr(style.titleKey, style.defaultTitle)
        if (style.id == CLEAR_FROST_DEFAULT_LIGHT_STYLE) "$label ($defaultTag)" else label
    }
    val cfDarkStyleLabels = clearFrostSelectableDarkStyles.map { style ->
        val label = tr(style.titleKey, style.defaultTitle)
        if (style.id == CLEAR_FROST_DEFAULT_DARK_STYLE) "$label ($defaultTag)" else label
    }
    val cfStrokePresetLabels = listOf(
        "${tr("clearFrostStrokeSmall", "小尺寸")} ($defaultTag)",
        tr("clearFrostStrokeMiddle", "中尺寸"),
        tr("clearFrostStrokeBig", "大尺寸"),
        tr("clearFrostStrokeAngle45", "45° 斜射光源"),
        tr("clearFrostStrokeWide", "宽光场"),
        tr("clearFrostStrokeAngle45Wide", "45° 斜射 + 宽光场"),
        tr("clearFrostStrokeCustom", "自定义"),
    )

    fun cfLightIndex(id: String): Int =
        clearFrostSelectableLightStyles.indexOfFirst { it.id == id }
            .takeIf { it >= 0 }
            ?: clearFrostSelectableLightStyles.indexOfFirst { it.id == CLEAR_FROST_DEFAULT_LIGHT_STYLE }.coerceAtLeast(0)

    fun cfDarkIndex(id: String): Int =
        clearFrostSelectableDarkStyles.indexOfFirst { it.id == id }
            .takeIf { it >= 0 }
            ?: clearFrostSelectableDarkStyles.indexOfFirst { it.id == CLEAR_FROST_DEFAULT_DARK_STYLE }.coerceAtLeast(0)

    val surfaceModeLabels = listOf(
        tr("classic_cc_surface_default", "系统原样"),
        tr("classic_cc_surface_solid", "现代纯色卡片"),
        tr("classic_cc_surface_clear_frost", "清透磨砂材质"),
        tr("classic_cc_surface_soft_glass", "柔光玻璃材质"),
        tr("classic_cc_surface_follow_system", "跟随系统材质（自动切换）"),
    )
    val handleStyleLabels = listOf(
        tr("classic_cc_handle_default", "系统默认箭头"),
        tr("classic_cc_handle_pill", "现代胶囊指示条"),
        tr("classic_cc_handle_hidden", "隐藏"),
    )
    val indicatorStyleLabels = listOf(
        tr("classic_cc_indicator_default", "系统默认圆点"),
        tr("classic_cc_indicator_pill", "现代胶囊指示器"),
        tr("classic_cc_indicator_hidden", "隐藏"),
    )
    val tileStyleLabels = listOf(
        tr("classic_cc_tile_style_squircle", "超椭圆圆角方块"),
        tr("classic_cc_tile_style_circle", "现代圆形按钮"),
        tr("classic_cc_tile_style_pill_card", "横向药丸卡片"),
        tr("classic_cc_tile_style_glass_card", "半透明玻璃卡片"),
    )
    val tileAccentLabels = listOf(
        tr("classic_cc_accent_monet", "跟随系统强调色 (Monet)"),
        tr("classic_cc_accent_hyper_blue", "经典 HyperOS 蓝"),
        tr("classic_cc_accent_custom", "自定义颜色"),
    )
    val brightnessPosLabels = listOf(
        tr("classic_cc_brightness_pos_bottom", "底部（磁贴下方）"),
        tr("classic_cc_brightness_pos_top", "顶部（磁贴上方）"),
    )
    val brightnessColorLabels = listOf(
        tr("classic_cc_brightness_color_accent", "跟随磁贴主题色"),
        tr("classic_cc_brightness_color_white", "现代高对比纯白"),
        tr("classic_cc_brightness_color_custom", "自定义颜色"),
    )
    val dataUsageModeLabels = listOf(
        tr("classic_cc_data_usage_system", "跟随系统设置"),
        tr("classic_cc_data_usage_hide", "隐藏流量信息栏"),
    )
    val clockWeightLabels = listOf(
        tr("classic_cc_weight_light", "纤细 (Light)"),
        tr("classic_cc_weight_regular", "常规 (Regular)"),
        tr("classic_cc_weight_medium", "中等 (Medium)"),
        tr("classic_cc_weight_semibold", "半粗 (SemiBold)"),
        tr("classic_cc_weight_bold", "粗体 (Bold)"),
    )

    AppPage(
        tr("classic_cc_title", "经典控制中心优化"),
        back,
        restartScopes = setOf(ScopeApplication.SYSTEM_UI),
        header = {
            SoftGlassHeaderTabs(
                listOf(
                    tr("classic_cc_tab_appearance", "外观"),
                    tr("classic_cc_tab_tiles", "磁贴"),
                    tr("classic_cc_tab_sliders", "滑块"),
                    tr("classic_cc_tab_header", "顶栏"),
                ),
                tab,
                { tab = it },
            )
        },
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Group(tr("classic_cc_general_group", "总开关与预设")) {
                    SwitchPreference(
                        title = tr("classic_cc_enable", "启用经典控制中心优化"),
                        summary = tr("classic_cc_enable_summary", "为被弃用的经典控制中心提供柔光玻璃与清透磨砂材质、自定义磁贴网格与卡片样式、滑块增强、音量条与顶栏快捷入口，同时保持通知柔光玻璃特性。"),
                        checked = value.enabled,
                        onCheckedChange = { enabled -> update { it.copy(enabled = enabled) } },
                    )
                    SwitchPreference(
                        title = tr("classic_cc_unlock_systemui", "解除系统界面强制新版限制"),
                        summary = tr("classic_cc_unlock_systemui_summary", "在系统界面中清除 forceUseControlCenter 锁定标志。如需在系统设置内切换样式，也可同时开启设置作用域的旧版控制中心解锁。"),
                        checked = value.unlockClassicInSystemUi,
                        onCheckedChange = { unlock -> update { it.copy(unlockClassicInSystemUi = unlock) } },
                    )
                    SharedViewArrowPreference(
                        title = tr("classic_cc_presets", "经典控制中心预设"),
                        summary = tr("classic_cc_presets_summary", "管理内置与自定义预设，支持保存、另存为、导入导出 JSON 与二维码分享。"),
                        onClick = openPresets,
                    )
                    SharedViewArrowPreference(
                        title = tr("classic_cc_reset", "恢复经典控制中心默认参数"),
                        onClick = { showResetConfirmation = true },
                    )
                }
            }

            if (value.enabled) {
                when (tab) {
                    0 -> {
                        item {
                            Group(tr("classic_cc_panel_material_group", "面板材质与圆角")) {
                                OverlayDropdownPreference(
                                    title = tr("classic_cc_surface_mode", "面板背景材质"),
                                    items = surfaceModeLabels,
                                    selectedIndex = value.panelSurfaceMode,
                                    onSelectedIndexChange = { idx -> update { it.copy(panelSurfaceMode = idx) } },
                                )
                                AnimatedVisibility(
                                    visible = value.panelSurfaceMode != CLASSIC_CC_SURFACE_DEFAULT,
                                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                                ) {
                                    Column {
                                        ParameterIntSlide(
                                            title = tr("classic_cc_panel_corner_radius", "面板圆角半径"),
                                            value = value.panelCornerRadiusDp,
                                            range = 0..48,
                                            suffix = " dp",
                                            defaultValue = 28,
                                        ) { n -> update { it.copy(panelCornerRadiusDp = n) } }

                                        when (value.panelSurfaceMode) {
                                            CLASSIC_CC_SURFACE_FOLLOW_SYSTEM -> {
                                                SwitchPreference(
                                                    title = tr("classic_cc_use_global_soft_glass", "应用全局柔光玻璃参数"),
                                                    summary = tr("classic_cc_use_global_soft_glass_summary", "开启后直接复用“柔光玻璃材质参数调整”的全局参数；关闭后可为经典控制中心单独调校玻璃折射与光影参数。"),
                                                    checked = value.useGlobalSoftGlass,
                                                    onCheckedChange = { useGlobal -> update { it.copy(useGlobalSoftGlass = useGlobal) } },
                                                )
                                                SwitchPreference(
                                                    title = tr("classic_cc_use_global_clear_frost", "应用全局清透磨砂参数"),
                                                    summary = tr("classic_cc_use_global_clear_frost_summary", "开启后直接复用“清透磨砂材质参数调整”的全局参数；关闭后可为经典控制中心单独配置混色材质、高光描边与表面阴影。"),
                                                    checked = value.useGlobalClearFrost,
                                                    onCheckedChange = { useGlobal -> update { it.copy(useGlobalClearFrost = useGlobal) } },
                                                )
                                                SwitchPreference(
                                                    title = tr("classic_cc_panel_shadow", "面板底部弥散阴影"),
                                                    checked = value.panelShadowEnabled,
                                                    onCheckedChange = { enabled -> update { it.copy(panelShadowEnabled = enabled) } },
                                                )
                                            }

                                            CLASSIC_CC_SURFACE_SOFT_GLASS -> {
                                                SwitchPreference(
                                                    title = tr("classic_cc_use_global_soft_glass", "应用全局柔光玻璃参数"),
                                                    summary = tr("classic_cc_use_global_soft_glass_summary", "开启后直接复用“柔光玻璃材质参数调整”的全局参数；关闭后可为经典控制中心单独调校玻璃折射与光影参数。"),
                                                    checked = value.useGlobalSoftGlass,
                                                    onCheckedChange = { useGlobal -> update { it.copy(useGlobalSoftGlass = useGlobal) } },
                                                )
                                                SwitchPreference(
                                                    title = tr("classic_cc_panel_shadow", "面板底部弥散阴影"),
                                                    checked = value.panelShadowEnabled,
                                                    onCheckedChange = { enabled -> update { it.copy(panelShadowEnabled = enabled) } },
                                                )
                                            }

                                            CLASSIC_CC_SURFACE_CLEAR_FROST -> {
                                                SwitchPreference(
                                                    title = tr("classic_cc_use_global_clear_frost", "应用全局清透磨砂参数"),
                                                    summary = tr("classic_cc_use_global_clear_frost_summary", "开启后直接复用“清透磨砂材质参数调整”的全局参数；关闭后可为经典控制中心单独配置混色材质、高光描边与表面阴影。"),
                                                    checked = value.useGlobalClearFrost,
                                                    onCheckedChange = { useGlobal -> update { it.copy(useGlobalClearFrost = useGlobal) } },
                                                )
                                            }

                                            CLASSIC_CC_SURFACE_SOLID -> {
                                                ParameterIntSlide(
                                                    title = tr("classic_cc_panel_opacity", "面板填充不透明度"),
                                                    value = value.panelOpacity,
                                                    range = 0..100,
                                                    suffix = "%",
                                                    defaultValue = 72,
                                                ) { n -> update { it.copy(panelOpacity = n) } }
                                                ClassicCcColorRow(
                                                    title = tr("classic_cc_panel_light_color", "浅色模式面板底色"),
                                                    color = value.panelLightBgColor,
                                                ) {
                                                    colorEdit = ClassicCcColorEditTarget(
                                                        title = tr("classic_cc_panel_light_color", "浅色模式面板底色"),
                                                        color = value.panelLightBgColor,
                                                        defaultColor = CLASSIC_CC_DEFAULT_PANEL_LIGHT_BG,
                                                    ) { c -> update { it.copy(panelLightBgColor = c) } }
                                                }
                                                ClassicCcColorRow(
                                                    title = tr("classic_cc_panel_dark_color", "深色模式面板底色"),
                                                    color = value.panelDarkBgColor,
                                                ) {
                                                    colorEdit = ClassicCcColorEditTarget(
                                                        title = tr("classic_cc_panel_dark_color", "深色模式面板底色"),
                                                        color = value.panelDarkBgColor,
                                                        defaultColor = CLASSIC_CC_DEFAULT_PANEL_DARK_BG,
                                                    ) { c -> update { it.copy(panelDarkBgColor = c) } }
                                                }
                                                SwitchPreference(
                                                    title = tr("classic_cc_panel_stroke", "面板高光描边"),
                                                    checked = value.panelStrokeEnabled,
                                                    onCheckedChange = { enabled -> update { it.copy(panelStrokeEnabled = enabled) } },
                                                )
                                                if (value.panelStrokeEnabled) {
                                                    ParameterFloatSlide(
                                                        title = tr("classic_cc_panel_stroke_width", "描边宽度"),
                                                        value = value.panelStrokeWidthDp,
                                                        range = 0f..3f,
                                                        defaultValue = 1.0f,
                                                    ) { v -> update { it.copy(panelStrokeWidthDp = v) } }
                                                    ParameterIntSlide(
                                                        title = tr("classic_cc_panel_stroke_alpha", "描边不透明度"),
                                                        value = value.panelStrokeAlpha,
                                                        range = 0..100,
                                                        suffix = "%",
                                                        defaultValue = 28,
                                                    ) { n -> update { it.copy(panelStrokeAlpha = n) } }
                                                }
                                                SwitchPreference(
                                                    title = tr("classic_cc_panel_shadow", "面板底部弥散阴影"),
                                                    checked = value.panelShadowEnabled,
                                                    onCheckedChange = { enabled -> update { it.copy(panelShadowEnabled = enabled) } },
                                                )
                                            }
                                        }

                                        SwitchPreference(
                                            title = tr("classic_cc_modernize_detail", "同步现代化详情与编辑面板"),
                                            summary = tr("classic_cc_modernize_detail_summary", "使点击磁贴展开的二级详情弹窗与磁贴编辑页也拥有统一圆角、材质与胶囊按钮。"),
                                            checked = value.modernizeDetailAndCustomizer,
                                            onCheckedChange = { enabled -> update { it.copy(modernizeDetailAndCustomizer = enabled) } },
                                        )
                                    }
                                }
                            }
                        }

                        if ((value.panelSurfaceMode == CLASSIC_CC_SURFACE_SOFT_GLASS ||
                                value.panelSurfaceMode == CLASSIC_CC_SURFACE_FOLLOW_SYSTEM) &&
                            !value.useGlobalSoftGlass
                        ) {
                            item {
                                Group(tr("classic_cc_soft_glass_custom_group", "经典控制中心独立柔光玻璃参数")) {
                                    ParameterIntSlide(
                                        title = tr("classic_cc_sg_refraction_blur", "玻璃折射模糊度"),
                                        value = value.softGlass.refractionBlur,
                                        range = 0..200,
                                        suffix = "%",
                                        defaultValue = 100,
                                    ) { n -> updateSoftGlass { it.copy(refractionBlur = n) } }
                                    softGlassParameters.forEach { def ->
                                        val defVal = if (def.key == "darker") -100 else 0
                                        ParameterIntSlide(
                                            title = tr(def.title, def.title),
                                            value = value.softGlass.adjustments[def.key] ?: defVal,
                                            range = def.adjustmentRange,
                                            suffix = "%",
                                            defaultValue = defVal,
                                        ) { n ->
                                            updateSoftGlass {
                                                it.copy(adjustments = it.adjustments + (def.key to n))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if ((value.panelSurfaceMode == CLASSIC_CC_SURFACE_CLEAR_FROST ||
                                value.panelSurfaceMode == CLASSIC_CC_SURFACE_FOLLOW_SYSTEM) &&
                            !value.useGlobalClearFrost
                        ) {
                            item {
                                val cf = value.clearFrost
                                Group(tr("classic_cc_clear_frost_custom_group", "经典控制中心独立清透磨砂参数")) {
                                    SwitchPreference(
                                        title = tr("clearFrostCustomBlendEnabled", "启用自定义混色"),
                                        summary = tr("clearFrostCustomBlendSummary", "关闭时直接选用系统材质预设，开启后可自由配置浅色与深色混色层。"),
                                        checked = cfCustomBlendEnabled,
                                        onCheckedChange = { useCustom ->
                                            if (useCustom) {
                                                updateClearFrost {
                                                    it.copy(
                                                        lightStyle = CLEAR_FROST_STYLE_CUSTOM,
                                                        darkStyle = CLEAR_FROST_STYLE_CUSTOM,
                                                    )
                                                }
                                            } else {
                                                updateClearFrost {
                                                    it.copy(
                                                        lightStyle = lastCfLightStyle,
                                                        darkStyle = lastCfDarkStyle,
                                                    )
                                                }
                                            }
                                        },
                                    )
                                    if (!cfCustomBlendEnabled) {
                                        OverlayDropdownPreference(
                                            title = tr("clearFrostLightStyle", "浅色模式材质"),
                                            items = cfLightStyleLabels,
                                            selectedIndex = cfLightIndex(cf.lightStyle),
                                            onSelectedIndexChange = { idx ->
                                                updateClearFrost { it.copy(lightStyle = clearFrostSelectableLightStyles[idx].id) }
                                            },
                                        )
                                        OverlayDropdownPreference(
                                            title = tr("clearFrostDarkStyle", "深色模式材质"),
                                            items = cfDarkStyleLabels,
                                            selectedIndex = cfDarkIndex(cf.darkStyle),
                                            onSelectedIndexChange = { idx ->
                                                updateClearFrost { it.copy(darkStyle = clearFrostSelectableDarkStyles[idx].id) }
                                            },
                                        )
                                    } else {
                                        ClassicCcColorRow(
                                            title = tr("clearFrostCustomLight1", "浅色混色层 1"),
                                            color = cf.customLightColor1,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("clearFrostCustomLight1", "浅色混色层 1"),
                                                color = cf.customLightColor1,
                                                defaultColor = CLEAR_FROST_DEFAULT_LIGHT_COLOR_1,
                                            ) { c -> updateClearFrost { it.copy(lightStyle = CLEAR_FROST_STYLE_CUSTOM, customLightColor1 = c) } }
                                        }
                                        ClassicCcColorRow(
                                            title = tr("clearFrostCustomLight2", "浅色混色层 2"),
                                            color = cf.customLightColor2,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("clearFrostCustomLight2", "浅色混色层 2"),
                                                color = cf.customLightColor2,
                                                defaultColor = CLEAR_FROST_DEFAULT_LIGHT_COLOR_2,
                                            ) { c -> updateClearFrost { it.copy(lightStyle = CLEAR_FROST_STYLE_CUSTOM, customLightColor2 = c) } }
                                        }
                                        ClassicCcColorRow(
                                            title = tr("clearFrostCustomLight3", "浅色混色层 3"),
                                            color = cf.customLightColor3,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("clearFrostCustomLight3", "浅色混色层 3"),
                                                color = cf.customLightColor3,
                                                defaultColor = CLEAR_FROST_DEFAULT_LIGHT_COLOR_3,
                                            ) { c -> updateClearFrost { it.copy(lightStyle = CLEAR_FROST_STYLE_CUSTOM, customLightColor3 = c) } }
                                        }
                                        ClassicCcColorRow(
                                            title = tr("clearFrostCustomDark1", "深色混色层 1"),
                                            color = cf.customDarkColor1,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("clearFrostCustomDark1", "深色混色层 1"),
                                                color = cf.customDarkColor1,
                                                defaultColor = CLEAR_FROST_DEFAULT_DARK_COLOR_1,
                                            ) { c -> updateClearFrost { it.copy(darkStyle = CLEAR_FROST_STYLE_CUSTOM, customDarkColor1 = c) } }
                                        }
                                        ClassicCcColorRow(
                                            title = tr("clearFrostCustomDark2", "深色混色层 2"),
                                            color = cf.customDarkColor2,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("clearFrostCustomDark2", "深色混色层 2"),
                                                color = cf.customDarkColor2,
                                                defaultColor = CLEAR_FROST_DEFAULT_DARK_COLOR_2,
                                            ) { c -> updateClearFrost { it.copy(darkStyle = CLEAR_FROST_STYLE_CUSTOM, customDarkColor2 = c) } }
                                        }
                                        ClassicCcColorRow(
                                            title = tr("clearFrostCustomDark3", "深色混色层 3"),
                                            color = cf.customDarkColor3,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("clearFrostCustomDark3", "深色混色层 3"),
                                                color = cf.customDarkColor3,
                                                defaultColor = CLEAR_FROST_DEFAULT_DARK_COLOR_3,
                                            ) { c -> updateClearFrost { it.copy(darkStyle = CLEAR_FROST_STYLE_CUSTOM, customDarkColor3 = c) } }
                                        }
                                    }
                                    ParameterIntSlide(
                                        title = tr("clearFrostElementOpacity", "元素混色不透明度"),
                                        value = cf.elementOpacity,
                                        range = 0..200,
                                        suffix = "%",
                                        defaultValue = 100,
                                    ) { n -> updateClearFrost { it.copy(elementOpacity = n) } }
                                    SwitchPreference(
                                        title = tr("clearFrostStrokeEnabled", "启用高光描边"),
                                        checked = cf.strokeEnabled,
                                        onCheckedChange = { enabled -> updateClearFrost { it.copy(strokeEnabled = enabled) } },
                                    )
                                    if (cf.strokeEnabled) {
                                        OverlayDropdownPreference(
                                            title = tr("clearFrostStrokePreset", "描边样式"),
                                            items = cfStrokePresetLabels,
                                            selectedIndex = clearFrostStrokePresets.indexOfFirst { it.first == cf.strokePreset }.coerceAtLeast(0),
                                            onSelectedIndexChange = { idx ->
                                                updateClearFrost { it.copy(strokePreset = clearFrostStrokePresets[idx].first) }
                                            },
                                        )
                                        if (cf.strokePreset == CLEAR_FROST_STROKE_CUSTOM) {
                                            ParameterDpSlide(
                                                title = tr("clearFrostStrokeWidth", "主描边宽度"),
                                                value = cf.strokeNormalWidth,
                                                range = 0f..6f,
                                                defaultValue = 2.6f,
                                            ) { v -> updateClearFrost { it.copy(strokeNormalWidth = v) } }
                                            ParameterIntSlide(
                                                title = tr("clearFrostStrokeSource1Alpha", "主光源高光强度"),
                                                value = cf.strokeSource1Alpha,
                                                range = 0..100,
                                                suffix = "%",
                                                defaultValue = 60,
                                            ) { v -> updateClearFrost { it.copy(strokeSource1Alpha = v) } }
                                            ParameterIntSlide(
                                                title = tr("clearFrostStrokeSource2Alpha", "副光源高光强度"),
                                                value = cf.strokeSource2Alpha,
                                                range = 0..100,
                                                suffix = "%",
                                                defaultValue = 35,
                                            ) { v -> updateClearFrost { it.copy(strokeSource2Alpha = v) } }
                                            ParameterDpSlide(
                                                title = tr("clearFrostStrokeBloomWidth", "泛光描边宽度"),
                                                value = cf.strokeBloomWidth,
                                                range = 0f..5f,
                                                defaultValue = 0.8f,
                                            ) { v -> updateClearFrost { it.copy(strokeBloomWidth = v) } }
                                            ParameterIntSlide(
                                                title = tr("clearFrostStrokeBloomAlpha", "泛光强度"),
                                                value = cf.strokeBloomAlpha,
                                                range = 0..100,
                                                suffix = "%",
                                                defaultValue = 5,
                                            ) { v -> updateClearFrost { it.copy(strokeBloomAlpha = v) } }
                                            ParameterIntSlide(
                                                title = tr("clearFrostStrokeBloomDegree", "泛光渐变角度"),
                                                value = cf.strokeBloomDegree,
                                                range = 0..360,
                                                suffix = "°",
                                                defaultValue = 180,
                                            ) { v -> updateClearFrost { it.copy(strokeBloomDegree = v) } }
                                        }
                                    }
                                    SwitchPreference(
                                        title = tr("clearFrostShadowEnabled", "启用表面阴影"),
                                        checked = cf.shadowEnabled,
                                        onCheckedChange = { enabled -> updateClearFrost { it.copy(shadowEnabled = enabled) } },
                                    )
                                    if (cf.shadowEnabled) {
                                        ParameterIntSlide(
                                            title = tr("clearFrostShadowAlpha", "阴影不透明度"),
                                            value = cf.shadowAlpha,
                                            range = 0..100,
                                            suffix = "%",
                                            defaultValue = 35,
                                        ) { v -> updateClearFrost { it.copy(shadowAlpha = v) } }
                                        ParameterIntSlide(
                                            title = tr("clearFrostShadowOffsetY", "阴影纵向偏移"),
                                            value = cf.shadowOffsetY,
                                            range = 0..80,
                                            suffix = " px",
                                            defaultValue = 30,
                                        ) { v -> updateClearFrost { it.copy(shadowOffsetY = v) } }
                                        ParameterIntSlide(
                                            title = tr("clearFrostShadowRadius", "阴影模糊半径"),
                                            value = cf.shadowRadius,
                                            range = 0..100,
                                            suffix = " px",
                                            defaultValue = 26,
                                        ) { v -> updateClearFrost { it.copy(shadowRadius = v) } }
                                    }
                                }
                            }
                        }

                        item {
                            Group(tr("classic_cc_shade_bg_group", "经典控制中心背景模糊调整")) {
                                SwitchPreference(
                                    title = tr("classic_cc_shade_bg_custom", "自定义背景模糊参数"),
                                    summary = tr("classic_cc_shade_bg_custom_summary", "单独控制经典控制中心下拉后的整屏背景模糊强度、玻璃折射模糊、采样缩放与蒙版不透明度（100% 为系统默认值，实时生效）。"),
                                    checked = value.shadeBackgroundCustomEnabled,
                                    onCheckedChange = { enabled -> update { it.copy(shadeBackgroundCustomEnabled = enabled) } },
                                )
                                AnimatedVisibility(
                                    visible = value.shadeBackgroundCustomEnabled,
                                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                                ) {
                                    Column {
                                        ParameterIntSlide(
                                            title = tr("classic_cc_shade_bg_blur", "整屏背景模糊度"),
                                            value = value.shadeBackgroundBlur,
                                            range = 0..200,
                                            suffix = "%",
                                            defaultValue = 100,
                                        ) { n -> update { it.copy(shadeBackgroundBlur = n) } }
                                        ParameterIntSlide(
                                            title = tr("classic_cc_shade_glass_blur", "玻璃卡片内折射模糊度"),
                                            value = value.shadeGlassBlur,
                                            range = 0..200,
                                            suffix = "%",
                                            defaultValue = 100,
                                        ) { n -> update { it.copy(shadeGlassBlur = n) } }
                                        ParameterIntSlide(
                                            title = tr("classic_cc_shade_bg_scale", "背景采样缩放程度"),
                                            value = value.shadeBackgroundScale,
                                            range = 0..200,
                                            suffix = "%",
                                            defaultValue = 100,
                                        ) { n -> update { it.copy(shadeBackgroundScale = n) } }
                                        ParameterIntSlide(
                                            title = tr("classic_cc_shade_bg_opacity", "背景蒙版不透明度"),
                                            value = value.shadeBackgroundOpacity,
                                            range = 0..200,
                                            suffix = "%",
                                            defaultValue = 100,
                                        ) { n -> update { it.copy(shadeBackgroundOpacity = n) } }
                                    }
                                }
                            }
                        }

                        item {
                            Group(tr("classic_cc_panel_spacing_group", "面板边距与指示器")) {
                                ParameterIntSlide(
                                    title = tr("classic_cc_panel_side_margin", "面板左右外边距"),
                                    value = value.panelSideMarginDp,
                                    range = 0..32,
                                    suffix = " dp",
                                    defaultValue = 12,
                                ) { n -> update { it.copy(panelSideMarginDp = n) } }
                                ParameterIntSlide(
                                    title = tr("classic_cc_panel_h_padding", "面板内容左右内边距"),
                                    value = value.panelHorizontalPaddingDp,
                                    range = 0..32,
                                    suffix = " dp",
                                    defaultValue = 16,
                                ) { n -> update { it.copy(panelHorizontalPaddingDp = n) } }
                                ParameterIntSlide(
                                    title = tr("classic_cc_panel_top_padding", "面板顶部内边距"),
                                    value = value.panelTopPaddingDp,
                                    range = 0..28,
                                    suffix = " dp",
                                    defaultValue = 16,
                                ) { n -> update { it.copy(panelTopPaddingDp = n) } }
                                ParameterIntSlide(
                                    title = tr("classic_cc_panel_bottom_padding", "面板底部内边距"),
                                    value = value.panelBottomPaddingDp,
                                    range = 0..28,
                                    suffix = " dp",
                                    defaultValue = 8,
                                ) { n -> update { it.copy(panelBottomPaddingDp = n) } }
                                ParameterIntSlide(
                                    title = tr("classic_cc_panel_notif_gap", "面板与通知卡片间距"),
                                    value = value.panelNotificationGapDp,
                                    range = 0..32,
                                    suffix = " dp",
                                    defaultValue = 12,
                                ) { n -> update { it.copy(panelNotificationGapDp = n) } }
                                Text(
                                    tr("classic_cc_panel_notif_gap_hint", "在顶部经典磁贴卡片底边与第一条通知卡片之间保留独立留白，避免边缘贴合或重叠。"),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                OverlayDropdownPreference(
                                    title = tr("classic_cc_drag_handle_style", "底部展开指示条样式"),
                                    items = handleStyleLabels,
                                    selectedIndex = value.dragHandleStyle,
                                    onSelectedIndexChange = { idx -> update { it.copy(dragHandleStyle = idx) } },
                                )
                                if (value.dragHandleStyle == CLASSIC_CC_HANDLE_PILL) {
                                    ParameterIntSlide(
                                        title = tr("classic_cc_drag_handle_width", "指示条宽度"),
                                        value = value.dragHandleWidthDp,
                                        range = 20..80,
                                        suffix = " dp",
                                        defaultValue = 36,
                                    ) { n -> update { it.copy(dragHandleWidthDp = n) } }
                                    ParameterIntSlide(
                                        title = tr("classic_cc_drag_handle_height", "指示条高度"),
                                        value = value.dragHandleHeightDp,
                                        range = 2..8,
                                        suffix = " dp",
                                        defaultValue = 4,
                                    ) { n -> update { it.copy(dragHandleHeightDp = n) } }
                                }
                                OverlayDropdownPreference(
                                    title = tr("classic_cc_page_indicator_style", "多页翻页指示器样式"),
                                    items = indicatorStyleLabels,
                                    selectedIndex = value.pageIndicatorStyle,
                                    onSelectedIndexChange = { idx -> update { it.copy(pageIndicatorStyle = idx) } },
                                )
                            }
                        }
                    }

                    1 -> {
                        item {
                            Group(tr("classic_cc_grid_group", "网格与磁贴数量")) {
                                ParameterIntSlide(
                                    title = tr("classic_cc_qqs_count", "收起态快捷磁贴数量"),
                                    value = value.qqsTileCount,
                                    range = 3..7,
                                    suffix = "",
                                    defaultValue = 5,
                                ) { n -> update { it.copy(qqsTileCount = n) } }
                                ParameterIntSlide(
                                    title = tr("classic_cc_qs_cols_portrait", "展开态竖屏列数"),
                                    value = value.qsColumnsPortrait,
                                    range = 2..6,
                                    suffix = "",
                                    defaultValue = 4,
                                ) { n -> update { it.copy(qsColumnsPortrait = n) } }
                                ParameterIntSlide(
                                    title = tr("classic_cc_qs_rows_portrait", "展开态竖屏行数"),
                                    value = value.qsRowsPortrait,
                                    range = 2..5,
                                    suffix = "",
                                    defaultValue = 3,
                                ) { n -> update { it.copy(qsRowsPortrait = n) } }
                                ParameterIntSlide(
                                    title = tr("classic_cc_qs_cols_landscape", "展开态横屏列数"),
                                    value = value.qsColumnsLandscape,
                                    range = 4..8,
                                    suffix = "",
                                    defaultValue = 6,
                                ) { n -> update { it.copy(qsColumnsLandscape = n) } }
                                ParameterIntSlide(
                                    title = tr("classic_cc_qs_rows_landscape", "展开态横屏行数"),
                                    value = value.qsRowsLandscape,
                                    range = 1..3,
                                    suffix = "",
                                    defaultValue = 2,
                                ) { n -> update { it.copy(qsRowsLandscape = n) } }
                            }
                        }

                        item {
                            Group(tr("classic_cc_tile_style_group", "磁贴样式与几何尺寸")) {
                                OverlayDropdownPreference(
                                    title = tr("classic_cc_tile_style", "磁贴外观样式"),
                                    items = tileStyleLabels,
                                    selectedIndex = value.tileStyle,
                                    onSelectedIndexChange = { idx -> update { it.copy(tileStyle = idx) } },
                                )
                                ParameterIntSlide(
                                    title = tr("classic_cc_tile_height", "磁贴行高"),
                                    value = value.tileHeightDp,
                                    range = 52..120,
                                    suffix = " dp",
                                    defaultValue = 92,
                                ) { n -> update { it.copy(tileHeightDp = n) } }
                                ParameterIntSlide(
                                    title = tr("classic_cc_tile_h_spacing", "磁贴水平间距"),
                                    value = value.tileHorizontalSpacingDp,
                                    range = 0..24,
                                    suffix = " dp",
                                    defaultValue = 8,
                                ) { n -> update { it.copy(tileHorizontalSpacingDp = n) } }
                                ParameterIntSlide(
                                    title = tr("classic_cc_tile_top_margin", "磁贴区域顶部留白"),
                                    value = value.tileTopMarginDp,
                                    range = 0..28,
                                    suffix = " dp",
                                    defaultValue = 16,
                                ) { n -> update { it.copy(tileTopMarginDp = n) } }
                                ParameterIntSlide(
                                    title = tr("classic_cc_tile_icon_bg_size", "磁贴图标容器尺寸"),
                                    value = value.tileIconBgSizeDp,
                                    range = 36..72,
                                    suffix = " dp",
                                    defaultValue = 54,
                                ) { n -> update { it.copy(tileIconBgSizeDp = n) } }
                                if (value.tileStyle != CLASSIC_CC_TILE_STYLE_CIRCLE) {
                                    ParameterIntSlide(
                                        title = tr("classic_cc_tile_corner_radius", "磁贴圆角半径"),
                                        value = value.tileCornerRadiusDp,
                                        range = 0..36,
                                        suffix = " dp",
                                        defaultValue = 18,
                                    ) { n -> update { it.copy(tileCornerRadiusDp = n) } }
                                }
                                ParameterIntSlide(
                                    title = tr("classic_cc_tile_icon_size", "磁贴内部图标尺寸"),
                                    value = value.tileIconSizeDp,
                                    range = 14..34,
                                    suffix = " dp",
                                    defaultValue = 24,
                                ) { n -> update { it.copy(tileIconSizeDp = n) } }
                                SwitchPreference(
                                    title = tr("classic_cc_tile_border", "磁贴边缘细微描边"),
                                    checked = value.tileBorderEnabled,
                                    onCheckedChange = { enabled -> update { it.copy(tileBorderEnabled = enabled) } },
                                )
                            }
                        }

                        item {
                            Group(tr("classic_cc_tile_label_group", "磁贴文字标签")) {
                                SwitchPreference(
                                    title = tr("classic_cc_show_tile_label", "显示磁贴文字标签"),
                                    checked = value.showTileLabel,
                                    onCheckedChange = { enabled -> update { it.copy(showTileLabel = enabled) } },
                                )
                                if (value.showTileLabel) {
                                    SwitchPreference(
                                        title = tr("classic_cc_show_tile_secondary", "显示磁贴副标题"),
                                        summary = tr("classic_cc_show_tile_secondary_summary", "显示 Wi-Fi 名称、蓝牙已连设备等第二行状态说明。"),
                                        checked = value.showTileSecondaryLabel,
                                        onCheckedChange = { enabled -> update { it.copy(showTileSecondaryLabel = enabled) } },
                                    )
                                    ParameterFloatSlide(
                                        title = tr("classic_cc_tile_label_size", "标签字号"),
                                        value = value.tileLabelSizeSp,
                                        range = 9f..16f,
                                        defaultValue = 11.5f,
                                    ) { v -> update { it.copy(tileLabelSizeSp = v) } }
                                    if (value.tileStyle != CLASSIC_CC_TILE_STYLE_PILL_CARD) {
                                        ParameterIntSlide(
                                            title = tr("classic_cc_tile_label_gap", "图标与标签间距"),
                                            value = value.tileLabelTopGapDp,
                                            range = 0..14,
                                            suffix = " dp",
                                            defaultValue = 6,
                                        ) { n -> update { it.copy(tileLabelTopGapDp = n) } }
                                    }
                                    SwitchPreference(
                                        title = tr("classic_cc_tile_label_bold", "磁贴标签字体加粗"),
                                        checked = value.tileLabelBold,
                                        onCheckedChange = { enabled -> update { it.copy(tileLabelBold = enabled) } },
                                    )
                                }
                            }
                        }

                        item {
                            Group(tr("classic_cc_tile_color_group", "磁贴主题配色")) {
                                OverlayDropdownPreference(
                                    title = tr("classic_cc_tile_accent_mode", "磁贴配色模式"),
                                    items = tileAccentLabels,
                                    selectedIndex = value.tileAccentMode,
                                    onSelectedIndexChange = { idx -> update { it.copy(tileAccentMode = idx) } },
                                )
                                AnimatedVisibility(
                                    visible = value.tileAccentMode == CLASSIC_CC_ACCENT_CUSTOM,
                                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                                ) {
                                    Column {
                                        ClassicCcColorRow(
                                            title = tr("classic_cc_tile_active_bg", "激活态背景色"),
                                            color = value.tileActiveBgColor,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("classic_cc_tile_active_bg", "激活态背景色"),
                                                color = value.tileActiveBgColor,
                                                defaultColor = CLASSIC_CC_DEFAULT_TILE_ACTIVE_BG,
                                            ) { c -> update { it.copy(tileActiveBgColor = c) } }
                                        }
                                        ClassicCcColorRow(
                                            title = tr("classic_cc_tile_active_icon", "激活态图标色"),
                                            color = value.tileActiveIconColor,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("classic_cc_tile_active_icon", "激活态图标色"),
                                                color = value.tileActiveIconColor,
                                                defaultColor = CLASSIC_CC_DEFAULT_TILE_ACTIVE_ICON,
                                            ) { c -> update { it.copy(tileActiveIconColor = c) } }
                                        }
                                        ClassicCcColorRow(
                                            title = tr("classic_cc_tile_inactive_light_bg", "浅色未激活背景色"),
                                            color = value.tileInactiveLightBgColor,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("classic_cc_tile_inactive_light_bg", "浅色未激活背景色"),
                                                color = value.tileInactiveLightBgColor,
                                                defaultColor = CLASSIC_CC_DEFAULT_TILE_INACTIVE_LIGHT_BG,
                                            ) { c -> update { it.copy(tileInactiveLightBgColor = c) } }
                                        }
                                        ClassicCcColorRow(
                                            title = tr("classic_cc_tile_inactive_dark_bg", "深色未激活背景色"),
                                            color = value.tileInactiveDarkBgColor,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("classic_cc_tile_inactive_dark_bg", "深色未激活背景色"),
                                                color = value.tileInactiveDarkBgColor,
                                                defaultColor = CLASSIC_CC_DEFAULT_TILE_INACTIVE_DARK_BG,
                                            ) { c -> update { it.copy(tileInactiveDarkBgColor = c) } }
                                        }
                                        ClassicCcColorRow(
                                            title = tr("classic_cc_tile_inactive_light_icon", "浅色未激活图标色"),
                                            color = value.tileInactiveLightIconColor,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("classic_cc_tile_inactive_light_icon", "浅色未激活图标色"),
                                                color = value.tileInactiveLightIconColor,
                                                defaultColor = CLASSIC_CC_DEFAULT_TILE_INACTIVE_LIGHT_ICON,
                                            ) { c -> update { it.copy(tileInactiveLightIconColor = c) } }
                                        }
                                        ClassicCcColorRow(
                                            title = tr("classic_cc_tile_inactive_dark_icon", "深色未激活图标色"),
                                            color = value.tileInactiveDarkIconColor,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("classic_cc_tile_inactive_dark_icon", "深色未激活图标色"),
                                                color = value.tileInactiveDarkIconColor,
                                                defaultColor = CLASSIC_CC_DEFAULT_TILE_INACTIVE_DARK_ICON,
                                            ) { c -> update { it.copy(tileInactiveDarkIconColor = c) } }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        item {
                            Group(tr("classic_cc_brightness_group", "亮度滑块现代化")) {
                                SwitchPreference(
                                    title = tr("classic_cc_brightness_visible", "显示亮度滑块"),
                                    checked = value.brightnessSliderVisible,
                                    onCheckedChange = { visible -> update { it.copy(brightnessSliderVisible = visible) } },
                                )
                                if (value.brightnessSliderVisible) {
                                    OverlayDropdownPreference(
                                        title = tr("classic_cc_brightness_pos", "亮度条位置"),
                                        items = brightnessPosLabels,
                                        selectedIndex = value.brightnessSliderPosition,
                                        onSelectedIndexChange = { idx -> update { it.copy(brightnessSliderPosition = idx) } },
                                    )
                                    SwitchPreference(
                                        title = tr("classic_cc_brightness_expanded_only", "仅在完全展开时显示亮度条"),
                                        summary = tr("classic_cc_brightness_expanded_only_summary", "收起态隐藏亮度条，进一步释放通知列表空间。"),
                                        checked = value.brightnessExpandedOnly,
                                        onCheckedChange = { expandedOnly -> update { it.copy(brightnessExpandedOnly = expandedOnly) } },
                                    )
                                    SwitchPreference(
                                        title = tr("classic_cc_brightness_auto_btn", "显示自动亮度快捷开关"),
                                        summary = tr("classic_cc_brightness_auto_btn_summary", "在亮度条右侧新增自动亮度胶囊按钮，实时同步系统自动亮度状态。"),
                                        checked = value.brightnessShowAutoButton,
                                        onCheckedChange = { show -> update { it.copy(brightnessShowAutoButton = show) } },
                                    )
                                    SwitchPreference(
                                        title = tr("classic_cc_brightness_percent", "显示实时亮度百分比"),
                                        checked = value.brightnessShowPercent,
                                        onCheckedChange = { show -> update { it.copy(brightnessShowPercent = show) } },
                                    )
                                    ParameterIntSlide(
                                        title = tr("classic_cc_brightness_height", "亮度条高度"),
                                        value = value.brightnessHeightDp,
                                        range = 24..56,
                                        suffix = " dp",
                                        defaultValue = 42,
                                    ) { n -> update { it.copy(brightnessHeightDp = n) } }
                                    ParameterIntSlide(
                                        title = tr("classic_cc_brightness_radius", "滑块圆角半径"),
                                        value = value.brightnessCornerRadiusDp,
                                        range = 4..28,
                                        suffix = " dp",
                                        defaultValue = 21,
                                    ) { n -> update { it.copy(brightnessCornerRadiusDp = n) } }
                                    ParameterIntSlide(
                                        title = tr("classic_cc_brightness_h_margin", "滑块左右边距"),
                                        value = value.brightnessHorizontalMarginDp,
                                        range = 0..28,
                                        suffix = " dp",
                                        defaultValue = 16,
                                    ) { n -> update { it.copy(brightnessHorizontalMarginDp = n) } }
                                    ParameterIntSlide(
                                        title = tr("classic_cc_brightness_top_margin", "滑块上边距"),
                                        value = value.brightnessTopMarginDp,
                                        range = 0..24,
                                        suffix = " dp",
                                        defaultValue = 12,
                                    ) { n -> update { it.copy(brightnessTopMarginDp = n) } }
                                    ParameterIntSlide(
                                        title = tr("classic_cc_brightness_bottom_margin", "滑块下边距"),
                                        value = value.brightnessBottomMarginDp,
                                        range = 0..24,
                                        suffix = " dp",
                                        defaultValue = 8,
                                    ) { n -> update { it.copy(brightnessBottomMarginDp = n) } }
                                    OverlayDropdownPreference(
                                        title = tr("classic_cc_brightness_color_mode", "滑块配色模式"),
                                        items = brightnessColorLabels,
                                        selectedIndex = value.brightnessColorMode,
                                        onSelectedIndexChange = { idx -> update { it.copy(brightnessColorMode = idx) } },
                                    )
                                    if (value.brightnessColorMode == CLASSIC_CC_BRIGHTNESS_COLOR_CUSTOM) {
                                        ClassicCcColorRow(
                                            title = tr("classic_cc_brightness_fill_color", "滑块填充色"),
                                            color = value.brightnessFillColor,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("classic_cc_brightness_fill_color", "滑块填充色"),
                                                color = value.brightnessFillColor,
                                                defaultColor = CLASSIC_CC_DEFAULT_BRIGHTNESS_FILL,
                                            ) { c -> update { it.copy(brightnessFillColor = c) } }
                                        }
                                        ClassicCcColorRow(
                                            title = tr("classic_cc_brightness_track_light", "浅色轨道背景色"),
                                            color = value.brightnessTrackLightColor,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("classic_cc_brightness_track_light", "浅色轨道背景色"),
                                                color = value.brightnessTrackLightColor,
                                                defaultColor = CLASSIC_CC_DEFAULT_BRIGHTNESS_TRACK_LIGHT,
                                            ) { c -> update { it.copy(brightnessTrackLightColor = c) } }
                                        }
                                        ClassicCcColorRow(
                                            title = tr("classic_cc_brightness_track_dark", "深色轨道背景色"),
                                            color = value.brightnessTrackDarkColor,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("classic_cc_brightness_track_dark", "深色轨道背景色"),
                                                color = value.brightnessTrackDarkColor,
                                                defaultColor = CLASSIC_CC_DEFAULT_BRIGHTNESS_TRACK_DARK,
                                            ) { c -> update { it.copy(brightnessTrackDarkColor = c) } }
                                        }
                                        ClassicCcColorRow(
                                            title = tr("classic_cc_brightness_icon_color", "滑块图标颜色"),
                                            color = value.brightnessIconColor,
                                        ) {
                                            colorEdit = ClassicCcColorEditTarget(
                                                title = tr("classic_cc_brightness_icon_color", "滑块图标颜色"),
                                                color = value.brightnessIconColor,
                                                defaultColor = CLASSIC_CC_DEFAULT_BRIGHTNESS_ICON,
                                            ) { c -> update { it.copy(brightnessIconColor = c) } }
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Group(tr("classic_cc_volume_group", "新增现代媒体音量滑块")) {
                                SwitchPreference(
                                    title = tr("classic_cc_volume_enabled", "添加媒体音量滑块"),
                                    summary = tr("classic_cc_volume_enabled_summary", "在经典控制中心底部注入与亮度条风格一致的媒体音量条，实时双向同步系统媒体音量。"),
                                    checked = value.volumeSliderEnabled,
                                    onCheckedChange = { enabled -> update { it.copy(volumeSliderEnabled = enabled) } },
                                )
                                if (value.volumeSliderEnabled) {
                                    SwitchPreference(
                                        title = tr("classic_cc_volume_in_collapsed", "收起态同时显示音量条"),
                                        checked = value.volumeSliderShowInCollapsed,
                                        onCheckedChange = { show -> update { it.copy(volumeSliderShowInCollapsed = show) } },
                                    )
                                    SwitchPreference(
                                        title = tr("classic_cc_volume_mute_btn", "显示一键静音快捷按钮"),
                                        checked = value.volumeSliderShowMuteButton,
                                        onCheckedChange = { show -> update { it.copy(volumeSliderShowMuteButton = show) } },
                                    )
                                    SwitchPreference(
                                        title = tr("classic_cc_volume_percent", "显示实时音量百分比"),
                                        checked = value.volumeSliderShowPercent,
                                        onCheckedChange = { show -> update { it.copy(volumeSliderShowPercent = show) } },
                                    )
                                    ParameterIntSlide(
                                        title = tr("classic_cc_volume_height", "音量条高度"),
                                        value = value.volumeSliderHeightDp,
                                        range = 24..56,
                                        suffix = " dp",
                                        defaultValue = 38,
                                    ) { n -> update { it.copy(volumeSliderHeightDp = n) } }
                                }
                            }
                        }

                        item {
                            Group(tr("classic_cc_data_usage_group", "底部流量信息栏")) {
                                OverlayDropdownPreference(
                                    title = tr("classic_cc_data_usage_mode", "流量栏显示方式"),
                                    items = dataUsageModeLabels,
                                    selectedIndex = value.dataUsageVisibilityMode,
                                    onSelectedIndexChange = { idx -> update { it.copy(dataUsageVisibilityMode = idx) } },
                                )
                                if (value.dataUsageVisibilityMode == CLASSIC_CC_DATA_USAGE_SYSTEM) {
                                    SwitchPreference(
                                        title = tr("classic_cc_data_usage_hide_divider", "隐藏流量栏顶部分割线"),
                                        checked = value.dataUsageHideDivider,
                                        onCheckedChange = { hide -> update { it.copy(dataUsageHideDivider = hide) } },
                                    )
                                    SwitchPreference(
                                        title = tr("classic_cc_data_usage_modern_pill", "现代半透明胶囊样式"),
                                        checked = value.dataUsageModernPill,
                                        onCheckedChange = { pill -> update { it.copy(dataUsageModernPill = pill) } },
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        item {
                            Group(tr("classic_cc_header_typography_group", "顶栏时钟与布局")) {
                                SwitchPreference(
                                    title = tr("classic_cc_header_modern_font", "使用现代无衬线时钟字体"),
                                    summary = tr("classic_cc_header_modern_font_summary", "替换旧版细体 MiClock 字体，支持自定义时钟字号与字重。"),
                                    checked = value.headerUseModernFont,
                                    onCheckedChange = { enabled -> update { it.copy(headerUseModernFont = enabled) } },
                                )
                                ParameterFloatSlide(
                                    title = tr("classic_cc_header_clock_size", "顶栏大时钟字号"),
                                    value = value.headerClockSizeSp,
                                    range = 20f..52f,
                                    defaultValue = 36f,
                                ) { v -> update { it.copy(headerClockSizeSp = v) } }
                                if (value.headerUseModernFont) {
                                    OverlayDropdownPreference(
                                        title = tr("classic_cc_header_clock_weight", "顶栏时钟字重"),
                                        items = clockWeightLabels,
                                        selectedIndex = value.headerClockWeight,
                                        onSelectedIndexChange = { idx -> update { it.copy(headerClockWeight = idx) } },
                                    )
                                }
                                ParameterFloatSlide(
                                    title = tr("classic_cc_header_date_size", "顶栏日期字号"),
                                    value = value.headerDateSizeSp,
                                    range = 10f..20f,
                                    defaultValue = 13f,
                                ) { v -> update { it.copy(headerDateSizeSp = v) } }
                                ParameterIntSlide(
                                    title = tr("classic_cc_header_height_offset", "顶栏高度微调"),
                                    value = value.headerHeightOffsetDp,
                                    range = -24..40,
                                    suffix = " dp",
                                    defaultValue = 0,
                                ) { n -> update { it.copy(headerHeightOffsetDp = n) } }
                                SwitchPreference(
                                    title = tr("classic_cc_header_hide_carrier", "隐藏顶栏运营商名称"),
                                    checked = value.headerHideCarrier,
                                    onCheckedChange = { hide -> update { it.copy(headerHideCarrier = hide) } },
                                )
                                SwitchPreference(
                                    title = tr("classic_cc_header_compact", "紧凑顶栏布局"),
                                    summary = tr("classic_cc_header_compact_summary", "收窄顶栏上下留白，让磁贴与通知获得更大展示区域。"),
                                    checked = value.headerCompactMode,
                                    onCheckedChange = { compact -> update { it.copy(headerCompactMode = compact) } },
                                )
                            }
                        }

                        item {
                            Group(tr("classic_cc_header_actions_group", "顶栏快捷功能按钮")) {
                                SwitchPreference(
                                    title = tr("classic_cc_header_show_settings", "显示系统设置按钮"),
                                    checked = value.headerShowSettingsButton,
                                    onCheckedChange = { show -> update { it.copy(headerShowSettingsButton = show) } },
                                )
                                SwitchPreference(
                                    title = tr("classic_cc_header_show_edit", "添加磁贴编辑快捷按钮"),
                                    summary = tr("classic_cc_header_show_edit_summary", "一键打开经典控制中心磁贴排序与编辑界面，无需再寻找编辑磁贴。"),
                                    checked = value.headerShowEditButton,
                                    onCheckedChange = { show -> update { it.copy(headerShowEditButton = show) } },
                                )
                                SwitchPreference(
                                    title = tr("classic_cc_header_show_power", "添加电源菜单快捷按钮"),
                                    summary = tr("classic_cc_header_show_power_summary", "在顶栏右侧增加电源图标，点击直接呼出系统关机与重启菜单。"),
                                    checked = value.headerShowPowerButton,
                                    onCheckedChange = { show -> update { it.copy(headerShowPowerButton = show) } },
                                )
                                SwitchPreference(
                                    title = tr("classic_cc_header_action_pill", "按钮现代圆形半透明底托"),
                                    checked = value.headerActionPillStyle,
                                    onCheckedChange = { pill -> update { it.copy(headerActionPillStyle = pill) } },
                                )
                                ParameterIntSlide(
                                    title = tr("classic_cc_header_action_size", "快捷按钮尺寸"),
                                    value = value.headerActionButtonSizeDp,
                                    range = 26..44,
                                    suffix = " dp",
                                    defaultValue = 34,
                                ) { n -> update { it.copy(headerActionButtonSizeDp = n) } }
                            }
                        }

                        item {
                            Group(tr("classic_cc_animation_group", "交互与动效")) {
                                SwitchPreference(
                                    title = tr("classic_cc_smooth_open_anim", "展开弹性微缩放动效"),
                                    summary = tr("classic_cc_smooth_open_anim_summary", "下拉展开完整磁贴面板时叠加细腻的缩放过渡，增强现代层次感。"),
                                    checked = value.smoothOpenAnimation,
                                    onCheckedChange = { enabled -> update { it.copy(smoothOpenAnimation = enabled) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    WindowDialog(
        show = showResetConfirmation,
        title = tr("classic_cc_reset_title", "恢复默认参数"),
        summary = tr("classic_cc_reset_message", "将经典控制中心优化的材质、磁贴、滑块与顶栏参数恢复为默认推荐值，不会关闭总开关。"),
        titleTextAlign = TextAlign.Left,
        summaryTextAlign = TextAlign.Left,
        onDismissRequest = { showResetConfirmation = false },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton({ showResetConfirmation = false }, Modifier.weight(1f)) {
                Text(tr("取消", "取消"))
            }
            GlassDialogButton(
                onClick = {
                    showResetConfirmation = false
                    update { it.resetParameters() }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(tr("确定", "确定"))
            }
        }
    }

    colorEdit?.let { target ->
        ClassicCcColorPickerDialog(
            title = target.title,
            initialColor = target.color,
            defaultColor = target.defaultColor,
            onDismiss = { colorEdit = null },
            onSave = { picked ->
                target.onSave(picked)
                colorEdit = null
            },
        )
    }
}

@Composable
internal fun ClassicControlCenterPresetsPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val changed = LocalNotificationSettingsChanged.current
    val store = remember(context) { ClassicControlCenterSettingsStore(context) }
    var presets by remember { mutableStateOf(store.presets) }
    var showSave by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var showNewPresetConfirmation by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ClassicControlCenterPreset?>(null) }
    var deleteConfirmation by remember { mutableStateOf<ClassicControlCenterPreset?>(null) }
    var export by remember { mutableStateOf<ClassicControlCenterPreset?>(null) }
    var currentSettings by remember { mutableStateOf(store.settings) }
    var appliedPresetId by remember { mutableStateOf<String?>(null) }
    var pendingJson by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<QrShareRequest?>(null) }
    var pendingQr by remember { mutableStateOf<QrShareRequest?>(null) }
    val coroutine = rememberCoroutineScope()

    fun toast(key: String) {
        Toast.makeText(context, tr(key, key), Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(service) {
        store.reload()
        service?.let(store::syncRemote)
        presets = store.presets
        currentSettings = store.settings
        appliedPresetId = store.appliedPresetId ?: (builtInClassicControlCenterPresets() + presets).firstOrNull {
            currentSettings.matchesParameters(it.settings)
        }?.id
    }

    DisposableEffect(store) {
        val preferences = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != KEY_CLASSIC_CC_PRESETS) {
                store.reload()
                currentSettings = store.settings
                appliedPresetId = store.appliedPresetId ?: appliedPresetId
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun applyPreset(preset: ClassicControlCenterPreset) {
        store.reload()
        store.applyPreset(service, preset)
        store.setAppliedPreset(service, preset.id)
        currentSettings = store.settings
        appliedPresetId = preset.id
        changed()
        toast("预设已应用")
    }

    fun hasUnsavedPresetChanges(): Boolean {
        val baseline = (builtInClassicControlCenterPresets() + presets).firstOrNull { it.id == appliedPresetId }?.settings
            ?: return false
        return !currentSettings.matchesParameters(baseline)
    }

    fun resetAndOpenNewPreset() {
        store.update(service) { it.resetParameters() }
        currentSettings = store.settings
        appliedPresetId = null
        store.setAppliedPreset(service, null)
        changed()
        saveAs = false
        showSave = true
    }

    fun beginNewPreset() {
        if (hasUnsavedPresetChanges()) {
            showNewPresetConfirmation = true
            return
        }
        resetAndOpenNewPreset()
    }

    fun importPayload(payload: String) {
        runCatching { ClassicControlCenterPresetCodec.decode(payload) }.onSuccess { preset ->
            store.reload()
            store.savePreset(service, preset)
            presets = store.presets
            applyPreset(preset)
            toast("预设已导入")
        }.onFailure { toast("导入失败") }
    }

    val jsonImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coroutine.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                        val buffer = CharArray(ClassicControlCenterPresetCodec.MAX_PAYLOAD_LENGTH + 1)
                        var length = 0
                        while (length < buffer.size) {
                            val count = it.read(buffer, length, buffer.size - length)
                            if (count < 0) break
                            length += count
                        }
                        require(length <= ClassicControlCenterPresetCodec.MAX_PAYLOAD_LENGTH)
                        String(buffer, 0, length)
                    } ?: error("Missing input")
                }
            }.onSuccess(::importPayload).onFailure { toast("导入失败") }
        }
    }

    val qrImport = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) coroutine.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 1
                        while (bounds.outWidth / inSampleSize > 2048 || bounds.outHeight / inSampleSize > 2048) inSampleSize *= 2
                    }
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                        ?: error("Missing image")
                    try { readPresetQrCode(bitmap) } finally { bitmap.recycle() }
                }
            }.onSuccess(::importPayload).onFailure { toast("导入失败") }
        }
    }

    val jsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = pendingJson
        pendingJson = null
        if (uri != null && payload != null) coroutine.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(payload) }
                        ?: error("Missing output")
                }
            }.onSuccess { toast("预设已导出") }.onFailure { toast("导出失败") }
        }
    }

    val qrExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val request = pendingQr
        pendingQr = null
        if (uri != null && request != null) coroutine.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bitmap = createPresetQrCode(request.payload)
                    try {
                        context.contentResolver.openOutputStream(uri)?.use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        } ?: error("Missing output")
                    } finally {
                        bitmap.recycle()
                    }
                }
            }.onSuccess { toast("预设已导出") }.onFailure { toast("导出失败") }
        }
    }

    fun display(preset: ClassicControlCenterPreset): ShadePreset = ShadePreset(
        if (preset.builtIn) tr(preset.name, preset.name) else preset.name,
        ClassicControlCenterPresetCodec.encode(preset),
        preset.builtIn,
    )

    AppPage(tr("classic_cc_presets", "经典控制中心预设"), back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Group(tr("classic_cc_presets", "经典控制中心预设")) {
                    Text(
                        tr("classic_cc_presets_hint", "所有预设同时应用材质、布局、磁贴、滑块与顶栏参数，不会更改总开关。点击或长按预设可保存、另存为、使用、导出或删除。"),
                        modifier = Modifier.padding(16.dp),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    SharedViewArrowPreference(title = tr("新建预设", "新建预设"), onClick = ::beginNewPreset)
                    SharedViewArrowPreference(title = tr("导入预设", "导入预设"), onClick = { showImport = true })
                }
            }
            item {
                Group(tr("内置预设", "内置预设")) {
                    builtInClassicControlCenterPresets().forEach { preset ->
                        val applied = appliedPresetId == preset.id
                        PresetRow(
                            display(preset),
                            applied = applied,
                            modified = applied && !currentSettings.matchesParameters(preset.settings),
                            onUse = { applyPreset(preset) },
                            onClick = { selected = preset },
                            onLongPress = { selected = preset },
                        )
                    }
                }
            }
            item {
                Group(tr("用户预设", "用户预设")) {
                    if (presets.isEmpty()) {
                        Text(
                            tr("暂无保存的用户预设", "暂无保存的用户预设"),
                            modifier = Modifier.padding(16.dp),
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                    presets.forEach { preset ->
                        val applied = appliedPresetId == preset.id
                        PresetRow(
                            display(preset),
                            applied = applied,
                            modified = applied && !currentSettings.matchesParameters(preset.settings),
                            onUse = { applyPreset(preset) },
                            onClick = { selected = preset },
                            onLongPress = { selected = preset },
                        )
                    }
                }
            }
        }
    }

    SavePresetDialog(showSave, { showSave = false }, titleKey = if (saveAs) "另存为" else "新建预设") { name ->
        store.reload()
        val preset = ClassicControlCenterPreset(
            name = name.trim(),
            settings = currentSettings.copy(enabled = false, unlockClassicInSystemUi = false).normalized(),
        )
        store.savePreset(service, preset)
        presets = store.presets
        appliedPresetId = preset.id
        store.setAppliedPreset(service, preset.id)
        showSave = false
        saveAs = false
        toast("预设已保存")
    }

    ImportPresetDialog(
        showImport,
        { showImport = false },
        { showImport = false; jsonImport.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        { showImport = false; qrImport.launch("image/*") },
    )

    NewPresetConfirmationDialog(
        show = showNewPresetConfirmation,
        onDismiss = { showNewPresetConfirmation = false },
        onConfirm = {
            showNewPresetConfirmation = false
            resetAndOpenNewPreset()
        },
    )

    selected?.let { preset ->
        SoftGlassPresetActionDialog(
            preset = display(preset),
            canSave = !preset.builtIn && !currentSettings.matchesParameters(preset.settings),
            onDismiss = { selected = null },
            onSave = {
                if (!preset.builtIn) {
                    val updated = preset.copy(
                        settings = currentSettings.copy(enabled = false, unlockClassicInSystemUi = false).normalized(),
                        builtIn = false,
                    )
                    store.savePreset(service, updated)
                    presets = store.presets
                    appliedPresetId = updated.id
                    store.setAppliedPreset(service, updated.id)
                    selected = null
                    toast("预设已保存")
                }
            },
            onSaveAs = { selected = null; saveAs = true; showSave = true },
            onUse = { applyPreset(preset); selected = null },
            onExport = { selected = null; export = preset },
            onDelete = { selected = null; deleteConfirmation = preset },
            editableName = !preset.builtIn,
            onRename = { name ->
                if (!preset.builtIn && name.isNotBlank()) {
                    store.renamePreset(service, preset.id, name)
                    presets = store.presets
                    selected = presets.firstOrNull { it.id == preset.id }
                }
            },
        )
    }

    deleteConfirmation?.let { preset ->
        PresetDeleteConfirmationDialog(
            name = display(preset).name,
            onDismiss = { deleteConfirmation = null },
            onConfirm = {
                store.deletePreset(service, preset.id)
                presets = store.presets
                if (appliedPresetId == preset.id) appliedPresetId = null
                deleteConfirmation = null
            },
        )
    }

    export?.let { preset ->
        ExportPresetDialog(
            true,
            display(preset),
            { export = null },
            {
                pendingJson = ClassicControlCenterPresetCodec.encode(preset)
                export = null
                jsonExport.launch("HyperChanger-classic-cc-preset.json")
            },
            {
                qr = QrShareRequest(display(preset).name, ClassicControlCenterPresetCodec.encode(preset))
                export = null
            },
        )
    }

    qr?.let { request ->
        QrShareDialog(
            request,
            { qr = null },
            {
                pendingQr = request
                qrExport.launch("HyperChanger-classic-cc-preset.png")
            },
        )
    }
}

@Composable
private fun ClassicCcColorRow(
    title: String,
    color: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MiuixTheme.textStyles.headline1, color = MiuixTheme.colorScheme.onSurface)
            Text(
                String.format(Locale.US, "#%08X", color),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Box(
            Modifier
                .size(28.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = .45f), CircleShape)
                .padding(2.dp)
                .background(ComposeColor(color), CircleShape),
        )
    }
}

@Composable
private fun ClassicCcColorPickerDialog(
    title: String,
    initialColor: Int,
    defaultColor: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var draftColor by remember(initialColor) { mutableIntStateOf(initialColor) }
    var hexText by remember(initialColor) {
        mutableStateOf(String.format(Locale.US, "%08X", initialColor))
    }
    val alphaValue = (draftColor ushr 24) and 0xFF

    fun setDraft(newColor: Int) {
        draftColor = newColor
        hexText = String.format(Locale.US, "%08X", newColor)
    }

    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                Box(
                    Modifier
                        .size(28.dp)
                        .background(MiuixTheme.colorScheme.outline.copy(alpha = .45f), CircleShape)
                        .padding(2.dp)
                        .background(ComposeColor(draftColor), CircleShape),
                )
            }
            ColorPalette(
                color = ComposeColor(draftColor or 0xFF000000.toInt()),
                onColorChanged = { picked ->
                    val rgb = picked.toArgb() and 0x00FFFFFF
                    setDraft((alphaValue shl 24) or rgb)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            ParameterIntSlide(
                title = tr("clearFrostColorAlpha", "透明度 (Alpha)"),
                value = alphaValue,
                range = 0..255,
                suffix = "",
                defaultValue = (defaultColor ushr 24) and 0xFF,
            ) { a ->
                setDraft((a.coerceIn(0, 255) shl 24) or (draftColor and 0x00FFFFFF))
            }
            TextField(
                value = hexText,
                onValueChange = { raw ->
                    val cleaned = raw.trim().removePrefix("#").uppercase(Locale.US).filter { it in '0'..'9' || it in 'A'..'F' }.take(8)
                    hexText = cleaned
                    if (cleaned.length == 8) {
                        runCatching { cleaned.toLong(16).toInt() }.onSuccess { draftColor = it }
                    } else if (cleaned.length == 6) {
                        runCatching { cleaned.toLong(16).toInt() }.onSuccess { rgb ->
                            draftColor = (alphaValue shl 24) or (rgb and 0x00FFFFFF)
                        }
                    }
                },
                label = "ARGB Hex (AARRGGBB)",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            GlassDialogButton(onClick = { setDraft(defaultColor) }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("恢复默认", "恢复默认"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = { onSave(draftColor) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("保存", "保存")) }
            }
        }
    }
}
