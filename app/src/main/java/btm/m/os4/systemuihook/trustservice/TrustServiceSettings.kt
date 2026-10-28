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

/** UI for MiTrustService diagnostics; kept alongside its implementation. */
@Composable
internal fun TrustServiceSettingsPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    var enabled by remember(checked) { mutableStateOf(checked) }
    SwitchPreference(
        title = tr("MiTrustService 风险检测（诊断）", "MiTrustService 风险检测（诊断）"),
        summary = tr(
            "仅记录目标方法是否匹配；不会修改系统风险检测结果。开启后重新启动安全服务进程。",
            "仅记录目标方法是否匹配；不会修改系统风险检测结果。开启后重新启动安全服务进程。",
        ),
        checked = enabled,
        onCheckedChange = { value ->
            enabled = value
            onCheckedChange(value)
        },
    )
}
