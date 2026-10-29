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
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun PortalPage(openMaterial: () -> Unit, back: () -> Unit) {
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
                    ArrowPreference(
                        title = tr("传送门材质调整", "传送门材质调整"),
                        onClick = openMaterial,
                    )
                    SwitchPreference(
                        title = tr("自定义背景卡片圆角", "自定义背景卡片圆角"),
                        checked = settings.customCorner,
                        onCheckedChange = { save(settings.copy(customCorner = it)) },
                    )
                    PortalAppearanceVisibility(settings.customCorner) {
                        PortalSlider(tr("背景卡片圆角", "背景卡片圆角"), settings.cornerOffsetDp, -32..64, 0, " dp", true) {
                            save(settings.copy(cornerOffsetDp = it))
                        }
                    }
                    SwitchPreference(
                        title = tr("去除全屏半透明遮罩层", "去除全屏半透明遮罩层"),
                        checked = settings.removeScrim, onCheckedChange = { save(settings.copy(removeScrim = it)) },
                    )
                    SwitchPreference(
                        title = tr("调整传送门背景展开态高度", "调整传送门背景展开态高度"),
                        checked = settings.customExpandedHeight,
                        onCheckedChange = { save(settings.copy(customExpandedHeight = it)) },
                    )
                    PortalAppearanceVisibility(settings.customExpandedHeight) {
                        PortalSlider(
                            tr("展开态高度偏移", "展开态高度偏移"), settings.expandedHeightOffsetDp, -128..256, 0, " dp", true,
                        ) { save(settings.copy(expandedHeightOffsetDp = it)) }
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
internal fun PortalAppearanceVisibility(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
    ) { content() }
}

@Composable
internal fun PortalSlider(
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
