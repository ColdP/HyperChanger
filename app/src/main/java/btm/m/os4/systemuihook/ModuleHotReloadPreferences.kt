// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ModuleHotReloadPreferences(enabled: Boolean) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember { ModuleHotReloadSettingsStore(context) }
    var checked by remember { mutableStateOf(store.enabled) }
    var busy by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<List<HookedTarget>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(service) {
        service?.let {
            if (runCatching { store.syncRemote(it) }.isFailure) message = tr("safetySyncFailed", "设置已保存到本地，但框架同步失败。请恢复 LSPosed 连接后重试。")
            checked = store.enabled
        }
    }
    SwitchPreference(title = tr("hotReloadSwitch", "进程热重载"), checked = checked, enabled = enabled && !busy,
        onCheckedChange = { value ->
            val result = runCatching { store.update(service, value) }
            checked = store.enabled
            if (result.isFailure) message = tr("safetySyncFailed", "设置已保存到本地，但框架同步失败。请恢复 LSPosed 连接后重试。")
        })
    AnimatedVisibility(visible = checked,
        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f)) {
        ArrowPreference(title = tr("hotReloadNow", "立即重载进程"),
            summary = tr("hotReloadHint", "可能需要 LSPosed API 102 或更高版本"), enabled = enabled && !busy,
            onClick = {
                busy = true
                scope.launch {
                    try {
                        val current = service
                        if (current == null) message = tr("safetyLsposedDisconnected", "LSPosed 未连接")
                        else {
                            val targets = withContext(Dispatchers.IO) {
                                if (current.apiVersion < XposedService.API_102) throw UnsupportedOperationException()
                                current.runningTargets.distinctBy { Triple(it.uid, it.pid, it.processName) }
                                    .sortedWith(compareBy({ it.processName }, { it.uid }, { it.pid }))
                            }
                            if (targets.isEmpty()) message = tr("hotReloadNoTargets", "没有已注入本模块的运行中进程")
                            else picker = targets
                        }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: UnsupportedOperationException) { message = processReloadOutcomeText(ProcessReloadOutcome.UNSUPPORTED)
                    } catch (_: Exception) { message = processReloadOutcomeText(ProcessReloadOutcome.SERVICE_ERROR)
                    } catch (_: LinkageError) { message = processReloadOutcomeText(ProcessReloadOutcome.UNSUPPORTED)
                    } finally { busy = false }
                }
            })
    }
    if (busy) Text(tr("hotReloadWorking", "正在处理热重载请求…"), Modifier.fillMaxWidth().padding(16.dp),
        style = MiuixTheme.textStyles.body2)
    picker?.let { targets ->
        ProcessReloadPicker(targets, { picker = null }) { selected ->
            picker = null
            busy = true
            scope.launch {
                try {
                    val current = service
                    message = if (current == null) tr("safetyLsposedDisconnected", "LSPosed 未连接")
                    else reloadModuleProcesses(current, selected).joinToString("\n\n") { (target, outcome) ->
                        tr("hotReloadProcessLabel", "%s (PID %d / UID %d)").format(target.processName, target.pid, target.uid) +
                            "\n" + processReloadOutcomeText(outcome)
                    }
                } finally { busy = false }
            }
        }
    }
    WindowDialog(show = message != null, title = tr("hotReloadSwitch", "进程热重载"),
        onDismissRequest = { message = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(message.orEmpty(), Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()), textAlign = TextAlign.Left)
            GlassDialogButton({ message = null }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("确认", "确认")) }
        }
    }
}

@Composable
private fun ProcessReloadPicker(targets: List<HookedTarget>, dismiss: () -> Unit, reload: (List<HookedTarget>) -> Unit) {
    var selected by remember(targets) { mutableStateOf(emptySet<Int>()) }
    WindowDialog(show = true, title = tr("hotReloadChoose", "选择重载进程"), onDismissRequest = dismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                targets.forEachIndexed { index, target ->
                    val toggle = { selected = if (index in selected) selected - index else selected + index }
                    Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(state = if (index in selected) ToggleableState.On else ToggleableState.Off, onClick = toggle)
                        Text(tr("hotReloadProcessLabel", "%s (PID %d / UID %d)").format(target.processName, target.pid, target.uid),
                            Modifier.padding(start = 12.dp).weight(1f), textAlign = TextAlign.Left)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton({ selected = targets.indices.toSet() }, Modifier.weight(1f)) { Text(tr("全选", "全选")) }
                GlassDialogButton({ reload(targets.filterIndexed { index, _ -> index in selected }) }, Modifier.weight(1f),
                    enabled = selected.isNotEmpty(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("hotReloadAction", "重载")) }
            }
            GlassDialogButton(dismiss, Modifier.fillMaxWidth()) { Text(tr("取消", "取消")) }
        }
    }
}
