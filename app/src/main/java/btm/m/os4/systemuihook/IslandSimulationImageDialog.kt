// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun IslandSimulationImagePreference(type: IslandSimulationType, slot: IslandSimulationImageSlot) {
    val context = LocalContext.current
    val store = remember(context, type, slot) { IslandSimulationImageStore(context, type, slot) }
    var saved by remember(store) { mutableStateOf(store.load()) }
    var show by remember(store) { mutableStateOf(false) }
    ArrowPreference(
        title = slot.title(type),
        summary = saved?.name ?: tr("island_sim_image_none", "未导入"),
        onClick = { show = true },
    )
    if (show) IslandSimulationImageDialog(store, saved,
        cover = type == IslandSimulationType.RESERVATION && slot == IslandSimulationImageSlot.PRIMARY,
        onSaved = { saved = it; show = false }, onDismiss = { show = false })
}

@Composable
private fun IslandSimulationImageDialog(
    store: IslandSimulationImageStore,
    saved: IslandSimulationImage?,
    cover: Boolean,
    onSaved: (IslandSimulationImage?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(saved) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                draft = withContext(Dispatchers.IO) { store.read(uri) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(context, tr("island_sim_image_read_failed", "无法读取图片，请选择其他图片"), Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }
    WindowDialog(show = true, onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            val picture = draft
            if (picture == null) {
                Text(tr("island_sim_image_none", "未导入"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            } else {
                Image(
                    bitmap = picture.bitmap.asImageBitmap(),
                    contentDescription = tr("island_sim_image", "超级岛图片"),
                    modifier = Modifier.size(112.dp, if (cover) 144.dp else 112.dp).clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                )
                Text(picture.name, style = MiuixTheme.textStyles.body2)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(
                    onClick = { picker.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f), enabled = !busy,
                ) { Text(tr("选择图片", "选择图片")) }
                GlassDialogButton(
                    onClick = { draft = null }, modifier = Modifier.weight(1f), enabled = !busy && draft != null,
                ) { Text(tr("清除", "清除")) }
            }
            GlassDialogButton(
                onClick = {
                    val selected = draft
                    scope.launch {
                        busy = true
                        try {
                            withContext(Dispatchers.IO) { store.save(selected) }
                            onSaved(selected)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            Toast.makeText(context, tr("island_sim_image_save_failed", "图片保存失败，请重试"), Toast.LENGTH_SHORT).show()
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = !busy,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("保存", "保存")) }
            GlassDialogButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Text(tr("取消", "取消"))
            }
        }
    }
}
