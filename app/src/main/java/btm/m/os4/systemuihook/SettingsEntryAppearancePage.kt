// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SettingsEntryAppearancePage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { SettingsEntryAppearanceSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        settings = store.settings
    }
    fun update(transform: (SettingsEntryAppearanceSettings) -> SettingsEntryAppearanceSettings) {
        store.update(service, transform)
        settings = store.settings
    }
    AppPage(tr("settingsEntryAppearance", "系统设置首页入口外观调整"), back, restartScopes = setOf(ScopeApplication.SETTINGS)) { padding, scroll ->
        AppList(padding, scroll) {
            item {
                Group(tr("settingsEntryAppearance", "系统设置首页入口外观调整")) {
                    SwitchPreference(
                        title = tr("settingsEntryIconScale", "入口图标缩放"),
                        checked = settings.iconScaleEnabled,
                        onCheckedChange = { enabled -> update { it.copy(iconScaleEnabled = enabled) } },
                    )
                    AnimatedVisibility(
                        visible = settings.iconScaleEnabled,
                        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
                    ) {
                        SettingsEntryAppearanceSlider(
                            title = tr("settingsEntryIconScale", "入口图标缩放"),
                            value = settings.iconScale,
                            onValueFinished = { value -> update { it.copy(iconScale = value) } },
                        )
                    }
                    SwitchPreference(
                        title = tr("settingsEntryTextScale", "入口文本缩放"),
                        checked = settings.textScaleEnabled,
                        onCheckedChange = { enabled -> update { it.copy(textScaleEnabled = enabled) } },
                    )
                    AnimatedVisibility(
                        visible = settings.textScaleEnabled,
                        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
                    ) {
                        SettingsEntryAppearanceSlider(
                            title = tr("settingsEntryTextScale", "入口文本缩放"),
                            value = settings.textScale,
                            onValueFinished = { value -> update { it.copy(textScale = value) } },
                        )
                    }
                    SwitchPreference(
                        title = tr("settingsEntryRowSpacing", "入口行间距"),
                        checked = settings.rowSpacingEnabled,
                        onCheckedChange = { enabled -> update { it.copy(rowSpacingEnabled = enabled) } },
                    )
                    AnimatedVisibility(
                        visible = settings.rowSpacingEnabled,
                        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
                    ) {
                        SettingsEntryAppearanceSlider(
                            title = tr("settingsEntryRowSpacing", "入口行间距"),
                            value = settings.rowSpacing,
                            onValueFinished = { value -> update { it.copy(rowSpacing = value) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsEntryAppearanceSlider(
    title: String,
    value: Float,
    onValueFinished: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    EditableSliderPreference(
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = {
            onValueFinished((current * 100f).roundToInt().coerceIn(-10000, 10000) / 100f)
        },
        title = title,
        valueText = if (current == 0f) {
            tr("settingsEntryAppearanceDefault", "0%（系统默认）")
        } else {
            String.format(Locale.US, "%+.2f%%", current)
        },
        valueRange = SETTINGS_ENTRY_APPEARANCE_MIN..SETTINGS_ENTRY_APPEARANCE_MAX,
        steps = 19999,
        defaultValue = SETTINGS_ENTRY_APPEARANCE_DEFAULT,
        centerValue = SETTINGS_ENTRY_APPEARANCE_DEFAULT,
        modifier = Modifier.fillMaxWidth(),
    )
}
