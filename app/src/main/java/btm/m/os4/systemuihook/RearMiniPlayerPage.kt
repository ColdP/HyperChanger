// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RearMiniPlayerPage(back: () -> Unit) = AppPage(
    tr("rear_mini_player_title", "在背屏启动小折叠版迷你音乐播放器"), back,
    restartScopes = setOf(ScopeApplication.NETEASE_MUSIC, ScopeApplication.SUBSCREEN_CENTER),
) { padding, scroll ->
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context) { RearMiniPlayerSettingsStore(context) }
    var enabled by remember { mutableStateOf(store.enabled) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(service) {
        service?.let { store.syncRemote(it); enabled = store.enabled }
    }
    AppList(padding, scroll, 28) {
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                Text(tr("rear_mini_player_scope", "需要同时勾选“网易云音乐”和“背屏”作用域。首次使用请在勾选后重新打开两个应用，或手动重启设备。"),
                    modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2)
                SwitchPreference(
                    title = tr("rear_mini_player_enable", "启用功能"),
                    checked = enabled,
                    onCheckedChange = { store.update(service, it); enabled = it },
                )
                Text(tr("rear_mini_player_hint", "适配网易云音乐 9.5.37。请先在网易云完成隐私确认并播放歌曲，再解锁设备投放。投放由 LSPosed 模块通过 Root shell 启动，不依赖 system 框架 Hook；空播放队列可能触发网易云默认播放。"),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    style = MiuixTheme.textStyles.body2)
                GlassDialogButton(
                    enabled = enabled && service != null && !sending,
                    onClick = launch@{
                        if (!runCatching { store.prepareLaunch(service) }.getOrDefault(false)) {
                            Toast.makeText(context, rearMiniPlayerResultText(RearMiniPlayerLaunch.UNAVAILABLE), Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        sending = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { RearMiniPlayerLaunch.request(context.applicationContext) }
                                    .getOrDefault(RearMiniPlayerLaunch.REJECTED)
                            }
                            sending = false
                            Toast.makeText(context, rearMiniPlayerResultText(result), Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(if (sending) tr("rear_mini_player_sending", "正在投放…") else tr("rear_mini_player_cast", "投放到背屏")) }
            }
        }
    }
}

private fun rearMiniPlayerResultText(result: Int): String = when (result) {
    RearMiniPlayerLaunch.REQUESTED -> tr("rear_mini_player_requested", "已提交背屏启动请求，请查看背屏；提交成功不代表页面已显示。")
    RearMiniPlayerLaunch.DISABLED -> tr("rear_mini_player_disabled", "背屏进程中的功能未启用，请检查模块连接与开关同步。")
    RearMiniPlayerLaunch.LOCKED -> tr("rear_mini_player_locked", "请先解锁设备再投放。")
    RearMiniPlayerLaunch.NO_DISPLAY -> tr("rear_mini_player_no_display", "未找到可用的背屏（显示屏 1）。")
    RearMiniPlayerLaunch.UNSUPPORTED -> tr("rear_mini_player_unsupported", "请安装网易云音乐 9.5.37，并确认迷你播放器组件可用。")
    RearMiniPlayerLaunch.REJECTED -> tr("rear_mini_player_rejected", "系统拒绝启动。请确认 Root shell 可用，并检查背屏策略及模块日志。")
    RearMiniPlayerLaunch.ROOT_UNAVAILABLE -> tr("rear_mini_player_root_unavailable", "未能取得 Root shell，无法绕过背屏 Activity 限制。请确认 LSPosed 模块已获 Root 权限。")
    else -> tr("rear_mini_player_unavailable", "背屏 Hook 未响应。请确认两个作用域已勾选、模块已激活，并重新打开背屏应用或手动重启设备。")
}
