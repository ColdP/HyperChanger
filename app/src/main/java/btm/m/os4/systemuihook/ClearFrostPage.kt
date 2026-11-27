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

private data class ClearFrostColorEditTarget(
    val title: String,
    val color: Int,
    val defaultColor: Int,
    val onSave: (Int) -> Unit,
)

@Composable
internal fun ClearFrostParametersPage(openPresets: () -> Unit, back: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    var showAdvancedStrokeLights by rememberSaveable { mutableStateOf(false) }
    var colorEdit by remember { mutableStateOf<ClearFrostColorEditTarget?>(null) }
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val changed = LocalNotificationSettingsChanged.current
    val store = remember(context) { ClearFrostSettingsStore(context) }
    var value by remember { mutableStateOf(store.settings) }
    LaunchedEffect(service) {
        store.reload()
        service?.let(store::syncRemote)
        value = store.settings
    }
    val updateFrost: ((ClearFrostSettings) -> ClearFrostSettings) -> Unit = { transform ->
        store.update(service, transform)
        value = store.settings
        changed()
    }
    var lastLightStyle by rememberSaveable {
        mutableStateOf(
            value.lightStyle.takeUnless { it == CLEAR_FROST_STYLE_CUSTOM } ?: CLEAR_FROST_DEFAULT_LIGHT_STYLE,
        )
    }
    var lastDarkStyle by rememberSaveable {
        mutableStateOf(
            value.darkStyle.takeUnless { it == CLEAR_FROST_STYLE_CUSTOM } ?: CLEAR_FROST_DEFAULT_DARK_STYLE,
        )
    }
    var lastNotifLightStyle by rememberSaveable {
        mutableStateOf(
            value.notificationLightStyle.takeUnless { it == CLEAR_FROST_STYLE_CUSTOM } ?: CLEAR_FROST_DEFAULT_LIGHT_STYLE,
        )
    }
    var lastNotifDarkStyle by rememberSaveable {
        mutableStateOf(
            value.notificationDarkStyle.takeUnless { it == CLEAR_FROST_STYLE_CUSTOM } ?: CLEAR_FROST_DEFAULT_DARK_STYLE,
        )
    }
    var lastCcLightStyle by rememberSaveable {
        mutableStateOf(
            value.controlCenterLightStyle.takeUnless { it == CLEAR_FROST_STYLE_CUSTOM } ?: CLEAR_FROST_DEFAULT_LIGHT_STYLE,
        )
    }
    var lastCcDarkStyle by rememberSaveable {
        mutableStateOf(
            value.controlCenterDarkStyle.takeUnless { it == CLEAR_FROST_STYLE_CUSTOM } ?: CLEAR_FROST_DEFAULT_DARK_STYLE,
        )
    }
    LaunchedEffect(value) {
        if (value.lightStyle != CLEAR_FROST_STYLE_CUSTOM) lastLightStyle = value.lightStyle
        if (value.darkStyle != CLEAR_FROST_STYLE_CUSTOM) lastDarkStyle = value.darkStyle
        if (value.notificationLightStyle != CLEAR_FROST_STYLE_CUSTOM) lastNotifLightStyle = value.notificationLightStyle
        if (value.notificationDarkStyle != CLEAR_FROST_STYLE_CUSTOM) lastNotifDarkStyle = value.notificationDarkStyle
        if (value.controlCenterLightStyle != CLEAR_FROST_STYLE_CUSTOM) lastCcLightStyle = value.controlCenterLightStyle
        if (value.controlCenterDarkStyle != CLEAR_FROST_STYLE_CUSTOM) lastCcDarkStyle = value.controlCenterDarkStyle
    }
    val customBlendEnabled = value.usesCustomLightStyle() || value.usesCustomDarkStyle()
    val defaultTag = tr("clearFrostDefaultTag", "默认")
    val lightStyleLabels = clearFrostSelectableLightStyles.map { style ->
        val label = tr(style.titleKey, style.defaultTitle)
        if (style.id == CLEAR_FROST_DEFAULT_LIGHT_STYLE) "$label ($defaultTag)" else label
    }
    val darkStyleLabels = clearFrostSelectableDarkStyles.map { style ->
        val label = tr(style.titleKey, style.defaultTitle)
        if (style.id == CLEAR_FROST_DEFAULT_DARK_STYLE) "$label ($defaultTag)" else label
    }
    val ccThemeLabels = listOf(
        tr("clearFrostThemeSystem", "跟随系统"),
        tr("clearFrostThemeLight", "浅色模式"),
        tr("clearFrostThemeDark", "深色模式"),
        tr("clearFrostThemeInverse", "反系统跟随"),
    )
    val strokePresetLabels = listOf(
        "${tr("clearFrostStrokeSmall", "小尺寸")} ($defaultTag)",
        tr("clearFrostStrokeMiddle", "中尺寸"),
        tr("clearFrostStrokeBig", "大尺寸"),
        tr("clearFrostStrokeAngle45", "45° 斜射光源"),
        tr("clearFrostStrokeWide", "宽光场"),
        tr("clearFrostStrokeAngle45Wide", "45° 斜射 + 宽光场"),
        tr("clearFrostStrokeCustom", "自定义"),
    )
    fun lightIndex(id: String): Int =
        clearFrostSelectableLightStyles.indexOfFirst { it.id == id }
            .takeIf { it >= 0 }
            ?: clearFrostSelectableLightStyles.indexOfFirst { it.id == CLEAR_FROST_DEFAULT_LIGHT_STYLE }.coerceAtLeast(0)
    fun darkIndex(id: String): Int =
        clearFrostSelectableDarkStyles.indexOfFirst { it.id == id }
            .takeIf { it >= 0 }
            ?: clearFrostSelectableDarkStyles.indexOfFirst { it.id == CLEAR_FROST_DEFAULT_DARK_STYLE }.coerceAtLeast(0)

    AppPage(
        tr("clearFrostTitle", "清透磨砂材质参数调整"),
        back,
        restartScopes = setOf(ScopeApplication.SYSTEM_UI),
        header = {
            SoftGlassHeaderTabs(
                listOf(
                    tr("clearFrostTabFrost", "磨砂参数调整"),
                    tr("背景调整", "背景调整"),
                ),
                tab,
                { tab = it },
            )
        },
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Group(tr("clearFrostPresets", "清透磨砂材质预设")) {
                    SharedViewArrowPreference(
                        title = tr("clearFrostPresets", "清透磨砂材质预设"),
                        onClick = openPresets,
                    )
                    SharedViewArrowPreference(
                        title = tr("clearFrostReset", "恢复清透磨砂默认参数"),
                        onClick = { showResetConfirmation = true },
                    )
                }
            }
            if (tab == 0) {
                item {
                    Group(tr("clearFrostMaterialGroup", "材质混色")) {
                        Text(
                            tr("clearFrostMaterialHint", "仅在系统使用清透磨砂材质时生效，不影响柔光玻璃。点击滑块数值可精确输入或恢复默认。"),
                            modifier = Modifier.padding(16.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        SwitchPreference(
                            title = tr("clearFrostCustomBlendEnabled", "启用自定义混色"),
                            summary = tr("clearFrostCustomBlendSummary", "关闭时直接选用系统材质预设，开启后可自由配置浅色与深色混色层。"),
                            checked = customBlendEnabled,
                            onCheckedChange = { useCustom ->
                                if (useCustom) {
                                    updateFrost {
                                        it.copy(
                                            lightStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            darkStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            notificationLightStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            notificationDarkStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            controlCenterLightStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            controlCenterDarkStyle = CLEAR_FROST_STYLE_CUSTOM,
                                        )
                                    }
                                } else {
                                    updateFrost {
                                        it.copy(
                                            lightStyle = lastLightStyle,
                                            darkStyle = lastDarkStyle,
                                            notificationLightStyle = lastNotifLightStyle,
                                            notificationDarkStyle = lastNotifDarkStyle,
                                            controlCenterLightStyle = lastCcLightStyle,
                                            controlCenterDarkStyle = lastCcDarkStyle,
                                        )
                                    }
                                }
                            },
                        )
                        AnimatedVisibility(
                            visible = !customBlendEnabled,
                            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                        ) {
                            Column {
                                if (!value.separateScopes) {
                                    OverlayDropdownPreference(
                                        title = tr("clearFrostLightStyle", "浅色模式材质"),
                                        items = lightStyleLabels,
                                        selectedIndex = lightIndex(value.lightStyle),
                                        onSelectedIndexChange = { idx ->
                                            updateFrost { it.copy(lightStyle = clearFrostSelectableLightStyles[idx].id) }
                                        },
                                    )
                                    OverlayDropdownPreference(
                                        title = tr("clearFrostDarkStyle", "深色模式材质"),
                                        items = darkStyleLabels,
                                        selectedIndex = darkIndex(value.darkStyle),
                                        onSelectedIndexChange = { idx ->
                                            updateFrost { it.copy(darkStyle = clearFrostSelectableDarkStyles[idx].id) }
                                        },
                                    )
                                } else {
                                    OverlayDropdownPreference(
                                        title = tr("clearFrostNotifLightStyle", "通知中心浅色材质"),
                                        items = lightStyleLabels,
                                        selectedIndex = lightIndex(value.notificationLightStyle),
                                        onSelectedIndexChange = { idx ->
                                            updateFrost { it.copy(notificationLightStyle = clearFrostSelectableLightStyles[idx].id) }
                                        },
                                    )
                                    OverlayDropdownPreference(
                                        title = tr("clearFrostNotifDarkStyle", "通知中心深色材质"),
                                        items = darkStyleLabels,
                                        selectedIndex = darkIndex(value.notificationDarkStyle),
                                        onSelectedIndexChange = { idx ->
                                            updateFrost { it.copy(notificationDarkStyle = clearFrostSelectableDarkStyles[idx].id) }
                                        },
                                    )
                                    OverlayDropdownPreference(
                                        title = tr("clearFrostCcLightStyle", "控制中心浅色材质"),
                                        items = lightStyleLabels,
                                        selectedIndex = lightIndex(value.controlCenterLightStyle),
                                        onSelectedIndexChange = { idx ->
                                            updateFrost { it.copy(controlCenterLightStyle = clearFrostSelectableLightStyles[idx].id) }
                                        },
                                    )
                                    OverlayDropdownPreference(
                                        title = tr("clearFrostCcDarkStyle", "控制中心深色材质"),
                                        items = darkStyleLabels,
                                        selectedIndex = darkIndex(value.controlCenterDarkStyle),
                                        onSelectedIndexChange = { idx ->
                                            updateFrost { it.copy(controlCenterDarkStyle = clearFrostSelectableDarkStyles[idx].id) }
                                        },
                                    )
                                }
                                SwitchPreference(
                                    title = tr("clearFrostSeparateScopes", "通知与控制中心分开设置"),
                                    checked = value.separateScopes,
                                    onCheckedChange = { separate -> updateFrost { it.copy(separateScopes = separate) } },
                                )
                            }
                        }
                        OverlayDropdownPreference(
                            title = tr("clearFrostCcThemeMode", "控制中心明暗外观"),
                            items = ccThemeLabels,
                            selectedIndex = value.controlCenterThemeMode,
                            onSelectedIndexChange = { idx -> updateFrost { it.copy(controlCenterThemeMode = idx) } },
                        )
                        ParameterIntSlide(
                            title = tr("clearFrostElementOpacity", "元素混色不透明度"),
                            value = value.elementOpacity,
                            range = 0..200,
                            suffix = "%",
                            defaultValue = 100,
                        ) { n -> updateFrost { it.copy(elementOpacity = n) } }
                    }
                }
                if (customBlendEnabled) {
                    item {
                        Group(tr("clearFrostCustomLightGroup", "自定义浅色混色")) {
                            ClearFrostColorRow(
                                title = tr("clearFrostBlendLayer1", "混色层 1"),
                                color = value.customLightColor1,
                            ) {
                                colorEdit = ClearFrostColorEditTarget(
                                    title = tr("clearFrostCustomLight1", "浅色混色层 1"),
                                    color = value.customLightColor1,
                                    defaultColor = CLEAR_FROST_DEFAULT_LIGHT_COLOR_1,
                                ) { c ->
                                    updateFrost {
                                        it.copy(
                                            lightStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            notificationLightStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            controlCenterLightStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            customLightColor1 = c,
                                        )
                                    }
                                }
                            }
                            ClearFrostColorRow(
                                title = tr("clearFrostBlendLayer2", "混色层 2"),
                                color = value.customLightColor2,
                            ) {
                                colorEdit = ClearFrostColorEditTarget(
                                    title = tr("clearFrostCustomLight2", "浅色混色层 2"),
                                    color = value.customLightColor2,
                                    defaultColor = CLEAR_FROST_DEFAULT_LIGHT_COLOR_2,
                                ) { c ->
                                    updateFrost {
                                        it.copy(
                                            lightStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            notificationLightStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            controlCenterLightStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            customLightColor2 = c,
                                        )
                                    }
                                }
                            }
                            ClearFrostColorRow(
                                title = tr("clearFrostBlendLayer3", "混色层 3"),
                                color = value.customLightColor3,
                            ) {
                                colorEdit = ClearFrostColorEditTarget(
                                    title = tr("clearFrostCustomLight3", "浅色混色层 3"),
                                    color = value.customLightColor3,
                                    defaultColor = CLEAR_FROST_DEFAULT_LIGHT_COLOR_3,
                                ) { c ->
                                    updateFrost {
                                        it.copy(
                                            lightStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            notificationLightStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            controlCenterLightStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            customLightColor3 = c,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Group(tr("clearFrostCustomDarkGroup", "自定义深色混色")) {
                            ClearFrostColorRow(
                                title = tr("clearFrostBlendLayer1", "混色层 1"),
                                color = value.customDarkColor1,
                            ) {
                                colorEdit = ClearFrostColorEditTarget(
                                    title = tr("clearFrostCustomDark1", "深色混色层 1"),
                                    color = value.customDarkColor1,
                                    defaultColor = CLEAR_FROST_DEFAULT_DARK_COLOR_1,
                                ) { c ->
                                    updateFrost {
                                        it.copy(
                                            darkStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            notificationDarkStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            controlCenterDarkStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            customDarkColor1 = c,
                                        )
                                    }
                                }
                            }
                            ClearFrostColorRow(
                                title = tr("clearFrostBlendLayer2", "混色层 2"),
                                color = value.customDarkColor2,
                            ) {
                                colorEdit = ClearFrostColorEditTarget(
                                    title = tr("clearFrostCustomDark2", "深色混色层 2"),
                                    color = value.customDarkColor2,
                                    defaultColor = CLEAR_FROST_DEFAULT_DARK_COLOR_2,
                                ) { c ->
                                    updateFrost {
                                        it.copy(
                                            darkStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            notificationDarkStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            controlCenterDarkStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            customDarkColor2 = c,
                                        )
                                    }
                                }
                            }
                            ClearFrostColorRow(
                                title = tr("clearFrostBlendLayer3", "混色层 3"),
                                color = value.customDarkColor3,
                            ) {
                                colorEdit = ClearFrostColorEditTarget(
                                    title = tr("clearFrostCustomDark3", "深色混色层 3"),
                                    color = value.customDarkColor3,
                                    defaultColor = CLEAR_FROST_DEFAULT_DARK_COLOR_3,
                                ) { c ->
                                    updateFrost {
                                        it.copy(
                                            darkStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            notificationDarkStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            controlCenterDarkStyle = CLEAR_FROST_STYLE_CUSTOM,
                                            customDarkColor3 = c,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Group(tr("clearFrostStrokeGroup", "高光描边")) {
                        SwitchPreference(
                            title = tr("clearFrostStrokeEnabled", "启用高光描边"),
                            summary = tr("clearFrostStrokeSummary", "为通知卡片与控制中心磨砂元素叠加柔和的边缘高光。"),
                            checked = value.strokeEnabled,
                            onCheckedChange = { enabled -> updateFrost { it.copy(strokeEnabled = enabled) } },
                        )
                        AnimatedVisibility(
                            visible = value.strokeEnabled,
                            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                        ) {
                            Column {
                                OverlayDropdownPreference(
                                    title = tr("clearFrostStrokePreset", "描边样式"),
                                    items = strokePresetLabels,
                                    selectedIndex = clearFrostStrokePresets.indexOfFirst { it.first == value.strokePreset }.coerceAtLeast(0),
                                    onSelectedIndexChange = { idx ->
                                        updateFrost { it.copy(strokePreset = clearFrostStrokePresets[idx].first) }
                                    },
                                )
                                AnimatedVisibility(
                                    visible = value.strokePreset == CLEAR_FROST_STROKE_CUSTOM,
                                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                                ) {
                                    Column {
                                        ParameterDpSlide(
                                            title = tr("clearFrostStrokeWidth", "主描边宽度"),
                                            value = value.strokeNormalWidth,
                                            range = 0f..6f,
                                            defaultValue = 2.6f,
                                        ) { v -> updateFrost { it.copy(strokeNormalWidth = v) } }
                                        ParameterIntSlide(
                                            title = tr("clearFrostStrokeSource1Alpha", "主光源高光强度"),
                                            value = value.strokeSource1Alpha,
                                            range = 0..100,
                                            suffix = "%",
                                            defaultValue = 60,
                                        ) { v -> updateFrost { it.copy(strokeSource1Alpha = v) } }
                                        ParameterIntSlide(
                                            title = tr("clearFrostStrokeSource2Alpha", "副光源高光强度"),
                                            value = value.strokeSource2Alpha,
                                            range = 0..100,
                                            suffix = "%",
                                            defaultValue = 35,
                                        ) { v -> updateFrost { it.copy(strokeSource2Alpha = v) } }
                                        ParameterDpSlide(
                                            title = tr("clearFrostStrokeBloomWidth", "泛光描边宽度"),
                                            value = value.strokeBloomWidth,
                                            range = 0f..5f,
                                            defaultValue = 0.8f,
                                        ) { v -> updateFrost { it.copy(strokeBloomWidth = v) } }
                                        ParameterIntSlide(
                                            title = tr("clearFrostStrokeBloomAlpha", "泛光强度"),
                                            value = value.strokeBloomAlpha,
                                            range = 0..100,
                                            suffix = "%",
                                            defaultValue = 5,
                                        ) { v -> updateFrost { it.copy(strokeBloomAlpha = v) } }
                                        ParameterIntSlide(
                                            title = tr("clearFrostStrokeBloomDegree", "泛光渐变角度"),
                                            value = value.strokeBloomDegree,
                                            range = 0..360,
                                            suffix = "°",
                                            defaultValue = 180,
                                        ) { v -> updateFrost { it.copy(strokeBloomDegree = v) } }
                                        SwitchPreference(
                                            title = tr("clearFrostStrokeAdvancedLights", "高级光源方向调整"),
                                            checked = showAdvancedStrokeLights,
                                            onCheckedChange = { showAdvancedStrokeLights = it },
                                        )
                                        AnimatedVisibility(visible = showAdvancedStrokeLights) {
                                            Column {
                                                ParameterFloatSlide(tr("clearFrostLight1X", "主光源 X"), value.strokeSource1X, -1f..1f, 0.5f) { v ->
                                                    updateFrost { it.copy(strokeSource1X = v) }
                                                }
                                                ParameterFloatSlide(tr("clearFrostLight1Y", "主光源 Y"), value.strokeSource1Y, -1f..1f, 0.5f) { v ->
                                                    updateFrost { it.copy(strokeSource1Y = v) }
                                                }
                                                ParameterFloatSlide(tr("clearFrostLight1Z", "主光源 Z"), value.strokeSource1Z, -1f..1f, -0.5f) { v ->
                                                    updateFrost { it.copy(strokeSource1Z = v) }
                                                }
                                                ParameterFloatSlide(tr("clearFrostLight2X", "副光源 X"), value.strokeSource2X, -1f..1f, 0.5f) { v ->
                                                    updateFrost { it.copy(strokeSource2X = v) }
                                                }
                                                ParameterFloatSlide(tr("clearFrostLight2Y", "副光源 Y"), value.strokeSource2Y, -1f..1f, 0.95f) { v ->
                                                    updateFrost { it.copy(strokeSource2Y = v) }
                                                }
                                                ParameterFloatSlide(tr("clearFrostLight2Z", "副光源 Z"), value.strokeSource2Z, -1f..1f, -0.5f) { v ->
                                                    updateFrost { it.copy(strokeSource2Z = v) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Group(tr("clearFrostShadowGroup", "表面阴影")) {
                        SwitchPreference(
                            title = tr("clearFrostShadowEnabled", "启用表面阴影"),
                            summary = tr("clearFrostShadowSummary", "为控制中心磨砂卡片与磁贴叠加立体阴影。"),
                            checked = value.shadowEnabled,
                            onCheckedChange = { enabled -> updateFrost { it.copy(shadowEnabled = enabled) } },
                        )
                        AnimatedVisibility(
                            visible = value.shadowEnabled,
                            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                        ) {
                            Column {
                                ParameterIntSlide(
                                    title = tr("clearFrostShadowAlpha", "阴影不透明度"),
                                    value = value.shadowAlpha,
                                    range = 0..100,
                                    suffix = "%",
                                    defaultValue = 35,
                                ) { v -> updateFrost { it.copy(shadowAlpha = v) } }
                                ParameterIntSlide(
                                    title = tr("clearFrostShadowOffsetY", "阴影纵向偏移"),
                                    value = value.shadowOffsetY,
                                    range = 0..80,
                                    suffix = " px",
                                    defaultValue = 30,
                                ) { v -> updateFrost { it.copy(shadowOffsetY = v) } }
                                ParameterIntSlide(
                                    title = tr("clearFrostShadowRadius", "阴影模糊半径"),
                                    value = value.shadowRadius,
                                    range = 0..100,
                                    suffix = " px",
                                    defaultValue = 26,
                                ) { v -> updateFrost { it.copy(shadowRadius = v) } }
                            }
                        }
                    }
                }
            } else {
                item {
                    Group(tr("背景调整", "背景调整")) {
                        Text(
                            tr("clearFrostBackgroundHint", "统一调整清透磨砂模式下通知中心与控制中心采样背景。100% 保持系统值，0% 关闭对应效果；开启自定义取色可覆盖背景明度混色并保留展开动画。"),
                            modifier = Modifier.padding(16.dp),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        ParameterIntSlide(
                            tr("softGlassBackgroundBlur", "背景模糊度"),
                            value.backgroundBlur,
                            0..200,
                            "%",
                            100,
                        ) { n -> updateFrost { it.copy(backgroundBlur = n) } }
                        ParameterIntSlide(
                            tr("softGlassBackgroundScale", "背景缩放程度"),
                            value.backgroundScale,
                            0..200,
                            "%",
                            100,
                        ) { n -> updateFrost { it.copy(backgroundScale = n) } }
                        ParameterIntSlide(
                            tr("背景不透明度", "背景不透明度"),
                            value.backgroundOpacity,
                            0..200,
                            "%",
                            100,
                        ) { n -> updateFrost { it.copy(backgroundOpacity = n) } }
                        SwitchPreference(
                            title = tr("clearFrostCustomShadeBlend", "自定义背景模糊取色"),
                            checked = value.customShadeBlendEnabled,
                            onCheckedChange = { enabled -> updateFrost { it.copy(customShadeBlendEnabled = enabled) } },
                        )
                        AnimatedVisibility(visible = value.customShadeBlendEnabled) {
                            ClearFrostColorRow(
                                title = tr("clearFrostShadeBlendColor", "背景模糊混色"),
                                color = value.customShadeBlendColor,
                            ) {
                                colorEdit = ClearFrostColorEditTarget(
                                    title = tr("clearFrostShadeBlendColor", "背景模糊混色"),
                                    color = value.customShadeBlendColor,
                                    defaultColor = CLEAR_FROST_DEFAULT_SHADE_BLEND_COLOR,
                                ) { c -> updateFrost { it.copy(customShadeBlendColor = c) } }
                            }
                        }
                    }
                }
            }
        }
    }

    WindowDialog(
        show = showResetConfirmation,
        title = tr("clearFrostReset", "恢复清透磨砂默认参数"),
        summary = tr("clearFrostResetConfirm", "确定将清透磨砂的材质、描边、阴影和背景参数恢复为默认值吗？"),
        titleTextAlign = TextAlign.Left,
        summaryTextAlign = TextAlign.Left,
        onDismissRequest = { showResetConfirmation = false },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton(
                onClick = { showResetConfirmation = false },
                modifier = Modifier.weight(1f),
            ) { Text(tr("取消", "取消")) }
            GlassDialogButton(
                onClick = {
                    showResetConfirmation = false
                    updateFrost { it.resetParameters() }
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("确认", "确认")) }
        }
    }

    colorEdit?.let { target ->
        ClearFrostArgbColorDialog(
            show = true,
            title = target.title,
            initialColor = target.color,
            defaultColor = target.defaultColor,
            onDismiss = { colorEdit = null },
            onSave = { selectedColor ->
                target.onSave(selectedColor)
                colorEdit = null
            },
        )
    }
}

@Composable
private fun ClearFrostColorRow(
    title: String,
    color: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            String.format(Locale.US, "#%08X", color),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
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
private fun ClearFrostArgbColorDialog(
    show: Boolean,
    title: String,
    initialColor: Int,
    defaultColor: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var draftColor by remember(show, initialColor) { mutableIntStateOf(initialColor) }
    var hexText by remember(show, initialColor) {
        mutableStateOf(String.format(Locale.US, "%08X", initialColor))
    }
    val alphaValue = (draftColor ushr 24) and 0xFF

    fun setDraft(newColor: Int) {
        draftColor = newColor
        hexText = String.format(Locale.US, "%08X", newColor)
    }

    WindowDialog(show = show, onDismissRequest = onDismiss) {
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

@Composable
internal fun ClearFrostPresetsPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val changed = LocalNotificationSettingsChanged.current
    val store = remember(context) { ClearFrostSettingsStore(context) }
    var presets by remember { mutableStateOf(store.presets) }
    var showSave by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var showNewPresetConfirmation by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ClearFrostPreset?>(null) }
    var deleteConfirmation by remember { mutableStateOf<ClearFrostPreset?>(null) }
    var export by remember { mutableStateOf<ClearFrostPreset?>(null) }
    var currentSettings by remember { mutableStateOf(store.settings) }
    var appliedPresetId by remember { mutableStateOf<String?>(null) }
    var pendingJson by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<QrShareRequest?>(null) }
    var pendingQr by remember { mutableStateOf<QrShareRequest?>(null) }
    val coroutine = rememberCoroutineScope()
    fun toast(key: String) { Toast.makeText(context, tr(key, key), Toast.LENGTH_SHORT).show() }
    LaunchedEffect(service) {
        store.reload()
        service?.let(store::syncRemote)
        presets = store.presets
        currentSettings = store.settings
        appliedPresetId = store.appliedPresetId ?: (builtInClearFrostPresets() + presets).firstOrNull {
            currentSettings.matchesParameters(it.settings)
        }?.id
    }
    DisposableEffect(store) {
        val preferences = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != KEY_CLEAR_FROST_PRESETS) {
                store.reload()
                currentSettings = store.settings
                appliedPresetId = store.appliedPresetId ?: appliedPresetId
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun applyPreset(preset: ClearFrostPreset) {
        store.reload()
        store.applyPreset(service, preset)
        store.setAppliedPreset(service, preset.id)
        currentSettings = store.settings
        appliedPresetId = preset.id
        changed()
        toast("预设已应用")
    }
    fun hasUnsavedPresetChanges(): Boolean {
        val baseline = (builtInClearFrostPresets() + presets).firstOrNull { it.id == appliedPresetId }?.settings
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
        runCatching { ClearFrostPresetCodec.decode(payload) }.onSuccess { preset ->
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
                        val buffer = CharArray(ClearFrostPresetCodec.MAX_PAYLOAD_LENGTH + 1)
                        var length = 0
                        while (length < buffer.size) {
                            val count = it.read(buffer, length, buffer.size - length)
                            if (count < 0) break
                            length += count
                        }
                        require(length <= ClearFrostPresetCodec.MAX_PAYLOAD_LENGTH)
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
    fun display(preset: ClearFrostPreset): ShadePreset = ShadePreset(
        if (preset.builtIn) tr(preset.name, preset.name) else preset.name,
        ClearFrostPresetCodec.encode(preset),
        preset.builtIn,
    )
    AppPage(tr("clearFrostPresets", "清透磨砂材质预设"), back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Group(tr("clearFrostPresets", "清透磨砂材质预设")) {
                    Text(
                        tr("clearFrostPresetHint", "所有预设同时应用材质混色、描边阴影与背景参数，不会更改总开关。点击或长按预设可保存、另存为、使用、导出或删除。"),
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
                    builtInClearFrostPresets().forEach { preset ->
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
        val preset = ClearFrostPreset(name.trim(), currentSettings.copy(enabled = false).normalized())
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
                    val updated = preset.copy(settings = currentSettings.copy(enabled = false).normalized(), builtIn = false)
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
            { pendingJson = ClearFrostPresetCodec.encode(preset); export = null; jsonExport.launch("HyperChanger-clear-frost-preset.json") },
            { qr = QrShareRequest(display(preset).name, ClearFrostPresetCodec.encode(preset)); export = null },
        )
    }
    qr?.let { request ->
        QrShareDialog(request, { qr = null }, { pendingQr = request; qrExport.launch("HyperChanger-clear-frost-preset.png") })
    }
}
