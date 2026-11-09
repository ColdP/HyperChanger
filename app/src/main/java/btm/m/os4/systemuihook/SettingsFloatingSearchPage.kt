// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt
import btm.m.os4.systemuihook.SeamlessCard as Card
import top.yukonga.miuix.kmp.basic.Text
import btm.m.os4.systemuihook.InterfaceSwitchPreference as SwitchPreference

@Composable
internal fun SettingsFloatingSearchPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { SettingsFloatingSearchSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        settings = store.settings
    }
    fun save(value: SettingsFloatingSearchSettings = settings) {
        store.update(service) { value }
        settings = store.settings
    }
    // Persist an interrupted slider drag on page exit as well as on its normal finish callback.
    val latest by rememberUpdatedState(settings)
    val latestService by rememberUpdatedState(service)
    DisposableEffect(store) {
        onDispose { store.update(latestService) { latest } }
    }
    AppPage(tr("settingsFloatingSearchParameters", "高级材质搜索框参数自定义"), back,
        restartScopes = setOf(ScopeApplication.SETTINGS), restartEnabled = service != null) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                val backdrop = rememberLayerBackdrop()
                val dark = isSystemInDarkTheme()
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth().height(310.dp).clip(RoundedCornerShape(22.5.dp))) {
                        Image(painterResource(R.drawable.lockscreen_widget_preview_background), null,
                            Modifier.fillMaxSize().layerBackdrop(backdrop), contentScale = ContentScale.Crop)
                        val density = LocalDensity.current
                        val width = with(density) { settingsSearchWidth(maxWidth.toPx(), density.density, settings.widthScale).toDp() }
                        val height = with(density) { settingsSearchHeight(density.density, settings.heightScale).toDp() }
                        SettingsFloatingSearchSurface(settings, dark, tr("settingsFloatingSearchHint", "搜索"),
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).width(width).height(height),
                            backdrop = backdrop)
                    }
                }
            }
            item {
                Group(tr("参数调整", "参数调整")) {
                    SettingsSearchPercent(tr("navMaterialOpacity", "背景不透明度"), settings.opacity, 0..100, 14,
                        { settings = settings.copy(opacity = it) }, { save() })
                    EditableSliderPreference(title = tr("navMaterialBlur", "模糊度"), value = settings.blurRadius.toFloat(),
                        valueText = tr("settingsFloatingSearchBlurValue", "%d px").format(settings.blurRadius),
                        valueRange = 0f..120f, steps = 119, defaultValue = 40f,
                        onValueChange = { settings = settings.copy(blurRadius = it.roundToInt()) },
                        onValueChangeFinished = { save() })
                    SwitchPreference(title = tr("显示高光", "显示高光"), checked = settings.highlight,
                        onCheckedChange = { save(settings.copy(highlight = it)) })
                    SettingsSearchPercent(tr("settingsFloatingSearchHeight", "搜索框高度缩放"), settings.heightScale, -100..100, 0,
                        { settings = settings.copy(heightScale = it) }, { save() })
                    SettingsSearchPercent(tr("settingsFloatingSearchWidth", "搜索框宽度缩放"), settings.widthScale, -100..100, 0,
                        { settings = settings.copy(widthScale = it) }, { save() })
                    SettingsSearchPercent(tr("settingsFloatingSearchIconScale", "搜索图标缩放"), settings.iconScale, -100..100, 0,
                        { settings = settings.copy(iconScale = it) }, { save() })
                    SettingsSearchPercent(tr("settingsFloatingSearchTextScale", "搜索文本缩放"), settings.textScale, -100..100, 0,
                        { settings = settings.copy(textScale = it) }, { save() })
                    EditableSliderPreference(title = tr("settingsFloatingSearchIconTextSpacing", "图标和文本的间距"),
                        value = settings.iconTextSpacing.toFloat(),
                        valueText = tr("settingsFloatingSearchSpacingValue", "%d dp").format(settings.iconTextSpacing),
                        valueRange = 0f..48f, steps = 47, defaultValue = 12f,
                        onValueChange = { settings = settings.copy(iconTextSpacing = it.roundToInt()) },
                        onValueChangeFinished = { save() })
                    SharedViewArrowPreference(title = tr("navMaterialResetParameters", "恢复默认参数"), onClick = { confirmReset = true })
                }
            }
        }
    }
    WindowDialog(show = confirmReset, title = tr("navMaterialResetParameters", "恢复默认参数"), onDismissRequest = { confirmReset = false }) {
        Text(tr("settingsFloatingSearchResetConfirm", "确定恢复搜索框的默认材质和尺寸参数吗？"))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton(onClick = { confirmReset = false }, modifier = Modifier.weight(1f)) { Text(tr("取消", "取消")) }
            GlassDialogButton(onClick = {
                confirmReset = false
                save(SettingsFloatingSearchSettings(enabled = settings.enabled))
            }, modifier = Modifier.weight(1f)) { Text(tr("恢复默认", "恢复默认")) }
        }
    }
}

@Composable
private fun SettingsSearchPercent(title: String, value: Int, range: IntRange, default: Int, change: (Int) -> Unit, save: () -> Unit) {
    EditableSliderPreference(title = title, value = value.toFloat(), valueText = tr("navMaterialPercent", "%d%%").format(value),
        valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1,
        defaultValue = default.toFloat(), onValueChange = { change(it.roundToInt()) }, onValueChangeFinished = save)
}
