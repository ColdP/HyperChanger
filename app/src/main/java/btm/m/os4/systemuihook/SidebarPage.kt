// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun SidebarPage(openGlass: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context, service) { SidebarSettingsStore(context) }
    var settings by remember(store) { mutableStateOf(store.settings) }
    val featureStore = remember(context, service) { SidebarFeatureSettingsStore(context) }
    var features by remember(featureStore) { mutableStateOf(featureStore.settings) }
    val appearanceStore = remember(context, service) { SidebarAppearanceSettingsStore(context) }
    var appearance by remember(appearanceStore) { mutableStateOf(appearanceStore.settings) }
    val spacingStore = remember(context, service) { SidebarColumnSpacingSettingsStore(context) }
    var spacing by remember(spacingStore) { mutableStateOf(spacingStore.settings) }
    val shapeStore = remember(context, service) { SidebarShapeSettingsStore(context) }
    var shape by remember(shapeStore) { mutableStateOf(shapeStore.settings) }
    val countStore = remember(context, service) { SidebarIconCountSettingsStore(context) }
    var iconCount by remember(countStore) { mutableStateOf(countStore.settings) }
    fun updateAppearance(transform: (SidebarAppearanceSettings) -> SidebarAppearanceSettings) {
        appearanceStore.update(service, transform)
        appearance = appearanceStore.settings
    }
    AppPage(tr("侧边栏", "侧边栏"), back,
        restartScopes = setOf(ScopeApplication.SECURITY_CENTER), restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Group(tr("侧边栏功能", "侧边栏功能")) {
                    SwitchPreference(
                        title = tr("侧边栏启用双列应用图标", "侧边栏启用双列应用图标"),
                        checked = appearance.dualRowApps,
                        onCheckedChange = { value -> updateAppearance { it.copy(dualRowApps = value) } },
                    )
                    OverlayDropdownPreference(
                        title = tr("自定义侧边栏图标显示数量", "自定义侧边栏图标显示数量"),
                        items = sidebarIconCountOptions.map { count -> when (count) {
                            0 -> tr("系统默认", "系统默认")
                            -1 -> tr("自定义", "自定义")
                            else -> tr(count.toString(), count.toString())
                        } },
                        selectedIndex = iconCount.mode,
                        onSelectedIndexChange = { value ->
                            countStore.update(service) { it.copy(mode = value) }
                            iconCount = countStore.settings
                        },
                    )
                    PortalAppearanceVisibility(iconCount.mode == SIDEBAR_ICON_COUNT_CUSTOM_MODE) {
                        var current by remember(iconCount.custom) { mutableFloatStateOf(iconCount.custom.toFloat()) }
                        EditableSliderPreference(
                            title = tr("侧边栏图标数量", "侧边栏图标数量"),
                            value = current, valueText = tr("%d 个", "%d 个").format(current.roundToInt()),
                            valueRange = 1f..100f, steps = 98, defaultValue = 16f,
                            onValueChange = { current = it },
                            onValueChangeFinished = {
                                countStore.update(service) { it.copy(custom = current.roundToInt()) }
                                iconCount = countStore.settings
                            },
                        )
                    }
                    TemperatureWarningSwitch(
                        title = tr("去除视频工具箱的温度限制", "去除视频工具箱的温度限制"),
                        checked = features.removeVideoTemperatureLimit,
                        onCheckedChange = { value ->
                            featureStore.update(service) { it.copy(removeVideoTemperatureLimit = value) }
                            features = featureStore.settings
                        },
                    )
                }
            }
            item {
                Group(tr("外观调整", "外观调整")) {
                    SharedViewArrowPreference(
                        title = tr("侧边栏柔光玻璃参数调整", "侧边栏柔光玻璃参数调整"), onClick = openGlass,
                    )
                    SwitchPreference(
                        title = tr("统一侧边栏各卡片的材质", "统一侧边栏各卡片的材质"),
                        checked = settings.unifyCards,
                        onCheckedChange = { value ->
                            store.update(service) { it.copy(unifyCards = value) }
                            settings = store.settings
                        },
                    )
                    SidebarPreferenceVisibility(appearance.dualRowApps) {
                        SwitchPreference(
                            title = tr("自定义双列图标列间距", "自定义双列图标列间距"),
                            checked = spacing.enabled,
                            onCheckedChange = { value ->
                                spacingStore.update(service) { it.copy(enabled = value) }
                                spacing = spacingStore.settings
                            },
                        )
                        SidebarPreferenceVisibility(spacing.enabled) {
                            var current by remember(spacing.spacing) { mutableFloatStateOf(spacing.spacing) }
                            EditableSliderPreference(
                                title = tr("双列图标列间距", "双列图标列间距"),
                                value = current, onValueChange = { current = it },
                                onValueChangeFinished = {
                                    spacingStore.update(service) { it.copy(spacing = current) }
                                    spacing = spacingStore.settings
                                },
                                valueText = if (current == 0f) tr("侧边栏间距系统默认", "0.00 dp（系统默认）")
                                    else tr("侧边栏间距数值", "%+.2f dp").format(Locale.US, current),
                                valueRange = -32f..64f, steps = 9599, defaultValue = 0f, centerValue = 0f,
                            )
                        }
                    }
                    SwitchPreference(
                        title = tr("侧边栏卡片圆角调整", "侧边栏卡片圆角调整"),
                        checked = shape.radiusEnabled,
                        onCheckedChange = { value ->
                            shapeStore.update(service) { it.copy(radiusEnabled = value) }
                            shape = shapeStore.settings
                        },
                    )
                    SidebarPreferenceVisibility(shape.radiusEnabled) {
                        var current by remember(shape.radius) { mutableFloatStateOf(shape.radius) }
                        EditableSliderPreference(
                            title = tr("侧边栏卡片圆角", "侧边栏卡片圆角"),
                            value = current, onValueChange = { current = it },
                            onValueChangeFinished = {
                                shapeStore.update(service) { it.copy(radius = current) }
                                shape = shapeStore.settings
                            },
                            valueText = if (current == 0f) tr("侧边栏圆角系统默认", "0.00 dp（系统默认）")
                                else tr("侧边栏圆角数值", "%+.2f dp").format(Locale.US, current),
                            valueRange = -32f..64f, steps = 9599, defaultValue = 0f, centerValue = 0f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SwitchPreference(
                        title = tr("替换展开全部应用按钮为胶囊形状", "替换展开全部应用按钮为胶囊形状"),
                        checked = shape.capsuleButton,
                        onCheckedChange = { value ->
                            shapeStore.update(service) { it.copy(capsuleButton = value) }
                            shape = shapeStore.settings
                        },
                    )
                    SidebarPreferenceVisibility(shape.capsuleButton) {
                        SwitchPreference(
                            title = tr("替换展开按钮背景材质为高级材质", "替换展开按钮背景材质为高级材质"),
                            checked = shape.advancedMaterial,
                            onCheckedChange = { value ->
                                shapeStore.update(service) { it.copy(advancedMaterial = value) }
                                shape = shapeStore.settings
                            },
                        )
                    }
                    SidebarScalePreference(
                        title = tr("侧边栏高度缩放", "侧边栏高度缩放"),
                        checked = appearance.heightScaleEnabled, value = appearance.heightScale,
                        onCheckedChange = { value -> updateAppearance { it.copy(heightScaleEnabled = value) } },
                        onValueFinished = { value -> updateAppearance { it.copy(heightScale = value) } },
                    )
                    SidebarScalePreference(
                        title = tr("侧边栏宽度缩放", "侧边栏宽度缩放"),
                        checked = appearance.widthScaleEnabled, value = appearance.widthScale,
                        onCheckedChange = { value -> updateAppearance { it.copy(widthScaleEnabled = value) } },
                        onValueFinished = { value -> updateAppearance { it.copy(widthScale = value) } },
                    )
                    SidebarScalePreference(
                        title = tr("侧边栏图标缩放", "侧边栏图标缩放"),
                        checked = appearance.iconScaleEnabled, value = appearance.iconScale,
                        onCheckedChange = { value -> updateAppearance { it.copy(iconScaleEnabled = value) } },
                        onValueFinished = { value -> updateAppearance { it.copy(iconScale = value) } },
                    )
                }
            }
        }
    }
}

/** Match the independent switch/expanding-slider pattern used by settings appearance. */
@Composable
private fun SidebarPreferenceVisibility(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
        exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun SidebarScalePreference(
    title: String, checked: Boolean, value: Float,
    onCheckedChange: (Boolean) -> Unit, onValueFinished: (Float) -> Unit,
) {
    SwitchPreference(title = title, checked = checked, onCheckedChange = onCheckedChange)
    SidebarPreferenceVisibility(checked) {
        var current by remember(value) { mutableFloatStateOf(value) }
        EditableSliderPreference(
            title = title, value = current, onValueChange = { current = it },
            onValueChangeFinished = { onValueFinished((current * 100f).roundToInt() / 100f) },
            valueText = if (current == 0f) tr("侧边栏缩放系统默认", "0.00%（系统默认）")
                else String.format(Locale.US, "%+.2f%%", current),
            valueRange = SIDEBAR_APPEARANCE_MIN..SIDEBAR_APPEARANCE_MAX,
            steps = 19999, defaultValue = 0f, centerValue = 0f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun SidebarGlassPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context, service) { SidebarSettingsStore(context) }
    var settings by remember(store) { mutableStateOf(store.settings) }
    fun update(transform: (SidebarSettings) -> SidebarSettings) {
        store.update(service, transform)
        settings = store.settings
    }
    AppPage(tr("侧边栏柔光玻璃参数调整", "侧边栏柔光玻璃参数调整"), back,
        restartScopes = setOf(ScopeApplication.SECURITY_CENTER), restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(
                        title = tr("侧边栏柔光玻璃参数调整", "侧边栏柔光玻璃参数调整"),
                        checked = settings.glass.enabled,
                        onCheckedChange = { value -> update { it.copy(glass = it.glass.copy(enabled = value)) } },
                    )
                    PortalAppearanceVisibility(settings.glass.enabled) {
                        SwitchPreference(
                            title = tr("统一使用全局柔光玻璃参数", "统一使用全局柔光玻璃参数"),
                            checked = settings.useGlobal,
                            onCheckedChange = { value -> update { it.copy(useGlobal = value) } },
                        )
                    }
                }
            }
            item {
                PortalAppearanceVisibility(settings.glass.enabled && !settings.useGlobal) {
                    Group(tr("外观调整", "外观调整")) {
                        SoftGlassMaterialSliders(settings.glass, includeBackdrop = false) { transform ->
                            update { it.copy(glass = transform(it.glass)) }
                        }
                    }
                }
            }
        }
    }
}
