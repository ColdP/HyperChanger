// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import btm.m.os4.systemuihook.InterfaceSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

@Composable
internal fun CameraWatermarkPage(
    settings: CameraWatermarkSettings,
    customModel: String,
    update: ((CameraWatermarkSettings) -> CameraWatermarkSettings) -> Unit,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val identity = remember(settings.brand, customModel) {
        resolveCameraWatermarkIdentity(settings.brand, customModel, cameraSystemMarketName(), android.os.Build.BRAND)
    }
    var previewTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); previewTime = System.currentTimeMillis() } }
    val library = remember(context) { CameraWatermarkLibrary(context) }
    val scope = rememberCoroutineScope()
    val service by HookApplication.service.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val kind = if (tab == 0) "photo" else "video"
    var entries by remember { mutableStateOf<List<SavedCameraWatermark>>(emptyList()) }
    var pending by remember { mutableStateOf<CameraWatermarkPackage?>(null) }
    var selected by remember { mutableStateOf<SavedCameraWatermark?>(null) }
    var deleting by remember { mutableStateOf<SavedCameraWatermark?>(null) }
    var exporting by remember { mutableStateOf<SavedCameraWatermark?>(null) }
    var busy by remember { mutableStateOf(false) }
    val changed = LocalNotificationSettingsChanged.current
    LaunchedEffect(library) { entries = withContext(Dispatchers.IO) { library.list() } }
    fun perform(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, tr("camera_watermark_operation_failed", "操作失败，请检查水印 ZIP 或重试"), Toast.LENGTH_LONG).show() }
            finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            val pack = withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openInputStream(uri)).use {
                    CameraWatermarkZip.decode(CameraWatermarkZip.readBounded(it, WATERMARK_MAX_ZIP))
                }
            }
            require(pack.kind == kind)
            pending = pack
        }
    }
    fun choose() = importer.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val entry = exporting
        exporting = null
        if (uri != null && entry != null) perform {
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> entry.archive.inputStream().use { it.copyTo(output) } }
            }
            Toast.makeText(context, tr("camera_watermark_exported", "水印已导出"), Toast.LENGTH_SHORT).show()
        }
    }
    AppPage(
        tr("camera_watermark_library", "水印库"), back,
        restartScopes = setOf(ScopeApplication.CAMERA, ScopeApplication.MEDIA_EDITOR),
        header = { SoftGlassHeaderTabs(listOf(tr("camera_watermark_photo", "照片"), tr("camera_watermark_video", "视频")), tab, { if (!busy && pending == null) tab = it }) },
    ) { padding, scroll ->
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Adaptive(160.dp),
            modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 4.dp, 16.dp, padding.calculateBottomPadding() + 28.dp),
            verticalItemSpacing = 12.dp,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = StaggeredGridItemSpan.FullLine) {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(title = tr("camera_watermark_unlock_all", "解锁所有相机水印"), checked = settings.allWatermarks,
                        onCheckedChange = { value -> update { it.copy(allWatermarks = value) } })
                    SwitchPreference(title = tr("camera_watermark_unlock_front", "去除前摄水印限制"), checked = settings.frontWatermarks,
                        onCheckedChange = { value -> update { it.copy(frontWatermarks = value) } })
                    SharedViewArrowPreference(title = tr("camera_watermark_import", "导入水印"),
                        summary = tr("camera_watermark_import_hint", "导入当前分类的水印 ZIP，保存后重新打开相机生效"), onClick = { if (!busy) choose() })
                }
            }
            val visible = entries.filter { it.kind == kind }
            if (visible.isEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                Text(tr("camera_watermark_empty", "还没有导入水印"), modifier = Modifier.padding(16.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            items(visible, key = { it.id }) { entry ->
                val pack by produceState<CameraWatermarkPackage?>(null, entry.id) {
                    value = withContext(Dispatchers.IO) { runCatching { CameraWatermarkZip.decode(entry.archive.readBytes()) }.getOrNull() }
                }
                val bitmap by watermarkRenderedBitmap(pack, identity, settings.suffixes, 640, previewTime)
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.5.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer).clickable(enabled = !busy) { selected = entry }) {
                    bitmap?.let { Image(it.asImageBitmap(), contentDescription = entry.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
            }
        }
    }
    pending?.let { pack ->
        WindowDialog(show = true, insideMargin = DpSize(24.dp, 24.dp), onDismissRequest = { if (!busy) pending = null }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("camera_watermark_import_confirm", "确认导入水印"), fontWeight = FontWeight.Bold, style = MiuixTheme.textStyles.title3)
                WatermarkDialogPreview(pack, identity, settings.suffixes, previewTime)
                GlassDialogButton({ choose() }, Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("camera_watermark_reselect", "重新选择")) }
                GlassDialogButton({ perform {
                    withContext(Dispatchers.IO) {
                        library.save(pack)
                        service?.let { CameraWatermarkRemote.publish(context, it) }
                    }
                    entries = withContext(Dispatchers.IO) { library.list() }
                    pending = null
                    changed()
                } }, Modifier.fillMaxWidth(), enabled = !busy, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("保存", "保存")) }
                GlassDialogButton({ pending = null }, Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("取消", "取消")) }
            }
        }
    }
    selected?.let { entry ->
        WindowDialog(show = deleting == null, insideMargin = DpSize(24.dp, 24.dp), onDismissRequest = { if (!busy) selected = null }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val pack by produceState<CameraWatermarkPackage?>(null, entry.id) {
                    value = withContext(Dispatchers.IO) { runCatching { CameraWatermarkZip.decode(entry.archive.readBytes()) }.getOrNull() }
                }
                pack?.let { WatermarkDialogPreview(it, identity, settings.suffixes, previewTime) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton({ exporting = entry; exporter.launch("${entry.kind}-${entry.id.take(12)}.zip") }, Modifier.weight(1f), enabled = !busy) { Text(tr("导出", "导出")) }
                    GlassDialogButton({ perform {
                        val uri = withContext(Dispatchers.IO) {
                            val directory = File(context.cacheDir, "camera_watermark_exports").apply { mkdirs() }
                            val file = File(directory, "${entry.id}.zip")
                            entry.archive.copyTo(file, overwrite = true)
                            FileProvider.getUriForFile(context, "${context.packageName}.devicepresets", file)
                        }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri(entry.name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, tr("分享", "分享")))
                    } }, Modifier.weight(1f), enabled = !busy) { Text(tr("分享", "分享")) }
                }
                GlassDialogButton({ deleting = entry }, Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("删除", "删除"), color = Color(0xFFD32F2F)) }
                GlassDialogButton({ selected = null }, Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("取消", "取消")) }
            }
        }
    }
    deleting?.let { entry ->
        WindowDialog(show = true, insideMargin = DpSize(24.dp, 24.dp), onDismissRequest = { if (!busy) deleting = null }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("camera_watermark_delete_confirm", "确定删除这个水印吗？"), fontWeight = FontWeight.Bold)
                Text(entry.name)
                GlassDialogButton({ perform {
                    withContext(Dispatchers.IO) {
                        library.delete(entry)
                        service?.let { CameraWatermarkRemote.publish(context, it) }
                    }
                    entries = withContext(Dispatchers.IO) { library.list() }
                    deleting = null
                    selected = null
                    changed()
                } }, Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("删除", "删除"), color = Color(0xFFD32F2F)) }
                GlassDialogButton({ deleting = null }, Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("取消", "取消")) }
            }
        }
    }
}

@Composable
private fun watermarkRenderedBitmap(pack: CameraWatermarkPackage?, identity: CameraWatermarkIdentity,
    suffixes: Int, maximum: Int, now: Long): State<android.graphics.Bitmap?> {
    val context = LocalContext.current
    return produceState<android.graphics.Bitmap?>(null, pack?.id, identity, suffixes, now) {
        value = withContext(Dispatchers.IO) {
            pack?.let { runCatching { renderCameraWatermarkPreview(context, it, identity, suffixes, maximum, now) }
                .onFailure { error -> ModuleLog.e("CameraWatermark", "Preview render failed: $error") }.getOrNull() }
        }
    }
}

@Composable
private fun WatermarkDialogPreview(pack: CameraWatermarkPackage, identity: CameraWatermarkIdentity, suffixes: Int, now: Long) {
    val bitmap by watermarkRenderedBitmap(pack, identity, suffixes, 1024, now)
    bitmap?.let {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
            Image(it.asImageBitmap(), contentDescription = pack.name,
                modifier = Modifier.width(minOf(maxWidth, 250.dp)).aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop)
        }
    }
}
