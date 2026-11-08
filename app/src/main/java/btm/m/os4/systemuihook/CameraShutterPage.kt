// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.ClipData
import android.content.Intent
import android.media.MediaPlayer
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import btm.m.os4.systemuihook.SeamlessCard as Card
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.util.Locale

private data class PendingShutterSound(val name: String, val bytes: ByteArray, val attribution: String = "")

@Composable
internal fun CameraShutterPage(settings: CameraShutterSettings,
    update: ((CameraShutterSettings) -> CameraShutterSettings) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val library = remember(context) { CameraShutterLibrary(context) }
    val service by HookApplication.service.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<CameraShutterSound>()) }
    var presets by remember { mutableStateOf(emptyList<CameraShutterPreset>()) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingShutterSound?>(null) }
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CameraShutterSound?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var downloadAll by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<CameraShutterSound?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    fun stop() { player?.release(); player = null; previewFile?.delete(); previewFile = null }
    DisposableEffect(Unit) { onDispose { player?.release(); previewFile?.delete() } }
    LaunchedEffect(Unit) { entries = withContext(Dispatchers.IO) { library.list() } }
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                ModuleLog.w("CameraShutter", "Sound library operation failed", error)
                Toast.makeText(context, tr("camera_shutter_error", "操作失败，请检查音频文件或网络后重试"), Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    suspend fun preview(file: File, temporary: Boolean = false) {
        stop()
        if (temporary) previewFile = file
        val next = MediaPlayer()
        try {
            withContext(Dispatchers.IO) { next.setDataSource(file.path); next.prepare() }
            next.setOnCompletionListener { if (player === it) stop() }
            player = next; next.start()
        } catch (error: Exception) {
            next.release()
            if (temporary) { file.delete(); previewFile = null }
            throw error
        }
    }
    fun setPending(value: PendingShutterSound) { stop(); pending = value; name = value.name }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) perform {
            val value = withContext(Dispatchers.IO) {
                val label = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }.orEmpty().substringBeforeLast('.').trim().take(80).ifBlank { tr("camera_shutter_imported", "导入的快门声") }
                val file = File.createTempFile("shutter-import-", ".audio", context.cacheDir)
                try {
                    file.writeBytes(requireNotNull(context.contentResolver.openInputStream(uri)).use(CameraShutterAudio::read))
                    PendingShutterSound(label, CameraShutterAudio.normalize(file))
                } finally { file.delete() }
            }
            setPending(value)
        }
    }
    fun choose() = importer.launch(arrayOf("audio/*", "application/ogg"))
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val entry = exporting; exporting = null
        if (uri != null && entry != null) perform {
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { output -> entry.file.inputStream().use { it.copyTo(output) } }
            }
        }
    }
    fun loadPresets() = perform { presets = withContext(Dispatchers.IO) { CameraShutterDownloads.catalog() } }
    suspend fun previewBytes(bytes: ByteArray) {
        val file = withContext(Dispatchers.IO) { File.createTempFile("shutter-preview-", ".wav", context.cacheDir).also { it.writeBytes(bytes) } }
        preview(file, temporary = true)
    }
    LaunchedEffect(tab) { stop(); if (tab == 1 && presets.isEmpty()) loadPresets() }
    AppPage(tr("camera_shutter_sounds", "自定义快门声"), back, restartScopes = setOf(ScopeApplication.CAMERA),
        header = { SoftGlassHeaderTabs(listOf(tr("camera_shutter_local", "已导入"), tr("camera_shutter_online", "在线预设")), tab, { if (!busy) tab = it }) },
    ) { padding, scroll ->
        LazyColumn(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 4.dp, 16.dp, padding.calculateBottomPadding() + 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SharedViewArrowPreference(title = tr("camera_shutter_import", "导入快门声"),
                        summary = tr("camera_shutter_import_hint", "支持 WAV、MP3、OGG、M4A、FLAC，最长 10 秒、最大 4 MB"), onClick = { if (!busy) choose() })
                    if (tab == 1) SharedViewArrowPreference(title = tr("camera_shutter_refresh", "刷新在线预设"), onClick = { if (!busy) loadPresets() })
                    if (presets.isNotEmpty()) SharedViewArrowPreference(title = tr("camera_shutter_download_all", "一键下载所有快门声"),
                        onClick = { if (!busy) downloadAll = true })
                }
            }
            if (busy) item { Text(tr("camera_shutter_loading", "正在处理…"), Modifier.padding(16.dp)) }
            if (tab == 0) {
                if (entries.isEmpty()) item { Text(tr("camera_shutter_empty", "还没有导入快门声"), Modifier.padding(16.dp)) }
                items(entries, key = { it.id }) { entry ->
                    Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                        BasicComponent(title = entry.name, summary =
                            String.format(Locale.ROOT, "%.2f s", entry.durationMs / 1000.0),
                            onClick = { if (!busy) { stop(); selected = entry } }, endActions = {
                                IconButton(enabled = !busy, onClick = { perform { preview(entry.file) } }) {
                                    Icon(MiuixIcons.Regular.Play, tr("camera_shutter_preview", "试听"))
                                }
                            })
                    }
                }
            } else {
                items(presets, key = { it.hash }) { preset ->
                    Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                        BasicComponent(title = preset.name, summary = if (entries.any { it.id == preset.hash }) tr("camera_shutter_downloaded", "已下载") else tr("camera_shutter_download", "下载"),
                            onClick = { perform {
                                val bytes = withContext(Dispatchers.IO) { CameraShutterDownloads.download(preset) }
                                setPending(PendingShutterSound(preset.name, bytes, preset.attribution))
                            } }, endActions = {
                                IconButton(enabled = !busy, onClick = { perform {
                                    val bytes = withContext(Dispatchers.IO) { entries.firstOrNull { it.id == preset.hash }?.file?.readBytes() ?: CameraShutterDownloads.download(preset) }
                                    previewBytes(bytes)
                                } }) { Icon(MiuixIcons.Regular.Play, tr("camera_shutter_preview", "试听")) }
                            })
                    }
                }
            }
        }
    }
    if (downloadAll) WindowDialog(show = true, insideMargin = DpSize(24.dp, 24.dp), onDismissRequest = { downloadAll = false }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(tr("camera_shutter_download_all_confirm", "即将从仓库下载所有快门声"), fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton({ downloadAll = false }, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton({
                    downloadAll = false
                    perform {
                        stop()
                        try {
                            withContext(Dispatchers.IO) {
                                val existing = library.list().map { it.id }.toMutableSet()
                                presets.forEach { preset -> if (preset.hash !in existing) {
                                    library.save(preset.name, CameraShutterDownloads.download(preset), preset.attribution)
                                    existing.add(preset.hash)
                                } }
                            }
                        } finally {
                            entries = withContext(Dispatchers.IO) { library.list() }
                            withContext(Dispatchers.IO) { service?.let { CameraShutterRemote.publish(context, it) } }
                        }
                    }
                }, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("确定", "确定")) }
            }
        }
    }
    pending?.let { sound ->
        WindowDialog(show = true, insideMargin = DpSize(24.dp, 24.dp), onDismissRequest = { if (!busy) { stop(); pending = null } }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicComponent(title = tr("camera_shutter_confirm", "确认导入快门声"), summary = sound.name, endActions = {
                    IconButton(enabled = !busy, onClick = { perform { previewBytes(sound.bytes) } }) {
                        Icon(MiuixIcons.Regular.Play, tr("camera_shutter_preview", "试听"))
                    }
                })
                TextField(value = name, onValueChange = { name = it.take(80) }, label = tr("camera_shutter_name", "名称"))
                if (sound.attribution.isNotBlank()) Text(sound.attribution)
                GlassDialogButton({ choose() }, Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("camera_watermark_reselect", "重新选择")) }
                GlassDialogButton({ perform {
                    stop()
                    val saved = withContext(Dispatchers.IO) {
                        library.save(name.trim(), sound.bytes, sound.attribution).also { service?.let { CameraShutterRemote.publish(context, it) } }
                    }
                    update { it }
                    entries = withContext(Dispatchers.IO) { library.list() }; pending = null; tab = 0
                } }, Modifier.fillMaxWidth(), enabled = !busy && name.isNotBlank(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("camera_shutter_save_use", "保存并使用")) }
                GlassDialogButton({ stop(); pending = null }, Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("取消", "取消")) }
            }
        }
    }
    selected?.let { entry ->
        WindowDialog(show = !deleting, insideMargin = DpSize(24.dp, 24.dp), onDismissRequest = { if (!busy) { stop(); selected = null } }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicComponent(title = entry.name, summary = String.format(Locale.ROOT, "%.2f s", entry.durationMs / 1000.0), endActions = {
                    IconButton(enabled = !busy, onClick = { perform { preview(entry.file) } }) {
                        Icon(MiuixIcons.Regular.Play, tr("camera_shutter_preview", "试听"))
                    }
                })
                if (entry.attribution.isNotBlank()) Text(entry.attribution)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton({ exporting = entry; exporter.launch("shutter-${entry.id.take(12)}.wav") }, Modifier.weight(1f), enabled = !busy) { Text(tr("导出", "导出")) }
                    GlassDialogButton({ perform {
                        val uri = withContext(Dispatchers.IO) {
                            val directory = File(context.cacheDir, "camera_shutter_exports").apply { mkdirs() }
                            val file = File(directory, "${entry.id}.wav"); entry.file.copyTo(file, overwrite = true)
                            FileProvider.getUriForFile(context, "${context.packageName}.devicepresets", file)
                        }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "audio/wav"; putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri(entry.name, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            if (entry.attribution.isNotBlank()) putExtra(Intent.EXTRA_TEXT, entry.attribution)
                        }, tr("分享", "分享")))
                    } }, Modifier.weight(1f), enabled = !busy) { Text(tr("分享", "分享")) }
                }
                GlassDialogButton({ stop(); deleting = true }, Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("删除", "删除"), color = Color(0xFFD32F2F)) }
                GlassDialogButton({ stop(); selected = null }, Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("取消", "取消")) }
            }
        }
        if (deleting) WindowDialog(show = true, insideMargin = DpSize(24.dp, 24.dp), onDismissRequest = { if (!busy) deleting = false }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr("camera_shutter_delete_confirm", "确定删除这个快门声吗？"), fontWeight = FontWeight.Bold)
                Text(entry.name)
                GlassDialogButton({ perform {
                    withContext(Dispatchers.IO) { library.delete(entry.id); service?.let { CameraShutterRemote.publish(context, it) } }
                    entries = withContext(Dispatchers.IO) { library.list() }; selected = null; deleting = false
                } }, Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("删除", "删除"), color = Color(0xFFD32F2F)) }
                GlassDialogButton({ deleting = false }, Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("取消", "取消")) }
            }
        }
    }
}
