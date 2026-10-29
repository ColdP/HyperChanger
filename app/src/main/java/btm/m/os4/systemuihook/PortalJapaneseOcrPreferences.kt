// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun PortalJapaneseOcrPreferences(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { PortalOcrModelStore.get(context) }
    val state by store.state.collectAsStateWithLifecycle()
    var modelDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    SwitchPreference(
        title = tr("增强传送门文本识别能力", "增强传送门文本识别能力"),
        checked = enabled,
        onCheckedChange = { value ->
            onChange(value)
            if (!value) { modelDialog = false; deleteDialog = false }
        },
    )
    AnimatedVisibility(
        visible = enabled,
        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
    ) {
        ArrowPreference(
            title = tr("OCR 中日英本地识别模型下载", "OCR 中日英本地识别模型下载"),
            summary = when (state.phase) {
                PortalModelPhase.CHECKING -> tr("正在检查模型…", "正在检查模型…")
                PortalModelPhase.MISSING -> tr("未下载，点击下载", "未下载，点击下载")
                PortalModelPhase.DOWNLOADING -> tr("正在下载，点击取消", "正在下载，点击取消") + " (${state.downloaded * 100 / PORTAL_OCR_MODEL_BYTES}%)"
                PortalModelPhase.CANCELLING -> tr("正在取消下载…", "正在取消下载…")
                PortalModelPhase.READY -> if (state.deleteFailed) tr("删除失败，点击重试", "删除失败，点击重试") else tr("已下载，点击管理", "已下载，点击管理")
                PortalModelPhase.DELETING -> tr("正在删除模型…", "正在删除模型…")
                PortalModelPhase.ERROR -> tr("下载失败，点击重试", "下载失败，点击重试")
            },
            enabled = state.phase !in setOf(PortalModelPhase.CHECKING, PortalModelPhase.CANCELLING, PortalModelPhase.DELETING),
            onClick = {
                when (state.phase) {
                    PortalModelPhase.DOWNLOADING -> store.cancelDownload()
                    PortalModelPhase.READY -> modelDialog = true
                    PortalModelPhase.MISSING, PortalModelPhase.ERROR -> store.startDownload()
                    else -> Unit
                }
            },
        )
    }
    WindowDialog(
        show = enabled && modelDialog,
        title = tr("中日英识别模型名称", PORTAL_OCR_MODEL_NAME),
        summary = tr("中日英识别模型", "中日英识别模型"),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { modelDialog = false },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassDialogButton(onClick = { modelDialog = false; deleteDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("删除", "删除"), color = Color.Red)
            }
            GlassDialogButton(onClick = { modelDialog = false }, modifier = Modifier.fillMaxWidth()) { Text(tr("取消", "取消")) }
        }
    }
    WindowDialog(
        show = enabled && deleteDialog,
        title = tr("删除中日英识别模型？", "删除中日英识别模型？"),
        summary = tr("删除后需要重新下载才能使用增强识别能力。", "删除后需要重新下载才能使用增强识别能力。"),
        titleTextAlign = TextAlign.Start,
        summaryTextAlign = TextAlign.Start,
        onDismissRequest = { deleteDialog = false; modelDialog = true },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton(onClick = { deleteDialog = false; modelDialog = true }, modifier = Modifier.weight(1f)) {
                Text(tr("取消", "取消"))
            }
            GlassDialogButton(onClick = { deleteDialog = false; store.deleteModel() }, modifier = Modifier.weight(1f)) {
                Text(tr("确认", "确认"))
            }
        }
    }
}
