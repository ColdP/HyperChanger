// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun MusicLockscreenScopeDialog(show: Boolean, onDismiss: () -> Unit) {
    var remaining by remember(show) { mutableIntStateOf(3) }
    LaunchedEffect(show) {
        if (show) while (remaining > 0) {
            delay(1000)
            remaining--
        }
    }
    WindowDialog(
        show = show,
        title = tr("musicScopeRestartTitle", "作用域重启提示"),
        summary = tr("musicScopeRestartMessage", "使用音乐锁屏，需要同时勾选并重启\"系统界面\"与\"壁纸\"作用域"),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { if (remaining == 0) onDismiss() },
    ) {
        GlassDialogButton(onClick = onDismiss, enabled = remaining == 0, modifier = Modifier.fillMaxWidth()) {
            Text(if (remaining > 0) tr("musicScopeUnderstandCountdown", "了解 (%ds)").format(remaining)
                else tr("musicScopeUnderstand", "了解"))
        }
    }
}
