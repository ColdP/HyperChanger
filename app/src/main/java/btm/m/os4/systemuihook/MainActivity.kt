// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.widget.ImageView
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.items
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import btm.m.liquidglass.LabelMode
import btm.m.liquidglass.hook.DampedDragAnimation
import btm.m.liquidglass.hook.CustomNavigation
import btm.m.liquidglass.hook.HostTab
import btm.m.liquidglass.hook.InteractiveHighlight
import btm.m.liquidglass.momentumBackTransform
import btm.m.liquidglass.rememberMomentumPredictiveBack
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.runtimeShaderEffect
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import io.github.libxposed.service.XposedService
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.time.Year
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val hookStore by lazy { HookSettingsStore(this) }
    private val cameraStore by lazy { CameraSettingsStore(this) }
    private val deviceProfileStore by lazy { DeviceProfileStore(this) }
    private val appearanceStore by lazy { SettingsAppearanceStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContent { Root(hookStore, cameraStore, deviceProfileStore, appearanceStore) }
    }
}

private enum class Tab { CATEGORY, SETTINGS }

private val LocalPageBackSuppressed = compositionLocalOf<(Boolean) -> Unit> { {} }
private val LocalLanguageChanged = compositionLocalOf<() -> Unit> { {} }
private var activeLanguageStrings by mutableStateOf<Map<String, String>>(emptyMap())

private fun tr(key: String, fallback: String): String =
    activeLanguageStrings[key] ?: activeLanguageStrings[fallback] ?: fallback

private fun languageStrings(
    context: android.content.Context,
    prefs: android.content.SharedPreferences,
    selection: String = prefs.getString("selected", "system") ?: "system",
): Map<String, String> = runCatching {
    if (selection == "system") return@runCatching builtinLanguageStrings(context, Locale.getDefault().language)
    if (selection in setOf("zh", "en", "ja")) return@runCatching builtinLanguageStrings(context, selection)
    val pack = loadLanguagePacks(prefs).firstOrNull { it.name == selection } ?: return@runCatching emptyMap()
    val objectStrings = JSONObject(pack.json).optJSONObject("strings") ?: return@runCatching emptyMap()
    buildMap { objectStrings.keys().forEach { key -> put(key, objectStrings.optString(key)) } }
}.getOrDefault(emptyMap())

private fun builtinLanguageStrings(context: android.content.Context, language: String): Map<String, String> {
    if (language !in setOf("en", "ja")) return emptyMap()
    return runCatching {
        val json = context.assets.open("languages/$language.json").bufferedReader().use { it.readText() }
        val strings = JSONObject(json).getJSONObject("strings")
        buildMap { strings.keys().forEach { key -> put(key, strings.getString(key)) } }
    }.getOrDefault(emptyMap())
}

private val modulePresetRandom = SecureRandom()
private const val MODULE_PRESET_RANDOM_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

private fun defaultModulePresetFileName(): String {
    val timestamp = SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date())
    val suffix = buildString {
        repeat(6) {
            append(MODULE_PRESET_RANDOM_ALPHABET[modulePresetRandom.nextInt(MODULE_PRESET_RANDOM_ALPHABET.length)])
        }
    }
    return "HyperChanger_Presets_${timestamp}_${suffix}.json"
}

private enum class PageId {
    SHADE,
    SHADE_PRESETS,
    SHADE_NOTIFICATION_ELEMENTS,
    SHADE_CONTROL_CENTER_ELEMENTS,
    SHADE_NOTIFICATION_BACKGROUND,
    SHADE_CONTROL_CENTER_BACKGROUND,
    ISLAND, STATUS, STATUS_SIGNAL_CUSTOMIZATION, CONTROL, LOCK, LOCKSCREEN_WIDGET_EDITOR, RASTER_WALLPAPER, SUPER_XIAOAI, CAMERA, CAMERA_PALETTE, SYSTEM_UPDATE, SYSTEM_SETTINGS, DEVICE_PROFILE,
    SETTINGS_APPEARANCE_HOME, SETTINGS_APPEARANCE_DEVICE, TUTORIAL_DEVICE_CARD, ABOUT, LICENSE, LANGUAGE, DONATE, OPEN,
    REAR_SCREEN, REAR_MUSIC_APPS, DISCLAIMER,
}

private data class ShadePresetActions(
    val userPresets: List<ShadePreset>,
    val saveUserPreset: (String) -> Unit,
    val deleteUserPreset: (ShadePreset) -> Unit,
    val importJson: () -> Unit,
    val importQr: () -> Unit,
    val exportJson: (ShadePreset) -> Unit,
    val exportQr: (ShadePreset) -> Unit,
)

private data class QrShareRequest(val name: String, val payload: String)

@Composable
private fun Root(
    hooks: HookSettingsStore,
    cameras: CameraSettingsStore,
    deviceProfiles: DeviceProfileStore,
    appearances: SettingsAppearanceStore,
) {
    val service by HookApplication.service.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(hooks.settings) }
    var cameraSettings by remember { mutableStateOf(cameras.settings) }
    var deviceProfile by remember { mutableStateOf(deviceProfiles.settings) }
    var appearance by remember { mutableStateOf(appearances.settings) }
    var languageRevision by remember { mutableIntStateOf(0) }
    var userPresets by remember { mutableStateOf(hooks.userShadePresets()) }
    val context = LocalContext.current
    val musicStore = remember(context) { MusicControlSettingsStore(context) }
    var musicWhitelist by remember { mutableStateOf(musicStore.apps) }
    fun importAppearance(slot: String, uri: Uri?) {
        if (uri == null) return
        runCatching {
            val mime = detectAppearanceMime(context, uri)
            val target = copyAppearanceFile(context, slot, uri)
            appearances.update(service) {
                when (slot) {
                    APPEARANCE_SLOT_HOME -> it.copy(homeMime = mime, homeVersion = target.lastModified())
                    APPEARANCE_SLOT_DEVICE -> it.copy(deviceMime = mime, deviceVersion = target.lastModified())
                    APPEARANCE_SLOT_DEVICE_IMAGE -> it.copy(tutorialCardImageMime = mime, tutorialCardImageVersion = target.lastModified())
                    APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO -> it.copy(tutorialCardLogoMime = mime, tutorialCardLogoVersion = target.lastModified())
                    APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND -> it.copy(tutorialCardBackgroundMime = mime, tutorialCardBackgroundVersion = target.lastModified())
                    APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE -> it.copy(style2ImageMime = mime, style2ImageVersion = target.lastModified())
                    APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO -> it.copy(style2LogoMime = mime, style2LogoVersion = target.lastModified())
                    APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND -> it.copy(style2BackgroundMime = mime, style2BackgroundVersion = target.lastModified())
                    else -> it.copy(logoMime = mime, logoVersion = target.lastModified(), logoMode = LOGO_MODE_KEEP_ADVANCED_MATERIAL)
                }
            }
            appearance = appearances.settings
            Toast.makeText(context, tr("已导入", "已导入"), Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(context, tr("导入失败", "导入失败"), Toast.LENGTH_SHORT).show() }
    }
    fun clearAppearance(slot: String) {
        runCatching {
            appearanceFile(context, slot).delete()
            appearances.update(service) {
                when (slot) {
                    APPEARANCE_SLOT_HOME -> it.copy(homeEnabled = false, homeMime = "", homeVersion = System.currentTimeMillis())
                    APPEARANCE_SLOT_DEVICE -> it.copy(deviceEnabled = false, deviceMime = "", deviceVersion = System.currentTimeMillis())
                    APPEARANCE_SLOT_DEVICE_IMAGE -> it.copy(tutorialCardEnabled = false, tutorialCardImageMime = "", tutorialCardImageVersion = System.currentTimeMillis())
                    APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO -> it.copy(tutorialCardLogoMime = "", tutorialCardLogoVersion = System.currentTimeMillis())
                    APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND -> it.copy(tutorialCardBackgroundMime = "", tutorialCardBackgroundVersion = System.currentTimeMillis())
                    APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE -> it.copy(style2ImageMime = "", style2ImageVersion = System.currentTimeMillis())
                    APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO -> it.copy(style2LogoMime = "", style2LogoVersion = System.currentTimeMillis())
                    APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND -> it.copy(style2BackgroundMime = "", style2BackgroundVersion = System.currentTimeMillis())
                    else -> it.copy(logoMime = "", logoVersion = System.currentTimeMillis(), logoMode = LOGO_MODE_SYSTEM)
                }
            }
            appearance = appearances.settings
            Toast.makeText(context, tr("已清除", "已清除"), Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(context, tr("清除失败", "清除失败"), Toast.LENGTH_SHORT).show() }
    }
    val pickAppearanceHome = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importAppearance(APPEARANCE_SLOT_HOME, it) }
    val pickAppearanceDevice = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importAppearance(APPEARANCE_SLOT_DEVICE, it) }
    val pickTutorialDeviceImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importAppearance(APPEARANCE_SLOT_DEVICE_IMAGE, it) }
    val pickStyle2DeviceImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importAppearance(APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE, it) }
    val pickStyle2UpdateBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importAppearance(APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND, it) }
    val pickStyle1UpdateBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importAppearance(APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND, it) }
    val pickAppearanceLogo = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) importAppearance(APPEARANCE_SLOT_LOGO, result.data?.data)
    }
    val pickCustomDeviceLogo = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) importAppearance(APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO, result.data?.data)
    }
    val pickStyle2DeviceLogo = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) importAppearance(APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO, result.data?.data)
    }
    val pickRasterImages = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { selected ->
        if (selected.isEmpty()) return@rememberLauncherForActivityResult
        val limited = selected.take(4)
        limited.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        hooks.update(service) { it.copy(rasterWallpaperUris = encodeRasterWallpaperUris(limited.map(Uri::toString))) }
        settings = hooks.settings
        if (selected.size > 4) Toast.makeText(context, tr("最多选择 4 个素材", "最多选择 4 个素材"), Toast.LENGTH_SHORT).show()
    }
    var pendingJsonExport by remember { mutableStateOf<ShadePreset?>(null) }
    var pendingModulePresetExport by remember { mutableStateOf<String?>(null) }
    var qrShareRequest by remember { mutableStateOf<QrShareRequest?>(null) }
    var pendingQrSave by remember { mutableStateOf<QrShareRequest?>(null) }
    val exportPreset = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val preset = pendingJsonExport ?: return@rememberLauncherForActivityResult
        pendingJsonExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(preset.payload)
            } ?: error(tr("无法写入文件", "无法写入文件"))
        }.onSuccess {
            Toast.makeText(context, tr("预设已导出", "预设已导出"), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, tr("导出失败", "导出失败"), Toast.LENGTH_SHORT).show()
        }
    }
    fun importPresetPayload(payload: String) {
        runCatching { parseShadePreset(payload) }
            .onSuccess { imported ->
                hooks.update(service) { it.importShadePreset(payload) }
                settings = hooks.settings
                imported.name?.let { hooks.saveUserShadePreset(it, settings) }
                userPresets = hooks.userShadePresets()
                Toast.makeText(context, tr("预设已导入", "预设已导入"), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, tr("导入失败", "导入失败"), Toast.LENGTH_SHORT).show()
            }
    }
    val importPreset = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error(tr("无法读取文件", "无法读取文件"))
        }.onSuccess(::importPresetPayload).onFailure {
            Toast.makeText(context, tr("导入失败", "导入失败"), Toast.LENGTH_SHORT).show()
        }
    }
    val exportModulePreset = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val payload = pendingModulePresetExport ?: return@rememberLauncherForActivityResult
        pendingModulePresetExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(payload) }
                ?: error(tr("无法写入文件", "无法写入文件"))
        }.onSuccess {
            Toast.makeText(context, tr("预设已导出", "预设已导出"), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, tr("导出失败", "导出失败"), Toast.LENGTH_SHORT).show()
        }
    }
    fun importModulePresetPayload(payload: String) {
        runCatching {
            ModulePresetCodec.import(context, service, payload)
            hooks.reload()
            cameras.reload()
            deviceProfiles.reload()
            appearances.reload()
            musicStore.reload()
            settings = hooks.settings
            cameraSettings = cameras.settings
            deviceProfile = deviceProfiles.settings
            appearance = appearances.settings
            musicWhitelist = musicStore.apps
        }.onSuccess {
            Toast.makeText(context, tr("预设已导入", "预设已导入"), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, tr("导入失败", "导入失败"), Toast.LENGTH_SHORT).show()
        }
    }
    val importModulePreset = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error(tr("无法读取文件", "无法读取文件"))
        }.onSuccess(::importModulePresetPayload).onFailure {
            Toast.makeText(context, tr("导入失败", "导入失败"), Toast.LENGTH_SHORT).show()
        }
    }
    val importQrPreset = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                ?: error(tr("无法读取图片", "无法读取图片"))
        }.map(::readPresetQrCode).onSuccess(::importPresetPayload).onFailure {
            Toast.makeText(context, tr("未识别到有效预设二维码", "未识别到有效预设二维码"), Toast.LENGTH_SHORT).show()
        }
    }
    val saveQrPreset = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png"),
    ) { uri ->
        val request = pendingQrSave ?: return@rememberLauncherForActivityResult
        pendingQrSave = null
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                createPresetQrCode(request.payload).compress(Bitmap.CompressFormat.PNG, 100, output)
            } ?: error(tr("无法写入文件", "无法写入文件"))
        }.onSuccess {
            Toast.makeText(context, tr("二维码已保存", "二维码已保存"), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, tr("保存失败", "保存失败"), Toast.LENGTH_SHORT).show()
        }
    }
    val controller = remember(settings.themeMode) {
        ThemeController(
            when (settings.themeMode) {
                "light" -> ColorSchemeMode.Light
                "dark" -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
        )
    }
    LaunchedEffect(service) {
        service?.let {
            hooks.syncRemote(it)
            cameras.syncRemote(it)
            deviceProfiles.syncRemote(it)
            appearances.syncRemote(it)
            musicStore.syncRemote(it)
            settings = hooks.settings
            cameraSettings = cameras.settings
            deviceProfile = deviceProfiles.settings
            appearance = appearances.settings
            musicWhitelist = musicStore.apps
        }
    }
    MiuixTheme(controller = controller) {
        ApplySystemBarAppearance()
        val languagePrefs = remember { context.getSharedPreferences("languages", android.content.Context.MODE_PRIVATE) }
        val strings = remember(languageRevision) { languageStrings(context, languagePrefs) }
        SideEffect { activeLanguageStrings = strings }
        CompositionLocalProvider(
            LocalLanguageChanged provides { languageRevision++ },
        ) {
        Shell(
            settings, cameraSettings, deviceProfile, appearance, musicWhitelist, service,
            update = { transform -> hooks.update(service, transform); settings = hooks.settings },
            updateCamera = { transform -> cameras.update(service, transform); cameraSettings = cameras.settings },
            updateDeviceProfile = { transform ->
                deviceProfiles.update(service, transform)
                deviceProfile = deviceProfiles.settings
            },
            updateAppearance = { transform ->
                appearances.update(service, transform)
                appearance = appearances.settings
            },
            updateMusicWhitelist = { next ->
                musicStore.update(service, next)
                musicWhitelist = musicStore.apps
            },
            onImportModulePreset = { importModulePreset.launch(arrayOf("application/json", "text/json", "text/plain")) },
            onExportModulePreset = {
                pendingModulePresetExport = ModulePresetCodec.export(context)
                exportModulePreset.launch(defaultModulePresetFileName())
            },
            presetActions = ShadePresetActions(
                userPresets = userPresets,
                saveUserPreset = { name ->
                    hooks.saveUserShadePreset(name, settings)
                    userPresets = hooks.userShadePresets()
                    Toast.makeText(context, tr("预设已保存", "预设已保存"), Toast.LENGTH_SHORT).show()
                },
                deleteUserPreset = { preset ->
                    hooks.deleteUserShadePreset(preset.name)
                    userPresets = hooks.userShadePresets()
                },
                importJson = { importPreset.launch(arrayOf("application/json", "text/plain")) },
                importQr = { importQrPreset.launch("image/*") },
                exportJson = { preset ->
                    pendingJsonExport = preset
                    exportPreset.launch("${preset.name}.json")
                },
                exportQr = { preset -> qrShareRequest = QrShareRequest(preset.name, preset.payload) },
            ),
            onPickRasterImages = { pickRasterImages.launch(arrayOf("image/*", "video/*")) },
            onApplyRasterWallpaper = {
                // HyperOS exposes CHANGE_LIVE_WALLPAPER but rejects third-party callers because
                // the picker requires the signature-only SET_WALLPAPER_COMPONENT permission.
                // Open the picker-owned list instead; the picker applies the selected service
                // with its own privileged identity after the user confirms it.
                runCatching {
                    context.startActivity(
                        Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).setPackage(
                            "com.android.wallpaper.livepicker",
                        ),
                    )
                }.onFailure { error ->
                    Toast.makeText(context, tr("无法打开动态壁纸选择器", "无法打开动态壁纸选择器"), Toast.LENGTH_SHORT).show()
                    Log.e("HyperSystemUiHook", "Could not open live wallpaper picker", error)
                }
            },
            onPickAppearanceHome = { pickAppearanceHome.launch(arrayOf("image/*", "video/*", "video/mp4", "video/webm")) },
            onPickAppearanceDevice = { pickAppearanceDevice.launch(arrayOf("image/*", "video/*", "video/mp4", "video/webm")) },
            onClearAppearanceHome = { clearAppearance(APPEARANCE_SLOT_HOME) },
            onClearAppearanceDevice = { clearAppearance(APPEARANCE_SLOT_DEVICE) },
            onPickTutorialDeviceImage = { pickTutorialDeviceImage.launch(arrayOf("image/*")) },
            onClearTutorialDeviceImage = { clearAppearance(APPEARANCE_SLOT_DEVICE_IMAGE) },
            onPickStyle2DeviceImage = { pickStyle2DeviceImage.launch(arrayOf("image/*")) },
            onClearStyle2DeviceImage = { clearAppearance(APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE) },
            onPickStyle2UpdateBackground = { pickStyle2UpdateBackground.launch(arrayOf("image/*")) },
            onClearStyle2UpdateBackground = { clearAppearance(APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND) },
            onPickStyle1UpdateBackground = { pickStyle1UpdateBackground.launch(arrayOf("image/*")) },
            onClearStyle1UpdateBackground = { clearAppearance(APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND) },
            onPickCustomDeviceLogo = {
                pickCustomDeviceLogo.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                })
            },
            onClearCustomDeviceLogo = { clearAppearance(APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO) },
            onPickStyle2DeviceLogo = {
                pickStyle2DeviceLogo.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                })
            },
            onClearStyle2DeviceLogo = { clearAppearance(APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO) },
            onPickAppearanceLogo = {
                pickAppearanceLogo.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                })
            },
            onClearAppearanceLogo = { clearAppearance(APPEARANCE_SLOT_LOGO) },
        )
        qrShareRequest?.let { request ->
            QrShareDialog(
                request = request,
                onDismiss = { qrShareRequest = null },
                onSave = {
                    pendingQrSave = request
                    saveQrPreset.launch("${request.name}.png")
                },
            )
        }
    }
}

}

@Composable
private fun ApplySystemBarAppearance() {
    val activity = LocalContext.current as? Activity ?: return
    val view = LocalView.current
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    SideEffect {
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }
}

@Composable
private fun Shell(
    settings: HookSettings,
    cameras: CameraSettings,
    deviceProfile: DeviceProfileSettings,
    appearance: SettingsAppearanceSettings,
    musicWhitelist: Set<String>,
    service: XposedService?,
    update: ((HookSettings) -> HookSettings) -> Unit,
    updateCamera: ((CameraSettings) -> CameraSettings) -> Unit,
    updateDeviceProfile: ((DeviceProfileSettings) -> DeviceProfileSettings) -> Unit,
    updateAppearance: ((SettingsAppearanceSettings) -> SettingsAppearanceSettings) -> Unit,
    updateMusicWhitelist: (Set<String>) -> Unit,
    onImportModulePreset: () -> Unit,
    onExportModulePreset: () -> Unit,
    presetActions: ShadePresetActions,
    onPickRasterImages: () -> Unit,
    onApplyRasterWallpaper: () -> Unit,
    onPickAppearanceHome: () -> Unit,
    onPickAppearanceDevice: () -> Unit,
    onClearAppearanceHome: () -> Unit,
    onClearAppearanceDevice: () -> Unit,
    onPickTutorialDeviceImage: () -> Unit,
    onClearTutorialDeviceImage: () -> Unit,
    onPickStyle1UpdateBackground: () -> Unit,
    onClearStyle1UpdateBackground: () -> Unit,
    onPickStyle2DeviceImage: () -> Unit,
    onClearStyle2DeviceImage: () -> Unit,
    onPickStyle2UpdateBackground: () -> Unit,
    onClearStyle2UpdateBackground: () -> Unit,
    onPickCustomDeviceLogo: () -> Unit,
    onClearCustomDeviceLogo: () -> Unit,
    onPickStyle2DeviceLogo: () -> Unit,
    onClearStyle2DeviceLogo: () -> Unit,
    onPickAppearanceLogo: () -> Unit,
    onClearAppearanceLogo: () -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.CATEGORY) }
    val osCode = remember { OsCompatibility.versionCode() }
    var showOsWarning by remember { mutableStateOf(false) }
    var showOsMissing by remember { mutableStateOf(false) }
    var showDebug by remember { mutableStateOf(false) }
    var debugPreview by remember { mutableStateOf<String?>(null) }
    var disclaimerRead by remember { mutableStateOf(false) }
    val captcha = remember { (100000..999999).random().toString() }
    var captchaInput by remember { mutableStateOf("") }
    var exitSeconds by remember { mutableIntStateOf(10) }
    LaunchedEffect(osCode) {
        if (osCode.isBlank()) {
            showOsMissing = true
            while (exitSeconds > 0) {
                delay(1000)
                exitSeconds--
            }
            (context as? Activity)?.finishAndRemoveTask()
        } else if (osCode != "4") {
            showOsWarning = true
        }
    }
    val pageStack = remember { mutableStateListOf<PageId>() }
    val page = pageStack.lastOrNull()
    var suppressPageBack by remember { mutableStateOf(false) }
    var retainedPage by remember { mutableStateOf<PageId?>(null) }
    var navigatingForward by remember { mutableStateOf(true) }
    if (page != null) retainedPage = page
    val openRootPage: (PageId) -> Unit = { target ->
        navigatingForward = true
        pageStack.clear()
        pageStack += target
        if (target == PageId.DISCLAIMER) disclaimerRead = true
    }
    val openNestedPage: (PageId) -> Unit = { target ->
        if (pageStack.lastOrNull() != target) {
            navigatingForward = true
            pageStack += target
            if (target == PageId.DISCLAIMER) disclaimerRead = true
        }
    }
    val dismissPage: () -> Unit = {
        val leavingDisclaimer = pageStack.lastOrNull() == PageId.DISCLAIMER
        if (pageStack.size > 1) {
            navigatingForward = false
            pageStack.removeAt(pageStack.lastIndex)
        } else {
            pageStack.clear()
        }
        if (leavingDisclaimer && (debugPreview == "non4" || (osCode.isNotBlank() && osCode != "4"))) {
            showOsWarning = true
        }
        Unit
    }
    val backState = rememberMomentumPredictiveBack(
        enabled = page != null && settings.predictiveBackEnabled && !suppressPageBack,
        maxProgress = settings.predictiveBackProgress.coerceIn(10, 100) / 100f,
        onBack = dismissPage
    )
    LaunchedEffect(pageStack.size) {
        if (pageStack.isNotEmpty()) backState.reset()
    }
    BackHandler(enabled = page != null && !settings.predictiveBackEnabled && !suppressPageBack, onBack = dismissPage)
    val backdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize()) {
        // The backdrop must only record page content. Recording the navigation that consumes it
        // creates a RenderNode cycle and crashes HyperOS's RenderThread.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            when (tab) {
                Tab.CATEGORY -> CategoryHome(
                    connected = service != null,
                    open = openRootPage,
                )
                Tab.SETTINGS -> SettingsHome(
                    settings,
                    service != null,
                    update,
                    openRootPage,
                    onImportModulePreset,
                    onExportModulePreset,
                )
            }
        }
        BottomBar(tab, { tab = it }, settings, backdrop, Modifier.align(Alignment.BottomCenter))
        AnimatedVisibility(
            visible = page != null,
            enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 5 } + fadeIn(tween(260)) + scaleIn(tween(300), initialScale = .97f),
            exit = slideOutHorizontally(tween(260)) { it / 8 } + fadeOut(tween(220)) + scaleOut(tween(260), targetScale = .985f)
        ) {
            Box(Modifier.fillMaxSize().momentumBackTransform(backState)) {
                AnimatedContent(
                    targetState = page ?: retainedPage,
                    transitionSpec = {
                        if (navigatingForward) {
                            (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { it / 6 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(220)) { -it / 10 } + fadeOut(tween(180)))
                        } else {
                            (slideInHorizontally(tween(260, easing = FastOutSlowInEasing)) { -it / 10 } + fadeIn(tween(200))) togetherWith
                                (slideOutHorizontally(tween(240)) { it / 6 } + fadeOut(tween(180)))
                        }
                    },
                    label = "detailPageNavigation",
                ) { detailPage ->
                    detailPage?.let {
                        CompositionLocalProvider(LocalPageBackSuppressed provides { suppressPageBack = it }) {
                        Detail(
                            it, settings, cameras, deviceProfile, appearance, musicWhitelist, update, updateCamera, updateDeviceProfile, updateAppearance, updateMusicWhitelist, presetActions,
                            openPage = openNestedPage, back = dismissPage,
                            onDebugMode = { showDebug = true },
                            captcha = captcha,
                            onPickRasterImages = onPickRasterImages,
                            onApplyRasterWallpaper = onApplyRasterWallpaper,
                            onPickAppearanceHome = onPickAppearanceHome,
                            onPickAppearanceDevice = onPickAppearanceDevice,
                            onClearAppearanceHome = onClearAppearanceHome,
                            onClearAppearanceDevice = onClearAppearanceDevice,
                            onPickTutorialDeviceImage = onPickTutorialDeviceImage,
                            onClearTutorialDeviceImage = onClearTutorialDeviceImage,
                            onPickStyle1UpdateBackground = onPickStyle1UpdateBackground,
                            onClearStyle1UpdateBackground = onClearStyle1UpdateBackground,
                            onPickStyle2DeviceImage = onPickStyle2DeviceImage,
                            onClearStyle2DeviceImage = onClearStyle2DeviceImage,
                            onPickStyle2UpdateBackground = onPickStyle2UpdateBackground,
                            onClearStyle2UpdateBackground = onClearStyle2UpdateBackground,
                            onPickCustomDeviceLogo = onPickCustomDeviceLogo,
                            onClearCustomDeviceLogo = onClearCustomDeviceLogo,
                            onPickStyle2DeviceLogo = onPickStyle2DeviceLogo,
                            onClearStyle2DeviceLogo = onClearStyle2DeviceLogo,
                            onPickAppearanceLogo = onPickAppearanceLogo,
                            onClearAppearanceLogo = onClearAppearanceLogo,
                        )
                    }
                }
            }
        }
        OsWarningDialog(
            show = page != PageId.DISCLAIMER && (showOsWarning || debugPreview == "non4"),
            version = if (debugPreview == "non4") "3" else osCode,
            captcha = captcha,
            captchaInput = captchaInput,
            disclaimerRead = disclaimerRead,
            preview = debugPreview == "non4",
            onCaptchaInput = { captchaInput = it.filter(Char::isDigit).take(6) },
            onDisclaimer = { showOsWarning = false; openNestedPage(PageId.DISCLAIMER) },
            onConfirm = { if (disclaimerRead && captchaInput == captcha) { showOsWarning = false; debugPreview = null } },
            onExit = { (context as? Activity)?.finishAndRemoveTask() },
        )
        OsMissingDialog(
            show = showOsMissing || debugPreview == "empty",
            seconds = if (debugPreview == "empty") 10 else exitSeconds,
            preview = debugPreview == "empty",
            onExit = { if (debugPreview == "empty") debugPreview = null else (context as? Activity)?.finishAndRemoveTask() },
        )
        if (showDebug) {
            DebugModeDialog(
                onDismiss = { showDebug = false },
                onNonHyperOs4 = { showDebug = false; debugPreview = "non4" },
                onNonHyperOs = { showDebug = false; debugPreview = "empty" },
            )
        }
    }
}

}

@Composable
private fun BottomBar(
    tab: Tab,
    select: (Tab) -> Unit,
    settings: HookSettings,
    backdrop: Backdrop,
    modifier: Modifier
) {
    val view = LocalView.current
    val index = remember { mutableIntStateOf(tab.ordinal) }
    LaunchedEffect(tab) { index.intValue = tab.ordinal }
    val tabs = Tab.entries.mapIndexed { i, item ->
        val title = if (item == Tab.CATEGORY) {
            tr("tab_category", tr("分类", "分类"))
        } else {
            tr("tab_settings", tr("设置", "设置"))
        }
        HostTab(title, "os4.$i") { index.intValue = i; select(item) }
    }
    Box(
        modifier.fillMaxWidth().height(if (settings.navigationStyle == "hyper_os") 76.dp else 100.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        CustomNavigation(
            sourceView = view,
            tabs = tabs,
            selectedIndex = index,
            blurRadius = if (settings.navigationStyle == "liquid_glass") 3 else 18,
            labelMode = settings.navigationLabelMode,
            navigationStyle = settings.navigationStyle,
            advancedMaterial = true,
            colorMode = settings.themeMode,
            liquidBottomSpacingDp = 0,
            onHostPreDraw = {},
            backdropOverride = backdrop,
            tabImageVector = { i -> if (i == Tab.CATEGORY.ordinal) MiuixIcons.Regular.All else MiuixIcons.Regular.Settings }
        )
    }
}

@Composable
private fun CategoryHome(
    connected: Boolean,
    open: (PageId) -> Unit,
) = AppPage(
    "HyperChanger",
    restartScopes = ScopeApplication.entries.toSet(),
    restartEnabled = connected,
) { padding, scroll ->
    AppList(padding, scroll) {
        item { Entry(tr("\u7cfb\u7edf\u754c\u9762", "\u7cfb\u7edf\u754c\u9762"), enabled = connected) { open(PageId.SHADE) } }
        item { Entry(tr("\u8d85\u7ea7\u5c9b", "\u8d85\u7ea7\u5c9b"), enabled = connected) { open(PageId.ISLAND) } }
        item { Entry(tr("\u72b6\u6001\u680f", "\u72b6\u6001\u680f"), enabled = connected) { open(PageId.STATUS) } }
        item { Entry(tr("\u9501\u5c4f", "\u9501\u5c4f"), enabled = connected) { open(PageId.LOCK) } }
        item { Entry(tr("\u8d85\u7ea7\u5c0f\u7231\u8f93\u5165\u6cd5", "\u8d85\u7ea7\u5c0f\u7231\u8f93\u5165\u6cd5"), enabled = connected) { open(PageId.SUPER_XIAOAI) } }
        item { Entry(tr("\u76f8\u673a\u4e0e\u76f8\u518c\u7f16\u8f91", "\u76f8\u673a\u4e0e\u76f8\u518c\u7f16\u8f91"), enabled = connected) { open(PageId.CAMERA) } }
        item { Entry(tr("\u7cfb\u7edf\u66f4\u65b0", "\u7cfb\u7edf\u66f4\u65b0"), enabled = connected) { open(PageId.SYSTEM_UPDATE) } }
        item { Entry(tr("\u7cfb\u7edf\u8bbe\u7f6e", "\u7cfb\u7edf\u8bbe\u7f6e"), enabled = connected) { open(PageId.SYSTEM_SETTINGS) } }
        item { Entry(tr("\u80cc\u5c4f", "\u80cc\u5c4f"), enabled = connected) { open(PageId.REAR_SCREEN) } }
    }
}

private data class RearApp(
    val info: ApplicationInfo,
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
)

@Composable
private fun rememberRearApps(): List<RearApp> {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val apps by produceState<List<RearApp>>(initialValue = emptyList(), packageManager) {
        value = withContext(Dispatchers.IO) {
            val rootPackages = runRootPackageList()
            val installed = if (rootPackages.isNotEmpty()) {
                rootPackages.mapNotNull { packageName ->
                    runCatching { packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA) }.getOrNull()
                }.ifEmpty { packageManager.getInstalledApplications(PackageManager.GET_META_DATA) }
            } else {
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            }
            installed.distinctBy { it.packageName }
                .map { info ->
                    RearApp(
                        info = info,
                        label = info.loadLabel(packageManager).toString(),
                        packageName = info.packageName,
                        isSystem = (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                    )
                }
                .sortedWith(compareBy<RearApp, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.packageName })
        }
    }
    return apps
}

private fun runRootPackageList(): Set<String> = runCatching {
    val process = ProcessBuilder("su", "-c", "pm list packages --user 0")
        .redirectErrorStream(true)
        .start()
    if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly()
        return@runCatching emptySet()
    }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    output.lineSequence()
        .mapNotNull { line -> line.trim().removePrefix("package:").takeIf { it.isNotEmpty() } }
        .toSet()
}.getOrDefault(emptySet())

@Composable
private fun RearScreen(
    selectedPackages: Set<String>,
    onSelectedPackagesChange: (Set<String>) -> Unit,
    open: (PageId) -> Unit,
    back: () -> Unit,
) = AppPage(tr("\u80cc\u5c4f", "\u80cc\u5c4f"), back, restartScopes = setOf(ScopeApplication.SUBSCREEN_CENTER)) { padding, scroll ->
    val apps = rememberRearApps()
    AppList(padding, scroll, 28) {
        item {
            Group(tr("\u80cc\u5c4f\u97f3\u4e50\u63a7\u4ef6", "\u80cc\u5c4f\u97f3\u4e50\u63a7\u4ef6")) {
                ArrowPreference(
                    title = tr("\u6dfb\u52a0\u5e94\u7528\u5230\u80cc\u5c4f\u97f3\u4e50\u63a7\u4ef6\u767d\u540d\u5355", "\u6dfb\u52a0\u5e94\u7528\u5230\u80cc\u5c4f\u97f3\u4e50\u63a7\u4ef6\u767d\u540d\u5355"),
                    onClick = { open(PageId.REAR_MUSIC_APPS) },
                )
            }
        }
        item {
            Group(tr("\u5df2\u7ecf\u6dfb\u52a0\u7684\u5e94\u7528", "\u5df2\u7ecf\u6dfb\u52a0\u7684\u5e94\u7528")) {
                val selected = apps.filter { it.packageName in selectedPackages }
                if (selected.isEmpty()) {
                    Text(
                        tr("\u6682\u65e0\u5df2\u6dfb\u52a0\u7684\u5e94\u7528", "\u6682\u65e0\u5df2\u6dfb\u52a0\u7684\u5e94\u7528"),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    selected.forEach { app ->
                        RearAppCard(
                            app = app,
                            checked = true,
                            onCheckedChange = {
                                onSelectedPackagesChange(selectedPackages - app.packageName)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RearMusicApps(
    selectedPackages: Set<String>,
    onSelectedPackagesChange: (Set<String>) -> Unit,
    back: () -> Unit,
) {
    val apps = rememberRearApps()
    var query by rememberSaveable { mutableStateOf("") }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    val normalizedQuery = query.trim().lowercase(Locale.getDefault())
    val visibleApps = remember(apps, selectedPackages, normalizedQuery, showSystemApps) {
        apps.filter { app ->
            (showSystemApps || !app.isSystem) &&
                (normalizedQuery.isEmpty() ||
                    app.label.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
                    app.packageName.lowercase(Locale.getDefault()).contains(normalizedQuery))
        }.sortedWith(compareByDescending<RearApp> { it.packageName in selectedPackages }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
            .thenBy { it.packageName })
    }
    AppPage(
        title = tr("\u80cc\u5c4f\u97f3\u4e50\u63a7\u4ef6\u767d\u540d\u5355", "\u80cc\u5c4f\u97f3\u4e50\u63a7\u4ef6\u767d\u540d\u5355"),
        onBack = back,
        content = { padding, scroll ->
            AppList(padding, scroll, 28) {
                item {
                    RearAppSearchBar(query = query, onQueryChange = { query = it })
                }
                if (visibleApps.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                            Text(
                                tr("\u6ca1\u6709\u5339\u914d\u7684\u5e94\u7528", "\u6ca1\u6709\u5339\u914d\u7684\u5e94\u7528"),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    items(visibleApps, key = { it.packageName }) { app ->
                        val checked = app.packageName in selectedPackages
                        RearAppCard(
                            app = app,
                            checked = checked,
                            onCheckedChange = {
                                onSelectedPackagesChange(
                                    if (checked) selectedPackages - app.packageName
                                    else selectedPackages + app.packageName,
                                )
                            },
                        )
                    }
                }
            }
        },
        actions = {
            GlassIconDropdownMenu(
                entry = DropdownEntry(
                    items = listOf(
                        DropdownItem(
                            text = if (showSystemApps) tr("\u9690\u85cf\u7cfb\u7edf\u5e94\u7528", "\u9690\u85cf\u7cfb\u7edf\u5e94\u7528") else tr("\u663e\u793a\u7cfb\u7edf\u5e94\u7528", "\u663e\u793a\u7cfb\u7edf\u5e94\u7528"),
                            selected = showSystemApps,
                            onClick = { showSystemApps = !showSystemApps },
                        ),
                    ),
                ),
                backdrop = LocalToolbarBackdrop.current,
            ) {
                Icon(
                    imageVector = MiuixIcons.MoreCircle,
                    contentDescription = tr("\u66f4\u591a", "\u66f4\u591a"),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    )
}

@Composable
private fun RearAppSearchBar(query: String, onQueryChange: (String) -> Unit) {
    SearchBar(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = DpSize(0.dp, 0.dp),
        inputField = {
            InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = tr("\u641c\u7d22\u5e94\u7528\u540d\u6216\u5305\u540d", "\u641c\u7d22\u5e94\u7528\u540d\u6216\u5305\u540d"),
            )
        },
        onExpandedChange = {},
        expanded = false,
    ) {}
}

@Composable
private fun RearAppCard(
    app: RearApp,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    val packageManager = LocalContext.current.packageManager
    Card(
        modifier = Modifier.fillMaxWidth().height(92.dp),
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        onClick = onCheckedChange,
        showIndication = true,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RearAppIcon(packageManager, app.info)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Checkbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                onClick = onCheckedChange,
            )
        }
    }
}

@Composable
private fun RearAppIcon(packageManager: PackageManager, info: ApplicationInfo) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, info.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val drawable = info.loadIcon(packageManager)
                val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                    drawable.bitmap
                } else {
                    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
                    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1
                    createBitmap(width, height).also { created ->
                        val canvas = Canvas(created)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                    }
                }
                bitmap.asImageBitmap()
            }.getOrNull()
        }
    }
    if (bitmap != null) {
        Image(bitmap = bitmap!!, contentDescription = null, modifier = Modifier.size(44.dp))
    } else {
        Spacer(Modifier.size(44.dp))
    }
}

@Composable
private fun SettingsHome(
    settings: HookSettings,
    online: Boolean,
    update: ((HookSettings) -> HookSettings) -> Unit,
    open: (PageId) -> Unit,
    onImportModulePreset: () -> Unit,
    onExportModulePreset: () -> Unit,
) = AppPage(tr("settings", tr("设置", "设置"))) { padding, scroll ->
    val context = LocalContext.current
    var predictiveProgress by remember(settings.predictiveBackProgress) { mutableFloatStateOf(settings.predictiveBackProgress.toFloat()) }
    var showScopeRestartDialog by remember { mutableStateOf(false) }
    AppList(padding, scroll) {
        item { ServiceCard(online) }
        item {
            Group(tr("application_settings", tr("\u5e94\u7528\u8bbe\u7f6e", "\u5e94\u7528\u8bbe\u7f6e"))) {
                ArrowPreference(
                    title = tr("restart_scope_apps", tr("\u91cd\u542f\u4f5c\u7528\u57df\u5e94\u7528", "\u91cd\u542f\u4f5c\u7528\u57df\u5e94\u7528")),
                    onClick = { showScopeRestartDialog = true },
                )
                val languagePrefs = remember { context.getSharedPreferences("languages", android.content.Context.MODE_PRIVATE) }
                val selectedLanguage = languagePrefs.getString("selected", "system") ?: "system"
                val selectedLanguageSummary = when (selectedLanguage) {
                    "system" -> tr("followSystem", "跟随系统")
                    "zh" -> tr("chinese", "中文")
                    "en" -> tr("english", "English")
                    "ja" -> tr("japanese", "日本語")
                    else -> loadLanguagePacks(languagePrefs).firstOrNull { it.name == selectedLanguage }?.name ?: selectedLanguage
                }
                ArrowPreference(title = tr("language", "语言"), summary = selectedLanguageSummary, onClick = { open(PageId.LANGUAGE) })
OverlayDropdownPreference(
                    title = tr("theme_mode", tr("\u4e3b\u9898\u6a21\u5f0f", "\u4e3b\u9898\u6a21\u5f0f")),
                    items = listOf(tr("followSystem", tr("\u8ddf\u968f\u7cfb\u7edf", "\u8ddf\u968f\u7cfb\u7edf")), tr("light_mode", tr("\u6d45\u8272\u6a21\u5f0f", "\u6d45\u8272\u6a21\u5f0f")), tr("dark_mode", tr("\u6df1\u8272\u6a21\u5f0f", "\u6df1\u8272\u6a21\u5f0f"))),
                    selectedIndex = listOf("system", "light", "dark").indexOf(settings.themeMode).coerceAtLeast(0),
                    onSelectedIndexChange = { i -> update { it.copy(themeMode = listOf("system", "light", "dark")[i]) } }
                )
OverlayDropdownPreference(
                    title = tr("navigation_style", tr("\u5e95\u90e8\u5bfc\u822a\u680f\u6837\u5f0f", "\u5e95\u90e8\u5bfc\u822a\u680f\u6837\u5f0f")),
                    items = listOf(tr("hyperos_bar", tr("HyperOS \u5e95\u680f", "HyperOS \u5e95\u680f")), tr("hyperos_floating_bar", tr("HyperOS \u60ac\u6d6e\u5e95\u680f", "HyperOS \u60ac\u6d6e\u5e95\u680f")), tr("liquid_glass_bar", tr("\u6db2\u6001\u73bb\u7483\u5e95\u680f", "\u6db2\u6001\u73bb\u7483\u5e95\u680f"))),
                    selectedIndex = listOf("hyper_os", "hyper_os_floating", "liquid_glass").indexOf(settings.navigationStyle).coerceAtLeast(0),
                    onSelectedIndexChange = { i -> update { it.copy(navigationStyle = listOf("hyper_os", "hyper_os_floating", "liquid_glass")[i]) } }
                )
                OverlayDropdownPreference(
                    title = tr("\u5e95\u90e8\u5bfc\u822a\u680f\u6807\u7b7e\u663e\u793a\u65b9\u5f0f", "\u5e95\u90e8\u5bfc\u822a\u680f\u6807\u7b7e\u663e\u793a\u65b9\u5f0f"),
                    items = LabelMode.entries.map { mode ->
                        when (mode) {
                            LabelMode.ICON_AND_TEXT -> tr("nav_label_icon_text", "图标和文本")
                            LabelMode.ICON_ONLY -> tr("nav_label_icon_only", "仅图标")
                            LabelMode.TEXT_ONLY -> tr("nav_label_text_only", "仅文本")
                        }
                    },
                    selectedIndex = LabelMode.entries.indexOfFirst { it.preferenceValue == settings.navigationLabelMode }.coerceAtLeast(0),
                    onSelectedIndexChange = { i -> update { it.copy(navigationLabelMode = LabelMode.entries[i].preferenceValue) } },
                )
                SwitchPreference(
                    title = tr("\u9884\u6d4b\u6027\u8fd4\u56de\u52a8\u753b", "\u9884\u6d4b\u6027\u8fd4\u56de\u52a8\u753b"),
                    checked = settings.predictiveBackEnabled,
                    onCheckedChange = { value -> update { it.copy(predictiveBackEnabled = value) } }
                )
                if (settings.predictiveBackEnabled) {
                    SliderPreference(
                        value = predictiveProgress,
                        onValueChange = { predictiveProgress = it },
                        onValueChangeFinished = { update { it.copy(predictiveBackProgress = predictiveProgress.toInt()) } },
                        title = tr("\u9884\u6d4b\u6027\u8fd4\u56de\u52a8\u753b\u6700\u5927\u8fdb\u5ea6", "\u9884\u6d4b\u6027\u8fd4\u56de\u52a8\u753b\u6700\u5927\u8fdb\u5ea6"),
                        valueText = "${predictiveProgress.toInt()}%",
                        valueRange = 10f..100f,
                        steps = 89
                    )
                }
            }
        }
        item {
            Group(tr("\u5e94\u7528\u9884\u8bbe", "\u5e94\u7528\u9884\u8bbe")) {
                ArrowPreference(title = tr("\u5bfc\u5165\u9884\u8bbe", "\u5bfc\u5165\u9884\u8bbe"), onClick = onImportModulePreset)
                ArrowPreference(title = tr("\u5bfc\u51fa\u9884\u8bbe", "\u5bfc\u51fa\u9884\u8bbe"), onClick = onExportModulePreset)
            }
        }
        item {
            Group(tr("\u5e94\u7528\u4fe1\u606f", "\u5e94\u7528\u4fe1\u606f")) {
                ArrowPreference(title = tr("\u5173\u4e8e", "\u5173\u4e8e"), onClick = { open(PageId.ABOUT) })
                ArrowPreference(title = tr("\u6350\u8d60", "\u6350\u8d60"), onClick = { open(PageId.DONATE) })
                ArrowPreference(title = tr("\u5f00\u6e90\u4ee3\u7801\u58f0\u660e", "\u5f00\u6e90\u4ee3\u7801\u58f0\u660e"), onClick = { open(PageId.OPEN) })
                ArrowPreference(title = tr("\u672c\u9879\u76ee\u57fa\u4e8e MIUIX \u6784\u5efa", "\u672c\u9879\u76ee\u57fa\u4e8e MIUIX \u6784\u5efa"), onClick = { openUrl(context, "https://compose-miuix-ui.github.io/miuix/") })
            }
        }
    }
    RestartScopeDialog(
        show = showScopeRestartDialog,
        onDismiss = { showScopeRestartDialog = false },
        onRestart = { targets ->
            SystemUiRestarter.restart(context, targets)
            showScopeRestartDialog = false
        },
    )
}

@Composable
private fun RestartScopeDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onRestart: (Set<ScopeApplication>) -> Unit,
    availableTargets: Set<ScopeApplication> = ScopeApplication.entries.toSet(),
) {
    var selectedTargets by remember(show) {
        mutableStateOf<Set<ScopeApplication>>(emptySet())
    }
    WindowDialog(
        show = show,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                tr("\u91cd\u542f\u4f5c\u7528\u57df\u5e94\u7528", "\u91cd\u542f\u4f5c\u7528\u57df\u5e94\u7528"),
                modifier = Modifier.fillMaxWidth(),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start,
            )
            Text(
                tr("\u9009\u62e9\u9700\u8981\u91cd\u542f\u7684\u5e94\u7528", "\u9009\u62e9\u9700\u8981\u91cd\u542f\u7684\u5e94\u7528"),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Start,
            )
            ScopeRestartCheckboxes(
                selectedTargets = selectedTargets,
                onSelectedTargetsChange = { selectedTargets = it },
                availableTargets = availableTargets,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("\u53d6\u6d88", "\u53d6\u6d88")) }
                GlassDialogButton(
                    onClick = { onRestart(selectedTargets) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    enabled = selectedTargets.isNotEmpty(),
                ) { Text(tr("\u91cd\u542f", "\u91cd\u542f")) }
            }
        }
    }
}

@Composable
private fun ScopeRestartCheckboxes(
    selectedTargets: Set<ScopeApplication>,
    onSelectedTargetsChange: (Set<ScopeApplication>) -> Unit,
    availableTargets: Set<ScopeApplication> = ScopeApplication.entries.toSet(),
) {
    ScopeApplication.entries.filter { it in availableTargets }.forEach { target ->
        val toggle = {
            onSelectedTargetsChange(
                if (target in selectedTargets) selectedTargets - target else selectedTargets + target,
            )
        }
        Row(
            Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                state = if (target in selectedTargets) ToggleableState.On else ToggleableState.Off,
                onClick = toggle,
            )
            Text(
                tr(target.title, target.title),
                style = MiuixTheme.textStyles.body1,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun SystemSettings(
    profile: DeviceProfileSettings,
    update: ((DeviceProfileSettings) -> DeviceProfileSettings) -> Unit,
    appearance: SettingsAppearanceSettings,
    updateAppearance: ((SettingsAppearanceSettings) -> SettingsAppearanceSettings) -> Unit,
    open: (PageId) -> Unit,
    onPickLogo: () -> Unit,
    onClearLogo: () -> Unit,
    back: () -> Unit,
) = AppPage(tr("系统设置", "系统设置"), back, restartScopes = setOf(ScopeApplication.SETTINGS)) { padding, scroll ->
    AppList(padding, scroll) {
        item {
            Group(tr("设置应用", "设置应用")) {
                SwitchPreference(
                    title = tr("启用设备信息覆盖", "启用设备信息覆盖"),
                    checked = profile.enabled,
                    onCheckedChange = { enabled -> update { it.copy(enabled = enabled) } },
                )
                SwitchPreference(
                    title = tr("使用骁龙处理器图标", "使用骁龙处理器图标"),
                    checked = profile.snapdragonIcon,
                    onCheckedChange = { enabled -> update { it.copy(snapdragonIcon = enabled) } },
                )
            }
        }
        item {
            SmallTitle(tr("界面自定义", "界面自定义"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(title = tr("自定义设置主界面背景图", "自定义设置主界面背景图"), onClick = { open(PageId.SETTINGS_APPEARANCE_HOME) })
                ArrowPreference(title = tr("自定义我的设备界面背景图", "自定义我的设备界面背景图"), onClick = { open(PageId.SETTINGS_APPEARANCE_DEVICE) })
                ArrowPreference(title = tr("自定义我的设备界面", "自定义我的设备界面"), onClick = { open(PageId.TUTORIAL_DEVICE_CARD) })
                if (appearance.deviceInterfaceStyle == DEVICE_INTERFACE_STYLE_SYSTEM) {
OverlayDropdownPreference(
                        title = tr("自定义LOGO", "自定义LOGO"),
                        items = listOf(tr("系统默认", "系统默认"), tr("不保留高级材质", "不保留高级材质"), tr("保留高级材质（需要导入SVG/XML）", "保留高级材质（需要导入SVG/XML）")),
                        selectedIndex = appearance.logoMode,
                        onSelectedIndexChange = { index -> updateAppearance { it.copy(logoMode = index) } },
                    )
                }
                if (appearance.deviceInterfaceStyle == DEVICE_INTERFACE_STYLE_SYSTEM && appearance.logoMode != LOGO_MODE_SYSTEM) {
                    ArrowPreference(
                        title = tr("导入LOGO", "导入LOGO"),
                        summary = appearance.logoMime.ifBlank { tr("未导入", "未导入") },
                        onClick = onPickLogo,
                    )
                    ArrowPreference(
                        title = tr("清除LOGO", "清除LOGO"),
                        summary = if (appearance.logoMime.isBlank()) tr("无LOGO", "无LOGO") else tr("已导入", "已导入"),
                        onClick = onClearLogo,
                    )
                    SliderPreference(
                        value = appearance.logoScale.toFloat(),
                        onValueChange = { value -> updateAppearance { it.copy(logoScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("LOGO缩放", "LOGO缩放"),
                        valueText = "${appearance.logoScale}%",
                        valueRange = 50f..200f,
                        steps = 149,
                    )
                }
            }
        }
        item {
            Group(tr("设备信息", "设备信息")) {
                ArrowPreference(title = tr("我的设备与全部参数", "我的设备与全部参数"), onClick = { open(PageId.DEVICE_PROFILE) })
            }
        }
    }
}

@Composable
private fun DeviceProfileEditor(
    profile: DeviceProfileSettings,
    update: ((DeviceProfileSettings) -> DeviceProfileSettings) -> Unit,
    back: () -> Unit,
) = AppPage(tr("我的设备与全部参数", "我的设备与全部参数"), back) { padding, scroll ->
    var editingField by remember { mutableStateOf<ProfileField?>(null) }
    val basicFields = listOf(
        tr("手机型号", "手机型号") to profile.model,
        tr("处理器", "处理器") to profile.processor,
        tr("运行内存", "运行内存") to profile.ram,
        tr("电池容量", "电池容量") to profile.battery,
        tr("屏幕尺寸", "屏幕尺寸") to profile.screenSize,
        tr("分辨率", "分辨率") to profile.resolution,
    )
    val allInfoFields = listOf(
        tr("处理器", "处理器") to profile.detailProcessor,
        tr("摄像头", "摄像头") to profile.camera,
        tr("OS 版本", "OS 版本") to profile.osVersion,
        tr("Android 版本", "Android 版本") to profile.androidVersion,
        tr("内部存储", "内部存储") to profile.storage,
        tr("内核版本", "内核版本") to profile.kernel,
        tr("基带版本", "基带版本") to profile.baseband,
        tr("硬件版本", "硬件版本") to profile.hardware,
    )
    val imagingFields = listOf(
        tr("后摄", "后摄") to profile.cameraRear,
        tr("前摄", "前摄") to profile.cameraFront,
    )
    AppList(padding, scroll) {
        item {
            Group(tr("基础参数", "基础参数")) {
                basicFields.forEach { (label, value) ->
                    ArrowPreference(
                        title = label,
                        summary = value.ifBlank { tr("未设置", "未设置") },
                        onClick = { editingField = ProfileField(title = label, value = value, setter = { p, v ->
                            when (label) {
                                tr("手机型号", "手机型号") -> p.copy(model = v)
                                tr("处理器", "处理器") -> p.copy(processor = v)
                                tr("运行内存", "运行内存") -> p.copy(ram = v)
                                tr("电池容量", "电池容量") -> p.copy(battery = v)
                                tr("屏幕尺寸", "屏幕尺寸") -> p.copy(screenSize = v)
                                else -> p.copy(resolution = v)
                            }
                        }) },
                    )
                }
            }
        }
        item {
            Group(tr("全部参数与信息", "全部参数与信息")) {
                allInfoFields.forEach { (label, value) ->
                    ArrowPreference(
                        title = label,
                        summary = value.ifBlank { tr("未设置", "未设置") },
                        onClick = { editingField = ProfileField(title = label, value = value, setter = { p, v ->
                            when (label) {
                                tr("处理器", "处理器") -> p.copy(detailProcessor = v)
                                tr("摄像头", "摄像头") -> p.copy(camera = v)
                                tr("OS 版本", "OS 版本") -> p.copy(osVersion = v)
                                tr("Android 版本", "Android 版本") -> p.copy(androidVersion = v)
                                tr("内部存储", "内部存储") -> p.copy(storage = v)
                                tr("内核版本", "内核版本") -> p.copy(kernel = v)
                                tr("基带版本", "基带版本") -> p.copy(baseband = v)
                                else -> p.copy(hardware = v)
                            }
                        }) },
                    )
                }
            }
        }
        item {
            Group(tr("我的设备（影像参数）", "我的设备（影像参数）")) {
                imagingFields.forEach { (label, value) ->
                    ArrowPreference(
                        title = label,
                        summary = value.ifBlank { tr("未设置", "未设置") },
                        onClick = { editingField = ProfileField(title = label, value = value, setter = { p, v ->
                            if (label == tr("后摄", "后摄")) p.copy(cameraRear = v) else p.copy(cameraFront = v)
                        }) },
                    )
                }
            }
        }
    }

    editingField?.let { field ->
        DeviceProfileFieldDialog(
            title = field.title,
            current = field.value,
            onDismiss = { editingField = null },
            onSave = { value ->
                update { field.setter(it, value) }
                editingField = null
            },
        )
    }
}

private data class ProfileField(
    val title: String,
    val value: String,
    val setter: (DeviceProfileSettings, String) -> DeviceProfileSettings,
)

@Composable
private fun TutorialDeviceCardSettings(
    appearance: SettingsAppearanceSettings,
    update: ((SettingsAppearanceSettings) -> SettingsAppearanceSettings) -> Unit,
    onPickSystemLogo: () -> Unit,
    onClearSystemLogo: () -> Unit,
    onPickStyle1Image: () -> Unit,
    onClearStyle1Image: () -> Unit,
    onPickStyle1Logo: () -> Unit,
    onClearStyle1Logo: () -> Unit,
    onPickStyle1Background: () -> Unit,
    onClearStyle1Background: () -> Unit,
    onPickStyle2Image: () -> Unit,
    onClearStyle2Image: () -> Unit,
    onPickStyle2Logo: () -> Unit,
    onClearStyle2Logo: () -> Unit,
    onPickStyle2Background: () -> Unit,
    onClearStyle2Background: () -> Unit,
    back: () -> Unit,
) = AppPage(tr("自定义我的设备界面", "自定义我的设备界面"), back, restartScopes = setOf(ScopeApplication.SETTINGS)) { padding, scroll ->
    val style = appearance.deviceInterfaceStyle.coerceIn(DEVICE_INTERFACE_STYLE_SYSTEM, DEVICE_INTERFACE_STYLE_TWO)
    val selectStyle: (Int) -> Unit = { selected ->
            update {
            it.copy(
                deviceInterfaceStyle = selected,
                tutorialCardEnabled = selected == DEVICE_INTERFACE_STYLE_ONE,
                tutorialCardInfoCardsEnabled = selected == DEVICE_INTERFACE_STYLE_ONE,
            )
        }
    }
    AppList(padding, scroll) {
        item {
            Card(Modifier.fillMaxWidth()) {
OverlayDropdownPreference(
                    title = tr("我的设备界面样式", "我的设备界面样式"),
                    items = listOf(tr("系统默认", "系统默认"), tr("样式1 (来自酷安@Mr_Bocchi)", "样式1 (来自酷安@Mr_Bocchi)"), tr("样式2", "样式2")),
                    selectedIndex = style,
                    onSelectedIndexChange = selectStyle,
                )
            }
        }
        if (style == DEVICE_INTERFACE_STYLE_SYSTEM) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = tr("导入LOGO", "导入LOGO"),
                        summary = appearance.logoMime.ifBlank { tr("未导入", "未导入") },
                        onClick = onPickSystemLogo,
                    )
                    ArrowPreference(
                        title = tr("清除LOGO", "清除LOGO"),
                        summary = if (appearance.logoMime.isBlank()) tr("无LOGO", "无LOGO") else tr("已导入", "已导入"),
                        onClick = onClearSystemLogo,
                    )
                }
            }
        }
        if (style == DEVICE_INTERFACE_STYLE_ONE) {
            item {
                Group(tr("机型图片", "机型图片")) {
                    ArrowPreference(
                        title = tr("导入机型图片", "导入机型图片"),
                        summary = appearance.tutorialCardImageMime.ifBlank { tr("未导入", "未导入") },
                        onClick = onPickStyle1Image,
                    )
                    ArrowPreference(
                        title = tr("清除机型图片", "清除机型图片"),
                        summary = if (appearance.tutorialCardImageMime.isBlank()) tr("无图片", "无图片") else tr("已导入", "已导入"),
                        onClick = onClearStyle1Image,
                    )
                    SliderPreference(
                        value = appearance.tutorialCardImageScale.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardImageScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("机型图片缩放", "机型图片缩放"),
                        valueText = "${appearance.tutorialCardImageScale}%",
                        valueRange = 40f..200f,
                        steps = 159,
                    )
                    SliderPreference(
                        value = appearance.tutorialCardImageLogoSpacing.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardImageLogoSpacing = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("机型图片与LOGO间距", "机型图片与LOGO间距"),
                        valueText = "${appearance.tutorialCardImageLogoSpacing}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                }
            }
            item {
                Group(tr("背景图片", "背景图片")) {
                    ArrowPreference(
                        title = tr("导入背景图片", "导入背景图片"),
                        summary = appearance.tutorialCardBackgroundMime.ifBlank { tr("未导入", "未导入") },
                        onClick = onPickStyle1Background,
                    )
                    ArrowPreference(
                        title = tr("清除背景图片", "清除背景图片"),
                        summary = if (appearance.tutorialCardBackgroundMime.isBlank()) tr("无图片", "无图片") else tr("已导入", "已导入"),
                        onClick = onClearStyle1Background,
                    )
                SliderPreference(
                    value = appearance.tutorialCardBackgroundBlur,
                    onValueChange = { value -> update { it.copy(tutorialCardBackgroundBlur = value) } },
                    onValueChangeFinished = {},
                    title = tr("背景图片模糊度", "背景图片模糊度"),
                    valueText = String.format(Locale.US, "%.2fdp", appearance.tutorialCardBackgroundBlur),
                    valueRange = 0f..25f,
                    steps = 2499,
                    enabled = true,
                )
                SliderPreference(
                    value = appearance.tutorialCardBackgroundHorizontalOffset.toFloat(),
                    onValueChange = { value -> update { it.copy(tutorialCardBackgroundHorizontalOffset = value.toInt()) } },
                    onValueChangeFinished = {},
                    title = tr("背景图片左右偏移", "背景图片左右偏移"),
                    valueText = "${appearance.tutorialCardBackgroundHorizontalOffset}%",
                    valueRange = -120f..120f,
                    steps = 239,
                    enabled = true,
                )
                SliderPreference(
                    value = appearance.tutorialCardBackgroundVerticalOffset.toFloat(),
                    onValueChange = { value -> update { it.copy(tutorialCardBackgroundVerticalOffset = value.toInt()) } },
                    onValueChangeFinished = {},
                    title = tr("背景图片上下偏移", "背景图片上下偏移"),
                    valueText = "${appearance.tutorialCardBackgroundVerticalOffset}%",
                    valueRange = -120f..120f,
                    steps = 239,
                    enabled = true,
                )
                SliderPreference(
                    value = appearance.tutorialCardBackgroundScale.toFloat(),
                    onValueChange = { value -> update { it.copy(tutorialCardBackgroundScale = value.toInt()) } },
                    onValueChangeFinished = {},
                    title = tr("背景图片缩放", "背景图片缩放"),
                    valueText = "${appearance.tutorialCardBackgroundScale}%",
                    valueRange = 40f..200f,
                    steps = 159,
                    enabled = true,
                )
                }
            }
            item {
                Group(tr("logo", "LOGO")) {
                    ArrowPreference(
                        title = tr("导入LOGO", "导入LOGO"),
                        summary = appearance.tutorialCardLogoMime.ifBlank { tr("未导入", "未导入") },
                        onClick = onPickStyle1Logo,
                    )
                    ArrowPreference(
                        title = tr("清除LOGO", "清除LOGO"),
                        summary = if (appearance.tutorialCardLogoMime.isBlank()) tr("无LOGO", "无LOGO") else tr("已导入", "已导入"),
                        onClick = onClearStyle1Logo,
                    )
                    SliderPreference(
                        value = appearance.tutorialCardLogoScale.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardLogoScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("LOGO缩放", "LOGO缩放"),
                        valueText = "${appearance.tutorialCardLogoScale}%",
                        valueRange = 40f..200f,
                        steps = 159,
                    )
                    SliderPreference(
                        value = appearance.tutorialCardLogoVerticalOffset.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardLogoVerticalOffset = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("LOGO上下偏移", "LOGO上下偏移"),
                        valueText = "${appearance.tutorialCardLogoVerticalOffset}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                }
            }
            item {
                Group(tr("底部标识", "底部标识")) {
                    SliderPreference(
                        value = appearance.tutorialCardTextSpacing.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardTextSpacing = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("底部Logo与文案间距", "底部Logo与文案间距"),
                        valueText = "${appearance.tutorialCardTextSpacing}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                    TutorialCardTextField(tr("署名", "署名"), appearance.tutorialCardAuthor) { value -> update { it.copy(tutorialCardAuthor = value) } }
                }
            }
        }
        if (style == DEVICE_INTERFACE_STYLE_TWO) {
            item {
                Group(tr("机型图片", "机型图片")) {
                    ArrowPreference(
                        title = tr("导入机型图片", "导入机型图片"),
                        summary = appearance.style2ImageMime.ifBlank { tr("未导入", "未导入") },
                        onClick = onPickStyle2Image,
                    )
                    ArrowPreference(
                        title = tr("清除机型图片", "清除机型图片"),
                        summary = if (appearance.style2ImageMime.isBlank()) tr("无图片", "无图片") else tr("已导入", "已导入"),
                        onClick = onClearStyle2Image,
                    )
                    SliderPreference(
                        value = appearance.style2ImageScale.toFloat(),
                        onValueChange = { value -> update { it.copy(style2ImageScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("机型图片缩放", "机型图片缩放"),
                        valueText = "${appearance.style2ImageScale}%",
                        valueRange = 40f..200f,
                        steps = 159,
                    )
                }
            }
            item {
                Group(tr("背景图片", "背景图片")) {
                    ArrowPreference(
                        title = tr("导入背景图片", "导入背景图片"),
                        summary = appearance.style2BackgroundMime.ifBlank { tr("未导入", "未导入") },
                        onClick = onPickStyle2Background,
                    )
                    ArrowPreference(
                        title = tr("清除背景图片", "清除背景图片"),
                        summary = if (appearance.style2BackgroundMime.isBlank()) tr("无背景图", "无背景图") else tr("已导入", "已导入"),
                        onClick = onClearStyle2Background,
                    )
                    SliderPreference(
                        value = appearance.style2BackgroundBlur,
                        onValueChange = { value -> update { it.copy(style2BackgroundBlur = value) } },
                        onValueChangeFinished = {},
                        title = tr("背景图片模糊度", "背景图片模糊度"),
                        valueText = String.format(Locale.US, "%.2fdp", appearance.style2BackgroundBlur),
                        valueRange = 0f..25f,
                        steps = 2499,
                    )
                    SliderPreference(
                        value = appearance.style2BackgroundHorizontalOffset.toFloat(),
                        onValueChange = { value -> update { it.copy(style2BackgroundHorizontalOffset = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("背景图片左右偏移", "背景图片左右偏移"),
                        valueText = "${appearance.style2BackgroundHorizontalOffset}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                    SliderPreference(
                        value = appearance.style2BackgroundVerticalOffset.toFloat(),
                        onValueChange = { value -> update { it.copy(style2BackgroundVerticalOffset = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("背景图片上下偏移", "背景图片上下偏移"),
                        valueText = "${appearance.style2BackgroundVerticalOffset}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                    SliderPreference(
                        value = appearance.style2BackgroundScale.toFloat(),
                        onValueChange = { value -> update { it.copy(style2BackgroundScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("背景图片缩放", "背景图片缩放"),
                        valueText = "${appearance.style2BackgroundScale}%",
                        valueRange = 40f..200f,
                        steps = 159,
                    )
                }
            }
            item {
                Group(tr("LOGO与版本号", "LOGO与版本号")) {
                    ArrowPreference(
                        title = tr("导入LOGO", "导入LOGO"),
                        summary = appearance.style2LogoMime.ifBlank { tr("未导入", "未导入") },
                        onClick = onPickStyle2Logo,
                    )
                    ArrowPreference(
                        title = tr("清除LOGO", "清除LOGO"),
                        summary = if (appearance.style2LogoMime.isBlank()) tr("无LOGO", "无LOGO") else tr("已导入", "已导入"),
                        onClick = onClearStyle2Logo,
                    )
OverlayDropdownPreference(
                        title = tr("LOGO与版本号对齐方式", "LOGO与版本号对齐方式"),
                        items = listOf(tr("左对齐", "左对齐"), tr("居中对齐", "居中对齐"), tr("右对齐", "右对齐")),
                        selectedIndex = appearance.style2LogoAlignment.coerceIn(0, 2),
                        onSelectedIndexChange = { value -> update { it.copy(style2LogoAlignment = value) } },
                    )
                    SliderPreference(
                        value = appearance.style2LogoVersionSpacing.toFloat(),
                        onValueChange = { value -> update { it.copy(style2LogoVersionSpacing = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("LOGO与版本号行间距", "LOGO与版本号行间距"),
                        valueText = "${appearance.style2LogoVersionSpacing}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                    SliderPreference(
                        value = appearance.style2LogoHorizontalOffsetForAlignment().toFloat(),
                        onValueChange = { value -> update { current -> current.withStyle2LogoHorizontalOffset(current.style2LogoAlignment, value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("LOGO与版本号左右偏移", "LOGO与版本号左右偏移"),
                        valueText = "${appearance.style2LogoHorizontalOffsetForAlignment()}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                    SliderPreference(
                        value = appearance.style2LogoVerticalOffsetForAlignment().toFloat(),
                        onValueChange = { value -> update { current -> current.withStyle2LogoVerticalOffset(current.style2LogoAlignment, value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("LOGO与版本号上下偏移", "LOGO与版本号上下偏移"),
                        valueText = "${appearance.style2LogoVerticalOffsetForAlignment()}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                }
            }
            item {
                Group(tr("文案", "文案")) {
                    SwitchPreference(
                        title = tr("显示自定义文案", "显示自定义文案"),
                        checked = appearance.style2TextEnabled,
                        onCheckedChange = { value -> update { it.copy(style2TextEnabled = value) } },
                    )
                    if (appearance.style2TextEnabled) {
                        TutorialCardTextField(tr("文案", "文案"), appearance.style2Text) { value -> update { it.copy(style2Text = value) } }
                        SwitchPreference(
                            title = tr("独立于LOGO和版本号", "独立于LOGO和版本号"),
                            checked = appearance.style2TextIndependent,
                            onCheckedChange = { value -> update { it.copy(style2TextIndependent = value) } },
                        )
                        SliderPreference(
                            value = appearance.style2TextScale.toFloat(),
                            onValueChange = { value -> update { it.copy(style2TextScale = value.toInt()) } },
                            onValueChangeFinished = {},
                            title = tr("文案缩放", "文案缩放"),
                            valueText = "${appearance.style2TextScale}%",
                            valueRange = 40f..200f,
                            steps = 159,
                        )
                        if (!appearance.style2TextIndependent) {
OverlayDropdownPreference(
                                title = tr("显示位置", "显示位置"),
                                items = listOf(tr("LOGO之上", "LOGO之上"), tr("版本号之下", "版本号之下")),
                                selectedIndex = appearance.style2TextPosition.coerceIn(0, 1),
                                onSelectedIndexChange = { value -> update { it.copy(style2TextPosition = value) } },
                            )
                            if (appearance.style2TextPosition == 0) {
                                SliderPreference(
                                    value = appearance.style2TextSpacingAbove.toFloat(),
                                    onValueChange = { value -> update { it.copy(style2TextSpacingAbove = value.toInt()) } },
                                    onValueChangeFinished = {},
                                    title = tr("文案与LOGO行间距", "文案与LOGO行间距"),
                                    valueText = "${appearance.style2TextSpacingAbove}%",
                                    valueRange = -120f..120f,
                                    steps = 239,
                                )
                            } else {
                                SliderPreference(
                                    value = appearance.style2TextSpacingBelow.toFloat(),
                                    onValueChange = { value -> update { it.copy(style2TextSpacingBelow = value.toInt()) } },
                                    onValueChangeFinished = {},
                                    title = tr("文案与版本号行间距", "文案与版本号行间距"),
                                    valueText = "${appearance.style2TextSpacingBelow}%",
                                    valueRange = -120f..120f,
                                    steps = 239,
                                )
                            }
                        } else {
OverlayDropdownPreference(
                                title = tr("显示位置", "显示位置"),
                                items = listOf(tr("居中", "居中"), tr("左对齐", "左对齐"), tr("右对齐", "右对齐")),
                                selectedIndex = appearance.style2TextAlignment.coerceIn(0, 2),
                                onSelectedIndexChange = { value -> update { it.copy(style2TextAlignment = value) } },
                            )
                            SliderPreference(
                                value = appearance.style2TextVerticalOffsetForAlignment().toFloat(),
                                onValueChange = { value -> update { current -> current.withStyle2TextVerticalOffset(current.style2TextAlignment, value.toInt()) } },
                                onValueChangeFinished = {},
                                title = tr("文案上下偏移", "文案上下偏移"),
                                valueText = "${appearance.style2TextVerticalOffsetForAlignment()}%",
                                valueRange = -120f..120f,
                                steps = 239,
                            )
                            SliderPreference(
                                value = appearance.style2TextHorizontalOffsetForAlignment().toFloat(),
                                onValueChange = { value -> update { current -> current.withStyle2TextHorizontalOffset(current.style2TextAlignment, value.toInt()) } },
                                onValueChangeFinished = {},
                                title = tr("文案左右偏移", "文案左右偏移"),
                                valueText = "${appearance.style2TextHorizontalOffsetForAlignment()}%",
                                valueRange = -120f..120f,
                                steps = 239,
                            )
                        }
                    }
                }
            }
            item {
                Group(tr("文本颜色模式", "文本颜色模式")) {
OverlayDropdownPreference(
                        title = tr("logo", "LOGO"),
                        items = listOf(tr("跟随系统", "跟随系统"), tr("深色", "深色"), tr("浅色", "浅色")),
                        selectedIndex = appearance.style2LogoColorMode.coerceIn(0, 2),
                        onSelectedIndexChange = { value -> update { it.copy(style2LogoColorMode = value) } },
                    )
OverlayDropdownPreference(
                        title = tr("版本号", "版本号"),
                        items = listOf(tr("跟随系统", "跟随系统"), tr("深色", "深色"), tr("浅色", "浅色")),
                        selectedIndex = appearance.style2VersionColorMode.coerceIn(0, 2),
                        onSelectedIndexChange = { value -> update { it.copy(style2VersionColorMode = value) } },
                    )
OverlayDropdownPreference(
                        title = tr("文案", "文案"),
                        items = listOf(tr("跟随系统", "跟随系统"), tr("深色", "深色"), tr("浅色", "浅色")),
                        selectedIndex = appearance.style2TextColorMode.coerceIn(0, 2),
                        onSelectedIndexChange = { value -> update { it.copy(style2TextColorMode = value) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun TutorialCardTextField(label: String, value: String, onValueChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    TextField(
        value = text,
        onValueChange = { next -> text = next; onValueChange(next) },
        label = label,
        useLabelAsPlaceholder = true,
        singleLine = true,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
    )
}

@Composable
private fun SettingsAppearancePage(
    slot: String,
    appearance: SettingsAppearanceSettings,
    update: ((SettingsAppearanceSettings) -> SettingsAppearanceSettings) -> Unit,
    onPick: () -> Unit,
    onClear: () -> Unit,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val home = slot == APPEARANCE_SLOT_HOME
    val enabled = if (home) appearance.homeEnabled else appearance.deviceEnabled
    val opacity = if (home) appearance.homeOpacity else appearance.deviceOpacity
    val blur = if (home) appearance.homeBlur else appearance.deviceBlur
    val font = if (home) appearance.homeFontMode else appearance.deviceFontMode
    val mime = if (home) appearance.homeMime else appearance.deviceMime
    val version = if (home) appearance.homeVersion else appearance.deviceVersion
    val bitmap = remember(slot, version) {
        val file = appearanceFile(context, slot)
        if (mime.startsWith("video/")) {
            runCatching {
                MediaMetadataRetriever().use { retriever ->
                    retriever.setDataSource(file.absolutePath)
                    retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }.getOrNull()
        } else {
            BitmapFactory.decodeFile(file.absolutePath)
        }
    }
    fun change(transform: (SettingsAppearanceSettings) -> SettingsAppearanceSettings) = update(transform)
    AppPage(if (home) tr("设置主界面背景", "设置主界面背景") else tr("我的设备界面背景", "我的设备界面背景"), back) { padding, scroll ->
        AppList(padding, scroll) {
            item {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Box(
                        Modifier.fillMaxWidth(0.34f).aspectRatio(9f / 19.5f).align(Alignment.CenterHorizontally)
                            .clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap.asImageBitmap(),
                                null,
                                Modifier.fillMaxSize()
                                    .graphicsLayer { alpha = opacity / 100f }
                                    .then(if (blur > 0) Modifier.blur(blur.dp) else Modifier),
                                contentScale = ContentScale.Crop,
                            )
                            Text(
                                text = if (home) tr("设置", "设置") else tr("我的设备", "我的设备"),
                                color = when (font) {
                                    1 -> ComposeColor.White
                                    2 -> ComposeColor.Black
                                    else -> MiuixTheme.colorScheme.onSurface
                                },
                                style = MiuixTheme.textStyles.title4,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Text(mime.ifBlank { tr("暂无预览", "暂无预览") }, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onPick,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) { Text(tr("选择图片", "选择图片")) }
                        Button(
                            onClick = onClear,
                            modifier = Modifier.weight(1f),
                            enabled = mime.isNotBlank(),
                        ) { Text(tr("清除图片", "清除图片")) }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = tr("启用自定义背景", "启用自定义背景"),
                        checked = enabled,
                        onCheckedChange = { value -> change { if (home) it.copy(homeEnabled = value) else it.copy(deviceEnabled = value) } },
                    )
                    SliderPreference(
                        value = blur.toFloat(),
                        onValueChange = { value -> change { if (home) it.copy(homeBlur = value) else it.copy(deviceBlur = value) } },
                        onValueChangeFinished = {},
                        title = tr("模糊度", "模糊度"),
                        valueText = String.format(Locale.US, "%.2f", blur),
                        valueRange = 0f..20f,
                        steps = 1999,
                        enabled = enabled,
                    )
                    SliderPreference(
                        value = opacity.toFloat(),
                        onValueChange = { value -> change { if (home) it.copy(homeOpacity = value.toInt()) else it.copy(deviceOpacity = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("不透明度", "不透明度"),
                        valueText = "$opacity%",
                        valueRange = 0f..100f,
                        steps = 99,
                        enabled = enabled,
                    )
OverlayDropdownPreference(
                        title = tr("字体颜色", "字体颜色"),
                        items = listOf(tr("默认", "默认"), tr("浅色", "浅色"), tr("深色", "深色")),
                        selectedIndex = font,
                        onSelectedIndexChange = { value -> change { if (home) it.copy(homeFontMode = value) else it.copy(deviceFontMode = value) } },
                        enabled = enabled,
                    )
                }
            }
            if (!home) {
                item {
                    Text(
                        tr("使用自定义我的设备界面背景将失去高级材质LOGO", "使用自定义我的设备界面背景将失去高级材质LOGO"),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
            item {
                Text(
                    tr("此功能为实验性功能，不能保证使用体验", "此功能为实验性功能，不能保证使用体验"),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
    }
}

@Composable
private fun DeviceProfileFieldDialog(
    title: String,
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(title, current) { mutableStateOf(current) }
    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            TextField(
                value = value,
                onValueChange = { value = it.take(256) },
                label = tr("参数值", "参数值"),
                useLabelAsPlaceholder = true,
                singleLine = true,
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = { onSave(value.trim()) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("保存", "保存")) }
            }
        }
    }
}

@Composable
private fun ServiceCard(online: Boolean) {
    val color = if (online) ComposeColor(0xFF38A169) else ComposeColor(0xFFE05353)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color.copy(alpha = .14f)), insideMargin = PaddingValues(16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text("LSPosed", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
            Text(if (online) tr("\u5df2\u8fde\u63a5", "\u5df2\u8fde\u63a5") else tr("\u672a\u8fde\u63a5", "\u672a\u8fde\u63a5"), style = MiuixTheme.textStyles.body2, color = color, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SmallTitle(title, insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
        Card(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun Entry(title: String, enabled: Boolean = true, click: () -> Unit) {
    Card(Modifier.fillMaxWidth()) { ArrowPreference(title = title, onClick = click, enabled = enabled) }
}

@Composable
private fun Detail(
    page: PageId,
    settings: HookSettings,
    cameras: CameraSettings,
    deviceProfile: DeviceProfileSettings,
    appearance: SettingsAppearanceSettings,
    musicWhitelist: Set<String>,
    update: ((HookSettings) -> HookSettings) -> Unit,
    updateCamera: ((CameraSettings) -> CameraSettings) -> Unit,
    updateDeviceProfile: ((DeviceProfileSettings) -> DeviceProfileSettings) -> Unit,
    updateAppearance: ((SettingsAppearanceSettings) -> SettingsAppearanceSettings) -> Unit,
    updateMusicWhitelist: (Set<String>) -> Unit,
    presetActions: ShadePresetActions,
    onDebugMode: () -> Unit,
    captcha: String,
    onPickRasterImages: () -> Unit,
    onApplyRasterWallpaper: () -> Unit,
    onPickAppearanceHome: () -> Unit,
    onPickAppearanceDevice: () -> Unit,
    onClearAppearanceHome: () -> Unit,
    onClearAppearanceDevice: () -> Unit,
    onPickTutorialDeviceImage: () -> Unit,
    onClearTutorialDeviceImage: () -> Unit,
    onPickStyle1UpdateBackground: () -> Unit,
    onClearStyle1UpdateBackground: () -> Unit,
    onPickStyle2DeviceImage: () -> Unit,
    onClearStyle2DeviceImage: () -> Unit,
    onPickStyle2UpdateBackground: () -> Unit,
    onClearStyle2UpdateBackground: () -> Unit,
    onPickCustomDeviceLogo: () -> Unit,
    onClearCustomDeviceLogo: () -> Unit,
    onPickStyle2DeviceLogo: () -> Unit,
    onClearStyle2DeviceLogo: () -> Unit,
    onPickAppearanceLogo: () -> Unit,
    onClearAppearanceLogo: () -> Unit,
    openPage: (PageId) -> Unit,
    back: () -> Unit
) {
    when (page) {
        PageId.SHADE -> Shade(settings, update, presetActions, openPage, back)
        PageId.SHADE_PRESETS -> ShadePresets(settings, update, presetActions, back)
        PageId.SHADE_NOTIFICATION_ELEMENTS -> MaterialOverrideAdvancedPage(
            tr("通知元素", "通知元素"), settings.notificationElementsMaterial, false,
            back,
        ) { update { value ->
            val next = value.copy(notificationElementsMaterial = it)
            if (value.shadeSettingsUnified) next.copy(controlCenterElementsMaterial = it) else next
        } }
        PageId.SHADE_CONTROL_CENTER_ELEMENTS -> MaterialOverrideAdvancedPage(
            tr("控制中心元素", "控制中心元素"), settings.controlCenterElementsMaterial, false,
            back,
        ) { update { value ->
            val next = value.copy(controlCenterElementsMaterial = it)
            if (value.shadeSettingsUnified) next.copy(notificationElementsMaterial = it) else next
        } }
        PageId.SHADE_NOTIFICATION_BACKGROUND -> MaterialOverrideAdvancedPage(
            tr("通知中心背景", "通知中心背景"), settings.notificationCenterBackgroundMaterial, true,
            back,
        ) { update { value ->
            val next = value.copy(notificationCenterBackgroundMaterial = it)
            if (value.shadeSettingsUnified) next.copy(controlCenterBackgroundMaterial = it) else next
        } }
        PageId.SHADE_CONTROL_CENTER_BACKGROUND -> MaterialOverrideAdvancedPage(
            tr("控制中心背景", "控制中心背景"), settings.controlCenterBackgroundMaterial, true,
            back,
        ) { update { value ->
            val next = value.copy(controlCenterBackgroundMaterial = it)
            if (value.shadeSettingsUnified) next.copy(notificationCenterBackgroundMaterial = it) else next
        } }
        PageId.ISLAND -> Island(settings, update, back)
        PageId.STATUS -> Status(settings, update, openPage, back)
        PageId.STATUS_SIGNAL_CUSTOMIZATION -> StatusSignalCustomization(settings, update, back)
        PageId.CONTROL -> Control(settings, update, back)
        PageId.LOCK -> Lock(settings, update, openPage, back)
        PageId.LOCKSCREEN_WIDGET_EDITOR -> LockscreenWidgetEditor(settings, update, back)
        PageId.RASTER_WALLPAPER -> RasterWallpaper(settings, update, onPickRasterImages, onApplyRasterWallpaper, back)
        PageId.SUPER_XIAOAI -> SuperXiaoAi(settings, update, back)
        PageId.CAMERA -> Camera(cameras, updateCamera, openPage, back)
        PageId.CAMERA_PALETTE -> CameraPalette(cameras, updateCamera, back)
        PageId.SYSTEM_UPDATE -> SystemUpdate(settings, update, back)
        PageId.SYSTEM_SETTINGS -> SystemSettings(deviceProfile, updateDeviceProfile, appearance, updateAppearance, openPage, onPickAppearanceLogo, onClearAppearanceLogo, back)
        PageId.DEVICE_PROFILE -> DeviceProfileEditor(deviceProfile, updateDeviceProfile, back)
        PageId.SETTINGS_APPEARANCE_HOME -> SettingsAppearancePage(APPEARANCE_SLOT_HOME, appearance, updateAppearance, onPickAppearanceHome, onClearAppearanceHome, back)
        PageId.SETTINGS_APPEARANCE_DEVICE -> SettingsAppearancePage(APPEARANCE_SLOT_DEVICE, appearance, updateAppearance, onPickAppearanceDevice, onClearAppearanceDevice, back)
            PageId.TUTORIAL_DEVICE_CARD -> TutorialDeviceCardSettings(appearance, updateAppearance, onPickAppearanceLogo, onClearAppearanceLogo, onPickTutorialDeviceImage, onClearTutorialDeviceImage, onPickCustomDeviceLogo, onClearCustomDeviceLogo, onPickStyle1UpdateBackground, onClearStyle1UpdateBackground, onPickStyle2DeviceImage, onClearStyle2DeviceImage, onPickStyle2DeviceLogo, onClearStyle2DeviceLogo, onPickStyle2UpdateBackground, onClearStyle2UpdateBackground, back)
        PageId.ABOUT -> About(back, openPage, onDebugMode)
        PageId.DISCLAIMER -> Disclaimer(back, captcha)
        PageId.LICENSE -> License(back)
        PageId.LANGUAGE -> LanguagePage(back)
        PageId.DONATE -> Donate(back)
        PageId.OPEN -> OpenSource(back)
        PageId.REAR_SCREEN -> RearScreen(musicWhitelist, updateMusicWhitelist, openPage, back)
        PageId.REAR_MUSIC_APPS -> RearMusicApps(musicWhitelist, updateMusicWhitelist, back)
    }
}

@Composable
private fun SuperXiaoAi(
    settings: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    back: () -> Unit,
) = AppPage(tr("\u8d85\u7ea7\u5c0f\u7231\u8f93\u5165\u6cd5", "\u8d85\u7ea7\u5c0f\u7231\u8f93\u5165\u6cd5"), back, restartScopes = setOf(ScopeApplication.SUPER_XIAOAI_IME)) { padding, scroll ->
    AppList(padding, scroll, 28) {
        item {
            Group(tr("\u5916\u89c2", "\u5916\u89c2")) {
                SwitchPreference(
                    title = tr("\u5168\u5c40\u4f7f\u7528\u641c\u7d22\u9875\u5916\u89c2", "\u5168\u5c40\u4f7f\u7528\u641c\u7d22\u9875\u5916\u89c2"),
                    checked = settings.superXiaoAiGlobalSearchAppearance,
                    onCheckedChange = { enabled ->
                        update { it.copy(superXiaoAiGlobalSearchAppearance = enabled) }
                    },
                )
            }
        }
        item {
            Group(tr("superXiaoAiRestrictions", "\u529f\u80fd\u9650\u5236")) {
                SwitchPreference(title = tr("superXiaoAiBlacklist", "\u53bb\u9664\u9ed1\u540d\u5355\u8bcd\u5e93\u9650\u5236"), checked = settings.superXiaoAiBlacklistUnblocked, onCheckedChange = { value -> update { it.copy(superXiaoAiBlacklistUnblocked = value) } })
                SwitchPreference(title = tr("superXiaoAiClipboard", "\u53bb\u9664\u526a\u5207\u677f\u9650\u5236"), checked = settings.superXiaoAiClipboardUnblocked, onCheckedChange = { value -> update { it.copy(superXiaoAiClipboardUnblocked = value) } })
                SwitchPreference(title = tr("superXiaoAiAiSafety", "\u53bb\u9664 AI \u5b89\u5168\u9650\u5236"), checked = settings.superXiaoAiAiSafetyUnblocked, onCheckedChange = { value -> update { it.copy(superXiaoAiAiSafetyUnblocked = value) } })
                SwitchPreference(title = tr("superXiaoAiVoiceSafety", "\u53bb\u9664\u8bed\u97f3\u8f93\u5165\u5b89\u5168\u9650\u5236"), checked = settings.superXiaoAiVoiceSafetyUnblocked, onCheckedChange = { value -> update { it.copy(superXiaoAiVoiceSafetyUnblocked = value) } })
            }
        }
        item {
            Group(tr("superXiaoAiKeyboardAppearance", "\u952e\u76d8\u5916\u89c2")) {
                OverlayDropdownPreference(title = tr("superXiaoAiKeyboardColorMode", "\u952e\u76d8\u989c\u8272\u6a21\u5f0f"), items = listOf(tr("followSystem", "\u8ddf\u968f\u7cfb\u7edf"), tr("superXiaoAiLightMode", "\u6d45\u8272\u6a21\u5f0f"), tr("superXiaoAiDarkMode", "\u6df1\u8272\u6a21\u5f0f")), selectedIndex = settings.superXiaoAiKeyboardColorMode, onSelectedIndexChange = { value -> update { it.copy(superXiaoAiKeyboardColorMode = value) } })
                SwitchPreference(title = tr("superXiaoAiKeyboardTuning", "\u952e\u76d8\u5916\u89c2\u53c2\u6570\u8c03\u6574"), checked = settings.superXiaoAiKeyboardStyleEnabled, onCheckedChange = { value -> update { it.copy(superXiaoAiKeyboardStyleEnabled = value) } })
                if (settings.superXiaoAiKeyboardStyleEnabled) {
                    ParameterIntSlide(tr("superXiaoAiKeyboardCorner", "\u952e\u76d8\u5706\u89d2"), settings.superXiaoAiKeyboardCornerRadius, 0..48, " dp") { value -> update { it.copy(superXiaoAiKeyboardCornerRadius = value) } }
                    ParameterIntSlide(tr("superXiaoAiKeyboardOpacity", "\u952e\u76d8\u80cc\u666f\u900f\u660e\u5ea6"), settings.superXiaoAiKeyboardOpacity, 0..100, "%") { value -> update { it.copy(superXiaoAiKeyboardOpacity = value) } }
                    ParameterIntSlide(tr("superXiaoAiKeyboardBlur", "\u952e\u76d8\u80cc\u666f\u6a21\u7cca\u5ea6"), settings.superXiaoAiKeyboardBlur, 0..100, " dp") { value -> update { it.copy(superXiaoAiKeyboardBlur = value) } }
                }
            }
        }
    }
}

@Composable
private fun SystemUpdate(
    settings: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    back: () -> Unit,
) = AppPage(tr("\u7cfb\u7edf\u66f4\u65b0", "\u7cfb\u7edf\u66f4\u65b0"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UPDATE)) { padding, scroll ->
    var editingVersion by remember { mutableStateOf<SystemUpdateVersionField?>(null) }
    AppList(padding, scroll, 28) {
        item {
            Group(tr("\u66f4\u65b0\u63a7\u5236", "\u66f4\u65b0\u63a7\u5236")) {
                SwitchPreference(
                    title = tr("\u7981\u7528\u7cfb\u7edf\u66f4\u65b0", "\u7981\u7528\u7cfb\u7edf\u66f4\u65b0"),
                    summary = tr("\u963b\u6b62\u5f00\u673a\u3001\u5b9a\u65f6\u548c\u624b\u52a8\u89e6\u53d1\u7684\u7cfb\u7edf\u66f4\u65b0\u68c0\u67e5", "\u963b\u6b62\u5f00\u673a\u3001\u5b9a\u65f6\u548c\u624b\u52a8\u89e6\u53d1\u7684\u7cfb\u7edf\u66f4\u65b0\u68c0\u67e5"),
                    checked = settings.systemUpdateDisabled,
                    onCheckedChange = { enabled -> update { it.copy(systemUpdateDisabled = enabled) } },
                )
                SwitchPreference(
                    title = tr("\u79fb\u9664 OTA \u9650\u5236", "\u79fb\u9664 OTA \u9650\u5236"),
                    summary = tr("\u4ec5\u9002\u7528\u4e8e VAB \u8bbe\u5907\uff0c\u4f1a\u8df3\u8fc7 OTA \u6821\u9a8c", "\u4ec5\u9002\u7528\u4e8e VAB \u8bbe\u5907\uff0c\u4f1a\u8df3\u8fc7 OTA \u6821\u9a8c"),
                    checked = settings.systemUpdateOtaLimitRemoved,
                    onCheckedChange = { enabled -> update { it.copy(systemUpdateOtaLimitRemoved = enabled) } },
                )
            }
        }
        item {
            Group(tr("\u7248\u672c\u4f2a\u88c5", "\u7248\u672c\u4f2a\u88c5")) {
                SwitchPreference(
                    title = tr("\u542f\u7528\u7248\u672c\u4f2a\u88c5", "\u542f\u7528\u7248\u672c\u4f2a\u88c5"),
                    summary = tr("\u4ec5\u5bf9\u7cfb\u7edf\u66f4\u65b0\u5e94\u7528\u751f\u6548", "\u4ec5\u5bf9\u7cfb\u7edf\u66f4\u65b0\u5e94\u7528\u751f\u6548"),
                    checked = settings.systemUpdateVersionSpoofEnabled,
                    onCheckedChange = { enabled -> update { it.copy(systemUpdateVersionSpoofEnabled = enabled) } },
                )
                ArrowPreference(
                    title = tr("\u4f2a\u88c5\u7248\u672c", "\u4f2a\u88c5\u7248\u672c"),
                    summary = settings.systemUpdateVersion.ifBlank { tr("\u672a\u8bbe\u7f6e", "\u672a\u8bbe\u7f6e") },
                    enabled = settings.systemUpdateVersionSpoofEnabled,
                    onClick = { editingVersion = SystemUpdateVersionField.SYSTEM },
                )
                ArrowPreference(
                    title = tr("\u4f2a\u88c5 SOTA \u7248\u672c", "\u4f2a\u88c5 SOTA \u7248\u672c"),
                    summary = settings.systemUpdateSotaVersion.ifBlank { tr("\u672a\u8bbe\u7f6e", "\u672a\u8bbe\u7f6e") },
                    enabled = settings.systemUpdateVersionSpoofEnabled,
                    onClick = { editingVersion = SystemUpdateVersionField.SOTA },
                )
            }
        }
    }
    editingVersion?.let { field ->
        val current = when (field) {
            SystemUpdateVersionField.SYSTEM -> settings.systemUpdateVersion
            SystemUpdateVersionField.SOTA -> settings.systemUpdateSotaVersion
        }
        SystemUpdateVersionDialog(
            title = when (field) {
                SystemUpdateVersionField.SYSTEM -> tr("\u4f2a\u88c5\u7cfb\u7edf\u8f6f\u4ef6\u7248\u672c", "\u4f2a\u88c5\u7cfb\u7edf\u8f6f\u4ef6\u7248\u672c")
                SystemUpdateVersionField.SOTA -> tr("\u4f2a\u88c5 SOTA (XMS) \u70ed\u66f4\u65b0\u7248\u672c", "\u4f2a\u88c5 SOTA (XMS) \u70ed\u66f4\u65b0\u7248\u672c")
            },
            current = current,
            onDismiss = { editingVersion = null },
            onSave = { value ->
                update {
                    when (field) {
                        SystemUpdateVersionField.SYSTEM -> it.copy(systemUpdateVersion = value)
                        SystemUpdateVersionField.SOTA -> it.copy(systemUpdateSotaVersion = value)
                    }
                }
                editingVersion = null
            },
        )
    }
}

@Composable
private fun SystemUpdateVersionDialog(
    title: String,
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(title, current) { mutableStateOf(current) }
    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            TextField(
                value = value,
                onValueChange = { value = it.take(128) },
                label = tr("\u7248\u672c\u53f7", "\u7248\u672c\u53f7"),
                useLabelAsPlaceholder = true,
                singleLine = true,
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("\u53d6\u6d88", "\u53d6\u6d88")) }
                GlassDialogButton(
                    onClick = { onSave(value.trim()) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("\u4fdd\u5b58", "\u4fdd\u5b58")) }
            }
        }
    }
}

private enum class SystemUpdateVersionField { SYSTEM, SOTA }

private fun statusBarFloatText(value: Float): String =
    String.format(Locale.US, "%.2f", value)

private fun statusBarTuningSummary(
    scale: Float,
    verticalOffset: Float,
    leftMargin: Float,
    rightMargin: Float,
): String =
    "${tr("缩放", "缩放")} ${statusBarFloatText(scale)}x · ${tr("上下偏移量", "上下偏移量")} ${statusBarFloatText(verticalOffset)}dp · ${tr("左间距", "左间距")} ${statusBarFloatText(leftMargin)}dp · ${tr("右间距", "右间距")} ${statusBarFloatText(rightMargin)}dp"

@Composable
private fun StatusSignalTuningDialog(
    title: String,
    scale: Float,
    verticalOffset: Float,
    leftMargin: Float,
    rightMargin: Float,
    onScaleChange: (Float) -> Unit,
    onVerticalOffsetChange: (Float) -> Unit,
    onLeftMarginChange: (Float) -> Unit,
    onRightMarginChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            StatusSignalTuningSlider(
                title = tr("缩放", "缩放"),
                value = scale,
                range = 0.1f..3f,
                suffix = "x",
                onValueChangeFinished = onScaleChange,
            )
            StatusSignalTuningSlider(
                title = tr("上下偏移量", "上下偏移量"),
                value = verticalOffset,
                range = -8f..8f,
                suffix = "dp",
                onValueChangeFinished = onVerticalOffsetChange,
            )
            StatusSignalTuningSlider(
                title = tr("左间距", "左间距"),
                value = leftMargin,
                range = -8f..8f,
                suffix = "dp",
                onValueChangeFinished = onLeftMarginChange,
            )
            StatusSignalTuningSlider(
                title = tr("右间距", "右间距"),
                value = rightMargin,
                range = -8f..8f,
                suffix = "dp",
                onValueChangeFinished = onRightMarginChange,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("完成", "完成")) }
            }
        }
    }
}

@Composable
private fun StatusSignalTuningSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onValueChangeFinished: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    SliderPreference(
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = {
            val normalized = (current * 100f).roundToInt() / 100f
            onValueChangeFinished(normalized.coerceIn(range))
        },
        title = title,
        valueText = "${statusBarFloatText(current)}$suffix",
        valueRange = range,
        steps = ((range.endInclusive - range.start) * 100f).roundToInt() - 1,
    )
}

@Composable
private fun Shade(
    s: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    presetActions: ShadePresetActions,
    openPage: (PageId) -> Unit,
    back: () -> Unit,
) = AppPage(tr("\u7cfb\u7edf\u754c\u9762", "\u7cfb\u7edf\u754c\u9762"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
    var showSavePresetDialog by remember { mutableStateOf(false) }
    AppList(p, scroll, 28) {
        item {
            Group(tr("\u5168\u5c40\u4e3b\u9898", "\u5168\u5c40\u4e3b\u9898")) {
                SwitchPreference(
                    title = tr("\u4f7f\u7528\u5168\u5c40\u4e3b\u9898\u540e\u4fdd\u6301\u67d4\u5149\u73bb\u7483", "\u4f7f\u7528\u5168\u5c40\u4e3b\u9898\u540e\u4fdd\u6301\u67d4\u5149\u73bb\u7483"),
                    checked = s.keepSoftGlassAfterGlobalTheme,
                    onCheckedChange = { enabled ->
                        update { it.copy(keepSoftGlassAfterGlobalTheme = enabled) }
                    },
                )
                SwitchPreference(
                    title = tr("\u7edf\u4e00\u901a\u77e5\u6750\u8d28", "\u7edf\u4e00\u901a\u77e5\u6750\u8d28"),
                    checked = s.unifyNotificationMaterial,
                    onCheckedChange = { enabled ->
                        update { it.copy(unifyNotificationMaterial = enabled) }
                    },
                )
                SwitchPreference(
                    title = tr("\u89e3\u9664\u201c\u73bb\u7483\u201d\u548c\u201c\u53e0\u52a0\u201d\u65f6\u949f\u6750\u8d28\u9650\u5236", "\u89e3\u9664\u201c\u73bb\u7483\u201d\u548c\u201c\u53e0\u52a0\u201d\u65f6\u949f\u6750\u8d28\u9650\u5236"),
                    checked = s.removeClockMaterialLimit,
                    onCheckedChange = { enabled ->
                        update { it.copy(removeClockMaterialLimit = enabled) }
                    },
                )
            }
        }
        item { Group(tr("\u9884\u8bbe", "\u9884\u8bbe")) {
            ArrowPreference(title = tr("\u9884\u8bbe", "\u9884\u8bbe"), onClick = { openPage(PageId.SHADE_PRESETS) })
            ArrowPreference(title = tr("\u4fdd\u5b58\u5f53\u524d\u9884\u8bbe", "\u4fdd\u5b58\u5f53\u524d\u9884\u8bbe"), onClick = { showSavePresetDialog = true })
        } }
        item {
            Group(tr("\u63a7\u5236\u4e2d\u5fc3\u4e0e\u901a\u77e5\u4e2d\u5fc3\u6750\u8d28\u8c03\u6574", "\u63a7\u5236\u4e2d\u5fc3\u4e0e\u901a\u77e5\u4e2d\u5fc3\u6750\u8d28\u8c03\u6574")) {
                SwitchPreference(
                    title = tr("\u63a7\u5236\u4e2d\u5fc3\u548c\u901a\u77e5\u4e2d\u5fc3\u7edf\u4e00\u8bbe\u7f6e", "\u63a7\u5236\u4e2d\u5fc3\u548c\u901a\u77e5\u4e2d\u5fc3\u7edf\u4e00\u8bbe\u7f6e"),
                    checked = s.shadeSettingsUnified,
                    onCheckedChange = { enabled ->
                        update { value ->
                            if (enabled) {
                                value.copy(
                                    shadeSettingsUnified = true,
                                    controlCenterElementsMaterial = value.notificationElementsMaterial,
                                    controlCenterBackgroundMaterial = value.notificationCenterBackgroundMaterial,
                                )
                            } else {
                                value.copy(shadeSettingsUnified = false)
                            }
                        }
                    },
                )
                if (s.shadeSettingsUnified) {
                    MaterialOverrideCard(tr("\u5143\u7d20", "\u5143\u7d20"), s.notificationElementsMaterial, false, { openPage(PageId.SHADE_NOTIFICATION_ELEMENTS) }, wrapCard = false) {
                        update { value ->
                            value.copy(
                                notificationElementsMaterial = it,
                                controlCenterElementsMaterial = it,
                            )
                        }
                    }
                    MaterialOverrideCard(tr("\u80cc\u666f", "\u80cc\u666f"), s.notificationCenterBackgroundMaterial, true, { openPage(PageId.SHADE_NOTIFICATION_BACKGROUND) }, wrapCard = false) {
                        update { value ->
                            value.copy(
                                notificationCenterBackgroundMaterial = it,
                                controlCenterBackgroundMaterial = it,
                            )
                        }
                    }
                } else {
                    MaterialOverrideCard(tr("\u901a\u77e5\u4e2d\u5fc3\u5143\u7d20", "\u901a\u77e5\u4e2d\u5fc3\u5143\u7d20"), s.notificationElementsMaterial, false, { openPage(PageId.SHADE_NOTIFICATION_ELEMENTS) }, wrapCard = false) {
                        update { value -> value.copy(notificationElementsMaterial = it) }
                    }
                    MaterialOverrideCard(tr("\u901a\u77e5\u4e2d\u5fc3\u80cc\u666f", "\u901a\u77e5\u4e2d\u5fc3\u80cc\u666f"), s.notificationCenterBackgroundMaterial, true, { openPage(PageId.SHADE_NOTIFICATION_BACKGROUND) }, wrapCard = false) {
                        update { value -> value.copy(notificationCenterBackgroundMaterial = it) }
                    }
                    MaterialOverrideCard(tr("\u63a7\u5236\u4e2d\u5fc3\u5143\u7d20", "\u63a7\u5236\u4e2d\u5fc3\u5143\u7d20"), s.controlCenterElementsMaterial, false, { openPage(PageId.SHADE_CONTROL_CENTER_ELEMENTS) }, wrapCard = false) {
                        update { value -> value.copy(controlCenterElementsMaterial = it) }
                    }
                    MaterialOverrideCard(tr("\u63a7\u5236\u4e2d\u5fc3\u80cc\u666f", "\u63a7\u5236\u4e2d\u5fc3\u80cc\u666f"), s.controlCenterBackgroundMaterial, true, { openPage(PageId.SHADE_CONTROL_CENTER_BACKGROUND) }, wrapCard = false) {
                        update { value -> value.copy(controlCenterBackgroundMaterial = it) }
                    }
                }
                ArrowPreference(title = tr("\u63a7\u5236\u4e2d\u5fc3\u5706\u89d2\u8be6\u7ec6\u8bbe\u7f6e", "\u63a7\u5236\u4e2d\u5fc3\u5706\u89d2\u8be6\u7ec6\u8bbe\u7f6e"), onClick = { openPage(PageId.CONTROL) })
            }
        }
        item {
            Group(tr("\u97f3\u91cf\u9762\u677f", "\u97f3\u91cf\u9762\u677f")) {
                VolumePanelPreferences(s, update)
            }
        }
    }
    SavePresetDialog(
        show = showSavePresetDialog,
        onDismiss = { showSavePresetDialog = false },
        onSave = { name ->
            presetActions.saveUserPreset(name)
            showSavePresetDialog = false
        },
    )
}

@Composable
private fun MaterialOverrideCard(
    title: String,
    value: MaterialOverride,
    isBackground: Boolean,
    openAdvanced: () -> Unit,
    wrapCard: Boolean = true,
    onChange: (MaterialOverride) -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val content: @Composable () -> Unit = {
        ArrowPreference(title = title, onClick = { expanded = !expanded })
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .98f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .98f),
        ) {
            Column {
        SwitchPreference(
            title = tr("\u542f\u7528\u6750\u8d28\u8c03\u6574", "\u542f\u7528\u6750\u8d28\u8c03\u6574"),
            checked = value.enabled,
            onCheckedChange = { enabled -> onChange(enableMaterialOverride(value, isBackground, enabled)) },
        )
        AnimatedVisibility(
            visible = value.enabled,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
        ) {
            Column {
                ParameterIntSlide(tr("\u6a21\u7cca\u6bd4\u4f8b", "\u6a21\u7cca\u6bd4\u4f8b"), value.blurPercent.coerceIn(0, if (isBackground) 100 else 200), 0..if (isBackground) 100 else 200, "%") { onChange(value.copy(blurPercent = it)) }
                if (isBackground) ParameterIntSlide(tr("\u80cc\u666f\u7f29\u653e\u6bd4\u4f8b", "\u80cc\u666f\u7f29\u653e\u6bd4\u4f8b"), value.scalePercent, 0..200, "%") { onChange(value.copy(scalePercent = it)) }
                if (isBackground) {
                    ParameterIntSlide(tr("\u80cc\u666f\u4e0d\u900f\u660e\u5ea6", "\u80cc\u666f\u4e0d\u900f\u660e\u5ea6"), (value.alpha + 100).coerceIn(0, 100), 0..100, "%") { onChange(value.copy(alpha = it - 100)) }
                    ParameterIntSlide(tr("\u6df7\u8272\u5f3a\u5ea6", "\u6df7\u8272\u5f3a\u5ea6"), value.tintStrength, 0..50, " x0.01") { onChange(value.copy(tintEnabled = it > 0, tintStrength = it)) }
                } else {
                    ParameterIntSlide(tr("Glass \u6a21\u7cca\u534a\u5f84", "Glass \u6a21\u7cca\u534a\u5f84"), value.glassRadius.coerceIn(0, 40), 0..40, " px") { onChange(value.copy(glassRadius = it)) }
                    ParameterIntSlide(tr("\u73bb\u7483\u5f3a\u5ea6", "\u73bb\u7483\u5f3a\u5ea6"), (value.refraction / 2 + 50).coerceIn(0, 100), 0..100, "%") { onChange(applyCompactGlassStrength(value, it)) }
                    ParameterIntSlide(tr("\u4e0d\u900f\u660e\u5ea6", "\u4e0d\u900f\u660e\u5ea6"), value.alpha + 50, 0..100, "%") { onChange(value.copy(alpha = it - 50)) }
                    ParameterIntSlide(tr("\u8fb9\u7f18\u4e0e\u53cd\u5c04", "\u8fb9\u7f18\u4e0e\u53cd\u5c04"), value.reflection + 50, 0..100, "%") { onChange(applyCompactReflection(value, it)) }
                    ParameterIntSlide(tr("\u8272\u5f69", "\u8272\u5f69"), value.saturation + 50, 0..100, "%") { onChange(applyCompactColor(value, it)) }
                }
                ArrowPreference(title = tr("\u9ad8\u7ea7\u6a21\u5f0f", "\u9ad8\u7ea7\u6a21\u5f0f"), onClick = openAdvanced)
            }
        }
    }
    }
}
    if (wrapCard) Card(Modifier.fillMaxWidth()) { content() } else content()
}

@Composable
private fun VolumePanelPreferences(
    s: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
) {
    SwitchPreference(
        title = tr("\u542f\u7528\u6750\u8d28\u8c03\u6574", "\u542f\u7528\u6750\u8d28\u8c03\u6574"),
        checked = s.volumePanelMaterialEnabled,
        onCheckedChange = { enabled ->
            update { it.copy(volumePanelMaterialEnabled = enabled) }
        },
    )
    AnimatedVisibility(
        visible = s.volumePanelMaterialEnabled,
        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
    ) {
        Column {
            ParameterIntSlide(tr("\u80cc\u666f\u4e0d\u900f\u660e\u5ea6", "\u80cc\u666f\u4e0d\u900f\u660e\u5ea6"), s.volumePanelBackgroundOpacity, 0..100, "%") { value ->
                update { it.copy(volumePanelBackgroundOpacity = value) }
            }
            ParameterIntSlide(tr("\u524d\u666f\u4e0d\u900f\u660e\u5ea6", "\u524d\u666f\u4e0d\u900f\u660e\u5ea6"), s.volumePanelBlurRadius, 0..120, " px") { value ->
                update { it.copy(volumePanelBlurRadius = value) }
            }
            FloatSlide(tr("\u5706\u89d2", "\u5706\u89d2"), s.volumePanelCornerRadius, 0f..60f) { value ->
                update { it.copy(volumePanelCornerRadius = value) }
            }
            ParameterIntSlide(tr("\u80cc\u666f\u6a21\u7cca\u5ea6", "\u80cc\u666f\u6a21\u7cca\u5ea6"), s.volumePanelGlassStrength, 0..100, "%") { value ->
                update { it.copy(volumePanelGlassStrength = value) }
            }
        }
    }
}

@Composable
private fun MaterialOverrideAdvancedPage(
    title: String,
    value: MaterialOverride,
    isBackground: Boolean,
    back: () -> Unit,
    onChange: (MaterialOverride) -> Unit,
) = AppPage(title, back) { p, scroll ->
    AppList(p, scroll, 28) {
        item { Card(Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = tr("\u542f\u7528\u6750\u8d28\u8c03\u6574", "\u542f\u7528\u6750\u8d28\u8c03\u6574"),
                checked = value.enabled,
                onCheckedChange = { enabled -> onChange(enableMaterialOverride(value, isBackground, enabled)) },
            )
            AnimatedVisibility(value.enabled) {
                Column {
                    ParameterIntSlide(tr("\u6a21\u7cca\u6bd4\u4f8b", "\u6a21\u7cca\u6bd4\u4f8b"), value.blurPercent.coerceIn(0, if (isBackground) 100 else 200), 0..if (isBackground) 100 else 200, "%") { onChange(value.copy(blurPercent = it)) }
                    if (isBackground) {
                        ParameterIntSlide(tr("\u80cc\u666f\u7f29\u653e\u6bd4\u4f8b", "\u80cc\u666f\u7f29\u653e\u6bd4\u4f8b"), value.scalePercent, 0..200, "%") { onChange(value.copy(scalePercent = it)) }
                        ParameterIntSlide(tr("\u80cc\u666f\u4e0d\u900f\u660e\u5ea6", "\u80cc\u666f\u4e0d\u900f\u660e\u5ea6"), (value.alpha + 100).coerceIn(0, 100), 0..100, "%") { onChange(value.copy(alpha = it - 100)) }
                    } else {
                        ParameterIntSlide(tr("Glass \u6a21\u7cca\u534a\u5f84", "Glass \u6a21\u7cca\u534a\u5f84"), value.glassRadius.coerceIn(0, 40), 0..40, " px") { onChange(value.copy(glassRadius = it)) }
                        ParameterIntSlide(tr("\u4eae\u5ea6\u504f\u79fb", "\u4eae\u5ea6\u504f\u79fb"), value.brightness, -30..30, " x0.01") { onChange(value.copy(brightness = it)) }
                        ParameterIntSlide(tr("\u538b\u6697\u504f\u79fb", "\u538b\u6697\u504f\u79fb"), value.darker, -50..50, " x0.01") { onChange(value.copy(darker = it)) }
                        ParameterIntSlide(tr("\u6298\u5c04\u504f\u79fb", "\u6298\u5c04\u504f\u79fb"), value.refraction, -100..100, " x0.01") { onChange(value.copy(refraction = it)) }
                        ParameterIntSlide(tr("\u70e7\u707c\u504f\u79fb", "\u70e7\u707c\u504f\u79fb"), value.burn, -50..50, " x0.01") { onChange(value.copy(burn = it)) }
                        ParameterIntSlide(tr("\u9971\u548c\u5ea6\u504f\u79fb", "\u9971\u548c\u5ea6\u504f\u79fb"), value.saturation, -100..100, " x0.01") { onChange(value.copy(saturation = it)) }
                        ParameterIntSlide(tr("\u4e0d\u900f\u660e\u5ea6\u504f\u79fb", "\u4e0d\u900f\u660e\u5ea6\u504f\u79fb"), value.alpha, -50..50, " x0.01") { onChange(value.copy(alpha = it)) }
                        ParameterIntSlide(tr("\u8fb9\u7f18\u539a\u5ea6", "\u8fb9\u7f18\u539a\u5ea6"), value.edgeThickness, -100..100, " x0.01") { onChange(value.copy(edgeThickness = it)) }
                        ParameterIntSlide(tr("\u53cd\u5c04\u5f3a\u5ea6", "\u53cd\u5c04\u5f3a\u5ea6"), value.reflection, -100..100, " x0.01") { onChange(value.copy(reflection = it)) }
                        ParameterIntSlide(tr("\u65b9\u5411\u5149\u5f3a\u5ea6", "\u65b9\u5411\u5149\u5f3a\u5ea6"), value.directionalLight, -100..100, " x0.01") { onChange(value.copy(directionalLight = it)) }
                        ParameterIntSlide(tr("\u80cc\u666f\u9971\u548c\u5ea6", "\u80cc\u666f\u9971\u548c\u5ea6"), value.backgroundSaturation, -100..100, " x0.01") { onChange(value.copy(backgroundSaturation = it)) }
                        ParameterIntSlide(tr("\u80cc\u666f\u4eae\u5ea6", "\u80cc\u666f\u4eae\u5ea6"), value.backgroundBrightness, -100..100, " x0.01") { onChange(value.copy(backgroundBrightness = it)) }
                    }
                    SwitchPreference(
                        title = tr("\u81ea\u5b9a\u4e49\u6df7\u8272", "\u81ea\u5b9a\u4e49\u6df7\u8272"),
                        checked = value.tintEnabled,
                        onCheckedChange = { onChange(value.copy(tintEnabled = it)) },
                    )
                    AnimatedVisibility(value.tintEnabled) {
                        Column {
                            ShortcutBackgroundColorPreference(tr("\u6df7\u8272\u989c\u8272", "\u6df7\u8272\u989c\u8272"), value.tintColor) { onChange(value.copy(tintColor = it)) }
                            ParameterIntSlide(tr("\u6df7\u8272\u5f3a\u5ea6", "\u6df7\u8272\u5f3a\u5ea6"), value.tintStrength, 0..50, " x0.01") { onChange(value.copy(tintStrength = it)) }
                        }
                    }
                }
            }
        } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShadePresets(
    settings: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    actions: ShadePresetActions,
    back: () -> Unit,
) = AppPage(tr("\u9884\u8bbe", "\u9884\u8bbe"), back) { p, scroll ->
    var selectedPreset by remember { mutableStateOf<ShadePreset?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    val presets = builtInShadePresets(settings)
    AppList(p, scroll, 28) {
        item {
            Group(tr("\u5bfc\u5165", "\u5bfc\u5165")) {
                ArrowPreference(title = tr("\u5bfc\u5165\u9884\u8bbe", "\u5bfc\u5165\u9884\u8bbe"), onClick = { showImportDialog = true })
            }
        }
        item { SmallTitle(tr("\u5185\u7f6e\u9884\u8bbe", "\u5185\u7f6e\u9884\u8bbe"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp)) }
        item { Card(Modifier.fillMaxWidth()) {
            presets.forEach { preset ->
                PresetRow(preset, onUse = { update { preset.applyTo(it) } }, onLongPress = { selectedPreset = preset })
            }
        } }
        item { SmallTitle(tr("\u7528\u6237\u9884\u8bbe", "\u7528\u6237\u9884\u8bbe"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp)) }
        item { Card(Modifier.fillMaxWidth()) {
            if (actions.userPresets.isEmpty()) {
                Text(tr("\u6682\u65e0\u4fdd\u5b58\u7684\u7528\u6237\u9884\u8bbe", "\u6682\u65e0\u4fdd\u5b58\u7684\u7528\u6237\u9884\u8bbe"), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(16.dp))
            } else {
                actions.userPresets.forEach { preset ->
                    PresetRow(preset, onUse = { update { preset.applyTo(it) } }, onLongPress = { selectedPreset = preset })
                }
            }
        } }
    }
    ImportPresetDialog(
        show = showImportDialog,
        onDismiss = { showImportDialog = false },
        onJson = { showImportDialog = false; actions.importJson() },
        onQr = { showImportDialog = false; actions.importQr() },
    )
    selectedPreset?.let { preset ->
        PresetActionDialog(
            preset = preset,
            onDismiss = { selectedPreset = null },
            onUse = { update { preset.applyTo(it) }; selectedPreset = null },
            onExport = { showExportDialog = true },
            onDelete = {
                actions.deleteUserPreset(preset)
                selectedPreset = null
            },
        )
        ExportPresetDialog(
            show = showExportDialog,
            preset = preset,
            onDismiss = { showExportDialog = false },
            onJson = { showExportDialog = false; selectedPreset = null; actions.exportJson(preset) },
            onQr = { showExportDialog = false; selectedPreset = null; actions.exportQr(preset) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetRow(preset: ShadePreset, onUse: () -> Unit, onLongPress: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onUse, onLongClick = onLongPress).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(preset.name, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
        Image(MiuixIcons.Regular.ChevronForward, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary))
    }
}

private fun builtInShadePresets(settings: HookSettings): List<ShadePreset> {
    val presets: List<Pair<String, (HookSettings) -> HookSettings>> = listOf(
        tr("\u7cfb\u7edf\u9ed8\u8ba4", "\u7cfb\u7edf\u9ed8\u8ba4") to { it.copy(
            notificationElementsMaterial = MaterialOverride(), controlCenterElementsMaterial = MaterialOverride(),
            notificationCenterBackgroundMaterial = MaterialOverride(), controlCenterBackgroundMaterial = MaterialOverride(),
        ) },
        tr("\u78e8\u7802\u73bb\u7483", "\u78e8\u7802\u73bb\u7483") to { it.copy(
            notificationElementsMaterial = referenceElementMaterial(
                glassRadius = 14,
                brightness = -8,
                darker = 20,
                refraction = -28,
                burn = 12,
                saturation = -18,
                alpha = -12,
                reflection = 6,
                edgeThickness = 22,
                backgroundSaturation = -8,
            ).copy(blurPercent = 70),
            controlCenterElementsMaterial = referenceElementMaterial(
                glassRadius = 14,
                brightness = -8,
                darker = 20,
                refraction = -28,
                burn = 12,
                saturation = -18,
                alpha = -12,
                reflection = 6,
                edgeThickness = 22,
                backgroundSaturation = -8,
            ).copy(blurPercent = 70),
            notificationCenterBackgroundMaterial = backgroundMaterial(blurPercent = 22, scalePercent = 96, opacity = 42),
            controlCenterBackgroundMaterial = backgroundMaterial(blurPercent = 22, scalePercent = 96, opacity = 42),
        ) },
        tr("\u6e05\u900f\u73bb\u7483", "\u6e05\u900f\u73bb\u7483") to { it.copy(
            notificationElementsMaterial = referenceElementMaterial(
                glassRadius = 20,
                brightness = 5,
                darker = -12,
                refraction = 26,
                burn = -18,
                saturation = 4,
                alpha = 8,
                reflection = 18,
                edgeThickness = 10,
            ),
            controlCenterElementsMaterial = referenceElementMaterial(
                glassRadius = 20,
                brightness = 5,
                darker = -12,
                refraction = 26,
                burn = -18,
                saturation = 4,
                alpha = 8,
                reflection = 18,
                edgeThickness = 10,
            ),
            notificationCenterBackgroundMaterial = backgroundMaterial(blurPercent = 30, scalePercent = 90, opacity = 16),
            controlCenterBackgroundMaterial = backgroundMaterial(blurPercent = 30, scalePercent = 90, opacity = 16),
        ) },
        tr("\u900f\u660e\u73bb\u7483", "\u900f\u660e\u73bb\u7483") to { it.copy(
            notificationElementsMaterial = referenceElementMaterial(
                glassRadius = 32,
                brightness = 10,
                darker = -4,
                refraction = 24,
                burn = -10,
                saturation = -8,
                alpha = 6,
                reflection = 18,
                edgeThickness = 6,
            ).copy(blurPercent = 72),
            controlCenterElementsMaterial = referenceElementMaterial(
                glassRadius = 32,
                brightness = 10,
                darker = -4,
                refraction = 24,
                burn = -10,
                saturation = -8,
                alpha = 6,
                reflection = 18,
                edgeThickness = 6,
            ).copy(blurPercent = 72),
            notificationCenterBackgroundMaterial = backgroundMaterial(blurPercent = 28, scalePercent = 88, opacity = 8),
            controlCenterBackgroundMaterial = backgroundMaterial(blurPercent = 28, scalePercent = 88, opacity = 8),
        ) },
        tr("\u6df1\u8272\u5bf9\u6bd4", "\u6df1\u8272\u5bf9\u6bd4") to { it.copy(
            notificationElementsMaterial = referenceElementMaterial(
                glassRadius = 24,
                brightness = -18,
                darker = 28,
                refraction = 18,
                burn = -20,
                saturation = -24,
                alpha = 16,
                reflection = 10,
            ),
            controlCenterElementsMaterial = referenceElementMaterial(
                glassRadius = 24,
                brightness = -18,
                darker = 28,
                refraction = 18,
                burn = -20,
                saturation = -24,
                alpha = 16,
                reflection = 10,
            ),
            notificationCenterBackgroundMaterial = backgroundMaterial(blurPercent = 24, opacity = 72),
            controlCenterBackgroundMaterial = backgroundMaterial(blurPercent = 24, opacity = 72),
        ) },
        tr("\u9ad8\u53cd\u5c04", "\u9ad8\u53cd\u5c04") to { it.copy(
            notificationElementsMaterial = referenceElementMaterial(
                glassRadius = 36,
                brightness = 8,
                darker = -6,
                refraction = 70,
                burn = -12,
                alpha = 10,
                reflection = 72,
                edgeThickness = 40,
            ),
            controlCenterElementsMaterial = referenceElementMaterial(
                glassRadius = 36,
                brightness = 8,
                darker = -6,
                refraction = 70,
                burn = -12,
                alpha = 10,
                reflection = 72,
                edgeThickness = 40,
            ),
            notificationCenterBackgroundMaterial = backgroundMaterial(blurPercent = 28, opacity = 20),
            controlCenterBackgroundMaterial = backgroundMaterial(blurPercent = 28, opacity = 20),
        ) },
        tr("\u8272\u5f69\u589e\u5f3a", "\u8272\u5f69\u589e\u5f3a") to { it.copy(
            notificationElementsMaterial = referenceElementMaterial(
                glassRadius = 28,
                brightness = 6,
                darker = -4,
                refraction = 20,
                saturation = 42,
                alpha = 5,
                reflection = 25,
                edgeThickness = 12,
                backgroundSaturation = 38,
            ),
            controlCenterElementsMaterial = referenceElementMaterial(
                glassRadius = 28,
                brightness = 6,
                darker = -4,
                refraction = 20,
                saturation = 42,
                alpha = 5,
                reflection = 25,
                edgeThickness = 12,
                backgroundSaturation = 38,
            ),
            notificationCenterBackgroundMaterial = backgroundMaterial(blurPercent = 26, scalePercent = 94, opacity = 34),
            controlCenterBackgroundMaterial = backgroundMaterial(blurPercent = 26, scalePercent = 94, opacity = 34),
        ) },
        tr("\u8f7b\u91cf\u6d41\u7545", "\u8f7b\u91cf\u6d41\u7545") to { it.copy(
            notificationElementsMaterial = referenceElementMaterial(
                glassRadius = 40,
                brightness = 2,
                darker = -3,
                refraction = 8,
                burn = -4,
                saturation = 2,
                alpha = 8,
                reflection = 8,
                edgeThickness = 4,
            ),
            controlCenterElementsMaterial = referenceElementMaterial(
                glassRadius = 40,
                brightness = 2,
                darker = -3,
                refraction = 8,
                burn = -4,
                saturation = 2,
                alpha = 8,
                reflection = 8,
                edgeThickness = 4,
            ),
            notificationCenterBackgroundMaterial = backgroundMaterial(blurPercent = 12, scalePercent = 72, opacity = 28),
            controlCenterBackgroundMaterial = backgroundMaterial(blurPercent = 12, scalePercent = 72, opacity = 28),
        ) },
    )
    return presets.map { (name, apply) -> ShadePreset(name, apply(settings).exportShadePreset(name), builtIn = true) }
}

@Composable
private fun SavePresetDialog(show: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(show) { mutableStateOf("") }
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("保存当前预设", "保存当前预设"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            TextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = tr("预设名", "预设名"),
                useLabelAsPlaceholder = true,
                singleLine = true,
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = { onSave(name) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("确定", "确定")) }
            }
        }
    }
}

@Composable
private fun ImportPresetDialog(show: Boolean, onDismiss: () -> Unit, onJson: () -> Unit, onQr: () -> Unit) {
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("导入预设", "导入预设"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            GlassDialogButton(onJson, Modifier.fillMaxWidth()) { Text("JSON") }
            GlassDialogButton(onQr, Modifier.fillMaxWidth()) { Text(tr("二维码", "二维码")) }
            GlassDialogButton(onDismiss, Modifier.fillMaxWidth()) { Text(tr("取消", "取消")) }
        }
    }
}

@Composable
private fun PresetActionDialog(
    preset: ShadePreset,
    onDismiss: () -> Unit,
    onUse: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(preset.name, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            GlassDialogButton(onUse, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("使用", "使用")) }
            GlassDialogButton(onExport, Modifier.fillMaxWidth()) { Text(tr("导出", "导出")) }
            if (!preset.builtIn) GlassDialogButton(onDelete, Modifier.fillMaxWidth()) { Text(tr("删除", "删除")) }
            GlassDialogButton(onDismiss, Modifier.fillMaxWidth()) { Text(tr("取消", "取消")) }
        }
    }
}

@Composable
private fun ExportPresetDialog(
    show: Boolean,
    preset: ShadePreset,
    onDismiss: () -> Unit,
    onJson: () -> Unit,
    onQr: () -> Unit,
) {
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(String.format(Locale.getDefault(), tr("export_preset_named", "导出 %s"), preset.name), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            GlassDialogButton(onJson, Modifier.fillMaxWidth()) { Text("JSON") }
            GlassDialogButton(onQr, Modifier.fillMaxWidth()) { Text(tr("二维码", "二维码")) }
            GlassDialogButton(onDismiss, Modifier.fillMaxWidth()) { Text(tr("取消", "取消")) }
        }
    }
}

@Composable
private fun QrShareDialog(request: QrShareRequest, onDismiss: () -> Unit, onSave: () -> Unit) {
    val bitmap = remember(request.payload) { createPresetQrCode(request.payload) }
    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(request.name, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = tr("预设二维码", "预设二维码"), modifier = Modifier.size(240.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(onSave, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("保存", "保存")) }
            }
        }
    }
}

private fun referenceElementMaterial(
    glassRadius: Int = 10,
    brightness: Int = -5,
    darker: Int = -8,
    refraction: Int = 20,
    burn: Int = -25,
    saturation: Int = 0,
    alpha: Int = 0,
    reflection: Int = 0,
    edgeThickness: Int = 0,
    backgroundSaturation: Int = 0,
): MaterialOverride = MaterialOverride(
    enabled = true, glassRadius = glassRadius, brightness = brightness, darker = darker,
    refraction = refraction, burn = burn, saturation = saturation, alpha = alpha,
    reflection = reflection, edgeThickness = edgeThickness, backgroundSaturation = backgroundSaturation,
)

private fun enableMaterialOverride(
    value: MaterialOverride,
    isBackground: Boolean,
    enabled: Boolean,
): MaterialOverride = if (enabled && isBackground) {
    value.copy(enabled = true, blurPercent = value.blurPercent.coerceIn(0, 100), alpha = value.alpha.coerceIn(-100, 0))
} else {
    value.copy(enabled = enabled)
}

private fun backgroundMaterial(blurPercent: Int, scalePercent: Int = 100, opacity: Int = 35): MaterialOverride = MaterialOverride(
    enabled = true,
    blurPercent = blurPercent.coerceIn(0, 100),
    scalePercent = scalePercent.coerceIn(0, 200),
    alpha = opacity.coerceIn(0, 100) - 100,
)

private fun applyCompactGlassStrength(value: MaterialOverride, percent: Int): MaterialOverride {
    val amount = (percent - 50).coerceIn(-50, 50)
    return value.copy(
        brightness = (amount / 4).coerceIn(-30, 30),
        darker = (-amount / 5).coerceIn(-50, 50),
        refraction = (amount * 2).coerceIn(-100, 100),
        burn = (-amount / 2).coerceIn(-50, 50),
    )
}

private fun applyCompactReflection(value: MaterialOverride, percent: Int): MaterialOverride {
    val amount = (percent - 50).coerceIn(-50, 50)
    return value.copy(reflection = amount, edgeThickness = (amount / 2).coerceIn(-100, 100))
}

private fun applyCompactColor(value: MaterialOverride, percent: Int): MaterialOverride {
    val amount = (percent - 50).coerceIn(-50, 50)
    return value.copy(saturation = amount, backgroundSaturation = amount)
}

@Composable
private fun Island(s: HookSettings, update: ((HookSettings) -> HookSettings) -> Unit, back: () -> Unit) = AppPage(tr("\u8d85\u7ea7\u5c9b", "\u8d85\u7ea7\u5c9b"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
    AppList(p, scroll, 28) {
        item {
                Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = tr("\u53bb\u9664\u7126\u70b9\u901a\u77e5\u4e0e\u8d85\u7ea7\u5c9b\u767d\u540d\u5355\u9650\u5236", "\u53bb\u9664\u7126\u70b9\u901a\u77e5\u4e0e\u8d85\u7ea7\u5c9b\u767d\u540d\u5355\u9650\u5236"),
                    checked = s.removeFocusAndIslandWhitelistLimit,
                    onCheckedChange = { value ->
                        update { it.copy(removeFocusAndIslandWhitelistLimit = value) }
                    },
                )
                SwitchPreference(title = tr("\u81ea\u5b9a\u4e49\u8d85\u7ea7\u5c9b\u957f\u5ea6", "\u81ea\u5b9a\u4e49\u8d85\u7ea7\u5c9b\u957f\u5ea6"), checked = s.islandEnabled, onCheckedChange = { v -> update { it.copy(islandEnabled = v) } })
                if (s.islandEnabled) IntSlide(tr("\u6700\u5c0f\u5bbd\u5ea6", "\u6700\u5c0f\u5bbd\u5ea6"), s.islandWidth, 108..190) { v -> update { it.copy(islandWidth = v) } }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = tr("\u5c55\u5f00\u6001\u4e0b\u7684\u8d85\u7ea7\u5c9b\u80cc\u666f\u8c03\u6574", "\u5c55\u5f00\u6001\u4e0b\u7684\u8d85\u7ea7\u5c9b\u80cc\u666f\u8c03\u6574"),
                    checked = s.expandedIslandBackgroundEnabled,
                    onCheckedChange = { v -> update { it.copy(expandedIslandBackgroundEnabled = v) } },
                )
                AnimatedVisibility(
                    visible = s.expandedIslandBackgroundEnabled,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    Column {
                        ParameterIntSlide(tr("\u80cc\u666f\u4e0d\u900f\u660e\u5ea6", "\u80cc\u666f\u4e0d\u900f\u660e\u5ea6"), s.expandedIslandBackgroundOpacity, 0..100, "%") { value ->
                            update { it.copy(expandedIslandBackgroundOpacity = value) }
                        }
                        ParameterIntSlide(tr("Glass \u5c0f\u6a21\u7cca\u534a\u5f84", "Glass \u5c0f\u6a21\u7cca\u534a\u5f84"), s.expandedIslandGlassBlurRadius, 0..40, " px") { value ->
                            update { it.copy(expandedIslandGlassBlurRadius = value) }
                        }
                        ParameterIntSlide(tr("Glass \u5927\u6a21\u7cca\u534a\u5f84", "Glass \u5927\u6a21\u7cca\u534a\u5f84"), s.expandedIslandGlassLargeBlurRadius, 0..40, " px") { value ->
                            update { it.copy(expandedIslandGlassLargeBlurRadius = value) }
                        }
                        ParameterIntSlide(tr("\u81ea\u6a21\u7cca\u5f3a\u5ea6", "\u81ea\u6a21\u7cca\u5f3a\u5ea6"), s.expandedIslandSelfBlurRadius, 0..40, " px") { value ->
                            update { it.copy(expandedIslandSelfBlurRadius = value) }
                        }
                        SwitchPreference(
                            title = tr("\u663e\u793a\u9ad8\u5149", "\u663e\u793a\u9ad8\u5149"),
                            checked = s.expandedIslandShowHighlight,
                            onCheckedChange = { value -> update { it.copy(expandedIslandShowHighlight = value) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Status(
    s: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    open: (PageId) -> Unit,
    back: () -> Unit,
) = AppPage(tr("\u72b6\u6001\u680f", "\u72b6\u6001\u680f"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
    AppList(p, scroll, 28) {
        item { Card(Modifier.fillMaxWidth()) {
        Dim(tr("\u65f6\u949f\u5927\u5c0f", "\u65f6\u949f\u5927\u5c0f"), s.clockEnabled, { v -> update { it.copy(clockEnabled = v) } }, s.clockSize, 10f..24f) { v -> update { it.copy(clockSize = v) } }
        DeltaDim(tr("\u53f3\u8fb9\u8ddd", "\u53f3\u8fb9\u8ddd"), s.paddingEnd) { v -> update { it.copy(paddingEnd = v, paddingEndEnabled = true, paddingEndLegacyAbsolute = null) } }
        Dim(tr("\u5de6\u8fb9\u8ddd", "\u5de6\u8fb9\u8ddd"), s.paddingStartEnabled, { v -> update { it.copy(paddingStartEnabled = v) } }, s.paddingStart, 0f..32f) { v -> update { it.copy(paddingStart = v) } }
        Dim(tr("\u72b6\u6001\u680f\u9ad8\u5ea6", "\u72b6\u6001\u680f\u9ad8\u5ea6"), s.heightEnabled, { v -> update { it.copy(heightEnabled = v) } }, s.statusBarHeight.toFloat(), 24f..72f) { v -> update { it.copy(statusBarHeight = v.toInt()) } }
        DeltaDim(tr("\u4e0a\u8fb9\u8ddd", "\u4e0a\u8fb9\u8ddd"), s.paddingTop) { v -> update { it.copy(paddingTop = v, paddingTopEnabled = true, paddingTopLegacyAbsolute = null) } }
    } }
        item {
            Group(tr("\u72b6\u6001\u680f\u663e\u793a", "\u72b6\u6001\u680f\u663e\u793a")) {
                SwitchPreference(
                    title = tr("\u9690\u85cf\u72b6\u6001\u680f\u7535\u6c60\u5185\u6587\u672c", "\u9690\u85cf\u72b6\u6001\u680f\u7535\u6c60\u5185\u6587\u672c"),
                    checked = s.hideStatusBarClockText,
                    onCheckedChange = { enabled -> update { it.copy(hideStatusBarClockText = enabled) } },
                )
                ArrowPreference(
                    title = tr("\u72b6\u6001\u680f\u4fe1\u53f7\u81ea\u5b9a\u4e49", "\u72b6\u6001\u680f\u4fe1\u53f7\u81ea\u5b9a\u4e49"),
                    onClick = { open(PageId.STATUS_SIGNAL_CUSTOMIZATION) },
                )
            }
        }
    }
}

@Composable
private fun StatusSignalCustomization(
    s: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    back: () -> Unit,
) = AppPage(tr("\u72b6\u6001\u680f\u4fe1\u53f7\u81ea\u5b9a\u4e49", "\u72b6\u6001\u680f\u4fe1\u53f7\u81ea\u5b9a\u4e49"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
    var showCustomTextDialog by remember { mutableStateOf(false) }
    var customTextDraft by remember { mutableStateOf(s.mobileNetworkTypeCustomText) }
    var showStackedSignalTuningDialog by remember { mutableStateOf(false) }
    var showMobileNetworkTypeTuningDialog by remember { mutableStateOf(false) }

    AppList(p, scroll, 28) {
        item {
            Group(tr("\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b", "\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b")) {
                SwitchPreference(
                    title = tr("\u53cc\u6392\u4fe1\u53f7", "\u53cc\u6392\u4fe1\u53f7"),
                    summary = tr("\u5c06\u53cc\u5361\u4fe1\u53f7\u5408\u5e76\u4e3a\u4e0a\u4e0b\u53cc\u6392\u663e\u793a", "\u5c06\u53cc\u5361\u4fe1\u53f7\u5408\u5e76\u4e3a\u4e0a\u4e0b\u53cc\u6392\u663e\u793a"),
                    checked = s.stackedMobileSignalEnabled,
                    onCheckedChange = { enabled -> update { it.copy(stackedMobileSignalEnabled = enabled) } },
                )
OverlayDropdownPreference(
                    title = tr("\u9690\u85cf\u7cfb\u7edf\u9ed8\u8ba4\u4fe1\u53f7\u56fe\u6807", "\u9690\u85cf\u7cfb\u7edf\u9ed8\u8ba4\u4fe1\u53f7\u56fe\u6807"),
                    items = listOf(tr("\u4e0d\u9690\u85cf", "\u4e0d\u9690\u85cf"), tr("\u9690\u85cf\u975e\u4e0a\u7f51\u5361", "\u9690\u85cf\u975e\u4e0a\u7f51\u5361"), tr("\u5168\u90e8\u9690\u85cf", "\u5168\u90e8\u9690\u85cf")),
                    selectedIndex = s.mobileSignalHideMode.coerceIn(0, 2),
                    onSelectedIndexChange = { value -> update { it.copy(mobileSignalHideMode = value) } },
                )
OverlayDropdownPreference(
                    title = tr("\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b\u663e\u793a", "\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b\u663e\u793a"),
                    items = listOf(
                        tr("\u8ddf\u968f\u7cfb\u7edf\u9ed8\u8ba4", "\u8ddf\u968f\u7cfb\u7edf\u9ed8\u8ba4"),
                        tr("\u72ec\u7acb\u663e\u793a", "\u72ec\u7acb\u663e\u793a"),
                        tr("\u4e0d\u663e\u793a", "\u4e0d\u663e\u793a"),
                    ),
                    selectedIndex = s.mobileNetworkTypeMode.coerceIn(0, 2),
                    onSelectedIndexChange = { value -> update { it.copy(mobileNetworkTypeMode = value) } },
                )
                AnimatedVisibility(visible = s.mobileNetworkTypeMode == 1) {
                    Column {
OverlayDropdownPreference(
                            title = tr("\u72ec\u7acb\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b\u663e\u793a\u903b\u8f91", "\u72ec\u7acb\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b\u663e\u793a\u903b\u8f91"),
                            items = listOf(tr("\u59cb\u7ec8\u663e\u793a", "\u59cb\u7ec8\u663e\u793a"), tr("\u4f7f\u7528\u79fb\u52a8\u7f51\u7edc\u65f6\u663e\u793a", "\u4f7f\u7528\u79fb\u52a8\u7f51\u7edc\u65f6\u663e\u793a")),
                            selectedIndex = s.mobileNetworkTypeDisplayLogic.coerceIn(0, 1),
                            onSelectedIndexChange = { value ->
                                update { it.copy(mobileNetworkTypeDisplayLogic = value) }
                            },
                        )
OverlayDropdownPreference(
                            title = tr("\u72ec\u7acb\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b\u663e\u793a\u4f4d\u7f6e", "\u72ec\u7acb\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b\u663e\u793a\u4f4d\u7f6e"),
                            items = listOf(tr("\u4fe1\u53f7\u524d", "\u4fe1\u53f7\u524d"), tr("\u4fe1\u53f7\u540e", "\u4fe1\u53f7\u540e")),
                            selectedIndex = s.mobileNetworkTypePosition.coerceIn(0, 1),
                            onSelectedIndexChange = { value -> update { it.copy(mobileNetworkTypePosition = value) } },
                        )
                        ArrowPreference(
                            title = tr("\u81ea\u5b9a\u4e49\u72ec\u7acb\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b\u663e\u793a\u6587\u672c", "\u81ea\u5b9a\u4e49\u72ec\u7acb\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b\u663e\u793a\u6587\u672c"),
                            summary = s.mobileNetworkTypeCustomText.ifBlank { tr("\u8ddf\u968f\u771f\u5b9e\u7f51\u7edc\u7c7b\u578b", "\u8ddf\u968f\u771f\u5b9e\u7f51\u7edc\u7c7b\u578b") },
                            onClick = {
                                customTextDraft = s.mobileNetworkTypeCustomText
                                showCustomTextDialog = true
                            },
                        )
                        SwitchPreference(
                            title = tr("5GA\u65f6\u81ea\u52a8\u7f29\u5c0f\u5b57\u6bcd A", "5GA\u65f6\u81ea\u52a8\u7f29\u5c0f\u5b57\u6bcd A"),
                            checked = s.mobileNetworkTypeShrink5gaA,
                            onCheckedChange = { value -> update { it.copy(mobileNetworkTypeShrink5gaA = value) } },
                        )
                    }
                }
            }
        }
        item {
            Group(tr("\u56fe\u6807\u663e\u793a", "\u56fe\u6807\u663e\u793a")) {
                SwitchPreference(
                    title = tr("\u9690\u85cf\u7cfb\u7edf\u9ed8\u8ba4\u7684\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b", "\u9690\u85cf\u7cfb\u7edf\u9ed8\u8ba4\u7684\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b"),
                    checked = s.hideStatusBarNetworkType,
                    onCheckedChange = { value -> update { it.copy(hideStatusBarNetworkType = value) } },
                )
                SwitchPreference(
                    title = tr("\u9690\u85cf WiFi \u4ee3\u6570", "\u9690\u85cf WiFi \u4ee3\u6570"),
                    checked = s.hideStatusBarWifiStandard,
                    onCheckedChange = { value -> update { it.copy(hideStatusBarWifiStandard = value) } },
                )
                SwitchPreference(
                    title = tr("\u9690\u85cf\u7f51\u7edc\u6d3b\u52a8\u56fe\u6807", "\u9690\u85cf\u7f51\u7edc\u6d3b\u52a8\u56fe\u6807"),
                    checked = s.hideStatusBarNetworkActivity,
                    onCheckedChange = { value -> update { it.copy(hideStatusBarNetworkActivity = value) } },
                )
            }
        }

        item {
            Group(tr("调整", "调整")) {
                ArrowPreference(
                    title = tr("调整双排信号", "调整双排信号"),
                    summary = statusBarTuningSummary(
                        s.stackedMobileSignalScale,
                        s.stackedMobileSignalVerticalOffset,
                        s.stackedMobileSignalLeftMargin,
                        s.stackedMobileSignalRightMargin,
                    ),
                    onClick = { showStackedSignalTuningDialog = true },
                )
                ArrowPreference(
                    title = tr("调整移动网络类型", "调整移动网络类型"),
                    summary = statusBarTuningSummary(
                        s.mobileNetworkTypeScale,
                        s.mobileNetworkTypeVerticalOffset,
                        s.mobileNetworkTypeLeftMargin,
                        s.mobileNetworkTypeRightMargin,
                    ),
                    onClick = { showMobileNetworkTypeTuningDialog = true },
                )
            }
        }
    }

    WindowDialog(
        show = showCustomTextDialog,
        onDismissRequest = { showCustomTextDialog = false },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                tr("\u81ea\u5b9a\u4e49\u72ec\u7acb\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b\u663e\u793a\u6587\u672c", "\u81ea\u5b9a\u4e49\u72ec\u7acb\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b\u663e\u793a\u6587\u672c"),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
            )
            TextField(
                value = customTextDraft,
                onValueChange = { customTextDraft = it.take(128) },
                label = tr("\u663e\u793a\u6587\u672c", "\u663e\u793a\u6587\u672c"),
                useLabelAsPlaceholder = true,
                singleLine = true,
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onClick = { showCustomTextDialog = false }, modifier = Modifier.weight(1f)) {
                    Text(tr("\u53d6\u6d88", "\u53d6\u6d88"))
                }
                GlassDialogButton(
                    onClick = {
                        update { it.copy(mobileNetworkTypeCustomText = customTextDraft.trim()) }
                        showCustomTextDialog = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("\u4fdd\u5b58", "\u4fdd\u5b58")) }
            }
        }
    }
    if (showStackedSignalTuningDialog) {
        StatusSignalTuningDialog(
            title = tr("调整双排信号", "调整双排信号"),
            scale = s.stackedMobileSignalScale,
            verticalOffset = s.stackedMobileSignalVerticalOffset,
            leftMargin = s.stackedMobileSignalLeftMargin,
            rightMargin = s.stackedMobileSignalRightMargin,
            onScaleChange = { value -> update { it.copy(stackedMobileSignalScale = value) } },
            onVerticalOffsetChange = { value -> update { it.copy(stackedMobileSignalVerticalOffset = value) } },
            onLeftMarginChange = { value -> update { it.copy(stackedMobileSignalLeftMargin = value) } },
            onRightMarginChange = { value -> update { it.copy(stackedMobileSignalRightMargin = value) } },
            onDismiss = { showStackedSignalTuningDialog = false },
        )
    }
    if (showMobileNetworkTypeTuningDialog) {
        StatusSignalTuningDialog(
            title = tr("调整移动网络类型", "调整移动网络类型"),
            scale = s.mobileNetworkTypeScale,
            verticalOffset = s.mobileNetworkTypeVerticalOffset,
            leftMargin = s.mobileNetworkTypeLeftMargin,
            rightMargin = s.mobileNetworkTypeRightMargin,
            onScaleChange = { value -> update { it.copy(mobileNetworkTypeScale = value) } },
            onVerticalOffsetChange = { value -> update { it.copy(mobileNetworkTypeVerticalOffset = value) } },
            onLeftMarginChange = { value -> update { it.copy(mobileNetworkTypeLeftMargin = value) } },
            onRightMarginChange = { value -> update { it.copy(mobileNetworkTypeRightMargin = value) } },
            onDismiss = { showMobileNetworkTypeTuningDialog = false },
        )
    }
}

@Composable
private fun Control(s: HookSettings, update: ((HookSettings) -> HookSettings) -> Unit, back: () -> Unit) = AppPage(tr("\u63a7\u5236\u4e2d\u5fc3", "\u63a7\u5236\u4e2d\u5fc3"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
    AppList(p, scroll, 28) {
        item { Card(Modifier.fillMaxWidth()) {
        Corner(tr("\u9876\u90e8\u64cd\u4f5c\u6309\u94ae", "\u9876\u90e8\u64cd\u4f5c\u6309\u94ae"), s.topButtonsRadiusEnabled, { v -> update { it.copy(topButtonsRadiusEnabled = v) } }, s.topButtonsRadius) { v -> update { it.copy(topButtonsRadius = v) } }
        Corner(tr("\u5a92\u4f53\u5361\u7247", "\u5a92\u4f53\u5361\u7247"), s.mediaCardRadiusEnabled, { v -> update { it.copy(mediaCardRadiusEnabled = v) } }, s.mediaCardRadius) { v -> update { it.copy(mediaCardRadius = v) } }
        Corner(tr("\u97f3\u91cf / \u4eae\u5ea6\u6761", "\u97f3\u91cf / \u4eae\u5ea6\u6761"), s.sliderRadiusEnabled, { v -> update { it.copy(sliderRadiusEnabled = v) } }, s.sliderRadius) { v -> update { it.copy(sliderRadius = v) } }
        Corner(tr("\u4e0b\u534a\u90e8\u5206\u5706\u5f62\u6309\u94ae", "\u4e0b\u534a\u90e8\u5206\u5706\u5f62\u6309\u94ae"), s.controlBottomButtonsRadiusEnabled, { v -> update { it.copy(controlBottomButtonsRadiusEnabled = v) } }, s.controlBottomButtonsRadius) { v -> update { it.copy(controlBottomButtonsRadius = v) } }
        Corner(tr("\u878d\u5408\u8bbe\u5907\u4e2d\u5fc3", "\u878d\u5408\u8bbe\u5907\u4e2d\u5fc3"), s.deviceCenterRadiusEnabled, { v -> update { it.copy(deviceCenterRadiusEnabled = v) } }, s.deviceCenterRadius) { v -> update { it.copy(deviceCenterRadius = v) } }
        } }
    }
}

@Composable
private fun Lock(
    s: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    open: (PageId) -> Unit,
    back: () -> Unit,
) = AppPage(tr("\u9501\u5c4f", "\u9501\u5c4f"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD)) { p, scroll ->
    var showBottomTextDialog by remember { mutableStateOf(false) }
    var showWidgetDeviceNameDialog by remember { mutableStateOf(false) }
    var showLockscreenTemplateLimitDialog by remember { mutableStateOf(false) }
    AppList(p, scroll, 28) {
        item {
            Group(tr("\u666f\u6df1", "\u666f\u6df1")) {
                SwitchPreference(title = tr("\u53bb\u9664\u666f\u6df1\u9650\u5236", "\u53bb\u9664\u666f\u6df1\u9650\u5236"), checked = s.removeDepthImageLimit, onCheckedChange = { v -> update { it.copy(removeDepthImageLimit = v) } })
            }
        }
        item {
            Group(tr("\u9501\u5c4f\u642d\u914d", "\u9501\u5c4f\u642d\u914d")) {
                OverlayDropdownPreference(
                    title = tr("\u81ea\u5b9a\u4e49\u9501\u5c4f\u642d\u914d\u6570\u91cf\u4e0a\u9650", "\u81ea\u5b9a\u4e49\u9501\u5c4f\u642d\u914d\u6570\u91cf\u4e0a\u9650"),
                    items = listOf(
                        tr("\u7cfb\u7edf\u9ed8\u8ba4", "\u7cfb\u7edf\u9ed8\u8ba4"),
                        "50",
                        "60",
                        "80",
                        "100",
                        tr("\u81ea\u5b9a\u4e49", "\u81ea\u5b9a\u4e49"),
                    ),
                    selectedIndex = s.lockscreenTemplateLimitMode,
                    onSelectedIndexChange = { value ->
                        update { it.copy(lockscreenTemplateLimitMode = value) }
                    },
                )
                AnimatedVisibility(
                    visible = s.lockscreenTemplateLimitMode == LOCKSCREEN_TEMPLATE_LIMIT_CUSTOM,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    ArrowPreference(
                        title = tr("\u81ea\u5b9a\u4e49\u642d\u914d\u6570\u91cf", "\u81ea\u5b9a\u4e49\u642d\u914d\u6570\u91cf"),
                        summary = "${s.lockscreenTemplateLimitCustom}",
                        onClick = { showLockscreenTemplateLimitDialog = true },
                    )
                }
            }
        }
        item {
            Group(tr("\u9501\u5c4f\u5927\u65f6\u949f", "\u9501\u5c4f\u5927\u65f6\u949f")) {
                SwitchPreference(
                    title = tr("\u9501\u5c4f\u5927\u65f6\u949f\u5f3a\u5236\u663e\u793a\u5192\u53f7", "\u9501\u5c4f\u5927\u65f6\u949f\u5f3a\u5236\u663e\u793a\u5192\u53f7"),
                    checked = s.lockscreenClockColonForceVisible,
                    onCheckedChange = { value ->
                        update { it.copy(lockscreenClockColonForceVisible = value) }
                    },
                )
            }
        }
        item {
            Group(tr("\u8da3\u5473\u5149\u6805", "\u8da3\u5473\u5149\u6805")) {
                SwitchPreference(
                    title = tr("\u5149\u6805\u58c1\u7eb8", "\u5149\u6805\u58c1\u7eb8"),
                    summary = tr("\u968f\u624b\u673a\u503e\u659c\u5728\u591a\u5f20\u56fe\u7247\u95f4\u5207\u6362", "\u968f\u624b\u673a\u503e\u659c\u5728\u591a\u5f20\u56fe\u7247\u95f4\u5207\u6362"),
                    checked = s.rasterWallpaperEnabled,
                    onCheckedChange = { value -> update { it.copy(rasterWallpaperEnabled = value) } },
                )
                AnimatedVisibility(
                    visible = s.rasterWallpaperEnabled,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    ArrowPreference(
                        title = tr("\u5149\u6805\u58c1\u7eb8\u8bbe\u7f6e", "\u5149\u6805\u58c1\u7eb8\u8bbe\u7f6e"),
                        summary = "${s.rasterWallpaperUriList().size}/4 \u5f20\u56fe\u7247",
                        onClick = { open(PageId.RASTER_WALLPAPER) },
                    )
                }
            }
        }
        item {
            Group(tr("\u901a\u77e5\u4e0b\u6c89", "\u901a\u77e5\u4e0b\u6c89")) {
                SwitchPreference(
                    title = tr("\u53bb\u9664\u901a\u77e5\u4e0b\u6c89\u4f4d\u7f6e\u9650\u5236", "\u53bb\u9664\u901a\u77e5\u4e0b\u6c89\u4f4d\u7f6e\u9650\u5236"),
                    checked = s.notificationFodPositionLimitRemoved,
                    onCheckedChange = { value ->
                        update { it.copy(notificationFodPositionLimitRemoved = value) }
                    },
                )
OverlayDropdownPreference(
                    title = tr("\u9690\u85cf\u6307\u7eb9\u56fe\u6807", "\u9690\u85cf\u6307\u7eb9\u56fe\u6807"),
                    items = listOf(tr("\u4e0d\u9690\u85cf", "\u4e0d\u9690\u85cf"), tr("\u4ec5\u9501\u5c4f\u9690\u85cf", "\u4ec5\u9501\u5c4f\u9690\u85cf"), tr("\u5168\u5c40\u9690\u85cf", "\u5168\u5c40\u9690\u85cf")),
                    selectedIndex = s.fingerprintHideMode,
                    onSelectedIndexChange = { value ->
                        update { it.copy(fingerprintHideMode = value) }
                    },
                )
            }
        }
        item {
            Group(tr("\u9501\u5c4f\u5c0f\u7ec4\u4ef6", "\u9501\u5c4f\u5c0f\u7ec4\u4ef6")) {
                SwitchPreference(
                    title = tr("\u5f00\u542f\u9501\u5c4f\u5c0f\u7ec4\u4ef6", "\u5f00\u542f\u9501\u5c4f\u5c0f\u7ec4\u4ef6"),
                    checked = s.lockscreenWidgetEnabled,
                    onCheckedChange = { value ->
                        update { it.copy(lockscreenWidgetEnabled = value) }
                    },
                )
                AnimatedVisibility(
                    visible = s.lockscreenWidgetEnabled,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    Column {
                        ArrowPreference(
                            title = tr("锁屏小组件", "锁屏小组件"),
                            summary = lockscreenWidgetItemsSummary(s.lockscreenWidgetItems),
                            onClick = { open(PageId.LOCKSCREEN_WIDGET_EDITOR) },
                        )
                        ArrowPreference(
                            title = tr("\u81ea\u5b9a\u4e49\u8bbe\u5907\u540d\u79f0", "\u81ea\u5b9a\u4e49\u8bbe\u5907\u540d\u79f0"),
                            summary = s.lockscreenWidgetDeviceName.ifBlank { readRoProductMarketName() },
                            onClick = { showWidgetDeviceNameDialog = true },
                        )
OverlayDropdownPreference(
                            title = tr("\u7535\u91cf\u8fdb\u5ea6\u6761\u80cc\u666f\u6750\u8d28", "\u7535\u91cf\u8fdb\u5ea6\u6761\u80cc\u666f\u6750\u8d28"),
                            items = listOf(tr("\u7eaf\u8272", "\u7eaf\u8272"), tr("\u9ad8\u7ea7\u6750\u8d28（\u4e0d\u663e\u793a\u9ad8\u5149）", "\u9ad8\u7ea7\u6750\u8d28（\u4e0d\u663e\u793a\u9ad8\u5149）"), tr("\u67d4\u5149\u73bb\u7483", "\u67d4\u5149\u73bb\u7483")),
                            selectedIndex = s.lockscreenWidgetBatteryMaterialMode,
                            onSelectedIndexChange = { value ->
                                update { it.copy(lockscreenWidgetBatteryMaterialMode = value) }
                            },
                        )
OverlayDropdownPreference(
                            title = tr("\u5c0f\u7ec4\u4ef6\u989c\u8272", "\u5c0f\u7ec4\u4ef6\u989c\u8272"),
                            items = listOf(tr("\u6d45\u8272", "\u6d45\u8272"), tr("\u6df1\u8272", "\u6df1\u8272"), tr("\u81ea\u52a8", "\u81ea\u52a8")),
                            selectedIndex = s.lockscreenWidgetColorMode,
                            onSelectedIndexChange = { value ->
                                update { it.copy(lockscreenWidgetColorMode = value) }
                            },
                        )
                        SwitchPreference(
                            title = tr("\u907f\u8ba9\u9501\u5c4f\u901a\u77e5", "\u907f\u8ba9\u9501\u5c4f\u901a\u77e5"),
                            summary = tr("\u6709\u901a\u77e5\u65f6\u79fb\u52a8\u5230\u901a\u77e5\u9876\u90e8\uff0c\u5e76\u4fdd\u7559\u5b89\u5168\u95f4\u8ddd", "\u6709\u901a\u77e5\u65f6\u79fb\u52a8\u5230\u901a\u77e5\u9876\u90e8\uff0c\u5e76\u4fdd\u7559\u5b89\u5168\u95f4\u8ddd"),
                            checked = s.lockscreenWidgetNotificationAvoid,
                            onCheckedChange = { value ->
                                update { it.copy(lockscreenWidgetNotificationAvoid = value) }
                            },
                        )
                    }
                }
            }
        }
        item {
            Group(tr("\u9501\u5c4f\u5e95\u90e8\u6587\u672c", "\u9501\u5c4f\u5e95\u90e8\u6587\u672c")) {
                ArrowPreference(
                    title = tr("\u9690\u85cf\u9501\u5c4f\u5e95\u90e8\u6587\u672c", "\u9690\u85cf\u9501\u5c4f\u5e95\u90e8\u6587\u672c"),
                    summary = lockscreenBottomTextSummary(s.lockscreenBottomTextMask),
                    onClick = { showBottomTextDialog = true },
                )
            }
        }
        item {
            Group(tr("\u9501\u5c4f\u8f93\u5165", "\u9501\u5c4f\u8f93\u5165")) {
                SwitchPreference(
                    title = tr("\u9501\u5c4f\u8f93\u5165\u5bc6\u7801\u754c\u9762\u6570\u5b57\u5706\u5f62\u80cc\u666f", "\u9501\u5c4f\u8f93\u5165\u5bc6\u7801\u754c\u9762\u6570\u5b57\u5706\u5f62\u80cc\u666f"),
                    checked = s.lockscreenPinCircleBackgroundEnabled,
                    onCheckedChange = { value ->
                        update { it.copy(lockscreenPinCircleBackgroundEnabled = value) }
                    },
                )
            }
        }
        item {
            Group(tr("\u8ff7\u4f60\u97f3\u4e50\u64ad\u653e\u5668", "\u8ff7\u4f60\u97f3\u4e50\u64ad\u653e\u5668")) {
        SwitchPreference(
            title = tr("\u9501\u5c4f\u8ff7\u4f60\u97f3\u4e50\u64ad\u653e\u5668", "\u9501\u5c4f\u8ff7\u4f60\u97f3\u4e50\u64ad\u653e\u5668"),
            summary = tr("\u663e\u793a\u5728\u5e95\u90e8\u5feb\u6377\u6309\u94ae\u4e4b\u95f4\uff0c\u8ddf\u968f\u5f53\u524d\u5a92\u4f53\u4f1a\u8bdd", "\u663e\u793a\u5728\u5e95\u90e8\u5feb\u6377\u6309\u94ae\u4e4b\u95f4\uff0c\u8ddf\u968f\u5f53\u524d\u5a92\u4f53\u4f1a\u8bdd"),
            checked = s.lockscreenMiniPlayerEnabled,
            onCheckedChange = { value ->
                update {
                    it.copy(
                        lockscreenMiniPlayerEnabled = value,
                    )
                }
            },
        )
        AnimatedVisibility(
            visible = s.lockscreenMiniPlayerEnabled,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
        ) {
            Column {
OverlayDropdownPreference(
                    title = tr("\u9501\u5c4f\u5a92\u4f53\u901a\u77e5", "\u9501\u5c4f\u5a92\u4f53\u901a\u77e5"),
                    items = listOf(tr("\u4e0d\u9690\u85cf", "\u4e0d\u9690\u85cf"), tr("\u59cb\u7ec8\u9690\u85cf", "\u59cb\u7ec8\u9690\u85cf"), tr("\u52a8\u6001\u663e\u793a", "\u52a8\u6001\u663e\u793a")),
                    selectedIndex = s.lockscreenMiniPlayerMediaNotificationMode,
                    onSelectedIndexChange = { value ->
                        update { it.copy(lockscreenMiniPlayerMediaNotificationMode = value) }
                    },
                )
OverlayDropdownPreference(
                    title = tr("\u8ff7\u4f60\u64ad\u653e\u5668\u80cc\u666f", "\u8ff7\u4f60\u64ad\u653e\u5668\u80cc\u666f"),
                    items = listOf(tr("\u8ddf\u968f\u5feb\u6377\u529f\u80fd\u80cc\u666f", "\u8ddf\u968f\u5feb\u6377\u529f\u80fd\u80cc\u666f"), tr("\u7eaf\u8272", "\u7eaf\u8272"), tr("\u9ad8\u7ea7\u6750\u8d28", "\u9ad8\u7ea7\u6750\u8d28"), tr("\u67d4\u5149\u73bb\u7483", "\u67d4\u5149\u73bb\u7483")),
                    selectedIndex = s.lockscreenMiniPlayerBackgroundMode,
                    onSelectedIndexChange = { value ->
                        update { it.copy(lockscreenMiniPlayerBackgroundMode = value) }
                    },
                )
                FloatSlide(tr("\u64ad\u653e\u5668\u5bbd\u5ea6 (dp)", "\u64ad\u653e\u5668\u5bbd\u5ea6 (dp)"), s.lockscreenMiniPlayerWidth, 160f..360f) { value ->
                    update { it.copy(lockscreenMiniPlayerWidth = value) }
                }
                FloatSlide(tr("\u64ad\u653e\u5668\u9ad8\u5ea6 (dp)", "\u64ad\u653e\u5668\u9ad8\u5ea6 (dp)"), s.lockscreenMiniPlayerHeight, 10f..60f) { value ->
                    update { it.copy(lockscreenMiniPlayerHeight = value) }
                }
                FloatSlide(tr("\u5a92\u4f53\u5c01\u9762\u5706\u89d2 (dp)", "\u5a92\u4f53\u5c01\u9762\u5706\u89d2 (dp)"), s.lockscreenMiniPlayerArtworkCornerRadius, 0f..60f) { value ->
                    update { it.copy(lockscreenMiniPlayerArtworkCornerRadius = value) }
                }
            }
        }
        AnimatedVisibility(
            visible = s.lockscreenMiniPlayerEnabled && s.lockscreenMiniPlayerBackgroundMode == 1,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
        ) {
            ShortcutBackgroundColorPreference(
                title = tr("\u64ad\u653e\u5668\u80cc\u666f\u989c\u8272", "\u64ad\u653e\u5668\u80cc\u666f\u989c\u8272"),
                color = s.miniPlayerPureColor,
                onColorChange = { value -> update { it.copy(miniPlayerPureColor = value) } },
            )
        }
        AnimatedVisibility(
            visible = s.lockscreenMiniPlayerEnabled && s.lockscreenMiniPlayerBackgroundMode == 2,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
        ) {
            Column {
                ShortcutBackgroundColorPreference(
                    title = tr("\u64ad\u653e\u5668\u6df7\u8272\u989c\u8272", "\u64ad\u653e\u5668\u6df7\u8272\u989c\u8272"),
                    color = s.miniPlayerAdvancedMaterialColor,
                    onColorChange = { value -> update { it.copy(miniPlayerAdvancedMaterialColor = value) } },
                )
                ParameterIntSlide(tr("\u64ad\u653e\u5668\u4e0d\u900f\u660e\u5ea6", "\u64ad\u653e\u5668\u4e0d\u900f\u660e\u5ea6"), s.miniPlayerAdvancedMaterialOpacity, 0..100, "%") { value ->
                    update { it.copy(miniPlayerAdvancedMaterialOpacity = value) }
                }
                ParameterIntSlide(tr("\u64ad\u653e\u5668\u80cc\u666f\u6a21\u7cca\u5ea6", "\u64ad\u653e\u5668\u80cc\u666f\u6a21\u7cca\u5ea6"), s.miniPlayerAdvancedMaterialBlurRadius, 0..40) { value ->
                    update { it.copy(miniPlayerAdvancedMaterialBlurRadius = value) }
                }
                SwitchPreference(
                    title = tr("\u64ad\u653e\u5668\u663e\u793a\u9ad8\u5149", "\u64ad\u653e\u5668\u663e\u793a\u9ad8\u5149"),
                    checked = s.miniPlayerAdvancedMaterialHighlight,
                    onCheckedChange = { value -> update { it.copy(miniPlayerAdvancedMaterialHighlight = value) } },
                    )
                }
            }
        AnimatedVisibility(
            visible = s.lockscreenMiniPlayerEnabled && s.lockscreenMiniPlayerBackgroundMode == 3,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
        ) {
            Column {
                ShortcutBackgroundColorPreference(
                    title = tr("\u64ad\u653e\u5668\u6df7\u8272\u989c\u8272", "\u64ad\u653e\u5668\u6df7\u8272\u989c\u8272"),
                    color = s.miniPlayerSoftGlassColor,
                    onColorChange = { value -> update { it.copy(miniPlayerSoftGlassColor = value) } },
                )
                ParameterIntSlide(tr("\u64ad\u653e\u5668\u4e0d\u900f\u660e\u5ea6", "\u64ad\u653e\u5668\u4e0d\u900f\u660e\u5ea6"), s.miniPlayerSoftGlassOpacity, 0..100, "%") { value ->
                    update { it.copy(miniPlayerSoftGlassOpacity = value) }
                }
                ParameterIntSlide(tr("\u64ad\u653e\u5668\u80cc\u666f\u6a21\u7cca\u5ea6", "\u64ad\u653e\u5668\u80cc\u666f\u6a21\u7cca\u5ea6"), s.miniPlayerSoftGlassBackdropBlurRadius, 0..40) { value ->
                    update { it.copy(miniPlayerSoftGlassBackdropBlurRadius = value) }
                }
                ParameterIntSlide(tr("\u64ad\u653e\u5668 Glass \u6a21\u7cca\u5ea6", "\u64ad\u653e\u5668 Glass \u6a21\u7cca\u5ea6"), s.miniPlayerSoftGlassBlurRadius, 0..40) { value ->
                    update { it.copy(miniPlayerSoftGlassBlurRadius = value) }
                }
                ParameterFloatSlide(tr("\u64ad\u653e\u5668\u67d4\u5149\u5f3a\u5ea6", "\u64ad\u653e\u5668\u67d4\u5149\u5f3a\u5ea6"), s.miniPlayerSoftGlassLuminance, 0f..0.4f) { value ->
                    update { it.copy(miniPlayerSoftGlassLuminance = value) }
                }
            }
        }
            }
        }
        item {
            Group(tr("\u5feb\u6377\u529f\u80fd\u80cc\u666f", "\u5feb\u6377\u529f\u80fd\u80cc\u666f")) {
OverlayDropdownPreference(
            title = tr("\u9501\u5c4f\u5feb\u6377\u529f\u80fd\u80cc\u666f", "\u9501\u5c4f\u5feb\u6377\u529f\u80fd\u80cc\u666f"),
            items = listOf(tr("\u4e0d\u663e\u793a", "\u4e0d\u663e\u793a"), tr("\u7eaf\u8272", "\u7eaf\u8272"), tr("\u9ad8\u7ea7\u6750\u8d28", "\u9ad8\u7ea7\u6750\u8d28"), tr("\u67d4\u5149\u73bb\u7483", "\u67d4\u5149\u73bb\u7483")),
            selectedIndex = s.lockscreenShortcutBackgroundMode,
            onSelectedIndexChange = { value ->
                update { it.copy(lockscreenShortcutBackgroundMode = value) }
            },
        )
OverlayDropdownPreference(
            title = tr("\u5feb\u6377\u6309\u94ae\u56fe\u6807\u989c\u8272", "\u5feb\u6377\u6309\u94ae\u56fe\u6807\u989c\u8272"),
            items = listOf(tr("\u81ea\u52a8\u6a21\u5f0f", "\u81ea\u52a8\u6a21\u5f0f"), tr("\u6d45\u8272", "\u6d45\u8272"), tr("\u6df1\u8272", "\u6df1\u8272")),
            selectedIndex = s.shortcutIconColorMode,
            onSelectedIndexChange = { value -> update { it.copy(shortcutIconColorMode = value) } },
        )
        AnimatedVisibility(
            visible = s.lockscreenShortcutBackgroundMode != 0,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
        ) {
            Column {
                FloatSlide(tr("\u5706\u5f62\u534a\u5f84 (dp)", "\u5706\u5f62\u534a\u5f84 (dp)"), s.lockscreenShortcutGlassRadius, 10f..60f) { v ->
                    update { it.copy(lockscreenShortcutGlassRadius = v) }
                }
                Dim(
                    title = tr("\u80cc\u666f\u5706\u89d2\u534a\u5f84", "\u80cc\u666f\u5706\u89d2\u534a\u5f84"),
                    enabled = s.lockscreenShortcutBackgroundRadiusEnabled,
                    changeEnabled = { value ->
                        update { it.copy(lockscreenShortcutBackgroundRadiusEnabled = value) }
                    },
                    value = s.lockscreenShortcutBackgroundRadius,
                    range = 0f..60f,
                ) { value ->
                    update { it.copy(lockscreenShortcutBackgroundRadius = value) }
                }
            }
        }
        Dim(
            title = tr("\u5feb\u6377\u6309\u94ae\u95f4\u8ddd", "\u5feb\u6377\u6309\u94ae\u95f4\u8ddd"),
            enabled = s.lockscreenShortcutSpacingEnabled,
            changeEnabled = { value -> update { it.copy(lockscreenShortcutSpacingEnabled = value) } },
            value = s.lockscreenShortcutSpacing,
            range = 0f..48f,
        ) { value ->
            update { it.copy(lockscreenShortcutSpacing = value) }
        }
        Dim(
            title = tr("\u5feb\u6377\u6309\u94ae\u56fe\u6807\u5927\u5c0f", "\u5feb\u6377\u6309\u94ae\u56fe\u6807\u5927\u5c0f"),
            enabled = s.lockscreenShortcutIconSizeEnabled,
            changeEnabled = { value -> update { it.copy(lockscreenShortcutIconSizeEnabled = value) } },
            value = s.lockscreenShortcutIconSize,
            range = 16f..64f,
        ) { value ->
            update { it.copy(lockscreenShortcutIconSize = value) }
        }
        AnimatedVisibility(
            visible = s.lockscreenShortcutBackgroundMode == 1,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
        ) {
            ShortcutBackgroundColorPreference(
                color = s.shortcutPureColor,
                onColorChange = { value -> update { it.copy(shortcutPureColor = value) } },
            )
        }
        AnimatedVisibility(
            visible = s.lockscreenShortcutBackgroundMode == 2,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
        ) {
            Column {
                ShortcutBackgroundColorPreference(
                    color = s.shortcutAdvancedMaterialColor,
                    onColorChange = { value ->
                        update { it.copy(shortcutAdvancedMaterialColor = value) }
                    },
                )
                ParameterIntSlide(
                    title = tr("\u4e0d\u900f\u660e\u5ea6", "\u4e0d\u900f\u660e\u5ea6"),
                    value = s.shortcutAdvancedMaterialOpacity,
                    range = 0..100,
                    suffix = "%",
                ) { value -> update { it.copy(shortcutAdvancedMaterialOpacity = value) } }
                ParameterIntSlide(
                    title = tr("\u80cc\u666f\u6a21\u7cca\u5ea6", "\u80cc\u666f\u6a21\u7cca\u5ea6"),
                    value = s.shortcutAdvancedMaterialBlurRadius,
                    range = 0..40,
                ) { value -> update { it.copy(shortcutAdvancedMaterialBlurRadius = value) } }
                SwitchPreference(
                    title = tr("\u663e\u793a\u9ad8\u5149", "\u663e\u793a\u9ad8\u5149"),
                    checked = s.shortcutAdvancedMaterialHighlight,
                    onCheckedChange = { value ->
                        update { it.copy(shortcutAdvancedMaterialHighlight = value) }
                    },
                )
            }
        }
        AnimatedVisibility(
            visible = s.lockscreenShortcutBackgroundMode == 3,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
        ) {
            Column {
                ShortcutBackgroundColorPreference(
                    color = s.shortcutSoftGlassColor,
                    onColorChange = { value -> update { it.copy(shortcutSoftGlassColor = value) } },
                )
                ParameterIntSlide(
                    title = tr("\u4e0d\u900f\u660e\u5ea6", "\u4e0d\u900f\u660e\u5ea6"),
                    value = s.shortcutSoftGlassOpacity,
                    range = 0..100,
                    suffix = "%",
                ) { value -> update { it.copy(shortcutSoftGlassOpacity = value) } }
                ParameterIntSlide(
                    title = tr("\u80cc\u666f\u6a21\u7cca\u5ea6", "\u80cc\u666f\u6a21\u7cca\u5ea6"),
                    value = s.shortcutSoftGlassBackdropBlurRadius,
                    range = 0..40,
                ) { value -> update { it.copy(shortcutSoftGlassBackdropBlurRadius = value) } }
                ParameterIntSlide(
                    title = tr("Glass \u6a21\u7cca\u5ea6", "Glass \u6a21\u7cca\u5ea6"),
                    value = s.shortcutSoftGlassBlurRadius,
                    range = 0..40,
                ) { value -> update { it.copy(shortcutSoftGlassBlurRadius = value) } }
                ParameterFloatSlide(
                    title = tr("\u67d4\u5149\u5f3a\u5ea6", "\u67d4\u5149\u5f3a\u5ea6"),
                    value = s.shortcutSoftGlassLuminance,
                    range = 0f..0.4f,
                ) { value -> update { it.copy(shortcutSoftGlassLuminance = value) } }
            }
        }
            }
        }
    }
    LockscreenWidgetDeviceNameDialog(
        show = showWidgetDeviceNameDialog,
        current = s.lockscreenWidgetDeviceName.ifBlank { readRoProductMarketName() },
        onDismiss = { showWidgetDeviceNameDialog = false },
        onSave = { value ->
            update { it.copy(lockscreenWidgetDeviceName = value.trim().take(64)) }
            showWidgetDeviceNameDialog = false
        },
    )
    LockScreenBottomTextDialog(
            show = showBottomTextDialog,
            mask = s.lockscreenBottomTextMask,
            onDismiss = { showBottomTextDialog = false },
            onApply = { value ->
                update { it.copy(lockscreenBottomTextMask = value) }
                showBottomTextDialog = false
            },
        )
    LockscreenTemplateLimitDialog(
        show = showLockscreenTemplateLimitDialog,
        current = s.lockscreenTemplateLimitCustom,
        onDismiss = { showLockscreenTemplateLimitDialog = false },
        onSave = { value ->
            update { it.copy(lockscreenTemplateLimitCustom = value.coerceIn(20, 200)) }
            showLockscreenTemplateLimitDialog = false
        },
    )
    }

@Composable
private fun LockscreenTemplateLimitDialog(
    show: Boolean,
    current: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var value by remember(show, current) { mutableStateOf(current.toString()) }
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                tr("\u81ea\u5b9a\u4e49\u9501\u5c4f\u642d\u914d\u6570\u91cf", "\u81ea\u5b9a\u4e49\u9501\u5c4f\u642d\u914d\u6570\u91cf"),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
            )
            TextField(
                value = value,
                onValueChange = { value = it.filter(Char::isDigit).take(3) },
                label = tr("\u6570\u91cf\uff0820-200\uff09", "\u6570\u91cf\uff0820-200\uff09"),
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) {
                    Text(tr("\u53d6\u6d88", "\u53d6\u6d88"))
                }
                GlassDialogButton(
                    onClick = {
                        onSave(value.toIntOrNull()?.coerceIn(20, 200) ?: current.coerceIn(20, 200))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(tr("\u4fdd\u5b58", "\u4fdd\u5b58"))
                }
            }
        }
    }
}

private const val LOCKSCREEN_TEXT_CHARGING = 1
private const val LOCKSCREEN_TEXT_DND = 2
private const val LOCKSCREEN_TEXT_NOTIFICATIONS = 4

@Composable
private fun LockscreenWidgetDeviceNameDialog(
    show: Boolean,
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember(show, current) { mutableStateOf(current) }
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("自定义设备名称", "自定义设备名称"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            TextField(
                value = value,
                onValueChange = { value = it.take(64) },
                label = tr("设备名称", "设备名称"),
                useLabelAsPlaceholder = true,
                singleLine = true,
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = { onSave(value) },
                    enabled = value.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("保存", "保存")) }
            }
        }
    }
}

private data class LockscreenWidgetEditorItem(
    val flag: Int,
    val title: String,
    val kind: LockscreenWidgetEditorKind,
    val wide: Boolean = false,
)

private enum class LockscreenWidgetEditorKind {
    DETAIL_WEATHER, DETAIL_BATTERY, COMPACT_WEATHER, SUN, STEPS, STEPS_WIDE, SIGNATURE,
    SCHEDULE, HUMIDITY, AQI, FEELS_LIKE, WIND, STAND, ALARM,
}

private fun lockscreenWidgetEditorItems() = listOf(
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER, tr("详细天气", "详细天气"), LockscreenWidgetEditorKind.DETAIL_WEATHER, wide = true),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY, tr("详细电量", "详细电量"), LockscreenWidgetEditorKind.DETAIL_BATTERY, wide = true),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_COMPACT_WEATHER, tr("简约天气", "简约天气"), LockscreenWidgetEditorKind.COMPACT_WEATHER, wide = true),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_SUN, tr("日出日落", "日出日落"), LockscreenWidgetEditorKind.SUN),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_STEPS, tr("步数", "步数"), LockscreenWidgetEditorKind.STEPS),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_STEPS_WIDE, tr("步数", "步数"), LockscreenWidgetEditorKind.STEPS_WIDE, wide = true),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_SIGNATURE, tr("签名", "签名"), LockscreenWidgetEditorKind.SIGNATURE, wide = true),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_SCHEDULE, tr("日程", "日程"), LockscreenWidgetEditorKind.SCHEDULE, wide = true),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_HUMIDITY, tr("湿度", "湿度"), LockscreenWidgetEditorKind.HUMIDITY),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_AQI, "AQI", LockscreenWidgetEditorKind.AQI),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_FEELS_LIKE, tr("体感温度", "体感温度"), LockscreenWidgetEditorKind.FEELS_LIKE),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_WIND, tr("风", "风"), LockscreenWidgetEditorKind.WIND),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_STAND, tr("站立", "站立"), LockscreenWidgetEditorKind.STAND),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_ALARM, tr("闹钟", "闹钟"), LockscreenWidgetEditorKind.ALARM),
)

private fun lockscreenWidgetItemsSummary(mask: Int): String = lockscreenWidgetEditorItems()
    .filter { mask and it.flag != 0 }
    .joinToString("、") { it.title }
    .ifBlank { tr("天气、电量", "天气、电量") }

private fun lockscreenWidgetItemsInOrder(mask: Int, order: String): List<LockscreenWidgetEditorItem> {
    val editorItems = lockscreenWidgetEditorItems()
    val byFlag = editorItems.associateBy(LockscreenWidgetEditorItem::flag)
    val orderedFlags = order.split(',').mapNotNull(String::toIntOrNull)
        .filter { it in byFlag && mask and it != 0 }.distinct()
    return (orderedFlags + editorItems.map(LockscreenWidgetEditorItem::flag)
        .filter { mask and it != 0 && it !in orderedFlags })
        .mapNotNull(byFlag::get)
}

private fun lockscreenWidgetCanAdd(
    current: List<LockscreenWidgetEditorItem>,
    candidate: LockscreenWidgetEditorItem,
): Boolean {
    val next = current + candidate
    val longCount = next.count(LockscreenWidgetEditorItem::wide)
    val circleCount = next.size - longCount
    return when (longCount) {
        0 -> circleCount <= 4
        1 -> circleCount <= 2
        2 -> circleCount == 0
        else -> false
    }
}

@Composable
private fun LockscreenWidgetEditor(
    settings: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    back: () -> Unit,
) = AppPage(tr("锁屏小组件", "锁屏小组件"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD)) { padding, scroll ->
    val context = LocalContext.current
    var showSignatureColorPicker by remember { mutableStateOf(false) }
    val selectedItems = remember(settings.lockscreenWidgetItems, settings.lockscreenWidgetOrder) {
        lockscreenWidgetItemsInOrder(settings.lockscreenWidgetItems, settings.lockscreenWidgetOrder)
    }
    val customPreview by produceState<Bitmap?>(
        initialValue = null,
        key1 = settings.lockscreenWidgetPreviewVersion,
    ) {
        value = withContext(Dispatchers.IO) {
            lockscreenWidgetPreviewFile(context)
                .takeIf(File::exists)
                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
        }
    }
    val previewTint by produceState(
        initialValue = ComposeColor(0xFF5F8FC5),
        key1 = customPreview,
        key2 = settings.lockscreenWidgetPreviewVersion,
    ) {
        value = withContext(Dispatchers.Default) {
            val bitmap = customPreview ?: BitmapFactory.decodeResource(
                context.resources,
                R.drawable.lockscreen_widget_preview_background,
            )
            ComposeColor(lockscreenWidgetPreviewTint(bitmap))
        }
    }
    // Only the background image is recorded. The tiles below consume this backdrop as siblings,
    // which avoids the self-sampling RenderNode cycle seen when entering this editor previously.
    val previewBackdrop = rememberLayerBackdrop()
    // The library has its own recorder for the same reason: tiles only consume a sibling layer.
    val libraryBackdrop = rememberLayerBackdrop()
    val pickPreviewBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            copyLockscreenWidgetPreview(context, uri)
            update { it.copy(lockscreenWidgetPreviewVersion = System.currentTimeMillis()) }
        }.onSuccess {
            Toast.makeText(context, tr("预览背景已更新", "预览背景已更新"), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, tr("无法导入预览背景", "无法导入预览背景"), Toast.LENGTH_SHORT).show()
        }
    }
    // Do not constrain the picker by MIME: some document providers omit SVG from their declared
    // types even though the file is valid. Validation happens after the user selects a file.
    val pickSignature = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val type = copyLockscreenWidgetSignature(context, uri)
            update {
                it.copy(
                    lockscreenWidgetSignatureType = type,
                    lockscreenWidgetSignatureVersion = System.currentTimeMillis(),
                )
            }
        }.onSuccess {
            Toast.makeText(context, tr("签名已导入", "签名已导入"), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, tr("仅支持可用的 PNG、SVG 或 Android Vector XML", "仅支持可用的 PNG、SVG 或 Android Vector XML"), Toast.LENGTH_SHORT).show()
        }
    }
    fun setItem(item: LockscreenWidgetEditorItem, enabled: Boolean) {
        val current = settings.lockscreenWidgetItems and LOCKSCREEN_WIDGET_ALL_ITEMS
        val next = if (enabled) current or item.flag else current and item.flag.inv()
        if (enabled && !lockscreenWidgetCanAdd(selectedItems, item)) {
            Toast.makeText(context, tr("当前布局已达到组件上限", "当前布局已达到组件上限"), Toast.LENGTH_SHORT).show()
            return
        }
        if (next != 0) update {
            val order = lockscreenWidgetItemsInOrder(it.lockscreenWidgetItems, it.lockscreenWidgetOrder)
                .map(LockscreenWidgetEditorItem::flag)
                .let { existing -> if (enabled) existing + item.flag else existing.filter { flag -> flag != item.flag } }
            it.copy(lockscreenWidgetItems = next, lockscreenWidgetOrder = order.joinToString(","))
        }
    }
    fun moveItem(item: LockscreenWidgetEditorItem, steps: Int) {
        if (steps == 0) return
        val from = selectedItems.indexOf(item)
        if (from < 0) return
        val to = (from + steps).coerceIn(0, selectedItems.lastIndex)
        if (to == from) return
        val reordered = selectedItems.toMutableList().apply {
            removeAt(from)
            add(to, item)
        }
        update { it.copy(lockscreenWidgetOrder = reordered.joinToString(",") { entry -> entry.flag.toString() }) }
    }
    AppList(padding, scroll, 28) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clickable { pickPreviewBackground.launch(arrayOf("image/*")) },
            ) {
                // Clip only the recorded background image. The widget overlay must be
                // allowed to draw outside the card while dragging or showing its remove button.
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(RoundedCornerShape(30.dp))
                        .background(ComposeColor(0xFF54A9F4))
                        .layerBackdrop(previewBackdrop),
                ) {
                    if (customPreview != null) {
                        Image(
                            bitmap = customPreview!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.lockscreen_widget_preview_background),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                // The selected strip is clipped by the preview card, so a drag cannot draw
                // outside the preview area. The editor limit guarantees it remains one row.
                Box(Modifier.matchParentSize().clip(RoundedCornerShape(30.dp))) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 18.dp, vertical = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (selectedItems.size >= 4) 8.dp else 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        (selectedItems + lockscreenWidgetEditorItems().filter { item -> item !in selectedItems })
                            .forEach { item ->
                                key(item.flag) {
                                    AnimatedVisibility(
                                        visible = item in selectedItems,
                                        enter = slideInVertically(tween(240)) { it } + fadeIn(tween(180)),
                                        exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(150)),
                                    ) {
                                        LockscreenWidgetPreviewTile(
                                            item = item,
                                            removable = true,
                                            onRemove = { setItem(item, false) },
                                            onMove = { steps -> moveItem(item, steps) },
                                            compact = selectedItems.size >= 4,
                                            backdrop = previewBackdrop,
                                            glassTint = previewTint,
                                            signatureType = settings.lockscreenWidgetSignatureType,
                                            signatureColor = settings.lockscreenWidgetSignatureColor,
                                            signatureScale = settings.lockscreenWidgetSignatureScale,
                                            signatureBackground = settings.lockscreenWidgetSignatureBackground,
                                        )
                                    }
                                }
                            }
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 30.dp,
                insideMargin = PaddingValues(horizontal = 18.dp, vertical = 20.dp),
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .matchParentSize()
                            // CardDefaults uses surfaceContainer; record the same default color.
                            .background(MiuixTheme.colorScheme.surfaceContainer)
                            .layerBackdrop(libraryBackdrop),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        lockscreenWidgetEditorItems().forEach { item ->
                            key(item.flag) {
                                AnimatedVisibility(
                                    visible = settings.lockscreenWidgetItems and item.flag == 0,
                                    enter = slideInVertically(tween(240)) { -it } + fadeIn(tween(180)),
                                    exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(150)),
                                ) {
                                    LockscreenWidgetPreviewTile(
                                        item = item,
                                        removable = false,
                                        onAdd = { setItem(item, true) },
                                        backdrop = libraryBackdrop,
                                        glassTint = MiuixTheme.colorScheme.onSurface.let { foreground ->
                                            if (foreground.luminance() < .5f) ComposeColor(0xFF4E545D) else MiuixTheme.colorScheme.surface
                                        }.copy(alpha = .46f),
                                        contentColor = MiuixTheme.colorScheme.onSurface,
                                        signatureType = settings.lockscreenWidgetSignatureType,
                                        signatureColor = settings.lockscreenWidgetSignatureColor,
                                        signatureScale = settings.lockscreenWidgetSignatureScale,
                                        signatureBackground = settings.lockscreenWidgetSignatureBackground,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (settings.lockscreenWidgetItems and LOCKSCREEN_WIDGET_ITEM_SIGNATURE != 0) {
            item {
                Group(tr("签名组件", "签名组件")) {
                    val signatureType = settings.lockscreenWidgetSignatureType
                    ArrowPreference(
                        title = tr("导入签名图片", "导入签名图片"),
                        summary = when (signatureType) {
                            LOCKSCREEN_WIDGET_SIGNATURE_PNG -> "PNG"
                            LOCKSCREEN_WIDGET_SIGNATURE_VECTOR -> "SVG / XML"
                            else -> tr("未导入", "未导入")
                        },
                        onClick = {
                            pickSignature.launch("*/*")
                        },
                    )
                    if (signatureType != LOCKSCREEN_WIDGET_SIGNATURE_NONE) {
                        SliderPreference(
                            value = settings.lockscreenWidgetSignatureScale.toFloat(),
                            onValueChange = { value ->
                                update { it.copy(lockscreenWidgetSignatureScale = value.roundToInt()) }
                            },
                            onValueChangeFinished = {},
                            title = tr("签名缩放", "签名缩放"),
                            valueText = "${settings.lockscreenWidgetSignatureScale}%",
                            valueRange = 25f..200f,
                            steps = 174,
                        )
                        SwitchPreference(
                            title = tr("显示背景", "显示背景"),
                            checked = settings.lockscreenWidgetSignatureBackground,
                            onCheckedChange = { value ->
                                update { it.copy(lockscreenWidgetSignatureBackground = value) }
                            },
                        )
                        if (signatureType == LOCKSCREEN_WIDGET_SIGNATURE_VECTOR) {
                            ArrowPreference(
                                title = tr("签名颜色", "签名颜色"),
                                summary = String.format(Locale.US, "#%06X", settings.lockscreenWidgetSignatureColor and 0x00FFFFFF),
                                onClick = { showSignatureColorPicker = true },
                            )
                        }
                        ArrowPreference(
                            title = tr("移除签名图片", "移除签名图片"),
                            onClick = {
                                removeLockscreenWidgetSignature(context)
                                update {
                                    it.copy(
                                        lockscreenWidgetSignatureType = LOCKSCREEN_WIDGET_SIGNATURE_NONE,
                                        lockscreenWidgetSignatureVersion = System.currentTimeMillis(),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    LockscreenWidgetSignatureColorDialog(
        show = showSignatureColorPicker,
        initialColor = settings.lockscreenWidgetSignatureColor,
        onDismiss = { showSignatureColorPicker = false },
        onConfirm = { color ->
            update { it.copy(lockscreenWidgetSignatureColor = color) }
            showSignatureColorPicker = false
        },
    )
}

@Composable
private fun LockscreenWidgetPreviewTile(
    item: LockscreenWidgetEditorItem,
    removable: Boolean,
    onRemove: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
    onMove: ((Int) -> Unit)? = null,
    compact: Boolean = false,
    backdrop: Backdrop? = null,
    glassTint: ComposeColor = ComposeColor(0x8A5F8FC5),
    contentColor: ComposeColor = ComposeColor.White,
    signatureType: Int = LOCKSCREEN_WIDGET_SIGNATURE_NONE,
    signatureColor: Int = 0xFFFFFFFF.toInt(),
    signatureScale: Int = 100,
    signatureBackground: Boolean = true,
) {
    var dragOffset by remember(item.flag) { mutableFloatStateOf(0f) }
    val animationScope = rememberCoroutineScope()
    val dragFeedback = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = 0f,
            valueRange = -1f..1f,
            visibilityThreshold = .001f,
            initialScale = 1f,
            pressedScale = 1.045f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount ->
                updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f))
            },
        )
    }

    val highlight = remember(animationScope) {
        InteractiveHighlight(animationScope) { size, offset ->
            androidx.compose.ui.geometry.Offset(
                offset.x.coerceIn(0f, size.width),
                offset.y.coerceIn(0f, size.height),
            )
        }
    }
    val width = when {
        item.kind == LockscreenWidgetEditorKind.DETAIL_WEATHER ||
            item.kind == LockscreenWidgetEditorKind.DETAIL_BATTERY -> if (removable) 151.dp else 142.dp
        item.kind == LockscreenWidgetEditorKind.COMPACT_WEATHER ||
            item.kind == LockscreenWidgetEditorKind.SIGNATURE ||
            item.kind == LockscreenWidgetEditorKind.SCHEDULE -> 142.dp
        item.kind == LockscreenWidgetEditorKind.STEPS_WIDE -> 142.dp
        item.wide -> 136.dp
        else -> 64.dp
    }
    val height = when {
        item.wide -> 64.dp
        else -> 64.dp
    }
    val shape = when {
        !item.wide -> CircleShape
        item.kind == LockscreenWidgetEditorKind.DETAIL_WEATHER ||
            item.kind == LockscreenWidgetEditorKind.DETAIL_BATTERY -> RoundedCornerShape(18.dp)
        else -> RoundedCornerShape(32.dp)
    }
    val showSurface = item.kind != LockscreenWidgetEditorKind.SIGNATURE || signatureBackground
    val interactionModifier = if (onMove == null) {
        Modifier.clickable { onAdd?.invoke() }
    } else {
        Modifier.pointerInput(item.flag) {
            detectDragGestures(
                onDragStart = { dragOffset = 0f },
                onDrag = { change, amount ->
                    change.consume()
                    dragOffset = (dragOffset + amount.x).coerceIn(-96.dp.toPx(), 96.dp.toPx())
                },
                onDragEnd = {
                    onMove((dragOffset / 64.dp.toPx()).roundToInt())
                    dragOffset = 0f
                },
                onDragCancel = { dragOffset = 0f },
            )
        }
    }
    Box(Modifier.graphicsLayer { translationX = dragOffset }) {
        Box(
            modifier = Modifier
                .size(width, height)
                .then(if (showSurface) Modifier.clip(shape) else Modifier)
                .then(highlight.gestureModifier)
                .then(dragFeedback.modifier)
                .then(interactionModifier)
                .then(
                    if (showSurface && backdrop != null) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                vibrancy()
                                blur(3.dp.toPx())
                                lens(
                                    8.dp.toPx() + dragFeedback.pressProgress * 10.dp.toPx(),
                                    16.dp.toPx() + dragFeedback.pressProgress * 12.dp.toPx(),
                                    chromaticAberration = true,
                                )
                            },
                            highlight = { Highlight.Default.copy(alpha = .62f + dragFeedback.pressProgress * .3f) },
                            layerBlock = {
                                scaleX = dragFeedback.scaleX
                                scaleY = dragFeedback.scaleY
                            },
                            onDrawSurface = { drawRect(glassTint.copy(alpha = 0.38f)) },
                        ).then(highlight.modifier)
                    } else if (showSurface) {
                        Modifier.background(ComposeColor(0xFFB7B7B7))
                    } else {
                        Modifier
                    }
                ),
        ) {
            LockscreenWidgetPreviewContent(
                item = item,
                modifier = Modifier.matchParentSize().padding(horizontal = if (item.wide) 9.dp else 4.dp),
                signatureType = signatureType,
                signatureColor = signatureColor,
                signatureScale = signatureScale,
                contentColor = contentColor,
            )
        }
        if (removable) {
            val removeDrag = remember(animationScope) {
                DampedDragAnimation(
                    animationScope = animationScope,
                    initialValue = 0f,
                    valueRange = -1f..1f,
                    visibilityThreshold = .001f,
                    initialScale = 1f,
                    pressedScale = 1.08f,
                    onDragStopped = { animateToValue(0f) },
                    onDrag = { size, amount ->
                        updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f))
                    },
                )
            }
            val removeHighlight = remember(animationScope) {
                InteractiveHighlight(animationScope) { size, offset ->
                    androidx.compose.ui.geometry.Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height))
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(6.dp, (-6).dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .then(removeHighlight.gestureModifier)
                    .then(removeDrag.modifier)
                    .then(
                        if (backdrop != null) {
                            Modifier.drawBackdrop(
                                backdrop = backdrop,
                                shape = { CircleShape },
                                effects = {
                                    vibrancy()
                                    blur(3.dp.toPx())
                                    lens(
                                        6.dp.toPx() + removeDrag.pressProgress * 7.dp.toPx(),
                                        12.dp.toPx() + removeDrag.pressProgress * 8.dp.toPx(),
                                        chromaticAberration = true,
                                    )
                                },
                                highlight = { Highlight.Default.copy(alpha = .65f + removeDrag.pressProgress * .3f) },
                                layerBlock = {
                                    scaleX = removeDrag.scaleX
                                    scaleY = removeDrag.scaleY
                                },
                                onDrawSurface = { drawCircle(ComposeColor.White.copy(alpha = 0.38f)) },
                            ).then(removeHighlight.modifier)
                        } else {
                            Modifier.background(ComposeColor(0xBDE5E5E5))
                        }
                    )
                    .clickable { onRemove?.invoke() },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_close_round),
                    contentDescription = tr("删除组件", "删除组件"),
                    modifier = Modifier.size(14.dp),
                    colorFilter = ColorFilter.tint(ComposeColor(0xFF1A1A1A)),
                )
            }
        }
    }
}

private fun lockscreenWidgetPreviewTint(bitmap: Bitmap): Int {
    if (bitmap.width <= 0 || bitmap.height <= 0) return Color.rgb(95, 143, 197)
    val top = (bitmap.height * 0.52f).roundToInt().coerceIn(0, bitmap.height - 1)
    val horizontalStep = (bitmap.width / 36).coerceAtLeast(1)
    val verticalStep = ((bitmap.height - top) / 20).coerceAtLeast(1)
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0L
    for (y in top until bitmap.height step verticalStep) {
        for (x in 0 until bitmap.width step horizontalStep) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.alpha(pixel) == 0) continue
            red += Color.red(pixel)
            green += Color.green(pixel)
            blue += Color.blue(pixel)
            count++
        }
    }
    if (count == 0L) return Color.rgb(95, 143, 197)
    return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
}

@Composable
private fun LockscreenWidgetPreviewContent(
    item: LockscreenWidgetEditorItem,
    modifier: Modifier = Modifier,
    signatureType: Int = LOCKSCREEN_WIDGET_SIGNATURE_NONE,
    signatureColor: Int = 0xFFFFFFFF.toInt(),
    signatureScale: Int = 100,
    contentColor: ComposeColor = ComposeColor.White,
) {
    val textColor = contentColor
    when (item.kind) {
        LockscreenWidgetEditorKind.DETAIL_WEATHER -> Column(
            modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WeatherPreviewIcon(color = textColor)
                Text(tr("22°  多云", "22°  多云"), color = textColor, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
            }
            Text(tr("芙蓉区", "芙蓉区"), color = textColor, style = MiuixTheme.textStyles.body2)
            Text("H: 25°  L: 19°", color = textColor, style = MiuixTheme.textStyles.body2)
        }
        LockscreenWidgetEditorKind.DETAIL_BATTERY -> Column(
            modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_mobile_3_bold), null, Modifier.size(14.dp, 20.dp), colorFilter = ColorFilter.tint(textColor))
                Spacer(Modifier.width(5.dp))
                Text("68%", color = textColor, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
            }
            Text("Xiaomi 15", color = textColor, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(textColor.copy(alpha = .4f))) {
                Box(Modifier.fillMaxWidth(.68f).fillMaxHeight().clip(CircleShape).background(textColor))
            }
        }
        LockscreenWidgetEditorKind.COMPACT_WEATHER -> Row(
            modifier, verticalAlignment = Alignment.CenterVertically,
        ) {
            WeatherPreviewIcon(28.dp, textColor)
            Spacer(Modifier.width(5.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(tr("芙蓉区", "芙蓉区"), color = textColor, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
                Text(tr("22° 多云", "22° 多云"), color = textColor, style = MiuixTheme.textStyles.body2)
            }
        }
        LockscreenWidgetEditorKind.SUN -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(painterResource(R.drawable.ic_widget_sunrise), null, Modifier.size(28.dp, 24.dp), colorFilter = ColorFilter.tint(textColor))
            Text("06:30", color = textColor, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
        }
        LockscreenWidgetEditorKind.STEPS -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("\u25CE", color = textColor, style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
            Text("6240", color = textColor, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
        }
        LockscreenWidgetEditorKind.STEPS_WIDE -> StepsWideWidgetPreview(modifier, textColor)
        LockscreenWidgetEditorKind.HUMIDITY -> MetricWidgetPreview(tr("湿度", "湿度"), "58%", modifier, textColor = textColor)
        LockscreenWidgetEditorKind.AQI -> MetricWidgetPreview("AQI", "42", modifier, textColor = textColor)
        LockscreenWidgetEditorKind.FEELS_LIKE -> MetricWidgetPreview(tr("体感", "体感"), "24°", modifier, textColor = textColor)
        LockscreenWidgetEditorKind.WIND -> MetricWidgetPreview("↗", tr("东北风 3级", "东北风 3级"), modifier, valueSmall = true, textColor = textColor)
        LockscreenWidgetEditorKind.STAND -> MetricWidgetPreview(tr("站立", "站立"), "8", modifier, textColor = textColor)
        LockscreenWidgetEditorKind.ALARM -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(painterResource(R.drawable.ic_widget_alarm), null, Modifier.size(30.dp), colorFilter = ColorFilter.tint(textColor))
            Text("07:30", color = textColor, style = MiuixTheme.textStyles.body2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        LockscreenWidgetEditorKind.SCHEDULE -> Column(
            modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(tr("日程", "日程"), color = textColor.copy(alpha = .78f), style = MiuixTheme.textStyles.body2, maxLines = 1)
            Text(tr("无日程", "无日程"), color = textColor, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LockscreenWidgetEditorKind.SIGNATURE -> LockscreenWidgetSignaturePreview(
            type = signatureType,
            color = signatureColor,
            scale = signatureScale,
            modifier = modifier,
            fallbackColor = textColor,
        )
    }
}

@Composable
private fun StepsWideWidgetPreview(modifier: Modifier, textColor: ComposeColor) {
    Row(modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(tr("今日步数", "今日步数"), color = textColor.copy(alpha = .78f), style = MiuixTheme.textStyles.body2)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("7256", color = textColor, style = MiuixTheme.textStyles.title2, fontSize = 21.6.sp, fontWeight = FontWeight.Bold)
                Text(tr("步", "步"), color = textColor, style = MiuixTheme.textStyles.body2, fontSize = 11.7.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp))
            }
        }
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                val stroke = 5.dp.toPx()
                drawArc(textColor.copy(alpha = .28f), -50f, 280f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawArc(textColor, -50f, 280f * (7256f / 10000f), false, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            }
            Image(
                painter = painterResource(R.drawable.ic_widget_steps),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                colorFilter = ColorFilter.tint(textColor),
            )
        }
    }
}

@Composable
private fun MetricWidgetPreview(
    marker: String,
    value: String,
    modifier: Modifier,
    valueSmall: Boolean = false,
    textColor: ComposeColor = ComposeColor.White,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(marker, color = textColor, style = MiuixTheme.textStyles.body2, fontWeight = FontWeight.Bold)
        Text(
            value,
            color = textColor,
            style = MiuixTheme.textStyles.body2,
            fontSize = if (valueSmall) 9.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun LockscreenWidgetSignaturePreview(
    type: Int,
    color: Int,
    scale: Int,
    modifier: Modifier = Modifier,
    fallbackColor: ComposeColor = ComposeColor.White,
) {
    val context = LocalContext.current
    if (type == LOCKSCREEN_WIDGET_SIGNATURE_NONE) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(tr("签名", "签名"), color = fallbackColor, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
        }
        return
    }
    AndroidView(
        factory = { viewContext ->
            ImageView(viewContext).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        },
        update = { image ->
            image.setImageDrawable(LogoDrawableLoader.loadFile(context, lockscreenWidgetSignatureFile(context))?.apply {
                if (type == LOCKSCREEN_WIDGET_SIGNATURE_VECTOR) {
                    setColorFilter(color, PorterDuff.Mode.SRC_IN)
                }
            })
            image.imageTintList = null
            val factor = scale.coerceIn(25, 200) / 100f
            image.scaleX = factor
            image.scaleY = factor
        },
        modifier = modifier,
    )
}

@Composable
private fun WeatherPreviewIcon(size: Dp = 24.dp, color: ComposeColor = ComposeColor.White) {
    Image(
        painter = painterResource(R.drawable.ic_widget_weather_preview),
        contentDescription = null,
        modifier = Modifier.size(size),
        colorFilter = ColorFilter.tint(color),
    )
}

private fun lockscreenBottomTextSummary(mask: Int): String = listOf(
    LOCKSCREEN_TEXT_CHARGING to tr("\u5145\u7535\u4e2d", "\u5145\u7535\u4e2d"),
    LOCKSCREEN_TEXT_DND to tr("\u52ff\u6270", "\u52ff\u6270"),
    LOCKSCREEN_TEXT_NOTIFICATIONS to tr("X\u4e2a\u901a\u77e5", "X\u4e2a\u901a\u77e5"),
).filter { mask and it.first != 0 }.joinToString(" / ") { it.second }.ifBlank { tr("\u672a\u9690\u85cf", "\u672a\u9690\u85cf") }

@Composable
private fun LockScreenBottomTextDialog(
    show: Boolean,
    mask: Int,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit,
) {
    var selected by remember(show, mask) { mutableIntStateOf(mask) }
    val items = listOf(
        LOCKSCREEN_TEXT_CHARGING to tr("\u5145\u7535\u4e2d", "\u5145\u7535\u4e2d"),
        LOCKSCREEN_TEXT_DND to tr("\u52ff\u6270", "\u52ff\u6270"),
        LOCKSCREEN_TEXT_NOTIFICATIONS to tr("X\u4e2a\u901a\u77e5", "X\u4e2a\u901a\u77e5"),
    )
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("\u9009\u62e9\u8981\u9690\u85cf\u7684\u9501\u5c4f\u5e95\u90e8\u6587\u672c", "\u9009\u62e9\u8981\u9690\u85cf\u7684\u9501\u5c4f\u5e95\u90e8\u6587\u672c"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            items.forEach { (bit, title) ->
                val toggle = { selected = if (selected and bit != 0) selected and bit.inv() else selected or bit }
                Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state = if (selected and bit != 0) ToggleableState.On else ToggleableState.Off, onClick = toggle)
                    Text(title, style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(start = 10.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("\u53d6\u6d88", "\u53d6\u6d88")) }
                GlassDialogButton({ onApply(selected) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("\u5e94\u7528", "\u5e94\u7528")) }
            }
        }
    }
}

@Composable
private fun RasterWallpaper(
    s: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    onPickImages: () -> Unit,
    onApply: () -> Unit,
    back: () -> Unit,
) = AppPage(tr("\u5149\u6805\u58c1\u7eb8", "\u5149\u6805\u58c1\u7eb8"), back) { p, scroll ->
    val context = LocalContext.current
    val sources = s.rasterWallpaperUriList()
    val sensitivityItems = listOf(tr("\u4f4e", "\u4f4e"), tr("\u6807\u51c6", "\u6807\u51c6"), tr("\u9ad8", "\u9ad8"), tr("\u8d85\u9ad8", "\u8d85\u9ad8"), tr("\u81ea\u5b9a\u4e49", "\u81ea\u5b9a\u4e49"))
    AppList(p, scroll, 28) {
        item {
            Group(tr("\u7d20\u6750", "\u7d20\u6750")) {
                ArrowPreference(
                    title = tr("\u6279\u91cf\u9009\u62e9\u56fe\u7247\u6216\u77ed\u89c6\u9891", "\u6279\u91cf\u9009\u62e9\u56fe\u7247\u6216\u77ed\u89c6\u9891"),
                    summary = if (sources.isEmpty()) tr("\u8bf7\u9009\u62e9 2\u20134 \u4e2a\u7d20\u6750", "\u8bf7\u9009\u62e9 2\u20134 \u4e2a\u7d20\u6750") else "${sources.size}/4 \u4e2a\u7d20\u6750",
                    onClick = onPickImages,
                )
            }
        }
        item {
            Group(tr("\u4f53\u611f", "\u4f53\u611f")) {
OverlayDropdownPreference(
                    title = tr("\u503e\u659c\u7075\u654f\u5ea6", "\u503e\u659c\u7075\u654f\u5ea6"),
                    items = sensitivityItems,
                    selectedIndex = s.rasterWallpaperSensitivityPreset.coerceIn(0, 4),
                    onSelectedIndexChange = { value -> update { it.copy(rasterWallpaperSensitivityPreset = value) } },
                )
                AnimatedVisibility(visible = s.rasterWallpaperSensitivityPreset == 4) {
                    ParameterFloatSlide(
                        title = tr("\u81ea\u5b9a\u4e49\u7075\u654f\u5ea6", "\u81ea\u5b9a\u4e49\u7075\u654f\u5ea6"),
                        value = s.rasterWallpaperCustomSensitivity,
                        range = 0.1f..2f,
                    ) { value -> update { it.copy(rasterWallpaperCustomSensitivity = value) } }
                }
            }
        }
        item {
            Group(tr("\u5e94\u7528", "\u5e94\u7528")) {
                Button(
                    onClick = {
                        if (sources.size < 2) {
                            Toast.makeText(context, tr("至少选择 2 个素材", "至少选择 2 个素材"), Toast.LENGTH_SHORT).show()
                        } else {
                            onApply()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("\u5e94\u7528", "\u5e94\u7528")) }
            }
        }
    }
}

@Composable
private fun ShortcutBackgroundColorPreference(
    title: String = tr("\u80cc\u666f\u989c\u8272", "\u80cc\u666f\u989c\u8272"),
    color: Int,
    onColorChange: (Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { showPicker = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body1,
        )
        Box(
            Modifier.size(28.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = .45f), CircleShape)
                .padding(2.dp)
                .background(ComposeColor(color), CircleShape),
        )
    }
    ShortcutBackgroundColorDialog(
        show = showPicker,
        initialColor = color,
        onDismiss = { showPicker = false },
        onConfirm = { selected ->
            onColorChange(selected)
            showPicker = false
        },
    )
}

@Composable
private fun ShortcutBackgroundColorDialog(
    show: Boolean,
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var draftColor by remember(show, initialColor) { mutableIntStateOf(initialColor) }
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                tr("\u9009\u62e9\u80cc\u666f\u989c\u8272", "\u9009\u62e9\u80cc\u666f\u989c\u8272"),
                modifier = Modifier.fillMaxWidth(),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start,
            )
            ColorPalette(
                color = ComposeColor(draftColor or 0xFF000000.toInt()),
                onColorChanged = { selected ->
                    draftColor = (draftColor and 0xFF000000.toInt()) or (selected.toArgb() and 0x00FFFFFF)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("\u53d6\u6d88", "\u53d6\u6d88")) }
                GlassDialogButton(
                    onClick = { onConfirm(draftColor) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("\u786e\u5b9a", "\u786e\u5b9a")) }
            }
        }
    }
}

@Composable
private fun LockscreenWidgetSignatureColorDialog(
    show: Boolean,
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var draftColor by remember(show, initialColor) { mutableIntStateOf(initialColor) }
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                tr("选择签名颜色", "选择签名颜色"),
                modifier = Modifier.fillMaxWidth(),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
            )
            ColorPalette(
                color = ComposeColor(draftColor or 0xFF000000.toInt()),
                onColorChanged = { selected ->
                    draftColor = (draftColor and 0xFF000000.toInt()) or (selected.toArgb() and 0x00FFFFFF)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = { onConfirm(draftColor) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("确定", "确定")) }
            }
        }
    }
}

@Composable
private fun Camera(c: CameraSettings, update: ((CameraSettings) -> CameraSettings) -> Unit, openPage: (PageId) -> Unit, back: () -> Unit) = AppPage(tr("\u76f8\u673a\u4e0e\u76f8\u518c\u7f16\u8f91", "\u76f8\u673a\u4e0e\u76f8\u518c\u7f16\u8f91"), back, restartScopes = setOf(ScopeApplication.CAMERA, ScopeApplication.GALLERY, ScopeApplication.MEDIA_EDITOR)) { p, scroll ->
    AppList(p, scroll, 28) { item { Card(Modifier.fillMaxWidth()) {
        SwitchPreference(title = tr("\u542f\u7528\u76f8\u673a\u6a21\u5757", "\u542f\u7528\u76f8\u673a\u6a21\u5757"), checked = c.masterEnabled, onCheckedChange = { v -> update { it.copy(masterEnabled = v) } })
        SwitchPreference(title = tr("leica_lcc_ui", "Leica LCC UI"), checked = c.leicaUi, enabled = c.masterEnabled, onCheckedChange = { v -> update { it.copy(leicaUi = v) } })
        SwitchPreference(title = tr("\u4fdd\u7559\u539f\u751f\u7126\u6bb5", "\u4fdd\u7559\u539f\u751f\u7126\u6bb5"), checked = c.preserveNativeFocalLengths, enabled = c.masterEnabled, onCheckedChange = { v -> update { it.copy(preserveNativeFocalLengths = v) } })
OverlayDropdownPreference(
            title = tr("\u4e00\u77ac\u529f\u80fd", "\u4e00\u77ac\u529f\u80fd"),
            items = listOf(tr("\u5f95\u5361\u4e00\u77ac", "\u5f95\u5361\u4e00\u77ac"), tr("\u4f20\u5947\u4e00\u77ac", "\u4f20\u5947\u4e00\u77ac")),
            selectedIndex = c.instantMode.coerceIn(0, 1),
            onSelectedIndexChange = { i -> update { it.copy(instantMode = i.coerceIn(0, 1)) } },
        )
        SwitchPreference(title = tr("\u76f8\u518c\u7f16\u8f91\u5168\u6c34\u5370", "\u76f8\u518c\u7f16\u8f91\u5168\u6c34\u5370"), checked = c.galleryAllWatermarks, onCheckedChange = { v -> update { it.copy(galleryAllWatermarks = v) } })
    } } }
}

@Composable
private fun CameraPalette(
    c: CameraSettings,
    update: ((CameraSettings) -> CameraSettings) -> Unit,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val importImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { decodePaletteBitmap(context, uri) } }
                .onSuccess { sourceBitmap = it }
                .onFailure { Toast.makeText(context, tr("无法导入图片", "无法导入图片"), Toast.LENGTH_SHORT).show() }
        }
    }
    val exportImage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val bitmap = sourceBitmap ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val success = withContext(Dispatchers.Default) {
                runCatching {
                    val rendered = renderPaletteBitmap(bitmap, c)
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        check(rendered.compress(Bitmap.CompressFormat.JPEG, 95, output))
                    } ?: error(tr("无法写入文件", "无法写入文件"))
                }.isSuccess
            }
            Toast.makeText(context, if (success) tr("图片已导出", "图片已导出") else tr("导出失败", "导出失败"), Toast.LENGTH_SHORT).show()
        }
    }
    AppPage(
        title = tr("调色盘", "调色盘"),
        onBack = back,
        compactTopBar = true,
        actions = {
            GlassActionButton(onClick = { importImage.launch(arrayOf("image/*")) }) {
                Image(MiuixIcons.Regular.Import, tr("导入图片", "导入图片"), Modifier.size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface))
            }
            GlassActionButton(enabled = sourceBitmap != null, onClick = { exportImage.launch(tr("HyperChanger_色彩编辑.jpg", "HyperChanger_色彩编辑.jpg")) }) {
                Image(MiuixIcons.Regular.Download, tr("导出图片", "导出图片"), Modifier.size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurface))
            }
        },
    ) { padding, scroll ->
        Column(
            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(top = padding.calculateTopPadding()),
        ) {
            PaletteImagePreview(
                source = sourceBitmap,
                settings = c,
                onImport = { importImage.launch(arrayOf("image/*")) },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Column(Modifier.fillMaxWidth().height(330.dp)) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (selectedTab) {
                        0 -> PaletteColorTab(c, update, scroll)
                        1 -> PaletteAdjustmentTab(c, update, scroll)
                        else -> PaletteSkinProtectionTab(c, update, scroll)
                    }
                }
                TabRowWithContour(
                    tabs = listOf(tr("颜色", "颜色"), tr("调整", "调整"), tr("肤色保护", "肤色保护")),
                    selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun PaletteImagePreview(
    source: Bitmap?,
    settings: CameraSettings,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview by produceState<Bitmap?>(initialValue = null, source, settings) {
        value = source?.let { withContext(Dispatchers.Default) { renderPaletteBitmap(it, settings) } }
    }
    Box(
        modifier.clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (preview == null) {
            IconButton(onClick = onImport, modifier = Modifier.size(56.dp)) {
                Image(MiuixIcons.Regular.Import, tr("导入图片", "导入图片"), Modifier.size(26.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.primary))
            }
        } else {
            Image(BitmapPainter(preview!!.asImageBitmap()), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun PaletteColorTab(c: CameraSettings, update: ((CameraSettings) -> CameraSettings) -> Unit, scroll: ScrollBehavior) {
    LazyColumn(
        Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Group(tr("颜色", "颜色")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.paletteFilter, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                        Text("${tr("tone", "色调")} ${signedPaletteValue(c.paletteTone)}  ·  ${tr("color", "色彩")} ${signedPaletteValue(c.paletteColor)}  ·  ${tr("intensity", "强度")} ${(c.paletteIntensity * 100).toInt()}%", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(onClick = { update { it.copy(paletteTone = 0f, paletteColor = 0f, paletteIntensity = 1f, paletteFilter = "自然") } }) { Text(tr("重置", "重置")) }
                }
                PaletteStylePad(c.paletteTone, c.paletteColor) { tone, color -> update { it.copy(paletteTone = tone, paletteColor = color) } }
                PaletteSlider(tr("风格强度", "风格强度"), c.paletteIntensity, 0f..1f, "${(c.paletteIntensity * 100).toInt()}%") { value -> update { it.copy(paletteIntensity = value) } }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    items(paletteStyles) { style ->
                        val selected = c.paletteFilter == style.id
                        Button(onClick = { update { it.copy(paletteFilter = style.id, paletteTone = 0f, paletteColor = 0f, paletteIntensity = 1f) } }, colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(), modifier = Modifier.width(72.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(width = 46.dp, height = 28.dp).clip(RoundedCornerShape(8.dp)).background(Brush.linearGradient(style.colors)))
                                Spacer(Modifier.height(4.dp)); Text(tr(style.id, style.id), style = MiuixTheme.textStyles.body2)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteAdjustmentTab(c: CameraSettings, update: ((CameraSettings) -> CameraSettings) -> Unit, scroll: ScrollBehavior) {
    LazyColumn(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item {
            Group(tr("调整", "调整")) {
                PaletteSlider(tr("曝光", "曝光"), c.paletteExposure, -1f..1f, signedExposure(c.paletteExposure)) { value -> update { it.copy(paletteExposure = value) } }
                PaletteSlider(tr("对比度", "对比度"), c.paletteContrast, 0f..2f, "${(c.paletteContrast * 100).toInt()}%") { value -> update { it.copy(paletteContrast = value) } }
                PaletteSlider(tr("饱和度", "饱和度"), c.paletteSaturation, 0f..1.8f, "${(c.paletteSaturation * 100).toInt()}%") { value -> update { it.copy(paletteSaturation = value) } }
                PaletteSlider(tr("暖色", "暖色"), c.paletteWarmth, -1f..1f, signedPaletteValue(c.paletteWarmth)) { value -> update { it.copy(paletteWarmth = value) } }
            }
        }
    }
}

@Composable
private fun PaletteSkinProtectionTab(c: CameraSettings, update: ((CameraSettings) -> CameraSettings) -> Unit, scroll: ScrollBehavior) {
    LazyColumn(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item {
            Group(tr("肤色保护", "肤色保护")) {
                SwitchPreference(title = tr("肤色保护", "肤色保护"), summary = tr("保留肤色区域的原始色彩", "保留肤色区域的原始色彩"), checked = c.skinToneProtection, onCheckedChange = { enabled -> update { it.copy(skinToneProtection = enabled) } })
                if (c.skinToneProtection) {
                    PaletteSlider(tr("保护强度", "保护强度"), c.skinToneProtectionAmount, 0f..1f, "${(c.skinToneProtectionAmount * 100).toInt()}%") { value -> update { it.copy(skinToneProtectionAmount = value) } }
                }
            }
        }
    }
}

private fun decodePaletteBitmap(context: android.content.Context, uri: Uri): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
        requireNotNull(descriptor) { tr("无法读取图片", "无法读取图片") }
        BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
    }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { tr("不支持的图片格式", "不支持的图片格式") }
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 1280 || bounds.outHeight / sampleSize > 1280) sampleSize *= 2
    return context.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
        requireNotNull(descriptor) { tr("无法读取图片", "无法读取图片") }
        BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    } ?: error(tr("无法解码图片", "无法解码图片"))
}

private fun renderPaletteBitmap(source: Bitmap, settings: CameraSettings): Bitmap {
    val matrix = ColorMatrix()
    val brightness = settings.paletteExposure * 42f
    matrix.postConcat(ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, brightness,
        0f, 1f, 0f, 0f, brightness,
        0f, 0f, 1f, 0f, brightness,
        0f, 0f, 0f, 1f, 0f,
    )))
    val contrastOffset = (1f - settings.paletteContrast) * 128f
    matrix.postConcat(ColorMatrix(floatArrayOf(
        settings.paletteContrast, 0f, 0f, 0f, contrastOffset,
        0f, settings.paletteContrast, 0f, 0f, contrastOffset,
        0f, 0f, settings.paletteContrast, 0f, contrastOffset,
        0f, 0f, 0f, 1f, 0f,
    )))
    matrix.postConcat(ColorMatrix().apply { setSaturation(settings.paletteSaturation) })
    matrix.postConcat(ColorMatrix(floatArrayOf(
        1f + settings.paletteWarmth * .12f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f - settings.paletteWarmth * .14f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )))
    when (settings.paletteFilter) {
        "鲜明" -> matrix.postConcat(ColorMatrix(floatArrayOf(1.13f, 0f, 0f, 0f, -10f, 0f, 1.13f, 0f, 0f, -10f, 0f, 0f, 1.13f, 0f, -10f, 0f, 0f, 0f, 1f, 0f)))
        "暖阳" -> matrix.postConcat(ColorMatrix(floatArrayOf(1.08f, 0f, 0f, 0f, 8f, 0f, 1.01f, 0f, 0f, 3f, 0f, 0f, .92f, 0f, -4f, 0f, 0f, 0f, 1f, 0f)))
        "冷冽" -> matrix.postConcat(ColorMatrix(floatArrayOf(.93f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1.10f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)))
        "柔和" -> matrix.postConcat(ColorMatrix(floatArrayOf(.90f, 0f, 0f, 0f, 14f, 0f, .90f, 0f, 0f, 14f, 0f, 0f, .90f, 0f, 14f, 0f, 0f, 0f, 1f, 0f)))
        "黑白" -> matrix.postConcat(ColorMatrix().apply { setSaturation(0f) })
    }
    val tone = settings.paletteTone * settings.paletteIntensity
    val chroma = settings.paletteColor * settings.paletteIntensity
    matrix.postConcat(ColorMatrix(floatArrayOf(
        1f + chroma * .12f, 0f, 0f, 0f, tone * 12f,
        0f, 1f, 0f, 0f, tone * 12f,
        0f, 0f, 1f - chroma * .14f, 0f, tone * 12f,
        0f, 0f, 0f, 1f, 0f,
    )))
    val adjusted = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    Canvas(adjusted).drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(matrix)
        isFilterBitmap = true
    })
    if (!settings.skinToneProtection || settings.skinToneProtectionAmount <= 0f) return adjusted

    val pixelCount = source.width * source.height
    val originalPixels = IntArray(pixelCount)
    val adjustedPixels = IntArray(pixelCount)
    source.getPixels(originalPixels, 0, source.width, 0, 0, source.width, source.height)
    adjusted.getPixels(adjustedPixels, 0, source.width, 0, 0, source.width, source.height)
    val hsv = FloatArray(3)
    originalPixels.indices.forEach { index ->
        val original = originalPixels[index]
        Color.colorToHSV(original, hsv)
        val isSkin = hsv[0] in 0f..50f && hsv[1] in .18f..0.68f && hsv[2] in .22f..1f
        if (isSkin) adjustedPixels[index] = blendPaletteColor(adjustedPixels[index], original, settings.skinToneProtectionAmount)
    }
    adjusted.setPixels(adjustedPixels, 0, source.width, 0, 0, source.width, source.height)
    return adjusted
}

private fun blendPaletteColor(adjusted: Int, original: Int, protection: Float): Int {
    val amount = protection.coerceIn(0f, 1f)
    fun mix(first: Int, second: Int) = (first * (1f - amount) + second * amount).toInt().coerceIn(0, 255)
    return Color.argb(mix(Color.alpha(adjusted), Color.alpha(original)), mix(Color.red(adjusted), Color.red(original)), mix(Color.green(adjusted), Color.green(original)), mix(Color.blue(adjusted), Color.blue(original)))
}

private data class PaletteStyle(val id: String, val colors: List<ComposeColor>)

private val paletteStyles = listOf(
    PaletteStyle("自然", listOf(ComposeColor(0xFF8DB0CA), ComposeColor(0xFFE0A375))),
    PaletteStyle("鲜明", listOf(ComposeColor(0xFF456B8A), ComposeColor(0xFFE59049))),
    PaletteStyle("暖阳", listOf(ComposeColor(0xFFE9B36D), ComposeColor(0xFFC15F55))),
    PaletteStyle("冷冽", listOf(ComposeColor(0xFF447A9B), ComposeColor(0xFFB9D4DF))),
    PaletteStyle("柔和", listOf(ComposeColor(0xFFB7A8B1), ComposeColor(0xFFE1CBB4))),
    PaletteStyle("黑白", listOf(ComposeColor(0xFF202126), ComposeColor(0xFFCDCDD0))),
)

private fun signedPaletteValue(value: Float): String {
    val number = (value * 100).toInt()
    return if (number > 0) "+$number" else number.toString()
}

private fun signedExposure(value: Float): String = if (value >= 0f) "+${"%.1f".format(Locale.US, value)}" else "%.1f".format(Locale.US, value)

@Composable
private fun PaletteSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onValueFinished: (Float) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value) }
    SliderPreference(
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { onValueFinished(current.coerceIn(range.start, range.endInclusive)) },
        title = title,
        valueText = valueText,
        valueRange = range,
        steps = ((range.endInclusive - range.start) * 20f).toInt().coerceAtLeast(0) - 1,
    )
}

@Composable
private fun PaletteStylePad(tone: Float, color: Float, onChange: (Float, Float) -> Unit) {
    fun update(offset: Offset, width: Float, height: Float) {
        if (width > 0f && height > 0f) onChange(
            (1f - offset.y / height * 2f).coerceIn(-1f, 1f),
            (offset.x / width * 2f - 1f).coerceIn(-1f, 1f),
        )
    }
    Canvas(
        Modifier.fillMaxWidth().height(96.dp).padding(top = 12.dp).clip(RoundedCornerShape(16.dp)).pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { update(it, size.width.toFloat(), size.height.toFloat()) },
                onDrag = { change, _ -> change.consume(); update(change.position, size.width.toFloat(), size.height.toFloat()) },
            )
        },
    ) {
        drawRect(Brush.horizontalGradient(listOf(ComposeColor(0xFF6B91C8), ComposeColor(0xFFBBB1A7), ComposeColor(0xFFD68C59))))
        drawRect(Brush.verticalGradient(listOf(ComposeColor.White.copy(.46f), ComposeColor.Transparent, ComposeColor.Black.copy(.56f))))
        drawLine(ComposeColor.White.copy(.28f), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1.dp.toPx())
        drawLine(ComposeColor.White.copy(.28f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.dp.toPx())
        val point = Offset((color + 1f) * size.width / 2f, (1f - tone) * size.height / 2f)
        drawCircle(ComposeColor.Black.copy(.3f), 11.dp.toPx(), point)
        drawCircle(ComposeColor.White, 8.dp.toPx(), point, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}

@Composable
private fun Dim(title: String, enabled: Boolean, changeEnabled: (Boolean) -> Unit, value: Float, range: ClosedFloatingPointRange<Float>, save: (Float) -> Unit) {
    SwitchPreference(title = tr("\u81ea\u5b9a\u4e49$title", "\u81ea\u5b9a\u4e49$title"), checked = enabled, onCheckedChange = changeEnabled)
    if (enabled) FloatSlide(title, value, range, save)
}

@Composable
private fun DeltaDim(title: String, value: Float, save: (Float) -> Unit) {
    FloatSlide(title, value, -35f..35f, save)
}

@Composable
private fun Corner(title: String, enabled: Boolean, changeEnabled: (Boolean) -> Unit, value: Float, save: (Float) -> Unit) {
    SwitchPreference(title = tr("\u81ea\u5b9a\u4e49$title\u5706\u89d2", "\u81ea\u5b9a\u4e49$title\u5706\u89d2"), checked = enabled, onCheckedChange = changeEnabled)
    if (enabled) FloatSlide(title, value, 0f..60f, save)
}

@Composable
private fun FloatSlide(title: String, value: Float, range: ClosedFloatingPointRange<Float>, save: (Float) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value) }
    SliderPreference(value = current, onValueChange = { current = it }, onValueChangeFinished = { save((current * 10f).toInt() / 10f) }, title = title, valueText = "${(current * 10f).toInt() / 10f} dp", valueRange = range, steps = ((range.endInclusive - range.start) * 10f).toInt() - 1)
}

@Composable
private fun IntSlide(title: String, value: Int, range: IntRange, save: (Int) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    SliderPreference(value = current, onValueChange = { current = it }, onValueChangeFinished = { save(current.toInt().coerceIn(range.first, range.last)) }, title = title, valueText = "${current.toInt()} dp", valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1)
}

@Composable
private fun ParameterIntSlide(
    title: String,
    value: Int,
    range: IntRange,
    suffix: String = "",
    save: (Int) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    SliderPreference(
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { save(current.toInt().coerceIn(range.first, range.last)) },
        title = title,
        valueText = "${current.toInt()}$suffix",
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
    )
}

@Composable
private fun ParameterFloatSlide(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    save: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    SliderPreference(
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { save((current * 100f).toInt() / 100f) },
        title = title,
        valueText = "${(current * 100f).toInt() / 100f}",
        valueRange = range,
        steps = ((range.endInclusive - range.start) * 100f).toInt() - 1,
    )
}

@Composable
private fun About(back: () -> Unit, openPage: (PageId) -> Unit, onDebugMode: () -> Unit) = AppPage(tr("\u5173\u4e8e", "\u5173\u4e8e"), back) { padding, scroll ->
    val context = LocalContext.current
    var iconTaps by remember { mutableIntStateOf(0) }
    AppList(padding, scroll, 28) {
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Image(painterResource(R.drawable.ic_hyperchanger_full), "HyperChanger", Modifier.size(72.dp).clickable {
                    iconTaps++
                    if (iconTaps >= 10) { iconTaps = 0; onDebugMode() }
                }, contentScale = ContentScale.Fit)
                Text("HyperChanger", style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                Text(tr("\u4e00\u4e2a\u4e34\u65f6\u7528\u4e8e\u89e3\u9501\u5c0f\u7c73\u6f8e\u6e43 OS 4 Beta \u7248\u9650\u5236\u7684\u6a21\u5757\u3002", "\u4e00\u4e2a\u4e34\u65f6\u7528\u4e8e\u89e3\u9501\u5c0f\u7c73\u6f8e\u6e43 OS 4 Beta \u7248\u9650\u5236\u7684\u6a21\u5757\u3002"), style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(top = 6.dp))
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(MiuixTheme.colorScheme.outline.copy(alpha = .22f)))
                Text(BuildConfig.VERSION_NAME, style = MiuixTheme.textStyles.body2)
            }
        }
        item {
            Group(tr("\u5f00\u53d1\u8005", "\u5f00\u53d1\u8005")) {
                Row(Modifier.fillMaxWidth().clickable { openUrl(context, "https://btm-m.site") }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.btm_m_avatar), "btm_m", Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape), contentScale = ContentScale.Crop)
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text("btm_m", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                        Text("https://btm-m.site", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 3.dp))
                    }
                    Image(MiuixIcons.Regular.ChevronForward, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary))
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(title = tr("license", "LICENSE"), summary = "Apache License 2.0", onClick = { openPage(PageId.LICENSE) })
                ArrowPreference(title = tr("githubRepository", "GitHub Repository"), summary = "github.com/ColdP/HyperChanger", onClick = { openUrl(context, "https://github.com/ColdP/HyperChanger") })
                ArrowPreference(title = tr("telegramGroup", "Telegram 群组"), summary = "t.me/HyperChanger", onClick = { openUrl(context, "https://t.me/HyperChanger") })
            }
        }
        item { Text("\u00a9 ${Year.now().value} btm_m", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .56f), modifier = Modifier.padding(start = 12.dp)) }
    }
}

@Composable
private fun OsWarningDialog(show: Boolean, version: String, captcha: String, captchaInput: String, disclaimerRead: Boolean, preview: Boolean, onCaptchaInput: (String) -> Unit, onDisclaimer: () -> Unit, onConfirm: () -> Unit, onExit: () -> Unit) {
    WindowDialog(show = show, onDismissRequest = {}) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("检测到当前系统版本为非 HyperOS 4", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Text("当前版本：${version.ifBlank { "未知" }}。本模块专为 HyperOS 4 设计，在其它系统版本上可能出现兼容性问题。请先阅读免责声明并确认理解后继续使用。", style = MiuixTheme.textStyles.body1)
            Text("免责声明（点击阅读）", color = ComposeColor(0xFF1976D2), modifier = Modifier.clickable(onClick = onDisclaimer), style = MiuixTheme.textStyles.body1)
            TextField(value = captchaInput, onValueChange = onCaptchaInput, label = "动态验证码", useLabelAsPlaceholder = true, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done), cornerRadius = 999.dp, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onClick = onConfirm, modifier = Modifier.weight(1f), enabled = disclaimerRead && captchaInput == captcha, colors = ButtonDefaults.buttonColorsPrimary()) { Text("确认") }
                GlassDialogButton(onClick = onExit, modifier = Modifier.weight(1f)) { Text("退出") }
            }
        }
    }
}

@Composable
private fun OsMissingDialog(show: Boolean, seconds: Int, preview: Boolean, onExit: () -> Unit) {
    WindowDialog(show = show, onDismissRequest = {}) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("检测到当前系统并非 HyperOS", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Text("未读取到 ro.mi.os.version.code，系统环境可能存在重大差异。为避免不可预期的问题，HyperChanger 将退出。", style = MiuixTheme.textStyles.body1)
            GlassDialogButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text(if (preview) "退出" else "退出（${seconds.coerceAtLeast(0)}s）", color = ComposeColor(0xFFD32F2F)) }
        }
    }
}

@Composable
private fun DebugModeDialog(onDismiss: () -> Unit, onNonHyperOs4: () -> Unit, onNonHyperOs: () -> Unit) {
    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("调试模式", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            GlassDialogButton(onClick = onNonHyperOs4, modifier = Modifier.fillMaxWidth()) { Text("非 HyperOS 4 弹窗") }
            GlassDialogButton(onClick = onNonHyperOs, modifier = Modifier.fillMaxWidth()) { Text("非 HyperOS 弹窗") }
            GlassDialogButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
}

@Composable
private fun Disclaimer(back: () -> Unit, captcha: String) = AppPage("免责声明", back) { padding, scroll ->
    AppList(padding, scroll, 28) {
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Text("HyperChanger 使用免责声明", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold)
                Text("本模块通过 Xposed API 调整系统界面与相关应用的显示和行为，仅面向 HyperOS 4 环境设计。由于系统组件版本、厂商实现和第三方模块可能存在差异，非 HyperOS 4 设备上可能出现界面异常、功能失效、应用崩溃或数据丢失。\n\n使用本模块前，请确认你已完成必要的数据备份，并理解启用系统级 Hook 可能带来的风险。由模块导致的任何直接或间接损失由使用者自行承担。模块作者不承诺兼容所有设备、地区版本或未来系统更新。\n\n你可以随时在设置中关闭相关功能，并在出现异常时卸载模块或恢复系统环境。继续使用即表示你已阅读并接受以上条款。", style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(top = 12.dp))
                Text("本次验证码：$captcha", style = MiuixTheme.textStyles.body2, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .58f), modifier = Modifier.padding(top = 20.dp))
                Text("阅读完成后返回上一页输入此验证码。", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .68f))
            }
        }
    }
}

@Composable
private fun Donate(back: () -> Unit) = AppPage(tr("\u6350\u8d60", "\u6350\u8d60"), back) { padding, scroll ->
    val context = LocalContext.current
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    AppList(padding, scroll, 28) {
        item { Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) { Image(painterResource(R.drawable.btm_m_avatar), "btm_m", Modifier.size(84.dp).clip(androidx.compose.foundation.shape.CircleShape), contentScale = ContentScale.Crop); Text("btm_m", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) } }
        item { Group(tr("\u7231\u53d1\u7535", "\u7231\u53d1\u7535")) { ArrowPreference(title = tr("\u901a\u8fc7\u7231\u53d1\u7535\u652f\u6301\u6211", "\u901a\u8fc7\u7231\u53d1\u7535\u652f\u6301\u6211"), summary = tr("\u7231\u53d1\u7535\uff1abtm_m", "\u7231\u53d1\u7535\uff1abtm_m"), onClick = { openUrl(context, "https://afdian.com/a/btm_m") }) } }
        item { Group(tr("\u5fae\u4fe1\u8d5e\u8d4f\u7801", "\u5fae\u4fe1\u8d5e\u8d4f\u7801")) { Image(painterResource(if (isDark) R.drawable.mm_reward_darkmode else R.drawable.mm_reward_lightmode), tr("\u5fae\u4fe1\u8d5e\u8d4f\u7801", "\u5fae\u4fe1\u8d5e\u8d4f\u7801"), Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp), contentScale = ContentScale.Fit) } }
    }
}

private data class OpenProject(val name: String, val version: String, val description: String, val url: String)
private fun openProjects() = listOf(
    OpenProject("MIUIX", "0.9.3", tr("HyperOS \u98ce\u683c\u754c\u9762\u3001\u504f\u597d\u8bbe\u7f6e\u3001\u56fe\u6807\u4e0e\u6a21\u7cca\u6548\u679c", "HyperOS \u98ce\u683c\u754c\u9762\u3001\u504f\u597d\u8bbe\u7f6e\u3001\u56fe\u6807\u4e0e\u6a21\u7cca\u6548\u679c"), "https://github.com/compose-miuix-ui/miuix"),
    OpenProject("LSPosed API", "102", tr("LSPosed \u6a21\u5757 API \u4e0e\u670d\u52a1\u901a\u4fe1", "LSPosed \u6a21\u5757 API \u4e0e\u670d\u52a1\u901a\u4fe1"), "https://github.com/LSPosed/LSPosed"),
    OpenProject("Backdrop / AndroidLiquidGlass", "2.0.0", tr("\u6db2\u6001\u73bb\u7483\u6e32\u67d3\u4e0e\u5e95\u90e8\u5bfc\u822a\u4ea4\u4e92", "\u6db2\u6001\u73bb\u7483\u6e32\u67d3\u4e0e\u5e95\u90e8\u5bfc\u822a\u4ea4\u4e92"), "https://github.com/Kyant0/AndroidLiquidGlass"),
    OpenProject("Compose Multiplatform", "1.11.x", tr("\u58f0\u660e\u5f0f\u754c\u9762\u3001\u5e03\u5c40\u4e0e\u52a8\u753b", "\u58f0\u660e\u5f0f\u754c\u9762\u3001\u5e03\u5c40\u4e0e\u52a8\u753b"), "https://github.com/JetBrains/compose-multiplatform"),
    OpenProject("AndroidX", tr("\u591a\u4e2a\u7ec4\u4ef6", "\u591a\u4e2a\u7ec4\u4ef6"), tr("Activity\u3001Lifecycle\u3001Core \u7b49 Android \u57fa\u7840\u5e93", "Activity\u3001Lifecycle\u3001Core \u7b49 Android \u57fa\u7840\u5e93"), "https://github.com/androidx/androidx")
)

@Composable
private fun OpenSource(back: () -> Unit) = AppPage(tr("\u5f00\u6e90\u4ee3\u7801\u58f0\u660e", "\u5f00\u6e90\u4ee3\u7801\u58f0\u660e"), back) { padding, scroll ->
    val context = LocalContext.current
    val projects = openProjects()
    AppList(padding, scroll, 28) {
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) { Text(tr("HyperChanger \u4f7f\u7528\u4e86\u4ee5\u4e0b\u5f00\u6e90\u9879\u76ee\u3002\u611f\u8c22\u6240\u6709\u9879\u76ee\u4f5c\u8005\u4e0e\u8d21\u732e\u8005\u3002", "HyperChanger \u4f7f\u7528\u4e86\u4ee5\u4e0b\u5f00\u6e90\u9879\u76ee\u3002\u611f\u8c22\u6240\u6709\u9879\u76ee\u4f5c\u8005\u4e0e\u8d21\u732e\u8005\u3002"), style = MiuixTheme.textStyles.body1) } }
        item { SmallTitle(tr("\u754c\u9762\u3001\u529f\u80fd\u4e0e\u5e73\u53f0", "\u754c\u9762\u3001\u529f\u80fd\u4e0e\u5e73\u53f0"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp)) }
        items(projects.size) { i -> val item = projects[i]; Card(Modifier.fillMaxWidth().clickable { openUrl(context, item.url) }, insideMargin = PaddingValues(16.dp)) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(item.name, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold); Text("${item.version} \u00b7 Apache License 2.0", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 3.dp)); Text(item.description, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 6.dp)) }; Image(MiuixIcons.Regular.ChevronForward, null, Modifier.padding(start = 12.dp).size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary)) } } }
    }
}

@Composable
private fun AppPage(
    title: String,
    onBack: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    restartScopes: Set<ScopeApplication> = emptySet(),
    restartEnabled: Boolean = true,
    compactTopBar: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues, ScrollBehavior) -> Unit,
) {
    val context = LocalContext.current
    var showRestartScopeDialog by remember { mutableStateOf(false) }
    val scroll = MiuixScrollBehavior()
    val backdrop = rememberLayerBackdrop()
    val surface = MiuixTheme.colorScheme.surface
    val collapsedFraction = scroll.state.collapsedFraction.coerceIn(0f, 1f)
    CompositionLocalProvider(
        LocalToolbarBackdrop provides backdrop,
        LocalToolbarCollapsed provides collapsedFraction,
    ) {
    Scaffold(
        topBar = {
            Box {
                if (isRuntimeShaderSupported()) {
                    Box(
                        Modifier.matchParentSize()
                            .graphicsLayer { alpha = 1f - collapsedFraction }
                            .drawPlainBackdrop(
                                backdrop = backdrop,
                                shape = { androidx.compose.ui.graphics.RectangleShape },
                                effects = { blur(6.dp.toPx()) },
                                onDrawSurface = { drawRect(surface.copy(alpha = 0.55f)) }
                            )
                    )
                    ProgressiveBlurLayer(backdrop, collapsedFraction)
                } else {
                    Box(Modifier.matchParentSize().background(surface.copy(alpha = 0.82f * (1f - collapsedFraction))))
                }
                if (compactTopBar) {
                    SmallTopAppBar(
                        title = title,
                        color = ComposeColor.Transparent,
                        navigationIcon = {
                            navigationIcon?.invoke() ?: onBack?.let { GlassBackButton(backdrop, 1f, it) }
                        },
                        actions = {
                            if (restartScopes.isNotEmpty()) {
                                GlassRefreshButton(
                                    backdrop = backdrop,
                                    collapsedFraction = 1f,
                                    enabled = restartEnabled,
                                ) { showRestartScopeDialog = true }
                            }
                            actions()
                        },
                    )
                } else {
                    TopAppBar(
                        title = "",
                        largeTitle = title,
                        color = ComposeColor.Transparent,
                        scrollBehavior = scroll,
                        navigationIcon = {
                            navigationIcon?.invoke() ?: onBack?.let { GlassBackButton(backdrop, collapsedFraction, it) }
                        },
                        actions = {
                            if (restartScopes.isNotEmpty()) {
                                GlassRefreshButton(
                                    backdrop = backdrop,
                                    collapsedFraction = collapsedFraction,
                                    enabled = restartEnabled,
                                ) { showRestartScopeDialog = true }
                            }
                            actions()
                        },
                    )
                    Box(
                        Modifier.windowInsetsPadding(WindowInsets.statusBars).height(52.dp).fillMaxWidth()
                            .graphicsLayer { alpha = scroll.state.collapsedFraction }
                            .padding(horizontal = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(title, style = MiuixTheme.textStyles.title3, maxLines = 1)
                    }
                }
            }
        }
    ) { padding ->
        Box(
            Modifier.fillMaxSize().layerBackdrop(backdrop)
        ) {
            content(padding, scroll)
        }
        RestartScopeDialog(
            show = showRestartScopeDialog,
            onDismiss = { showRestartScopeDialog = false },
            onRestart = { targets ->
                SystemUiRestarter.restart(context, targets)
                showRestartScopeDialog = false
            },
            availableTargets = restartScopes,
        )
    }
    }
}

@Composable
private fun License(back: () -> Unit) = AppPage("LICENSE", back) { padding, scroll ->
    val licenseText = """Apache License
Version 2.0, January 2004
http://www.apache.org/licenses/

Copyright 2026 btm_m

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

NOTICE: Historical licensing attribution is documented in the repository's
NOTICE file; current distributions are under Apache License 2.0.

(以下中文翻译仅供参考，一切法律效力以英文原文为准)

Apache 许可证 2.0 版
版权所有 2026 btm_m

本文件遵循 Apache License 2.0（以下简称“本许可证”）授权；除非遵守本许可证，否则不得使用本文件。你可以在以下地址获取本许可证副本：
http://www.apache.org/licenses/LICENSE-2.0

除非适用法律要求或书面同意，依据本许可证分发的软件均按“原样”提供，不附带任何明示或暗示的担保或条件。有关本许可证具体权限和限制，请参阅许可证正文。

致敬说明：历史授权归属与版权信息见仓库 NOTICE 文件；当前版本依据 Apache License 2.0 发布。"""
    AppList(padding, scroll, 28) {
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                Text(licenseText, style = MiuixTheme.textStyles.body2)
            }
        }
    }
}

private data class LanguagePack(
    val name: String,
    val creator: String,
    val json: String,
    val builtIn: Boolean = false,
    val selection: String = name,
)

private fun applyLanguageSelection(
    context: android.content.Context,
    prefs: android.content.SharedPreferences,
    selection: String,
    packs: List<LanguagePack>,
) {
    val tags = when (selection) {
        "system" -> ""
        "zh" -> "zh-CN"
        "en" -> "en"
        "ja" -> "ja"
        else -> packs.firstOrNull { it.name == selection }?.let { pack ->
            runCatching { JSONObject(pack.json).optString("locale") }.getOrDefault("")
        }.orEmpty()
    }
    prefs.edit().putString("selected", selection).apply()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LanguagePage(back: () -> Unit) {
    val context = LocalContext.current
    val languageChanged = LocalLanguageChanged.current
    val prefs = remember { context.getSharedPreferences("languages", android.content.Context.MODE_PRIVATE) }
    var selected by remember { mutableStateOf(prefs.getString("selected", "system") ?: "system") }
    var packs by remember { mutableStateOf(loadLanguagePacks(prefs)) }
    var editor by remember { mutableStateOf<LanguagePack?>(null) }
    var menuPack by remember { mutableStateOf<LanguagePack?>(null) }
    var exportPack by remember { mutableStateOf<LanguagePack?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pack = exportPack
        exportPack = null
        if (uri == null || pack == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pack.json) }
                ?: error("output")
        }.onSuccess {
            Toast.makeText(context, tr("presetExported", "语言已导出"), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, tr("exportFailed", "导出失败"), Toast.LENGTH_SHORT).show()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val obj = JSONObject(text)
            require(obj.optJSONObject("strings") != null)
            val name = obj.optString("languageName", obj.optString("name", "")).trim()
            require(name.isNotEmpty())
            val pack = LanguagePack(name, obj.optString("creator", "").trim(), text)
            packs = (packs.filterNot { it.name == pack.name } + pack)
            saveLanguagePacks(prefs, packs)
            languageChanged()
        }.onFailure { Toast.makeText(context, tr("importFailed", "导入失败"), Toast.LENGTH_SHORT).show() }
    }
    if (editor != null) {
        LanguageEditor(editor!!, onClose = { editor = null }, onSaved = { pack -> packs = (packs.filterNot { it.name == pack.name } + pack); saveLanguagePacks(prefs, packs); languageChanged(); editor = null })
        return
    }
    val builtInEntries = listOf(
        LanguagePack(tr("followSystem", "跟随系统"), "btm_m", builtinLanguageJson(context, "system"), builtIn = true, selection = "system"),
        LanguagePack(tr("chinese", "中文"), "btm_m", builtinLanguageJson(context, "zh"), builtIn = true, selection = "zh"),
        LanguagePack(tr("english", "English"), "btm_m", builtinLanguageJson(context, "en"), builtIn = true, selection = "en"),
        LanguagePack(tr("japanese", "日本語"), "btm_m", builtinLanguageJson(context, "ja"), builtIn = true, selection = "ja"),
    )
    fun select(pack: LanguagePack) {
        selected = pack.selection
        applyLanguageSelection(context, prefs, pack.selection, packs)
        languageChanged()
    }
    fun showExport(pack: LanguagePack) {
        menuPack = null
        exportPack = pack
        exportLauncher.launch("${pack.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")
    }
    AppPage(tr("language", "语言"), back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            builtInEntries.forEach { pack ->
                item {
                    LanguageCard(pack, selected == pack.selection, onSelect = { select(pack) }, onLongPress = { menuPack = pack })
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                    SmallTitle(tr("languageActions", "语言操作"), insideMargin = PaddingValues(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 4.dp))
                    ArrowPreference(title = tr("import", "导入..."), onClick = { importer.launch(arrayOf("application/json", "text/json", "text/plain")) })
                    ArrowPreference(title = tr("new", "新建"), summary = tr("newSummary", "根据 example.json 创建"), onClick = { editor = LanguagePack("New Language", "", exampleLanguageJson(context)) })
                }
            }
            packs.forEach { pack ->
                item {
                    LanguageCard(pack, selected == pack.selection, onSelect = { select(pack) }, onLongPress = { menuPack = pack })
                }
            }
        }
    }
    menuPack?.let { pack ->
        WindowDialog(show = true, onDismissRequest = { menuPack = null }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(pack.name, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                Text(pack.creator.ifBlank { "btm_m" }, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (!pack.builtIn) {
                    GlassDialogButton({ menuPack = null; editor = pack }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("edit", "编辑")) }
                }
                GlassDialogButton({ showExport(pack) }, Modifier.fillMaxWidth()) { Text(tr("export", "导出")) }
                GlassDialogButton({ menuPack = null }, Modifier.fillMaxWidth()) { Text(tr("cancel", "取消")) }
            }
        }
    }
}

@Composable
private fun LanguageCard(pack: LanguagePack, selected: Boolean, onSelect: () -> Unit, onLongPress: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onSelect, onLongClick = onLongPress),
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pack.name, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                Text(pack.creator.ifBlank { "btm_m" }, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 3.dp))
            }
            Checkbox(state = if (selected) ToggleableState.On else ToggleableState.Off, onClick = onSelect)
        }
    }
}

@Composable
private fun LanguageEditor(pack: LanguagePack, onClose: () -> Unit, onSaved: (LanguagePack) -> Unit) {
    val context = LocalContext.current
    val suppressPageBack = LocalPageBackSuppressed.current
    var text by remember { mutableStateOf(pack.json) }
    var dirty by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    fun requestClose() {
        if (dirty) showDiscardDialog = true else onClose()
    }
    DisposableEffect(Unit) {
        suppressPageBack(true)
        onDispose { suppressPageBack(false) }
    }
    val predictiveBack = rememberMomentumPredictiveBack(
        enabled = true,
        maxProgress = 0.92f,
        onBack = ::requestClose,
    )
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out -> out.write(text.toByteArray()) }
                ?: error("output")
        }.onFailure { Toast.makeText(context, tr("exportFailed", "导出失败"), Toast.LENGTH_SHORT).show() }
    }
    Box(Modifier.fillMaxSize().momentumBackTransform(predictiveBack)) {
    AppPage(tr("edit_language", "编辑语言"), onBack = ::requestClose, actions = {
        GlassToolbarIconButton(MiuixIcons.Regular.Download, tr("saveAsJson", tr("保存为 JSON", "保存为 JSON"))) { exporter.launch("language.json") }
        GlassToolbarIconButton(MiuixIcons.Regular.Ok, tr("saveInApp", tr("应用内保存", "应用内保存"))) {
            runCatching {
                val o = JSONObject(text)
                val name = o.optString("languageName", "").trim()
                require(name.isNotEmpty() && o.optJSONObject("strings") != null)
                onSaved(LanguagePack(name, o.optString("creator", "").trim(), text))
            }.onSuccess { dirty = false }.onFailure {
                Toast.makeText(context, tr("saveFailed", "保存失败"), Toast.LENGTH_SHORT).show()
            }
        }
    }) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Group(pack.name) {
                    if (pack.creator.isNotBlank()) Text(pack.creator, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(12.dp)) { TextField(value = text, onValueChange = { text = it; dirty = true }, modifier = Modifier.fillMaxWidth(), singleLine = false, label = tr("json", "JSON")) } }
        }
    }
    if (showDiscardDialog) {
        WindowDialog(show = true, onDismissRequest = { showDiscardDialog = false }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("discardChanges", tr("放弃修改？", "放弃修改？")), style = MiuixTheme.textStyles.title3)
                Text(tr("discardChangesSummary", tr("未保存的内容将丢失。", "未保存的内容将丢失。")), style = MiuixTheme.textStyles.body2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton({ showDiscardDialog = false }, Modifier.weight(1f)) { Text(tr("cancel", tr("取消", "取消"))) }
                    GlassDialogButton({ showDiscardDialog = false; onClose() }, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("discard", tr("放弃", "放弃"))) }
                }
            }
        }
    }
    }
}

@Composable
private fun GlassToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    backdrop: LayerBackdrop? = LocalToolbarBackdrop.current,
    collapsedFraction: Float = LocalToolbarCollapsed.current,
    containerPadding: PaddingValues = PaddingValues(horizontal = 2.dp),
    iconTranslationX: Dp = 0.dp,
    onClick: () -> Unit,
) {
    val animationScope = rememberCoroutineScope()
    val drag = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = 0f,
            valueRange = -1f..1f,
            visibilityThreshold = .001f,
            initialScale = 1f,
            pressedScale = 1.08f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount -> updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f)) },
        )
    }
    val highlight = remember(animationScope) {
        InteractiveHighlight(animationScope) { size, offset ->
            androidx.compose.ui.geometry.Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height))
        }
    }
    val glassTint = MiuixTheme.colorScheme.surface.copy(alpha = TOOLBAR_GLASS_SURFACE_ALPHA)
    val iconColor = MiuixTheme.colorScheme.onSurface
    val glassAlpha by animateFloatAsState(collapsedFraction, tween(180), label = "toolbarButtonGlassAlpha")
    Box(
        Modifier.padding(containerPadding)
            .then(Modifier.size(46.dp))
            .then(highlight.gestureModifier)
            .then(drag.modifier)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { alpha = glassAlpha }
                .then(if (backdrop != null) Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(TOOLBAR_GLASS_BLUR_RADIUS.toPx())
                        toolbarGlassLens(drag.pressProgress)
                    },
                    highlight = { toolbarGlassHighlight(drag.pressProgress) },
                    shadow = { Shadow.Default.copy(radius = 5.dp, color = ComposeColor.Black, alpha = .24f) },
                    innerShadow = null,
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        val velocity = abs(drag.velocity.coerceIn(-1f, 1f))
                        scaleX *= 1f + velocity * .12f
                        scaleY /= 1f + velocity * .08f
                        translationX = drag.value * 5.dp.toPx()
                    },
                    onDrawSurface = { drawCircle(glassTint) },
                ) else Modifier.background(glassTint, CircleShape))
                .then(highlight.modifier),
        ) {
        }
        Image(
            icon,
            description,
            Modifier.size(22.dp).graphicsLayer { translationX = iconTranslationX.toPx() },
            colorFilter = ColorFilter.tint(iconColor),
        )
    }
}

private fun exampleLanguageJson(context: android.content.Context): String = runCatching {
    context.assets.open("languages/en.json").bufferedReader().use { it.readText() }
}.getOrElse {
    """{"languageName":"English","creator":"btm_m","locale":"en","strings":{}}"""
}

private fun builtinLanguageJson(context: android.content.Context, selection: String): String {
    if (selection == "en" || selection == "ja") {
        return runCatching {
            context.assets.open("languages/$selection.json").bufferedReader().use { it.readText() }
        }.getOrDefault("{}")
    }
    val languageName = when (selection) {
        "zh" -> "中文"
        else -> "跟随系统"
    }
    val locale = if (selection == "zh") "zh-CN" else ""
    return JSONObject()
        .put("languageName", languageName)
        .put("creator", "btm_m")
        .put("locale", locale)
        .put("strings", JSONObject())
        .toString(2)
}

private fun loadLanguagePacks(prefs: android.content.SharedPreferences): List<LanguagePack> = runCatching {
    val arr = org.json.JSONArray(prefs.getString("packs", "[]"))
    (0 until arr.length()).map { val o = arr.getJSONObject(it); LanguagePack(o.optString("name"), o.optString("creator"), o.optString("json")) }
}.getOrDefault(emptyList())

private fun saveLanguagePacks(prefs: android.content.SharedPreferences, packs: List<LanguagePack>) {
    val arr = org.json.JSONArray(); packs.forEach { arr.put(JSONObject().put("name", it.name).put("creator", it.creator).put("json", it.json)) }; prefs.edit().putString("packs", arr.toString()).apply()
}

@Composable
private fun BoxScope.ProgressiveBlurLayer(backdrop: LayerBackdrop, collapsedFraction: Float) {
    Box(
        Modifier.matchParentSize()
            .expandDrawHeight(1.184625f)
            .graphicsLayer { alpha = collapsedFraction }
            .drawPlainBackdrop(
                backdrop = backdrop,
                shape = { androidx.compose.ui.graphics.RectangleShape },
                effects = {
                    blur(16.dp.toPx())
                    runtimeShaderEffect(
                        key = "progressive-blur-alpha-mask",
                        shaderString = PROGRESSIVE_BLUR_ALPHA_MASK_SHADER,
                        uniformShaderName = "content"
                    ) { setFloatUniform("size", size.width, size.height) }
                }
            )
    )
}

private const val TOOLBAR_GLASS_SURFACE_ALPHA = .80f
private val TOOLBAR_GLASS_BLUR_RADIUS = 2.dp
private val TOOLBAR_GLASS_LENS_HEIGHT = 16.dp
private val TOOLBAR_GLASS_LENS_AMOUNT = 32.dp

private fun BackdropEffectScope.toolbarGlassLens(progress: Float = 0f) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    lens(
        (TOOLBAR_GLASS_LENS_HEIGHT + 4.dp * clampedProgress).toPx(),
        (TOOLBAR_GLASS_LENS_AMOUNT + 8.dp * clampedProgress).toPx(),
        depthEffect = true,
        chromaticAberration = true,
    )
}

private fun toolbarGlassHighlight(progress: Float = 0f): Highlight {
    val clampedProgress = progress.coerceIn(0f, 1f)
    return Highlight.Default.copy(
        width = .75.dp,
        blurRadius = .4.dp,
        alpha = .76f + clampedProgress * .24f,
    )
}

@Composable
private fun GlassBackButton(backdrop: LayerBackdrop, collapsedFraction: Float, onClick: () -> Unit) {
    GlassToolbarIconButton(
        icon = MiuixIcons.Regular.ChevronBackward,
        description = tr("\u8fd4\u56de", "\u8fd4\u56de"),
        onClick = onClick,
        backdrop = backdrop,
        collapsedFraction = collapsedFraction,
        containerPadding = PaddingValues(start = 8.dp),
        iconTranslationX = -1.15.dp,
    )
}

@Composable
private fun GlassRefreshButton(
    backdrop: LayerBackdrop,
    collapsedFraction: Float,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val animationScope = rememberCoroutineScope()
    val drag = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = 0f,
            valueRange = -1f..1f,
            visibilityThreshold = .001f,
            initialScale = 1f,
            pressedScale = 1.08f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount -> updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f)) }
        )
    }
    val highlight = remember(animationScope) {
        InteractiveHighlight(animationScope) { size, offset -> androidx.compose.ui.geometry.Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height)) }
    }
    val glassTint = MiuixTheme.colorScheme.surface.copy(alpha = TOOLBAR_GLASS_SURFACE_ALPHA)
    val iconColor = MiuixTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f)
    val glassAlpha by animateFloatAsState(collapsedFraction, tween(180), label = "refreshButtonGlassAlpha")
    Box(
        Modifier.padding(end = 8.dp).size(46.dp)
            .then(if (enabled) highlight.gestureModifier else Modifier)
            .then(if (enabled) drag.modifier else Modifier)
            .clickable(enabled = enabled, interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { alpha = glassAlpha }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        vibrancy()
                        blur(TOOLBAR_GLASS_BLUR_RADIUS.toPx())
                        toolbarGlassLens(drag.pressProgress)
                    },
                    highlight = { toolbarGlassHighlight(drag.pressProgress) },
                    shadow = {
                        Shadow.Default.copy(
                            radius = 5.dp,
                            color = ComposeColor.Black,
                            alpha = .24f,
                        )
                    },
                    innerShadow = null,
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        val velocity = abs(drag.velocity.coerceIn(-1f, 1f))
                        scaleX *= 1f + velocity * .12f
                        scaleY /= 1f + velocity * .08f
                        translationX = drag.value * 5.dp.toPx()
                    },
                    onDrawSurface = { drawCircle(glassTint) }
                )
                .then(highlight.modifier)
        )
        Image(
            MiuixIcons.Regular.Refresh,
            tr("重启作用域应用", "重启作用域应用"),
            Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(iconColor),
        )
    }
}

val LocalToolbarBackdrop = compositionLocalOf<LayerBackdrop?> { null }
private val LocalToolbarCollapsed = compositionLocalOf { 1f }

@Composable
private fun OverlayDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelectedIndexChange: (Int) -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val isEnabled = enabled && items.isNotEmpty()
    GlassDropdownPreference(
        title = title,
        items = items,
        selectedIndex = selectedIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)),
        enabled = isEnabled,
        modifier = modifier,
        backdrop = LocalToolbarBackdrop.current,
        onSelectedIndexChange = { selected ->
            hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
            onSelectedIndexChange(selected)
        },
    )
}

@Composable
private fun GlassActionButton(
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val animationScope = rememberCoroutineScope()
    val drag = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = 0f,
            valueRange = -1f..1f,
            visibilityThreshold = .001f,
            initialScale = 1f,
            pressedScale = 1.08f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount -> updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f)) },
        )
    }
    val highlight = remember(animationScope) {
        InteractiveHighlight(animationScope) { size, offset ->
            androidx.compose.ui.geometry.Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height))
        }
    }
    val surface = MiuixTheme.colorScheme.surface
    val tint = surface.copy(alpha = TOOLBAR_GLASS_SURFACE_ALPHA)
    val backdrop = LocalToolbarBackdrop.current
    val collapsedFraction = LocalToolbarCollapsed.current
    Box(
        Modifier.padding(end = 4.dp).size(46.dp)
            .then(if (enabled) highlight.gestureModifier else Modifier)
            .then(if (enabled) drag.modifier else Modifier)
            .clip(CircleShape)
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { CircleShape },
                        effects = {
                            vibrancy()
                            blur(TOOLBAR_GLASS_BLUR_RADIUS.toPx())
                            toolbarGlassLens(drag.pressProgress)
                        },
                        highlight = { toolbarGlassHighlight(drag.pressProgress) },
                        shadow = { Shadow.Default.copy(radius = 5.dp, color = ComposeColor.Black, alpha = .24f) },
                        innerShadow = null,
                        onDrawSurface = { drawCircle(tint) },
                    )
                } else {
                    Modifier.background(tint)
                }
            )
            .graphicsLayer { alpha = if (enabled) 1f else .45f * collapsedFraction.coerceIn(.5f, 1f) }
            .clickable(enabled = enabled, interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.matchParentSize()
                .then(if (enabled) highlight.modifier else Modifier),
        )
        androidx.compose.foundation.layout.Box(
            Modifier.matchParentSize().graphicsLayer {
                scaleX = drag.scaleX
                scaleY = drag.scaleY
            },
            contentAlignment = Alignment.Center,
        ) { icon() }
    }
}

private const val PROGRESSIVE_BLUR_ALPHA_MASK_SHADER = """
uniform shader content;
uniform float2 size;
half4 main(float2 coord) {
    float blurAlpha = smoothstep(size.y, size.y * 0.5, coord.y);
    return content.eval(coord) * blurAlpha;
}
"""

private fun Modifier.expandDrawHeight(factor: Float) = layout { measurable, constraints ->
    val expandedHeight = (constraints.maxHeight * factor).toInt()
    val placeable = measurable.measure(constraints.copy(minHeight = expandedHeight, maxHeight = expandedHeight))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(0, 0) }
}

@Composable
private fun AppList(padding: PaddingValues, scroll: ScrollBehavior, bottom: Int = 112, items: LazyListScope.() -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).nestedScroll(scroll.nestedScrollConnection),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 4.dp, 16.dp, padding.calculateBottomPadding() + bottom.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = items
    )
}

private fun openUrl(context: android.content.Context, url: String) = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun appearanceFile(context: android.content.Context, slot: String): File =
    File(File(context.filesDir, "settings_appearance"), "$slot.bin")

private fun lockscreenWidgetPreviewFile(context: android.content.Context): File =
    File(File(context.filesDir, "lockscreen_widget"), "preview_background.bin")

private fun lockscreenWidgetSignatureFile(context: android.content.Context): File =
    File(File(context.filesDir, "lockscreen_widget"), "signature.bin")

private fun signatureAssetType(bytes: ByteArray): Int {
    if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        )
    ) {
        return LOCKSCREEN_WIDGET_SIGNATURE_PNG
    }
    val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
    return if (
        text.startsWith("<svg", ignoreCase = true) ||
        text.startsWith("<vector", ignoreCase = true) ||
        text.startsWith("<?xml", ignoreCase = true) && text.contains("<vector", ignoreCase = true)
    ) {
        LOCKSCREEN_WIDGET_SIGNATURE_VECTOR
    } else {
        LOCKSCREEN_WIDGET_SIGNATURE_NONE
    }
}

private fun copyLockscreenWidgetSignature(context: android.content.Context, uri: Uri): Int {
    val bytes = context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { tr("无法读取签名文件", "无法读取签名文件") }
        ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 5L * 1024L * 1024L) { tr("签名文件不能超过5MB", "签名文件不能超过5MB") }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
    val type = signatureAssetType(bytes)
    require(type != LOCKSCREEN_WIDGET_SIGNATURE_NONE) { tr("文件类型不受支持", "文件类型不受支持") }
    val target = lockscreenWidgetSignatureFile(context)
    val directory = requireNotNull(target.parentFile)
    check(directory.exists() || directory.mkdirs()) { tr("无法创建签名目录", "无法创建签名目录") }
    val temporary = File(directory, "signature.tmp")
    if (temporary.exists()) temporary.delete()
    FileOutputStream(temporary).use { output ->
        output.write(bytes)
        output.fd.sync()
    }
    check(LogoDrawableLoader.loadFile(context, temporary) != null) { tr("无法解析签名文件", "无法解析签名文件") }
    check(!target.exists() || target.delete()) { tr("无法替换旧签名", "无法替换旧签名") }
    check(temporary.renameTo(target)) { tr("无法保存签名", "无法保存签名") }
    target.setLastModified(System.currentTimeMillis())
    return type
}

private fun removeLockscreenWidgetSignature(context: android.content.Context) {
    val target = lockscreenWidgetSignatureFile(context)
    check(!target.exists() || target.delete()) { tr("无法移除签名", "无法移除签名") }
}

private fun copyLockscreenWidgetPreview(context: android.content.Context, uri: Uri): File {
    val target = lockscreenWidgetPreviewFile(context)
    val directory = requireNotNull(target.parentFile)
    check(directory.exists() || directory.mkdirs()) { tr("无法创建预览目录", "无法创建预览目录") }
    val temporary = File(directory, "preview_background.tmp")
    if (temporary.exists()) temporary.delete()
    var total = 0L
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { tr("无法读取图片", "无法读取图片") }
        FileOutputStream(temporary).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 30L * 1024L * 1024L) { tr("图片不能超过30MB", "图片不能超过30MB") }
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
    }
    check(!target.exists() || target.delete()) { tr("无法替换旧图片", "无法替换旧图片") }
    check(temporary.renameTo(target)) { tr("无法保存图片", "无法保存图片") }
    target.setLastModified(System.currentTimeMillis())
    return target
}

private fun copyAppearanceFile(context: android.content.Context, slot: String, uri: Uri): File {
    val directory = File(context.filesDir, "settings_appearance")
    check(directory.exists() || directory.mkdirs()) { tr("无法创建配置目录", "无法创建配置目录") }
    val target = appearanceFile(context, slot)
    val temporary = File(directory, "$slot.bin.tmp")
    if (temporary.exists()) temporary.delete()
    var total = 0L
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { tr("无法读取文件", "无法读取文件") }
        FileOutputStream(temporary).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 200L * 1024L * 1024L) { tr("文件不能超过200MB", "文件不能超过200MB") }
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
    }
    check(!target.exists() || target.delete()) { tr("无法替换旧文件", "无法替换旧文件") }
    check(temporary.renameTo(target)) { tr("无法保存文件", "无法保存文件") }
    target.setLastModified(System.currentTimeMillis())
    return target
}

private fun detectAppearanceMime(context: android.content.Context, uri: Uri): String {
    val reported = context.contentResolver.getType(uri).orEmpty()
    if (reported.isNotBlank() && reported != "application/octet-stream") return reported
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).orEmpty() else ""
        }
    }.getOrDefault("").orEmpty().lowercase()
    return when {
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".xml") -> "application/xml"
        reported.isNotBlank() -> reported
        else -> "application/octet-stream"
    }
}
