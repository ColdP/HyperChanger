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
internal fun AssistantWidgetGlassPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context, service) { WidgetGlassSettingsStore(context) }
    var settings by remember(store) { mutableStateOf(store.read()) }
    fun update(transform: (WidgetGlassSettings) -> WidgetGlassSettings) {
        store.update(service, transform)
        settings = store.read()
    }
    AppPage(tr("负一屏小部件柔光玻璃参数调整", "负一屏小部件柔光玻璃参数调整"), back,
        restartScopes = setOf(ScopeApplication.PERSONAL_ASSISTANT), restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(
                        title = tr("柔光玻璃参数调整", "柔光玻璃参数调整"),
                        checked = settings.glass.enabled,
                        onCheckedChange = { value -> update { it.copy(glass = it.glass.copy(enabled = value)) } },
                    )
                    PortalAppearanceVisibility(settings.glass.enabled) {
                        SwitchPreference(
                            title = tr("使用全局柔光玻璃参数", "使用全局柔光玻璃参数"),
                            checked = settings.useGlobal,
                            onCheckedChange = { value -> update { it.copy(useGlobal = value) } },
                        )
                    }
                }
            }
            item {
                PortalAppearanceVisibility(settings.glass.enabled && !settings.useGlobal) {
                    Group(tr("柔光玻璃参数调整", "柔光玻璃参数调整")) {
                        SoftGlassMaterialSliders(settings.glass, includeBackdrop = false) { transform ->
                            update { it.copy(glass = transform(it.glass)) }
                        }
                    }
                }
            }
        }
    }
}
