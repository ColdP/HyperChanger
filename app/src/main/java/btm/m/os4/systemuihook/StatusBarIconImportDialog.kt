// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun StatusBarIconImportPreference() {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { StatusBarIconImportStore(context) }
    var saved by remember(store) { mutableStateOf(store.load()) }
    var selectedScope by remember(store) { mutableIntStateOf(store.loadScope()) }
    var showImport by remember { mutableStateOf(false) }
    var showScope by remember { mutableStateOf(false) }
    LaunchedEffect(service) { service?.let(store::syncRemote) }
    ArrowPreference(
        title = tr("系统界面资源导入", "系统界面资源导入"),
        summary = saved?.name ?: tr("未导入", "未导入"),
        onClick = { showImport = true },
    )
    if (saved != null) {
        ArrowPreference(
            title = tr("资源应用范围", "资源应用范围"),
            summary = resourceScopeSummary(selectedScope),
            onClick = { showScope = true },
        )
    }
    if (showImport) StatusBarIconImportDialog(
        store,
        saved,
        onSaved = { saved = it; showImport = false },
        onDismiss = { showImport = false },
    )
    if (showScope) ResourceApplicationScopeDialog(
        selectedScope = selectedScope,
        onSave = {
            store.saveScope(it, service)
            selectedScope = it
            showScope = false
        },
        onDismiss = { showScope = false },
    )
}

@Composable
private fun resourceScopeSummary(scope: Int): String? {
    val labels = mutableListOf<String>()
    if (scope and SYSTEM_UI_RESOURCE_SCOPE_STATUS_BAR != 0) labels += tr("状态栏", "状态栏")
    if (scope and SYSTEM_UI_RESOURCE_SCOPE_CONTROL_CENTER != 0) labels += tr("控制中心", "控制中心")
    return labels.takeIf { it.isNotEmpty() }?.joinToString(" / ")
}

@Composable
private fun ResourceApplicationScopeDialog(
    selectedScope: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(selectedScope) { mutableIntStateOf(selectedScope) }
    fun toggle(bit: Int) {
        draft = if (draft and bit != 0) draft and bit.inv() else draft or bit
    }
    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(tr("资源应用范围", "资源应用范围"), style = MiuixTheme.textStyles.title3)
            resourceScopeSummary(draft)?.let { Text(it, style = MiuixTheme.textStyles.body2) }
            Row(
                Modifier.fillMaxWidth().clickable { toggle(SYSTEM_UI_RESOURCE_SCOPE_STATUS_BAR) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(
                    state = if (draft and SYSTEM_UI_RESOURCE_SCOPE_STATUS_BAR != 0) ToggleableState.On else ToggleableState.Off,
                    onClick = { toggle(SYSTEM_UI_RESOURCE_SCOPE_STATUS_BAR) },
                )
                Text(tr("状态栏", "状态栏"))
            }
            Row(
                Modifier.fillMaxWidth().clickable { toggle(SYSTEM_UI_RESOURCE_SCOPE_CONTROL_CENTER) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(
                    state = if (draft and SYSTEM_UI_RESOURCE_SCOPE_CONTROL_CENTER != 0) ToggleableState.On else ToggleableState.Off,
                    onClick = { toggle(SYSTEM_UI_RESOURCE_SCOPE_CONTROL_CENTER) },
                )
                Text(tr("控制中心", "控制中心"))
            }
            GlassDialogButton(
                onClick = { draft = SYSTEM_UI_RESOURCE_SCOPE_ALL },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("全选", "全选")) }
            GlassDialogButton(
                onClick = { onSave(draft) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("保存", "保存")) }
            GlassDialogButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(tr("取消", "取消"))
            }
        }
    }
}

@Composable
private fun StatusBarIconImportDialog(
    store: StatusBarIconImportStore,
    saved: StatusBarIconImport?,
    onSaved: (StatusBarIconImport?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(saved) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                draft = withContext(Dispatchers.IO) { store.read(uri) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(context, tr("系统界面资源文件无法识别", "系统界面资源文件无法识别"), Toast.LENGTH_SHORT).show()
            } finally { busy = false }
        }
    }
    WindowDialog(show = true, onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            val selected = draft
            if (selected == null) {
                Text(tr("未导入", "未导入"), style = MiuixTheme.textStyles.title3)
            } else {
                val tint = MiuixTheme.colorScheme.onSurface
                Row(
                    Modifier.fillMaxWidth().height(42.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(selected.signal, selected.wifi, selected.battery).forEach { bitmap ->
                        if (bitmap == null) Text(tr("未导入", "未导入"), style = MiuixTheme.textStyles.body2)
                        else Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = tr("系统界面资源预览", "系统界面资源预览"),
                            modifier = Modifier.size(36.dp),
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.tint(tint),
                        )
                    }
                }
                Text(selected.name, style = MiuixTheme.textStyles.body2)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(
                    onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed", "*/*")) },
                    modifier = Modifier.weight(1f), enabled = !busy,
                ) { Text(tr("选择文件", "选择文件")) }
                GlassDialogButton(
                    onClick = { draft = null }, modifier = Modifier.weight(1f), enabled = !busy && draft != null,
                ) { Text(tr("恢复默认", "恢复默认")) }
            }
            GlassDialogButton(
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) { store.save(draft, service) }
                            onSaved(draft)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            Toast.makeText(context, tr("系统界面资源保存失败", "系统界面资源保存失败"), Toast.LENGTH_SHORT).show()
                        } finally { busy = false }
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = !busy,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("保存", "保存")) }
            GlassDialogButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Text(tr("取消", "取消"))
            }
        }
    }
}
