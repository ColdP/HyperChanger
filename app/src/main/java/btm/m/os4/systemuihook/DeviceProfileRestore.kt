// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** Empty overrides let Settings display its original values; selection itself is not persisted. */
internal fun DeviceProfileSettings.restoreDeviceProfileSections(basic: Boolean, info: Boolean): DeviceProfileSettings {
    val restoredBasic = if (basic) copy(
        model = "", processor = "", ram = "", battery = "", screenSize = "", resolution = "",
        cameraFront = "", cameraRear = "",
    ) else this
    return if (info) restoredBasic.copy(
        detailModel = "", detailProcessor = "", camera = "", osVersion = "", androidVersion = "",
        storage = "", kernel = "", baseband = "", hardware = "",
    ) else restoredBasic
}

/** Commit the complete current parameter snapshot before allowing a restore. */
internal fun DeviceInfoPresetStore.saveRestoreBackup(profile: DeviceProfileSettings): DeviceInfoPreset = synchronized(preferences) {
    val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(Date())
    val existing = load()
    repeat(10_000) {
        val suffix = Random.nextInt(10_000).toString().padStart(4, '0')
        val name = "DeviceInfoPreset_${timestamp}_$suffix"
        val id = "device-info-preset-$timestamp-$suffix"
        if (existing.none { it.id == id || it.name == name }) {
            val preset = DeviceInfoPresetCodec.capture(id, name, "", profile)
            save(preset)
            return@synchronized preset
        }
    }
    error("Could not allocate a unique device parameter backup name")
}

@Composable
internal fun DeviceProfileRestoreDialog(show: Boolean, profile: DeviceProfileSettings, onDismiss: () -> Unit, onRestore: (Boolean, Boolean) -> Unit) {
    var basic by remember(show) { mutableStateOf(false) }
    var info by remember(show) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var savedName by remember { mutableStateOf<String?>(null) }
    var saveFailed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val store = remember(context) { DeviceInfoPresetStore(context) }
    val scope = rememberCoroutineScope()
    WindowDialog(show = show && !saveFailed, onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                tr("device_profile_restore_parts", "选择要恢复的部分"),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start,
                style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold,
            )
            RestoreSectionCheckbox(tr("基础参数", "基础参数"), basic, !busy) { basic = !basic }
            RestoreSectionCheckbox(tr("全部参数与信息", "全部参数与信息"), info, !busy) { info = !info }
            Spacer(Modifier.height(8.dp))
            GlassDialogButton(
                onClick = {
                    if (!busy && (basic || info)) {
                        val restoreBasic = basic
                        val restoreInfo = info
                        busy = true
                        scope.launch {
                            val preset = try {
                                withContext(Dispatchers.IO) { store.saveRestoreBackup(profile) }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                saveFailed = true
                                null
                            } finally {
                                busy = false
                            }
                            if (preset != null) {
                                onRestore(restoreBasic, restoreInfo)
                                savedName = preset.name
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = !busy && (basic || info),
            ) { Text(tr("device_profile_save_then_restore", "保存当前参数后恢复")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f), enabled = !busy) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = { onRestore(basic, info) }, modifier = Modifier.weight(1f),
                    enabled = !busy && (basic || info), colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("device_profile_restore", "恢复")) }
            }
        }
    }
    WindowDialog(show = savedName != null, onDismissRequest = { savedName = null }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("device_profile_preset_created", "创建成功"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Text(tr("预设名", "预设名"), style = MiuixTheme.textStyles.body2)
            Text(savedName.orEmpty(), style = MiuixTheme.textStyles.body1)
            GlassDialogButton({ savedName = null }, Modifier.fillMaxWidth()) { Text(tr("确定", "确定")) }
        }
    }
    WindowDialog(show = saveFailed, onDismissRequest = { saveFailed = false }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("device_profile_backup_failed", "预设保存失败，参数尚未恢复，请重试。"), style = MiuixTheme.textStyles.body1)
            GlassDialogButton({ saveFailed = false }, Modifier.fillMaxWidth()) { Text(tr("确定", "确定")) }
        }
    }
}

@Composable
private fun RestoreSectionCheckbox(title: String, checked: Boolean, enabled: Boolean, toggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox, onClick = toggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(state = if (checked) ToggleableState.On else ToggleableState.Off, onClick = { if (enabled) toggle() })
        Text(title, style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(start = 10.dp))
    }
}
