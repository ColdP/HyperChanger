package btm.m.os4.systemuihook

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.util.Log
import android.widget.Toast
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
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.items
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import btm.m.liquidglass.LabelMode
import btm.m.liquidglass.hook.DampedDragAnimation
import btm.m.liquidglass.hook.CustomNavigation
import btm.m.liquidglass.hook.HostTab
import btm.m.liquidglass.hook.InteractiveHighlight
import btm.m.liquidglass.momentumBackTransform
import btm.m.liquidglass.rememberMomentumPredictiveBack
import com.kyant.backdrop.Backdrop
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
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.*
import top.yukonga.miuix.kmp.window.WindowDialog
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

private enum class Tab(val title: String) { CATEGORY("\u5206\u7c7b"), SETTINGS("\u8bbe\u7f6e") }

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
    ISLAND, STATUS, CONTROL, LOCK, RASTER_WALLPAPER, SUPER_XIAOAI, CAMERA, SYSTEM_SETTINGS, DEVICE_PROFILE,
    SETTINGS_APPEARANCE_HOME, SETTINGS_APPEARANCE_DEVICE, TUTORIAL_DEVICE_CARD, ABOUT, DONATE, OPEN,
    REAR_SCREEN, REAR_MUSIC_APPS,
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
            Toast.makeText(context, "已导入", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show() }
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
            Toast.makeText(context, "已清除", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(context, "清除失败", Toast.LENGTH_SHORT).show() }
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
        if (selected.size > 4) Toast.makeText(context, "最多选择 4 个素材", Toast.LENGTH_SHORT).show()
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
            } ?: error("无法写入文件")
        }.onSuccess {
            Toast.makeText(context, "预设已导出", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
        }
    }
    fun importPresetPayload(payload: String) {
        runCatching { parseShadePreset(payload) }
            .onSuccess { imported ->
                hooks.update(service) { it.importShadePreset(payload) }
                settings = hooks.settings
                imported.name?.let { hooks.saveUserShadePreset(it, settings) }
                userPresets = hooks.userShadePresets()
                Toast.makeText(context, "预设已导入", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show()
            }
    }
    val importPreset = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("无法读取文件")
        }.onSuccess(::importPresetPayload).onFailure {
            Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show()
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
                ?: error("无法写入文件")
        }.onSuccess {
            Toast.makeText(context, "预设已导出", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "预设已导入", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show()
        }
    }
    val importModulePreset = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("无法读取文件")
        }.onSuccess(::importModulePresetPayload).onFailure {
            Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show()
        }
    }
    val importQrPreset = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                ?: error("无法读取图片")
        }.map(::readPresetQrCode).onSuccess(::importPresetPayload).onFailure {
            Toast.makeText(context, "未识别到有效预设二维码", Toast.LENGTH_SHORT).show()
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
            } ?: error("无法写入文件")
        }.onSuccess {
            Toast.makeText(context, "二维码已保存", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, "预设已保存", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(context, "无法打开动态壁纸选择器", Toast.LENGTH_SHORT).show()
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
    var tab by rememberSaveable { mutableStateOf(Tab.CATEGORY) }
    val pageStack = remember { mutableStateListOf<PageId>() }
    val page = pageStack.lastOrNull()
    var retainedPage by remember { mutableStateOf<PageId?>(null) }
    var navigatingForward by remember { mutableStateOf(true) }
    if (page != null) retainedPage = page
    val openRootPage: (PageId) -> Unit = { target ->
        navigatingForward = true
        pageStack.clear()
        pageStack += target
    }
    val openNestedPage: (PageId) -> Unit = { target ->
        if (pageStack.lastOrNull() != target) {
            navigatingForward = true
            pageStack += target
        }
    }
    val dismissPage: () -> Unit = {
        if (pageStack.size > 1) {
            navigatingForward = false
            pageStack.removeAt(pageStack.lastIndex)
        } else {
            pageStack.clear()
        }
        Unit
    }
    val backState = rememberMomentumPredictiveBack(
        enabled = page != null && settings.predictiveBackEnabled,
        maxProgress = settings.predictiveBackProgress.coerceIn(10, 100) / 100f,
        onBack = dismissPage
    )
    LaunchedEffect(pageStack.size) {
        if (pageStack.isNotEmpty()) backState.reset()
    }
    BackHandler(enabled = page != null && !settings.predictiveBackEnabled, onBack = dismissPage)
    val backdrop = rememberLayerBackdrop()

    Box(Modifier.fillMaxSize()) {
        // The backdrop must only record page content. Recording the navigation that consumes it
        // creates a RenderNode cycle and crashes HyperOS's RenderThread.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            when (tab) {
                Tab.CATEGORY -> CategoryHome(openRootPage)
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
                        Detail(
                            it, settings, cameras, deviceProfile, appearance, musicWhitelist, update, updateCamera, updateDeviceProfile, updateAppearance, updateMusicWhitelist, presetActions,
                            openPage = openNestedPage, back = dismissPage,
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
        HostTab(item.title, "os4.$i") { index.intValue = i; select(item) }
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
private fun CategoryHome(open: (PageId) -> Unit) = AppPage("HyperChanger") { padding, scroll ->
    AppList(padding, scroll) {
        item { Entry("\u7cfb\u7edf\u754c\u9762") { open(PageId.SHADE) } }
        item { Entry("\u8d85\u7ea7\u5c9b") { open(PageId.ISLAND) } }
        item { Entry("\u72b6\u6001\u680f") { open(PageId.STATUS) } }
        item { Entry("\u9501\u5c4f") { open(PageId.LOCK) } }
        item { Entry("\u8d85\u7ea7\u5c0f\u7231\u8f93\u5165\u6cd5") { open(PageId.SUPER_XIAOAI) } }
        item { Entry("\u76f8\u673a\u4e0e\u76f8\u518c\u7f16\u8f91") { open(PageId.CAMERA) } }
        item { Entry("\u7cfb\u7edf\u8bbe\u7f6e") { open(PageId.SYSTEM_SETTINGS) } }
        item { Entry("\u80cc\u5c4f") { open(PageId.REAR_SCREEN) } }
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
) = AppPage("\u80cc\u5c4f", back, restartScopes = setOf(ScopeApplication.SUBSCREEN_CENTER)) { padding, scroll ->
    val apps = rememberRearApps()
    AppList(padding, scroll, 28) {
        item {
            Group("\u80cc\u5c4f\u97f3\u4e50\u63a7\u4ef6") {
                ArrowPreference(
                    title = "\u6dfb\u52a0\u5e94\u7528\u5230\u80cc\u5c4f\u97f3\u4e50\u63a7\u4ef6\u767d\u540d\u5355",
                    onClick = { open(PageId.REAR_MUSIC_APPS) },
                )
            }
        }
        item {
            Group("\u5df2\u7ecf\u6dfb\u52a0\u7684\u5e94\u7528") {
                val selected = apps.filter { it.packageName in selectedPackages }
                if (selected.isEmpty()) {
                    Text(
                        "\u6682\u65e0\u5df2\u6dfb\u52a0\u7684\u5e94\u7528",
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
    var showMenu by remember { mutableStateOf(false) }
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
        title = "\u80cc\u5c4f\u97f3\u4e50\u63a7\u4ef6\u767d\u540d\u5355",
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
                                "\u6ca1\u6709\u5339\u914d\u7684\u5e94\u7528",
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
            Box {
                IconButton(onClick = { showMenu = true }, holdDownState = showMenu) {
                    Icon(
                        imageVector = MiuixIcons.MoreCircle,
                        contentDescription = "\u66f4\u591a",
                        tint = MiuixTheme.colorScheme.onBackground,
                    )
                }
                OverlayListPopup(
                    show = showMenu,
                    popupModifier = Modifier,
                    popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
                    alignment = PopupPositionProvider.Align.End,
                    enableWindowDim = true,
                    onDismissRequest = { showMenu = false },
                    maxHeight = null,
                    minWidth = 200.dp,
                    renderInRootScaffold = true,
                ) {
                    ListPopupColumn {
                        SpinnerItemImpl(
                            entry = SpinnerEntry(title = if (showSystemApps) "隐藏系统应用" else "显示系统应用"),
                            entryCount = 1,
                            isSelected = showSystemApps,
                            index = 0,
                            spinnerColors = SpinnerDefaults.spinnerColors(),
                            onSelectedIndexChange = {
                                showMenu = false
                                showSystemApps = !showSystemApps
                            },
                        )
                    }
                }
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
                label = "\u641c\u7d22\u5e94\u7528\u540d\u6216\u5305\u540d",
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
) = AppPage("\u8bbe\u7f6e") { padding, scroll ->
    val context = LocalContext.current
    var predictiveProgress by remember(settings.predictiveBackProgress) { mutableFloatStateOf(settings.predictiveBackProgress.toFloat()) }
    var showScopeRestartDialog by remember { mutableStateOf(false) }
    AppList(padding, scroll) {
        item { ServiceCard(online) }
        item {
            Group("\u5e94\u7528\u8bbe\u7f6e") {
                ArrowPreference(
                    title = "\u91cd\u542f\u4f5c\u7528\u57df\u5e94\u7528",
                    onClick = { showScopeRestartDialog = true },
                )
                OverlayDropdownPreference(
                    title = "\u4e3b\u9898\u6a21\u5f0f",
                    items = listOf("\u8ddf\u968f\u7cfb\u7edf", "\u6d45\u8272\u6a21\u5f0f", "\u6df1\u8272\u6a21\u5f0f"),
                    selectedIndex = listOf("system", "light", "dark").indexOf(settings.themeMode).coerceAtLeast(0),
                    onSelectedIndexChange = { i -> update { it.copy(themeMode = listOf("system", "light", "dark")[i]) } }
                )
                OverlayDropdownPreference(
                    title = "\u5e95\u90e8\u5bfc\u822a\u680f\u6837\u5f0f",
                    items = listOf("HyperOS \u5e95\u680f", "HyperOS \u60ac\u6d6e\u5e95\u680f", "\u6db2\u6001\u73bb\u7483\u5e95\u680f"),
                    selectedIndex = listOf("hyper_os", "hyper_os_floating", "liquid_glass").indexOf(settings.navigationStyle).coerceAtLeast(0),
                    onSelectedIndexChange = { i -> update { it.copy(navigationStyle = listOf("hyper_os", "hyper_os_floating", "liquid_glass")[i]) } }
                )
                OverlayDropdownPreference(
                    title = "\u5e95\u90e8\u5bfc\u822a\u680f\u6807\u7b7e\u663e\u793a\u65b9\u5f0f",
                    items = LabelMode.entries.map { it.displayName },
                    selectedIndex = LabelMode.entries.indexOfFirst { it.preferenceValue == settings.navigationLabelMode }.coerceAtLeast(0),
                    onSelectedIndexChange = { i -> update { it.copy(navigationLabelMode = LabelMode.entries[i].preferenceValue) } },
                )
                SwitchPreference(
                    title = "\u9884\u6d4b\u6027\u8fd4\u56de\u52a8\u753b",
                    checked = settings.predictiveBackEnabled,
                    onCheckedChange = { value -> update { it.copy(predictiveBackEnabled = value) } }
                )
                if (settings.predictiveBackEnabled) {
                    SliderPreference(
                        value = predictiveProgress,
                        onValueChange = { predictiveProgress = it },
                        onValueChangeFinished = { update { it.copy(predictiveBackProgress = predictiveProgress.toInt()) } },
                        title = "\u9884\u6d4b\u6027\u8fd4\u56de\u52a8\u753b\u6700\u5927\u8fdb\u5ea6",
                        valueText = "${predictiveProgress.toInt()}%",
                        valueRange = 10f..100f,
                        steps = 89
                    )
                }
            }
        }
        item {
            Group("\u5e94\u7528\u9884\u8bbe") {
                ArrowPreference(title = "\u5bfc\u5165\u9884\u8bbe", onClick = onImportModulePreset)
                ArrowPreference(title = "\u5bfc\u51fa\u9884\u8bbe", onClick = onExportModulePreset)
            }
        }
        item {
            Group("\u5e94\u7528\u4fe1\u606f") {
                ArrowPreference(title = "\u5173\u4e8e", onClick = { open(PageId.ABOUT) })
                ArrowPreference(title = "\u6350\u8d60", onClick = { open(PageId.DONATE) })
                ArrowPreference(title = "\u5f00\u6e90\u4ee3\u7801\u58f0\u660e", onClick = { open(PageId.OPEN) })
                ArrowPreference(title = "\u672c\u9879\u76ee\u57fa\u4e8e MIUIX \u6784\u5efa", onClick = { openUrl(context, "https://compose-miuix-ui.github.io/miuix/") })
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
                "\u91cd\u542f\u4f5c\u7528\u57df\u5e94\u7528",
                modifier = Modifier.fillMaxWidth(),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start,
            )
            Text(
                "\u9009\u62e9\u9700\u8981\u91cd\u542f\u7684\u5e94\u7528",
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
                Button(onDismiss, Modifier.weight(1f)) { Text("\u53d6\u6d88") }
                Button(
                    onClick = { onRestart(selectedTargets) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    enabled = selectedTargets.isNotEmpty(),
                ) { Text("\u91cd\u542f") }
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
                target.title,
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
) = AppPage("系统设置", back, restartScopes = setOf(ScopeApplication.SETTINGS)) { padding, scroll ->
    AppList(padding, scroll) {
        item {
            Group("设置应用") {
                SwitchPreference(
                    title = "启用设备信息覆盖",
                    checked = profile.enabled,
                    onCheckedChange = { enabled -> update { it.copy(enabled = enabled) } },
                )
                SwitchPreference(
                    title = "使用骁龙处理器图标",
                    checked = profile.snapdragonIcon,
                    onCheckedChange = { enabled -> update { it.copy(snapdragonIcon = enabled) } },
                )
            }
        }
        item {
            SmallTitle("界面自定义", insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(title = "自定义设置主界面背景图", onClick = { open(PageId.SETTINGS_APPEARANCE_HOME) })
                ArrowPreference(title = "自定义我的设备界面背景图", onClick = { open(PageId.SETTINGS_APPEARANCE_DEVICE) })
                ArrowPreference(title = "自定义我的设备界面", onClick = { open(PageId.TUTORIAL_DEVICE_CARD) })
                if (appearance.deviceInterfaceStyle == DEVICE_INTERFACE_STYLE_SYSTEM) {
                    OverlayDropdownPreference(
                        title = "自定义LOGO",
                        items = listOf("系统默认", "不保留高级材质", "保留高级材质（需要导入SVG/XML）"),
                        selectedIndex = appearance.logoMode,
                        onSelectedIndexChange = { index -> updateAppearance { it.copy(logoMode = index) } },
                    )
                }
                if (appearance.deviceInterfaceStyle == DEVICE_INTERFACE_STYLE_SYSTEM && appearance.logoMode != LOGO_MODE_SYSTEM) {
                    ArrowPreference(
                        title = "导入LOGO",
                        summary = appearance.logoMime.ifBlank { "未导入" },
                        onClick = onPickLogo,
                    )
                    ArrowPreference(
                        title = "清除LOGO",
                        summary = if (appearance.logoMime.isBlank()) "无LOGO" else "已导入",
                        onClick = onClearLogo,
                    )
                    SliderPreference(
                        value = appearance.logoScale.toFloat(),
                        onValueChange = { value -> updateAppearance { it.copy(logoScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = "LOGO缩放",
                        valueText = "${appearance.logoScale}%",
                        valueRange = 50f..200f,
                        steps = 149,
                    )
                }
            }
        }
        item {
            Group("设备信息") {
                ArrowPreference(title = "我的设备与全部参数", onClick = { open(PageId.DEVICE_PROFILE) })
            }
        }
    }
}

@Composable
private fun DeviceProfileEditor(
    profile: DeviceProfileSettings,
    update: ((DeviceProfileSettings) -> DeviceProfileSettings) -> Unit,
    back: () -> Unit,
) = AppPage("我的设备与全部参数", back) { padding, scroll ->
    val fields = listOf(
        "手机型号" to profile.model,
        "处理器" to profile.processor,
        "运行内存" to profile.ram,
        "电池容量" to profile.battery,
        "屏幕尺寸" to profile.screenSize,
        "分辨率" to profile.resolution,
        "我的设备 · 后摄" to profile.cameraRear,
        "我的设备 · 前摄" to profile.cameraFront,
        "全部参数与信息 · 摄像头" to profile.camera,
        "OS 版本" to profile.osVersion,
        "Android 版本" to profile.androidVersion,
        "内部存储" to profile.storage,
        "内核版本" to profile.kernel,
        "基带版本" to profile.baseband,
        "硬件版本" to profile.hardware,
    )
    val setters: List<(DeviceProfileSettings, String) -> DeviceProfileSettings> = listOf(
        { p, v -> p.copy(model = v) }, { p, v -> p.copy(processor = v) },
        { p, v -> p.copy(ram = v) }, { p, v -> p.copy(battery = v) },
        { p, v -> p.copy(screenSize = v) }, { p, v -> p.copy(resolution = v) },
        { p, v -> p.copy(cameraRear = v) }, { p, v -> p.copy(cameraFront = v) },
        { p, v -> p.copy(camera = v) }, { p, v -> p.copy(osVersion = v) },
        { p, v -> p.copy(androidVersion = v) }, { p, v -> p.copy(storage = v) },
        { p, v -> p.copy(kernel = v) }, { p, v -> p.copy(baseband = v) },
        { p, v -> p.copy(hardware = v) },
    )
    AppList(padding, scroll) {
        item { SmallTitle("基础参数", insideMargin = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 4.dp)) }
        fields.take(6).forEachIndexed { index, (label, value) ->
            item { DeviceProfileField(label, value) { next -> update { setters[index](it, next) } } }
        }
        item { SmallTitle("全部参数与信息", insideMargin = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp)) }
        fields.drop(8).forEachIndexed { offset, (label, value) ->
            val index = offset + 8
            item { DeviceProfileField(label, value) { next -> update { setters[index](it, next) } } }
        }
        item { SmallTitle("我的设备（影像参数）", insideMargin = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp)) }
        fields.subList(6, 8).forEachIndexed { offset, (label, value) ->
            val index = offset + 6
            item { DeviceProfileField(label, value) { next -> update { setters[index](it, next) } } }
        }
    }
}

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
) = AppPage("自定义我的设备界面", back, restartScopes = setOf(ScopeApplication.SETTINGS)) { padding, scroll ->
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
                    title = "我的设备界面样式",
                    items = listOf("系统默认", "样式1 (来自酷安@Mr_Bocchi)", "样式2"),
                    selectedIndex = style,
                    onSelectedIndexChange = selectStyle,
                )
            }
        }
        if (style == DEVICE_INTERFACE_STYLE_SYSTEM) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = "导入LOGO",
                        summary = appearance.logoMime.ifBlank { "未导入" },
                        onClick = onPickSystemLogo,
                    )
                    ArrowPreference(
                        title = "清除LOGO",
                        summary = if (appearance.logoMime.isBlank()) "无LOGO" else "已导入",
                        onClick = onClearSystemLogo,
                    )
                }
            }
        }
        if (style == DEVICE_INTERFACE_STYLE_ONE) {
            item {
                Group("机型图片") {
                    ArrowPreference(
                        title = "导入机型图片",
                        summary = appearance.tutorialCardImageMime.ifBlank { "未导入" },
                        onClick = onPickStyle1Image,
                    )
                    ArrowPreference(
                        title = "清除机型图片",
                        summary = if (appearance.tutorialCardImageMime.isBlank()) "无图片" else "已导入",
                        onClick = onClearStyle1Image,
                    )
                    SliderPreference(
                        value = appearance.tutorialCardImageScale.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardImageScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = "机型图片缩放",
                        valueText = "${appearance.tutorialCardImageScale}%",
                        valueRange = 40f..200f,
                        steps = 159,
                    )
                    SliderPreference(
                        value = appearance.tutorialCardImageLogoSpacing.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardImageLogoSpacing = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = "机型图片与LOGO间距",
                        valueText = "${appearance.tutorialCardImageLogoSpacing}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                }
            }
            item {
                Group("背景图片") {
                    ArrowPreference(
                        title = "导入背景图片",
                        summary = appearance.tutorialCardBackgroundMime.ifBlank { "未导入" },
                        onClick = onPickStyle1Background,
                    )
                    ArrowPreference(
                        title = "清除背景图片",
                        summary = if (appearance.tutorialCardBackgroundMime.isBlank()) "无图片" else "已导入",
                        onClick = onClearStyle1Background,
                    )
                SliderPreference(
                    value = appearance.tutorialCardBackgroundBlur,
                    onValueChange = { value -> update { it.copy(tutorialCardBackgroundBlur = value) } },
                    onValueChangeFinished = {},
                    title = "背景图片模糊度",
                    valueText = String.format(Locale.US, "%.2fdp", appearance.tutorialCardBackgroundBlur),
                    valueRange = 0f..25f,
                    steps = 2499,
                    enabled = true,
                )
                SliderPreference(
                    value = appearance.tutorialCardBackgroundHorizontalOffset.toFloat(),
                    onValueChange = { value -> update { it.copy(tutorialCardBackgroundHorizontalOffset = value.toInt()) } },
                    onValueChangeFinished = {},
                    title = "背景图片左右偏移",
                    valueText = "${appearance.tutorialCardBackgroundHorizontalOffset}%",
                    valueRange = -120f..120f,
                    steps = 239,
                    enabled = true,
                )
                SliderPreference(
                    value = appearance.tutorialCardBackgroundVerticalOffset.toFloat(),
                    onValueChange = { value -> update { it.copy(tutorialCardBackgroundVerticalOffset = value.toInt()) } },
                    onValueChangeFinished = {},
                    title = "背景图片上下偏移",
                    valueText = "${appearance.tutorialCardBackgroundVerticalOffset}%",
                    valueRange = -120f..120f,
                    steps = 239,
                    enabled = true,
                )
                SliderPreference(
                    value = appearance.tutorialCardBackgroundScale.toFloat(),
                    onValueChange = { value -> update { it.copy(tutorialCardBackgroundScale = value.toInt()) } },
                    onValueChangeFinished = {},
                    title = "背景图片缩放",
                    valueText = "${appearance.tutorialCardBackgroundScale}%",
                    valueRange = 40f..200f,
                    steps = 159,
                    enabled = true,
                )
                }
            }
            item {
                Group("LOGO") {
                    ArrowPreference(
                        title = "导入LOGO",
                        summary = appearance.tutorialCardLogoMime.ifBlank { "未导入" },
                        onClick = onPickStyle1Logo,
                    )
                    ArrowPreference(
                        title = "清除LOGO",
                        summary = if (appearance.tutorialCardLogoMime.isBlank()) "无LOGO" else "已导入",
                        onClick = onClearStyle1Logo,
                    )
                    SliderPreference(
                        value = appearance.tutorialCardLogoScale.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardLogoScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = "LOGO缩放",
                        valueText = "${appearance.tutorialCardLogoScale}%",
                        valueRange = 40f..200f,
                        steps = 159,
                    )
                    SliderPreference(
                        value = appearance.tutorialCardLogoVerticalOffset.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardLogoVerticalOffset = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = "LOGO上下偏移",
                        valueText = "${appearance.tutorialCardLogoVerticalOffset}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                }
            }
            item {
                Group("底部标识") {
                    SliderPreference(
                        value = appearance.tutorialCardTextSpacing.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardTextSpacing = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = "底部Logo与文案间距",
                        valueText = "${appearance.tutorialCardTextSpacing}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                    TutorialCardTextField("署名", appearance.tutorialCardAuthor) { value -> update { it.copy(tutorialCardAuthor = value) } }
                }
            }
        }
        if (style == DEVICE_INTERFACE_STYLE_TWO) {
            item {
                Group("机型图片") {
                    ArrowPreference(
                        title = "导入机型图片",
                        summary = appearance.style2ImageMime.ifBlank { "未导入" },
                        onClick = onPickStyle2Image,
                    )
                    ArrowPreference(
                        title = "清除机型图片",
                        summary = if (appearance.style2ImageMime.isBlank()) "无图片" else "已导入",
                        onClick = onClearStyle2Image,
                    )
                    SliderPreference(
                        value = appearance.style2ImageScale.toFloat(),
                        onValueChange = { value -> update { it.copy(style2ImageScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = "机型图片缩放",
                        valueText = "${appearance.style2ImageScale}%",
                        valueRange = 40f..200f,
                        steps = 159,
                    )
                }
            }
            item {
                Group("背景图片") {
                    ArrowPreference(
                        title = "导入背景图片",
                        summary = appearance.style2BackgroundMime.ifBlank { "未导入" },
                        onClick = onPickStyle2Background,
                    )
                    ArrowPreference(
                        title = "清除背景图片",
                        summary = if (appearance.style2BackgroundMime.isBlank()) "无背景图" else "已导入",
                        onClick = onClearStyle2Background,
                    )
                    SliderPreference(
                        value = appearance.style2BackgroundBlur,
                        onValueChange = { value -> update { it.copy(style2BackgroundBlur = value) } },
                        onValueChangeFinished = {},
                        title = "背景图片模糊度",
                        valueText = String.format(Locale.US, "%.2fdp", appearance.style2BackgroundBlur),
                        valueRange = 0f..25f,
                        steps = 2499,
                    )
                    SliderPreference(
                        value = appearance.style2BackgroundHorizontalOffset.toFloat(),
                        onValueChange = { value -> update { it.copy(style2BackgroundHorizontalOffset = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = "背景图片左右偏移",
                        valueText = "${appearance.style2BackgroundHorizontalOffset}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                    SliderPreference(
                        value = appearance.style2BackgroundVerticalOffset.toFloat(),
                        onValueChange = { value -> update { it.copy(style2BackgroundVerticalOffset = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = "背景图片上下偏移",
                        valueText = "${appearance.style2BackgroundVerticalOffset}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                    SliderPreference(
                        value = appearance.style2BackgroundScale.toFloat(),
                        onValueChange = { value -> update { it.copy(style2BackgroundScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = "背景图片缩放",
                        valueText = "${appearance.style2BackgroundScale}%",
                        valueRange = 40f..200f,
                        steps = 159,
                    )
                }
            }
            item {
                Group("LOGO与版本号") {
                    ArrowPreference(
                        title = "导入LOGO",
                        summary = appearance.style2LogoMime.ifBlank { "未导入" },
                        onClick = onPickStyle2Logo,
                    )
                    ArrowPreference(
                        title = "清除LOGO",
                        summary = if (appearance.style2LogoMime.isBlank()) "无LOGO" else "已导入",
                        onClick = onClearStyle2Logo,
                    )
                    OverlayDropdownPreference(
                        title = "LOGO与版本号对齐方式",
                        items = listOf("左对齐", "居中对齐", "右对齐"),
                        selectedIndex = appearance.style2LogoAlignment.coerceIn(0, 2),
                        onSelectedIndexChange = { value -> update { it.copy(style2LogoAlignment = value) } },
                    )
                    SliderPreference(
                        value = appearance.style2LogoVersionSpacing.toFloat(),
                        onValueChange = { value -> update { it.copy(style2LogoVersionSpacing = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = "LOGO与版本号行间距",
                        valueText = "${appearance.style2LogoVersionSpacing}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                    SliderPreference(
                        value = appearance.style2LogoHorizontalOffsetForAlignment().toFloat(),
                        onValueChange = { value -> update { current -> current.withStyle2LogoHorizontalOffset(current.style2LogoAlignment, value.toInt()) } },
                        onValueChangeFinished = {},
                        title = "LOGO与版本号左右偏移",
                        valueText = "${appearance.style2LogoHorizontalOffsetForAlignment()}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                    SliderPreference(
                        value = appearance.style2LogoVerticalOffsetForAlignment().toFloat(),
                        onValueChange = { value -> update { current -> current.withStyle2LogoVerticalOffset(current.style2LogoAlignment, value.toInt()) } },
                        onValueChangeFinished = {},
                        title = "LOGO与版本号上下偏移",
                        valueText = "${appearance.style2LogoVerticalOffsetForAlignment()}%",
                        valueRange = -120f..120f,
                        steps = 239,
                    )
                }
            }
            item {
                Group("文案") {
                    SwitchPreference(
                        title = "显示自定义文案",
                        checked = appearance.style2TextEnabled,
                        onCheckedChange = { value -> update { it.copy(style2TextEnabled = value) } },
                    )
                    if (appearance.style2TextEnabled) {
                        TutorialCardTextField("文案", appearance.style2Text) { value -> update { it.copy(style2Text = value) } }
                        SwitchPreference(
                            title = "独立于LOGO和版本号",
                            checked = appearance.style2TextIndependent,
                            onCheckedChange = { value -> update { it.copy(style2TextIndependent = value) } },
                        )
                        SliderPreference(
                            value = appearance.style2TextScale.toFloat(),
                            onValueChange = { value -> update { it.copy(style2TextScale = value.toInt()) } },
                            onValueChangeFinished = {},
                            title = "文案缩放",
                            valueText = "${appearance.style2TextScale}%",
                            valueRange = 40f..200f,
                            steps = 159,
                        )
                        if (!appearance.style2TextIndependent) {
                            OverlayDropdownPreference(
                                title = "显示位置",
                                items = listOf("LOGO之上", "版本号之下"),
                                selectedIndex = appearance.style2TextPosition.coerceIn(0, 1),
                                onSelectedIndexChange = { value -> update { it.copy(style2TextPosition = value) } },
                            )
                            if (appearance.style2TextPosition == 0) {
                                SliderPreference(
                                    value = appearance.style2TextSpacingAbove.toFloat(),
                                    onValueChange = { value -> update { it.copy(style2TextSpacingAbove = value.toInt()) } },
                                    onValueChangeFinished = {},
                                    title = "文案与LOGO行间距",
                                    valueText = "${appearance.style2TextSpacingAbove}%",
                                    valueRange = -120f..120f,
                                    steps = 239,
                                )
                            } else {
                                SliderPreference(
                                    value = appearance.style2TextSpacingBelow.toFloat(),
                                    onValueChange = { value -> update { it.copy(style2TextSpacingBelow = value.toInt()) } },
                                    onValueChangeFinished = {},
                                    title = "文案与版本号行间距",
                                    valueText = "${appearance.style2TextSpacingBelow}%",
                                    valueRange = -120f..120f,
                                    steps = 239,
                                )
                            }
                        } else {
                            OverlayDropdownPreference(
                                title = "显示位置",
                                items = listOf("居中", "左对齐", "右对齐"),
                                selectedIndex = appearance.style2TextAlignment.coerceIn(0, 2),
                                onSelectedIndexChange = { value -> update { it.copy(style2TextAlignment = value) } },
                            )
                            SliderPreference(
                                value = appearance.style2TextVerticalOffsetForAlignment().toFloat(),
                                onValueChange = { value -> update { current -> current.withStyle2TextVerticalOffset(current.style2TextAlignment, value.toInt()) } },
                                onValueChangeFinished = {},
                                title = "文案上下偏移",
                                valueText = "${appearance.style2TextVerticalOffsetForAlignment()}%",
                                valueRange = -120f..120f,
                                steps = 239,
                            )
                            SliderPreference(
                                value = appearance.style2TextHorizontalOffsetForAlignment().toFloat(),
                                onValueChange = { value -> update { current -> current.withStyle2TextHorizontalOffset(current.style2TextAlignment, value.toInt()) } },
                                onValueChangeFinished = {},
                                title = "文案左右偏移",
                                valueText = "${appearance.style2TextHorizontalOffsetForAlignment()}%",
                                valueRange = -120f..120f,
                                steps = 239,
                            )
                        }
                    }
                }
            }
            item {
                Group("文本颜色模式") {
                    OverlayDropdownPreference(
                        title = "LOGO",
                        items = listOf("跟随系统", "深色", "浅色"),
                        selectedIndex = appearance.style2LogoColorMode.coerceIn(0, 2),
                        onSelectedIndexChange = { value -> update { it.copy(style2LogoColorMode = value) } },
                    )
                    OverlayDropdownPreference(
                        title = "版本号",
                        items = listOf("跟随系统", "深色", "浅色"),
                        selectedIndex = appearance.style2VersionColorMode.coerceIn(0, 2),
                        onSelectedIndexChange = { value -> update { it.copy(style2VersionColorMode = value) } },
                    )
                    OverlayDropdownPreference(
                        title = "文案",
                        items = listOf("跟随系统", "深色", "浅色"),
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
    AppPage(if (home) "设置主界面背景" else "我的设备界面背景", back) { padding, scroll ->
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
                                text = if (home) "设置" else "我的设备",
                                color = when (font) {
                                    1 -> ComposeColor.White
                                    2 -> ComposeColor.Black
                                    else -> MiuixTheme.colorScheme.onSurface
                                },
                                style = MiuixTheme.textStyles.title4,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Text(mime.ifBlank { "暂无预览" }, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
                        ) { Text("选择图片") }
                        Button(
                            onClick = onClear,
                            modifier = Modifier.weight(1f),
                            enabled = mime.isNotBlank(),
                        ) { Text("清除图片") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        title = "启用自定义背景",
                        checked = enabled,
                        onCheckedChange = { value -> change { if (home) it.copy(homeEnabled = value) else it.copy(deviceEnabled = value) } },
                    )
                    SliderPreference(
                        value = blur.toFloat(),
                        onValueChange = { value -> change { if (home) it.copy(homeBlur = value) else it.copy(deviceBlur = value) } },
                        onValueChangeFinished = {},
                        title = "模糊度",
                        valueText = String.format(Locale.US, "%.2f", blur),
                        valueRange = 0f..20f,
                        steps = 1999,
                        enabled = enabled,
                    )
                    SliderPreference(
                        value = opacity.toFloat(),
                        onValueChange = { value -> change { if (home) it.copy(homeOpacity = value.toInt()) else it.copy(deviceOpacity = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = "不透明度",
                        valueText = "$opacity%",
                        valueRange = 0f..100f,
                        steps = 99,
                        enabled = enabled,
                    )
                    OverlayDropdownPreference(
                        title = "字体颜色",
                        items = listOf("默认", "浅色", "深色"),
                        selectedIndex = font,
                        onSelectedIndexChange = { value -> change { if (home) it.copy(homeFontMode = value) else it.copy(deviceFontMode = value) } },
                        enabled = enabled,
                    )
                }
            }
            if (!home) {
                item {
                    Text(
                        "使用自定义我的设备界面背景将失去高级材质LOGO",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
            item {
                Text(
                    "此功能为实验性功能，不能保证使用体验",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
    }
}

@Composable
private fun DeviceProfileField(label: String, value: String, onValueChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    TextField(
        value = text,
        onValueChange = { next -> text = next; onValueChange(next) },
        label = label,
        useLabelAsPlaceholder = true,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
    )
}

@Composable
private fun ServiceCard(online: Boolean) {
    val color = if (online) ComposeColor(0xFF38A169) else ComposeColor(0xFFE05353)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.defaultColors(color.copy(alpha = .14f)), insideMargin = PaddingValues(16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text("LSPosed", style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
            Text(if (online) "\u5df2\u8fde\u63a5" else "\u672a\u8fde\u63a5", style = MiuixTheme.textStyles.body2, color = color, modifier = Modifier.padding(top = 4.dp))
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
private fun Entry(title: String, click: () -> Unit) {
    Card(Modifier.fillMaxWidth()) { ArrowPreference(title = title, onClick = click) }
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
            "通知元素", settings.notificationElementsMaterial, false,
            back,
        ) { update { value ->
            val next = value.copy(notificationElementsMaterial = it)
            if (value.shadeSettingsUnified) next.copy(controlCenterElementsMaterial = it) else next
        } }
        PageId.SHADE_CONTROL_CENTER_ELEMENTS -> MaterialOverrideAdvancedPage(
            "控制中心元素", settings.controlCenterElementsMaterial, false,
            back,
        ) { update { value ->
            val next = value.copy(controlCenterElementsMaterial = it)
            if (value.shadeSettingsUnified) next.copy(notificationElementsMaterial = it) else next
        } }
        PageId.SHADE_NOTIFICATION_BACKGROUND -> MaterialOverrideAdvancedPage(
            "通知中心背景", settings.notificationCenterBackgroundMaterial, true,
            back,
        ) { update { value ->
            val next = value.copy(notificationCenterBackgroundMaterial = it)
            if (value.shadeSettingsUnified) next.copy(controlCenterBackgroundMaterial = it) else next
        } }
        PageId.SHADE_CONTROL_CENTER_BACKGROUND -> MaterialOverrideAdvancedPage(
            "控制中心背景", settings.controlCenterBackgroundMaterial, true,
            back,
        ) { update { value ->
            val next = value.copy(controlCenterBackgroundMaterial = it)
            if (value.shadeSettingsUnified) next.copy(notificationCenterBackgroundMaterial = it) else next
        } }
        PageId.ISLAND -> Island(settings, update, back)
        PageId.STATUS -> Status(settings, update, back)
        PageId.CONTROL -> Control(settings, update, back)
        PageId.LOCK -> Lock(settings, update, openPage, back)
        PageId.RASTER_WALLPAPER -> RasterWallpaper(settings, update, onPickRasterImages, onApplyRasterWallpaper, back)
        PageId.SUPER_XIAOAI -> SuperXiaoAi(settings, update, back)
        PageId.CAMERA -> Camera(cameras, updateCamera, back)
        PageId.SYSTEM_SETTINGS -> SystemSettings(deviceProfile, updateDeviceProfile, appearance, updateAppearance, openPage, onPickAppearanceLogo, onClearAppearanceLogo, back)
        PageId.DEVICE_PROFILE -> DeviceProfileEditor(deviceProfile, updateDeviceProfile, back)
        PageId.SETTINGS_APPEARANCE_HOME -> SettingsAppearancePage(APPEARANCE_SLOT_HOME, appearance, updateAppearance, onPickAppearanceHome, onClearAppearanceHome, back)
        PageId.SETTINGS_APPEARANCE_DEVICE -> SettingsAppearancePage(APPEARANCE_SLOT_DEVICE, appearance, updateAppearance, onPickAppearanceDevice, onClearAppearanceDevice, back)
            PageId.TUTORIAL_DEVICE_CARD -> TutorialDeviceCardSettings(appearance, updateAppearance, onPickAppearanceLogo, onClearAppearanceLogo, onPickTutorialDeviceImage, onClearTutorialDeviceImage, onPickCustomDeviceLogo, onClearCustomDeviceLogo, onPickStyle1UpdateBackground, onClearStyle1UpdateBackground, onPickStyle2DeviceImage, onClearStyle2DeviceImage, onPickStyle2DeviceLogo, onClearStyle2DeviceLogo, onPickStyle2UpdateBackground, onClearStyle2UpdateBackground, back)
        PageId.ABOUT -> About(back)
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
) = AppPage("\u8d85\u7ea7\u5c0f\u7231\u8f93\u5165\u6cd5", back, restartScopes = setOf(ScopeApplication.SUPER_XIAOAI_IME)) { padding, scroll ->
    AppList(padding, scroll, 28) {
        item {
            Group("\u5916\u89c2") {
                SwitchPreference(
                    title = "\u5168\u5c40\u4f7f\u7528\u641c\u7d22\u9875\u5916\u89c2",
                    checked = settings.superXiaoAiGlobalSearchAppearance,
                    onCheckedChange = { enabled ->
                        update { it.copy(superXiaoAiGlobalSearchAppearance = enabled) }
                    },
                )
            }
        }
    }
}

@Composable
private fun Shade(
    s: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    presetActions: ShadePresetActions,
    openPage: (PageId) -> Unit,
    back: () -> Unit,
) = AppPage("\u7cfb\u7edf\u754c\u9762", back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
    var showSavePresetDialog by remember { mutableStateOf(false) }
    AppList(p, scroll, 28) {
        item {
            Group("\u5168\u5c40\u4e3b\u9898") {
                SwitchPreference(
                    title = "\u4f7f\u7528\u5168\u5c40\u4e3b\u9898\u540e\u4fdd\u6301\u67d4\u5149\u73bb\u7483",
                    checked = s.keepSoftGlassAfterGlobalTheme,
                    onCheckedChange = { enabled ->
                        update { it.copy(keepSoftGlassAfterGlobalTheme = enabled) }
                    },
                )
                SwitchPreference(
                    title = "\u89e3\u9664\u201c\u73bb\u7483\u201d\u548c\u201c\u53e0\u52a0\u201d\u65f6\u949f\u6750\u8d28\u9650\u5236",
                    checked = s.removeClockMaterialLimit,
                    onCheckedChange = { enabled ->
                        update { it.copy(removeClockMaterialLimit = enabled) }
                    },
                )
            }
        }
        item { Group("\u9884\u8bbe") {
            ArrowPreference(title = "\u9884\u8bbe", onClick = { openPage(PageId.SHADE_PRESETS) })
            ArrowPreference(title = "\u4fdd\u5b58\u5f53\u524d\u9884\u8bbe", onClick = { showSavePresetDialog = true })
        } }
        item {
            Group("\u63a7\u5236\u4e2d\u5fc3\u4e0e\u901a\u77e5\u4e2d\u5fc3\u6750\u8d28\u8c03\u6574") {
                SwitchPreference(
                    title = "\u63a7\u5236\u4e2d\u5fc3\u548c\u901a\u77e5\u4e2d\u5fc3\u7edf\u4e00\u8bbe\u7f6e",
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
                    MaterialOverrideCard("\u5143\u7d20", s.notificationElementsMaterial, false, { openPage(PageId.SHADE_NOTIFICATION_ELEMENTS) }, wrapCard = false) {
                        update { value ->
                            value.copy(
                                notificationElementsMaterial = it,
                                controlCenterElementsMaterial = it,
                            )
                        }
                    }
                    MaterialOverrideCard("\u80cc\u666f", s.notificationCenterBackgroundMaterial, true, { openPage(PageId.SHADE_NOTIFICATION_BACKGROUND) }, wrapCard = false) {
                        update { value ->
                            value.copy(
                                notificationCenterBackgroundMaterial = it,
                                controlCenterBackgroundMaterial = it,
                            )
                        }
                    }
                } else {
                    MaterialOverrideCard("\u901a\u77e5\u4e2d\u5fc3\u5143\u7d20", s.notificationElementsMaterial, false, { openPage(PageId.SHADE_NOTIFICATION_ELEMENTS) }, wrapCard = false) {
                        update { value -> value.copy(notificationElementsMaterial = it) }
                    }
                    MaterialOverrideCard("\u901a\u77e5\u4e2d\u5fc3\u80cc\u666f", s.notificationCenterBackgroundMaterial, true, { openPage(PageId.SHADE_NOTIFICATION_BACKGROUND) }, wrapCard = false) {
                        update { value -> value.copy(notificationCenterBackgroundMaterial = it) }
                    }
                    MaterialOverrideCard("\u63a7\u5236\u4e2d\u5fc3\u5143\u7d20", s.controlCenterElementsMaterial, false, { openPage(PageId.SHADE_CONTROL_CENTER_ELEMENTS) }, wrapCard = false) {
                        update { value -> value.copy(controlCenterElementsMaterial = it) }
                    }
                    MaterialOverrideCard("\u63a7\u5236\u4e2d\u5fc3\u80cc\u666f", s.controlCenterBackgroundMaterial, true, { openPage(PageId.SHADE_CONTROL_CENTER_BACKGROUND) }, wrapCard = false) {
                        update { value -> value.copy(controlCenterBackgroundMaterial = it) }
                    }
                }
                ArrowPreference(title = "\u63a7\u5236\u4e2d\u5fc3\u5706\u89d2\u8be6\u7ec6\u8bbe\u7f6e", onClick = { openPage(PageId.CONTROL) })
            }
        }
        item {
            Group("\u97f3\u91cf\u9762\u677f") {
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
            title = "\u542f\u7528\u6750\u8d28\u8c03\u6574",
            checked = value.enabled,
            onCheckedChange = { enabled -> onChange(enableMaterialOverride(value, isBackground, enabled)) },
        )
        AnimatedVisibility(
            visible = value.enabled,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
        ) {
            Column {
                ParameterIntSlide("\u6a21\u7cca\u6bd4\u4f8b", value.blurPercent.coerceIn(0, if (isBackground) 100 else 200), 0..if (isBackground) 100 else 200, "%") { onChange(value.copy(blurPercent = it)) }
                if (isBackground) ParameterIntSlide("\u80cc\u666f\u7f29\u653e\u6bd4\u4f8b", value.scalePercent, 0..200, "%") { onChange(value.copy(scalePercent = it)) }
                if (isBackground) {
                    ParameterIntSlide("\u80cc\u666f\u4e0d\u900f\u660e\u5ea6", (value.alpha + 100).coerceIn(0, 100), 0..100, "%") { onChange(value.copy(alpha = it - 100)) }
                    ParameterIntSlide("\u6df7\u8272\u5f3a\u5ea6", value.tintStrength, 0..50, " x0.01") { onChange(value.copy(tintEnabled = it > 0, tintStrength = it)) }
                } else {
                    ParameterIntSlide("Glass \u6a21\u7cca\u534a\u5f84", value.glassRadius.coerceIn(0, 40), 0..40, " px") { onChange(value.copy(glassRadius = it)) }
                    ParameterIntSlide("\u73bb\u7483\u5f3a\u5ea6", (value.refraction / 2 + 50).coerceIn(0, 100), 0..100, "%") { onChange(applyCompactGlassStrength(value, it)) }
                    ParameterIntSlide("\u4e0d\u900f\u660e\u5ea6", value.alpha + 50, 0..100, "%") { onChange(value.copy(alpha = it - 50)) }
                    ParameterIntSlide("\u8fb9\u7f18\u4e0e\u53cd\u5c04", value.reflection + 50, 0..100, "%") { onChange(applyCompactReflection(value, it)) }
                    ParameterIntSlide("\u8272\u5f69", value.saturation + 50, 0..100, "%") { onChange(applyCompactColor(value, it)) }
                }
                ArrowPreference(title = "\u9ad8\u7ea7\u6a21\u5f0f", onClick = openAdvanced)
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
        title = "\u542f\u7528\u6750\u8d28\u8c03\u6574",
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
            ParameterIntSlide("\u80cc\u666f\u4e0d\u900f\u660e\u5ea6", s.volumePanelBackgroundOpacity, 0..100, "%") { value ->
                update { it.copy(volumePanelBackgroundOpacity = value) }
            }
            ParameterIntSlide("\u524d\u666f\u4e0d\u900f\u660e\u5ea6", s.volumePanelBlurRadius, 0..120, " px") { value ->
                update { it.copy(volumePanelBlurRadius = value) }
            }
            FloatSlide("\u5706\u89d2", s.volumePanelCornerRadius, 0f..60f) { value ->
                update { it.copy(volumePanelCornerRadius = value) }
            }
            ParameterIntSlide("\u80cc\u666f\u6a21\u7cca\u5ea6", s.volumePanelGlassStrength, 0..100, "%") { value ->
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
                title = "\u542f\u7528\u6750\u8d28\u8c03\u6574",
                checked = value.enabled,
                onCheckedChange = { enabled -> onChange(enableMaterialOverride(value, isBackground, enabled)) },
            )
            AnimatedVisibility(value.enabled) {
                Column {
                    ParameterIntSlide("\u6a21\u7cca\u6bd4\u4f8b", value.blurPercent.coerceIn(0, if (isBackground) 100 else 200), 0..if (isBackground) 100 else 200, "%") { onChange(value.copy(blurPercent = it)) }
                    if (isBackground) {
                        ParameterIntSlide("\u80cc\u666f\u7f29\u653e\u6bd4\u4f8b", value.scalePercent, 0..200, "%") { onChange(value.copy(scalePercent = it)) }
                        ParameterIntSlide("\u80cc\u666f\u4e0d\u900f\u660e\u5ea6", (value.alpha + 100).coerceIn(0, 100), 0..100, "%") { onChange(value.copy(alpha = it - 100)) }
                    } else {
                        ParameterIntSlide("Glass \u6a21\u7cca\u534a\u5f84", value.glassRadius.coerceIn(0, 40), 0..40, " px") { onChange(value.copy(glassRadius = it)) }
                        ParameterIntSlide("\u4eae\u5ea6\u504f\u79fb", value.brightness, -30..30, " x0.01") { onChange(value.copy(brightness = it)) }
                        ParameterIntSlide("\u538b\u6697\u504f\u79fb", value.darker, -50..50, " x0.01") { onChange(value.copy(darker = it)) }
                        ParameterIntSlide("\u6298\u5c04\u504f\u79fb", value.refraction, -100..100, " x0.01") { onChange(value.copy(refraction = it)) }
                        ParameterIntSlide("\u70e7\u707c\u504f\u79fb", value.burn, -50..50, " x0.01") { onChange(value.copy(burn = it)) }
                        ParameterIntSlide("\u9971\u548c\u5ea6\u504f\u79fb", value.saturation, -100..100, " x0.01") { onChange(value.copy(saturation = it)) }
                        ParameterIntSlide("\u4e0d\u900f\u660e\u5ea6\u504f\u79fb", value.alpha, -50..50, " x0.01") { onChange(value.copy(alpha = it)) }
                        ParameterIntSlide("\u8fb9\u7f18\u539a\u5ea6", value.edgeThickness, -100..100, " x0.01") { onChange(value.copy(edgeThickness = it)) }
                        ParameterIntSlide("\u53cd\u5c04\u5f3a\u5ea6", value.reflection, -100..100, " x0.01") { onChange(value.copy(reflection = it)) }
                        ParameterIntSlide("\u65b9\u5411\u5149\u5f3a\u5ea6", value.directionalLight, -100..100, " x0.01") { onChange(value.copy(directionalLight = it)) }
                        ParameterIntSlide("\u80cc\u666f\u9971\u548c\u5ea6", value.backgroundSaturation, -100..100, " x0.01") { onChange(value.copy(backgroundSaturation = it)) }
                        ParameterIntSlide("\u80cc\u666f\u4eae\u5ea6", value.backgroundBrightness, -100..100, " x0.01") { onChange(value.copy(backgroundBrightness = it)) }
                    }
                    SwitchPreference(
                        title = "\u81ea\u5b9a\u4e49\u6df7\u8272",
                        checked = value.tintEnabled,
                        onCheckedChange = { onChange(value.copy(tintEnabled = it)) },
                    )
                    AnimatedVisibility(value.tintEnabled) {
                        Column {
                            ShortcutBackgroundColorPreference("\u6df7\u8272\u989c\u8272", value.tintColor) { onChange(value.copy(tintColor = it)) }
                            ParameterIntSlide("\u6df7\u8272\u5f3a\u5ea6", value.tintStrength, 0..50, " x0.01") { onChange(value.copy(tintStrength = it)) }
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
) = AppPage("\u9884\u8bbe", back) { p, scroll ->
    var selectedPreset by remember { mutableStateOf<ShadePreset?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    val presets = builtInShadePresets(settings)
    AppList(p, scroll, 28) {
        item {
            Group("\u5bfc\u5165") {
                ArrowPreference(title = "\u5bfc\u5165\u9884\u8bbe", onClick = { showImportDialog = true })
            }
        }
        item { SmallTitle("\u5185\u7f6e\u9884\u8bbe", insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp)) }
        item { Card(Modifier.fillMaxWidth()) {
            presets.forEach { preset ->
                PresetRow(preset, onUse = { update { preset.applyTo(it) } }, onLongPress = { selectedPreset = preset })
            }
        } }
        item { SmallTitle("\u7528\u6237\u9884\u8bbe", insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp)) }
        item { Card(Modifier.fillMaxWidth()) {
            if (actions.userPresets.isEmpty()) {
                Text("\u6682\u65e0\u4fdd\u5b58\u7684\u7528\u6237\u9884\u8bbe", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(16.dp))
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
        "\u7cfb\u7edf\u9ed8\u8ba4" to { it.copy(
            notificationElementsMaterial = MaterialOverride(), controlCenterElementsMaterial = MaterialOverride(),
            notificationCenterBackgroundMaterial = MaterialOverride(), controlCenterBackgroundMaterial = MaterialOverride(),
        ) },
        "\u78e8\u7802\u73bb\u7483" to { it.copy(
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
        "\u6e05\u900f\u73bb\u7483" to { it.copy(
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
        "\u900f\u660e\u73bb\u7483" to { it.copy(
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
        "\u6df1\u8272\u5bf9\u6bd4" to { it.copy(
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
        "\u9ad8\u53cd\u5c04" to { it.copy(
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
        "\u8272\u5f69\u589e\u5f3a" to { it.copy(
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
        "\u8f7b\u91cf\u6d41\u7545" to { it.copy(
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
            Text("保存当前预设", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            TextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = "预设名",
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onDismiss, Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = { onSave(name) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("确定") }
            }
        }
    }
}

@Composable
private fun ImportPresetDialog(show: Boolean, onDismiss: () -> Unit, onJson: () -> Unit, onQr: () -> Unit) {
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("导入预设", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Button(onJson, Modifier.fillMaxWidth()) { Text("JSON") }
            Button(onQr, Modifier.fillMaxWidth()) { Text("二维码") }
            Button(onDismiss, Modifier.fillMaxWidth()) { Text("取消") }
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
            Button(onUse, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("使用") }
            Button(onExport, Modifier.fillMaxWidth()) { Text("导出") }
            if (!preset.builtIn) Button(onDelete, Modifier.fillMaxWidth()) { Text("删除") }
            Button(onDismiss, Modifier.fillMaxWidth()) { Text("取消") }
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
            Text("导出 ${preset.name}", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Button(onJson, Modifier.fillMaxWidth()) { Text("JSON") }
            Button(onQr, Modifier.fillMaxWidth()) { Text("二维码") }
            Button(onDismiss, Modifier.fillMaxWidth()) { Text("取消") }
        }
    }
}

@Composable
private fun QrShareDialog(request: QrShareRequest, onDismiss: () -> Unit, onSave: () -> Unit) {
    val bitmap = remember(request.payload) { createPresetQrCode(request.payload) }
    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(request.name, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Image(bitmap = bitmap.asImageBitmap(), contentDescription = "预设二维码", modifier = Modifier.size(240.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onDismiss, Modifier.weight(1f)) { Text("取消") }
                Button(onSave, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text("保存") }
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
private fun Island(s: HookSettings, update: ((HookSettings) -> HookSettings) -> Unit, back: () -> Unit) = AppPage("\u8d85\u7ea7\u5c9b", back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
    AppList(p, scroll, 28) {
        item {
                Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = "\u53bb\u9664\u7126\u70b9\u901a\u77e5\u4e0e\u8d85\u7ea7\u5c9b\u767d\u540d\u5355\u9650\u5236",
                    checked = s.removeFocusAndIslandWhitelistLimit,
                    onCheckedChange = { value ->
                        update { it.copy(removeFocusAndIslandWhitelistLimit = value) }
                    },
                )
                SwitchPreference(title = "\u81ea\u5b9a\u4e49\u8d85\u7ea7\u5c9b\u957f\u5ea6", checked = s.islandEnabled, onCheckedChange = { v -> update { it.copy(islandEnabled = v) } })
                if (s.islandEnabled) IntSlide("\u6700\u5c0f\u5bbd\u5ea6", s.islandWidth, 108..190) { v -> update { it.copy(islandWidth = v) } }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    title = "\u5c55\u5f00\u6001\u4e0b\u7684\u8d85\u7ea7\u5c9b\u80cc\u666f\u8c03\u6574",
                    checked = s.expandedIslandBackgroundEnabled,
                    onCheckedChange = { v -> update { it.copy(expandedIslandBackgroundEnabled = v) } },
                )
                AnimatedVisibility(
                    visible = s.expandedIslandBackgroundEnabled,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    Column {
                        ParameterIntSlide("\u80cc\u666f\u4e0d\u900f\u660e\u5ea6", s.expandedIslandBackgroundOpacity, 0..100, "%") { value ->
                            update { it.copy(expandedIslandBackgroundOpacity = value) }
                        }
                        ParameterIntSlide("Glass \u5c0f\u6a21\u7cca\u534a\u5f84", s.expandedIslandGlassBlurRadius, 0..40, " px") { value ->
                            update { it.copy(expandedIslandGlassBlurRadius = value) }
                        }
                        ParameterIntSlide("Glass \u5927\u6a21\u7cca\u534a\u5f84", s.expandedIslandGlassLargeBlurRadius, 0..40, " px") { value ->
                            update { it.copy(expandedIslandGlassLargeBlurRadius = value) }
                        }
                        ParameterIntSlide("\u81ea\u6a21\u7cca\u5f3a\u5ea6", s.expandedIslandSelfBlurRadius, 0..40, " px") { value ->
                            update { it.copy(expandedIslandSelfBlurRadius = value) }
                        }
                        SwitchPreference(
                            title = "\u663e\u793a\u9ad8\u5149",
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
private fun Status(s: HookSettings, update: ((HookSettings) -> HookSettings) -> Unit, back: () -> Unit) = AppPage("\u72b6\u6001\u680f", back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
    AppList(p, scroll, 28) {
        item { Card(Modifier.fillMaxWidth()) {
        Dim("\u65f6\u949f\u5927\u5c0f", s.clockEnabled, { v -> update { it.copy(clockEnabled = v) } }, s.clockSize, 10f..24f) { v -> update { it.copy(clockSize = v) } }
        DeltaDim("\u53f3\u8fb9\u8ddd", s.paddingEnd) { v -> update { it.copy(paddingEnd = v, paddingEndEnabled = true, paddingEndLegacyAbsolute = null) } }
        Dim("\u5de6\u8fb9\u8ddd", s.paddingStartEnabled, { v -> update { it.copy(paddingStartEnabled = v) } }, s.paddingStart, 0f..32f) { v -> update { it.copy(paddingStart = v) } }
        Dim("\u72b6\u6001\u680f\u9ad8\u5ea6", s.heightEnabled, { v -> update { it.copy(heightEnabled = v) } }, s.statusBarHeight.toFloat(), 24f..72f) { v -> update { it.copy(statusBarHeight = v.toInt()) } }
        DeltaDim("\u4e0a\u8fb9\u8ddd", s.paddingTop) { v -> update { it.copy(paddingTop = v, paddingTopEnabled = true, paddingTopLegacyAbsolute = null) } }
    } }
        item {
            Group("\u72b6\u6001\u680f\u663e\u793a") {
                SwitchPreference(
                    title = "\u9690\u85cf\u7f51\u7edc\u7c7b\u578b",
                    checked = s.hideStatusBarNetworkType,
                    onCheckedChange = { enabled -> update { it.copy(hideStatusBarNetworkType = enabled) } },
                )
                SwitchPreference(
                    title = "\u9690\u85cf WiFi \u4ee3\u6570",
                    checked = s.hideStatusBarWifiStandard,
                    onCheckedChange = { enabled -> update { it.copy(hideStatusBarWifiStandard = enabled) } },
                )
                SwitchPreference(
                    title = "\u9690\u85cf\u72b6\u6001\u680f\u7535\u6c60\u5185\u6587\u672c",
                    checked = s.hideStatusBarClockText,
                    onCheckedChange = { enabled -> update { it.copy(hideStatusBarClockText = enabled) } },
                )
                SwitchPreference(
                    title = "\u9690\u85cf WiFi/\u79fb\u52a8\u6570\u636e\u6d41\u91cf\u6d3b\u52a8\u56fe\u6807",
                    checked = s.hideStatusBarNetworkActivity,
                    onCheckedChange = { enabled -> update { it.copy(hideStatusBarNetworkActivity = enabled) } },
                )
            }
        }
    }
}

@Composable
private fun Control(s: HookSettings, update: ((HookSettings) -> HookSettings) -> Unit, back: () -> Unit) = AppPage("\u63a7\u5236\u4e2d\u5fc3", back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
    AppList(p, scroll, 28) {
        item { Card(Modifier.fillMaxWidth()) {
        Corner("\u9876\u90e8\u64cd\u4f5c\u6309\u94ae", s.topButtonsRadiusEnabled, { v -> update { it.copy(topButtonsRadiusEnabled = v) } }, s.topButtonsRadius) { v -> update { it.copy(topButtonsRadius = v) } }
        Corner("\u5a92\u4f53\u5361\u7247", s.mediaCardRadiusEnabled, { v -> update { it.copy(mediaCardRadiusEnabled = v) } }, s.mediaCardRadius) { v -> update { it.copy(mediaCardRadius = v) } }
        Corner("\u97f3\u91cf / \u4eae\u5ea6\u6761", s.sliderRadiusEnabled, { v -> update { it.copy(sliderRadiusEnabled = v) } }, s.sliderRadius) { v -> update { it.copy(sliderRadius = v) } }
        Corner("\u4e0b\u534a\u90e8\u5206\u5706\u5f62\u6309\u94ae", s.controlBottomButtonsRadiusEnabled, { v -> update { it.copy(controlBottomButtonsRadiusEnabled = v) } }, s.controlBottomButtonsRadius) { v -> update { it.copy(controlBottomButtonsRadius = v) } }
        Corner("\u878d\u5408\u8bbe\u5907\u4e2d\u5fc3", s.deviceCenterRadiusEnabled, { v -> update { it.copy(deviceCenterRadiusEnabled = v) } }, s.deviceCenterRadius) { v -> update { it.copy(deviceCenterRadius = v) } }
        } }
    }
}

@Composable
private fun Lock(
    s: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    open: (PageId) -> Unit,
    back: () -> Unit,
) = AppPage("\u9501\u5c4f", back, restartScopes = setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD)) { p, scroll ->
    var showBottomTextDialog by remember { mutableStateOf(false) }
    AppList(p, scroll, 28) {
        item {
            Group("\u666f\u6df1") {
                SwitchPreference(title = "\u53bb\u9664\u666f\u6df1\u9650\u5236", checked = s.removeDepthImageLimit, onCheckedChange = { v -> update { it.copy(removeDepthImageLimit = v) } })
            }
        }
        item {
            Group("\u8da3\u5473\u5149\u6805") {
                SwitchPreference(
                    title = "\u5149\u6805\u58c1\u7eb8",
                    summary = "\u968f\u624b\u673a\u503e\u659c\u5728\u591a\u5f20\u56fe\u7247\u95f4\u5207\u6362",
                    checked = s.rasterWallpaperEnabled,
                    onCheckedChange = { value -> update { it.copy(rasterWallpaperEnabled = value) } },
                )
                AnimatedVisibility(
                    visible = s.rasterWallpaperEnabled,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    ArrowPreference(
                        title = "\u5149\u6805\u58c1\u7eb8\u8bbe\u7f6e",
                        summary = "${s.rasterWallpaperUriList().size}/4 \u5f20\u56fe\u7247",
                        onClick = { open(PageId.RASTER_WALLPAPER) },
                    )
                }
            }
        }
        item {
            Group("\u901a\u77e5\u4e0b\u6c89") {
                SwitchPreference(
                    title = "\u53bb\u9664\u901a\u77e5\u4e0b\u6c89\u4f4d\u7f6e\u9650\u5236",
                    checked = s.notificationFodPositionLimitRemoved,
                    onCheckedChange = { value ->
                        update { it.copy(notificationFodPositionLimitRemoved = value) }
                    },
                )
                WindowDropdownPreference(
                    title = "\u9690\u85cf\u6307\u7eb9\u56fe\u6807",
                    items = listOf("\u4e0d\u9690\u85cf", "\u4ec5\u9501\u5c4f\u9690\u85cf", "\u5168\u5c40\u9690\u85cf"),
                    selectedIndex = s.fingerprintHideMode,
                    onSelectedIndexChange = { value ->
                        update { it.copy(fingerprintHideMode = value) }
                    },
                )
            }
        }
        item {
            Group("\u9501\u5c4f\u5e95\u90e8\u6587\u672c") {
                ArrowPreference(
                    title = "\u9690\u85cf\u9501\u5c4f\u5e95\u90e8\u6587\u672c",
                    summary = lockscreenBottomTextSummary(s.lockscreenBottomTextMask),
                    onClick = { showBottomTextDialog = true },
                )
            }
        }
        item {
            Group("\u9501\u5c4f\u8f93\u5165") {
                SwitchPreference(
                    title = "\u9501\u5c4f\u8f93\u5165\u5bc6\u7801\u754c\u9762\u6570\u5b57\u5706\u5f62\u80cc\u666f",
                    checked = s.lockscreenPinCircleBackgroundEnabled,
                    onCheckedChange = { value ->
                        update { it.copy(lockscreenPinCircleBackgroundEnabled = value) }
                    },
                )
            }
        }
        item {
            Group("\u8ff7\u4f60\u97f3\u4e50\u64ad\u653e\u5668") {
        SwitchPreference(
            title = "\u9501\u5c4f\u8ff7\u4f60\u97f3\u4e50\u64ad\u653e\u5668",
            summary = "\u663e\u793a\u5728\u5e95\u90e8\u5feb\u6377\u6309\u94ae\u4e4b\u95f4\uff0c\u8ddf\u968f\u5f53\u524d\u5a92\u4f53\u4f1a\u8bdd",
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
                SwitchPreference(
                    title = "\u97f3\u4e50\u9501\u5c4f",
                    summary = "\u957f\u6309\u9501\u5c4f\u5c9b\u663e\u793a\u5168\u5c4f\u97f3\u4e50\u64ad\u653e\u5668",
                    checked = s.lockscreenMusicLockscreenEnabled,
                    onCheckedChange = { value ->
                        update { it.copy(lockscreenMusicLockscreenEnabled = value) }
                    },
                )
                OverlayDropdownPreference(
                    title = "\u9501\u5c4f\u5a92\u4f53\u901a\u77e5",
                    items = listOf("\u4e0d\u9690\u85cf", "\u59cb\u7ec8\u9690\u85cf", "\u52a8\u6001\u663e\u793a"),
                    selectedIndex = s.lockscreenMiniPlayerMediaNotificationMode,
                    onSelectedIndexChange = { value ->
                        update { it.copy(lockscreenMiniPlayerMediaNotificationMode = value) }
                    },
                )
                OverlayDropdownPreference(
                    title = "\u8ff7\u4f60\u64ad\u653e\u5668\u80cc\u666f",
                    items = listOf("\u8ddf\u968f\u5feb\u6377\u529f\u80fd\u80cc\u666f", "\u7eaf\u8272", "\u9ad8\u7ea7\u6750\u8d28", "\u67d4\u5149\u73bb\u7483"),
                    selectedIndex = s.lockscreenMiniPlayerBackgroundMode,
                    onSelectedIndexChange = { value ->
                        update { it.copy(lockscreenMiniPlayerBackgroundMode = value) }
                    },
                )
                FloatSlide("\u64ad\u653e\u5668\u5bbd\u5ea6 (dp)", s.lockscreenMiniPlayerWidth, 160f..360f) { value ->
                    update { it.copy(lockscreenMiniPlayerWidth = value) }
                }
                FloatSlide("\u64ad\u653e\u5668\u9ad8\u5ea6 (dp)", s.lockscreenMiniPlayerHeight, 28f..80f) { value ->
                    update { it.copy(lockscreenMiniPlayerHeight = value) }
                }
            }
        }
        AnimatedVisibility(
            visible = s.lockscreenMiniPlayerEnabled && s.lockscreenMiniPlayerBackgroundMode == 1,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
        ) {
            ShortcutBackgroundColorPreference(
                title = "\u64ad\u653e\u5668\u80cc\u666f\u989c\u8272",
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
                    title = "\u64ad\u653e\u5668\u6df7\u8272\u989c\u8272",
                    color = s.miniPlayerAdvancedMaterialColor,
                    onColorChange = { value -> update { it.copy(miniPlayerAdvancedMaterialColor = value) } },
                )
                ParameterIntSlide("\u64ad\u653e\u5668\u4e0d\u900f\u660e\u5ea6", s.miniPlayerAdvancedMaterialOpacity, 0..100, "%") { value ->
                    update { it.copy(miniPlayerAdvancedMaterialOpacity = value) }
                }
                ParameterIntSlide("\u64ad\u653e\u5668\u80cc\u666f\u6a21\u7cca\u5ea6", s.miniPlayerAdvancedMaterialBlurRadius, 0..40) { value ->
                    update { it.copy(miniPlayerAdvancedMaterialBlurRadius = value) }
                }
                SwitchPreference(
                    title = "\u64ad\u653e\u5668\u663e\u793a\u9ad8\u5149",
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
                    title = "\u64ad\u653e\u5668\u6df7\u8272\u989c\u8272",
                    color = s.miniPlayerSoftGlassColor,
                    onColorChange = { value -> update { it.copy(miniPlayerSoftGlassColor = value) } },
                )
                ParameterIntSlide("\u64ad\u653e\u5668\u4e0d\u900f\u660e\u5ea6", s.miniPlayerSoftGlassOpacity, 0..100, "%") { value ->
                    update { it.copy(miniPlayerSoftGlassOpacity = value) }
                }
                ParameterIntSlide("\u64ad\u653e\u5668\u80cc\u666f\u6a21\u7cca\u5ea6", s.miniPlayerSoftGlassBackdropBlurRadius, 0..40) { value ->
                    update { it.copy(miniPlayerSoftGlassBackdropBlurRadius = value) }
                }
                ParameterIntSlide("\u64ad\u653e\u5668 Glass \u6a21\u7cca\u5ea6", s.miniPlayerSoftGlassBlurRadius, 0..40) { value ->
                    update { it.copy(miniPlayerSoftGlassBlurRadius = value) }
                }
                ParameterFloatSlide("\u64ad\u653e\u5668\u67d4\u5149\u5f3a\u5ea6", s.miniPlayerSoftGlassLuminance, 0f..0.4f) { value ->
                    update { it.copy(miniPlayerSoftGlassLuminance = value) }
                }
            }
        }
            }
        }
        item {
            Group("\u5feb\u6377\u529f\u80fd\u80cc\u666f") {
        OverlayDropdownPreference(
            title = "\u9501\u5c4f\u5feb\u6377\u529f\u80fd\u80cc\u666f",
            items = listOf("\u4e0d\u663e\u793a", "\u7eaf\u8272", "\u9ad8\u7ea7\u6750\u8d28", "\u67d4\u5149\u73bb\u7483"),
            selectedIndex = s.lockscreenShortcutBackgroundMode,
            onSelectedIndexChange = { value ->
                update { it.copy(lockscreenShortcutBackgroundMode = value) }
            },
        )
        OverlayDropdownPreference(
            title = "\u5feb\u6377\u6309\u94ae\u56fe\u6807\u989c\u8272",
            items = listOf("\u81ea\u52a8\u6a21\u5f0f", "\u6d45\u8272", "\u6df1\u8272"),
            selectedIndex = s.shortcutIconColorMode,
            onSelectedIndexChange = { value -> update { it.copy(shortcutIconColorMode = value) } },
        )
        AnimatedVisibility(
            visible = s.lockscreenShortcutBackgroundMode != 0,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
        ) {
            Column {
                FloatSlide("\u5706\u5f62\u534a\u5f84 (dp)", s.lockscreenShortcutGlassRadius, 28f..80f) { v ->
                    update { it.copy(lockscreenShortcutGlassRadius = v) }
                }
                Dim(
                    title = "\u80cc\u666f\u5706\u89d2\u534a\u5f84",
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
            title = "\u5feb\u6377\u6309\u94ae\u95f4\u8ddd",
            enabled = s.lockscreenShortcutSpacingEnabled,
            changeEnabled = { value -> update { it.copy(lockscreenShortcutSpacingEnabled = value) } },
            value = s.lockscreenShortcutSpacing,
            range = 0f..48f,
        ) { value ->
            update { it.copy(lockscreenShortcutSpacing = value) }
        }
        Dim(
            title = "\u5feb\u6377\u6309\u94ae\u56fe\u6807\u5927\u5c0f",
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
                    title = "\u4e0d\u900f\u660e\u5ea6",
                    value = s.shortcutAdvancedMaterialOpacity,
                    range = 0..100,
                    suffix = "%",
                ) { value -> update { it.copy(shortcutAdvancedMaterialOpacity = value) } }
                ParameterIntSlide(
                    title = "\u80cc\u666f\u6a21\u7cca\u5ea6",
                    value = s.shortcutAdvancedMaterialBlurRadius,
                    range = 0..40,
                ) { value -> update { it.copy(shortcutAdvancedMaterialBlurRadius = value) } }
                SwitchPreference(
                    title = "\u663e\u793a\u9ad8\u5149",
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
                    title = "\u4e0d\u900f\u660e\u5ea6",
                    value = s.shortcutSoftGlassOpacity,
                    range = 0..100,
                    suffix = "%",
                ) { value -> update { it.copy(shortcutSoftGlassOpacity = value) } }
                ParameterIntSlide(
                    title = "\u80cc\u666f\u6a21\u7cca\u5ea6",
                    value = s.shortcutSoftGlassBackdropBlurRadius,
                    range = 0..40,
                ) { value -> update { it.copy(shortcutSoftGlassBackdropBlurRadius = value) } }
                ParameterIntSlide(
                    title = "Glass \u6a21\u7cca\u5ea6",
                    value = s.shortcutSoftGlassBlurRadius,
                    range = 0..40,
                ) { value -> update { it.copy(shortcutSoftGlassBlurRadius = value) } }
                ParameterFloatSlide(
                    title = "\u67d4\u5149\u5f3a\u5ea6",
                    value = s.shortcutSoftGlassLuminance,
                    range = 0f..0.4f,
                ) { value -> update { it.copy(shortcutSoftGlassLuminance = value) } }
            }
        }
            }
        }
    }
        LockScreenBottomTextDialog(
            show = showBottomTextDialog,
            mask = s.lockscreenBottomTextMask,
            onDismiss = { showBottomTextDialog = false },
            onApply = { value ->
                update { it.copy(lockscreenBottomTextMask = value) }
                showBottomTextDialog = false
            },
        )
    }

private const val LOCKSCREEN_TEXT_CHARGING = 1
private const val LOCKSCREEN_TEXT_DND = 2
private const val LOCKSCREEN_TEXT_NOTIFICATIONS = 4

private fun lockscreenBottomTextSummary(mask: Int): String = listOf(
    LOCKSCREEN_TEXT_CHARGING to "\u5145\u7535\u4e2d",
    LOCKSCREEN_TEXT_DND to "\u52ff\u6270",
    LOCKSCREEN_TEXT_NOTIFICATIONS to "X\u4e2a\u901a\u77e5",
).filter { mask and it.first != 0 }.joinToString(" / ") { it.second }.ifBlank { "\u672a\u9690\u85cf" }

@Composable
private fun LockScreenBottomTextDialog(
    show: Boolean,
    mask: Int,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit,
) {
    var selected by remember(show, mask) { mutableIntStateOf(mask) }
    val items = listOf(
        LOCKSCREEN_TEXT_CHARGING to "\u5145\u7535\u4e2d",
        LOCKSCREEN_TEXT_DND to "\u52ff\u6270",
        LOCKSCREEN_TEXT_NOTIFICATIONS to "X\u4e2a\u901a\u77e5",
    )
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("\u9009\u62e9\u8981\u9690\u85cf\u7684\u9501\u5c4f\u5e95\u90e8\u6587\u672c", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            items.forEach { (bit, title) ->
                val toggle = { selected = if (selected and bit != 0) selected and bit.inv() else selected or bit }
                Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state = if (selected and bit != 0) ToggleableState.On else ToggleableState.Off, onClick = toggle)
                    Text(title, style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(start = 10.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onDismiss, Modifier.weight(1f)) { Text("\u53d6\u6d88") }
                Button({ onApply(selected) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text("\u5e94\u7528") }
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
) = AppPage("\u5149\u6805\u58c1\u7eb8", back) { p, scroll ->
    val context = LocalContext.current
    val sources = s.rasterWallpaperUriList()
    val sensitivityItems = listOf("\u4f4e", "\u6807\u51c6", "\u9ad8", "\u8d85\u9ad8", "\u81ea\u5b9a\u4e49")
    AppList(p, scroll, 28) {
        item {
            Group("\u7d20\u6750") {
                ArrowPreference(
                    title = "\u6279\u91cf\u9009\u62e9\u56fe\u7247\u6216\u77ed\u89c6\u9891",
                    summary = if (sources.isEmpty()) "\u8bf7\u9009\u62e9 2\u20134 \u4e2a\u7d20\u6750" else "${sources.size}/4 \u4e2a\u7d20\u6750",
                    onClick = onPickImages,
                )
            }
        }
        item {
            Group("\u4f53\u611f") {
                OverlayDropdownPreference(
                    title = "\u503e\u659c\u7075\u654f\u5ea6",
                    items = sensitivityItems,
                    selectedIndex = s.rasterWallpaperSensitivityPreset.coerceIn(0, 4),
                    onSelectedIndexChange = { value -> update { it.copy(rasterWallpaperSensitivityPreset = value) } },
                )
                AnimatedVisibility(visible = s.rasterWallpaperSensitivityPreset == 4) {
                    ParameterFloatSlide(
                        title = "\u81ea\u5b9a\u4e49\u7075\u654f\u5ea6",
                        value = s.rasterWallpaperCustomSensitivity,
                        range = 0.1f..2f,
                    ) { value -> update { it.copy(rasterWallpaperCustomSensitivity = value) } }
                }
            }
        }
        item {
            Group("\u5e94\u7528") {
                Button(
                    onClick = {
                        if (sources.size < 2) {
                            Toast.makeText(context, "至少选择 2 个素材", Toast.LENGTH_SHORT).show()
                        } else {
                            onApply()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("\u5e94\u7528") }
            }
        }
    }
}

@Composable
private fun ShortcutBackgroundColorPreference(
    title: String = "\u80cc\u666f\u989c\u8272",
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
                "\u9009\u62e9\u80cc\u666f\u989c\u8272",
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
                Button(onDismiss, Modifier.weight(1f)) { Text("\u53d6\u6d88") }
                Button(
                    onClick = { onConfirm(draftColor) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text("\u786e\u5b9a") }
            }
        }
    }
}

@Composable
private fun Camera(c: CameraSettings, update: ((CameraSettings) -> CameraSettings) -> Unit, back: () -> Unit) = AppPage("\u76f8\u673a\u4e0e\u76f8\u518c\u7f16\u8f91", back, restartScopes = setOf(ScopeApplication.CAMERA, ScopeApplication.GALLERY, ScopeApplication.MEDIA_EDITOR)) { p, scroll ->
    AppList(p, scroll, 28) { item { Card(Modifier.fillMaxWidth()) {
        SwitchPreference(title = "\u542f\u7528\u76f8\u673a\u6a21\u5757", checked = c.masterEnabled, onCheckedChange = { v -> update { it.copy(masterEnabled = v) } })
        SwitchPreference(title = "Leica LCC UI", checked = c.leicaUi, enabled = c.masterEnabled, onCheckedChange = { v -> update { it.copy(leicaUi = v) } })
        SwitchPreference(title = "\u4fdd\u7559\u539f\u751f\u7126\u6bb5", checked = c.preserveNativeFocalLengths, enabled = c.masterEnabled, onCheckedChange = { v -> update { it.copy(preserveNativeFocalLengths = v) } })
        SwitchPreference(title = "\u76f8\u518c\u7f16\u8f91\u5168\u6c34\u5370", checked = c.galleryAllWatermarks, enabled = c.masterEnabled, onCheckedChange = { v -> update { it.copy(galleryAllWatermarks = v) } })
    } } }
}

@Composable
private fun Dim(title: String, enabled: Boolean, changeEnabled: (Boolean) -> Unit, value: Float, range: ClosedFloatingPointRange<Float>, save: (Float) -> Unit) {
    SwitchPreference(title = "\u81ea\u5b9a\u4e49$title", checked = enabled, onCheckedChange = changeEnabled)
    if (enabled) FloatSlide(title, value, range, save)
}

@Composable
private fun DeltaDim(title: String, value: Float, save: (Float) -> Unit) {
    FloatSlide(title, value, -35f..35f, save)
}

@Composable
private fun Corner(title: String, enabled: Boolean, changeEnabled: (Boolean) -> Unit, value: Float, save: (Float) -> Unit) {
    SwitchPreference(title = "\u81ea\u5b9a\u4e49$title\u5706\u89d2", checked = enabled, onCheckedChange = changeEnabled)
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
private fun About(back: () -> Unit) = AppPage("\u5173\u4e8e", back) { padding, scroll ->
    val context = LocalContext.current
    AppList(padding, scroll, 28) {
        item {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Image(painterResource(R.drawable.ic_hyperchanger_full), "HyperChanger", Modifier.size(72.dp), contentScale = ContentScale.Fit)
                Text("HyperChanger", style = MiuixTheme.textStyles.title1, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                Text("\u4e00\u4e2a\u4e34\u65f6\u7528\u4e8e\u89e3\u9501\u5c0f\u7c73\u6f8e\u6e43 OS 4 Beta \u7248\u9650\u5236\u7684\u6a21\u5757\u3002", style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(top = 6.dp))
                Box(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(1.dp).background(MiuixTheme.colorScheme.outline.copy(alpha = .22f)))
                Text(BuildConfig.VERSION_NAME, style = MiuixTheme.textStyles.body2)
            }
        }
        item {
            Group("\u5f00\u53d1\u8005") {
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
        item { Card(Modifier.fillMaxWidth()) { Text("\u672c\u9879\u76ee\u57fa\u4e8e MIT \u534f\u8bae\u5f00\u6e90", style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)); ArrowPreference(title = "GitHub Repository", summary = "github.com/ColdP/HyperChanger", onClick = { openUrl(context, "https://github.com/ColdP/HyperChanger") }) } }
        item { Text("\u00a9 ${Year.now().value} btm_m", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .56f), modifier = Modifier.padding(start = 12.dp)) }
    }
}

@Composable
private fun Donate(back: () -> Unit) = AppPage("\u6350\u8d60", back) { padding, scroll ->
    val context = LocalContext.current
    AppList(padding, scroll, 28) {
        item { Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) { Image(painterResource(R.drawable.btm_m_avatar), "btm_m", Modifier.size(84.dp).clip(androidx.compose.foundation.shape.CircleShape), contentScale = ContentScale.Crop); Text("btm_m", style = MiuixTheme.textStyles.title2, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) } }
        item { Group("\u7231\u53d1\u7535") { ArrowPreference(title = "\u901a\u8fc7\u7231\u53d1\u7535\u652f\u6301\u6211", summary = "\u7231\u53d1\u7535\uff1abtm_m", onClick = { openUrl(context, "https://afdian.com/a/btm_m") }) } }
        item { Group("\u5fae\u4fe1\u8d5e\u8d4f\u7801") { Image(painterResource(R.drawable.mm_reward_lightmode), "\u5fae\u4fe1\u8d5e\u8d4f\u7801", Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp), contentScale = ContentScale.Fit) } }
    }
}

private data class OpenProject(val name: String, val version: String, val description: String, val url: String)
private val openProjects = listOf(
    OpenProject("MIUIX", "0.9.3", "HyperOS \u98ce\u683c\u754c\u9762\u3001\u504f\u597d\u8bbe\u7f6e\u3001\u56fe\u6807\u4e0e\u6a21\u7cca\u6548\u679c", "https://github.com/compose-miuix-ui/miuix"),
    OpenProject("LSPosed API", "102", "LSPosed \u6a21\u5757 API \u4e0e\u670d\u52a1\u901a\u4fe1", "https://github.com/LSPosed/LSPosed"),
    OpenProject("Backdrop / AndroidLiquidGlass", "2.0.0", "\u6db2\u6001\u73bb\u7483\u6e32\u67d3\u4e0e\u5e95\u90e8\u5bfc\u822a\u4ea4\u4e92", "https://github.com/Kyant0/AndroidLiquidGlass"),
    OpenProject("Compose Multiplatform", "1.11.x", "\u58f0\u660e\u5f0f\u754c\u9762\u3001\u5e03\u5c40\u4e0e\u52a8\u753b", "https://github.com/JetBrains/compose-multiplatform"),
    OpenProject("AndroidX", "\u591a\u4e2a\u7ec4\u4ef6", "Activity\u3001Lifecycle\u3001Core \u7b49 Android \u57fa\u7840\u5e93", "https://github.com/androidx/androidx")
)

@Composable
private fun OpenSource(back: () -> Unit) = AppPage("\u5f00\u6e90\u4ee3\u7801\u58f0\u660e", back) { padding, scroll ->
    val context = LocalContext.current
    AppList(padding, scroll, 28) {
        item { Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) { Text("HyperChanger \u4f7f\u7528\u4e86\u4ee5\u4e0b\u5f00\u6e90\u9879\u76ee\u3002\u611f\u8c22\u6240\u6709\u9879\u76ee\u4f5c\u8005\u4e0e\u8d21\u732e\u8005\u3002", style = MiuixTheme.textStyles.body1) } }
        item { SmallTitle("\u754c\u9762\u3001\u529f\u80fd\u4e0e\u5e73\u53f0", insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp)) }
        items(openProjects.size) { i -> val item = openProjects[i]; Card(Modifier.fillMaxWidth().clickable { openUrl(context, item.url) }, insideMargin = PaddingValues(16.dp)) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(item.name, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold); Text("${item.version} \u00b7 Apache License 2.0", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 3.dp)); Text(item.description, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 6.dp)) }; Image(MiuixIcons.Regular.ChevronForward, null, Modifier.padding(start = 12.dp).size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary)) } } }
    }
}

@Composable
private fun AppPage(
    title: String,
    onBack: (() -> Unit)? = null,
    restartScopes: Set<ScopeApplication> = emptySet(),
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues, ScrollBehavior) -> Unit,
) {
    val context = LocalContext.current
    var showRestartScopeDialog by remember { mutableStateOf(false) }
    val scroll = MiuixScrollBehavior()
    val backdrop = rememberLayerBackdrop()
    val surface = MiuixTheme.colorScheme.surface
    val collapsedFraction = scroll.state.collapsedFraction.coerceIn(0f, 1f)
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
                TopAppBar(
                    title = "",
                    largeTitle = title,
                    color = ComposeColor.Transparent,
                    scrollBehavior = scroll,
                    navigationIcon = {
                        if (onBack != null) GlassBackButton(backdrop, collapsedFraction, onBack)
                    },
                    actions = {
                        if (restartScopes.isNotEmpty()) {
                            GlassRefreshButton(backdrop, collapsedFraction) { showRestartScopeDialog = true }
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
    ) { padding -> Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { content(padding, scroll) } }
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

@Composable
private fun GlassBackButton(backdrop: LayerBackdrop, collapsedFraction: Float, onClick: () -> Unit) {
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
    val glassTint = MiuixTheme.colorScheme.surface.copy(alpha = .42f)
    val iconColor = MiuixTheme.colorScheme.onSurface
    val glassAlpha by animateFloatAsState(collapsedFraction, tween(180), label = "backButtonGlassAlpha")
    Box(
        Modifier.padding(start = 8.dp).size(46.dp)
            .then(highlight.gestureModifier)
            .then(drag.modifier)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
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
                        blur(4.dp.toPx())
                        val progress = drag.pressProgress
                        lens(8.dp.toPx() * progress, 12.dp.toPx() * progress, chromaticAberration = true)
                    },
                    highlight = { Highlight.Default.copy(alpha = .65f + drag.pressProgress * .35f) },
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
            MiuixIcons.Regular.ChevronBackward,
            "\u8fd4\u56de",
            Modifier.size(22.dp).graphicsLayer { translationX = -1.15.dp.toPx() },
            colorFilter = ColorFilter.tint(iconColor),
        )
    }
}

@Composable
private fun GlassRefreshButton(backdrop: LayerBackdrop, collapsedFraction: Float, onClick: () -> Unit) {
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
    val glassTint = MiuixTheme.colorScheme.surface.copy(alpha = .42f)
    val iconColor = MiuixTheme.colorScheme.onSurface
    val glassAlpha by animateFloatAsState(collapsedFraction, tween(180), label = "refreshButtonGlassAlpha")
    Box(
        Modifier.padding(end = 8.dp).size(46.dp)
            .then(highlight.gestureModifier)
            .then(drag.modifier)
            .clickable(interactionSource = null, indication = null, onClick = onClick),
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
                        blur(4.dp.toPx())
                        val progress = drag.pressProgress
                        lens(8.dp.toPx() * progress, 12.dp.toPx() * progress, chromaticAberration = true)
                    },
                    highlight = { Highlight.Default.copy(alpha = .65f + drag.pressProgress * .35f) },
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
            "重启作用域应用",
            Modifier.size(22.dp),
            colorFilter = ColorFilter.tint(iconColor),
        )
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

private fun copyAppearanceFile(context: android.content.Context, slot: String, uri: Uri): File {
    val directory = File(context.filesDir, "settings_appearance")
    check(directory.exists() || directory.mkdirs()) { "无法创建配置目录" }
    val target = appearanceFile(context, slot)
    val temporary = File(directory, "$slot.bin.tmp")
    if (temporary.exists()) temporary.delete()
    var total = 0L
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "无法读取文件" }
        FileOutputStream(temporary).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= 200L * 1024L * 1024L) { "文件不能超过200MB" }
                output.write(buffer, 0, count)
            }
            output.fd.sync()
        }
    }
    check(!target.exists() || target.delete()) { "无法替换旧文件" }
    check(temporary.renameTo(target)) { "无法保存文件" }
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
