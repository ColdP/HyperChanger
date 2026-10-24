// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import android.net.Uri
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
import android.content.ContentValues
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt
import java.util.UUID
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

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
    var showShare by remember { mutableStateOf<RearScreenshotShellStyle?>(null) }
    var showQr by remember { mutableStateOf<RearScreenshotShellStyle?>(null) }
    var pendingQrExport by remember { mutableStateOf<RearScreenshotShellStyle?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var actionName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val frame = remember { mutableStateOf<Bitmap?>(null) }
    val defaultShell by produceState<ShellPreviewAsset?>(null, context, mode) {
        value = withContext(Dispatchers.IO) { loadBundledShell(context, mode) }
        withContext(Dispatchers.IO) { loadDefaultShell(mode) }?.let { value = it }
    }
    val previewStyle = draft ?: selected
    val shellPreview = remember(previewStyle, defaultShell) {
        previewStyle?.let {
            BitmapFactory.decodeByteArray(it.image, 0, it.image.size)?.let { bitmap ->
                cropFocusPreview(ShellPreviewAsset(bitmap, parsePreviewGeometry(it.geometry)), it.mode)
            }
        } ?: defaultShell
    }
    val compositePreview by produceState<Bitmap?>(null, frame.value, shellPreview) {
        val latestFrame = frame.value
        value = withContext(Dispatchers.Default) { shellPreview?.let { composeShellPreview(latestFrame, it) } }
    }
    val capture = remember(context) {
        RearScreenCaptureSession(
            onFrame = { frame.value = it },
            onFailure = {
                frame.value = null
                error = tr("rear_screenshot_capture_failed", "无法获取背屏录屏预览，请确认背屏可用及Root权限。")
            },
        )
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.readUri(uri, mode) } }
                .onSuccess { draft = it; error = null }
                .onFailure { error = tr("rear_screenshot_shell_read_failed", "无法读取外壳文件") }
        }
    }
    val qrPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.readQr(uri, mode) } }
                .onSuccess { draft = it; error = null }
                .onFailure { error = tr("rear_screenshot_qr_invalid", "无法导入二维码，请选择HyperChanger导出的二维码原图。") }
        }
    }
    val qrExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val style = pendingQrExport
        pendingQrExport = null
        if (uri != null && style != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(store.qrImage(style)) }
            } }.onFailure { error = tr("rear_screenshot_export_failed", "导出失败，请重试。") }
        }
    }
    LaunchedEffect(Unit) {
        val root = withContext(Dispatchers.IO) { rearScreenshotRootCommand("id")?.contains("uid=0") == true }
        if (root) capture.start() else error = tr("rear_screenshot_root_required", "未授权Root权限，无法实时预览")
    }
    DisposableEffect(Unit) { onDispose { capture.stop(); capture.close() } }
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
                    compositePreview?.let { preview ->
                        Box(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp), contentAlignment = Alignment.Center) {
                            Image(
                                preview.asImageBitmap(),
                                tr("rear_screenshot_shell_preview", "背屏带壳截图预览"),
                                Modifier.fillMaxWidth(0.6f).clip(RoundedCornerShape(18.dp)),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassDialogButton(onClick = { showImport = true }, modifier = Modifier.weight(1f)) {
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

    if (showImport) WindowDialog(show = true, onDismissRequest = { showImport = false }) {
        Text(tr("rear_screenshot_import_method", "导入方式"), style = MiuixTheme.textStyles.title3)
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassDialogButton(onClick = { showImport = false; qrPicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("rear_screenshot_shell_qr", "二维码"))
            }
            GlassDialogButton(onClick = {
                showImport = false
                picker.launch(arrayOf("image/*", "application/zip", "application/octet-stream", "*/*"))
            }, modifier = Modifier.fillMaxWidth()) { Text(tr("rear_screenshot_shell_file", "文件")) }
            GlassDialogButton(onClick = { showImport = false }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("rear_screenshot_shell_cancel", "取消"))
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
                GlassDialogButton(onClick = { showShare = style; showActions = null }, modifier = Modifier.weight(1f)) { Text(tr("rear_screenshot_shell_share", "分享")) }
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
    showShare?.let { style ->
        WindowDialog(show = true, onDismissRequest = { showShare = null }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onClick = { showQr = style; showShare = null }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("rear_screenshot_shell_qr", "二维码"))
                }
                GlassDialogButton(onClick = {
                    val uri = Uri.parse("content://${REAR_SCREENSHOT_SHELL_AUTHORITY}/${style.mode.key}/${style.id}/export")
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, null)) }.onFailure { error = tr("rear_screenshot_export_failed", "导出失败，请重试。") }
                }, modifier = Modifier.fillMaxWidth()) { Text(tr("rear_screenshot_shell_archive", "压缩包")) }
                GlassDialogButton(onClick = { showShare = null }, modifier = Modifier.fillMaxWidth()) { Text(tr("rear_screenshot_shell_cancel", "取消")) }
            }
        }
    }
    showQr?.let { style ->
        val qr = remember(style) { createPresetQrCode(store.qrPayload(style), 512) }
        WindowDialog(show = true, onDismissRequest = { showQr = null }) {
            ShellDialogPreview(style)
            Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
                Image(qr.asImageBitmap(), tr("rear_screenshot_shell_qr", "二维码"), Modifier.size(180.dp))
            }
            Text(tr("rear_screenshot_qr_original", "二维码原图内附外壳文件，请保存并发送原图；截图不含外壳文件。"), modifier = Modifier.padding(top = 12.dp), style = MiuixTheme.textStyles.body2)
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { saveShellQrToGallery(context, store.qrImage(style)) } }
                            .onSuccess { Toast.makeText(context, tr("二维码已保存", "二维码已保存"), Toast.LENGTH_SHORT).show() }
                            .onFailure { error = tr("rear_screenshot_export_failed", "导出失败，请重试。") }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(tr("rear_screenshot_shell_save", "保存")) }
                GlassDialogButton(onClick = { pendingQrExport = style; qrExporter.launch("HyperChanger-shell-${style.mode.key}.png") }, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("rear_screenshot_shell_export", "导出"))
                }
                GlassDialogButton(onClick = { showQr = null }, modifier = Modifier.fillMaxWidth()) { Text(tr("rear_screenshot_shell_cancel", "取消")) }
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

private fun saveShellQrToGallery(context: Context, bytes: ByteArray) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "HyperChanger-shell-${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/HyperChanger")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
    try {
        requireNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    } catch (error: Throwable) {
        resolver.delete(uri, null, null)
        throw error
    }
}

private data class ShellPreviewGeometry(
    val contentWidth: Float,
    val contentHeight: Float,
    val leftMargin: Float,
    val topMargin: Float,
    val rightMargin: Float,
    val bottomMargin: Float,
)

private data class ShellPreviewAsset(val image: Bitmap, val geometry: ShellPreviewGeometry)

private fun parsePreviewGeometry(text: String): ShellPreviewGeometry {
    val json = JSONObject(text)
    return ShellPreviewGeometry(
        json.optDouble("with", 904.0).toFloat(),
        json.optDouble("height", 572.0).toFloat(),
        json.optDouble("leftMargin", 0.0).toFloat(),
        json.optDouble("topMargin", 0.0).toFloat(),
        json.optDouble("rightMargin", 0.0).toFloat(),
        json.optDouble("bottomMargin", 0.0).toFloat(),
    )
}

private fun loadDefaultShell(mode: RearScreenshotShellMode): ShellPreviewAsset? {
    val prefix = mode.prefix
    val base = "/data/user/0/com.miui.screenshot/files/back_screen"
    val imagePath = runRootCommand("find '$base' -mindepth 2 -maxdepth 2 -type f -name '${prefix}_shell.png' -print -quit")
        ?.lineSequence()?.firstOrNull { it.startsWith("$base/") }
    return imagePath?.let { path ->
        val image = readRootFile(path)
        val geometry = readRootFile(path.removeSuffix(".png") + ".json")
        if (image != null && geometry != null) {
            BitmapFactory.decodeByteArray(image, 0, image.size)?.let { bitmap ->
                runCatching { cropFocusPreview(ShellPreviewAsset(bitmap, parsePreviewGeometry(String(geometry, Charsets.UTF_8))), mode) }.getOrNull()
            }
        } else null
    }
}

private fun loadBundledShell(context: Context, mode: RearScreenshotShellMode): ShellPreviewAsset? {
    val prefix = mode.prefix
    val imageName = "rear_screenshot_shell_defaults/${prefix}_shell.png"
    val geometryName = "rear_screenshot_shell_defaults/${prefix}_shell.json"
    return runCatching {
        val image = context.assets.open(imageName).use { it.readBytes() }
        val geometry = context.assets.open(geometryName).use { it.readBytes() }
        BitmapFactory.decodeByteArray(image, 0, image.size)?.let { bitmap ->
            cropFocusPreview(ShellPreviewAsset(bitmap, parsePreviewGeometry(String(geometry, Charsets.UTF_8))), mode)
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

private fun composeShellPreview(frame: Bitmap?, asset: ShellPreviewAsset): Bitmap {
    val geometry = asset.geometry
    val shellWidth = (geometry.contentWidth + geometry.leftMargin + geometry.rightMargin).roundToInt().coerceAtLeast(1)
    val shellHeight = (geometry.contentHeight + geometry.topMargin + geometry.bottomMargin).roundToInt().coerceAtLeast(1)
    val previewScale = minOf(1f, 600f / shellWidth)
    val output = Bitmap.createBitmap((shellWidth * previewScale).roundToInt().coerceAtLeast(1), (shellHeight * previewScale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    canvas.scale(previewScale, previewScale)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    canvas.drawColor(Color.BLACK)
    if (frame != null && !frame.isRecycled) {
        val contentAspect = geometry.contentWidth / geometry.contentHeight
        val frameAspect = frame.width.toFloat() / frame.height.toFloat()
        val topMask = if (abs(frameAspect - contentAspect) <= 0.001f) 0f else ((frame.width / contentAspect) - frame.height).coerceAtLeast(0f)
        if (topMask > 0f) {
            paint.color = Color.BLACK
            canvas.drawRect(geometry.leftMargin, geometry.topMargin, shellWidth - geometry.rightMargin, geometry.topMargin + topMask, paint)
        }
        canvas.drawBitmap(
            frame,
            null,
            RectF(geometry.leftMargin, geometry.topMargin + topMask, shellWidth - geometry.rightMargin, shellHeight - geometry.bottomMargin),
            paint,
        )
    }
    canvas.drawBitmap(asset.image, null, RectF(0f, 0f, shellWidth.toFloat(), shellHeight.toFloat()), paint)
    return output
}

private fun cropFocusPreview(asset: ShellPreviewAsset, mode: RearScreenshotShellMode): ShellPreviewAsset {
    if (mode != RearScreenshotShellMode.FOCUS || asset.image.height < 4) return asset
    val cropPixels = (asset.image.height * 2f / 7f).roundToInt().coerceIn(1, asset.image.height - 1)
    val cropped = Bitmap.createBitmap(asset.image, 0, cropPixels, asset.image.width, asset.image.height - cropPixels)
    val scale = (asset.geometry.contentHeight + asset.geometry.topMargin + asset.geometry.bottomMargin) / asset.image.height.toFloat()
    return asset.copy(
        image = cropped,
        geometry = asset.geometry.copy(topMargin = (asset.geometry.topMargin - cropPixels * scale).coerceAtLeast(0f)),
    )
}
