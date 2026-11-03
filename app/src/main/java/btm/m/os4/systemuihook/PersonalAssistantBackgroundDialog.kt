// SPDX-License-Identifier: AGPL-3.0-or-later
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PersonalAssistantBackgroundDialog(
    settings: PersonalAssistantSettings,
    onSaved: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { PersonalAssistantBackgroundImageStore(context) }
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf<PersonalAssistantBackgroundImage?>(null) }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try { draft = withContext(Dispatchers.IO) { store.load(settings) } }
        finally { busy = false }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try { draft = withContext(Dispatchers.IO) { store.read(uri) } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                Toast.makeText(context, tr("无法读取背景文件，请选择其他图片", "无法读取背景文件，请选择其他图片"), Toast.LENGTH_SHORT).show()
            } finally { busy = false }
        }
    }
    WindowDialog(show = true, onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
            val image = draft
            if (image == null) {
                Text(tr("未导入", "未导入"), style = MiuixTheme.textStyles.title3)
            } else {
                Image(image.bitmap.asImageBitmap(), contentDescription = tr("负一屏背景预览", "负一屏背景预览"),
                    modifier = Modifier.size(120.dp, 168.dp).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
            }
            Text(image?.name ?: tr("请导入背景文件", "请导入背景文件"), style = MiuixTheme.textStyles.body2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(onClick = { picker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f), enabled = !busy) {
                    Text(tr("选择文件", "选择文件"))
                }
                GlassDialogButton(onClick = { draft = null }, modifier = Modifier.weight(1f), enabled = !busy && draft != null) {
                    Text(tr("清除文件", "清除文件"))
                }
            }
            GlassDialogButton(onClick = {
                val selected = draft
                scope.launch {
                    busy = true
                    try {
                        val id = withContext(Dispatchers.IO) { store.save(selected) }
                        onSaved(id, selected?.name.orEmpty())
                        // Clean up only after the settings point to the newly committed file.
                        withContext(NonCancellable + Dispatchers.IO) { store.removeUnused(id) }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        Toast.makeText(context, tr("背景文件保存失败，请重试", "背景文件保存失败，请重试"), Toast.LENGTH_SHORT).show()
                    } finally { busy = false }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = !busy, colors = ButtonDefaults.buttonColorsPrimary()) {
                Text(tr("保存", "保存"))
            }
            GlassDialogButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text(tr("取消", "取消")) }
        }
    }
}
