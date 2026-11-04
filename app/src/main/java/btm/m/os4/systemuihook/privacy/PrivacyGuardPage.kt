// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.privacy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.os4.systemuihook.AppList
import btm.m.os4.systemuihook.AppPage
import btm.m.os4.systemuihook.GlassDialogButton
import btm.m.os4.systemuihook.HookApplication
import btm.m.os4.systemuihook.ScopeApplication
import btm.m.os4.systemuihook.WindowDialog
import btm.m.os4.systemuihook.tr
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Experimental notice first, then the scope description, disclaimer and issue guidance. */
private const val NOTICE_STEPS = 4

/**
 * Per-framework blocking switches for com.mobiletools.systemhelper and com.miui.guardprovider.
 * Shaped like EnvironmentDetectionPage: the master switch stays disabled until the acknowledgement
 * flow has been dismissed, and it is off by default so the user has to opt in.
 */
@Composable
internal fun PrivacyGuardPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context) { PrivacyGuardSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    var noticeStep by remember { mutableIntStateOf(if (store.settings.enabled) NOTICE_STEPS else 0) }
    var showNotice by remember { mutableStateOf(!store.settings.enabled) }
    var secondsRemaining by remember(noticeStep) { mutableIntStateOf(5) }

    LaunchedEffect(service) {
        service?.let {
            store.syncRemote(it)
            settings = store.settings
            if (store.settings.enabled) {
                noticeStep = NOTICE_STEPS
                showNotice = false
            }
        }
    }
    LaunchedEffect(noticeStep, showNotice) {
        if (showNotice && noticeStep < NOTICE_STEPS) {
            secondsRemaining = 5
            while (secondsRemaining > 0) {
                delay(1_000)
                secondsRemaining--
            }
        }
    }
    fun update(transform: (PrivacyGuardSettings) -> PrivacyGuardSettings) {
        store.update(service, transform)
        settings = store.settings
    }

    AppPage(
        tr("privacyGuardTitle", "预装隐私拦截"),
        back,
        restartScopes = setOf(ScopeApplication.SYSTEM_HELPER, ScopeApplication.GUARD_PROVIDER),
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(
                        title = tr("privacyGuardMaster", "拦截总开关"),
                        summary = tr("privacyGuardMasterSummary", "仅对系统助手与系统安全组件生效，关闭后全部放开"),
                        checked = settings.enabled,
                        enabled = noticeStep == NOTICE_STEPS,
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
                            tr("privacyGuardGroup", "拦截项目"),
                            insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
                        )
                        Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                            SwitchPreference(
                                title = tr("privacyGuardReports", "阻断上报端点"),
                                summary = tr(
                                    "privacyGuardReportsSummary",
                                    "拦截 dm.wo.com.cn、flash.sec.miui.com、fraud-detection.engine.miui.com 的域名解析",
                                ),
                                checked = settings.blockReports,
                                onCheckedChange = { value -> update { it.copy(blockReports = value) } },
                            )
                            SwitchPreference(
                                title = tr("privacyGuardInventory", "阻断应用清单与使用统计"),
                                summary = tr(
                                    "privacyGuardInventorySummary",
                                    "返回空的应用列表、使用记录和流量统计，使其无法生成清单",
                                ),
                                checked = settings.blockInventory,
                                onCheckedChange = { value -> update { it.copy(blockInventory = value) } },
                            )
                            SwitchPreference(
                                title = tr("privacyGuardDeviceIds", "阻断设备与 SIM 标识"),
                                summary = tr(
                                    "privacyGuardDeviceIdsSummary",
                                    "IMEI、MEID、IMSI、ICCID、SIM 序列号一律返回空串",
                                ),
                                checked = settings.blockDeviceIds,
                                onCheckedChange = { value -> update { it.copy(blockDeviceIds = value) } },
                            )
                            SwitchPreference(
                                title = tr("privacyGuardCapture", "阻断录屏与录音采集"),
                                summary = tr(
                                    "privacyGuardCaptureSummary",
                                    "画面改写到被丢弃的虚拟显示，音频读取返回静音，采集流程不报错",
                                ),
                                checked = settings.blockCapture,
                                onCheckedChange = { value -> update { it.copy(blockCapture = value) } },
                            )
                        }
                    }
                }
            }
        }

        val noticeTitle = when (noticeStep) {
            0 -> tr("实验性功能", "实验性功能")
            1 -> tr("privacyGuardTitle", "预装隐私拦截")
            2 -> tr("免责声明", "免责声明")
            else -> tr("问题反馈", "问题反馈")
        }
        val noticeMessage = when (noticeStep) {
            0 -> tr(
                "environmentDetectionExperimentalNotice",
                "本功能为实验性功能，可能导致应用异常或设备无法正常启动。使用前必须确保自己具备停用模块、恢复设备和还原数据的能力。",
            )
            1 -> tr(
                "privacyGuardNoticeIntro",
                "拦截只作用于 com.mobiletools.systemhelper 与 com.miui.guardprovider 两个进程。开启后它们仍会正常运行，但应用清单、使用统计和设备标识会被置空，录屏与录音只能拿到空气数据，上报域名在解析阶段即被阻断。",
            )
            2 -> tr(
                "privacyGuardNoticeRisk",
                "拦截可能影响系统安全组件的应用安装校验与反诈告警能力，也可能让相关功能误判或失效。本功能按现状提供，不保证与未来系统版本的兼容性，请自行评估风险后使用。",
            )
            else -> tr(
                "privacyGuardNoticeIssue",
                "遇到问题请前往 GitHub 仓库 ColdP/HyperChanger 提交 Issues。请附上模块日志、问题描述、复现步骤、设备型号与系统和模块版本。提交前请移除日志中的隐私信息。",
            )
        }
        WindowDialog(
            show = showNotice && noticeStep < NOTICE_STEPS,
            title = noticeTitle,
            summary = noticeMessage,
            titleTextAlign = TextAlign.Start,
            summaryTextAlign = TextAlign.Start,
            onDismissRequest = back,
            onDismissFinished = {
                if (noticeStep < NOTICE_STEPS) {
                    noticeStep++
                    secondsRemaining = 5
                    showNotice = noticeStep < NOTICE_STEPS
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
