// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun LockscreenCapsuleNotificationPreference() {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { LockscreenCapsuleNotificationSettingsStore(context) }
    var types by remember { mutableIntStateOf(store.types) }
    var show by rememberSaveable { mutableStateOf(false) }
    val changed = LocalNotificationSettingsChanged.current
    LaunchedEffect(service) { service?.let { store.syncRemote(it); types = store.types } }
    val items = listOf(
        CAPSULE_NOTIFICATION_MEDIA to tr("capsuleTypeMedia", "音乐通知"),
        CAPSULE_NOTIFICATION_FOCUS to tr("capsuleTypeFocus", "焦点通知"),
        CAPSULE_NOTIFICATION_ORDINARY to tr("capsuleTypeOrdinary", "普通通知"),
    )
    ArrowPreference(
        title = tr("capsuleNotificationTypes", "加入锁屏胶囊的通知类型"),
        summary = items.filter { (bit, _) -> types and bit != 0 }.joinToString("、") { it.second }
            .ifEmpty { tr("capsuleTypeNone", "不加入任何通知") },
        onClick = { show = true },
    )
    if (show) {
        var selected by rememberSaveable { mutableIntStateOf(types) }
        WindowDialog(show = true, onDismissRequest = { show = false }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("capsuleNotificationTypes", "加入锁屏胶囊的通知类型"),
                    style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                Text(tr("capsuleNotificationTypesHint", "仅选中的通知类型会转换为锁屏胶囊，未选中的通知保持系统原样。"),
                    style = MiuixTheme.textStyles.body2)
                items.forEach { (bit, title) ->
                    val toggle = { selected = selected xor bit }
                    Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state = if (selected and bit != 0) ToggleableState.On else ToggleableState.Off,
                            onClick = toggle)
                        Text(title, style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(start = 10.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton({ show = false }, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                    GlassDialogButton({
                        store.update(service, selected)
                        types = store.types
                        changed()
                        show = false
                    }, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("保存", "保存")) }
                }
            }
        }
    }
}
