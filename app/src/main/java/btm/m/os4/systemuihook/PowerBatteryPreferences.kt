// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import btm.m.os4.systemuihook.InterfaceSwitchPreference as SwitchPreference

@Composable
internal fun PowerBatteryPreferences() {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { PowerBatterySettingsStore(context) }
    var settings by remember(store) { mutableStateOf(store.settings) }
    LaunchedEffect(service) {
        service?.let { store.syncRemote(it); settings = store.settings }
    }
    Group(tr("省电与电池", "省电与电池")) {
        PowerBatteryFeature.entries.forEach { feature ->
            SwitchPreference(
                title = tr(feature.title, feature.title),
                checked = feature in settings,
                onCheckedChange = { value ->
                    store.update(service, feature, value)
                    settings = store.settings
                },
            )
        }
    }
}
