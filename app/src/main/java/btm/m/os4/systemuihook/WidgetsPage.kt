// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import btm.m.os4.systemuihook.SeamlessCard as Card
import btm.m.os4.systemuihook.InterfaceSwitchPreference as SwitchPreference

@Composable
internal fun WidgetsPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context, service) { WidgetSettingsStore(context) }
    var settings by remember(store) { mutableStateOf(store.read()) }
    fun update(transform: (WidgetSettings) -> WidgetSettings) {
        store.update(service, transform)
        settings = store.read()
    }
    AppPage(tr("小部件", "小部件"), back,
        restartScopes = setOf(ScopeApplication.PERSONAL_ASSISTANT, ScopeApplication.DESKTOP),
        restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(
                        title = tr("解锁 HyperOS 小部件白名单限制", "解锁 HyperOS 小部件白名单限制"),
                        checked = settings.unlockHyperOs,
                        onCheckedChange = { value -> update { it.copy(unlockHyperOs = value) } },
                    )
                    SwitchPreference(
                        title = tr("允许安卓小部件参与堆叠", "允许安卓小部件参与堆叠"),
                        checked = settings.androidStack,
                        onCheckedChange = { value -> update { it.copy(androidStack = value) } },
                    )
                    SwitchPreference(
                        title = tr("解锁安卓小部件柔光玻璃背景", "解锁安卓小部件柔光玻璃背景"),
                        checked = settings.androidGlass,
                        onCheckedChange = { value -> update { it.copy(androidGlass = value) } },
                    )
                }
            }
        }
    }
}
