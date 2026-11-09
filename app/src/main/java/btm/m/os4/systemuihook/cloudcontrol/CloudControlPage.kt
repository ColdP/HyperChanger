// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.cloudcontrol

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
import btm.m.os4.systemuihook.SeamlessCard as Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import btm.m.os4.systemuihook.InterfaceSwitchPreference as SwitchPreference

/** Experimental notice first, then the cloud control explanation, disclaimer and issue guidance. */
private const val NOTICE_STEPS = 4

/**
 * Dedicated cloud control page, shaped exactly like EnvironmentDetectionPage: one master switch
 * that gates the per-application switches, and the same acknowledgement flow with the countdown
 * before the master switch can be turned on.
 */
@Composable
internal fun CloudControlPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context) { CloudControlSettingsStore(context) }
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
    fun update(transform: (CloudControlSettings) -> CloudControlSettings) {
        store.update(service, transform)
        settings = store.settings
    }

    AppPage(
        tr("cloudControlTitle", "云控"),
        back,
        restartScopes = setOf(ScopeApplication.HTML_VIEWER, ScopeApplication.JOYOSE),
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(
                        title = tr("cloudControlMasterSwitch", "云控总开关"),
                        summary = tr("cloudControlMasterSummary", "统一控制各应用的云控拦截，关闭后下方开关失效"),
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
                            tr("cloudControlGroup", "云控相关设置"),
                            insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
                        )
                        Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                            SwitchPreference(
                                title = tr("htmlViewerTitle", "HTML 查看器"),
                                summary = tr("htmlViewerDisableCloudControlSummary", "阻止 HTML 查看器向云端同步任务数据"),
                                checked = settings.htmlViewer,
                                onCheckedChange = { value -> update { it.copy(htmlViewer = value) } },
                            )
                            SwitchPreference(
                                title = tr("joyoseTitle", "Joyose"),
                                summary = tr("joyoseDisableCloudControlSummary", "跳过 Joyose 的云控任务同步"),
                                checked = settings.joyose,
                                onCheckedChange = { value -> update { it.copy(joyose = value) } },
                            )
                        }
                    }
                }
            }
        }

        val noticeTitle = when (noticeStep) {
            0 -> tr("实验性功能", "实验性功能")
            1 -> tr("cloudControlTitle", "云控")
            2 -> tr("免责声明", "免责声明")
            else -> tr("问题反馈", "问题反馈")
        }
        val noticeMessage = when (noticeStep) {
            0 -> tr(
                "environmentDetectionExperimentalNotice",
                "本功能为实验性功能，可能导致应用异常或设备无法正常启动。使用前必须确保自己具备停用模块、恢复设备和还原数据的能力。",
            )
            1 -> tr(
                "cloudControlNoticeIntro",
                "云控总开关会统一启用下方的应用开关。各应用开关只在总开关开启时生效，关闭总开关后所有云控拦截立即失效。",
            )
            2 -> tr(
                "cloudControlNoticeRisk",
                "拦截云控可能影响相关应用的云端功能或数据同步。请确认这是你需要的行为后再继续；本功能按现状提供，不保证与未来系统版本的兼容性。",
            )
            else -> tr(
                "cloudControlNoticeIssue",
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
