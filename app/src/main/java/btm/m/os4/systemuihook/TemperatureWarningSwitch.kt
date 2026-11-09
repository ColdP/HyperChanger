// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import top.yukonga.miuix.kmp.basic.Text
import btm.m.os4.systemuihook.InterfaceSwitchPreference as SwitchPreference

@Composable
internal fun TemperatureWarningSwitch(
    title: String,
    checked: Boolean,
    summary: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    var showWarning by remember { mutableStateOf(false) }
    SwitchPreference(title = title, summary = summary, checked = checked, onCheckedChange = { enabled ->
        if (enabled && !checked) showWarning = true else onCheckedChange(enabled)
    })
    WindowDialog(
        show = showWarning,
        title = tr("高温警告", "高温警告"),
        summary = tr("温度限制免责声明", "解除温度限制可能导致设备过热、运行不稳定或硬件损坏。\n\n免责声明：请在充分了解并接受风险后开启，使用本功能产生的后果由你自行承担。"),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { showWarning = false },
    ) {
        GlassDialogButton(onClick = { showWarning = false; onCheckedChange(true) }, modifier = Modifier.fillMaxWidth()) {
            Text(tr("确认", "确认"))
        }
    }
}
