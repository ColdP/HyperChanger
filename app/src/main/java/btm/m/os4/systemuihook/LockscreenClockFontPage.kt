// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import btm.m.os4.systemuihook.SeamlessCard as Card
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class PendingClockFontPreview(
    val uri: Uri,
    val fileName: String,
    val info: FontVariableAxesInfo,
    val typeface: Typeface,
    val sizeBytes: Long,
)

@Composable
internal fun LockscreenClockFontLibraryPage(back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service by HookApplication.service.collectAsState()
    val library = remember(context) { LockscreenClockFontLibrary(context) }
    val store = remember(context) { LockscreenClockCustomSettingsStore(context) }
    var entries by remember { mutableStateOf<List<LockscreenClockFontEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    var showImportDialog by remember { mutableStateOf(false) }
    var pendingFont by remember { mutableStateOf<PendingClockFontPreview?>(null) }
    var pendingDisplayName by remember { mutableStateOf("") }

    var selectedEntry by remember { mutableStateOf<LockscreenClockFontEntry?>(null) }
    var renamingEntry by remember { mutableStateOf<LockscreenClockFontEntry?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var deletingEntry by remember { mutableStateOf<LockscreenClockFontEntry?>(null) }

    val changed = LocalNotificationSettingsChanged.current

    fun refreshList() {
        scope.launch {
            entries = withContext(Dispatchers.IO) { library.list() }
        }
    }

    LaunchedEffect(library) {
        entries = withContext(Dispatchers.IO) { library.list() }
    }
    LaunchedEffect(service) {
        service?.let { svc ->
            withContext(Dispatchers.IO) { store.syncRemote(svc) }
        }
    }

    fun perform(errorFallback: String, operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, errorFallback, Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }

    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        perform(tr("lockscreen_clock_font_invalid", "无法解析所选字体文件，请选择有效的 TTF 或 OTF 字体")) {
            val loaded = withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use {
                    ClockFontParser.readBounded(it)
                } ?: error("unable to open font")
                val info = ClockFontParser.inspect(bytes)
                val temp = File(context.cacheDir, "clock_font_preview_tmp.ttf")
                temp.writeBytes(bytes)
                val typeface = ClockFontParser.buildTypeface(temp) ?: error("invalid typeface")
                val displayFileName = appearanceAssetDisplayName(context, uri).ifBlank { "CustomFont.ttf" }
                PendingClockFontPreview(
                    uri = uri,
                    fileName = displayFileName,
                    info = info,
                    typeface = typeface,
                    sizeBytes = bytes.size.toLong(),
                )
            }
            pendingFont = loaded
            pendingDisplayName = loaded.fileName.substringBeforeLast('.').trim().take(80)
                .ifBlank { loaded.fileName.take(80) }
        }
    }

    AppPage(
        title = tr("lockscreen_clock_font_library", "时钟字体库"),
        onBack = back,
        restartScopes = setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD),
        floatingToolbarPosition = ToolbarPosition.BottomCenter,
        floatingToolbar = {
            Box(Modifier.fillMaxWidth().padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                GlassActionButton(
                    enabled = !busy,
                    onClick = {
                        pendingFont = null
                        pendingDisplayName = ""
                        showImportDialog = true
                    },
                    size = 60.dp,
                    surfaceColor = MiuixTheme.colorScheme.primary,
                ) {
                    Icon(
                        MiuixIcons.Regular.Add,
                        tr("lockscreen_clock_font_import", "导入时钟字体"),
                        Modifier.size(24.dp),
                        tint = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            }
        },
        content = { padding, scroll ->
            AppList(padding, scroll, 112) {
                item(key = "usage_hint") {
                    Card(
                        Modifier.fillMaxWidth(),
                        cornerRadius = 22.5.dp,
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    ) {
                        Text(
                            tr(
                                "lockscreen_clock_font_editor_hint",
                                "导入的字体会自动追加到“息屏与锁屏编辑 → 时钟编辑 → 字体”选择列表中，与系统内置字体共存，可按锁屏样式自由切换。",
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (entries.isEmpty()) {
                    item(key = "empty") {
                        Card(
                            Modifier.fillMaxWidth(),
                            cornerRadius = 22.5.dp,
                            insideMargin = PaddingValues(horizontal = 20.dp, vertical = 28.dp),
                        ) {
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    tr("lockscreen_clock_font_empty", "字体库为空"),
                                    style = MiuixTheme.textStyles.headline1,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                )
                                Text(
                                    tr(
                                        "lockscreen_clock_font_empty_hint",
                                        "点击右下角 + 按钮导入 TTF / OTF 字体文件，导入后即可在锁屏时钟编辑的字体列表中选用。",
                                    ),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                } else {
                    items(entries, key = { it.id }) { entry ->
                        ClockFontLibraryCard(
                            entry = entry,
                            onClick = { if (!busy) selectedEntry = entry },
                        )
                    }
                }
            }
        },
    )

    // Import dialog referencing DeviceAppearanceAssetPreference's Import LOGO dialog.
    if (showImportDialog) {
        WindowDialog(
            show = true,
            insideMargin = DpSize(24.dp, 24.dp),
            onDismissRequest = { if (!busy) showImportDialog = false },
        ) {
            val preview = pendingFont
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    tr("lockscreen_clock_font_import", "导入时钟字体"),
                    style = MiuixTheme.textStyles.title3,
                )
                ClockFontDialogPreviewBox(
                    typeface = preview?.typeface,
                    emptyText = tr("未导入", "未导入"),
                    showDigitsRow = false,
                )
                if (preview == null) {
                    Text(
                        tr("appearance_import_hint", "点击下方的按钮进行导入，可以导入：%s").format("TTF / OTF"),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    Text(
                        buildFontMetaLine(preview.fileName, preview.info.format, preview.sizeBytes, preview.info.isVariable, preview.info.hasHeightAxis),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextField(
                        value = pendingDisplayName,
                        onValueChange = { pendingDisplayName = it.take(80) },
                        label = tr("lockscreen_clock_font_name_label", "字体名称"),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassDialogButton(
                        onClick = {
                            fontPicker.launch(
                                arrayOf(
                                    "font/ttf",
                                    "font/otf",
                                    "application/x-font-ttf",
                                    "application/x-font-opentype",
                                    "application/octet-stream",
                                    "*/*",
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text(tr("选择文件", "选择文件")) }
                    GlassDialogButton(
                        onClick = {
                            pendingFont = null
                            pendingDisplayName = ""
                        },
                        modifier = Modifier.weight(1f),
                        enabled = preview != null && !busy,
                    ) { Text(tr("清除", "清除")) }
                }
                GlassDialogButton(
                    onClick = {
                        val chosen = preview ?: return@GlassDialogButton
                        perform(tr("导入失败", "导入失败")) {
                            withContext(Dispatchers.IO) {
                                library.importFromUri(chosen.uri, pendingDisplayName)
                                store.update(service) {
                                    it.copy(fontVersion = System.currentTimeMillis())
                                }
                                service?.let { LockscreenClockFontRemote.publish(context, it) }
                            }
                            entries = withContext(Dispatchers.IO) { library.list() }
                            pendingFont = null
                            pendingDisplayName = ""
                            showImportDialog = false
                            changed()
                            Toast.makeText(context, tr("已导入", "已导入"), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = preview != null && !busy,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("保存", "保存")) }
                GlassDialogButton(
                    onClick = { showImportDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) { Text(tr("取消", "取消")) }
            }
        }
    }

    // Card detail & action dialog.
    selectedEntry?.let { entry ->
        val typeface by produceState<Typeface?>(null, entry.id, entry.file.lastModified()) {
            value = withContext(Dispatchers.IO) { ClockFontParser.buildTypeface(entry.file) }
        }
        WindowDialog(
            show = deletingEntry == null && renamingEntry == null,
            insideMargin = DpSize(24.dp, 24.dp),
            onDismissRequest = { if (!busy) selectedEntry = null },
        ) {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    entry.name,
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildFontMetaLine(entry.fileName, entry.format, entry.sizeBytes, entry.isVariable, entry.hasHeightAxis),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ClockFontDialogPreviewBox(
                    typeface = typeface,
                    emptyText = tr("lockscreen_clock_font_preview_unavailable", "预览加载失败"),
                    showDigitsRow = true,
                )
                Text(
                    tr("lockscreen_clock_font_dialog_usage", "此字体已同步到锁屏时钟编辑的“字体”列表中，可直接在息屏与锁屏编辑中切换使用。"),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassDialogButton(
                        onClick = {
                            renameInput = entry.name
                            renamingEntry = entry
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text(tr("lockscreen_clock_font_rename", "重命名")) }
                    GlassDialogButton(
                        onClick = { deletingEntry = entry },
                        modifier = Modifier.weight(1f),
                        enabled = !busy,
                    ) { Text(tr("删除", "删除"), color = Color(0xFFD32F2F)) }
                }
                GlassDialogButton(
                    onClick = { selectedEntry = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) { Text(tr("取消", "取消")) }
            }
        }
    }

    // Rename dialog.
    renamingEntry?.let { entry ->
        WindowDialog(
            show = true,
            insideMargin = DpSize(24.dp, 24.dp),
            onDismissRequest = { if (!busy) renamingEntry = null },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    tr("lockscreen_clock_font_rename", "重命名"),
                    style = MiuixTheme.textStyles.title3,
                )
                TextField(
                    value = renameInput,
                    onValueChange = { renameInput = it.take(80) },
                    label = tr("lockscreen_clock_font_name_label", "字体名称"),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                GlassDialogButton(
                    onClick = {
                        val trimmed = renameInput.trim()
                        if (trimmed.isEmpty()) return@GlassDialogButton
                        perform(tr("操作失败", "操作失败")) {
                            val updated = withContext(Dispatchers.IO) {
                                library.rename(entry.id, trimmed).also {
                                    store.update(service) { s -> s.copy(fontVersion = System.currentTimeMillis()) }
                                    service?.let { svc -> LockscreenClockFontRemote.publish(context, svc) }
                                }
                            }
                            entries = withContext(Dispatchers.IO) { library.list() }
                            selectedEntry = updated
                            renamingEntry = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = renameInput.isNotBlank() && !busy,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("保存", "保存")) }
                GlassDialogButton(
                    onClick = { renamingEntry = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) { Text(tr("取消", "取消")) }
            }
        }
    }

    // Delete confirmation dialog.
    deletingEntry?.let { entry ->
        WindowDialog(
            show = true,
            insideMargin = DpSize(24.dp, 24.dp),
            onDismissRequest = { if (!busy) deletingEntry = null },
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    tr("lockscreen_clock_font_delete_confirm", "确定删除这个字体吗？"),
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold,
                )
                Text(entry.name, style = MiuixTheme.textStyles.body2)
                GlassDialogButton(
                    onClick = {
                        perform(tr("操作失败", "操作失败")) {
                            withContext(Dispatchers.IO) {
                                library.delete(entry.id)
                                store.update(service) {
                                    it.copy(
                                        activeFontId = if (it.activeFontId == entry.id) "" else it.activeFontId,
                                        fontVersion = System.currentTimeMillis(),
                                    )
                                }
                                service?.let { LockscreenClockFontRemote.publish(context, it) }
                            }
                            refreshList()
                            deletingEntry = null
                            selectedEntry = null
                            changed()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) { Text(tr("删除", "删除"), color = Color(0xFFD32F2F)) }
                GlassDialogButton(
                    onClick = { deletingEntry = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                ) { Text(tr("取消", "取消")) }
            }
        }
    }
}

@Composable
private fun ClockFontLibraryCard(
    entry: LockscreenClockFontEntry,
    onClick: () -> Unit,
) {
    val typeface by produceState<Typeface?>(null, entry.id, entry.file.lastModified()) {
        value = withContext(Dispatchers.IO) { ClockFontParser.buildTypeface(entry.file) }
    }
    val previewTime = remember { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()) }
    Card(
        Modifier.fillMaxWidth(),
        cornerRadius = 22.5.dp,
        insideMargin = PaddingValues(16.dp),
        onClick = onClick,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = previewTime,
                    fontFamily = typeface?.let(::FontFamily) ?: FontFamily.Default,
                    fontSize = 52.sp,
                    lineHeight = 58.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = entry.name,
                    style = MiuixTheme.textStyles.headline1,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildFontBadgeText(entry.format, entry.sizeBytes, entry.isVariable, entry.hasHeightAxis),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ClockFontDialogPreviewBox(
    typeface: Typeface?,
    emptyText: String,
    showDigitsRow: Boolean,
) {
    if (typeface == null) {
        Text(
            emptyText,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        return
    }
    val family = remember(typeface) { FontFamily(typeface) }
    val dateLabel = remember {
        SimpleDateFormat("M月d日, EEEE", Locale.getDefault()).format(Date())
    }
    val timeLabel = remember {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 16.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (showDigitsRow) {
                Text(
                    text = dateLabel,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = timeLabel,
                fontFamily = family,
                fontSize = 56.sp,
                lineHeight = 62.sp,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (showDigitsRow) {
                Text(
                    text = "0123456789:",
                    fontFamily = family,
                    fontSize = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun formatFontSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun buildFontBadgeText(
    format: String,
    sizeBytes: Long,
    isVariable: Boolean,
    hasHeightAxis: Boolean,
): String = buildList {
    add(format)
    add(formatFontSize(sizeBytes))
    if (hasHeightAxis) add(tr("lockscreen_clock_font_hght_axis", "支持高度轴"))
    else if (isVariable) add(tr("lockscreen_clock_font_variable", "可变字体"))
}.joinToString(" · ")

private fun buildFontMetaLine(
    fileName: String,
    format: String,
    sizeBytes: Long,
    isVariable: Boolean,
    hasHeightAxis: Boolean,
): String {
    val badge = buildFontBadgeText(format, sizeBytes, isVariable, hasHeightAxis)
    return if (fileName.isBlank()) badge else "$fileName · $badge"
}
