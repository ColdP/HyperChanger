// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.util.UUID
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RearScreenshotShellPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context) { RearScreenshotShellStore(context) }
    var mode by remember { mutableStateOf(RearScreenshotShellMode.FOCUS) }
    var styles by remember(mode) { mutableStateOf(store.list(mode)) }
    var selected by remember(mode) { mutableStateOf(store.active(mode)) }
    var draft by remember(mode) { mutableStateOf<RearScreenshotShellStyle?>(null) }
    var showSave by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf<RearScreenshotShellStyle?>(null) }
    var confirmDelete by remember { mutableStateOf<RearScreenshotShellStyle?>(null) }
    var exportedStyle by remember { mutableStateOf<RearScreenshotShellStyle?>(null) }
    var name by remember { mutableStateOf("") }
    var actionName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val defaultShell by produceState<Bitmap?>(null, context, mode) {
        value = withContext(Dispatchers.IO) { loadBundledShell(context, mode) }
        withContext(Dispatchers.IO) { loadDefaultShell(mode) }?.let { value = it }
    }
    val previewStyle = draft ?: selected
    val shellPreview = remember(previewStyle, defaultShell) {
        previewStyle?.let {
            BitmapFactory.decodeByteArray(it.image, 0, it.image.size)?.let { bitmap ->
                cropFocusPreview(bitmap, it.mode)
            }
        } ?: defaultShell
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.readUri(uri, mode) } }
                .onSuccess { draft = it; error = null }
                .onFailure { error = tr("rear_screenshot_shell_read_failed", "无法读取外壳文件") }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val style = exportedStyle
        exportedStyle = null
        if (uri != null && style != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(store.pngImage(style)) }
            } }.onFailure { error = tr("rear_screenshot_export_failed", "导出失败，请重试。") }
        }
    }
    LaunchedEffect(service) { service?.let { store.syncRemote(it) } }

    fun persistStyle(value: RearScreenshotShellStyle, styleName: String = value.name, apply: Boolean = true) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                store.save(
                    value.mode,
                    styleName,
                    value.image,
                    value.geometry,
                    value.id.ifBlank { UUID.randomUUID().toString() },
                )
            } }.onSuccess { saved ->
                if (apply) store.select(value.mode, saved, service)
                if (mode == value.mode) {
                    if (apply) selected = saved
                    draft = saved
                    styles = store.list(mode)
                }
            }.onFailure { error = tr("rear_screenshot_save_failed", "无法保存外壳样式，请重试。") }
        }
    }

    AppPage(
        title = tr("rear_screenshot_shell_title", "自定义带壳截图外壳样式"),
        onBack = back,
        header = {
            SoftGlassHeaderTabs(
                listOf(
                    tr("rear_screenshot_shell_focus", "聚焦"),
                    tr("rear_screenshot_shell_full", "完整"),
                ),
                mode.ordinal,
                { index -> RearScreenshotShellMode.entries.getOrNull(index)?.let { mode = it } },
            )
        },
        restartScopes = setOf(ScopeApplication.SCREENSHOT),
    ) { padding, scroll ->
        AppList(padding, scroll, 20) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    shellPreview?.let { image ->
                        Box(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp), contentAlignment = Alignment.Center) {
                            Image(
                                image.asImageBitmap(),
                                tr("rear_screenshot_shell_preview", "背屏带壳截图外壳预览"),
                                Modifier.fillMaxWidth(0.6f).clip(RoundedCornerShape(18.dp)),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassDialogButton(onClick = { picker.launch(arrayOf("image/*", "application/zip", "application/octet-stream", "*/*")) }, modifier = Modifier.weight(1f)) {
                            Text(tr("rear_screenshot_shell_import", "导入"))
                        }
                        GlassDialogButton(
                            onClick = {
                                draft = null
                                store.select(mode, null, service)
                                selected = null
                            },
                            modifier = Modifier.weight(1f), enabled = draft != null || selected != null,
                        ) { Text(tr("rear_screenshot_shell_clear", "清除")) }
                    }
                    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassDialogButton(
                            onClick = { previewStyle?.let { persistStyle(it) } },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = previewStyle != null,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) { Text(tr("rear_screenshot_shell_apply", "应用")) }
                        GlassDialogButton(
                            onClick = { draft = previewStyle; name = previewStyle?.name.orEmpty(); showSave = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = previewStyle != null,
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) { Text(tr("rear_screenshot_shell_save", "保存")) }
                    }
                }
            }
            item {
                Text(tr("rear_screenshot_shell_saved", "已保存的外壳样式"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
            if (styles.isEmpty()) item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    Text(tr("rear_screenshot_shell_empty", "暂无已保存样式"), modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2)
                }
            } else items(styles.size, key = { styles[it].previewKey }) { index ->
                val style = styles[index]
                Card(Modifier.fillMaxWidth().clickable { actionName = style.name; showActions = style }, cornerRadius = 22.5.dp) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(BitmapFactory.decodeByteArray(style.image, 0, style.image.size)!!.asImageBitmap(), tr("rear_screenshot_shell_image_preview", "外壳预览"), Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                        Text(style.name, style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(start = 14.dp))
                    }
                }
            }
        }
    }

    if (error != null) WindowDialog(show = true, onDismissRequest = { error = null }) {
        Text(error.orEmpty(), style = MiuixTheme.textStyles.body1)
        GlassDialogButton(onClick = { error = null }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(tr("确定", "确定")) }
    }
    if (showSave) WindowDialog(show = true, onDismissRequest = { showSave = false }) {
        draft?.let { style ->
            ShellDialogPreview(style)
        }
        TextField(name, { name = it.take(64) }, label = tr("rear_screenshot_shell_name", "样式名"), useLabelAsPlaceholder = true, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassDialogButton(onClick = { showSave = false }, modifier = Modifier.weight(1f)) { Text(tr("rear_screenshot_shell_cancel", "取消")) }
            GlassDialogButton(onClick = {
                val value = draft ?: return@GlassDialogButton
                persistStyle(value, name, apply = false)
                showSave = false
            }, modifier = Modifier.weight(1f), enabled = name.isNotBlank(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("rear_screenshot_shell_save", "保存")) }
        }
    }
    showActions?.let { style ->
        WindowDialog(show = true, onDismissRequest = { showActions = null }) {
            ShellDialogPreview(style)
            TextField(actionName, { actionName = it.take(64) }, label = tr("rear_screenshot_shell_name", "样式名"), singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onClick = { confirmDelete = style }, modifier = Modifier.weight(1f)) { Text(tr("rear_screenshot_shell_delete", "删除")) }
                GlassDialogButton(onClick = { exportedStyle = style; showActions = null; exporter.launch("HyperChanger-shell-${style.mode.key}.png") }, modifier = Modifier.weight(1f)) { Text(tr("rear_screenshot_shell_export", "导出")) }
            }
            Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onClick = {
                    persistStyle(style, actionName)
                    showActions = null
                }, modifier = Modifier.fillMaxWidth(), enabled = actionName.isNotBlank(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(tr("rear_screenshot_shell_apply", "应用"))
                }
                GlassDialogButton(onClick = {
                    persistStyle(style, actionName, apply = false)
                    showActions = null
                }, modifier = Modifier.fillMaxWidth(), enabled = actionName.isNotBlank(), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(tr("rear_screenshot_shell_save", "保存"))
                }
                GlassDialogButton(onClick = { showActions = null }, modifier = Modifier.fillMaxWidth()) { Text(tr("rear_screenshot_shell_cancel", "取消")) }
            }
        }
    }
    confirmDelete?.let { style ->
        WindowDialog(show = true, onDismissRequest = { confirmDelete = null }) {
            Text(tr("rear_screenshot_shell_delete_confirm", "确定删除此外壳样式吗？"), style = MiuixTheme.textStyles.body1)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onClick = { confirmDelete = null }, modifier = Modifier.weight(1f)) { Text(tr("rear_screenshot_shell_cancel", "取消")) }
                GlassDialogButton(onClick = {
                    store.delete(style, service)
                    styles = store.list(mode)
                    if (selected?.id == style.id) selected = null
                    if (draft?.id == style.id) draft = null
                    confirmDelete = null
                    showActions = null
                }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("rear_screenshot_shell_delete", "删除")) }
            }
        }
    }
}

@Composable
private fun ShellDialogPreview(style: RearScreenshotShellStyle) {
    val bitmap = remember(style) { BitmapFactory.decodeByteArray(style.image, 0, style.image.size) }
    bitmap?.let {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            val aspect = it.width.toFloat() / it.height
            Image(it.asImageBitmap(), tr("rear_screenshot_shell_image_preview", "外壳预览"),
                Modifier.width(minOf(maxWidth, 180.dp * aspect)).aspectRatio(aspect).clip(RoundedCornerShape(18.dp)),
                contentScale = ContentScale.FillBounds)
        }
    }
}

private fun loadDefaultShell(mode: RearScreenshotShellMode): Bitmap? {
    val prefix = mode.prefix
    val base = "/data/user/0/com.miui.screenshot/files/back_screen"
    val imagePath = runRootCommand("find '$base' -mindepth 2 -maxdepth 2 -type f -name '${prefix}_shell.png' -print -quit")
        ?.lineSequence()?.firstOrNull { it.startsWith("$base/") }
    return imagePath?.let { path ->
        val image = readRootFile(path)
        if (image != null) {
            BitmapFactory.decodeByteArray(image, 0, image.size)?.let { bitmap ->
                cropFocusPreview(bitmap, mode)
            }
        } else null
    }
}

private fun loadBundledShell(context: Context, mode: RearScreenshotShellMode): Bitmap? {
    val prefix = mode.prefix
    val imageName = "rear_screenshot_shell_defaults/${prefix}_shell.png"
    return runCatching {
        val image = context.assets.open(imageName).use { it.readBytes() }
        BitmapFactory.decodeByteArray(image, 0, image.size)?.let { bitmap ->
            cropFocusPreview(bitmap, mode)
        }
    }.getOrNull()
}

private fun readRootFile(path: String): ByteArray? = runCatching {
    val process = ProcessBuilder("su", "-c", "cat -- '$path'").redirectErrorStream(true).start()
    val bytes = process.inputStream.use { it.readBytes() }
    if (process.waitFor() == 0) bytes else null
}.getOrNull()

private fun runRootCommand(command: String): String? = runCatching {
    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (process.waitFor() == 0) output else null
}.getOrNull()

private fun cropFocusPreview(image: Bitmap, mode: RearScreenshotShellMode): Bitmap {
    if (mode != RearScreenshotShellMode.FOCUS || image.height < 4) return image
    val cropPixels = (image.height * 2f / 7f).roundToInt().coerceIn(1, image.height - 1)
    return Bitmap.createBitmap(image, 0, cropPixels, image.width, image.height - cropPixels)
}
