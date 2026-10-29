// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.os4.systemuihook.securitycenter.SecurityCenterRootCheckDiagnosticsPreference
import btm.m.os4.systemuihook.securitycenter.SecurityCenterRootedCheckDiagnosticsPreference
import btm.m.os4.systemuihook.trustservice.TrustServiceSettingsPreference
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun EnvironmentDetectionPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context) { EnvironmentDetectionSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    var noticeStep by remember { mutableIntStateOf(if (store.settings.enabled) 3 else 0) }
    var showNotice by remember { mutableStateOf(!store.settings.enabled) }
    var secondsRemaining by remember(noticeStep) { mutableIntStateOf(5) }

    LaunchedEffect(service) {
        service?.let {
            store.syncRemote(it)
            settings = store.settings
            if (store.settings.enabled) {
                noticeStep = 3
                showNotice = false
            }
        }
    }
    LaunchedEffect(noticeStep, showNotice) {
        if (showNotice && noticeStep < 3) {
            secondsRemaining = 5
            while (secondsRemaining > 0) {
                delay(1_000)
                secondsRemaining--
            }
        }
    }
    fun update(transform: (EnvironmentDetectionSettings) -> EnvironmentDetectionSettings) {
        store.update(service, transform)
        settings = store.settings
    }

    AppPage(tr("环境检测", "环境检测"), back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(
                        title = tr("环境检测功能总开关", "环境检测功能总开关"),
                        checked = settings.enabled,
                        enabled = noticeStep == 3,
                        onCheckedChange = { value -> update { it.copy(enabled = value) } },
                    )
                }
            }
            item {
                AnimatedVisibility(
                    visible = settings.enabled,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    Column {
                        SmallTitle(
                            tr("环境检测相关设置", "环境检测相关设置"),
                            insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
                        )
                        Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                            TrustServiceSettingsPreference(
                                checked = settings.disableMrmd,
                                onCheckedChange = { value -> update { it.copy(disableMrmd = value) } },
                            )
                            SecurityCenterRootedCheckDiagnosticsPreference(
                                checked = settings.rootedCheck,
                                onCheckedChange = { value -> update { it.copy(rootedCheck = value) } },
                            )
                            SecurityCenterRootCheckDiagnosticsPreference(
                                checked = settings.rootCheck,
                                onCheckedChange = { value -> update { it.copy(rootCheck = value) } },
                            )
                        }
                    }
                }
            }
        }

        val noticeTitle = when (noticeStep) {
            0 -> tr("实验性功能", "实验性功能")
            1 -> tr("免责声明", "免责声明")
            else -> tr("问题反馈", "问题反馈")
        }
        val noticeMessage = when (noticeStep) {
            0 -> tr("environmentDetectionExperimentalNotice", "本功能为实验性功能，可能导致应用异常或设备无法正常启动。使用前必须确保自己具备停用模块、恢复设备和还原数据的能力。")
            1 -> tr("environmentDetectionDisclaimer", "本功能按现状提供，不保证兼容性、稳定性或检测结果。请提前备份数据并自行评估风险；因使用本功能造成的应用异常、数据丢失或设备故障等后果，由使用者自行承担。不同意请返回并停止使用。")
            else -> tr("environmentDetectionIssueNotice", "遇到问题请前往 GitHub 仓库 ColdP/HyperChanger 提交 Issues。必须包含模块日志、完整问题描述、复现步骤、设备型号、系统和模块版本，以及已开启的环境检测选项；涉及崩溃时请附上崩溃日志。提交前请移除日志中的隐私信息。")
        }
        WindowDialog(
            show = showNotice && noticeStep < 3,
            title = noticeTitle,
            summary = noticeMessage,
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = back,
            onDismissFinished = {
                if (noticeStep < 3) {
                    noticeStep++
                    secondsRemaining = 5
                    showNotice = noticeStep < 3
                }
            },
        ) {
            GlassDialogButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = showNotice && secondsRemaining == 0,
                colors = ButtonDefaults.buttonColorsPrimary(),
                onClick = { if (secondsRemaining == 0) showNotice = false },
            ) {
                Text(
                    if (secondsRemaining > 0) {
                        tr("environmentDetectionConfirmCountdown", "确定（%d 秒）").format(secondsRemaining)
                    } else {
                        tr("确定", "确定")
                    },
                )
            }
        }
    }
}
