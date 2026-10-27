// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

@Composable
internal fun DeviceProfilePresetsPage(
    profile: DeviceProfileSettings,
    update: ((DeviceProfileSettings) -> DeviceProfileSettings) -> Unit,
    openCloud: () -> Unit,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { DeviceInfoPresetStore(context) }
    var presets by remember { mutableStateOf(store.load()) }
    var showSave by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<DeviceInfoPreset?>(null) }
    var deleting by remember { mutableStateOf<DeviceInfoPreset?>(null) }
    var replacing by remember { mutableStateOf<DeviceInfoPreset?>(null) }
    var exporting by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The library page stays composed beneath the cloud page during nested navigation.
    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> presets = store.load() }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun perform(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                devicePresetToast(context, tr("devicePresetOperationFailed", "操作失败，请重试"))
            } finally {
                busy = false
            }
        }
    }

    fun save(preset: DeviceInfoPreset) = perform {
        withContext(Dispatchers.IO) { store.save(preset) }
        presets = store.load()
        replacing = null
        showSave = false
        devicePresetToast(context, tr("devicePresetSaved", "预设已保存"))
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = exporting
        exporting = null
        if (uri != null && payload != null) perform {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri) ?: error("Cannot open output")
                output.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
            }
            devicePresetToast(context, tr("devicePresetExported", "预设已导出"))
        }
    }

    AppPage(tr("预设库", "预设库"), back) { padding, scroll ->
        AppList(padding, scroll) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    ArrowPreference(title = tr("云端预设库", "云端预设库"), onClick = openCloud)
                    ArrowPreference(title = tr("保存当前预设", "保存当前预设"), enabled = !busy, onClick = { showSave = true })
                }
            }
            item {
                Group(tr("用户预设", "用户预设")) {
                    if (presets.isEmpty()) {
                        Text(tr("暂无保存的用户预设", "暂无保存的用户预设"), Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2)
                    }
                    presets.forEach { preset ->
                        ArrowPreference(title = preset.name, summary = deviceInfoPresetSubtitle(preset.id, preset.contributor), enabled = !busy, onClick = { selected = preset })
                    }
                }
            }
        }
    }

    if (showSave) {
        SaveDeviceInfoPresetDialog(
            show = replacing == null,
            busy = busy,
            onDismiss = { if (!busy) showSave = false },
            onSave = { id, name, description ->
                val preset = DeviceInfoPresetCodec.capture(id, name, description, profile)
                if (store.load().any { it.id == id }) replacing = preset else save(preset)
            },
        )
    }
    replacing?.let { preset ->
        DevicePresetConfirmationDialog(
            title = tr("devicePresetOverwriteTitle", "覆盖预设"),
            message = tr("devicePresetSaveOverwrite", "本地已存在相同 ID 的预设。保存后将覆盖本地预设，是否继续？"),
            confirm = tr("保存", "保存"), busy = busy,
            onDismiss = { replacing = null }, onConfirm = { save(preset) },
        )
    }
    selected?.let { preset ->
        WindowDialog(show = true, onDismissRequest = { if (!busy) selected = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(preset.name, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                Text(deviceInfoPresetSubtitle(preset.id, preset.contributor), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton(onClick = {
                        perform {
                            val uri = withContext(Dispatchers.IO) {
                                val directory = File(context.cacheDir, "device_preset_exports").apply { check(isDirectory || mkdirs()) }
                                // Unique directories keep an earlier share's content stable until its receiver reads it.
                                val folder = File(directory, java.util.UUID.randomUUID().toString()).apply { check(mkdirs()) }
                                val file = File(folder, "${preset.id}.json")
                                file.writeText(DeviceInfoPresetCodec.encode(preset), Charsets.UTF_8)
                                FileProvider.getUriForFile(context, "${context.packageName}.devicepresets", file)
                            }
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = ClipData.newRawUri(preset.name, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, tr("分享", "分享")))
                            selected = null
                        }
                    }, modifier = Modifier.weight(1f), enabled = !busy) { Text(tr("分享", "分享")) }
                    GlassDialogButton(onClick = {
                        exporting = DeviceInfoPresetCodec.encode(preset)
                        exporter.launch("${preset.id}.json")
                        selected = null
                    }, modifier = Modifier.weight(1f), enabled = !busy) { Text(tr("保存", "保存")) }
                }
                GlassDialogButton(onClick = {
                    update { preset.applyTo(it) }
                    selected = null
                    devicePresetToast(context, tr("devicePresetApplied", "预设已应用"))
                }, modifier = Modifier.fillMaxWidth(), enabled = !busy, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("应用", "应用")) }
                preset.contributorUrl?.let { url ->
                    GlassDialogButton(
                        onClick = { openDevicePresetLink(context, url) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                    ) { Text(tr("devicePresetContributorHome", "进入上传者主页")) }
                }
                GlassDialogButton(onClick = { selected = null; deleting = preset }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("删除", "删除")) }
                GlassDialogButton(onClick = { selected = null }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("取消", "取消")) }
            }
        }
    }
    deleting?.let { preset ->
        DevicePresetConfirmationDialog(
            title = tr("确认删除预设", "确认删除预设"),
            message = String.format(tr("确认删除预设内容", "确定要删除预设“%s”吗？删除后无法恢复。"), preset.name),
            confirm = tr("删除", "删除"), busy = busy,
            onDismiss = { deleting = null },
            onConfirm = {
                perform {
                    withContext(Dispatchers.IO) { store.delete(preset.id) }
                    presets = store.load()
                    deleting = null
                }
            },
        )
    }
}

@Composable
private fun SaveDeviceInfoPresetDialog(
    show: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var id by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    val validId = DeviceInfoPresetCodec.validId(id.trim())
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("保存当前预设", "保存当前预设"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            TextField(value = id, onValueChange = { id = it.take(240) }, label = tr("预设ID", "预设ID"), singleLine = true, cornerRadius = 999.dp, modifier = Modifier.fillMaxWidth())
            if (id.isNotBlank() && !validId) {
                Text(tr("devicePresetIdHint", "预设 ID 仅支持小写字母、数字及单个连字符分隔"), style = MiuixTheme.textStyles.body2)
            }
            TextField(value = name, onValueChange = { name = it.take(256) }, label = tr("预设名", "预设名"), singleLine = true, cornerRadius = 999.dp, modifier = Modifier.fillMaxWidth())
            TextField(value = description, onValueChange = { description = it.take(2048) }, label = tr("描述", "描述"), cornerRadius = 999.dp, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f), enabled = !busy) { Text(tr("取消", "取消")) }
                GlassDialogButton({ onSave(id.trim(), name.trim(), description.trim()) }, Modifier.weight(1f), enabled = !busy && validId && name.isNotBlank(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("保存", "保存")) }
            }
        }
    }
}

@Composable
internal fun CloudDeviceProfilePresetsPage(
    update: ((DeviceProfileSettings) -> DeviceProfileSettings) -> Unit,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { DeviceInfoPresetStore(context) }
    val scope = rememberCoroutineScope()
    var presets by remember { mutableStateOf<List<CloudDeviceInfoPreset>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var request by remember { mutableIntStateOf(0) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var replacing by remember { mutableStateOf<CloudDevicePresetAction?>(null) }
    var selected by remember { mutableStateOf<CloudDeviceInfoPreset?>(null) }
    var showUpload by remember { mutableStateOf(false) }

    LaunchedEffect(request) {
        loading = true
        failed = false
        try {
            presets = withContext(Dispatchers.IO) { CloudDeviceInfoPresets.list() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    fun download(action: CloudDevicePresetAction) {
        if (downloading != null) return
        val entry = action.preset
        downloading = entry.id
        scope.launch {
            try {
                val preset = withContext(Dispatchers.IO) {
                    CloudDeviceInfoPresets.download(entry).also { store.save(it) }
                }
                if (action.apply) update { preset.applyTo(it) }
                replacing = null
                selected = null
                devicePresetToast(context, if (action.apply) tr("devicePresetApplied", "预设已应用") else tr("devicePresetDownloaded", "已下载到用户预设"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                devicePresetToast(context, tr("devicePresetDownloadFailed", "预设下载失败，请重试"))
            } finally {
                downloading = null
            }
        }
    }

    fun requestDownload(preset: CloudDeviceInfoPreset, apply: Boolean) {
        if (downloading != null) return
        val action = CloudDevicePresetAction(preset, apply)
        if (store.load().any { it.id == preset.id }) replacing = action else download(action)
    }

    AppPage(tr("云端预设库", "云端预设库"), back) { padding, scroll ->
        AppList(padding, scroll) {
            if (loading || failed || presets.isEmpty()) item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(when {
                            loading -> tr("devicePresetLoading", "正在加载预设…")
                            failed -> tr("devicePresetCloudFailed", "云端预设加载失败，请检查网络后重试")
                            else -> tr("devicePresetCloudEmpty", "暂无云端预设")
                        }, style = MiuixTheme.textStyles.body2)
                        if (!loading) GlassDialogButton({ request++ }) { Text(tr("重试", "重试")) }
                    }
                }
            }
            if (!loading && !failed) items(presets, key = { it.id }) { preset ->
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    BasicComponent(
                        title = preset.name,
                        summary = deviceInfoPresetSubtitle(preset.id, preset.contributor),
                        onClick = { if (downloading == null) selected = preset },
                        endActions = {
                            IconButton(enabled = downloading == null, onClick = {
                                requestDownload(preset, apply = false)
                            }) {
                                Icon(
                                    MiuixIcons.Regular.Download,
                                    if (downloading == preset.id) tr("devicePresetDownloading", "正在下载…") else tr("下载", "下载"),
                                )
                            }
                        },
                    )
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.5.dp,
                    insideMargin = PaddingValues(16.dp),
                    onClick = { showUpload = true },
                ) {
                    Text(tr("devicePresetUpload", "为云端预设库上传预设"), color = Color(0xFF0088FF), style = MiuixTheme.textStyles.body1)
                }
            }
        }
    }
    if (replacing == null) selected?.let { preset ->
        WindowDialog(show = true, onDismissRequest = { if (downloading == null) selected = null }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(preset.name, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                Text(deviceInfoPresetSubtitle(preset.id, preset.contributor), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton({ requestDownload(preset, apply = false) }, Modifier.weight(1f), enabled = downloading == null) { Text(tr("下载", "下载")) }
                    GlassDialogButton({ requestDownload(preset, apply = true) }, Modifier.weight(1f), enabled = downloading == null, colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("应用", "应用")) }
                }
                GlassDialogButton(
                    onClick = { preset.contributorUrl?.let { openDevicePresetLink(context, it) } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = downloading == null && preset.contributorUrl != null,
                ) { Text(tr("devicePresetContributorHome", "进入上传者主页")) }
                GlassDialogButton({ selected = null }, Modifier.fillMaxWidth(), enabled = downloading == null) { Text(tr("取消", "取消")) }
            }
        }
    }
    replacing?.let { action ->
        DevicePresetConfirmationDialog(
            title = tr("devicePresetOverwriteTitle", "覆盖预设"),
            message = if (action.apply) tr("devicePresetApplyOverwrite", "本地已存在相同 ID 的预设。继续后将下载并覆盖本地预设文件，然后应用参数，是否继续？") else tr("devicePresetDownloadOverwrite", "本地已存在相同 ID 的预设。下载后将覆盖本地预设文件，是否继续？"),
            confirm = if (action.apply) tr("应用", "应用") else tr("下载", "下载"), busy = downloading != null,
            onDismiss = { replacing = null }, onConfirm = { download(action) },
        )
    }
    if (showUpload) {
        DevicePresetConfirmationDialog(
            title = tr("devicePresetUploadTitle", "即将跳转到 HyperChanger 设备信息预设仓库"),
            message = tr("devicePresetUploadMessage", "请登录 GitHub，在仓库 Issues 中选择“手机参数预设投稿”，按 example.json 模板提交完整 JSON。参数请填写真实信息，未知项留空，无需填写贡献者信息。投稿通过自动校验后，还需维护者审核并合并，才会出现在云端预设库。"),
            confirm = tr("跳转", "跳转"), busy = false,
            onDismiss = { showUpload = false },
            onConfirm = {
                if (openDevicePresetLink(context, "https://github.com/ColdP/hyperchanger-device-info-presets")) showUpload = false
            },
        )
    }
}

private data class CloudDevicePresetAction(val preset: CloudDeviceInfoPreset, val apply: Boolean)

private fun openDevicePresetLink(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    true
} catch (_: Exception) {
    devicePresetToast(context, tr("devicePresetLinkFailed", "无法打开链接，请确认已安装浏览器"))
    false
}

@Composable
private fun DevicePresetConfirmationDialog(
    title: String,
    message: String,
    confirm: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(show = true, onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Text(message, style = MiuixTheme.textStyles.body1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f), enabled = !busy) { Text(tr("取消", "取消")) }
                GlassDialogButton(onConfirm, Modifier.weight(1f), enabled = !busy, colors = ButtonDefaults.buttonColorsPrimary()) { Text(confirm) }
            }
        }
    }
}

private fun devicePresetToast(context: Context, message: String) = Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
