// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.securitycenter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import btm.m.os4.systemuihook.tr
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SecurityCenterRootedCheckDiagnosticsPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    var enabled by remember(checked) { mutableStateOf(checked) }
    SwitchPreference(
        title = tr("Root 环境检测 Hook", "Root 环境检测 Hook"),
        summary = tr(
            "环境状态处理，并将匹配到的 /system/bin/ 布尔检测方法返回 false。",
            "环境状态处理，并将匹配到的 /system/bin/ 布尔检测方法返回 false。",
        ),
        checked = enabled,
        onCheckedChange = { value ->
            enabled = value
            onCheckedChange(value)
        },
    )
}

@Composable
internal fun SecurityCenterRootCheckDiagnosticsPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    var enabled by remember(checked) { mutableStateOf(checked) }
    SwitchPreference(
        title = tr("Root 检查项 Hook", "Root 检查项 Hook"),
        summary = tr(
            "修改key_check_item_root 匹配逻辑，并将目标布尔方法返回 false。",
            "修改key_check_item_root 匹配逻辑，并将目标布尔方法返回 false。",
        ),
        checked = enabled,
        onCheckedChange = { value ->
            enabled = value
            onCheckedChange(value)
        },
    )
}
