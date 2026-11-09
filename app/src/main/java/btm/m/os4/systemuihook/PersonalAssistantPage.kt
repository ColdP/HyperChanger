// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import btm.m.os4.systemuihook.InterfaceSwitchPreference as SwitchPreference

@Composable
internal fun PersonalAssistantPage(openWidgetGlass: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { PersonalAssistantSettingsStore(context) }
    var settings by remember(store) { mutableStateOf(store.settings) }
    var showBackgroundDialog by remember { mutableStateOf(false) }
    LaunchedEffect(service) {
        service?.let { store.syncRemote(it) }
        settings = store.settings
    }
    fun update(transform: (PersonalAssistantSettings) -> PersonalAssistantSettings) {
        store.update(service, transform)
        settings = store.settings
    }
    AppPage(tr("负一屏", "负一屏"), back,
        restartScopes = setOf(ScopeApplication.PERSONAL_ASSISTANT), restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Group(tr("背景壁纸", "背景壁纸")) {
                    OverlayDropdownPreference(
                        title = tr("替换负一屏背景", "替换负一屏背景"),
                        items = listOf(tr("系统默认", "系统默认"), tr("桌面壁纸", "桌面壁纸"), tr("自定义", "自定义")),
                        selectedIndex = settings.backgroundMode,
                        onSelectedIndexChange = { value -> update { it.copy(backgroundMode = value) } },
                    )
                    PortalAppearanceVisibility(settings.backgroundMode == 2) {
                        top.yukonga.miuix.kmp.preference.ArrowPreference(
                            title = tr("自定义负一屏背景", "自定义负一屏背景"),
                            summary = settings.backgroundName.ifBlank { tr("请导入背景文件", "请导入背景文件") },
                            onClick = { showBackgroundDialog = true },
                        )
                    }
                }
            }
            item {
                Group(tr("背景模糊", "背景模糊")) {
                    SwitchPreference(
                        title = tr("去除负一屏背景壁纸模糊", "去除负一屏背景壁纸模糊"),
                        checked = settings.removeWallpaperBlur,
                        onCheckedChange = { value -> update { it.copy(removeWallpaperBlur = value) } },
                    )
                    SwitchPreference(
                        title = tr("负一屏背景壁纸模糊值", "负一屏背景壁纸模糊值"),
                        checked = settings.customBlurActive,
                        enabled = !settings.removeWallpaperBlur,
                        onCheckedChange = { value ->
                            if (!settings.removeWallpaperBlur) update { it.copy(customWallpaperBlur = value) }
                        },
                    )
                    PortalAppearanceVisibility(settings.customBlurActive) {
                        PortalSlider(
                            title = tr("负一屏背景壁纸模糊值", "负一屏背景壁纸模糊值"),
                            value = settings.wallpaperBlurRadius,
                            range = 0..ASSISTANT_BLUR_RADIUS_MAX,
                            default = ASSISTANT_BLUR_RADIUS_DEFAULT,
                        ) { value ->
                            if (settings.customBlurActive) update { it.copy(wallpaperBlurRadius = value) }
                        }
                    }
                }
            }
            item {
                Group(tr("小部件", "小部件")) {
                    SharedViewArrowPreference(
                        title = tr("负一屏小部件柔光玻璃参数调整", "负一屏小部件柔光玻璃参数调整"),
                        onClick = openWidgetGlass,
                    )
                }
            }
        }
    }
    if (showBackgroundDialog) PersonalAssistantBackgroundDialog(
        settings = settings,
        onSaved = { id, name ->
            update { it.copy(backgroundImage = id, backgroundName = name) }
            showBackgroundDialog = false
        },
        onDismiss = { showBackgroundDialog = false },
    )
}
