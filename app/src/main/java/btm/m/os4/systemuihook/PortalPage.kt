// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun PortalPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val preferences = remember(context) { context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE) }
    var settings by remember(service) { mutableStateOf(PortalSettings.read(preferences)) }
    fun save(value: PortalAppearanceSettings) {
        PortalSettings.save(context, service, value)
        settings = value
    }
    AppPage(
        tr("传送门", "传送门"), back,
        restartScopes = setOf(ScopeApplication.PORTAL), restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Group(tr("外观调整", "外观调整")) {
                    SwitchPreference(
                        title = tr("为传送门添加背景模糊", "为传送门添加背景模糊"),
                        checked = settings.blur, onCheckedChange = { save(settings.copy(blur = it)) },
                    )
                    SwitchPreference(
                        title = tr("去除全屏半透明遮罩层", "去除全屏半透明遮罩层"),
                        checked = settings.removeScrim, onCheckedChange = { save(settings.copy(removeScrim = it)) },
                    )
                    PortalAppearanceVisibility(settings.blur) {
                        Column {
                            SwitchPreference(
                                title = tr("自定义背景卡片模糊度", "自定义背景卡片模糊度"),
                                checked = settings.customBlur, onCheckedChange = { save(settings.copy(customBlur = it)) },
                            )
                            PortalAppearanceVisibility(settings.customBlur) {
                                PortalSlider(
                                    tr("背景卡片模糊度", "背景卡片模糊度"), settings.blurRadiusDp, 0..100, 24, " dp",
                                ) { save(settings.copy(blurRadiusDp = it)) }
                            }
                            SwitchPreference(
                                title = tr("自定义背景卡片不透明度", "自定义背景卡片不透明度"),
                                checked = settings.customOpacity, onCheckedChange = { save(settings.copy(customOpacity = it)) },
                            )
                            PortalAppearanceVisibility(settings.customOpacity) {
                                PortalSlider(
                                    tr("背景卡片不透明度", "背景卡片不透明度"), settings.opacityPercent, 0..100, 70, "%",
                                ) { save(settings.copy(opacityPercent = it)) }
                            }
                            SwitchPreference(
                                title = tr("自定义背景卡片圆角", "自定义背景卡片圆角"),
                                checked = settings.customCorner, onCheckedChange = { save(settings.copy(customCorner = it)) },
                            )
                            PortalAppearanceVisibility(settings.customCorner) {
                                PortalSlider(
                                    tr("背景卡片圆角", "背景卡片圆角"), settings.cornerOffsetDp, -32..64, 0, " dp", true,
                                ) { save(settings.copy(cornerOffsetDp = it)) }
                            }
                        }
                    }
                }
            }
            item {
                Group(tr("功能修改", "功能修改")) {
                    PortalJapaneseOcrPreferences(settings.japaneseOcr) { save(settings.copy(japaneseOcr = it)) }
                    SwitchPreference(
                        title = tr("始终开启传送门开发者模式", "始终开启传送门开发者模式"),
                        checked = settings.developerMode, onCheckedChange = { save(settings.copy(developerMode = it)) },
                    )
                }
            }
        }
    }
}

/** Same fade/scale visibility used by the existing background settings pages. */
@Composable
private fun PortalAppearanceVisibility(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
    ) { content() }
}

@Composable
private fun PortalSlider(
    title: String, value: Int, range: IntRange, default: Int, suffix: String = "", relative: Boolean = false,
    save: (Int) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    EditableSliderPreference(
        title = title, value = current,
        valueText = if (relative && current.roundToInt() == 0) tr("系统默认", "系统默认") else "${current.roundToInt()}$suffix",
        valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1,
        defaultValue = default.toFloat(), centerValue = if (relative) 0f else null,
        onValueChange = { current = it }, onValueChangeFinished = { save(current.roundToInt()) },
    )
}
