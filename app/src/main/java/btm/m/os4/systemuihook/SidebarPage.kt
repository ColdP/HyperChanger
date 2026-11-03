// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun SidebarPage(openGlass: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context, service) { SidebarSettingsStore(context) }
    var settings by remember(store) { mutableStateOf(store.settings) }
    val featureStore = remember(context, service) { SidebarFeatureSettingsStore(context) }
    var features by remember(featureStore) { mutableStateOf(featureStore.settings) }
    AppPage(tr("侧边栏", "侧边栏"), back,
        restartScopes = setOf(ScopeApplication.SECURITY_CENTER), restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Group(tr("侧边栏功能", "侧边栏功能")) {
                    TemperatureWarningSwitch(
                        title = tr("去除视频工具箱的温度限制", "去除视频工具箱的温度限制"),
                        checked = features.removeVideoTemperatureLimit,
                        onCheckedChange = { value ->
                            featureStore.update(service) { it.copy(removeVideoTemperatureLimit = value) }
                            features = featureStore.settings
                        },
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SharedViewArrowPreference(
                        title = tr("侧边栏柔光玻璃参数调整", "侧边栏柔光玻璃参数调整"), onClick = openGlass,
                    )
                    SwitchPreference(
                        title = tr("统一侧边栏各卡片的材质", "统一侧边栏各卡片的材质"),
                        checked = settings.unifyCards,
                        onCheckedChange = { value ->
                            store.update(service) { it.copy(unifyCards = value) }
                            settings = store.settings
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SidebarGlassPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context, service) { SidebarSettingsStore(context) }
    var settings by remember(store) { mutableStateOf(store.settings) }
    fun update(transform: (SidebarSettings) -> SidebarSettings) {
        store.update(service, transform)
        settings = store.settings
    }
    AppPage(tr("侧边栏柔光玻璃参数调整", "侧边栏柔光玻璃参数调整"), back,
        restartScopes = setOf(ScopeApplication.SECURITY_CENTER), restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(
                        title = tr("侧边栏柔光玻璃参数调整", "侧边栏柔光玻璃参数调整"),
                        checked = settings.glass.enabled,
                        onCheckedChange = { value -> update { it.copy(glass = it.glass.copy(enabled = value)) } },
                    )
                    PortalAppearanceVisibility(settings.glass.enabled) {
                        SwitchPreference(
                            title = tr("统一使用全局柔光玻璃参数", "统一使用全局柔光玻璃参数"),
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
