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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import btm.m.os4.systemuihook.AppList
import btm.m.os4.systemuihook.AppPage
import btm.m.os4.systemuihook.HookApplication
import btm.m.os4.systemuihook.ScopeApplication
import btm.m.os4.systemuihook.tr
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** Per-framework blocking switches for com.mobiletools.systemhelper and com.miui.guardprovider. */
@Composable
internal fun PrivacyGuardPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context) { PrivacyGuardSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }

    LaunchedEffect(service) {
        service?.let {
            store.syncRemote(it)
            settings = store.settings
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
    }
}
