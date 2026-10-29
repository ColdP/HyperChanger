// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun PortalMaterialPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val preferences = remember(context) { context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE) }
    var settings by remember(service) { mutableStateOf(PortalSettings.read(preferences)) }
    fun save(value: PortalAppearanceSettings) {
        PortalSettings.save(context, service, value)
        settings = value
    }
    AppPage(
        tr("传送门材质调整", "传送门材质调整"), back,
        restartScopes = setOf(ScopeApplication.PORTAL), restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    OverlayDropdownPreference(
                        title = tr("背景材质", "背景材质"),
                        items = listOf(tr("系统默认", "系统默认"), tr("高级材质", "高级材质"), tr("柔光玻璃", "柔光玻璃")),
                        selectedIndex = settings.materialMode,
                        onSelectedIndexChange = { save(settings.copy(materialMode = it)) },
                    )
                    PortalAppearanceVisibility(settings.materialMode == PORTAL_MATERIAL_SOFT_GLASS) {
                        SwitchPreference(
                            title = tr("使用全局柔光玻璃参数", "使用全局柔光玻璃参数"),
                            checked = settings.useGlobalGlass,
                            onCheckedChange = { save(settings.copy(useGlobalGlass = it)) },
                        )
                    }
                }
            }
            item {
                PortalAppearanceVisibility(settings.materialMode != PORTAL_MATERIAL_DEFAULT &&
                    (settings.materialMode != PORTAL_MATERIAL_SOFT_GLASS || !settings.useGlobalGlass)) {
                    Group(tr("材质参数调整", "材质参数调整")) {
                        PortalAppearanceVisibility(settings.materialMode == PORTAL_MATERIAL_ADVANCED) {
                            PortalAdvancedMaterialControls(settings, ::save)
                        }
                        PortalAppearanceVisibility(settings.materialMode == PORTAL_MATERIAL_SOFT_GLASS && !settings.useGlobalGlass) {
                            SoftGlassMaterialSliders(settings.glass, includeBackdrop = false) { transform ->
                                save(settings.copy(glass = transform(settings.glass).normalized()))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortalAdvancedMaterialControls(settings: PortalAppearanceSettings, save: (PortalAppearanceSettings) -> Unit) {
    androidx.compose.foundation.layout.Column {
        SwitchPreference(
            title = tr("自定义背景卡片模糊度", "自定义背景卡片模糊度"),
            checked = settings.customBlur, onCheckedChange = { save(settings.copy(customBlur = it)) },
        )
        PortalAppearanceVisibility(settings.customBlur) {
            PortalSlider(tr("背景卡片模糊度", "背景卡片模糊度"), settings.blurRadiusDp, 0..100, 24, " dp") {
                save(settings.copy(blurRadiusDp = it))
            }
        }
        SwitchPreference(
            title = tr("自定义背景卡片不透明度", "自定义背景卡片不透明度"),
            checked = settings.customOpacity, onCheckedChange = { save(settings.copy(customOpacity = it)) },
        )
        PortalAppearanceVisibility(settings.customOpacity) {
            PortalSlider(tr("背景卡片不透明度", "背景卡片不透明度"), settings.opacityPercent, 0..100, 70, "%") {
                save(settings.copy(opacityPercent = it))
            }
        }
        SwitchPreference(
            title = tr("显示高光", "显示高光"),
            checked = settings.advancedHighlight,
            onCheckedChange = { save(settings.copy(advancedHighlight = it)) },
        )
    }
}
