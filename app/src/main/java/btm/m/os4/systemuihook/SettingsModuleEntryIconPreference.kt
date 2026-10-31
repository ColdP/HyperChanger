// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun SettingsModuleEntryIconPreference() {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { SettingsModuleEntryIconStore(context) }
    val imageReader = remember(context) { SettingsEntryIconStore(context) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) {
        bitmap = withContext(Dispatchers.IO) { store.load() }
        loaded = true
    }
    ArrowPreference(
        title = tr("settingsModuleEntryIcon", "设置模块入口图标"),
        summary = if (!loaded) null else if (bitmap == null) tr("未导入", "未导入") else tr("已导入", "已导入"),
        enabled = loaded && !busy,
        onClick = { dialog = true },
    )
    if (dialog) SettingsEntrySingleIconDialog(
        title = tr("settingsModuleEntryIcon", "设置模块入口图标"),
        bitmap = bitmap,
        store = imageReader,
        busySaving = busy,
        onSave = { value ->
            if (!busy) scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) { store.save(value, service) }
                    bitmap = value
                    dialog = false
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Toast.makeText(context, tr("settingsIconsSaveFailed", "图标保存失败，请重试"), Toast.LENGTH_SHORT).show()
                } finally { busy = false }
            }
        },
        onDismiss = { dialog = false },
    )
}
