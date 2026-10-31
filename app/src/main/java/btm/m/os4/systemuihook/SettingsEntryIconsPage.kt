// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private fun SettingsEntryIconEntry.label() = tr("settingsIcon_$id", title)

@Composable
internal fun SettingsEntryIconsPage(back: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { SettingsEntryIconStore(context) }
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(SettingsEntryIconPack("", emptyMap())) }
    var loaded by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SettingsEntryIconEntry?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(store) {
        saved = withContext(Dispatchers.IO) { store.load() }
        loaded = true
    }
    fun save(pack: SettingsEntryIconPack, done: () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { store.save(pack, service) }
                saved = pack
                done()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(context, tr("settingsIconsSaveFailed", "图标保存失败，请重试"), Toast.LENGTH_SHORT).show()
            } finally { busy = false }
        }
    }
    AppPage(tr("settingsEntryIcons", "自定义系统设置入口图标"), back, restartScopes = setOf(ScopeApplication.SETTINGS)) { padding, scroll ->
        AppList(padding, scroll) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    ArrowPreference(
                        title = tr("settingsIconsBatchImport", "批量导入"),
                        enabled = loaded && !busy,
                        onClick = { showImport = true },
                    )
                    if (saved.icons.isNotEmpty()) ArrowPreference(
                        title = tr("settingsIconsResetAll", "全部恢复默认"),
                        enabled = !busy,
                        onClick = { confirmReset = true },
                    )
                }
            }
            settingsEntryIconGroups.forEach { group ->
                item {
                    Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                        group.forEach { entry ->
                            ArrowPreference(
                                title = entry.label(),
                                startAction = {
                                    SettingsEntryIconPreview(saved.icons[entry.id], entry.label())
                                },
                                enabled = loaded && !busy,
                                onClick = { editing = entry },
                            )
                        }
                    }
                }
            }
        }
    }
    if (showImport) SettingsEntryIconImportDialog(
        store = store,
        busySaving = busy,
        onSave = { pack -> save(pack) { showImport = false } },
        onDismiss = { showImport = false },
    )
    editing?.let { entry ->
        SettingsEntrySingleIconDialog(
            title = entry.label(),
            bitmap = saved.icons[entry.id],
            store = store,
            busySaving = busy,
            onSave = { bitmap ->
                val icons = if (bitmap == null) saved.icons - entry.id else saved.icons + (entry.id to bitmap)
                save(saved.copy(icons = icons)) { editing = null }
            },
            onDismiss = { editing = null },
        )
    }
    if (confirmReset) WindowDialog(show = true, onDismissRequest = { if (!busy) confirmReset = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("settingsIconsResetAll", "全部恢复默认"), style = MiuixTheme.textStyles.title3)
            Text(tr("settingsIconsResetConfirm", "确定清除所有已导入的入口图标并恢复系统默认图标？"))
            GlassDialogButton(
                onClick = { save(SettingsEntryIconPack("", emptyMap())) { confirmReset = false } },
                modifier = Modifier.fillMaxWidth(), enabled = !busy,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("确认", "确认")) }
            GlassDialogButton(onClick = { confirmReset = false }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Text(tr("取消", "取消"))
            }
        }
    }
}

@Composable
private fun SettingsEntryIconPreview(bitmap: android.graphics.Bitmap?, description: String, modifier: Modifier = Modifier) {
    // A 16dp inset from the 22.5dp card gives a concentric 6.5dp inner corner.
    val shape = remember { RoundedRectangle(6.5.dp, RoundedCornerStyle.Continuous) }
    if (bitmap != null) Image(
        bitmap = bitmap.asImageBitmap(), contentDescription = description,
        modifier = modifier.size(28.dp), contentScale = ContentScale.Fit,
    ) else Box(
        modifier.size(28.dp).clip(shape).background(MiuixTheme.colorScheme.onSurface.copy(alpha = .10f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = tr("settingsIconNotImported", "未导入图标"),
            modifier = Modifier.size(16.dp), tint = MiuixTheme.colorScheme.onSurface.copy(alpha = .4f),
        )
    }
}

@Composable
private fun SettingsEntryIconImportDialog(
    store: SettingsEntryIconStore,
    busySaving: Boolean,
    onSave: (SettingsEntryIconPack) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<SettingsEntryIconPack?>(null) }
    var reading by remember { mutableStateOf(false) }
    val busy = reading || busySaving
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            reading = true
            try {
                draft = withContext(Dispatchers.IO) { store.read(uri) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(context, tr("settingsIconsImportFailed", "无法读取文件或未找到可用的设置入口图标"), Toast.LENGTH_SHORT).show()
            } finally { reading = false }
        }
    }
    WindowDialog(show = true, onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
            val selected = draft
            if (selected == null) Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) {
                Text(tr("settingsIconsPendingFile", "待选择文件"), style = MiuixTheme.textStyles.title3, textAlign = TextAlign.Start)
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.Start)) {
                // Show the first five imported icons, rather than five fixed entries that may be absent.
                selected.icons.entries.take(5).forEach { (id, bitmap) ->
                    SettingsEntryIconPreview(bitmap, settingsEntryIconCatalog.first { it.id == id }.label())
                }
            }
            Text(
                selected?.name ?: tr("settingsIconsChooseArchive", "请选择文件"),
                style = MiuixTheme.textStyles.body2,
                textAlign = TextAlign.Start,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f), enabled = !busy) {
                    Text(tr("选择文件", "选择文件"))
                }
                GlassDialogButton(onClick = { draft = null }, modifier = Modifier.weight(1f), enabled = !busy && draft != null) {
                    Text(tr("清除", "清除"))
                }
            }
            GlassDialogButton(
                onClick = { draft?.let(onSave) }, modifier = Modifier.fillMaxWidth(), enabled = !busy && draft != null,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("保存", "保存")) }
            GlassDialogButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("取消", "取消")) }
        }
    }
}

@Composable
internal fun SettingsEntrySingleIconDialog(
    title: String,
    bitmap: android.graphics.Bitmap?,
    store: SettingsEntryIconStore,
    busySaving: Boolean,
    onSave: (android.graphics.Bitmap?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember(title) { mutableStateOf(bitmap) }
    var reading by remember { mutableStateOf(false) }
    val busy = reading || busySaving
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            reading = true
            try { draft = withContext(Dispatchers.IO) { store.readImage(uri) } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(context, tr("settingsIconsImageFailed", "无法读取所选图片"), Toast.LENGTH_SHORT).show()
            } finally { reading = false }
        }
    }
    WindowDialog(show = true, onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
            Text(title, style = MiuixTheme.textStyles.title3, textAlign = TextAlign.Start)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) { SettingsEntryIconPreview(draft, title) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f), enabled = !busy) {
                    Text(tr("选择图片", "选择图片"))
                }
                GlassDialogButton(onClick = { draft = null }, modifier = Modifier.weight(1f), enabled = !busy && draft != null) {
                    Text(tr("恢复默认", "恢复默认"))
                }
            }
            GlassDialogButton(onClick = { onSave(draft) }, modifier = Modifier.fillMaxWidth(), enabled = !busy, colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(tr("保存", "保存"))
            }
            GlassDialogButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("取消", "取消")) }
        }
    }
}
