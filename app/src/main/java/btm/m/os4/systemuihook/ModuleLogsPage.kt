// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.ClipData
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class ModuleLogExporter(val busy: Boolean, val status: String?, val export: (share: Boolean) -> Unit)

@Composable
internal fun rememberModuleLogExporter(): ModuleLogExporter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<File?>(null) }
    var savedDialog by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val file = pending
        pending = null
        if (uri != null && file != null) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).use { output -> file.inputStream().use { it.copyTo(output) } }
                } }
                if (result.isSuccess) savedDialog = true
                else status = tr("safetyLogSaveFailed", "日志保存失败，请重试")
                busy = false
            }
        } else busy = false
    }
    WindowDialog(show = savedDialog, title = tr("safetyLogSaved", "日志 ZIP 已保存"),
        onDismissRequest = { savedDialog = false }) {
        GlassDialogButton({ savedDialog = false }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) {
            Text(tr("确认", "确认"))
        }
    }
    return ModuleLogExporter(busy, status) { share ->
        if (!busy) {
            busy = true
            status = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val repo = ModuleLogRepository(context)
                    val refreshed = runCatching { repo.refresh() }.getOrDefault(false)
                    val environment = collectModuleLogEnvironment(context)
                    repo.exportZip(environment) to refreshed
                } }
                result.onSuccess { (file, refreshed) ->
                    if (!refreshed) status = tr("safetyLogCachedExport", "未能读取新的 LSPosed 日志，ZIP 包含当前已保存的日志。")
                    val launched = runCatching {
                        if (share) {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.modulelogs", file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "application/zip"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri("HyperChanger logs", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, tr("safetyShareLogs", "分享日志")))
                            busy = false
                        } else {
                            pending = file
                            launcher.launch(file.name)
                        }
                    }
                    if (launched.isFailure) { pending = null; busy = false; status = tr("safetyLogExportFailed", "日志导出失败，请重试") }
                }.onFailure { busy = false; status = tr("safetyLogExportFailed", "日志导出失败，请重试") }
            }
        }
    }
}

@Composable
internal fun ModuleLogsPage(back: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { ModuleLogRepository(context) }
    var source by rememberSaveable { mutableIntStateOf(0) }
    var level by rememberSaveable { mutableIntStateOf(0) }
    var tag by rememberSaveable { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<ModuleLogEntry>()) }
    var busy by remember { mutableStateOf(true) }
    var clear by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val exporter = rememberModuleLogExporter()
    val levels = listOf("ALL", "VERBOSE", "DEBUG", "INFO", "WARNING", "ERROR", "FATAL")
    val priorities = listOf(Log.VERBOSE, Log.VERBOSE, Log.DEBUG, Log.INFO, Log.WARN, Log.ERROR, Log.ASSERT)
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) { runCatching { repo.refresh() } }
        status = when {
            result.isFailure -> tr("safetyLogReadFailed", "无法读取 LSPosed 日志，请检查超级用户授权。以下显示已保存的日志。")
            result.getOrDefault(false).not() -> tr("safetyLogSourceEmpty", "未找到可读取的 LSPosed 日志。")
            else -> null
        }
        revision++
    }
    LaunchedEffect(source, level, tag, revision) {
        busy = true
        val result = withContext(Dispatchers.IO) { runCatching { repo.read(source, priorities[level], tag == 1) } }
        entries = result.getOrDefault(emptyList())
        if (result.isFailure) status = tr("safetyLogReadCacheFailed", "无法读取已保存的日志，请重试")
        busy = false
    }
    AppPage(tr("safetyViewLogs", "查看日志"), back, actions = {
        GlassToolbarIconButton(MiuixIcons.Regular.Download, tr("safetySaveLogs", "保存日志")) { if (!exporter.busy) exporter.export(false) }
        GlassToolbarIconButton(Icons.Default.Share, tr("safetyShareLogs", "分享日志")) { if (!exporter.busy) exporter.export(true) }
    }, header = {
        SoftGlassHeaderTabs(listOf(tr("safetyModuleLogs", "模块日志"), tr("safetyLsposedLogs", "LSPosed 日志")), source) { source = it }
    }) { padding, scroll ->
        AppList(padding, scroll) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    OverlayDropdownPreference(tr("safetyLogLevel", "日志级别"), levels.map { tr("safetyLevel$it", it) }, level,
                        onSelectedIndexChange = { level = it })
                    OverlayDropdownPreference(tr("safetyLogTag", "标签"), listOf(tr("safetyLevelALL", "ALL"), tr("safetyTagCrash", "CRASH")), tag,
                        onSelectedIndexChange = { tag = it })
                    ArrowPreference(tr("safetyClearLogs", "清空日志"), enabled = !busy, onClick = { clear = true })
                }
            }
            status?.let { value -> item { SafetyNote(value) } }
            exporter.status?.let { value -> item { SafetyNote(value) } }
            if (busy) item { SafetyNote(tr("safetyLogLoading", "正在读取日志…")) }
            else if (entries.isEmpty()) item { SafetyNote(tr("safetyLogEmpty", "暂无匹配日志")) }
            items(entries, key = { it.id }) { entry -> ModuleLogRow(entry) }
        }
    }
    if (clear) ModuleLogClearDialog({ clear = false }) {
        clear = false
        busy = true
        val selectedSource = source
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repo.clear(selectedSource) } }
            status = if (result.isSuccess) tr("safetyLogsCleared", "日志已清空") else tr("safetyLogsClearFailed", "无法清空日志，请重试")
            revision++
            busy = false
        }
    }
}

@Composable
private fun ModuleLogRow(entry: ModuleLogEntry) {
    val color = when (entry.priority) {
        Log.ASSERT -> Color(0xFFCF3787)
        Log.ERROR -> Color(0xFFD95151)
        Log.WARN -> Color(0xFFB67B16)
        Log.INFO -> Color(0xFF339F73)
        Log.DEBUG -> Color(0xFF4A90D9)
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val label = when (entry.priority) { Log.ASSERT -> "FATAL"; Log.ERROR -> "ERROR"; Log.WARN -> "WARNING"; Log.INFO -> "INFO"; Log.DEBUG -> "DEBUG"; else -> "VERBOSE" }
    Card(Modifier.fillMaxWidth(), cornerRadius = 18.dp) {
        SelectionContainer {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("safetyLogEntryHeader", "%s · %s · %s").format(
                    SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.time)), tr("safetyLevel$label", label), entry.tag),
                    color = color, style = MiuixTheme.textStyles.body2)
                Text(entry.text, style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

@Composable
internal fun ModuleLogClearDialog(dismiss: () -> Unit, confirm: () -> Unit) {
    WindowDialog(show = true, title = tr("safetyClearLogs", "清空日志"),
        summary = tr("safetyClearConfirm", "确认清空已保存的日志？此操作无法撤销，框架原始日志不受影响。"),
        titleTextAlign = TextAlign.Left, summaryTextAlign = TextAlign.Left, onDismissRequest = dismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassDialogButton(dismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
            GlassDialogButton(confirm, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("确认", "确认")) }
        }
    }
}
