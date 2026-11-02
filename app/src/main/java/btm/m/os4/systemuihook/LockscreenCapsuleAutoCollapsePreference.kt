// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun LockscreenCapsuleAutoCollapsePreference() {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { LockscreenCapsuleBehaviorSettingsStore(context) }
    var enabled by remember { mutableStateOf(store.autoCollapse) }
    val changed = LocalNotificationSettingsChanged.current
    LaunchedEffect(service) {
        service?.let {
            store.syncRemote(it)
            enabled = store.autoCollapse
        }
    }
    SwitchPreference(
        title = tr("capsuleAutoCollapse", "自动收起为锁屏胶囊"),
        summary = tr("capsuleAutoCollapseHint", "关闭后需要手动下滑通知，才会转换为锁屏胶囊"),
        checked = enabled,
        onCheckedChange = { value ->
            store.update(service, value)
            enabled = store.autoCollapse
            changed()
        },
    )
}
