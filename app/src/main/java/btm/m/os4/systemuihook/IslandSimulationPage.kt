// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
internal fun IslandSimulationPage(onRequestNotificationPermission: () -> Unit, back: () -> Unit) =
    AppPage(tr("island_simulation", "超级岛模拟"), back) { padding, scroll ->
        val context = LocalContext.current
        val store = remember(context) { IslandSimulationSettingsStore(context) }
        var type by remember { mutableStateOf(store.selectedType()) }
        var settings by remember(type) { mutableStateOf(store.load(type)) }
        val defaults = store.defaults(type)
        fun edit(value: IslandSimulationSettings) {
            settings = value
            store.save(type, value)
        }
        fun send() {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, tr("notification_permission_required", "请先允许通知权限"), Toast.LENGTH_SHORT).show()
                onRequestNotificationPermission()
                return
            }
            val required = buildList {
                add(settings.title)
                add(settings.content)
                add(settings.summary)
                if (type in listOf(IslandSimulationType.MOVIE_LOGISTICS, IslandSimulationType.HEALTH,
                        IslandSimulationType.RESERVATION, IslandSimulationType.PAYMENT)) {
                    add(settings.detail)
                }
                if (type in listOf(IslandSimulationType.MOVIE_LOGISTICS, IslandSimulationType.HEALTH,
                        IslandSimulationType.PHONE, IslandSimulationType.RESERVATION, IslandSimulationType.FILE_SHARE,
                        IslandSimulationType.PAYMENT, IslandSimulationType.VERIFICATION)) {
                    add(settings.action)
                }
                if (type == IslandSimulationType.RESERVATION) add(settings.subContent)
                if (type == IslandSimulationType.FILE_SHARE) add(settings.secondaryAction)
            }
            if (required.any(String::isBlank)) {
                Toast.makeText(context, tr("island_sim_fields_required", "请填写当前类型的全部文本内容"), Toast.LENGTH_SHORT).show()
                return
            }
            val sent = IslandSimulator.send(context, type, settings)
            Toast.makeText(context, if (sent) tr("island_sim_sent", "超级岛已发送") else tr("island_sim_failed", "超级岛发送失败"), Toast.LENGTH_SHORT).show()
        }
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    OverlayDropdownPreference(
                        title = tr("island_sim_type", "模拟超级岛类型"),
                        items = IslandSimulationType.entries.map { it.title() },
                        selectedIndex = type.ordinal,
                        onSelectedIndexChange = { index ->
                            type = IslandSimulationType.entries[index]
                            store.select(type)
                        },
                    )
                    if (supportsIslandSimulationRearScreen(type, settings.logistics)) {
                        SwitchPreference(
                            title = tr("island_sim_rear_screen", "同时发送至背屏"),
                            checked = settings.rearScreen,
                            onCheckedChange = { edit(settings.copy(rearScreen = it)) },
                        )
                    }
                    GlassDialogButton(
                        onClick = ::send,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(tr("island_sim_send", "发送超级岛")) }
                }
            }
            item {
                SmallTitle(type.title(), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    if (type == IslandSimulationType.MOVIE_LOGISTICS) {
                        OverlayDropdownPreference(
                            title = tr("island_sim_scenario", "模拟场景"),
                            items = listOf(tr("island_sim_movie", "电影"), tr("island_sim_logistics", "物流")),
                            selectedIndex = if (settings.logistics) 1 else 0,
                            onSelectedIndexChange = { edit(settings.copy(logistics = it == 1)) },
                        )
                    }
                    Column(Modifier.fillMaxWidth()) {
                        IslandSimulationTextPreference(
                            when (type) {
                                IslandSimulationType.CALL, IslandSimulationType.PHONE -> tr("island_sim_caller", "联系人")
                                IslandSimulationType.PROGRESS -> tr("island_sim_task", "任务名称")
                                IslandSimulationType.HEALTH -> tr("island_sim_health_value", "运动数据（公里）")
                                IslandSimulationType.FILE_SHARE -> tr("island_sim_file_name", "文件名称")
                                IslandSimulationType.VERIFICATION -> tr("island_sim_verification_code", "验证码")
                                else -> tr("island_sim_main_text", "主要文本")
                            }, settings.title, defaults.title,
                        ) { edit(settings.copy(title = it)) }
                        IslandSimulationTextPreference(
                            when (type) {
                                IslandSimulationType.CALL, IslandSimulationType.PHONE -> tr("island_sim_call_state", "通话状态")
                                IslandSimulationType.PROGRESS -> tr("island_sim_file_name", "文件名称")
                                IslandSimulationType.HEALTH -> tr("island_sim_exercise", "运动名称")
                                else -> tr("island_sim_secondary_text", "次要文本")
                            }, settings.content, defaults.content, limit = 80,
                        ) { edit(settings.copy(content = it)) }
                        IslandSimulationTextPreference(
                            tr("island_sim_summary", "摘要态短文本"), settings.summary, defaults.summary, limit = 8,
                        ) { edit(settings.copy(summary = it)) }
                        if (type == IslandSimulationType.CALL || type == IslandSimulationType.PHONE) {
                            IslandSimulationTextPreference(
                                tr("island_sim_call_seconds", "已通话时长（秒）"), settings.callSeconds.toString(),
                                defaults.callSeconds.toString(),
                                numeric = true, limit = 4,
                            ) { edit(settings.copy(callSeconds = (it.toIntOrNull() ?: 0).coerceIn(0, 3599))) }
                        }
                        if (type == IslandSimulationType.RESERVATION) {
                            IslandSimulationTextPreference(tr("island_sim_reservation_location", "预约地点"),
                                settings.subContent, defaults.subContent, limit = 40) { edit(settings.copy(subContent = it)) }
                        }
                        if (type in listOf(IslandSimulationType.MOVIE_LOGISTICS, IslandSimulationType.HEALTH,
                                IslandSimulationType.RESERVATION, IslandSimulationType.PAYMENT)) {
                            IslandSimulationTextPreference(
                                when {
                                    type == IslandSimulationType.HEALTH -> tr("island_sim_exercise_time", "运动时长")
                                    type == IslandSimulationType.RESERVATION -> tr("island_sim_highlight_text", "高亮文本")
                                    type == IslandSimulationType.PAYMENT -> tr("island_sim_payment_amount", "待支付金额")
                                    settings.logistics -> tr("island_sim_pickup_code", "取件码")
                                    else -> tr("island_sim_ticket_code", "取票码")
                                }, settings.detail, defaults.detail, limit = 24,
                            ) { edit(settings.copy(detail = it)) }
                        }
                        if (type in listOf(IslandSimulationType.MOVIE_LOGISTICS, IslandSimulationType.HEALTH,
                                IslandSimulationType.PHONE, IslandSimulationType.RESERVATION, IslandSimulationType.FILE_SHARE,
                                IslandSimulationType.PAYMENT, IslandSimulationType.VERIFICATION)) {
                            IslandSimulationTextPreference(tr("island_sim_action_text", "按钮文本"), settings.action, defaults.action, limit = 12) {
                                edit(settings.copy(action = it))
                            }
                        }
                        if (type == IslandSimulationType.FILE_SHARE) {
                            IslandSimulationTextPreference(tr("island_sim_secondary_action_text", "次要按钮文本"),
                                settings.secondaryAction, defaults.secondaryAction, limit = 12) { edit(settings.copy(secondaryAction = it)) }
                        }
                        islandSimulationImageSlots(type).forEach { slot ->
                            key(type, slot) { IslandSimulationImagePreference(type, slot) }
                        }
                        if (type == IslandSimulationType.DELIVERY || type == IslandSimulationType.PROGRESS) {
                            Text(
                                tr("island_sim_initial_progress", "初始进度") + " ${settings.progress}%",
                                style = MiuixTheme.textStyles.body1,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                            Slider(
                                value = settings.progress.toFloat(),
                                onValueChange = { edit(settings.copy(progress = it.roundToInt().coerceIn(0, 99))) },
                                valueRange = 0f..99f,
                                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                            )
                        }
                    }
                    if (type == IslandSimulationType.PROGRESS) {
                        OverlayDropdownPreference(
                            title = tr("island_sim_countdown", "进度完成倒计时"),
                            items = ISLAND_SIMULATION_DURATIONS.map { tr("island_sim_seconds", "%ds").format(it) },
                            selectedIndex = ISLAND_SIMULATION_DURATIONS.indexOf(settings.durationSeconds),
                            onSelectedIndexChange = { edit(settings.copy(durationSeconds = ISLAND_SIMULATION_DURATIONS[it])) },
                        )
                    }
                }
            }
        }
    }

@Composable
private fun IslandSimulationTextPreference(
    label: String,
    value: String,
    defaultValue: String,
    numeric: Boolean = false,
    limit: Int = 40,
    onValueChange: (String) -> Unit,
) {
    var show by remember(label) { mutableStateOf(false) }
    var draft by remember(label) { mutableStateOf(value) }
    ArrowPreference(title = label, summary = value, onClick = {
        draft = value
        show = true
    })
    if (show) WindowDialog(show = true, onDismissRequest = { show = false }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            TextField(
                value = draft,
                onValueChange = { if (!numeric || it.all { char -> char in '0'..'9' }) draft = it.take(limit) },
                label = label,
                useLabelAsPlaceholder = true,
                singleLine = true,
                cornerRadius = 16.dp,
                keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
            GlassDialogButton(onClick = { draft = defaultValue }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("island_sim_restore_default", "恢复默认"))
            }
            GlassDialogButton(
                onClick = { onValueChange(draft.trim()); show = false },
                modifier = Modifier.fillMaxWidth(),
                enabled = !numeric || draft.toIntOrNull()?.let { it in 0..3599 } == true,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("保存", "保存")) }
            GlassDialogButton(onClick = { show = false }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("取消", "取消"))
            }
        }
    }
}
