// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.OpenableColumns
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
import java.io.File
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun appearanceAssetDisplayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0).orEmpty() else ""
    }.orEmpty().ifBlank { uri.lastPathSegment.orEmpty().substringAfterLast('/') }
}.getOrDefault("")

private fun assetNameFile(context: Context, slot: String) = File(context.filesDir, "settings_appearance/$slot.name")

internal fun SettingsAppearanceSettings.assetMime(slot: String): String {
    if (':' in slot) return colorModeAssetMimes[slot].orEmpty()
    return when (slot) {
        APPEARANCE_SLOT_AUDIO_LOGO -> "image/*"
        APPEARANCE_SLOT_LOGO -> logoMime
        APPEARANCE_SLOT_DEVICE_IMAGE -> tutorialCardImageMime
        APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO -> tutorialCardLogoMime
        APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND -> tutorialCardBackgroundMime
        APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE -> style2ImageMime
        APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO -> style2LogoMime
        APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND -> style2BackgroundMime
        APPEARANCE_SLOT_STYLE3_BACKGROUND -> style3BackgroundMime
        APPEARANCE_SLOT_STYLE3_LOGO -> style3LogoMime
        APPEARANCE_SLOT_STYLE4_BACKGROUND -> deviceStyleCustomization.styleFour.backgroundMime
        APPEARANCE_SLOT_STYLE4_LOGO -> deviceStyleCustomization.styleFour.logoMime
        APPEARANCE_SLOT_CPU_ICON -> deviceStyleCustomization.cpuIconMime
        APPEARANCE_SLOT_STYLE3_CPU_ICON -> deviceStyleCustomization.separateProcessorIconMime
        APPEARANCE_SLOT_STYLE3_CAMERA_BRAND -> deviceStyleCustomization.cameraBrandMime
        else -> ""
    }
}

internal fun SettingsAppearanceSettings.withAsset(slot: String, mime: String, version: Long): SettingsAppearanceSettings {
    if (':' in slot) return copy(
        colorModeAssetMimes = colorModeAssetMimes + (slot to mime),
        colorModeAssetVersions = colorModeAssetVersions + (slot to version),
        logoMode = if (slot.substringBefore(':') == APPEARANCE_SLOT_LOGO && mime.isNotEmpty()) LOGO_MODE_KEEP_ADVANCED_MATERIAL else logoMode,
    )
    return when (slot) {
        APPEARANCE_SLOT_LOGO -> copy(logoMime = mime, logoVersion = version, logoMode = if (mime.isEmpty()) LOGO_MODE_SYSTEM else LOGO_MODE_KEEP_ADVANCED_MATERIAL)
        APPEARANCE_SLOT_DEVICE_IMAGE -> copy(tutorialCardImageMime = mime, tutorialCardImageVersion = version)
        APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO -> copy(tutorialCardLogoMime = mime, tutorialCardLogoVersion = version)
        APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND -> copy(tutorialCardBackgroundMime = mime, tutorialCardBackgroundVersion = version)
        APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE -> copy(style2ImageMime = mime, style2ImageVersion = version)
        APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO -> copy(style2LogoMime = mime, style2LogoVersion = version)
        APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND -> copy(style2BackgroundMime = mime, style2BackgroundVersion = version)
        APPEARANCE_SLOT_STYLE3_BACKGROUND -> copy(style3BackgroundMime = mime, style3BackgroundVersion = version)
        APPEARANCE_SLOT_STYLE3_LOGO -> copy(style3LogoMime = mime, style3LogoVersion = version)
        APPEARANCE_SLOT_STYLE4_BACKGROUND -> copy(deviceStyleCustomization = deviceStyleCustomization.copy(styleFour = deviceStyleCustomization.styleFour.copy(backgroundMime = mime, backgroundVersion = version)))
        APPEARANCE_SLOT_STYLE4_LOGO -> copy(deviceStyleCustomization = deviceStyleCustomization.copy(styleFour = deviceStyleCustomization.styleFour.copy(logoMime = mime, logoVersion = version)))
        APPEARANCE_SLOT_CPU_ICON -> copy(deviceStyleCustomization = deviceStyleCustomization.copy(cpuIconMime = mime, cpuIconVersion = version))
        APPEARANCE_SLOT_STYLE3_CPU_ICON -> copy(deviceStyleCustomization = deviceStyleCustomization.copy(separateProcessorIconMime = mime, separateProcessorIconVersion = version))
        APPEARANCE_SLOT_STYLE3_CAMERA_BRAND -> copy(deviceStyleCustomization = deviceStyleCustomization.copy(cameraBrandMime = mime, cameraBrandVersion = version))
        else -> this
    }
}

@Composable
internal fun DeviceAppearanceAssetPreference(
    appearance: SettingsAppearanceSettings,
    slot: String,
    title: String,
    update: ((SettingsAppearanceSettings) -> SettingsAppearanceSettings) -> Unit,
) {
    if (slot == APPEARANCE_SLOT_LOGO && appearance.deviceStyleCustomization.assetsForStyle(appearanceAssetStyle(slot))) {
        var mode by remember { mutableIntStateOf(0) }
        OverlayDropdownPreference(
            title = tr("appearance_asset_color_mode", "素材颜色模式"),
            items = listOf(tr("浅色", "浅色"), tr("深色", "深色")),
            selectedIndex = mode,
            onSelectedIndexChange = { mode = it },
        )
        key(mode) { AppearanceAssetRow(appearance.assetMime(colorModeAssetKey(slot, mode == 1)), colorModeAssetKey(slot, mode == 1), title, update) }
        LogoRecognitionPreference(appearance, slot, update)
        return
    }
    val slots = if (appearance.deviceStyleCustomization.assetsForStyle(appearanceAssetStyle(slot)) && slot in COLOR_MODE_ASSET_SLOTS) listOf(colorModeAssetKey(slot, false), colorModeAssetKey(slot, true)) else listOf(slot)
    slots.forEach { effective ->
        key(effective) {
            val label = when {
                effective.endsWith(":light") -> "$title · ${tr("浅色", "浅色")}"
                effective.endsWith(":dark") -> "$title · ${tr("深色", "深色")}"
                else -> title
            }
            AppearanceAssetRow(appearance.assetMime(effective), effective, label, update)
        }
    }
    if (slot.substringBefore(':') in RECOGNIZED_LOGO_SLOTS) LogoRecognitionPreference(appearance, slot, update)
}

@Composable
private fun AppearanceAssetRow(mime: String, slot: String, title: String, update: ((SettingsAppearanceSettings) -> SettingsAppearanceSettings) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Uri?>(null) }
    var replacement by remember { mutableStateOf<Uri?>(null) }
    var cleared by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val file = appearanceFile(context, slot)
    val savedName = remember(mime, revision) { runCatching { assetNameFile(context, slot).readText() }.getOrDefault("").ifBlank { file.name } }
    val imported = file.isFile && (mime.isNotEmpty() || slot == APPEARANCE_SLOT_CPU_ICON)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if ((imported && !cleared) || selected != null) replacement = uri
            else { selected = uri; cleared = false }
        }
    }
    SharedViewArrowPreference(title = title, summary = if (imported) savedName else tr("未导入", "未导入"), onClick = {
        selected = null; replacement = null; cleared = false; open = true
    })
    if (open) {
        val preview = remember(selected, cleared, revision, mime) {
            if (cleared) null else if (selected != null) previewBitmap(context, selected!!)
            else if (imported) previewBitmap(context, Uri.fromFile(file)) else null
        }
        val name = selected?.let { appearanceAssetDisplayName(context, it) } ?: if (imported && !cleared) savedName else ""
        val isLogo = slot.substringBefore(':') in listOf(APPEARANCE_SLOT_LOGO, APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO, APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO, APPEARANCE_SLOT_STYLE3_LOGO, APPEARANCE_SLOT_STYLE4_LOGO, APPEARANCE_SLOT_CPU_ICON, APPEARANCE_SLOT_STYLE3_CPU_ICON, APPEARANCE_SLOT_STYLE3_CAMERA_BRAND)
        WindowDialog(show = true, onDismissRequest = { open = false }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
                Text(title, style = MiuixTheme.textStyles.title3)
                AssetPreview(preview)
                Text(name.ifBlank { tr("appearance_import_hint", "点击下方的按钮进行导入，可以导入：%s").format(if (isLogo) "PNG / JPG / WebP / SVG / XML" else "PNG / JPG / WebP") }, style = MiuixTheme.textStyles.body2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassDialogButton(onClick = { picker.launch(if (isLogo) arrayOf("image/*", "image/svg+xml", "application/xml", "text/xml") else arrayOf("image/*")) }, modifier = Modifier.weight(1f)) { Text(tr("选择文件", "选择文件")) }
                    GlassDialogButton(onClick = { selected = null; cleared = true }, modifier = Modifier.weight(1f), enabled = selected != null || imported && !cleared) { Text(tr("清除", "清除")) }
                }
                GlassDialogButton(onClick = {
                    runCatching {
                        if (cleared) {
                            check(!file.exists() || file.delete())
                            assetNameFile(context, slot).delete()
                            update { it.withAsset(slot, "", System.currentTimeMillis()) }
                        } else selected?.let { uri ->
                            val type = detectAppearanceMime(context, uri)
                            val copied = copyAppearanceFile(context, slot, uri)
                            update { it.withAsset(slot, type, copied.lastModified()) }
                        }
                        revision++
                        open = false
                    }.onFailure { Toast.makeText(context, tr("导入失败", "导入失败"), Toast.LENGTH_SHORT).show() }
                }, modifier = Modifier.fillMaxWidth()) { Text(tr("保存", "保存")) }
                GlassDialogButton(onClick = { open = false }, modifier = Modifier.fillMaxWidth()) { Text(tr("取消", "取消")) }
            }
        }
    }
    replacement?.let { uri ->
        val preview = remember(uri) { previewBitmap(context, uri) }
        WindowDialog(show = true, onDismissRequest = { replacement = null }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.Start) {
                Text(tr("appearance_replace_confirm", "替换已导入的文件？"), style = MiuixTheme.textStyles.title3)
                AssetPreview(preview)
                Text(appearanceAssetDisplayName(context, uri), style = MiuixTheme.textStyles.body2)
                GlassDialogButton(onClick = { selected = uri; cleared = false; replacement = null }, modifier = Modifier.fillMaxWidth()) { Text(tr("确认", "确认")) }
                GlassDialogButton(onClick = { replacement = null }, modifier = Modifier.fillMaxWidth()) { Text(tr("取消", "取消")) }
            }
        }
    }
}

@Composable
private fun AssetPreview(bitmap: Bitmap?) {
    if (bitmap == null) Text(tr("未导入", "未导入"), style = MiuixTheme.textStyles.body2)
    else {
        val scale = minOf(180f / bitmap.width, 180f / bitmap.height)
        Image(bitmap.asImageBitmap(), null, Modifier.size((bitmap.width * scale).dp, (bitmap.height * scale).dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Fit)
    }
}

private fun previewBitmap(context: Context, uri: Uri): Bitmap? = runCatching {
    val drawable = if (uri.scheme == "file") LogoDrawableLoader.loadFile(context, File(requireNotNull(uri.path)))
    else context.contentResolver.openInputStream(uri)?.use { LogoDrawableLoader.loadBytes(context, it.readBytes()) }
    drawable ?: return@runCatching null
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val scale = minOf(1f, 720f / maxOf(width, height))
    Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
        drawable.setBounds(0, 0, it.width, it.height)
        drawable.draw(Canvas(it))
    }
}.getOrNull()
