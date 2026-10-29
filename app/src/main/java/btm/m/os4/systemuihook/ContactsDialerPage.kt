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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun ContactsDialerPage(openBackground: () -> Unit, back: () -> Unit) {
    AppPage(tr("拨号与通话", "拨号与通话"), back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                SmallTitle(
                    tr("界面调整", "界面调整"),
                    insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
                )
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    ArrowPreference(title = tr("拨号盘背景", "拨号盘背景"), onClick = openBackground)
                }
            }
        }
    }
}

@Composable
internal fun ContactsDialerBackgroundPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val preferences = remember { context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE) }
    var settings by remember(service) { mutableStateOf(ContactsDialerSettings.read(preferences)) }
    var opacity by remember(settings.opacityPercent) { mutableFloatStateOf(settings.opacityPercent.toFloat()) }
    var blurRadius by remember(settings.blurRadiusDp) { mutableFloatStateOf(settings.blurRadiusDp.toFloat()) }
    var borderWidth by remember(settings.borderWidthHundredthsPx) { mutableFloatStateOf(settings.borderWidthPx) }
    var borderOpacity by remember(settings.borderOpacityPercent) { mutableFloatStateOf(settings.borderOpacityPercent.toFloat()) }
    var cornerOffset by remember(settings.cornerOffset) { mutableFloatStateOf(settings.cornerOffset.toFloat()) }
    fun save(value: ContactsDialerBackgroundSettings) {
        ContactsDialerSettings.save(context, service, value)
        settings = value
    }
    AppPage(
        tr("拨号盘背景", "拨号盘背景"),
        back,
        restartScopes = setOf(ScopeApplication.CONTACTS_DIALER),
        restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                SmallTitle(
                    tr("背景调整", "背景调整"),
                    insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
                )
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(
                        title = tr("为拨号盘添加背景模糊", "为拨号盘添加背景模糊"),
                        checked = settings.blur,
                        onCheckedChange = { save(settings.copy(blur = it)) },
                    )
                    AnimatedVisibility(
                        visible = settings.blur,
                        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                    ) {
                        Column {
                            SwitchPreference(
                                title = tr("自定义拨号盘背景模糊度", "自定义拨号盘背景模糊度"),
                                checked = settings.customBlur,
                                onCheckedChange = { save(settings.copy(customBlur = it)) },
                            )
                            AnimatedVisibility(
                                visible = settings.customBlur,
                                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                                exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                            ) {
                                EditableSliderPreference(
                                    title = tr("拨号盘背景模糊度", "拨号盘背景模糊度"),
                                    value = blurRadius,
                                    valueText = blurRadius.roundToInt().toString(),
                                    valueRange = 0f..100f,
                                    steps = 99,
                                    defaultValue = 24f,
                                    onValueChange = { blurRadius = it },
                                    onValueChangeFinished = { save(settings.copy(blurRadiusDp = blurRadius.roundToInt())) },
                                )
                            }
                        }
                    }
                    SwitchPreference(
                        title = tr("自定义拨号盘背景不透明度", "自定义拨号盘背景不透明度"),
                        checked = settings.customOpacity,
                        onCheckedChange = { save(settings.copy(customOpacity = it)) },
                    )
                    AnimatedVisibility(
                        visible = settings.customOpacity,
                        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                    ) {
                        EditableSliderPreference(
                            title = tr("拨号盘背景不透明度", "拨号盘背景不透明度"),
                            value = opacity,
                            valueText = "${opacity.roundToInt()}%",
                            valueRange = 0f..100f,
                            steps = 99,
                            defaultValue = 70f,
                            onValueChange = { opacity = it },
                            onValueChangeFinished = { save(settings.copy(opacityPercent = opacity.roundToInt())) },
                        )
                    }
                    SwitchPreference(
                        title = tr("为拨号盘背景添加边框", "为拨号盘背景添加边框"),
                        checked = settings.border,
                        onCheckedChange = { save(settings.copy(border = it)) },
                    )
                    AnimatedVisibility(
                        visible = settings.border,
                        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                    ) {
                        Column {
                            EditableSliderPreference(
                                title = tr("边框粗细", "边框粗细"),
                                value = borderWidth,
                                valueText = String.format(Locale.US, "%.2f px", borderWidth),
                                valueRange = .01f..6f,
                                steps = 598,
                                defaultValue = 1f,
                                onValueChange = { borderWidth = (it * 100f).roundToInt().coerceIn(1, 600) / 100f },
                                onValueChangeFinished = { save(settings.copy(borderWidthHundredthsPx = (borderWidth * 100f).roundToInt())) },
                            )
                            EditableSliderPreference(
                                title = tr("边框不透明度", "边框不透明度"),
                                value = borderOpacity,
                                valueText = "${borderOpacity.roundToInt()}%",
                                valueRange = 0f..100f,
                                steps = 99,
                                defaultValue = 100f,
                                onValueChange = { borderOpacity = it },
                                onValueChangeFinished = { save(settings.copy(borderOpacityPercent = borderOpacity.roundToInt())) },
                            )
                        }
                    }
                    EditableSliderPreference(
                        title = tr("拨号盘背景圆角", "拨号盘背景圆角"),
                        value = cornerOffset,
                        valueText = if (cornerOffset.roundToInt() == 0) tr("系统默认", "系统默认") else cornerOffset.roundToInt().toString(),
                        valueRange = -50f..64f,
                        steps = 113,
                        defaultValue = 0f,
                        centerValue = 0f,
                        onValueChange = { cornerOffset = it },
                        onValueChangeFinished = { save(settings.copy(cornerOffset = cornerOffset.roundToInt())) },
                    )
                }
            }
        }
    }
}
