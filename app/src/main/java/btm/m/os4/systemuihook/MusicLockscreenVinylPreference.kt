// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import btm.m.os4.systemuihook.InterfaceSwitchPreference as SwitchPreference

@Composable
internal fun MusicLockscreenVinylPreference() {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { MusicLockscreenVinylSettingsStore(context) }
    var state by remember { mutableStateOf(store.settings) }
    LaunchedEffect(service) { service?.let { store.syncRemote(it); state = store.settings } }
    fun update(transform: (MusicLockscreenVinylSettings) -> MusicLockscreenVinylSettings) {
        store.update(service, transform)
        state = store.settings
    }
    Column {
        SwitchPreference(
            title = tr("hmcVinylRotation", "黑胶旋转动画"), checked = state.rotation,
            onCheckedChange = { value -> update { it.copy(rotation = value) } },
        )
        AnimatedVisibility(visible = state.rotation) {
            SwitchPreference(
                title = tr("hmcVinylRotationKeepOn", "黑胶旋转时屏幕常亮"),
                checked = state.rotationKeepOn,
                onCheckedChange = { value -> update { it.copy(rotationKeepOn = value) } },
            )
        }
        SwitchPreference(
            title = tr("hmcVinylTonearm", "显示黑胶唱臂与唱头"), checked = state.tonearm,
            onCheckedChange = { value -> update { it.copy(tonearm = value) } },
        )
        OverlayDropdownPreference(
            title = tr("hmcVinylColor", "黑胶颜色"),
            items = listOf(tr("hmcVinylDefault", "默认"), tr("hmcFromCover", "跟随封面"), tr("hmcVinylCustom", "自定义")),
            selectedIndex = state.colorMode,
            onSelectedIndexChange = { value -> update { it.copy(colorMode = value) } },
        )
        AnimatedVisibility(visible = state.colorMode == 2) {
            ShortcutBackgroundColorPreference(
                title = tr("hmcVinylCustomColor", "自定义黑胶颜色"), color = state.customColor,
                onColorChange = { value -> update { it.copy(customColor = value) } },
            )
        }
    }
}
