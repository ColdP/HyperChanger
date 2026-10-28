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
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun ContactsDialerPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val preferences = remember { context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE) }
    var settings by remember(service) { mutableStateOf(ContactsDialerSettings.read(preferences)) }
    var opacity by remember(settings.opacityPercent) { mutableFloatStateOf(settings.opacityPercent.toFloat()) }
    var blurRadius by remember(settings.blurRadiusDp) { mutableFloatStateOf(settings.blurRadiusDp.toFloat()) }
    fun save(value: ContactsDialerBackgroundSettings) {
        ContactsDialerSettings.save(context, service, value)
        settings = value
    }
    AppPage(
        tr("拨号与通话", "拨号与通话"),
        back,
        restartScopes = setOf(ScopeApplication.CONTACTS_DIALER),
        restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                SmallTitle(
                    tr("界面调整", "界面调整"),
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
                }
            }
        }
    }
}
