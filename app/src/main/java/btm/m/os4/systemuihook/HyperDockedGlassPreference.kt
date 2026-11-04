package btm.m.os4.systemuihook

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import btm.m.liquidglass.HyperDockedNavigationSettings
import io.github.libxposed.service.XposedService
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun rememberHyperDockedGlassEnabled(): Boolean {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE) }
    val revision = rememberNavigationMaterialRevision(prefs)
    return remember(prefs, revision) { HyperDockedNavigationSettings.glassEnabled(prefs) }
}

@Composable
internal fun HyperDockedGlassPreference(service: XposedService?) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE) }
    val enabled = rememberHyperDockedGlassEnabled()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(service) {
        service?.let { HyperDockedNavigationSettings.syncPreferences(prefs, it.getRemotePreferences(REMOTE_PREFERENCE_GROUP)) }
    }
    val title = tr("hyperDockedGlass", "HyperOS 底部导航栏启用液态玻璃材质（实验性）")
    SwitchPreference(title = title, checked = enabled, onCheckedChange = {
        if (it) confirm = true else HyperDockedNavigationSettings.saveGlass(prefs, service, false)
    })
    WindowDialog(show = confirm, title = title, titleTextAlign = TextAlign.Start,
        summary = tr("hyperDockedGlassWarning", "此实验性功能可能增加功耗，并出现渲染异常、卡顿或不稳定。启用后将应用于 HyperOS 底部导航栏，目标应用需重启后生效。是否继续？"),
        summaryTextAlign = TextAlign.Start, onDismissRequest = { confirm = false }) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton(onClick = { confirm = false }, modifier = Modifier.weight(1f)) { Text(tr("取消", "取消")) }
            GlassDialogButton(onClick = {
                HyperDockedNavigationSettings.saveGlass(prefs, service, true)
                confirm = false
            }, modifier = Modifier.weight(1f)) { Text(tr("确认", "确认")) }
        }
    }
}
