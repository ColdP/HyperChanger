// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ModuleSafetyPage(openLogs: () -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember { ModuleSafetySettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var sectionsDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val exporter = rememberModuleLogExporter()
    val nativeFailure = tr("safetyNativeFailed", "设置已保存，但桌面原生配置同步失败。请授予超级用户权限后重试，确认成功后再重启设备。")
    val syncFailure = tr("safetySyncFailed", "设置已保存到本地，但框架同步失败。请恢复 LSPosed 连接后重试。")

    fun update(change: (ModuleSafetySettings) -> ModuleSafetySettings) {
        if (busy) return
        val result = runCatching { store.update(service, change) }
        settings = store.settings
        status = if (result.isFailure) syncFailure else null
        busy = true
        scope.launch {
            val staged = withContext(Dispatchers.IO) {
                val p = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, 0)
                stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(p))
            }
            if (!staged) status = nativeFailure
            withContext(Dispatchers.IO) { runCatching { ModuleLogRepository(context).cleanup() } }
            busy = false
        }
    }

    LaunchedEffect(service) {
        service?.let {
            if (runCatching { store.syncRemote(it) }.isFailure) status = syncFailure
            settings = store.settings
        }
    }

    AppPage(tr("moduleSafety", "模块安全"), back, header = {
        SoftGlassHeaderTabs(listOf(tr("safetyOptions", "选项"), tr("safetyInformation", "信息")), selectedTab) {
            selectedTab = it
        }
    }) { padding, scroll ->
        AppList(padding, scroll) {
            if (selectedTab == 0) {
                item {
                    Group(tr("safetySwitches", "模块开关")) {
                        SwitchPreference(
                            title = tr("safetyMaster", "模块总开关"),
                            summary = tr("safetyMasterHint", "关闭以后禁用所有 Hook，至少软重启设备以生效"),
                            checked = settings.enabled,
                            enabled = !busy,
                            onCheckedChange = { value -> update { it.copy(enabled = value) } }
                        )
                        SwitchPreference(
                            title = tr("safetySystemUi", "系统界面安全模式"),
                            summary = tr("safetySystemUiHint", "禁用对系统界面的 Hook，重启后生效"),
                            checked = settings.systemUiSafe,
                            enabled = !busy,
                            onCheckedChange = { value -> update { it.copy(systemUiSafe = value) } }
                        )
                        SwitchPreference(
                            title = tr("safetyPartial", "部分安全模式"),
                            summary = tr("safetyPartialHint", "停用某个部分的模块功能，重启后生效"),
                            checked = settings.partialSafe,
                            enabled = !busy,
                            onCheckedChange = { value ->
                                if (value) sectionsDialog = true else update { it.copy(partialSafe = false) }
                            }
                        )
                        if (settings.partialSafe) {
                            ArrowPreference(
                                tr("safetyChooseSections", "选择停用部分"),
                                summary = settings.sections.joinToString("、") { safetySectionTitle(it) },
                                enabled = !busy,
                                onClick = { sectionsDialog = true }
                            )
                        }
                        ModuleHotReloadPreferences(enabled = !busy)
                    }
                }
                item {
                    Group(tr("safetyLogging", "日志输出")) {
                        SwitchPreference(
                            title = tr("safetyLogging", "日志输出"),
                            checked = settings.logEnabled,
                            enabled = !busy,
                            onCheckedChange = { value -> update { it.copy(logEnabled = value) } }
                        )
                        OverlayDropdownPreference(
                            tr("safetyLogOutputLevel", "日志输出等级"),
                            listOf(tr("safetyLogNormal", "一般"), tr("safetyLogDetailed", "详细")),
                            if (settings.detailedLogs) 1 else 0,
                            enabled = !busy && settings.logEnabled,
                            onSelectedIndexChange = { value -> update { it.copy(detailedLogs = value == 1) } }
                        )
                        ArrowPreference(tr("safetyViewLogs", "查看日志"), onClick = openLogs)
                        ArrowPreference(
                            tr("safetyExportLogs", "导出日志"),
                            enabled = !exporter.busy,
                            onClick = { exporter.export(false) }
                        )
                        val days = listOf(3, 7, 14, 30, 120)
                        OverlayDropdownPreference(
                            tr("safetyRetention", "定时清理日志"),
                            days.map { tr("safetyDays", "%d 天").format(it) },
                            days.indexOf(settings.retentionDays),
                            enabled = !busy,
                            onSelectedIndexChange = { index -> update { it.copy(retentionDays = days[index]) } }
                        )
                        ArrowPreference(
                            tr("safetyClearAll", "立即清空日志"),
                            enabled = !busy,
                            onClick = { clearDialog = true }
                        )
                    }
                }
                status?.let { value -> item { SafetyNote(value) } }
                if (status != null) {
                    item {
                        Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                            ArrowPreference(
                                tr("safetyRetrySync", "重试同步"),
                                enabled = !busy,
                                onClick = { update { it } }
                            )
                        }
                    }
                }
                exporter.status?.let { value -> item { SafetyNote(value) } }
                if (service == null) {
                    item { SafetyNote(tr("safetyOffline", "LSPosed 未连接，设置将保存在本地并在连接后同步。")) }
                }
            } else {
                item { ModuleSafetyInformation() }
            }
        }
    }

    if (sectionsDialog) {
        SafetySectionsDialog(settings.sections, { sectionsDialog = false }) { selected ->
            sectionsDialog = false
            update { it.copy(partialSafe = true, sections = selected) }
        }
    }

    if (clearDialog) {
        ModuleLogClearDialog({ clearDialog = false }) {
            clearDialog = false
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { ModuleLogRepository(context).clear() }
                }
                status = if (result.isSuccess) {
                    tr("safetyLogsCleared", "日志已清空")
                } else {
                    tr("safetyLogsClearFailed", "无法清空日志，请重试")
                }
                busy = false
            }
        }
    }
}

internal fun safetySectionTitle(section: SafetySection) = tr("safetySection${section.name}", section.title)

@Composable
private fun SafetySectionsDialog(
    initial: Set<SafetySection>,
    dismiss: () -> Unit,
    save: (Set<SafetySection>) -> Unit
) {
    var selected by remember { mutableStateOf(initial) }
    WindowDialog(show = true, title = tr("safetyPartial", "部分安全模式"), onDismissRequest = dismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                SafetySection.entries.forEach { section ->
                    val toggle = {
                        selected = if (section in selected) selected - section else selected + section
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            state = if (section in selected) ToggleableState.On else ToggleableState.Off,
                            onClick = toggle
                        )
                        Text(safetySectionTitle(section), Modifier.padding(start = 12.dp))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton({ selected = SafetySection.entries.toSet() }, Modifier.weight(1f)) {
                    Text(tr("全选", "全选"))
                }
                GlassDialogButton({ save(selected) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(tr("保存", "保存"))
                }
            }
            GlassDialogButton(dismiss, Modifier.fillMaxWidth()) {
                Text(tr("取消", "取消"))
            }
        }
    }
}

@Composable
internal fun SafetyNote(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    )
}

@Composable
private fun ModuleSafetyInformation() {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    var root by remember { mutableStateOf<Boolean?>(null) }
    var zygisk by remember { mutableStateOf<String?>(null) }
    var lsposedVersion by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val detection = withContext(Dispatchers.IO) {
            val probe = runCatching { ModuleRoot.run("id -u", 1024) }.getOrNull()
            val hasRoot = probe?.let { it.code == 0 && it.output.trim() == "0" } ?: false
            var zygiskInfo: String? = null
            var lsposedInfo: String? = null

            if (hasRoot) {
                zygiskInfo = runCatching {
                    ModuleRoot.run(
                        """
                        for directory in /data/adb/modules/*; do
                          [ -f "${'$'}directory/module.prop" ] || continue
                          [ ! -f "${'$'}directory/disable" ] || continue
                          case "${'$'}directory" in *zygisk*|*rezygisk*)
                            name=''; version=''
                            while IFS='=' read -r key value; do
                              case "${'$'}key" in name) name="${'$'}value";; version) version="${'$'}value";; esac
                            done < "${'$'}directory/module.prop"
                            printf '%s %s\n' "${'$'}name" "${'$'}version";;
                          esac
                        done
                        """.trimIndent(),
                        8192
                    ).output.trim().takeIf { it.isNotBlank() }
                }.getOrNull()

                lsposedInfo = runCatching {
                    ModuleRoot.run(
                        """
                        for directory in /data/adb/modules/*; do
                            [ -f "${'$'}directory/module.prop" ] || continue
                            [ -f "${'$'}directory/disable" ] && continue
                            id=''
                            name=''
                            version=''
                            while IFS='=' read -r key value; do
                                case "${'$'}key" in
                                    id) id="${'$'}value" ;;
                                    name) name="${'$'}value" ;;
                                    version) version="${'$'}value" ;;
                                esac
                            done < "${'$'}directory/module.prop"
                            case "${'$'}id:${'$'}name" in
                                *lsposed*|*LSPosed*|*LSPOSED*)
                                    printf '%s\n' "${'$'}version"
                                    break
                                    ;;
                            esac
                        done
                        """.trimIndent(),
                        8192
                    ).output.trim().takeIf { it.isNotBlank() }
                }.getOrNull()
            }

            Triple(hasRoot, zygiskInfo, lsposedInfo)
        }
        root = detection.first
        zygisk = detection.second
        lsposedVersion = detection.third
    }

    val unknown = tr("未检测到", "未检测到")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Group(tr("safetyFrameworkRoot", "框架与超级用户")) {
            Column(Modifier.fillMaxWidth().clickable { openLsposedManager(context) }) {
                DeviceInfoItem(
                    if (service != null) {
                        tr("safetyLsposedConnected", "LSPosed 已连接")
                    } else {
                        tr("safetyLsposedDisconnected", "LSPosed 未连接")
                    },
                    tr("safetyApi", "API %s").format(
                        runCatching { service?.apiVersion?.toString() }.getOrNull() ?: unknown
                    ) + "\n" + (lsposedVersion ?: unknown)
                )
            }
            DeviceInfoItem(
                when (root) {
                    true -> tr("safetyRootGranted", "超级用户 已授权")
                    false -> tr("safetyRootDenied", "超级用户 未授权")
                    null -> tr("safetyRootChecking", "超级用户 检测中")
                },
                zygisk ?: tr("safetyZygiskUnknown", "Zygisk (Next)：未检测到版本")
            )
        }

        Group(tr("safetyDeviceInformation", "设备信息")) {
            DeviceInfoItem(
                OsCompatibility.systemProperty("ro.product.marketname").ifBlank { Build.MODEL },
                tr("safetyDeviceModel", "设备型号")
            )
            DeviceInfoItem(
                OsCompatibility.systemProperty("ro.product.device").ifBlank { Build.DEVICE },
                tr("设备代号", "设备代号")
            )
            DeviceInfoItem(
                tr("safetyAndroidValue", "%s (SDK %d)").format(Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
                tr("safetyAndroidVersion", "Android 版本")
            )
            DeviceInfoItem(
                OsCompatibility.systemProperty("ro.mi.os.version.incremental").removePrefix("OS").ifBlank { unknown },
                tr("safetyHyperOsBuildVersion", "HyperOS 构建版本号")
            )
            DeviceInfoItem(Build.SUPPORTED_ABIS.joinToString(", "), tr("safetyArchitecture", "系统架构"))
            DeviceInfoItem(
                OsCompatibility.systemProperty("ro.mi.os.version.code").ifBlank { unknown },
                tr("safetyHyperOsVersion", "HyperOS 版本")
            )
            DeviceInfoItem(
                OsCompatibility.systemProperty("ro.build.version.security_patch").ifBlank { unknown },
                tr("safetySecurityPatchLevel", "安全补丁等级")
            )
            DeviceInfoItem(
                runCatching { System.getProperty("os.version").orEmpty().trim() }.getOrDefault("").ifBlank { unknown },
                tr("safetyKernelVersion", "内核版本")
            )
            DeviceInfoItem(
                OsCompatibility.systemProperty("ro.build.fingerprint").ifBlank { unknown },
                tr("safetyBuildFingerprint", "系统构建指纹")
            )
            DeviceInfoItem(
                OsCompatibility.systemProperty("ro.soc.model").ifBlank { unknown },
                tr("safetyProcessorModel", "处理器型号")
            )
        }
    }
}
