// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.trustservice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import btm.m.os4.systemuihook.tr
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** UI for the MiTrustService MRMD-disable hook; kept alongside its implementation. */
@Composable
internal fun TrustServiceSettingsPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    var enabled by remember(checked) { mutableStateOf(checked) }
    SwitchPreference(
        title = tr("禁用 MiTrustService MRMD 初始化", "禁用 MiTrustService MRMD 初始化"),
        summary = tr(
            "(未测试功能)阻止 MiTrustService 初始化 MRMD 服务。开启后强制停止并重新启动安全服务进程使其生效。",
            "(未测试功能)阻止 MiTrustService 初始化 MRMD 服务。开启后强制停止并重新启动安全服务进程使其生效。",
        ),
        checked = enabled,
        onCheckedChange = { value ->
            enabled = value
            onCheckedChange(value)
        },
    )
}
