// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import btm.m.os4.systemuihook.newnotificationcenter.IosNotificationCenterSettingsStore
import btm.m.os4.systemuihook.newnotificationcenter.IosNotificationShortcutsSettingsStore
import btm.m.os4.systemuihook.newnotificationcenter.IosNotificationBottomOffsetSettingsStore
import btm.m.os4.systemuihook.newnotificationcenter.IosNotificationHeaderSettingsStore
import btm.m.os4.systemuihook.newnotificationcenter.IosNotificationHeaderSettings
import btm.m.os4.systemuihook.newnotificationcenter.IosNotificationLayoutSettingsStore

import android.app.Activity
import android.app.DownloadManager
import android.app.WallpaperManager
import android.Manifest
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.res.Resources
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
import android.os.Build
import android.os.Environment
import btm.m.os4.systemuihook.ModuleLog as Log
import android.util.Base64
import android.widget.Toast
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import androidx.compose.ui.unit.Constraints
import java.util.UUID
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.items
import androidx.core.view.WindowCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import btm.m.liquidglass.LabelMode
import btm.m.liquidglass.NavigationStyle
import btm.m.liquidglass.ScopedSettings
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
import com.kyant.shapes.Capsule
import top.yukonga.miuix.kmp.blur.LayerBackdrop as MiuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop as miuixLayerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop as rememberMiuixLayerBackdrop
import io.github.libxposed.service.XposedService
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.All
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.preference.SliderPreference as MiuixSliderPreference
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.time.Year
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val hookStore by lazy { HookSettingsStore(this) }
    private val navigationStore by lazy { ModuleNavigationSettingsStore(this) }
    private val lockscreenCapsuleStore by lazy { LockscreenCapsuleSettingsStore(this) }
    private val cameraStore by lazy { CameraSettingsStore(this) }
    private val deviceProfileStore by lazy { DeviceProfileStore(this) }
    private val appearanceStore by lazy { SettingsAppearanceStore(this) }
    private val airDropStore by lazy { AirDropSettingsStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContent { Root(hookStore, navigationStore, lockscreenCapsuleStore, cameraStore, deviceProfileStore, appearanceStore, airDropStore) }
    }
}

private enum class Tab { CATEGORY, SETTINGS }

private val LocalNotificationSettingsChanged = compositionLocalOf<() -> Unit> { {} }
private val LocalDesktopSettingsChanged = compositionLocalOf<() -> Unit> { {} }
private val LocalXiaoAiSettingsChanged = compositionLocalOf<() -> Unit> { {} }
private val LocalPageBackSuppressed = compositionLocalOf<(Boolean) -> Unit> { {} }
private val LocalLanguageChanged = compositionLocalOf<() -> Unit> { {} }
val LocalDialogBackdrop = compositionLocalOf<Backdrop?> { null }
private var activeLanguageStrings by mutableStateOf<Map<String, String>>(emptyMap())

internal fun tr(key: String, fallback: String): String =
    activeLanguageStrings[key] ?: activeLanguageStrings[fallback] ?: fallback

private fun systemLanguage(context: android.content.Context): String = runCatching {
    val locales = context.getSystemService(android.app.LocaleManager::class.java)?.systemLocales
        ?: Resources.getSystem().configuration.locales
    if (!locales.isEmpty) locales[0].language else Locale.getDefault().language
}.getOrDefault(Locale.getDefault().language)

private fun languageStrings(
    context: android.content.Context,
    prefs: android.content.SharedPreferences,
    selection: String = prefs.getString("selected", "system") ?: "system",
): Map<String, String> = runCatching {
    if (selection == "system") return@runCatching builtinLanguageStrings(context, systemLanguage(context))
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
    MODULE_SAFETY, MODULE_LOGS, ENVIRONMENT_DETECTION,
    SHADE,
    SHADE_PRESETS,
    SOFT_GLASS_PARAMETERS,
    SOFT_GLASS_PRESETS,
    IOS_NOTIFICATION_CLOCK_GLASS, CONTROL_CENTER_TOP_BUTTONS,
    GESTURE_HANDLE_STYLE, DESKTOP, DESKTOP_MATERIAL,
    ISLAND, ISLAND_COLLAPSED, ISLAND_EXPANDED, ISLAND_EXPANDED_BACKGROUND, IOS_NOTIFICATION_CENTER, IOS_NOTIFICATION_HEADER_SETTINGS, UNLOCK_ISLAND, NOTIFICATION_RESTRICTIONS, NOTIFICATION_MEDIA_CARD, STATUS, STATUS_ADJUSTMENT, STATUS_SIGNAL_CUSTOMIZATION, STATUS_SIGNAL_TUNING, CONTROL, LOCK, LOCKSCREEN_MUSIC, LOCKSCREEN_CAPSULE, LOCKSCREEN_SHORTCUT_BACKGROUND, LIFT_TO_WAKE_SCHEDULE, LOCKSCREEN_WIDGET_EDITOR, LOCKSCREEN_WIDGET_BACKGROUND, LYRIC_LIBRARY, RASTER_WALLPAPER, SUPER_XIAOAI, CAMERA, CAMERA_PALETTE, SYSTEM_UPDATE, SYSTEM_SETTINGS, SYSTEM_FRAMEWORK, DEVICE_PROFILE,
    DEVICE_PROFILE_PRESETS, DEVICE_PROFILE_CLOUD_PRESETS, CUSTOM_DEPTH_WALLPAPER,
    SETTINGS_APPEARANCE_HOME, SETTINGS_APPEARANCE_DEVICE, SETTINGS_ENTRY_LABELS, THEME_AND_COLOR, TUTORIAL_DEVICE_CARD, ABOUT, LICENSE, LANGUAGE, DONATE, OPEN, CONTRIBUTORS,
    LEGAL_DISCLAIMER, LEGAL_PRIVACY, LEGAL_USER_AGREEMENT,
    SOFTWARE_UPDATE, UPDATE_LOG, REAR_SCREEN, REAR_SCREEN_SHELL, REAR_CUSTOM_CENTER, REAR_MUSIC_APPS, REAR_SWIPE_APP, REAR_MINI_PLAYER, OTHER, CONTACTS_DIALER, CONTACTS_DIALER_BACKGROUND, PORTAL, PORTAL_MATERIAL, APP_NAVIGATION, RESTART_SCOPES, DISCLAIMER, SIMULATE_MEDIA_NOTIFICATION, SIMULATE_ISLAND,
}

private data class CategorySearchItem(
    val title: String,
    val description: String,
    val scopes: Set<ScopeApplication>,
    val page: PageId,
)

private data class CategoryPageEntry(
    val title: String,
    val page: PageId,
)

private fun categorySearchItems(): List<CategorySearchItem> = listOf(
    CategorySearchItem(
        tr("系统界面与材质调整", "系统界面与材质调整"),
        tr("category_search_system_ui_description", "调整通知、控制中心、状态栏和手势提示线。"),
        setOf(ScopeApplication.SYSTEM_UI),
        PageId.SHADE,
    ),
    CategorySearchItem(
        tr("桌面", "桌面"),
        tr("category_search_desktop_description", "调整桌面、文件夹、Dock 和最近任务。"),
        setOf(ScopeApplication.DESKTOP),
        PageId.DESKTOP,
    ),
    CategorySearchItem(
        tr("通知与超级岛", "通知与超级岛"),
        tr("category_search_island_description", "调整通知下沉、指纹图标和超级岛相关功能。"),
        setOf(ScopeApplication.SYSTEM_UI),
        PageId.ISLAND,
    ),
    CategorySearchItem(
        tr("状态栏与控制中心", "状态栏与控制中心"),
        tr("category_search_status_description", "调整状态栏图标、信号显示和控制中心布局。"),
        setOf(ScopeApplication.SYSTEM_UI),
        PageId.STATUS,
    ),
    CategorySearchItem(
        tr("锁屏", "锁屏"),
        tr("category_search_lockscreen_description", "调整锁屏时钟、壁纸、小组件、歌词和锁屏胶囊。"),
        setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD),
        PageId.LOCK,
    ),
    CategorySearchItem(
        tr("超级小爱与输入法", "超级小爱与输入法"),
        tr("category_search_xiaoai_description", "调整超级小爱悬浮窗口和输入法。"),
        setOf(ScopeApplication.SUPER_XIAOAI, ScopeApplication.SUPER_XIAOAI_IME),
        PageId.SUPER_XIAOAI,
    ),
    CategorySearchItem(
        tr("相机与相册编辑", "相机与相册编辑"),
        tr("category_search_camera_description", "调整相机、相册和媒体编辑器的功能。"),
        setOf(ScopeApplication.CAMERA, ScopeApplication.GALLERY, ScopeApplication.MEDIA_EDITOR),
        PageId.CAMERA,
    ),
    CategorySearchItem(
        tr("系统更新", "系统更新"),
        tr("category_search_update_description", "阻止自动更新检查并调整系统更新行为。"),
        setOf(ScopeApplication.SYSTEM_UPDATE),
        PageId.SYSTEM_UPDATE,
    ),
    CategorySearchItem(
        tr("系统设置", "系统设置"),
        tr("category_search_settings_description", "调整设置应用首页、设备信息和外观。"),
        setOf(ScopeApplication.SETTINGS),
        PageId.SYSTEM_SETTINGS,
    ),
    CategorySearchItem(
        tr("系统框架", "系统框架"),
        tr("category_search_framework_description", "调整小窗、音量、截图与系统服务行为。"),
        setOf(ScopeApplication.SETTINGS),
        PageId.SYSTEM_FRAMEWORK,
    ),
    CategorySearchItem(
        tr("背屏", "背屏"),
        tr("category_search_rear_screen_description", "解锁和自定义背屏应用、壁纸与媒体控件。"),
        setOf(ScopeApplication.SUBSCREEN_CENTER, ScopeApplication.THEME_MANAGER, ScopeApplication.PERSONAL_ASSISTANT),
        PageId.REAR_SCREEN,
    ),
    CategorySearchItem(
        tr("其他", "其他"),
        tr("category_search_other_description", "调整屏幕录制、健康和小米互传等功能。"),
        setOf(ScopeApplication.SCREEN_RECORDER, ScopeApplication.XIAOMI_HEALTH, ScopeApplication.AIRDROP),
        PageId.OTHER,
    ),
    CategorySearchItem(
        tr("拨号与通话", "拨号与通话"),
        tr("category_search_contacts_dialer_description", "调整拨号盘背景模糊、模糊度与不透明度。"),
        setOf(ScopeApplication.CONTACTS_DIALER),
        PageId.CONTACTS_DIALER,
    ),
    CategorySearchItem(
        tr("iosNotificationCenter", "iOS 风格通知中心"),
        tr("iosNotificationCenterSummary", "下拉时展示圆角壁纸面板、锁屏时钟及景深效果。动态壁纸暂不支持。"),
        setOf(ScopeApplication.SYSTEM_UI),
        PageId.SHADE,
    ),
    CategorySearchItem(
        tr("iosNotificationHideClear", "隐藏通知中心清除按钮"),
        tr("iosNotificationHideClearSummary", "隐藏通知中心底部的清除按钮"),
        setOf(ScopeApplication.SYSTEM_UI),
        PageId.IOS_NOTIFICATION_CENTER,
    ),
    CategorySearchItem(
        tr("controlCenterBigClock", "在控制中心顶栏日期之上添加大时钟"),
        tr("category_search_control_center_clock_description", "在控制中心日期上方显示可自定义的大时钟。"),
        setOf(ScopeApplication.SYSTEM_UI),
        PageId.STATUS,
    ),
    CategorySearchItem(
        tr("controlCenterCarrierCustom", "自定义运营商文本"),
        tr("category_search_carrier_description", "自定义或隐藏状态栏中的运营商名称。"),
        setOf(ScopeApplication.SYSTEM_UI),
        PageId.STATUS,
    ),
    CategorySearchItem(
        tr("全局隐藏手势提示线", "全局隐藏手势提示线"),
        tr("category_search_gesture_description", "隐藏系统底部的手势提示线，也可以单独调整其样式。"),
        setOf(ScopeApplication.SYSTEM_UI),
        PageId.SHADE,
    ),
    CategorySearchItem(
        tr("为桌面添加 Dock", "为桌面添加 Dock"),
        tr("category_search_dock_description", "在桌面底部添加 Dock，需要重启桌面后生效。"),
        setOf(ScopeApplication.DESKTOP),
        PageId.DESKTOP,
    ),
    CategorySearchItem(
        tr("锁屏胶囊", "锁屏胶囊"),
        tr("category_search_capsule_description", "在锁屏上显示媒体和通知胶囊。"),
        setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD),
        PageId.LOCKSCREEN_CAPSULE,
    ),
    CategorySearchItem(
        tr("锁屏小组件", "锁屏小组件"),
        tr("category_search_widget_description", "编辑锁屏小组件、背景和设备名称。"),
        setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD),
        PageId.LOCKSCREEN_WIDGET_EDITOR,
    ),
    CategorySearchItem(
        tr("光栅壁纸", "光栅壁纸"),
        tr("category_search_raster_description", "随手机倾斜在多张图片间切换。"),
        setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.WALLPAPER),
        PageId.RASTER_WALLPAPER,
    ),
    CategorySearchItem(
        tr("自定义背屏中心", "自定义背屏中心"),
        tr("category_search_rear_center_description", "查看和管理已导入的自定义背屏应用。"),
        setOf(ScopeApplication.SUBSCREEN_CENTER),
        PageId.REAR_CUSTOM_CENTER,
    ),
    CategorySearchItem(
        tr("相机调色板", "相机调色板"),
        tr("category_search_camera_palette_description", "自定义相机取景器中的调色板和色彩保护。"),
        setOf(ScopeApplication.CAMERA, ScopeApplication.GALLERY, ScopeApplication.MEDIA_EDITOR),
        PageId.CAMERA_PALETTE,
    ),
    CategorySearchItem(
        tr("应用底部导航", "应用底部导航"),
        tr("category_search_app_navigation_description", "为支持的应用自定义底部导航样式。"),
        setOf(
            ScopeApplication.APPLE_MUSIC,
            ScopeApplication.WEIBO,
            ScopeApplication.XIAOHONGSHU,
            ScopeApplication.XIAOMI_STORE,
            ScopeApplication.XIAOMI_WALLET,
            ScopeApplication.XIAOMI_HEALTH,
            ScopeApplication.LUCKIN_COFFEE,
            ScopeApplication.TENCENT_MEETING,
            ScopeApplication.CMB_BANK,
            ScopeApplication.WECHAT,
            ScopeApplication.QQ,
            ScopeApplication.PINDUODUO,
            ScopeApplication.INSTAGRAM,
        ScopeApplication.X,
        ),
        PageId.APP_NAVIGATION,
    ),
)

private data class CustomRearScreenApp(
    val productId: String,
    val name: String,
    val iconPath: String,
    val extractedPath: String,
    val runtimePath: String,
)

private data class CustomRearScreenImportPreview(
    val uri: Uri,
    val preview: ImageBitmap?,
    val icon: ImageBitmap?,
    val name: String,
    val description: String,
)

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

private fun suggestedScopesForHookChange(before: HookSettings, after: HookSettings): Set<ScopeApplication> {
    if (before == after) return emptySet()
    val changedNames = runCatching {
        HookSettings::class.java.declaredFields.mapNotNull { field ->
            field.isAccessible = true
            field.name.takeIf { field.get(before) != field.get(after) }
        }
    }.getOrDefault(emptyList())
    val scopes = mutableSetOf(ScopeApplication.SYSTEM_UI)
    if (changedNames.any { it.contains("lock", true) || it.contains("aod", true) || it.contains("clock", true) }) {
        scopes += ScopeApplication.AOD
    }
    if (changedNames.any { it.contains("wallpaper", true) }) scopes += ScopeApplication.WALLPAPER
    if (changedNames.any { it.contains("theme", true) }) scopes += ScopeApplication.THEME_MANAGER
    if (changedNames.any { it.contains("rear", true) || it.contains("subscreen", true) }) {
        scopes += setOf(
            ScopeApplication.SUBSCREEN_CENTER,
            ScopeApplication.THEME_MANAGER,
            ScopeApplication.PERSONAL_ASSISTANT,
        )
    }
    if (changedNames.any { it.contains("xiaoai", true) }) scopes += ScopeApplication.SUPER_XIAOAI_IME
    return scopes
}

@Composable
private fun Root(
    hooks: HookSettingsStore,
    navigation: ModuleNavigationSettingsStore,
    lockscreenCapsules: LockscreenCapsuleSettingsStore,
    cameras: CameraSettingsStore,
    deviceProfiles: DeviceProfileStore,
    appearances: SettingsAppearanceStore,
    airDrop: AirDropSettingsStore,
) {
    val service by HookApplication.service.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(hooks.settings) }
    var navigationSettings by remember { mutableStateOf(navigation.settings) }
    var lockscreenCapsuleSettings by remember { mutableStateOf(lockscreenCapsules.settings) }
    var cameraSettings by remember { mutableStateOf(cameras.settings) }
    var deviceProfile by remember { mutableStateOf(deviceProfiles.settings) }
    var appearance by remember { mutableStateOf(appearances.settings) }
    var airDropSettings by remember { mutableStateOf(airDrop.settings) }
    var languageRevision by remember { mutableIntStateOf(0) }
    var userPresets by remember { mutableStateOf(hooks.userShadePresets()) }
    val context = LocalContext.current
    val globalSoftGlassStore = remember(context) { SoftGlassSettingsStore(context) }
    val oobePreferences = remember { context.getSharedPreferences("oobe", Context.MODE_PRIVATE) }
    var showOobe by remember {
        // OOBE is a one-time acknowledgement. Keep recognizing the legacy
        // version-based marker so upgrades do not show OOBE again.
        mutableStateOf(
            !oobePreferences.getBoolean("completed", false) &&
                !oobePreferences.contains("completed_version"),
        )
    }
    val musicStore = remember(context) { MusicControlSettingsStore(context) }
    var musicWhitelist by remember { mutableStateOf(musicStore.apps) }
    val screenRecorderStore = remember(context) { ScreenRecorderSettingsStore(context) }
    var screenRecorder by remember { mutableStateOf(screenRecorderStore.settings) }
    val dockStore = remember(context) { DockSettingsStore(context) }
    var dockSettings by remember { mutableStateOf(dockStore.settings) }
    val homeRecentsStore = remember(context) { HomeRecentsSettingsStore(context) }
    var homeRecentsSettings by remember { mutableStateOf(homeRecentsStore.settings) }
    val homeMultiSplitStore = remember(context) { HomeMultiSplitSettingsStore(context) }
    var homeMultiSplitSettings by remember { mutableStateOf(homeMultiSplitStore.settings) }
    val homeFolderStore = remember(context) { HomeFolderSettingsStore(context) }
    var homeFolderSettings by remember { mutableStateOf(homeFolderStore.settings) }
    val homeMaterialStore = remember(context) { HomeMaterialSettingsStore(context) }
    var homeMaterialSettings by remember { mutableStateOf(homeMaterialStore.settings) }
    val settingsAppStore = remember(context) { SettingsAppPreferencesStore(context) }
    val appThemeStore = remember(context) { AppThemeSettingsStore(context) }
    val superXiaoAiStore = remember(context) { SuperXiaoAiPreferencesStore(context) }
    val systemUpdateStore = remember(context) { SystemUpdatePreferencesStore(context) }
    var settingsApp by remember { mutableStateOf(settingsAppStore.settings) }
    var superXiaoAi by remember { mutableStateOf(superXiaoAiStore.settings) }
    var systemUpdate by remember { mutableStateOf(systemUpdateStore.settings) }
    DisposableEffect(appThemeStore) {
        val dispose = appThemeStore.observe()
        onDispose { dispose() }
    }
    var pendingRestartTargets by remember { mutableStateOf(emptySet<ScopeApplication>()) }
    val markRestartTargets: (Set<ScopeApplication>) -> Unit = { targets ->
        if (targets.isNotEmpty()) pendingRestartTargets = pendingRestartTargets + targets
    }
    val restartRecommendationScope = rememberCoroutineScope()
    val restartTracker = remember(context, service) {
        val groups = listOf(REMOTE_PREFERENCE_GROUP, "settings", DEVICE_PROFILE_PREFERENCES, SETTINGS_APPEARANCE_PREFERENCES)
        val sources = groups.flatMap { group ->
            buildList {
                add(group to context.getSharedPreferences(group, Context.MODE_PRIVATE))
                service?.getRemotePreferences(group)?.let { add(group to it) }
            }
        }
        RestartRecommendationTracker(sources, ::restartRecommendationSnapshot) { targets ->
            restartRecommendationScope.launch { markRestartTargets(targets) }
        }
    }
    DisposableEffect(restartTracker) {
        restartTracker.start()
        onDispose { restartTracker.close() }
    }
    fun importAppearance(slot: String, uri: Uri?) {
        if (uri == null) return
        runCatching {
            val mime = detectAppearanceMime(context, uri)
            val target = copyAppearanceFile(context, slot, uri)
            appearances.update(service) {
                val baseSlot = slot.substringBefore(':')
                val modeKey = slot.takeIf { ':' in it }
                if (modeKey != null) {
                    it.copy(
                        colorModeAssetMimes = it.colorModeAssetMimes + (modeKey to mime),
                        colorModeAssetVersions = it.colorModeAssetVersions + (modeKey to target.lastModified()),
                    )
                } else when (baseSlot) {
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
            markRestartTargets(setOf(ScopeApplication.SETTINGS))
            Toast.makeText(context, tr("已导入", "已导入"), Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(context, tr("导入失败", "导入失败"), Toast.LENGTH_SHORT).show() }
    }
    fun clearAppearance(slot: String) {
        runCatching {
            appearanceFile(context, slot).delete()
            appearances.update(service) {
                val baseSlot = slot.substringBefore(':')
                val modeKey = slot.takeIf { ':' in it }
                if (modeKey != null) {
                    it.copy(
                        colorModeAssetMimes = it.colorModeAssetMimes + (modeKey to ""),
                        colorModeAssetVersions = it.colorModeAssetVersions + (modeKey to System.currentTimeMillis()),
                    )
                } else when (baseSlot) {
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
            markRestartTargets(setOf(ScopeApplication.SETTINGS))
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
    var pendingColorModeSlot by remember { mutableStateOf<String?>(null) }
    val pickColorModeAsset = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingColorModeSlot?.let { slot -> importAppearance(slot, uri) }
        pendingColorModeSlot = null
    }
    val onColorModeAssetAction: (String, Boolean, Boolean) -> Unit = { slot, dark, import ->
        val key = colorModeAssetKey(slot, dark)
        if (import) {
            pendingColorModeSlot = key
            pickColorModeAsset.launch(arrayOf("image/*", "image/svg+xml"))
        } else clearAppearance(key)
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
        markRestartTargets(setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.WALLPAPER))
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
                val before = hooks.settings
                hooks.update(service) { it.importShadePreset(payload) }
                settings = hooks.settings
                markRestartTargets(suggestedScopesForHookChange(before, settings))
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
        val previousNotificationSettings = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE).toNotificationIslandSettings()
        val previousWidthSettings = readIslandWidthSettings(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))
        val previousSettings = hooks.settings
        val previousLockscreenCapsule = lockscreenCapsules.settings
        val previousCameras = cameras.settings
        val previousDeviceProfile = deviceProfiles.settings
        val previousAppearance = appearances.settings
        val previousMusicWhitelist = musicStore.apps
        val previousDockSettings = dockStore.settings
        val previousHomeRecentsSettings = homeRecentsStore.settings
        val previousHomeMultiSplitSettings = homeMultiSplitStore.settings
        val previousHomeFolderSettings = homeFolderStore.settings
        val previousHomeMaterialSettings = homeMaterialStore.settings
        runCatching {
            ModulePresetCodec.import(context, service, payload)
            hooks.reload()
            lockscreenCapsules.reload()
            cameras.reload()
            deviceProfiles.reload()
            appearances.reload()
            musicStore.reload()
            dockStore.reload()
            homeRecentsStore.reload()
            homeMultiSplitStore.reload()
            homeFolderStore.reload()
            homeMaterialStore.reload()
            settings = hooks.settings
            lockscreenCapsuleSettings = lockscreenCapsules.settings
            cameraSettings = cameras.settings
            deviceProfile = deviceProfiles.settings
            appearance = appearances.settings
            musicWhitelist = musicStore.apps
            dockSettings = dockStore.settings
            homeRecentsSettings = homeRecentsStore.settings
            homeMultiSplitSettings = homeMultiSplitStore.settings
            homeFolderSettings = homeFolderStore.settings
            homeMaterialSettings = homeMaterialStore.settings
            stageHomeRecentsNativeConfig(
                context,
                homeRecentsSettings,
                homeFolderSettings,
                homeMaterialSettings,
                readHomeNativeDockSettings(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)),
                homeMultiSplitSettings,
            )
        }.onSuccess {
            markRestartTargets(suggestedScopesForHookChange(previousSettings, settings))
            val preferences = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
            if (previousNotificationSettings != preferences.toNotificationIslandSettings() || previousWidthSettings != readIslandWidthSettings(preferences)) {
                markRestartTargets(setOf(ScopeApplication.SYSTEM_UI))
            }
            if (previousLockscreenCapsule != lockscreenCapsuleSettings) {
                markRestartTargets(setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD))
            }
            if (previousCameras != cameraSettings) markRestartTargets(
                setOf(ScopeApplication.CAMERA, ScopeApplication.GALLERY, ScopeApplication.MEDIA_EDITOR),
            )
            if (previousDeviceProfile != deviceProfile || previousAppearance != appearance) {
                markRestartTargets(setOf(ScopeApplication.SETTINGS))
            }
            if (previousMusicWhitelist != musicWhitelist) {
                markRestartTargets(setOf(ScopeApplication.SUBSCREEN_CENTER))
            }
            if (previousDockSettings != dockSettings) markRestartTargets(setOf(ScopeApplication.DESKTOP))
            if (previousHomeRecentsSettings != homeRecentsSettings || previousHomeMultiSplitSettings != homeMultiSplitSettings || previousHomeFolderSettings != homeFolderSettings || previousHomeMaterialSettings != homeMaterialSettings) {
                markRestartTargets(setOf(ScopeApplication.DESKTOP))
            }
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
    val requestNotificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val appThemeSettings = appThemeStore.settings
    val appMonetPalette = appThemeStore.monetPalette.effectivePalette(appThemeSettings.colorTheme)
    val appMonetColorSpec = appThemeStore.monetColorSpec.effectiveSpec
    val appThemeSeed = appThemeSettings.seedColor(appThemeStore.previewColor)
        // Miuix ignores palette/spec when keyColor is null; explicit choices use the system accent.
        ?: if (appThemeStore.monetPalette.selection != null || appThemeStore.monetColorSpec.selection != null) {
            context.getColor(android.R.color.system_accent1_500)
        } else null
    val controller = remember(settings.themeMode, appThemeSettings, appThemeSeed, appMonetPalette, appMonetColorSpec) {
        val standardMode = when (settings.themeMode) {
            "light" -> ColorSchemeMode.Light
            "dark" -> ColorSchemeMode.Dark
            else -> ColorSchemeMode.System
        }
        val monetMode = when (settings.themeMode) {
            "light" -> ColorSchemeMode.MonetLight
            "dark" -> ColorSchemeMode.MonetDark
            else -> ColorSchemeMode.MonetSystem
        }
        ThemeController(
            colorSchemeMode = if (appThemeSettings.monetEnabled) monetMode else standardMode,
            keyColor = appThemeSeed?.let { ComposeColor(it) },
            paletteStyle = appMonetPalette.style,
            colorSpec = appMonetColorSpec.spec,
        )
    }
    LaunchedEffect(service) {
        service?.let {
            restartTracker.withoutTracking {
                hooks.syncRemote(it)
                PortalSettings.syncRemote(context, it)
                globalSoftGlassStore.syncRemote(it)
                VolumePanelGlassSettingsStore(context).syncRemote(it)
                VolumePanelLayoutSettingsStore(context).syncRemote(it)
                NotificationTextColorSettingsStore(context).syncRemote(it)
                ControlCenterRadiusSettingsStore(context).syncRemote(it)
                ControlCenterTopButtonsGlassSettingsStore(context).syncRemote(it)
                SuperXiaoAiInputGlassSettingsStore(context).syncRemote(it)
                RearScreenGestureSettingsStore(context).syncRemote(it)
                RearMiniPlayerSettingsStore(context).syncRemote(it)
                navigation.syncRemote(it)
                lockscreenCapsules.syncRemote(it)
                cameras.syncRemote(it)
                deviceProfiles.syncRemote(it)
                appearances.syncRemote(it)
                airDrop.syncRemote(it)
                IslandWidthSettingsStore(context).syncRemote(it)
                StatusBarAdjustmentSettingsStore(context).syncRemote(it)
                StatusBarIconSpacingSettingsStore(context).syncRemote(it)
                StatusBarIconImportStore(context).syncRemote(it)
                NotificationIslandSettingsStore(context).syncRemote(it)
                ExpandedIslandGlassSettingsStore(context).syncRemote(it)
                NotificationStackBlurSettingsStore(context).syncRemote(it)
                ControlCenterHeaderHeightSettingsStore(context).syncRemote(it)
                IosNotificationBottomOffsetSettingsStore(context).syncRemote(it)
                ControlCenterCarrierSettingsStore(context).syncRemote(it)
                ControlCenterClockSettingsStore(context).syncRemote(it)
                IslandSmoothingSettingsStore(context).syncRemote(it)
                IslandSpacingSettingsStore(context).syncRemote(it)
                DynamicIslandAppearanceSettingsStore(context).syncRemote(it)
                LockscreenFaceUnlockSettingsStore(context).syncRemote(it)
                musicStore.syncRemote(it)
                HomeGlassSettingsStore(context).syncRemote(it)
                dockStore.syncRemote(it)
                homeRecentsStore.syncRemote(it)
                homeMultiSplitStore.syncRemote(it)
                homeFolderStore.syncRemote(it)
                homeMaterialStore.syncRemote(it)
                settingsAppStore.syncRemote(it)
                appThemeStore.syncRemote(it)
                SettingsAdvancedColorStyleSettingsStore(context).syncRemote(it)
                superXiaoAiStore.syncRemote(it)
                systemUpdateStore.syncRemote(it)
                settings = hooks.settings
                navigationSettings = navigation.settings
                lockscreenCapsuleSettings = lockscreenCapsules.settings
                cameraSettings = cameras.settings
                deviceProfile = deviceProfiles.settings
                appearance = appearances.settings
                airDropSettings = airDrop.settings
                musicWhitelist = musicStore.apps
                dockSettings = dockStore.settings
                homeRecentsSettings = homeRecentsStore.settings
                homeMultiSplitSettings = homeMultiSplitStore.settings
                homeFolderSettings = homeFolderStore.settings
                homeMaterialSettings = homeMaterialStore.settings
                settingsApp = settingsAppStore.settings
                superXiaoAi = superXiaoAiStore.settings
                systemUpdate = systemUpdateStore.settings
            }
        }
    }
    MiuixTheme(controller = controller) {
        ApplySystemBarAppearance()
        val animatedThemeSurface by animateColorAsState(
            targetValue = MiuixTheme.colorScheme.surface,
            animationSpec = tween(420, easing = FastOutSlowInEasing),
            label = "themeSurfaceTransition",
        )
        val languagePrefs = remember { context.getSharedPreferences("languages", android.content.Context.MODE_PRIVATE) }
        val strings = remember(languageRevision) { languageStrings(context, languagePrefs) }
        SideEffect { activeLanguageStrings = strings }
        CompositionLocalProvider(
            LocalLanguageChanged provides { languageRevision++ },
            LocalNotificationSettingsChanged provides { markRestartTargets(setOf(ScopeApplication.SYSTEM_UI)) },
            LocalDesktopSettingsChanged provides { markRestartTargets(setOf(ScopeApplication.DESKTOP)) },
            LocalXiaoAiSettingsChanged provides { markRestartTargets(setOf(ScopeApplication.SUPER_XIAOAI)) },
        ) {
        AnimatedContent(
            targetState = showOobe,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(tween(520, easing = FastOutSlowInEasing)) { -it / 3 } + fadeIn(tween(380)) + scaleIn(tween(520), initialScale = .975f)) togetherWith
                        (slideOutHorizontally(tween(420, easing = FastOutSlowInEasing)) { it / 7 } + fadeOut(tween(320)) + scaleOut(tween(420), targetScale = .985f))
                } else {
                    (slideInHorizontally(tween(560, easing = FastOutSlowInEasing)) { it / 7 } + fadeIn(tween(420)) + scaleIn(tween(560), initialScale = .985f)) togetherWith
                        (slideOutHorizontally(tween(460, easing = FastOutSlowInEasing)) { -it / 3 } + fadeOut(tween(360)) + scaleOut(tween(460), targetScale = .975f))
                }
            },
            label = "oobeRootTransition",
            modifier = Modifier.fillMaxSize().background(animatedThemeSurface),
        ) { oobeVisible ->
        val rootBlurRadius by transition.animateFloat(
            transitionSpec = { tween(560, easing = FastOutSlowInEasing) },
            label = "oobeRootBlur",
        ) { state ->
            if (showOobe) {
                if (state == EnterExitState.PostExit) 14f else 0f
            } else {
                if (state == EnterExitState.PreEnter) 14f else 0f
            }
        }
        Box(Modifier.fillMaxSize().blur(rootBlurRadius.dp)) {
        if (oobeVisible) {
            OobeFlow(
                serviceConnected = service != null,
                onLanguageChanged = { languageRevision++ },
                onFinished = {
                    oobePreferences.edit().putBoolean("completed", true).apply()
                    showOobe = false
                },
            )
        } else {
        Shell(
            settings, navigationSettings, lockscreenCapsuleSettings, cameraSettings, deviceProfile, appearance, musicWhitelist, screenRecorder, airDropSettings,
            dockSettings, homeRecentsSettings, homeFolderSettings, homeMaterialSettings, service,
            appThemeStore = appThemeStore,
            update = { transform ->
                val before = hooks.settings
                hooks.update(service, transform)
                settings = hooks.settings
                markRestartTargets(suggestedScopesForHookChange(before, settings))
            },
            settingsApp = settingsApp,
            superXiaoAi = superXiaoAi,
            updateSuperXiaoAi = { transform ->
                superXiaoAiStore.update(service, transform)
                superXiaoAi = superXiaoAiStore.settings
            },
            systemUpdate = systemUpdate,
            updateSystemUpdate = { transform ->
                val before = systemUpdateStore.settings
                systemUpdateStore.update(service, transform)
                systemUpdate = systemUpdateStore.settings
                if (before != systemUpdate) markRestartTargets(setOf(ScopeApplication.SYSTEM_UPDATE))
            },
            updateSettingsApp = { transform ->
                val before = settingsAppStore.settings
                settingsAppStore.update(service, transform)
                settingsApp = settingsAppStore.settings
            superXiaoAi = superXiaoAiStore.settings
            systemUpdate = systemUpdateStore.settings
                // Everything here is applied inside the Settings process.
                if (before != settingsApp) markRestartTargets(setOf(ScopeApplication.SETTINGS))
            },
            updateLockscreenCapsule = { transform ->
                val before = lockscreenCapsules.settings
                lockscreenCapsules.update(service, transform)
                lockscreenCapsuleSettings = lockscreenCapsules.settings
                if (before != lockscreenCapsuleSettings) {
                    markRestartTargets(setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD))
                }
            },
            updateNavigation = { transform ->
                navigation.update(service, transform)
                navigationSettings = navigation.settings
            },
            updateDock = { transform ->
                val before = dockStore.settings
                dockStore.update(service, transform)
                dockSettings = dockStore.settings
                if (before != dockSettings) markRestartTargets(setOf(ScopeApplication.DESKTOP))
            },
            updateHomeRecents = { transform ->
                val before = homeRecentsStore.settings
                val staged = homeRecentsStore.update(service, transform)
                homeRecentsSettings = homeRecentsStore.settings
                if (staged && before != homeRecentsSettings) {
                    markRestartTargets(setOf(ScopeApplication.DESKTOP))
                } else if (!staged) {
                    Toast.makeText(context, tr("写入桌面配置失败，请检查 Root 权限", "写入桌面配置失败，请检查 Root 权限"), Toast.LENGTH_LONG).show()
                }
            },
            updateHomeFolder = { transform ->
                val before = homeFolderStore.settings
                val staged = homeFolderStore.update(service, transform)
                homeFolderSettings = homeFolderStore.settings
                if (staged && before != homeFolderSettings) {
                    markRestartTargets(setOf(ScopeApplication.DESKTOP))
                } else if (!staged) {
                    Toast.makeText(context, tr("写入桌面配置失败，请检查 Root 权限", "写入桌面配置失败，请检查 Root 权限"), Toast.LENGTH_LONG).show()
                }
            },
            updateHomeMaterial = { transform ->
                val before = homeMaterialStore.settings
                val staged = homeMaterialStore.update(service, transform)
                homeMaterialSettings = homeMaterialStore.settings
                if (staged && before != homeMaterialSettings) markRestartTargets(setOf(ScopeApplication.DESKTOP))
                else if (!staged) Toast.makeText(context, tr("写入桌面配置失败，请检查 Root 权限", "写入桌面配置失败，请检查 Root 权限"), Toast.LENGTH_LONG).show()
            },
            updateCamera = { transform ->
                val before = cameras.settings
                cameras.update(service, transform)
                cameraSettings = cameras.settings
                if (before != cameraSettings) markRestartTargets(
                    setOf(ScopeApplication.CAMERA, ScopeApplication.GALLERY, ScopeApplication.MEDIA_EDITOR),
                )
            },
            updateDeviceProfile = { transform ->
                val before = deviceProfiles.settings
                deviceProfiles.update(service, transform)
                deviceProfile = deviceProfiles.settings
                if (before != deviceProfile) markRestartTargets(setOf(ScopeApplication.SETTINGS))
            },
            updateAppearance = { transform ->
                val before = appearances.settings
                appearances.update(service, transform)
                appearance = appearances.settings
                if (before != appearance) markRestartTargets(setOf(ScopeApplication.SETTINGS))
            },
            updateMusicWhitelist = { next ->
                val before = musicStore.apps
                musicStore.update(service, next)
                musicWhitelist = musicStore.apps
                if (before != musicWhitelist) markRestartTargets(setOf(ScopeApplication.SUBSCREEN_CENTER))
            },
            updateScreenRecorder = { transform ->
                val before = screenRecorderStore.settings
                screenRecorderStore.update(service, transform(screenRecorderStore.settings))
                screenRecorder = screenRecorderStore.settings
                if (before != screenRecorder) markRestartTargets(
                    setOf(ScopeApplication.SCREEN_RECORDER, ScopeApplication.XIAOMI_HEALTH),
                )
            },
            updateAirDrop = { transform ->
                val before = airDrop.settings
                airDrop.update(service, transform)
                airDropSettings = airDrop.settings
                if (before != airDropSettings) markRestartTargets(setOf(ScopeApplication.AIRDROP))
            },
            suggestedRestartTargets = pendingRestartTargets,
            onRestartedTargets = { restarted -> pendingRestartTargets = pendingRestartTargets - restarted },
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
            onColorModeAssetAction = onColorModeAssetAction,
            onRequestNotificationPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onEnterOobe = { showOobe = true },
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
    navigationSettings: ModuleNavigationSettings,
    lockscreenCapsuleSettings: LockscreenCapsuleSettings,
    cameras: CameraSettings,
    deviceProfile: DeviceProfileSettings,
    appearance: SettingsAppearanceSettings,
    musicWhitelist: Set<String>,
    screenRecorder: ScreenRecorderSettings,
    airDropSettings: AirDropSettings,
    dockSettings: DockSettings,
    homeRecentsSettings: HomeRecentsSettings,
    homeFolderSettings: HomeFolderSettings,
    homeMaterialSettings: HomeMaterialSettings,
    service: XposedService?,
    appThemeStore: AppThemeSettingsStore,
    settingsApp: SettingsAppPreferences,
    superXiaoAi: SuperXiaoAiPreferences,
    systemUpdate: SystemUpdatePreferences,
    update: ((HookSettings) -> HookSettings) -> Unit,
    updateSettingsApp: ((SettingsAppPreferences) -> SettingsAppPreferences) -> Unit,
    updateSuperXiaoAi: ((SuperXiaoAiPreferences) -> SuperXiaoAiPreferences) -> Unit,
    updateSystemUpdate: ((SystemUpdatePreferences) -> SystemUpdatePreferences) -> Unit,
    updateLockscreenCapsule: ((LockscreenCapsuleSettings) -> LockscreenCapsuleSettings) -> Unit,
    updateNavigation: ((ModuleNavigationSettings) -> ModuleNavigationSettings) -> Unit,
    updateDock: ((DockSettings) -> DockSettings) -> Unit,
    updateHomeRecents: ((HomeRecentsSettings) -> HomeRecentsSettings) -> Unit,
    updateHomeFolder: ((HomeFolderSettings) -> HomeFolderSettings) -> Unit,
    updateHomeMaterial: ((HomeMaterialSettings) -> HomeMaterialSettings) -> Unit,
    updateCamera: ((CameraSettings) -> CameraSettings) -> Unit,
    updateDeviceProfile: ((DeviceProfileSettings) -> DeviceProfileSettings) -> Unit,
    updateAppearance: ((SettingsAppearanceSettings) -> SettingsAppearanceSettings) -> Unit,
    updateMusicWhitelist: (Set<String>) -> Unit,
    updateScreenRecorder: (((ScreenRecorderSettings) -> ScreenRecorderSettings) -> Unit),
    updateAirDrop: (((AirDropSettings) -> AirDropSettings) -> Unit),
    suggestedRestartTargets: Set<ScopeApplication>,
    onRestartedTargets: (Set<ScopeApplication>) -> Unit,
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
    onColorModeAssetAction: (String, Boolean, Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onEnterOobe: () -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.CATEGORY) }
    val osCode = remember { OsCompatibility.versionCode() }
    var showOsWarning by remember { mutableStateOf(false) }
    var showOsMissing by remember { mutableStateOf(false) }
    var disclaimerRead by remember { mutableStateOf(false) }
    // Module self-update prompt. Seeded from the process-level result so a rebuilt composition does
    // not lose an already resolved update.
    var pendingUpdate by remember { mutableStateOf(startupUpdatePending) }
    val captcha = remember { (100000..999999).random().toString() }
    var captchaInput by remember { mutableStateOf("") }
    var exitSeconds by remember { mutableIntStateOf(10) }
    // Proactive check for the module's own release feed. The very first request after a cold start
    // can lose the race against DNS/TLS setup, which is why the update page worked while this did
    // not; three attempts close that gap. Only a resolved answer is cached for the process.
    LaunchedEffect(Unit) {
        if (startupUpdateChecked) {
            pendingUpdate = startupUpdatePending
            return@LaunchedEffect
        }
        var resolved: UpdateInfo? = null
        for (attempt in 0 until 3) {
            if (attempt > 0) delay(3_000L)
            val result = withContext(Dispatchers.IO) {
                runCatching { fetchUpdateInfo(lastUpdateChannel) }
            }
            if (result.isSuccess) {
                resolved = result.getOrNull()
                break
            }
        }
        // Offline, timeout or a malformed feed: stay silent rather than nag on every launch.
        val info = resolved ?: return@LaunchedEffect
        startupUpdateChecked = true
        if (info.versionCode > BuildConfig.VERSION_CODE.toLong()) {
            startupUpdatePending = info
            pendingUpdate = info
        }
    }
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
        if (leavingDisclaimer && osCode.isNotBlank() && osCode != "4") {
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
    val maxBackProgress = settings.predictiveBackProgress.coerceIn(10, 100) / 100f
    val gestureBlur = 12f - 8f *
        (backState.gestureProgress / maxBackProgress).coerceIn(0f, 1f)
    val baseCoveredBlur by animateFloatAsState(
        targetValue = when {
            page == null -> 0f
            backState.isSwiping && pageStack.size == 1 -> gestureBlur
            else -> 12f
        },
        animationSpec = when {
            page == null -> tween(300, easing = FastOutSlowInEasing)
            backState.isSwiping && pageStack.size == 1 -> snap()
            else -> tween(520, easing = FastOutSlowInEasing)
        },
        label = "basePageCoveredBlur",
    )

    // Keep the page immediately below the top page composed. Predictive back moves the
    // top page away before the stack is popped, so the underlying page must already be
    // available instead of revealing the home screen during the gesture. Keeping it
    // composed also preserves its scroll state when returning from a nested page.
    val renderDetail: @Composable (PageId, Boolean) -> Unit = { detailPage, isTop ->
        CompositionLocalProvider(
            LocalPageBackSuppressed provides if (isTop) ({ value -> suppressPageBack = value }) else ({ }),
        ) {
            Detail(
                detailPage, settings, lockscreenCapsuleSettings, cameras, deviceProfile, appearance, musicWhitelist, dockSettings, homeRecentsSettings, homeFolderSettings, homeMaterialSettings, airDropSettings,
                update, updateLockscreenCapsule, updateDock, updateHomeRecents, updateHomeFolder, updateHomeMaterial, updateCamera,
                updateDeviceProfile, updateAppearance, updateMusicWhitelist, presetActions, updateAirDrop,
                settingsApp = settingsApp, updateSettingsApp = updateSettingsApp, superXiaoAi = superXiaoAi, updateSuperXiaoAi = updateSuperXiaoAi, systemUpdate = systemUpdate, updateSystemUpdate = updateSystemUpdate,
                appThemeStore = appThemeStore, service = service,
                openPage = openNestedPage, back = dismissPage,
                onEnterOobe = onEnterOobe, captcha = captcha,
                onPickRasterImages = onPickRasterImages, onApplyRasterWallpaper = onApplyRasterWallpaper,
                onPickAppearanceHome = onPickAppearanceHome, onPickAppearanceDevice = onPickAppearanceDevice,
                onClearAppearanceHome = onClearAppearanceHome, onClearAppearanceDevice = onClearAppearanceDevice,
                onPickTutorialDeviceImage = onPickTutorialDeviceImage, onClearTutorialDeviceImage = onClearTutorialDeviceImage,
                onPickStyle1UpdateBackground = onPickStyle1UpdateBackground,
                onClearStyle1UpdateBackground = onClearStyle1UpdateBackground,
                onPickStyle2DeviceImage = onPickStyle2DeviceImage, onClearStyle2DeviceImage = onClearStyle2DeviceImage,
                onPickStyle2UpdateBackground = onPickStyle2UpdateBackground,
                onClearStyle2UpdateBackground = onClearStyle2UpdateBackground,
                onPickCustomDeviceLogo = onPickCustomDeviceLogo, onClearCustomDeviceLogo = onClearCustomDeviceLogo,
                onPickStyle2DeviceLogo = onPickStyle2DeviceLogo, onClearStyle2DeviceLogo = onClearStyle2DeviceLogo,
                onPickAppearanceLogo = onPickAppearanceLogo, onClearAppearanceLogo = onClearAppearanceLogo,
                onColorModeAssetAction = onColorModeAssetAction,
                onRequestNotificationPermission = onRequestNotificationPermission,
                screenRecorder = screenRecorder,
                updateScreenRecorder = updateScreenRecorder,
                suggestedRestartTargets = suggestedRestartTargets,
                onRestartedTargets = onRestartedTargets,
        )
        }
    }

    CompositionLocalProvider(LocalDialogBackdrop provides backdrop) {
    Scaffold(containerColor = ComposeColor.Transparent) {
    Box(Modifier.fillMaxSize()) {
        // The backdrop must only record page content. Recording the navigation that consumes it
        // creates a RenderNode cycle and crashes HyperOS's RenderThread.
        Box(Modifier.fillMaxSize().blur(baseCoveredBlur.dp).layerBackdrop(backdrop)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { direction * it / 7 } + fadeIn(tween(210)) + scaleIn(tween(280), initialScale = .985f)) togetherWith
                        (slideOutHorizontally(tween(230, easing = FastOutSlowInEasing)) { -direction * it / 3 } + fadeOut(tween(180)) + scaleOut(tween(230), targetScale = .975f))
                },
                label = "mainTabNavigation",
                modifier = Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
            ) { currentTab ->
            val tabBlurRadius by transition.animateFloat(
                transitionSpec = { tween(280, easing = FastOutSlowInEasing) },
                label = "mainTabBlur",
            ) { state -> if (state == EnterExitState.Visible) 0f else 12f }
            Box(Modifier.fillMaxSize().blur(tabBlurRadius.dp)) {
                when (currentTab) {
                    Tab.CATEGORY -> CategoryHome(
                        connected = service != null,
                        open = openRootPage,
                    )
                    Tab.SETTINGS -> SettingsHome(
                        settings,
                        navigationSettings,
                        settingsApp,
                        appThemeStore,
                        service,
                        service != null,
                        update,
                        updateNavigation,
                        updateSettingsApp,
                        openRootPage,
                        onImportModulePreset,
                        onExportModulePreset,
                    )
                }
            }
        }
        }
        BottomBar(
            tab,
            { tab = it },
            settings,
            navigationSettings,
            backdrop,
            Modifier.align(Alignment.BottomCenter).blur(baseCoveredBlur.dp),
        )
        AnimatedVisibility(
            visible = page != null,
            enter = slideInHorizontally(
                animationSpec = tween(252, easing = FastOutSlowInEasing),
                initialOffsetX = { it / 7 },
            ) + fadeIn(tween(189)) + scaleIn(
                animationSpec = tween(252, easing = FastOutSlowInEasing),
                initialScale = .985f,
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(207, easing = FastOutSlowInEasing),
                targetOffsetX = { -it / 3 },
            ) + fadeOut(tween(162)) + scaleOut(
                animationSpec = tween(207, easing = FastOutSlowInEasing),
                targetScale = .975f,
            ),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Keep every page in the stack composed. The top page is an overlay,
                // while the page below remains the exact instance revealed by the
                // predictive-back gesture, preserving its scroll position.
                pageStack.forEachIndexed { index, stackPage ->
                    key(index, stackPage) {
                        val isTop = index == pageStack.lastIndex
                        val coveredPageBlur by animateFloatAsState(
                            targetValue = when {
                                isTop -> 0f
                                index == pageStack.lastIndex - 1 && backState.isSwiping -> gestureBlur
                                else -> 12f
                            },
                            animationSpec = when {
                                isTop -> tween(300, easing = FastOutSlowInEasing)
                                index == pageStack.lastIndex - 1 && backState.isSwiping -> snap()
                                else -> tween(520, easing = FastOutSlowInEasing)
                            },
                            label = "coveredPageBlur:$stackPage",
                        )
                        val pageVisibility = remember(stackPage) {
                            MutableTransitionState(false).apply { targetState = true }
                        }
                        AnimatedVisibility(
                            visibleState = pageVisibility,
                            enter = if (index > 0) {
                                slideInHorizontally(
                                    animationSpec = tween(252, easing = FastOutSlowInEasing),
                                    initialOffsetX = { it / 7 },
                                ) + fadeIn(tween(189)) + scaleIn(
                                    animationSpec = tween(252, easing = FastOutSlowInEasing),
                                    initialScale = .985f,
                                )
                            } else EnterTransition.None,
                            exit = ExitTransition.None,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .blur(coveredPageBlur.dp)
                                    .then(if (isTop) Modifier.momentumBackTransform(backState) else Modifier),
                            ) {
                                renderDetail(stackPage, isTop)
                            }
                        }
                    }
                }
            }
        }
        OsWarningDialog(
            show = page != PageId.DISCLAIMER && showOsWarning,
            version = osCode,
            captcha = captcha,
            captchaInput = captchaInput,
            disclaimerRead = disclaimerRead,
            preview = false,
            onCaptchaInput = { captchaInput = it.filter(Char::isDigit).take(6) },
            onDisclaimer = { showOsWarning = false; openNestedPage(PageId.DISCLAIMER) },
            onConfirm = { if (disclaimerRead && captchaInput == captcha) showOsWarning = false },
            onExit = { (context as? Activity)?.finishAndRemoveTask() },
        )
        OsMissingDialog(
            show = showOsMissing,
            seconds = exitSeconds,
            preview = false,
            onExit = { (context as? Activity)?.finishAndRemoveTask() },
        )
        UpdateAvailableDialog(
            // Never stack on top of the compatibility gates.
            show = pendingUpdate != null && !showOsWarning && !showOsMissing,
            currentVersion = BuildConfig.VERSION_NAME,
            latestVersion = pendingUpdate?.versionName.orEmpty(),
            onDismiss = {
                pendingUpdate = null
                startupUpdatePending = null
            },
            onViewUpdate = {
                pendingUpdate = null
                startupUpdatePending = null
                openRootPage(PageId.SOFTWARE_UPDATE)
            },
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
    navigationSettings: ModuleNavigationSettings,
    backdrop: Backdrop,
    modifier: Modifier
) {
    val view = LocalView.current
    val monetAccentColor by animateColorAsState(
        targetValue = MiuixTheme.colorScheme.primary,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "bottomBarMonetAccent",
    )
    val index = remember { mutableIntStateOf(tab.ordinal) }
    LaunchedEffect(tab) { index.intValue = tab.ordinal }
    val tabs = Tab.entries.mapIndexed { i, item ->
        val title = if (item == Tab.CATEGORY) {
            tr("tab_home", tr("首页", "首页"))
        } else {
            tr("tab_settings", tr("设置", "设置"))
        }
        HostTab(title, "os4.$i") { index.intValue = i; select(item) }
    }
    Box(
        modifier.fillMaxWidth().height(if (navigationSettings.style == "hyper_os") 76.dp else 100.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        CustomNavigation(
            sourceView = view,
            tabs = tabs,
            selectedIndex = index,
            blurRadius = if (navigationSettings.style == "liquid_glass") 3 else 18,
            labelMode = navigationSettings.labelMode,
            navigationStyle = navigationSettings.style,
            advancedMaterial = true,
            colorMode = settings.themeMode,
            accentColorOverride = monetAccentColor,
            liquidBottomSpacingDp = 0,
            onHostPreDraw = {},
            backdropOverride = backdrop,
            tabImageVector = { i -> if (i == Tab.CATEGORY.ordinal) MiuixIcons.Regular.Home else MiuixIcons.Regular.Settings }
        )
    }
}

@Composable
private fun CategoryHome(
    connected: Boolean,
    open: (PageId) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val searchItems = categorySearchItems()
    val normalizedQuery = query.trim().lowercase(Locale.getDefault())
    val filteredItems = if (normalizedQuery.isBlank()) {
        emptyList()
    } else {
        searchItems.filter { item ->
            buildString {
                append(item.title)
                append('\n')
                append(item.description)
                item.scopes.forEach { scope ->
                    append('\n')
                    append(tr(scope.title, scope.title))
                    append('\n')
                    append(scope.packageName)
                }
            }.lowercase(Locale.getDefault()).contains(normalizedQuery)
        }
    }
    val groupedItems = filteredItems
        .flatMap { item -> item.scopes.map { scope -> scope to item } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, items) -> items.distinctBy { it.title to it.page } }

    AppPage(
        "HyperChanger",
        actions = {
            GlassToolbarIconButton(
                icon = MiuixIcons.Regular.Refresh,
                description = tr("重启作用域应用", "重启作用域应用"),
                onClick = { open(PageId.RESTART_SCOPES) },
            )
        },
    ) { padding, scroll ->
        AppList(padding, scroll) {
            item {
                CategorySearchBar(
                    query = query,
                    expanded = expanded,
                    onQueryChange = { query = it },
                    onExpandedChange = { expanded = it },
                    onCancel = { query = ""; expanded = false },
                    collapsedFraction = scroll.state.collapsedFraction.coerceIn(0f, 1f),
                )
            }
            if (normalizedQuery.isBlank()) {
                item {
                    CategoryGroup(
                        title = tr("系统界面", "系统界面"),
                        entries = listOf(
                            CategoryPageEntry(tr("系统界面与材质调整", "系统界面与材质调整"), PageId.SHADE),
                            CategoryPageEntry(tr("桌面", "桌面"), PageId.DESKTOP),
                            CategoryPageEntry(tr("通知与超级岛", "通知与超级岛"), PageId.ISLAND),
                            CategoryPageEntry(tr("状态栏与控制中心", "状态栏与控制中心"), PageId.STATUS),
                            CategoryPageEntry(tr("锁屏", "锁屏"), PageId.LOCK),
                        ),
                        enabled = connected,
                        open = open,
                    )
                }
                item {
                    CategoryGroup(
                        title = tr("系统功能", "系统功能"),
                        entries = listOf(
                            CategoryPageEntry(tr("系统框架", "系统框架"), PageId.SYSTEM_FRAMEWORK),
                            CategoryPageEntry(tr("超级小爱与输入法", "超级小爱与输入法"), PageId.SUPER_XIAOAI),
                            CategoryPageEntry(tr("相机与相册编辑", "相机与相册编辑"), PageId.CAMERA),
                            CategoryPageEntry(tr("系统更新", "系统更新"), PageId.SYSTEM_UPDATE),
                            CategoryPageEntry(tr("系统设置", "系统设置"), PageId.SYSTEM_SETTINGS),
                            CategoryPageEntry(tr("背屏", "背屏"), PageId.REAR_SCREEN),
                        ),
                        enabled = connected,
                        open = open,
                    )
                }
                item {
                    CategoryGroup(
                        title = tr("其他", "其他"),
                        entries = listOf(
                            CategoryPageEntry(tr("拨号与通话", "拨号与通话"), PageId.CONTACTS_DIALER),
                            CategoryPageEntry(tr("其他", "其他"), PageId.OTHER),
                        ),
                        enabled = connected,
                        open = open,
                    )
                }
            } else if (groupedItems.isEmpty()) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        cornerRadius = 22.5.dp,
                        insideMargin = PaddingValues(18.dp),
                    ) {
                        Text(
                            tr("category_search_no_results", "没有找到匹配的设置项"),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                groupedItems.forEach { (scope, items) ->
                    item {
                        SmallTitle(
                            tr(scope.title, scope.title),
                            insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
                        )
                        Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                            items.forEach { result ->
                                ArrowPreference(
                                    title = result.title,
                                    summary = result.description,
                                    enabled = connected,
                                    onClick = { open(result.page) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategorySearchBar(
    query: String,
    expanded: Boolean,
    onQueryChange: (String) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
    collapsedFraction: Float,
) {
    val showCancel = expanded || query.isNotBlank()
    val cancelAlpha by animateFloatAsState(
        targetValue = if (showCancel) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "categorySearchCancelAlpha",
    )
    val cancelBlur by animateFloatAsState(
        targetValue = if (showCancel) 0f else 8f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "categorySearchCancelBlur",
    )
    SearchBar(
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer {
                val scale = 1f - .1f * collapsedFraction
                scaleX = scale
                scaleY = scale
            }
            .blur((4f * collapsedFraction).dp),
        insideMargin = DpSize(0.dp, 0.dp),
        inputField = {
            InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { onExpandedChange(false) },
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                label = tr("category_search_placeholder", "搜索设置项名称、描述或作用域"),
            )
        },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        outsideEndAction = {
            AnimatedVisibility(
                visible = showCancel,
                enter = fadeIn(tween(220, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(220, easing = FastOutSlowInEasing)),
            ) {
                Text(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .graphicsLayer { alpha = cancelAlpha }
                        .blur(cancelBlur.dp)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onCancel,
                        ),
                    text = tr("取消", "取消"),
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        },
    ) {}
}

@Composable
private fun CategoryGroup(
    title: String,
    entries: List<CategoryPageEntry>,
    enabled: Boolean,
    open: (PageId) -> Unit,
) {
    SmallTitle(
        title,
        insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
    )
    Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
        entries.forEach { entry ->
            ArrowPreference(
                title = entry.title,
                enabled = enabled,
                onClick = { open(entry.page) },
            )
        }
    }
}

@Composable
private fun OtherPage(
    airDropSettings: AirDropSettings,
    updateAirDrop: (((AirDropSettings) -> AirDropSettings) -> Unit),
    screenRecorder: ScreenRecorderSettings,
    update: (((ScreenRecorderSettings) -> ScreenRecorderSettings) -> Unit),
    open: (PageId) -> Unit,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val scopedPrefs = remember(service) {
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
            ?: context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, 0)
    }
    var removeWatchFaceTrialLimit by remember(service) {
        mutableStateOf(
            ScopedSettings.getBoolean(
                scopedPrefs,
                "com.mi.health",
                ScopedSettings.KEY_REMOVE_WATCH_FACE_TRIAL_LIMIT,
                false,
            ),
        )
    }
    AppPage(
    tr("其他", "其他"),
    back,
    restartScopes = setOf(ScopeApplication.SCREEN_RECORDER, ScopeApplication.XIAOMI_HEALTH, ScopeApplication.AIRDROP),
) { padding, scroll ->
    var showSavePathDialog by remember { mutableStateOf(false) }
    var pathDraft by remember(screenRecorder.savePath) { mutableStateOf(screenRecorder.savePath) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        documentTreePath(uri)?.let { pathDraft = it }
    }
    AppList(padding, scroll, 28) {
        item {
            SmallTitle(tr("屏幕录制", "屏幕录制"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                SwitchPreference(
                    title = tr("添加更多帧率选项", "添加更多帧率选项"),
                    checked = screenRecorder.moreFrameRates,
                    onCheckedChange = { value -> update { it.copy(moreFrameRates = value) } },
                )
                SwitchPreference(
                    title = tr("添加更多码率选项", "添加更多码率选项"),
                    checked = screenRecorder.moreBitRates,
                    onCheckedChange = { value -> update { it.copy(moreBitRates = value) } },
                )
                ArrowPreference(
                    title = tr("自定义保存位置", "自定义保存位置"),
                    summary = screenRecorder.savePath.ifBlank { tr("使用系统默认位置", "使用系统默认位置") },
                    onClick = { pathDraft = screenRecorder.savePath; showSavePathDialog = true },
                )
            }
        }
        item {
            SmallTitle(
                tr("系统应用优化", "系统应用优化"),
                insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
            )
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                ArrowPreference(
                    title = tr("环境检测", "环境检测"),
                    summary = tr("实验性功能", "实验性功能"),
                    onClick = { open(PageId.ENVIRONMENT_DETECTION) },
                )
                ArrowPreference(
                    title = tr("应用底部导航", "应用底部导航"),
                    onClick = { open(PageId.APP_NAVIGATION) },
                )
                SwitchPreference(
                    title = tr("去除表盘试用10分钟限制", "去除表盘试用10分钟限制"),
                    checked = removeWatchFaceTrialLimit,
                    onCheckedChange = { enabled ->
                        removeWatchFaceTrialLimit = enabled
                        scopedPrefs.edit().putBoolean(
                            ScopedSettings.key(
                                "com.mi.health",
                                ScopedSettings.KEY_REMOVE_WATCH_FACE_TRIAL_LIMIT,
                            ),
                            enabled,
                        ).apply()
                    },
                )
                SwitchPreference(
                    title = tr("强制启用隔空投送", "强制启用隔空投送"),
                    checked = airDropSettings.forceEnable,
                    onCheckedChange = { enabled -> updateAirDrop { it.copy(forceEnable = enabled) } },
                )
            }
        }
    }
    WindowDialog(show = showSavePathDialog, onDismissRequest = { showSavePathDialog = false }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("自定义保存位置", "自定义保存位置"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Text(
                tr("录制的视频将保存到指定文件夹。需要重启屏幕录制应用后生效。", "录制的视频将保存到指定文件夹。需要重启屏幕录制应用后生效。"),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            TextField(
                value = pathDraft,
                onValueChange = { pathDraft = it.take(512) },
                label = tr("文件路径", "文件路径"),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            GlassDialogButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("选择文件夹", "选择文件夹"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onClick = { showSavePathDialog = false }, modifier = Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = { update { it.copy(savePath = pathDraft.trim()) }; showSavePathDialog = false },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("保存", "保存")) }
            }
        }
    }
}
}

private fun documentTreePath(uri: Uri): String? = runCatching {
    val documentId = android.provider.DocumentsContract.getTreeDocumentId(uri) ?: return@runCatching null
    val split = documentId.split(':', limit = 2)
    if (split.size == 1) null else if (split[0].equals("primary", true)) {
        File(Environment.getExternalStorageDirectory(), split[1]).absolutePath
    } else null
}.getOrNull()

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
    val configuration = LocalConfiguration.current
    val locales = remember(context, configuration) { applicationLabelLocales(context) }
    val apps by produceState<List<RearApp>>(initialValue = emptyList(), context, locales.toLanguageTags()) {
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
                        label = localizedApplicationLabel(context, info, locales),
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

private fun importCustomRearScreenApp(context: Context, uri: Uri): Result<String> = runCatching {
    val token = UUID.randomUUID().toString()
    val staged = File(context.cacheDir, "rear-screen-$token.mtz")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(staged).use { output -> input.copyTo(output) }
    } ?: error("无法读取所选文件")
    val productId = token
    val escaped = staged.absolutePath.replace("'", "'\\''")
    // ThemeManager's actual AI-app storage path (documented in bbb.txt) is shared
    // external storage, not the theme_magic runtime directory.
    val aiAppRoot = "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/.ai_app"
    val command = "destination=\"$aiAppRoot/${productId}_extracted\"; runtime=/data/system/theme_magic/users/0/rearScreenAiApp_Theme/$productId; " +
        "mkdir -p \"\$destination/app\" \"\$destination/preview\"; " +
        "if unzip -l '$escaped' 2>/dev/null | grep -q 'description.xml'; then unzip -o '$escaped' -d \"\$destination\" >/dev/null; " +
        "elif unzip -l '$escaped' 2>/dev/null | grep -q 'manifest.xml'; then cp '$escaped' \"\$destination/rearscreen\"; " +
        "else cp '$escaped' \"\$destination/rearscreen\"; fi; " +
        "found=\$(find \"\$destination\" -type f -name rearscreen -print -quit); " +
        "if [ -n \"\$found\" ] && [ \"\$found\" != \"\$destination/rearscreen\" ]; then cp -a \"\$(dirname \"\$found\")\"/. \"\$destination\"/; fi; " +
        "mkdir -p \"\$runtime\"; cp \"\$destination/rearscreen\" \"\$runtime/rearScreen.mrc\"; " +
        "[ -f \"\$destination/app/app_icon.png\" ] && cp \"\$destination/app/app_icon.png\" \"\$runtime/app_icon.png\"; " +
        "preview=\$(find \"\$destination/preview\" -type f -print -quit); [ -n \"\$preview\" ] && cp \"\$preview\" \"\$runtime/preview.png\"; " +
        "find \"\$destination\" \"\$runtime\" -type d -exec chmod 755 {} \\; ; find \"\$destination\" \"\$runtime\" -type f -exec chmod 644 {} \\; ; " +
        "am broadcast -a com.android.thememanager.action.REAR_SCREEN_AI_APP_CHANGED >/dev/null 2>&1 || true"
    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
        process.destroyForcibly()
        error("需要 root 权限或系统目录不可写")
    }
    staged.delete()
    productId
}.onFailure { stagedError -> Log.w("HyperChanger", "Custom rear-screen import failed", stagedError) }

@Composable
private fun RearScreen(
    settings: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    selectedPackages: Set<String>,
    onSelectedPackagesChange: (Set<String>) -> Unit,
    open: (PageId) -> Unit,
    back: () -> Unit,
) = AppPage(tr("\u80cc\u5c4f", "\u80cc\u5c4f"), back, restartScopes = setOf(
    ScopeApplication.SUBSCREEN_CENTER,
    ScopeApplication.THEME_MANAGER,
    ScopeApplication.PERSONAL_ASSISTANT,
)) { padding, scroll ->
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val rearScreenThemeApplyStore = remember(context) { RearScreenThemeApplySettingsStore(context) }
    val miniPlayerStore = remember(context) { RearMiniPlayerSettingsStore(context) }
    var allowAllActivities by remember { mutableStateOf(miniPlayerStore.allowAllActivities) }
    val (gestureSettings, updateGestureSettings) = rememberRearScreenGestureSettings()
    var showGestureAppActions by remember { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val labelLocales = remember(context, configuration) { applicationLabelLocales(context) }
    val gestureAppLabel = remember(context, gestureSettings.packageName, labelLocales.toLanguageTags()) {
        runCatching {
            localizedApplicationLabel(context,
                context.packageManager.getApplicationInfo(gestureSettings.packageName, 0), labelLocales)
        }.getOrDefault(gestureSettings.packageName)
    }
    WindowDialog(show = showGestureAppActions && gestureSettings.enabled, onDismissRequest = { showGestureAppActions = false }) {
        Text(gestureAppLabel, style = MiuixTheme.textStyles.title3)
        Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassDialogButton(onClick = {
                showGestureAppActions = false
                open(PageId.REAR_SWIPE_APP)
            }, modifier = Modifier.fillMaxWidth()) { Text(tr("编辑", "编辑")) }
            GlassDialogButton(onClick = {
                updateGestureSettings { it.copy(packageName = "") }
                showGestureAppActions = false
            }, modifier = Modifier.fillMaxWidth()) { Text(tr("清除", "清除")) }
            GlassDialogButton(onClick = { showGestureAppActions = false }, modifier = Modifier.fillMaxWidth()) { Text(tr("取消", "取消")) }
        }
    }
    var rearScreenThemeApplyFix by remember { mutableStateOf(rearScreenThemeApplyStore.enabled) }
    val wallpaperLimitStore = remember(context) { RearScreenWallpaperLimitSettingsStore(context) }
    var wallpaperLimit by remember { mutableStateOf(wallpaperLimitStore.settings) }
    var showWallpaperLimitDialog by remember { mutableStateOf(false) }
    fun updateWallpaperLimit(transform: (RearScreenWallpaperLimitSettings) -> RearScreenWallpaperLimitSettings) {
        wallpaperLimitStore.update(service, transform)
        wallpaperLimit = wallpaperLimitStore.settings
    }
    LaunchedEffect(service) {
        service?.let {
            rearScreenThemeApplyStore.syncRemote(it)
            miniPlayerStore.syncRemote(it)
            allowAllActivities = miniPlayerStore.allowAllActivities
            rearScreenThemeApplyFix = rearScreenThemeApplyStore.enabled
            wallpaperLimitStore.syncRemote(it)
            wallpaperLimit = wallpaperLimitStore.settings
        }
    }
    RearScreenWallpaperLimitDialog(
        show = showWallpaperLimitDialog && wallpaperLimit.enabled,
        current = wallpaperLimit.custom,
        onDismiss = { showWallpaperLimitDialog = false },
        onSave = { value ->
            updateWallpaperLimit { it.copy(custom = value) }
            showWallpaperLimitDialog = false
        },
    )
    val apps = rememberRearApps()
    AppList(padding, scroll, 28) {
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                ArrowPreference(
                    title = tr("rear_mini_player_title", "在背屏启动小折叠版迷你音乐播放器"),
                    summary = tr("rear_mini_player_entry", "将网易云音乐原生迷你播放器投放到背屏。"),
                    onClick = { open(PageId.REAR_MINI_PLAYER) },
                )
                ArrowPreference(
                    title = tr("rear_screenshot_shell_title", "自定义带壳截图外壳样式"),
                    summary = tr("rear_screenshot_shell_entry", "在截屏应用中自定义背屏带壳截图的聚焦和完整样式。"),
                    onClick = { open(PageId.REAR_SCREEN_SHELL) },
                )
                SwitchPreference(
                    title = tr("rear_allow_all_activities", "强制允许所有 Activity 可以在背屏上被打开"),
                    summary = tr("rear_allow_all_activities_hint", "需要勾选系统框架（system）作用域并手动重启设备。放宽背屏 Activity 白名单和显示屏 1 的启动限制；不保证所有应用布局适配。"),
                    checked = allowAllActivities,
                    onCheckedChange = {
                        miniPlayerStore.updateAllowAllActivities(service, it)
                        allowAllActivities = it
                    },
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                SwitchPreference(
                    title = tr("开启左滑手势", "开启左滑手势"),
                    summary = tr("从背屏右侧向左滑动，打开所选应用。", "从背屏右侧向左滑动，打开所选应用。"),
                    checked = gestureSettings.enabled,
                    onCheckedChange = { enabled ->
                        updateGestureSettings { it.copy(enabled = enabled) }
                        if (!enabled) showGestureAppActions = false
                    },
                )
                AnimatedVisibility(
                    visible = gestureSettings.enabled,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    ArrowPreference(
                        title = tr("左滑启动应用", "左滑启动应用"),
                        summary = gestureAppLabel.ifBlank { tr("未选择应用", "未选择应用") },
                        onClick = {
                            if (gestureSettings.packageName.isBlank()) open(PageId.REAR_SWIPE_APP)
                            else showGestureAppActions = true
                        },
                    )
                }
            }
        }
        item {
            Group(tr("解锁 AI 功能", "解锁 AI 功能")) {
                SwitchPreference(
                    title = tr("解锁 Xiaomi 18 Pro 系列 AI 背屏功能", "解锁 Xiaomi 18 Pro 系列 AI 背屏功能"),
                    summary = tr("需要同时勾选并重启“背屏”、“主题壁纸”和“智能助理”三个作用域。", "需要同时勾选并重启“背屏”、“主题壁纸”和“智能助理”三个作用域。"),
                    checked = settings.unlockXiaomi18RearScreenAi,
                    onCheckedChange = { enabled -> update { it.copy(unlockXiaomi18RearScreenAi = enabled) } },
                )
                SwitchPreference(
                    title = tr("去除自定义背屏限制和校验", "去除自定义背屏限制和校验"),
                    summary = tr("放宽 ThemeManager 与 SubScreenCenter 对自定义 AI 背屏应用的限制。", "放宽 ThemeManager 与 SubScreenCenter 对自定义 AI 背屏应用的限制。"),
                    checked = settings.removeCustomRearScreenRestrictions,
                    onCheckedChange = { enabled -> update { it.copy(removeCustomRearScreenRestrictions = enabled) } },
                )
                ArrowPreference(
                    title = tr("自定义背屏中心", "自定义背屏中心"),
                    summary = tr("查看和管理已导入的自定义背屏应用", "查看和管理已导入的自定义背屏应用"),
                    onClick = { open(PageId.REAR_CUSTOM_CENTER) },
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                SwitchPreference(
                    title = tr("主题破解后应用失败", "主题破解后应用失败"),
                    summary = tr(
                        "修复主题破解与背屏主题、壁纸授权流程的冲突，保留普通主题破解。需勾选主题壁纸作用域，重启主题壁纸后生效。",
                        "修复主题破解与背屏主题、壁纸授权流程的冲突，保留普通主题破解。需勾选主题壁纸作用域，重启主题壁纸后生效。",
                    ),
                    checked = rearScreenThemeApplyFix,
                    onCheckedChange = { enabled ->
                        rearScreenThemeApplyStore.update(service, enabled)
                        rearScreenThemeApplyFix = enabled
                    },
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                SwitchPreference(
                    title = tr("自定义背屏壁纸上限", "自定义背屏壁纸上限"),
                    summary = tr(
                        "系统默认最多 15 张，NFC 卡片不计入。需勾选主题壁纸作用域，重启主题壁纸后生效。",
                        "系统默认最多 15 张，NFC 卡片不计入。需勾选主题壁纸作用域，重启主题壁纸后生效。",
                    ),
                    checked = wallpaperLimit.enabled,
                    onCheckedChange = { value ->
                        updateWallpaperLimit { it.copy(enabled = value) }
                        if (!value) showWallpaperLimitDialog = false
                    },
                )
                AnimatedVisibility(
                    visible = wallpaperLimit.enabled,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    Column {
                        OverlayDropdownPreference(
                            title = tr("背屏壁纸数量上限", "背屏壁纸数量上限"),
                            items = listOf(
                                tr("50", "50"), tr("60", "60"), tr("80", "80"), tr("100", "100"),
                                tr("自定义", "自定义"),
                            ),
                            selectedIndex = wallpaperLimit.mode,
                            onSelectedIndexChange = { value -> updateWallpaperLimit { it.copy(mode = value) } },
                        )
                        AnimatedVisibility(
                            visible = wallpaperLimit.mode == REAR_WALLPAPER_LIMIT_CUSTOM_MODE,
                            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                        ) {
                            ArrowPreference(
                                title = tr("自定义背屏壁纸数量", "自定义背屏壁纸数量"),
                                summary = tr("%d 张", "%d 张").format(wallpaperLimit.custom),
                                onClick = { showWallpaperLimitDialog = true },
                            )
                        }
                    }
                }
            }
        }
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
private fun CustomRearScreenCenter(back: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<CustomRearScreenApp>>(emptyList()) }
    var selected by remember { mutableStateOf<CustomRearScreenApp?>(null) }
    var confirmDelete by remember { mutableStateOf<CustomRearScreenApp?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var importPreview by remember { mutableStateOf<CustomRearScreenImportPreview?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { readCustomRearScreenImportPreview(context, uri) }
            .onSuccess { importPreview = it; importError = null }
            .onFailure { importError = it.message ?: tr("无法读取压缩文件", "无法读取压缩文件") }
    }
    LaunchedEffect(refreshKey) {
        apps = withContext(Dispatchers.IO) { scanCustomRearScreenApps(context) }
    }
    fun closeSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }
    fun toggleSelection(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }
    BackHandler(enabled = selectionMode) { closeSelection() }
    AppPage(
        title = tr("自定义背屏中心", "自定义背屏中心"),
        onBack = back,
        actions = {
            AnimatedVisibility(visible = !selectionMode) {
                GlassToolbarIconButton(icon = MiuixIcons.Regular.Refresh, description = tr("刷新", "刷新"), onClick = { refreshKey++ })
            }
        },
        floatingToolbarPosition = ToolbarPosition.BottomCenter,
        floatingToolbar = {
            if (!selectionMode) {
                Box(Modifier.fillMaxWidth().padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                    GlassActionButton(
                        onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
                        size = 60.dp,
                        surfaceColor = ComposeColor(0xFF0088FF),
                    ) {
                        Icon(MiuixIcons.Regular.Add, tr("导入", "导入"), Modifier.size(24.dp), tint = ComposeColor.White)
                    }
                }
            } else AnimatedVisibility(visible = true, enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = .86f), exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = .9f)) {
                GlassLyricFloatingToolbar {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { selectedIds = if (selectedIds.size == apps.size) emptySet() else apps.mapTo(linkedSetOf(), CustomRearScreenApp::productId) }) {
                            Icon(MiuixIcons.Regular.SelectAll, tr("全选", "全选"), Modifier.size(26.4.dp))
                        }
                        IconButton(enabled = selectedIds.isNotEmpty(), onClick = { confirmDelete = apps.firstOrNull { it.productId in selectedIds } }) {
                            Icon(MiuixIcons.Regular.Delete, tr("删除", "删除"), Modifier.size(26.4.dp), tint = ComposeColor(0xFFD32F2F))
                        }
                        IconButton(onClick = ::closeSelection) {
                            Icon(MiuixIcons.Regular.Close, tr("关闭", "关闭"), Modifier.size(26.4.dp))
                        }
                    }
                }
            }
        },
        content = { padding, scroll ->
            AppList(padding, scroll, if (selectionMode) 112 else 28) {
                if (apps.isEmpty()) {
                    item {
                        Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(18.dp)) {
                            Text(
                                tr("暂无自定义背屏应用", "暂无自定义背屏应用"),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    items(apps, key = { it.productId }) { app ->
                        Card(
                            Modifier.fillMaxWidth(),
                            cornerRadius = 22.5.dp,
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            onClick = { if (selectionMode) toggleSelection(app.productId) else selected = app },
                            onLongPress = { selectionMode = true; selectedIds = selectedIds + app.productId },
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val bitmap = remember(app.iconPath) {
                                    BitmapFactory.decodeFile(app.iconPath)?.asImageBitmap()
                                }
                                if (bitmap != null) {
                                    Image(bitmap, tr("应用图标", "应用图标"), Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                                } else {
                                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(MiuixTheme.colorScheme.secondaryVariant))
                                }
                                Spacer(Modifier.width(16.dp))
                                Text(app.name, style = MiuixTheme.textStyles.body1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                AnimatedVisibility(visible = selectionMode, enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 2 }, exit = fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { it / 2 }) {
                                    Checkbox(state = if (app.productId in selectedIds) ToggleableState.On else ToggleableState.Off, onClick = { toggleSelection(app.productId) }, modifier = Modifier.padding(start = 12.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
    )
    selected?.let { app ->
        var name by remember(app.productId) { mutableStateOf(app.name) }
        WindowDialog(show = true, onDismissRequest = { selected = null }) {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = tr("应用名", "应用名"),
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            GlassDialogButton(
                onClick = { confirmDelete = app; selected = null },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("删除", "删除"), color = ComposeColor.Red) }
            Spacer(Modifier.height(8.dp))
            GlassDialogButton(onClick = { selected = null }, modifier = Modifier.fillMaxWidth()) { Text(tr("取消", "取消")) }
        }
    }
    confirmDelete?.let { app ->
        WindowDialog(show = true, onDismissRequest = { confirmDelete = null }) {
            Text(tr("确认删除此自定义背屏应用？相关文件将被永久删除。", "确认删除此自定义背屏应用？相关文件将被永久删除。"), style = MiuixTheme.textStyles.body1)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                GlassDialogButton(onClick = { confirmDelete = null }, modifier = Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = {
                        val targets = if (selectionMode) apps.filter { it.productId in selectedIds } else listOf(app)
                        targets.forEach(::deleteCustomRearScreenApp)
                        confirmDelete = null
                        closeSelection()
                        refreshKey++
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(tr("删除", "删除"), color = ComposeColor.Red) }
            }
        }
    }
    importError?.let { message ->
        WindowDialog(show = true, onDismissRequest = { importError = null }) {
            Text(message, style = MiuixTheme.textStyles.body1)
            Spacer(Modifier.height(12.dp))
            GlassDialogButton(onClick = { importError = null }, modifier = Modifier.fillMaxWidth()) { Text(tr("取消", "取消")) }
        }
    }
    importPreview?.let { preview ->
        WindowDialog(show = true, onDismissRequest = { importPreview = null }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                preview.preview?.let { image ->
                    Image(image, tr("背屏预览", "背屏预览"), Modifier.fillMaxWidth().aspectRatio(904f / 572f).clip(RoundedCornerShape(22.5.dp)), contentScale = ContentScale.Crop)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    preview.icon?.let { image -> Image(image, tr("应用图标", "应用图标"), Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) }
                    Spacer(Modifier.width(14.dp))
                    Text(preview.name, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text(preview.description.ifBlank { tr("暂无描述", "暂无描述") }, style = MiuixTheme.textStyles.body2)
                }
                GlassDialogButton(
                    onClick = {
                        runCatching { importCustomRearScreenApp(context, preview.uri).getOrThrow() }
                            .onSuccess { importPreview = null; refreshKey++ }
                            .onFailure { importError = it.message ?: tr("导入失败", "导入失败"); importPreview = null }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("导入", "导入")) }
            }
        }
    }
}

private fun readCustomRearScreenImportPreview(context: Context, uri: Uri): CustomRearScreenImportPreview {
    var previewBytes: ByteArray? = null
    var iconBytes: ByteArray? = null
    var descriptionXml = ""
    context.contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                when {
                    name.substringAfterLast('/').startsWith("preview_rearscreen", true) && name.endsWith(".png", true) && previewBytes == null -> previewBytes = zip.readBytes()
                    name.endsWith("/app/app_icon.png", true) || name == "app/app_icon.png" -> iconBytes = zip.readBytes()
                    name.substringAfterLast('/').equals("description.xml", true) -> descriptionXml = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
    } ?: error("无法读取压缩文件")
    val name = Regex("<(?:appName|AppName)>\\s*(.*?)\\s*</(?:appName|AppName)>", RegexOption.DOT_MATCHES_ALL)
        .find(descriptionXml)?.groupValues?.getOrNull(1)?.trim()
        ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")
        .orEmpty().ifBlank { tr("未命名背屏应用", "未命名背屏应用") }
    val description = Regex("<funcDesc>\\s*(.*?)\\s*</funcDesc>", RegexOption.DOT_MATCHES_ALL)
        .find(descriptionXml)?.groupValues?.getOrNull(1)?.trim()
        ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")
        .orEmpty()
    return CustomRearScreenImportPreview(
        uri = uri,
        preview = previewBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() },
        icon = iconBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() },
        name = name,
        description = description,
    )
}

private fun scanCustomRearScreenApps(context: Context): List<CustomRearScreenApp> {
    val rootPath = "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/.ai_app"
    val runtimeRoot = "/data/system/theme_magic/users/0/rearScreenAiApp_Theme"
    val folders = runRootCommand("find '$rootPath' -maxdepth 1 -type d -name '*_extracted' -print")
        .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    return folders.mapNotNull { folderPath ->
        val folder = File(folderPath)
        val id = folder.name.removeSuffix("_extracted")
        val xml = runRootCommand("cat ${shellQuote("$folderPath/description.xml")}")
        val name = Regex("<appName>\\s*(.*?)\\s*</appName>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.getOrNull(1)?.trim()
            ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")
            ?.takeUnless { it.isNullOrBlank() } ?: id
        val sourceIcon = if (runRootCommand("test -f ${shellQuote("$runtimeRoot/$id/app_icon.png")} && echo yes").trim() == "yes") {
            "$runtimeRoot/$id/app_icon.png"
        } else "$folderPath/app/app_icon.png"
        val cachedIcon = File(context.cacheDir, "custom-rear-icons/$id.png")
        cachedIcon.parentFile?.mkdirs()
        runRootCommand("test -f ${shellQuote(sourceIcon)} && cp ${shellQuote(sourceIcon)} ${shellQuote(cachedIcon.absolutePath)} && chmod 644 ${shellQuote(cachedIcon.absolutePath)}")
        val icon = cachedIcon.takeIf { it.isFile }?.absolutePath ?: sourceIcon
        CustomRearScreenApp(id, name, icon, folderPath, "$runtimeRoot/$id")
    }.sortedBy { it.name.lowercase(Locale.getDefault()) }
}

private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

private fun runRootCommand(command: String): String = runCatching {
    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    process.waitFor(15, TimeUnit.SECONDS)
    process.destroy()
    output
}.getOrDefault("")

private fun deleteCustomRearScreenApp(app: CustomRearScreenApp) {
    val command = "rm -rf '${app.extractedPath.replace("'", "'\\''")}' '${app.runtimePath.replace("'", "'\\''")}'"
    runCatching {
        ProcessBuilder("su", "-c", command).redirectErrorStream(true).start().apply {
            inputStream.bufferedReader().readText()
            waitFor(15, TimeUnit.SECONDS)
        }
    }.onFailure { Log.w("HyperChanger", "Custom rear-screen deletion failed", it) }
}

@Composable
private fun rememberRearScreenGestureSettings(): Pair<RearScreenGestureSettings, (((RearScreenGestureSettings) -> RearScreenGestureSettings) -> Unit)> {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context) { RearScreenGestureSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    DisposableEffect(context, store) {
        val preferences = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_REAR_SCREEN_LEFT_SWIPE_ENABLED || key == KEY_REAR_SCREEN_LEFT_SWIPE_PACKAGE) {
                store.reload()
                settings = store.settings
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return settings to { transform ->
        store.update(service, transform)
        settings = store.settings
    }
}

@Composable
private fun RearSwipeAppPicker(back: () -> Unit) {
    val context = LocalContext.current
    val installed = rememberRearApps()
    val apps by produceState<List<RearApp>>(emptyList(), installed) {
        value = withContext(Dispatchers.IO) {
            installed.filter { it.packageName != "com.xiaomi.subscreencenter" &&
                context.packageManager.getLaunchIntentForPackage(it.packageName) != null }
        }
    }
    val (settings, update) = rememberRearScreenGestureSettings()
    var query by rememberSaveable { mutableStateOf("") }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    val visibleApps = remember(apps, query, showSystemApps) {
        apps.filter { (showSystemApps || !it.isSystem) &&
            (it.label.contains(query.trim(), ignoreCase = true) || it.packageName.contains(query.trim(), ignoreCase = true)) }
    }
    AppPage(
        title = tr("选择应用", "选择应用"),
        onBack = back,
        restartScopes = setOf(ScopeApplication.SUBSCREEN_CENTER),
        actions = {
            GlassIconDropdownMenu(
                entry = DropdownEntry(items = listOf(DropdownItem(
                    text = if (showSystemApps) tr("隐藏系统应用", "隐藏系统应用") else tr("显示系统应用", "显示系统应用"),
                    selected = showSystemApps,
                    onClick = { showSystemApps = !showSystemApps },
                ))),
                backdrop = LocalToolbarBackdrop.current,
                trigger = { onClick, enabled ->
                    GlassToolbarIconButton(
                        icon = MiuixIcons.Regular.More,
                        description = tr("更多", "更多"),
                        containerPadding = PaddingValues(end = 8.dp),
                        onClick = { if (enabled) onClick() },
                    )
                },
            )
        },
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item { RearAppSearchBar(query, onQueryChange = { query = it }) }
            if (visibleApps.isEmpty()) item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(18.dp)) {
                    Text(tr("没有匹配的应用", "没有匹配的应用"), style = MiuixTheme.textStyles.body2)
                }
            }
            items(visibleApps, key = { it.packageName }) { app ->
                RearAppCard(app = app, checked = settings.packageName == app.packageName, onCheckedChange = {
                    update { it.copy(packageName = app.packageName) }
                    back()
                })
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
                        Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(18.dp)) {
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
                trigger = { onClick, enabled ->
                    GlassToolbarIconButton(
                        icon = MiuixIcons.Regular.More,
                        description = tr("\u66f4\u591a", "\u66f4\u591a"),
                        containerPadding = PaddingValues(end = 8.dp),
                        onClick = { if (enabled) onClick() },
                    )
                },
            )
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
        cornerRadius = 22.5.dp,
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
    navigationSettings: ModuleNavigationSettings,
    settingsApp: SettingsAppPreferences,
    appThemeStore: AppThemeSettingsStore,
    service: XposedService?,
    online: Boolean,
    update: ((HookSettings) -> HookSettings) -> Unit,
    updateNavigation: ((ModuleNavigationSettings) -> ModuleNavigationSettings) -> Unit,
    updateSettingsApp: ((SettingsAppPreferences) -> SettingsAppPreferences) -> Unit,
    open: (PageId) -> Unit,
    onImportModulePreset: () -> Unit,
    onExportModulePreset: () -> Unit,
) = AppPage(tr("settings", tr("设置", "设置"))) { padding, scroll ->
    val context = LocalContext.current
    var editingEntryTitle by remember { mutableStateOf(false) }
    var predictiveProgress by remember(settings.predictiveBackProgress) { mutableFloatStateOf(settings.predictiveBackProgress.toFloat()) }
    AppList(padding, scroll) {
        item { ServiceCard(service, appThemeStore.settings.monetEnabled) }
        item {
            Group(tr("模块设置", "模块设置")) {
                ArrowPreference(title = tr("moduleSafety", "模块安全"), onClick = { open(PageId.MODULE_SAFETY) })
                ArrowPreference(
                    title = tr("restart_scope_apps", tr("\u91cd\u542f\u4f5c\u7528\u57df\u5e94\u7528", "\u91cd\u542f\u4f5c\u7528\u57df\u5e94\u7528")),
                    onClick = { open(PageId.RESTART_SCOPES) },
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
                SwitchPreference(
                    title = tr("隐藏桌面图标", "隐藏桌面图标"),
                    checked = settings.hideAppIcon,
                    onCheckedChange = { value ->
                        update { it.copy(hideAppIcon = value) }
                        setLauncherIconVisible(context, !value)
                    },
                )
                OverlayDropdownPreference(
                    title = tr("系统设置内模块入口", "系统设置内模块入口"),
                    items = listOf(
                        tr("禁用", "禁用"),
                        tr("顶部", "顶部"),
                        tr("中部", "中部"),
                        tr("底部", "底部"),
                    ),
                    selectedIndex = settingsApp.entryPosition.coerceIn(0, 3),
                    onSelectedIndexChange = { index ->
                        if (index == 0) editingEntryTitle = false
                        updateSettingsApp { it.copy(entryPosition = index) }
                    },
                )
                if (settingsApp.entryPosition != 0) {
                    ArrowPreference(
                        title = tr("settingsAppEntryTitle", "模块入口文案"),
                        summary = settingsApp.entryTitle.ifBlank { tr("settingsAppEntryTitleDefault", "默认（模块名称）") },
                        onClick = { editingEntryTitle = true },
                    )
                }
                ArrowPreference(title = tr("导入预设", "导入预设"), onClick = onImportModulePreset)
                ArrowPreference(title = tr("导出预设", "导出预设"), onClick = onExportModulePreset)
            }
        }
        item {
            Group(tr("外观", "外观")) {
                ArrowPreference(
                    title = tr("主题与颜色", "主题与颜色"),
                    summary = if (appThemeStore.settings.monetEnabled) tr("Monet 取色", "Monet 取色") else tr("theme_mode", "主题模式"),
                    onClick = { open(PageId.THEME_AND_COLOR) },
                )
                val navigationStyles = listOf(
                    NavigationStyle.HYPER_OS_FLOATING,
                    NavigationStyle.HYPER_OS,
                    NavigationStyle.HARMONY_OS_FLOATING,
                    NavigationStyle.LIQUID_GLASS,
                )
                OverlayDropdownPreference(
                    title = tr("navigation_style", tr("\u5e95\u90e8\u5bfc\u822a\u680f\u6837\u5f0f", "\u5e95\u90e8\u5bfc\u822a\u680f\u6837\u5f0f")),
                    items = navigationStyles.map { style -> tr(style.displayName, style.displayName) },
                    selectedIndex = navigationStyles.indexOfFirst { it.preferenceValue == navigationSettings.style }.coerceAtLeast(0),
                    onSelectedIndexChange = { i -> updateNavigation { it.copy(style = navigationStyles[i].preferenceValue) } }
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
                    selectedIndex = LabelMode.entries.indexOfFirst { it.preferenceValue == navigationSettings.labelMode }.coerceAtLeast(0),
                    onSelectedIndexChange = { i -> updateNavigation { it.copy(labelMode = LabelMode.entries[i].preferenceValue) } },
                )
                SwitchPreference(
                    title = tr("\u9884\u6d4b\u6027\u8fd4\u56de\u52a8\u753b", "\u9884\u6d4b\u6027\u8fd4\u56de\u52a8\u753b"),
                    checked = settings.predictiveBackEnabled,
                    onCheckedChange = { value -> update { it.copy(predictiveBackEnabled = value) } }
                )
                if (settings.predictiveBackEnabled) {
                    EditableSliderPreference(
                        value = predictiveProgress,
                        onValueChange = { predictiveProgress = it },
                        onValueChangeFinished = { update { it.copy(predictiveBackProgress = predictiveProgress.toInt()) } },
                        title = tr("\u9884\u6d4b\u6027\u8fd4\u56de\u52a8\u753b\u6700\u5927\u8fdb\u5ea6", "\u9884\u6d4b\u6027\u8fd4\u56de\u52a8\u753b\u6700\u5927\u8fdb\u5ea6"),
                        valueText = "${predictiveProgress.toInt()}%",
                        valueRange = 10f..100f,
                        steps = 89,
                        defaultValue = 90f,
                    )
                }
            }
        }
        item {
            Group(tr("模块信息", "模块信息")) {
                ArrowPreference(title = tr("\u5173\u4e8e", "\u5173\u4e8e"), onClick = { open(PageId.ABOUT) })
                ArrowPreference(title = tr("\u8f6f\u4ef6\u66f4\u65b0", "\u8f6f\u4ef6\u66f4\u65b0"), onClick = { open(PageId.SOFTWARE_UPDATE) })
                ArrowPreference(title = tr("\u6350\u8d60", "\u6350\u8d60"), onClick = { open(PageId.DONATE) })
            }
        }
    }
    if (editingEntryTitle) {
        DeviceProfileFieldDialog(
            title = tr("settingsAppEntryTitle", "模块入口文案"),
            current = settingsApp.entryTitle,
            inputLabel = tr("settingsAppEntryTitleHint", "留空则使用模块名称"),
            onDismiss = { editingEntryTitle = false },
            onSave = { value ->
                updateSettingsApp { it.copy(entryTitle = value) }
                editingEntryTitle = false
            },
        )
    }
}

@Composable
private fun ThemeAndColorPage(
    settings: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    appThemeStore: AppThemeSettingsStore,
    service: XposedService?,
    back: () -> Unit,
) {
    val appTheme = appThemeStore.settings
    val themeModes = listOf("system", "light", "dark")
    val colorThemeItems = buildList {
        add(APP_COLOR_SYSTEM)
        addAll(appColorPresets.map { it.id })
        add(APP_COLOR_CUSTOM)
    }
    val selectedColorTheme = appTheme.colorTheme
    AppPage(title = tr("主题与颜色", "主题与颜色"), onBack = back) { padding, scroll ->
        AppList(padding, scroll) {
            item {
                Group(tr("主题模式", "主题模式")) {
                    OverlayDropdownPreference(
                        title = tr("主题模式", "主题模式"),
                        items = listOf(
                            tr("followSystem", "跟随系统"),
                            tr("light_mode", "浅色模式"),
                            tr("dark_mode", "深色模式"),
                        ),
                        selectedIndex = themeModes.indexOf(settings.themeMode).coerceAtLeast(0),
                        onSelectedIndexChange = { index -> update { it.copy(themeMode = themeModes[index]) } },
                    )
                }
            }
            item {
                Group(tr("颜色", "颜色")) {
                    SwitchPreference(
                        title = tr("Monet 取色", "Monet 取色"),
                        summary = tr("monet_color_summary", "使用 Miuix 动态颜色生成主题色"),
                        checked = appTheme.monetEnabled,
                        onCheckedChange = { enabled ->
                            appThemeStore.update(service, appTheme.copy(monetEnabled = enabled))
                        },
                    )
                    AnimatedVisibility(
                        visible = appTheme.monetEnabled,
                        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                    ) {
                        Column {
                            OverlayDropdownPreference(
                                title = tr("monet_color_spec", "色彩规范"),
                                items = AppMonetColorSpec.entries.map { tr(it.labelKey, it.label) },
                                selectedIndex = AppMonetColorSpec.entries.indexOf(appThemeStore.monetColorSpec.effectiveSpec),
                                onSelectedIndexChange = { index ->
                                    appThemeStore.monetColorSpec.update(service, AppMonetColorSpec.entries[index])
                                },
                            )
                            if (appThemeStore.monetColorSpec.effectiveSpec == AppMonetColorSpec.EXPRESSIVE) {
                                Text(
                                    tr("monet_color_spec_2025_summary", "2025 规范适用于柔和彩调、中性、鲜艳和丰富表现，其余调色盘使用 2021 规范。"),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            OverlayDropdownPreference(
                                title = tr("monet_palette", "调色盘"),
                                items = AppMonetPalette.entries.map { tr(it.labelKey, it.label) },
                                selectedIndex = AppMonetPalette.entries.indexOf(appThemeStore.monetPalette.effectivePalette(appTheme.colorTheme)),
                                onSelectedIndexChange = { index ->
                                    appThemeStore.monetPalette.update(service, AppMonetPalette.entries[index])
                                },
                            )
                            OverlayDropdownPreference(
                                title = tr("颜色主题", "颜色主题"),
                                items = colorThemeItems.map { id ->
                                    when (id) {
                                        APP_COLOR_SYSTEM -> tr("followSystem", "跟随系统")
                                        APP_COLOR_CUSTOM -> tr("custom", "自定义")
                                        else -> appColorPresets.first { it.id == id }.let { tr(it.labelKey, it.name) }
                                    }
                                },
                                selectedIndex = colorThemeItems.indexOf(selectedColorTheme).coerceAtLeast(0),
                                onSelectedIndexChange = { index ->
                                    appThemeStore.previewColor = null
                                    appThemeStore.update(service, appTheme.copy(colorTheme = colorThemeItems[index]))
                                },
                            )
                            AnimatedVisibility(
                                visible = selectedColorTheme == APP_COLOR_CUSTOM,
                                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                                exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                            ) {
                                ThemeSeedColorPreference(
                                    title = tr("选择颜色", "选择颜色"),
                                    color = appThemeStore.previewColor ?: appTheme.customColor,
                                    onPreviewColor = { appThemeStore.previewColor = it },
                                    onDismissPreview = { appThemeStore.previewColor = null },
                                    onColorChange = { color ->
                                        appThemeStore.update(service, appTheme.copy(customColor = color, colorTheme = APP_COLOR_CUSTOM))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RestartScopesPage(
    suggestedTargets: Set<ScopeApplication>,
    onRestartedTargets: (Set<ScopeApplication>) -> Unit,
    back: () -> Unit,
) {
    val context = LocalContext.current
    var selectedTargets by remember { mutableStateOf(emptySet<ScopeApplication>()) }
    var confirmationTargets by remember { mutableStateOf<Set<ScopeApplication>?>(null) }
    var hiddenSuggestionTargets by remember { mutableStateOf(emptySet<ScopeApplication>()) }
    val systemUiTargets = listOf(
        ScopeApplication.SYSTEM_UI,
        ScopeApplication.DESKTOP,
        ScopeApplication.WALLPAPER,
        ScopeApplication.AOD,
        ScopeApplication.THEME_MANAGER,
    )
    val systemFeatureTargets = listOf(
        ScopeApplication.SETTINGS,
        ScopeApplication.PERSONAL_ASSISTANT,
        ScopeApplication.SUBSCREEN_CENTER,
        ScopeApplication.SYSTEM_UPDATE,
        ScopeApplication.SOUND_EFFECT,
        ScopeApplication.MI_SETTINGS,
        ScopeApplication.SCREEN_RECORDER,
        ScopeApplication.SCREENSHOT,
        ScopeApplication.SUPER_XIAOAI,
        ScopeApplication.SUPER_XIAOAI_IME,
        ScopeApplication.GALLERY,
        ScopeApplication.CAMERA,
        ScopeApplication.MEDIA_EDITOR,
    )
    val otherTargets = listOf(
        ScopeApplication.CONTACTS_DIALER,
        ScopeApplication.PORTAL,
        ScopeApplication.APPLE_MUSIC,
        ScopeApplication.WEIBO,
        ScopeApplication.XIAOHONGSHU,
        ScopeApplication.XIAOMI_STORE,
        ScopeApplication.XIAOMI_WALLET,
        ScopeApplication.XIAOMI_HEALTH,
        ScopeApplication.TENCENT_MEETING,
        ScopeApplication.CMB_BANK,
        ScopeApplication.WECHAT,
        ScopeApplication.QQ,
        ScopeApplication.PINDUODUO,
        ScopeApplication.INSTAGRAM,
        ScopeApplication.X,
    )
    val toggleTarget: (ScopeApplication) -> Unit = { target ->
        selectedTargets = if (target in selectedTargets) selectedTargets - target else selectedTargets + target
    }
    AppPage(
        title = tr("重启作用域应用", "重启作用域应用"),
        onBack = back,
        floatingToolbarPosition = ToolbarPosition.BottomCenter,
        floatingToolbar = {
            Box(Modifier.fillMaxWidth().padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                GlassActionButton(
                    enabled = selectedTargets.isNotEmpty(),
                    onClick = { confirmationTargets = selectedTargets },
                    size = 60.dp,
                    surfaceColor = MiuixTheme.colorScheme.primary,
                ) {
                    Icon(
                        MiuixIcons.Regular.Refresh,
                        tr("重启", "重启"),
                        Modifier.size(24.dp),
                        tint = MiuixTheme.colorScheme.onPrimary,
                    )
                }
            }
        },
    ) { padding, scroll ->
        AppList(padding, scroll, 112) {
            if (suggestedTargets.isNotEmpty()) {
                item {
                    AnimatedVisibility(
                        visible = suggestedTargets != hiddenSuggestionTargets,
                        exit = fadeOut(
                            animationSpec = tween(320, easing = FastOutSlowInEasing),
                        ) + slideOutVertically(
                            animationSpec = tween(380, easing = FastOutSlowInEasing),
                        ) { height -> -height / 4 } + shrinkVertically(
                            animationSpec = tween(380, easing = FastOutSlowInEasing),
                            shrinkTowards = Alignment.Top,
                        ),
                    ) {
                        val exitBlurRadius by transition.animateFloat(
                            transitionSpec = { tween(300, easing = FastOutSlowInEasing) },
                            label = "restartSuggestionExitBlur",
                        ) { state ->
                            if (state == EnterExitState.Visible) 0f else 12f
                        }
                        Box(Modifier.blur(exitBlurRadius.dp)) {
                            RestartSuggestionCard(
                                targets = suggestedTargets,
                                onApply = { selectedTargets = selectedTargets + suggestedTargets },
                                onDismiss = { hiddenSuggestionTargets = suggestedTargets },
                            )
                        }
                    }
                }
            }
            item {
                RestartScopeCard(
                    title = tr("系统界面", "系统界面"),
                    targets = systemUiTargets,
                    selectedTargets = selectedTargets,
                    onToggle = toggleTarget,
                )
            }
            item {
                RestartScopeCard(
                    title = tr("系统功能", "系统功能"),
                    targets = systemFeatureTargets,
                    selectedTargets = selectedTargets,
                    onToggle = toggleTarget,
                )
            }
            item {
                RestartScopeCard(
                    title = tr("其他", "其他"),
                    targets = otherTargets,
                    selectedTargets = selectedTargets,
                    onToggle = toggleTarget,
                )
            }
        }
    }
    RestartScopesConfirmationDialog(
        show = confirmationTargets != null,
        selectedTargets = confirmationTargets.orEmpty(),
        onDismiss = { confirmationTargets = null },
        onConfirm = {
            val restartedTargets = confirmationTargets.orEmpty()
            SystemUiRestarter.restart(context, restartedTargets)
            onRestartedTargets(restartedTargets)
            selectedTargets = selectedTargets - restartedTargets
            confirmationTargets = null
        },
    )
}

@Composable
private fun RestartSuggestionCard(
    targets: Set<ScopeApplication>,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(22.5.dp)
    val backdrop = rememberLayerBackdrop()
    val animationScope = rememberCoroutineScope()
    val touchHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope,
            radiusMultiplier = 1.75f,
            surfaceAlpha = .12f,
            falloffMultiplier = .30f,
        ) { size, offset ->
            Offset(
                offset.x.coerceIn(0f, size.width),
                offset.y.coerceIn(0f, size.height),
            )
        }
    }
    val surface = MiuixTheme.colorScheme.surfaceVariant.copy(alpha = .70f)
    val glassModifier = if (isRuntimeShaderSupported()) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(4.dp.toPx())
            },
            highlight = {
                Highlight.Default.copy(
                    width = .75.dp,
                    blurRadius = .35.dp,
                    alpha = .78f,
                )
            },
            shadow = null,
            innerShadow = null,
            onDrawSurface = { },
        )
    } else {
        Modifier.clip(shape)
    }
    val orderedTargets = ScopeApplication.entries.filter { it in targets }
    val targetNames = orderedTargets.joinToString("、") { tr(it.title, it.title) }
    Box(
        modifier = Modifier.fillMaxWidth().then(touchHighlight.gestureModifier),
    ) {
        // Keep the sampled layer and the glass consumer as siblings. Reading the page's own
        // backdrop from inside its recorded content creates a RenderNode cycle on HyperOS.
        Box(
            Modifier.matchParentSize()
                .layerBackdrop(backdrop)
                .clip(shape)
                .background(surface),
        )
        Box(
            Modifier.matchParentSize()
                .then(glassModifier)
                .border(
                    width = 1.dp,
                    color = ComposeColor.Gray.copy(alpha = .20f),
                    shape = shape,
                ),
        )
        Box(
            Modifier.matchParentSize()
                .clip(shape)
                .then(touchHighlight.modifier),
        )
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("重启建议", "重启建议"),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = MiuixIcons.Regular.Close,
                    contentDescription = tr("关闭", "关闭"),
                    modifier = Modifier.size(18.dp).clip(CircleShape).clickable(onClick = onDismiss),
                    tint = MiuixTheme.colorScheme.onSurface.copy(alpha = .70f),
                )
            }
            Text(
                tr("建议重启%s作用域", "建议重启%s作用域").format(targetNames),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 5.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                GlassDialogButton(
                    onClick = onApply,
                    modifier = Modifier.widthIn(min = 95.dp).height(43.dp),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(
                        tr("应用选项", "应用选项"),
                        style = MiuixTheme.textStyles.body1.copy(
                            fontSize = MiuixTheme.textStyles.body1.fontSize * .80f,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun RestartScopeCard(
    title: String,
    targets: List<ScopeApplication>,
    selectedTargets: Set<ScopeApplication>,
    onToggle: (ScopeApplication) -> Unit,
) {
    SmallTitle(
        title,
        insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
    )
    Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
        targets.forEach { target ->
            Row(
                Modifier.fillMaxWidth().clickable { onToggle(target) }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        tr(target.title, target.title),
                        style = MiuixTheme.textStyles.body1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        target.packageName,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Checkbox(
                    state = if (target in selectedTargets) ToggleableState.On else ToggleableState.Off,
                    onClick = { onToggle(target) },
                    modifier = Modifier.padding(start = 12.dp),
                    colors = CheckboxDefaults.checkboxColors(
                        uncheckedForegroundColor = ComposeColor(0x808F8F8F),
                    ),
                )
            }
        }
    }
}

@Composable
private fun RestartScopesConfirmationDialog(
    show: Boolean,
    selectedTargets: Set<ScopeApplication>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Text(
            tr("确认重启所选作用域应用？", "确认重启所选作用域应用？"),
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Bold,
        )
        Text(
            tr("重启后相关应用会暂时关闭，并在下次使用时重新启动。", "重启后相关应用会暂时关闭，并在下次使用时重新启动。"),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            selectedTargets.joinToString("、") { tr(it.title, it.title) },
            style = MiuixTheme.textStyles.body1,
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
            GlassDialogButton(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary(),
                enabled = selectedTargets.isNotEmpty(),
            ) { Text(tr("重启", "重启")) }
        }
    }
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
                colors = CheckboxDefaults.checkboxColors(
                    uncheckedForegroundColor = ComposeColor(0x808F8F8F),
                ),
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
private fun Desktop(
    settings: DockSettings,
    update: ((DockSettings) -> DockSettings) -> Unit,
    homeRecentsSettings: HomeRecentsSettings,
    updateHomeRecents: ((HomeRecentsSettings) -> HomeRecentsSettings) -> Unit,
    homeFolderSettings: HomeFolderSettings,
    updateHomeFolder: ((HomeFolderSettings) -> HomeFolderSettings) -> Unit,
    homeMaterialSettings: HomeMaterialSettings,
    updateHomeMaterial: ((HomeMaterialSettings) -> HomeMaterialSettings) -> Unit,
    openPage: (PageId) -> Unit,
    back: () -> Unit,
) = AppPage(tr("桌面", "桌面"), back, restartScopes = setOf(ScopeApplication.DESKTOP)) { padding, scroll ->
    AppList(padding, scroll) {
        item {
            Group(tr("Dock 栏", "Dock 栏")) {
                SwitchPreference(
                    title = tr("Dock 栏图标垂直偏移", "Dock 栏图标垂直偏移"),
                    summary = tr("负值向下，正值向上", "负值向下，正值向上"),
                    checked = homeMaterialSettings.dockIcon.enabled,
                    onCheckedChange = { enabled -> updateHomeMaterial { it.copy(dockIcon = it.dockIcon.copy(enabled = enabled)) } },
                )
                AnimatedVisibility(visible = homeMaterialSettings.dockIcon.enabled) {
                    var offset by remember(homeMaterialSettings.dockIcon.offset) {
                        mutableFloatStateOf(homeMaterialSettings.dockIcon.offset.toFloat())
                    }
                    EditableSliderPreference(
                        title = tr("图标垂直偏移", "图标垂直偏移"),
                        value = offset,
                        valueText = tr("Dock 图标偏移数值", "%s dp").format(offset.roundToInt()),
                        valueRange = -60f..30f, steps = 89, centerValue = 0f, defaultValue = 0f,
                        onValueChange = { offset = it },
                        onValueChangeFinished = {
                            updateHomeMaterial { it.copy(dockIcon = it.dockIcon.copy(offset = offset.roundToInt().coerceIn(-60, 30))) }
                        },
                    )
                }

                SwitchPreference(
                    title = tr("为桌面添加 Dock", "为桌面添加 Dock"),
                    checked = settings.enabled,
                    onCheckedChange = { enabled -> update { it.copy(enabled = enabled) } },
                )
            }
        }
        item {
            AnimatedVisibility(
                visible = settings.enabled,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
            ) {
                Group(tr("Dock 背景材质", "Dock 背景材质")) {
                    OverlayDropdownPreference(
                        title = tr("Dock 背景材质", "Dock 背景材质"),
                        items = listOf(tr("纯色", "纯色"), tr("高级材质", "高级材质"), tr("柔光玻璃", "柔光玻璃")),
                        selectedIndex = settings.material,
                        onSelectedIndexChange = { value -> update { it.copy(material = value) } },
                    )
                    ParameterIntSlide(tr("Dock 水平边距", "Dock 水平边距"), settings.margin, 0..150, " dp", defaultValue = 25) { value ->
                        update { it.copy(margin = value) }
                    }
                    ParameterIntSlide(tr("Dock 圆角", "Dock 圆角"), settings.radius, 0..60, " dp", defaultValue = 30) { value ->
                        update { it.copy(radius = value) }
                    }
                    when (settings.material) {
                        0 -> ShortcutBackgroundColorPreference(
                            color = settings.solidColor,
                            onColorChange = { value -> update { it.copy(solidColor = value) } },
                        )
                        1 -> Column {
                            ShortcutBackgroundColorPreference(
                                color = settings.advancedColor,
                                onColorChange = { value -> update { it.copy(advancedColor = value) } },
                            )
                            ParameterIntSlide(tr("不透明度", "不透明度"), settings.advancedOpacity, 0..100, "%", defaultValue = 14) { value ->
                                update { it.copy(advancedOpacity = value) }
                            }
                            ParameterIntSlide(tr("背景模糊度", "背景模糊度"), settings.advancedBlur, 0..100, " px", defaultValue = 40) { value ->
                                update { it.copy(advancedBlur = value) }
                            }
                            SwitchPreference(
                                title = tr("显示高光", "显示高光"),
                                checked = settings.advancedHighlight,
                                onCheckedChange = { value -> update { it.copy(advancedHighlight = value) } },
                            )
                        }
                        else -> HomeGlassMaterialControls(dock = true)
                    }
                }
            }
        }
        item {
            Group(tr("桌面材质", "桌面材质")) {
                SwitchPreference(
                    title = tr("桌面材质调整", "桌面材质调整"),
                    checked = homeMaterialSettings.enabled,
                    onCheckedChange = { enabled -> updateHomeMaterial { it.copy(enabled = enabled) } },
                )
                AnimatedVisibility(visible = homeMaterialSettings.enabled) {
                    ArrowPreference(title = tr("调整桌面材质", "调整桌面材质"), onClick = { openPage(PageId.DESKTOP_MATERIAL) })
                }
            }
        }
        item {
            Group(tr("文件夹", "文件夹")) {
                val columns = listOf(0, 4, 5, 6, HOME_FOLDER_CUSTOM_COLUMNS)
                OverlayDropdownPreference(
                    title = tr("自定义文件夹列数", "自定义文件夹列数"),
                    items = listOf(tr("系统默认", "系统默认"), tr("4", "4"), tr("5", "5"), tr("6", "6"), tr("自定义", "自定义")),
                    selectedIndex = columns.indexOf(homeFolderSettings.columns).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        updateHomeFolder { it.copy(columns = columns[index]) }
                    },
                )
                AnimatedVisibility(visible = homeFolderSettings.columns == HOME_FOLDER_CUSTOM_COLUMNS) {
                    ParameterIntSlide(
                        tr("文件夹列数", "文件夹列数"), homeFolderSettings.customColumns,
                        1..15, defaultValue = 3,
                    ) { value -> updateHomeFolder { it.copy(customColumns = value) } }
                }
                var rowSpacing by remember(homeFolderSettings.rowSpacing) {
                    mutableFloatStateOf(homeFolderSettings.rowSpacing.toFloat())
                }
                SwitchPreference(
                    title = tr("自定义文件夹图标行间距", "自定义文件夹图标行间距"),
                    checked = homeFolderSettings.rowSpacingEnabled,
                    onCheckedChange = { enabled -> updateHomeFolder { it.copy(rowSpacingEnabled = enabled) } },
                )
                AnimatedVisibility(visible = homeFolderSettings.rowSpacingEnabled) {
                    EditableSliderPreference(
                        title = tr("文件夹图标行间距", "文件夹图标行间距"),
                        value = rowSpacing,
                        valueText = tr("文件夹行间距数值", "%s dp").format(rowSpacing.roundToInt()),
                        valueRange = -20f..80f,
                        steps = 99,
                        centerValue = 0f,
                        defaultValue = 0f,
                        onValueChange = { rowSpacing = it },
                        onValueChangeFinished = {
                            updateHomeFolder { it.copy(rowSpacing = rowSpacing.roundToInt().coerceIn(-20, 80)) }
                        },
                    )
                }
            }
        }
        item {
            Group(tr("多任务", "多任务")) {
                OverlayDropdownPreference(
                    title = tr("隐藏多任务界面的清除按钮", "隐藏多任务界面的清除按钮"),
                    items = listOf(
                        tr("不隐藏", "不隐藏"),
                        tr("保留功能", "保留功能"),
                        tr("不保留功能", "不保留功能"),
                    ),
                    selectedIndex = homeRecentsSettings.clearMode.value,
                    onSelectedIndexChange = { index ->
                        updateHomeRecents {
                            it.copy(clearMode = HomeRecentsClearMode.fromValue(index))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeGlassMaterialControls(dock: Boolean) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { HomeGlassSettingsStore(context) }
    var selected by remember(dock) { mutableStateOf(store.read(dock)) }
    val changed = LocalDesktopSettingsChanged.current
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        selected = store.read(dock)
    }
    fun apply(transform: (HomeGlassSurfaceSettings) -> HomeGlassSurfaceSettings) {
        store.update(service, dock, transform)
        selected = store.read(dock)
        val p = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        if (stageHomeRecentsNativeConfig(context, readHomeRecentsSettings(p))) changed()
        else Toast.makeText(context, tr("写入桌面配置失败，请检查 Root 权限", "写入桌面配置失败，请检查 Root 权限"), Toast.LENGTH_LONG).show()
    }
    Column {
        if (dock) {
            OverlayDropdownPreference(
                title = tr("柔光玻璃参数来源", "柔光玻璃参数来源"),
                items = listOf(tr("系统默认", "系统默认"), tr("全局柔光玻璃参数", "全局柔光玻璃参数"), tr("自定义", "自定义")),
                selectedIndex = selected.dockSource.ordinal,
                onSelectedIndexChange = { index -> apply { it.copy(dockSource = DockGlassParameterSource.entries[index]) } },
            )
        } else {
            SwitchPreference(
                title = tr("使用全局柔光玻璃参数", "使用全局柔光玻璃参数"),
                checked = selected.useGlobal,
                onCheckedChange = { value -> apply { it.copy(useGlobal = value) } },
            )
        }
        AnimatedVisibility(
            visible = if (dock) selected.dockSource == DockGlassParameterSource.CUSTOM else !selected.useGlobal,
            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
        ) {
            SoftGlassMaterialSliders(
                glass = selected.glass,
                onChange = { transform -> apply { it.copy(glass = transform(it.glass)) } },
                includeBackdrop = false,
            )
        }
    }
}

@Composable
private fun HomeMaterialPage(back: () -> Unit) = AppPage(tr("桌面材质调整", "桌面材质调整"), back, restartScopes = setOf(ScopeApplication.DESKTOP)) { padding, scroll ->
    AppList(padding, scroll) {
        item {
            Group(tr("柔光玻璃", "柔光玻璃")) {
                HomeGlassMaterialControls(dock = false)
            }
        }
    }
}

@Composable
private fun FrameworkPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { FrameworkPreferencesStore(context) }
    var framework by remember { mutableStateOf(store.settings) }
    LaunchedEffect(service) {
        service?.let {
            store.syncRemote(it)
            framework = store.settings
        }
    }
    fun apply(transform: (FrameworkPreferences) -> FrameworkPreferences) {
        store.update(service, transform)
        framework = store.settings
    }
    AppPage(tr("系统框架", "系统框架"), back) { padding, scroll ->
        AppList(padding, scroll) {
            item {
                Text(
                    tr(
                        "系统框架相关的全部功能在设置后，至少要进行软重启才能生效。",
                        "系统框架相关的全部功能在设置后，至少要进行软重启才能生效。",
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
            }
            item {
                Group(tr("小窗", "小窗")) {
                    SwitchPreference(
                        title = tr("允许多小窗", "允许多小窗"),
                        summary = tr("放宽小窗数量上限，可同时打开多个小窗。", "放宽小窗数量上限，可同时打开多个小窗。"),
                        checked = framework.multipleFreeform,
                        onCheckedChange = { value -> apply { it.copy(multipleFreeform = value) } },
                    )
                    SwitchPreference(
                        title = tr("强制允许小窗", "强制允许小窗"),
                        summary = tr("忽略应用的小窗黑名单与可调整大小限制。", "忽略应用的小窗黑名单与可调整大小限制。"),
                        checked = framework.forceFreeform,
                        onCheckedChange = { value -> apply { it.copy(forceFreeform = value) } },
                    )
                    SwitchPreference(
                        title = tr("记住状态和位置", "记住状态和位置"),
                        summary = tr("下次从小窗应用进入时恢复它的小窗位置与大小。", "下次从小窗应用进入时恢复它的小窗位置与大小。"),
                        checked = framework.rememberFreeform,
                        onCheckedChange = { value -> apply { it.copy(rememberFreeform = value) } },
                    )
                    SwitchPreference(
                        title = tr("贴边小窗允许后台活动", "贴边小窗允许后台活动"),
                        summary = tr("小窗贴边后仍按前台处理，避免应用被冻结。", "小窗贴边后仍按前台处理，避免应用被冻结。"),
                        checked = framework.freeformBackground,
                        onCheckedChange = { value -> apply { it.copy(freeformBackground = value) } },
                    )
                }
            }
            item {
                Group(tr("音量", "音量")) {
                    OverlayDropdownPreference(
                        title = tr("移除高音量安全提示", "移除高音量安全提示"),
                        items = listOf(
                            tr("系统默认", "系统默认"),
                            tr("所有情况", "所有情况"),
                            tr("仅对耳机连接时生效", "仅对耳机连接时生效"),
                        ),
                        selectedIndex = framework.volumeSafeMode.coerceIn(0, VOLUME_SAFE_HEADSET_ONLY),
                        onSelectedIndexChange = { index ->
                            apply { it.copy(volumeSafeMode = index.coerceIn(0, VOLUME_SAFE_HEADSET_ONLY)) }
                        },
                    )
                    SwitchPreference(
                        title = tr("音量即时调整", "音量即时调整"),
                        summary = tr("按住音量键时立即调整音量，无需先取消静音。", "按住音量键时立即调整音量，无需先取消静音。"),
                        checked = framework.volumeInstant,
                        onCheckedChange = { value -> apply { it.copy(volumeInstant = value) } },
                    )
                }
            }
            item {
                Group(tr("功能", "功能")) {
                    SwitchPreference(
                        title = tr("允许所有应用启用强制深色模式", "允许所有应用启用强制深色模式"),
                        summary = tr("让第三方应用也能出现在强制深色模式的应用列表中。", "让第三方应用也能出现在强制深色模式的应用列表中。"),
                        checked = framework.forceDarkAllApps,
                        onCheckedChange = { value -> apply { it.copy(forceDarkAllApps = value) } },
                    )
                    SwitchPreference(
                        title = tr("增强最近任务可见性", "增强最近任务可见性"),
                        summary = tr("让声明隐藏于最近任务的应用重新出现在最近任务中。", "让声明隐藏于最近任务的应用重新出现在最近任务中。"),
                        checked = framework.recentsVisibility,
                        onCheckedChange = { value -> apply { it.copy(recentsVisibility = value) } },
                    )
                    SwitchPreference(
                        title = tr("使用原生安装器", "使用原生安装器"),
                        summary = tr("安装应用时使用系统原生安装器界面。", "安装应用时使用系统原生安装器界面。"),
                        checked = framework.nativeInstaller,
                        onCheckedChange = { value -> apply { it.copy(nativeInstaller = value) } },
                    )
                    SwitchPreference(
                        title = tr("禁用每72小时必须输入锁屏密码的功能", "禁用每72小时必须输入锁屏密码的功能"),
                        summary = tr("不再强制要求定期输入锁屏密码进行强身份验证。", "不再强制要求定期输入锁屏密码进行强身份验证。"),
                        checked = framework.disablePin72h,
                        onCheckedChange = { value -> apply { it.copy(disablePin72h = value) } },
                    )
                    SwitchPreference(
                        title = tr("禁用设备名称校验", "禁用设备名称校验"),
                        summary = tr("跳过修改设备名称与蓝牙名称时的合规校验。", "跳过修改设备名称与蓝牙名称时的合规校验。"),
                        checked = framework.disableDeviceNameCheck,
                        onCheckedChange = { value -> apply { it.copy(disableDeviceNameCheck = value) } },
                    )
                }
            }
            item {
                Group(tr("截图", "截图")) {
                    SwitchPreference(
                        title = tr("允许即时截图", "允许即时截图"),
                        summary = tr("去掉按键截屏的长按等待，按下即触发。", "去掉按键截屏的长按等待，按下即触发。"),
                        checked = framework.quickScreenshot,
                        onCheckedChange = { value -> apply { it.copy(quickScreenshot = value) } },
                    )
                    SwitchPreference(
                        title = tr("允许在任意界面截屏", "允许在任意界面截屏"),
                        summary = tr("忽略应用的截屏限制标记，允许对受保护界面截图。", "忽略应用的截屏限制标记，允许对受保护界面截图。"),
                        checked = framework.screenshotAnywhere,
                        onCheckedChange = { value -> apply { it.copy(screenshotAnywhere = value) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemSettings(
    settingsApp: SettingsAppPreferences,
    updateSettingsApp: ((SettingsAppPreferences) -> SettingsAppPreferences) -> Unit,
    profile: DeviceProfileSettings,
    update: ((DeviceProfileSettings) -> DeviceProfileSettings) -> Unit,
    appearance: SettingsAppearanceSettings,
    updateAppearance: ((SettingsAppearanceSettings) -> SettingsAppearanceSettings) -> Unit,
    open: (PageId) -> Unit,
    onPickLogo: () -> Unit,
    onClearLogo: () -> Unit,
    back: () -> Unit,
) = AppPage(tr("系统设置", "系统设置"), back, restartScopes = setOf(ScopeApplication.SETTINGS, ScopeApplication.SOUND_EFFECT, ScopeApplication.MI_SETTINGS)) { padding, scroll ->
    val cpuIconContext = LocalContext.current
    val soundEffectService by HookApplication.service.collectAsStateWithLifecycle()
    val appScopeService by HookApplication.service.collectAsStateWithLifecycle()
    val miSettingsService by HookApplication.service.collectAsStateWithLifecycle()
    val miSettingsStore = remember(cpuIconContext) { MiSettingsPreferencesStore(cpuIconContext) }
    var miSettings by remember { mutableStateOf(miSettingsStore.settings) }
    LaunchedEffect(miSettingsService) {
        miSettingsService?.let {
            miSettingsStore.syncRemote(it)
            miSettings = miSettingsStore.settings
        }
    }
    val appScopeStore = remember(cpuIconContext) { SettingsAppScopePreferencesStore(cpuIconContext) }
    var appScope by remember { mutableStateOf(appScopeStore.settings) }
    LaunchedEffect(appScopeService) {
        appScopeService?.let {
            appScopeStore.syncRemote(it)
            appScope = appScopeStore.settings
        }
    }
    val advancedColorStyleStore = remember(cpuIconContext) { SettingsAdvancedColorStyleSettingsStore(cpuIconContext) }
    var advancedColorStyle by remember { mutableStateOf(advancedColorStyleStore.settings) }
    LaunchedEffect(appScopeService) {
        appScopeService?.let {
            advancedColorStyleStore.syncRemote(it)
            advancedColorStyle = advancedColorStyleStore.settings
        }
    }
    val soundEffectStore = remember(cpuIconContext) { SoundEffectPreferencesStore(cpuIconContext) }
    var soundEffect by remember { mutableStateOf(soundEffectStore.settings) }
    LaunchedEffect(soundEffectService) {
        soundEffectService?.let {
            soundEffectStore.syncRemote(it)
            soundEffect = soundEffectStore.settings
        }
    }
    var audioLogoImported by remember {
        mutableStateOf(runCatching { appearanceFile(cpuIconContext, APPEARANCE_SLOT_AUDIO_LOGO).exists() }.getOrDefault(false))
    }
    val pickAudioLogo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                copyAppearanceFile(cpuIconContext, APPEARANCE_SLOT_AUDIO_LOGO, uri)
                audioLogoImported = true
                Toast.makeText(cpuIconContext, tr("已导入", "已导入"), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(cpuIconContext, tr("导入失败", "导入失败"), Toast.LENGTH_SHORT).show()
            }
        }
    }
    var cpuIconImported by remember {
        mutableStateOf(runCatching { appearanceFile(cpuIconContext, APPEARANCE_SLOT_CPU_ICON).exists() }.getOrDefault(false))
    }
    val pickCpuIcon = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                copyAppearanceFile(cpuIconContext, APPEARANCE_SLOT_CPU_ICON, uri)
                cpuIconImported = true
                Toast.makeText(cpuIconContext, tr("已导入", "已导入"), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(cpuIconContext, tr("导入失败", "导入失败"), Toast.LENGTH_SHORT).show()
            }
        }
    }
    AppList(padding, scroll) {
        item {
            Group(tr("音效设置", "音效设置")) {
                SwitchPreference(
                    title = tr("启用 BOSE 音效", "启用 BOSE 音效"),
                    summary = tr("在音效设置中启用 BOSE 调音。仅解锁系统隐藏的调音方案，不保证对音质有提升作用。", "在音效设置中启用 BOSE 调音。仅解锁系统隐藏的调音方案，不保证对音质有提升作用。"),
                    checked = soundEffect.boseEnabled,
                    onCheckedChange = { value ->
                        soundEffectStore.update(soundEffectService) { it.copy(boseEnabled = value) }
                        soundEffect = soundEffectStore.settings
                    },
                )
                Column(Modifier.fillMaxWidth()) {
                    val logoItems = if (soundEffect.boseEnabled) {
                        listOf(
                            tr("BOSE", "BOSE"),
                            tr("Xiaomi", "Xiaomi"),
                            tr("Harman Kardon", "Harman Kardon"),
                            tr("Dolby Atmos", "Dolby Atmos"),
                            tr("自定义", "自定义"),
                        )
                    } else {
                        listOf(
                            tr("不显示", "不显示"),
                            tr("BOSE", "BOSE"),
                            tr("Xiaomi", "Xiaomi"),
                            tr("Harman Kardon", "Harman Kardon"),
                            tr("Dolby Atmos", "Dolby Atmos"),
                            tr("自定义", "自定义"),
                        )
                    }
                    // Without BOSE the badge is an extra element, so "hide it" is a valid choice.
                    val logoOffset = if (soundEffect.boseEnabled) 1 else 0
                    OverlayDropdownPreference(
                        title = tr("音效徽标", "音效徽标"),
                        items = logoItems,
                        selectedIndex = (soundEffect.logoMode - logoOffset).coerceIn(0, logoItems.size - 1),
                        onSelectedIndexChange = { index ->
                            soundEffectStore.update(soundEffectService) { it.copy(logoMode = index + logoOffset) }
                            soundEffect = soundEffectStore.settings
                        },
                    )
                    AnimatedVisibility(
                        visible = soundEffect.needsCustomLogo,
                        enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(180)),
                        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(120)),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            ArrowPreference(
                                title = tr("导入徽标", "导入徽标"),
                                summary = if (audioLogoImported) tr("已导入", "已导入") else tr("未导入", "未导入"),
                                onClick = { pickAudioLogo.launch(arrayOf("image/*", "application/xml", "text/xml")) },
                            )
                            ArrowPreference(
                                title = tr("清除徽标", "清除徽标"),
                                summary = if (audioLogoImported) tr("已导入", "已导入") else tr("无图标", "无图标"),
                                onClick = {
                                    runCatching { appearanceFile(cpuIconContext, APPEARANCE_SLOT_AUDIO_LOGO).delete() }
                                    audioLogoImported = false
                                },
                            )
                        }
                    }
                }
            }
        }
        item {
            Group(tr("设置应用", "设置应用")) {
                SwitchPreference(
                    title = tr("启用设备信息覆盖", "启用设备信息覆盖"),
                    checked = profile.enabled,
                    onCheckedChange = { enabled -> update { it.copy(enabled = enabled) } },
                )
                OverlayDropdownPreference(
                    title = tr("处理器图标", "处理器图标"),
                    items = listOf(
                        tr("系统默认", "系统默认"),
                        tr("骁龙", "骁龙"),
                        tr("玄戒", "玄戒"),
                        tr("天玑", "天玑"),
                        tr("Apple Silicon", "Apple Silicon"),
                        tr("英特尔", "英特尔"),
                        tr("英伟达", "英伟达"),
                        tr("AMD", "AMD"),
                        tr("自定义", "自定义"),
                    ),
                    selectedIndex = profile.processorIcon.coerceIn(0, PROCESSOR_ICON_MAX),
                    onSelectedIndexChange = { index ->
                        update {
                            it.copy(
                                processorIcon = index.coerceIn(0, PROCESSOR_ICON_MAX),
                                snapdragonIcon = index == PROCESSOR_ICON_SNAPDRAGON,
                            )
                        }
                    },
                )
                // Mirrors the LOGO import pattern in the appearance page, but animated so the rows
                // slide in instead of popping the group's height.
                AnimatedVisibility(
                    visible = profile.processorIcon == PROCESSOR_ICON_CUSTOM,
                    enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(180)),
                    exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(120)),
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        ArrowPreference(
                            title = tr("导入处理器图标", "导入处理器图标"),
                            summary = if (cpuIconImported) tr("已导入", "已导入") else tr("未导入", "未导入"),
                            onClick = { pickCpuIcon.launch(arrayOf("image/*", "application/xml", "text/xml")) },
                        )
                        ArrowPreference(
                            title = tr("清除处理器图标", "清除处理器图标"),
                            summary = if (cpuIconImported) tr("已导入", "已导入") else tr("无图标", "无图标"),
                            onClick = {
                                runCatching { appearanceFile(cpuIconContext, APPEARANCE_SLOT_CPU_ICON).delete() }
                                cpuIconImported = false
                            },
                        )
                    }
                }
                SwitchPreference(
                    title = tr("解锁更多自动锁屏选项", "解锁更多自动锁屏选项"),
                    checked = settingsApp.unlockNeverScreenTimeout,
                    onCheckedChange = { enabled -> updateSettingsApp { it.copy(unlockNeverScreenTimeout = enabled) } },
                )
                SwitchPreference(
                    title = tr("显示 Google 服务入口", "显示 Google 服务入口"),
                    checked = settingsApp.showGoogleServiceEntry,
                    onCheckedChange = { enabled -> updateSettingsApp { it.copy(showGoogleServiceEntry = enabled) } },
                )
                SwitchPreference(
                    title = tr("显示已保存 WiFi 的密码", "显示已保存 WiFi 的密码"),
                    checked = settingsApp.showSavedWifiPasswords,
                    onCheckedChange = { enabled -> updateSettingsApp { it.copy(showSavedWifiPasswords = enabled) } },
                )
                SwitchPreference(
                    title = tr("解锁旧版控制中心样式", "解锁旧版控制中心样式"),
                    summary = tr("恢复旧版控制中心的下拉样式。需要同时勾选并重启“设置”和“系统界面”两个作用域应用，不保证功能能在所有设备和系统上生效。", "恢复旧版控制中心的下拉样式。需要同时勾选并重启“设置”和“系统界面”两个作用域应用，不保证功能能在所有设备和系统上生效。"),
                    checked = appScope.oldControlCenter,
                    onCheckedChange = { value ->
                        appScopeStore.update(appScopeService) { it.copy(oldControlCenter = value) }
                        appScope = appScopeStore.settings
                    },
                )
                SwitchPreference(
                    title = tr("解锁屏幕色彩风格的高级模式", "解锁屏幕色彩风格的高级模式"),
                    checked = advancedColorStyle.enabled,
                    onCheckedChange = { value ->
                        advancedColorStyleStore.update(appScopeService) { it.copy(enabled = value) }
                        advancedColorStyle = advancedColorStyleStore.settings
                    },
                )
            }
        }
        item {
            SmallTitle(tr("小米设置", "小米设置"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                SwitchPreference(
                    title = tr("显示更多帧率选项", "显示更多帧率选项"),
                    summary = tr("在显示设置的刷新率列表中增加更多帧率选项。仅改变可选列表，不保证能修改真实帧率。", "在显示设置的刷新率列表中增加更多帧率选项。仅改变可选列表，不保证能修改真实帧率。"),
                    checked = miSettings.showMoreFps,
                    onCheckedChange = { value ->
                        miSettingsStore.update(miSettingsService) { it.copy(showMoreFps = value) }
                        miSettings = miSettingsStore.settings
                    },
                )
            }
        }
        item {
            SmallTitle(tr("界面自定义", "界面自定义"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
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
                    EditableSliderPreference(
                        value = appearance.logoScale.toFloat(),
                        onValueChange = { value -> updateAppearance { it.copy(logoScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("LOGO缩放", "LOGO缩放"),
                        valueText = "${appearance.logoScale}%",
                        valueRange = 50f..200f,
                        steps = 149,
                        defaultValue = 100f,
                    )
                }
            }
        }
        item {
            Group(tr("settingsEntryLabelsGroup", "设置入口文案")) {
                ArrowPreference(
                    title = tr("settingsEntryLabels", "自定义系统设置入口文案"),
                    onClick = { open(PageId.SETTINGS_ENTRY_LABELS) },
                )
            }
        }
        item {
            Group(tr("设备信息", "设备信息")) {
                ArrowPreference(title = tr("我的设备与全部参数", "我的设备与全部参数"), onClick = { open(PageId.DEVICE_PROFILE) })
            }
        }
    }
}

private data class SettingsEntryLabel(val id: String, val title: String)

/**
 * Settings dashboard headers this module can retitle.
 *
 * Keyed by the header id's **resource entry name** (`my_device`, `bluetooth_settings`, ...), which is
 * stable across Settings builds; the numeric `R.id` values are not.
 */
private fun settingsEntryLabelCatalog(): List<SettingsEntryLabel> = listOf(
    SettingsEntryLabel("my_device", tr("我的设备", "我的设备")),
    SettingsEntryLabel("bluetooth_settings", tr("蓝牙", "蓝牙")),
    SettingsEntryLabel("wifi_settings", tr("WLAN", "WLAN")),
    SettingsEntryLabel("msim_settings", tr("SIM 卡与移动网络", "SIM 卡与移动网络")),
    SettingsEntryLabel("mobile_network_settings", tr("移动网络", "移动网络")),
    SettingsEntryLabel("wifi_tether_settings", tr("个人热点", "个人热点")),
    SettingsEntryLabel("vpn_settings", tr("VPN", "VPN")),
    SettingsEntryLabel("sound_settings", tr("声音与振动", "声音与振动")),
    SettingsEntryLabel("screen_settings", tr("显示与亮度", "显示与亮度")),
    SettingsEntryLabel("battery_settings_new", tr("电池", "电池")),
    SettingsEntryLabel("application_settings", tr("应用", "应用")),
    SettingsEntryLabel("privacy_protection_settings", tr("隐私保护", "隐私保护")),
    SettingsEntryLabel("location_settings", tr("位置信息", "位置信息")),
    SettingsEntryLabel("security_settings", tr("安全", "安全")),
    SettingsEntryLabel("mi_account_settings", tr("小米账号", "小米账号")),
    SettingsEntryLabel("micloud_settings", tr("小米云服务", "小米云服务")),
    SettingsEntryLabel("wallpaper_settings", tr("壁纸与个性化", "壁纸与个性化")),
    SettingsEntryLabel("theme_settings", tr("主题", "主题")),
    SettingsEntryLabel("font_settings", tr("字体", "字体")),
    SettingsEntryLabel("accessibility_function", tr("无障碍", "无障碍")),
    SettingsEntryLabel("other_advanced_settings", tr("更多设置", "更多设置")),
    SettingsEntryLabel("launcher_settings", tr("桌面", "桌面")),
    SettingsEntryLabel("app_timer", tr("应用使用时间", "应用使用时间")),
    SettingsEntryLabel("sub_screen", tr("妙享背屏", "妙享背屏")),
    SettingsEntryLabel("flip_screen_settings", tr("小折叠外屏", "小折叠外屏")),
    SettingsEntryLabel("fold_screen_settings", tr("折叠屏专区", "折叠屏专区")),
    SettingsEntryLabel("tablet_screen_settings", tr("平板专区", "平板专区")),
    SettingsEntryLabel("satellite_settings", tr("卫星通信", "卫星通信")),
    SettingsEntryLabel("interconnection_settings", tr("互联互通", "互联互通")),
    SettingsEntryLabel("header_google_settings", tr("Google", "Google")),
    SettingsEntryLabel("xiao_mi_hyperos_ai", tr("小米澎湃AI", "小米澎湃AI")),
    SettingsEntryLabel("system_apps_updater", tr("系统应用更新", "系统应用更新")),
)

@Composable
private fun SettingsEntryLabelsPage(
    settingsApp: SettingsAppPreferences,
    update: ((SettingsAppPreferences) -> SettingsAppPreferences) -> Unit,
    back: () -> Unit,
) = AppPage(tr("settingsEntryLabels", "自定义系统设置入口文案"), back, restartScopes = setOf(ScopeApplication.SETTINGS)) { padding, scroll ->
    var editing by remember { mutableStateOf<SettingsEntryLabel?>(null) }
    AppList(padding, scroll) {
        item {
            Group(tr("settingsEntryLabelsGroup", "设置入口文案")) {
                settingsEntryLabelCatalog().forEach { entry ->
                    ArrowPreference(
                        title = entry.title,
                        summary = settingsApp.entryLabels[entry.id]?.takeIf { it.isNotBlank() }
                            ?: tr("默认", "默认"),
                        onClick = { editing = entry },
                    )
                }
            }
        }
    }
    editing?.let { entry ->
        DeviceProfileFieldDialog(
            title = entry.title,
            current = settingsApp.entryLabels[entry.id].orEmpty(),
            inputLabel = tr("settingsEntryLabelsInputHint", "留空使用系统默认"),
            onDismiss = { editing = null },
            onSave = { value ->
                val trimmed = value.trim().take(MAX_SETTINGS_APP_ENTRY_TITLE_LENGTH)
                update { current ->
                    current.copy(
                        entryLabels = if (trimmed.isEmpty()) {
                            current.entryLabels - entry.id
                        } else {
                            current.entryLabels + (entry.id to trimmed)
                        },
                    )
                }
                editing = null
            },
        )
    }
}

@Composable
private fun DeviceProfileEditor(
    profile: DeviceProfileSettings,
    update: ((DeviceProfileSettings) -> DeviceProfileSettings) -> Unit,
    openPresets: () -> Unit,
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
        tr("认证型号", "认证型号") to profile.detailModel,
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
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                ArrowPreference(title = tr("预设库", "预设库"), onClick = openPresets)
            }
        }
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
                                tr("认证型号", "认证型号") -> p.copy(detailModel = v)
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
private fun ColorModeAssetRows(
    appearance: SettingsAppearanceSettings,
    slot: String,
    label: String,
    onAction: (String, Boolean, Boolean) -> Unit,
) {
    listOf(false to tr("浅色", "浅色"), true to tr("深色", "深色")).forEach { (dark, mode) ->
        val key = colorModeAssetKey(slot, dark)
        val imported = appearance.colorModeAssetMimes[key].orEmpty().isNotBlank()
        ArrowPreference(
            title = "$mode $label - ${tr("导入", "导入")}",
            summary = if (imported) tr("已导入", "已导入") else tr("未导入", "未导入"),
            onClick = { onAction(slot, dark, true) },
        )
        ArrowPreference(
            title = "$mode $label - ${tr("清除", "清除")}",
            summary = if (imported) tr("清除当前素材", "清除当前素材") else tr("无素材", "无素材"),
            onClick = { onAction(slot, dark, false) },
        )
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
    onColorModeAssetAction: (String, Boolean, Boolean) -> Unit,
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
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
OverlayDropdownPreference(
                    title = tr("我的设备界面样式", "我的设备界面样式"),
                    items = listOf(tr("系统默认", "系统默认"), tr("样式1 (来自酷安@Mr_Bocchi)", "样式1 (来自酷安@Mr_Bocchi)"), tr("样式2", "样式2")),
                    selectedIndex = style,
                    onSelectedIndexChange = selectStyle,
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                SwitchPreference(
                    title = tr("按颜色模式使用独立素材", "按颜色模式使用独立素材"),
                    summary = tr("开启后分别使用浅色和深色素材；关闭时保持当前素材不变", "开启后分别使用浅色和深色素材；关闭时保持当前素材不变"),
                    checked = appearance.colorModeAssetsEnabled,
                    onCheckedChange = { enabled -> update { it.copy(colorModeAssetsEnabled = enabled) } },
                )
            }
        }
        if (style == DEVICE_INTERFACE_STYLE_SYSTEM) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
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
                    if (appearance.colorModeAssetsEnabled) ColorModeAssetRows(appearance, APPEARANCE_SLOT_DEVICE_IMAGE, tr("机型图片", "机型图片"), onColorModeAssetAction)
                    else {
                        ArrowPreference(title = tr("导入机型图片", "导入机型图片"), summary = appearance.tutorialCardImageMime.ifBlank { tr("未导入", "未导入") }, onClick = onPickStyle1Image)
                        ArrowPreference(title = tr("清除机型图片", "清除机型图片"), summary = if (appearance.tutorialCardImageMime.isBlank()) tr("无图片", "无图片") else tr("已导入", "已导入"), onClick = onClearStyle1Image)
                    }
                    EditableSliderPreference(
                        value = appearance.tutorialCardImageScale.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardImageScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("机型图片缩放", "机型图片缩放"),
                        valueText = "${appearance.tutorialCardImageScale}%",
                        valueRange = 40f..200f,
                        steps = 159,
                        defaultValue = 100f,
                    )
                    EditableSliderPreference(
                        value = appearance.tutorialCardImageLogoSpacing.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardImageLogoSpacing = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("机型图片与LOGO间距", "机型图片与LOGO间距"),
                        valueText = "${appearance.tutorialCardImageLogoSpacing}%",
                        valueRange = -120f..120f,
                        steps = 239,
                        defaultValue = 0f,
                    )
                }
            }
            item {
                Group(tr("背景图片", "背景图片")) {
                    if (appearance.colorModeAssetsEnabled) ColorModeAssetRows(appearance, APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND, tr("背景图片", "背景图片"), onColorModeAssetAction)
                    else {
                        ArrowPreference(title = tr("导入背景图片", "导入背景图片"), summary = appearance.tutorialCardBackgroundMime.ifBlank { tr("未导入", "未导入") }, onClick = onPickStyle1Background)
                        ArrowPreference(title = tr("清除背景图片", "清除背景图片"), summary = if (appearance.tutorialCardBackgroundMime.isBlank()) tr("无图片", "无图片") else tr("已导入", "已导入"), onClick = onClearStyle1Background)
                    }
                EditableSliderPreference(
                    value = appearance.tutorialCardBackgroundBlur,
                    onValueChange = { value -> update { it.copy(tutorialCardBackgroundBlur = value) } },
                    onValueChangeFinished = {},
                    title = tr("背景图片模糊度", "背景图片模糊度"),
                    valueText = String.format(Locale.US, "%.2fdp", appearance.tutorialCardBackgroundBlur),
                    valueRange = 0f..25f,
                    steps = 2499,
                    defaultValue = 0f,
                    enabled = true,
                )
                EditableSliderPreference(
                    value = appearance.tutorialCardBackgroundHorizontalOffset.toFloat(),
                    onValueChange = { value -> update { it.copy(tutorialCardBackgroundHorizontalOffset = value.toInt()) } },
                    onValueChangeFinished = {},
                    title = tr("背景图片左右偏移", "背景图片左右偏移"),
                    valueText = "${appearance.tutorialCardBackgroundHorizontalOffset}%",
                    valueRange = -120f..120f,
                    steps = 239,
                    defaultValue = 0f,
                    enabled = true,
                )
                EditableSliderPreference(
                    value = appearance.tutorialCardBackgroundVerticalOffset.toFloat(),
                    onValueChange = { value -> update { it.copy(tutorialCardBackgroundVerticalOffset = value.toInt()) } },
                    onValueChangeFinished = {},
                    title = tr("背景图片上下偏移", "背景图片上下偏移"),
                    valueText = "${appearance.tutorialCardBackgroundVerticalOffset}%",
                    valueRange = -120f..120f,
                    steps = 239,
                    defaultValue = 0f,
                    enabled = true,
                )
                EditableSliderPreference(
                    value = appearance.tutorialCardBackgroundScale.toFloat(),
                    onValueChange = { value -> update { it.copy(tutorialCardBackgroundScale = value.toInt()) } },
                    onValueChangeFinished = {},
                    title = tr("背景图片缩放", "背景图片缩放"),
                    valueText = "${appearance.tutorialCardBackgroundScale}%",
                    valueRange = 40f..200f,
                    steps = 159,
                    defaultValue = 100f,
                    enabled = true,
                )
                }
            }
            item {
                Group(tr("logo", "LOGO")) {
                    if (appearance.colorModeAssetsEnabled) ColorModeAssetRows(appearance, APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO, "LOGO", onColorModeAssetAction)
                    else {
                        ArrowPreference(title = tr("导入LOGO", "导入LOGO"), summary = appearance.tutorialCardLogoMime.ifBlank { tr("未导入", "未导入") }, onClick = onPickStyle1Logo)
                        ArrowPreference(title = tr("清除LOGO", "清除LOGO"), summary = if (appearance.tutorialCardLogoMime.isBlank()) tr("无LOGO", "无LOGO") else tr("已导入", "已导入"), onClick = onClearStyle1Logo)
                    }
                    EditableSliderPreference(
                        value = appearance.tutorialCardLogoScale.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardLogoScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("LOGO缩放", "LOGO缩放"),
                        valueText = "${appearance.tutorialCardLogoScale}%",
                        valueRange = 40f..200f,
                        steps = 159,
                        defaultValue = 100f,
                    )
                    EditableSliderPreference(
                        value = appearance.tutorialCardLogoVerticalOffset.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardLogoVerticalOffset = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("LOGO上下偏移", "LOGO上下偏移"),
                        valueText = "${appearance.tutorialCardLogoVerticalOffset}%",
                        valueRange = -120f..120f,
                        steps = 239,
                        defaultValue = 0f,
                    )
                }
            }
            item {
                Group(tr("底部标识", "底部标识")) {
                    EditableSliderPreference(
                        value = appearance.tutorialCardTextSpacing.toFloat(),
                        onValueChange = { value -> update { it.copy(tutorialCardTextSpacing = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("底部Logo与文案间距", "底部Logo与文案间距"),
                        valueText = "${appearance.tutorialCardTextSpacing}%",
                        valueRange = -120f..120f,
                        steps = 239,
                        defaultValue = 0f,
                    )
                    TutorialCardTextField(tr("署名", "署名"), appearance.tutorialCardAuthor) { value -> update { it.copy(tutorialCardAuthor = value) } }
                }
            }
        }
        if (style == DEVICE_INTERFACE_STYLE_TWO) {
            item {
                Group(tr("机型图片", "机型图片")) {
                    if (appearance.colorModeAssetsEnabled) ColorModeAssetRows(appearance, APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE, tr("机型图片", "机型图片"), onColorModeAssetAction)
                    else {
                        ArrowPreference(title = tr("导入机型图片", "导入机型图片"), summary = appearance.style2ImageMime.ifBlank { tr("未导入", "未导入") }, onClick = onPickStyle2Image)
                        ArrowPreference(title = tr("清除机型图片", "清除机型图片"), summary = if (appearance.style2ImageMime.isBlank()) tr("无图片", "无图片") else tr("已导入", "已导入"), onClick = onClearStyle2Image)
                    }
                    EditableSliderPreference(
                        value = appearance.style2ImageScale.toFloat(),
                        onValueChange = { value -> update { it.copy(style2ImageScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("机型图片缩放", "机型图片缩放"),
                        valueText = "${appearance.style2ImageScale}%",
                        valueRange = 40f..200f,
                        steps = 159,
                        defaultValue = 100f,
                    )
                }
            }
            item {
                Group(tr("背景图片", "背景图片")) {
                    if (appearance.colorModeAssetsEnabled) ColorModeAssetRows(appearance, APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND, tr("背景图片", "背景图片"), onColorModeAssetAction)
                    else {
                        ArrowPreference(title = tr("导入背景图片", "导入背景图片"), summary = appearance.style2BackgroundMime.ifBlank { tr("未导入", "未导入") }, onClick = onPickStyle2Background)
                        ArrowPreference(title = tr("清除背景图片", "清除背景图片"), summary = if (appearance.style2BackgroundMime.isBlank()) tr("无背景图", "无背景图") else tr("已导入", "已导入"), onClick = onClearStyle2Background)
                    }
                    EditableSliderPreference(
                        value = appearance.style2BackgroundBlur,
                        onValueChange = { value -> update { it.copy(style2BackgroundBlur = value) } },
                        onValueChangeFinished = {},
                        title = tr("背景图片模糊度", "背景图片模糊度"),
                        valueText = String.format(Locale.US, "%.2fdp", appearance.style2BackgroundBlur),
                        valueRange = 0f..25f,
                        steps = 2499,
                        defaultValue = 0f,
                    )
                    EditableSliderPreference(
                        value = appearance.style2BackgroundHorizontalOffset.toFloat(),
                        onValueChange = { value -> update { it.copy(style2BackgroundHorizontalOffset = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("背景图片左右偏移", "背景图片左右偏移"),
                        valueText = "${appearance.style2BackgroundHorizontalOffset}%",
                        valueRange = -120f..120f,
                        steps = 239,
                        defaultValue = 0f,
                    )
                    EditableSliderPreference(
                        value = appearance.style2BackgroundVerticalOffset.toFloat(),
                        onValueChange = { value -> update { it.copy(style2BackgroundVerticalOffset = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("背景图片上下偏移", "背景图片上下偏移"),
                        valueText = "${appearance.style2BackgroundVerticalOffset}%",
                        valueRange = -120f..120f,
                        steps = 239,
                        defaultValue = 0f,
                    )
                    EditableSliderPreference(
                        value = appearance.style2BackgroundScale.toFloat(),
                        onValueChange = { value -> update { it.copy(style2BackgroundScale = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("背景图片缩放", "背景图片缩放"),
                        valueText = "${appearance.style2BackgroundScale}%",
                        valueRange = 40f..200f,
                        steps = 159,
                        defaultValue = 100f,
                    )
                }
            }
            item {
                Group(tr("LOGO与版本号", "LOGO与版本号")) {
                    if (appearance.colorModeAssetsEnabled) ColorModeAssetRows(appearance, APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO, "LOGO", onColorModeAssetAction)
                    else {
                        ArrowPreference(title = tr("导入LOGO", "导入LOGO"), summary = appearance.style2LogoMime.ifBlank { tr("未导入", "未导入") }, onClick = onPickStyle2Logo)
                        ArrowPreference(title = tr("清除LOGO", "清除LOGO"), summary = if (appearance.style2LogoMime.isBlank()) tr("无LOGO", "无LOGO") else tr("已导入", "已导入"), onClick = onClearStyle2Logo)
                    }
OverlayDropdownPreference(
                        title = tr("LOGO与版本号对齐方式", "LOGO与版本号对齐方式"),
                        items = listOf(tr("左对齐", "左对齐"), tr("居中对齐", "居中对齐"), tr("右对齐", "右对齐")),
                        selectedIndex = appearance.style2LogoAlignment.coerceIn(0, 2),
                        onSelectedIndexChange = { value -> update { it.copy(style2LogoAlignment = value) } },
                    )
                    EditableSliderPreference(
                        value = appearance.style2LogoVersionSpacing.toFloat(),
                        onValueChange = { value -> update { it.copy(style2LogoVersionSpacing = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("LOGO与版本号行间距", "LOGO与版本号行间距"),
                        valueText = "${appearance.style2LogoVersionSpacing}%",
                        valueRange = -120f..120f,
                        steps = 239,
                        defaultValue = 0f,
                    )
                    EditableSliderPreference(
                        value = appearance.style2LogoHorizontalOffsetForAlignment().toFloat(),
                        onValueChange = { value -> update { current -> current.withStyle2LogoHorizontalOffset(current.style2LogoAlignment, value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("LOGO与版本号左右偏移", "LOGO与版本号左右偏移"),
                        valueText = "${appearance.style2LogoHorizontalOffsetForAlignment()}%",
                        valueRange = -120f..120f,
                        steps = 239,
                        defaultValue = 0f,
                    )
                    EditableSliderPreference(
                        value = appearance.style2LogoVerticalOffsetForAlignment().toFloat(),
                        onValueChange = { value -> update { current -> current.withStyle2LogoVerticalOffset(current.style2LogoAlignment, value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("LOGO与版本号上下偏移", "LOGO与版本号上下偏移"),
                        valueText = "${appearance.style2LogoVerticalOffsetForAlignment()}%",
                        valueRange = -120f..120f,
                        steps = 239,
                        defaultValue = 0f,
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
                        EditableSliderPreference(
                            value = appearance.style2TextScale.toFloat(),
                            onValueChange = { value -> update { it.copy(style2TextScale = value.toInt()) } },
                            onValueChangeFinished = {},
                            title = tr("文案缩放", "文案缩放"),
                            valueText = "${appearance.style2TextScale}%",
                            valueRange = 40f..200f,
                            steps = 159,
                            defaultValue = 100f,
                        )
                        if (!appearance.style2TextIndependent) {
OverlayDropdownPreference(
                                title = tr("显示位置", "显示位置"),
                                items = listOf(tr("LOGO之上", "LOGO之上"), tr("版本号之下", "版本号之下")),
                                selectedIndex = appearance.style2TextPosition.coerceIn(0, 1),
                                onSelectedIndexChange = { value -> update { it.copy(style2TextPosition = value) } },
                            )
                            if (appearance.style2TextPosition == 0) {
                                EditableSliderPreference(
                                    value = appearance.style2TextSpacingAbove.toFloat(),
                                    onValueChange = { value -> update { it.copy(style2TextSpacingAbove = value.toInt()) } },
                                    onValueChangeFinished = {},
                                    title = tr("文案与LOGO行间距", "文案与LOGO行间距"),
                                    valueText = "${appearance.style2TextSpacingAbove}%",
                                    valueRange = -120f..120f,
                                    steps = 239,
                                    defaultValue = 0f,
                                )
                            } else {
                                EditableSliderPreference(
                                    value = appearance.style2TextSpacingBelow.toFloat(),
                                    onValueChange = { value -> update { it.copy(style2TextSpacingBelow = value.toInt()) } },
                                    onValueChangeFinished = {},
                                    title = tr("文案与版本号行间距", "文案与版本号行间距"),
                                    valueText = "${appearance.style2TextSpacingBelow}%",
                                    valueRange = -120f..120f,
                                    steps = 239,
                                    defaultValue = 0f,
                                )
                            }
                        } else {
OverlayDropdownPreference(
                                title = tr("显示位置", "显示位置"),
                                items = listOf(tr("居中", "居中"), tr("左对齐", "左对齐"), tr("右对齐", "右对齐")),
                                selectedIndex = appearance.style2TextAlignment.coerceIn(0, 2),
                                onSelectedIndexChange = { value -> update { it.copy(style2TextAlignment = value) } },
                            )
                            EditableSliderPreference(
                                value = appearance.style2TextVerticalOffsetForAlignment().toFloat(),
                                onValueChange = { value -> update { current -> current.withStyle2TextVerticalOffset(current.style2TextAlignment, value.toInt()) } },
                                onValueChangeFinished = {},
                                title = tr("文案上下偏移", "文案上下偏移"),
                                valueText = "${appearance.style2TextVerticalOffsetForAlignment()}%",
                                valueRange = -120f..120f,
                                steps = 239,
                                defaultValue = 0f,
                            )
                            EditableSliderPreference(
                                value = appearance.style2TextHorizontalOffsetForAlignment().toFloat(),
                                onValueChange = { value -> update { current -> current.withStyle2TextHorizontalOffset(current.style2TextAlignment, value.toInt()) } },
                                onValueChangeFinished = {},
                                title = tr("文案左右偏移", "文案左右偏移"),
                                valueText = "${appearance.style2TextHorizontalOffsetForAlignment()}%",
                                valueRange = -120f..120f,
                                steps = 239,
                                defaultValue = 0f,
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
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
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
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(
                        title = tr("启用自定义背景", "启用自定义背景"),
                        checked = enabled,
                        onCheckedChange = { value -> change { if (home) it.copy(homeEnabled = value) else it.copy(deviceEnabled = value) } },
                    )
                    EditableSliderPreference(
                        value = blur.toFloat(),
                        onValueChange = { value -> change { if (home) it.copy(homeBlur = value) else it.copy(deviceBlur = value) } },
                        onValueChangeFinished = {},
                        title = tr("模糊度", "模糊度"),
                        valueText = String.format(Locale.US, "%.2f", blur),
                        valueRange = 0f..20f,
                        steps = 1999,
                        defaultValue = 0f,
                        enabled = enabled,
                    )
                    EditableSliderPreference(
                        value = opacity.toFloat(),
                        onValueChange = { value -> change { if (home) it.copy(homeOpacity = value.toInt()) else it.copy(deviceOpacity = value.toInt()) } },
                        onValueChangeFinished = {},
                        title = tr("不透明度", "不透明度"),
                        valueText = "$opacity%",
                        valueRange = 0f..100f,
                        steps = 99,
                        defaultValue = 100f,
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
    inputLabel: String? = null,
    validationError: ((String) -> String?)? = null,
) {
    var value by remember(title, current) { mutableStateOf(current) }
    val error = validationError?.invoke(value.trim())
    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            TextField(
                value = value,
                onValueChange = { value = it.take(256) },
                label = inputLabel ?: tr("参数值", "参数值"),
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
                    enabled = error == null,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("保存", "保存")) }
            }
            if (error != null) Text(error, color = ComposeColor(0xFFE05353), style = MiuixTheme.textStyles.body2)
        }
    }
}

@Composable
private fun ServiceCard(service: XposedService?, monetEnabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val online = service != null
    val cardColor = when {
        !online -> ComposeColor(0xFFF9B9B9)
        monetEnabled -> MiuixTheme.colorScheme.primaryContainer
        else -> ComposeColor(0xFFADE895)
    }
    val contentColor = when {
        !online || !monetEnabled -> ComposeColor.Black
        else -> MiuixTheme.colorScheme.onPrimaryContainer
    }
    val iconColor = when {
        !online -> ComposeColor(0xFF980000).copy(alpha = .63f)
        monetEnabled -> MiuixTheme.colorScheme.primary.copy(alpha = .74f)
        else -> ComposeColor(0xFF01A01B).copy(alpha = .74f)
    }
    // Both states share one height; only their color, copy, and illustration change.
    val cardRatio = 2780f / 980f
    val moduleVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0),
            ).versionName
        }.getOrNull()?.takeIf(String::isNotBlank) ?: BuildConfig.VERSION_NAME
    }
    val runtimeInfo by produceState<HookFrameworkInfo?>(initialValue = null, service) {
        value = withContext(Dispatchers.IO) { readHookFrameworkInfo(service) }
    }
    Card(
        Modifier.fillMaxWidth().aspectRatio(cardRatio),
        cornerRadius = 22.5.dp,
        colors = CardDefaults.defaultColors(cardColor.copy(alpha = .43f)),
        insideMargin = PaddingValues(0.dp),
        onClick = { scope.launch { openHookFrameworkManager(context) } },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(22.5.dp))) {
            val horizontalPadding = maxWidth * .050f
            val verticalPadding = maxWidth * .050f
            val cardWidth = maxWidth
            val title = tr(
                if (online) "framework_connected_title" else "framework_disconnected_title",
                if (online) "已连接" else "未连接",
            )
            val subtitleStyle = MiuixTheme.textStyles.body2.copy(
                fontWeight = FontWeight.SemiBold,
                lineHeight = 16.sp,
            )
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontalPadding, verticalPadding),
            ) {
                if (online) {
                    Column(
                        modifier = Modifier.fillMaxWidth(.72f),
                        verticalArrangement = Arrangement.spacedBy(cardWidth * .012f),
                    ) {
                        Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, color = contentColor)
                        Text(
                            if (runtimeInfo == null) tr("加载中......", "加载中......") else hookFrameworkSummary(runtimeInfo),
                            style = subtitleStyle,
                            color = contentColor,
                        )
                    }
                } else {
                    Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold, color = contentColor)
                    Text(
                        "${tr("HyperChanger", "HyperChanger")} $moduleVersion",
                        modifier = Modifier.padding(top = cardWidth * .012f),
                        style = subtitleStyle,
                        color = contentColor,
                    )
                }
            }
            Image(
                painter = painterResource(if (online) R.drawable.lsposed_status_success else R.drawable.lsposed_status_error),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .fillMaxWidth(if (online) .3205f else .2953f)
                    .aspectRatio(if (online) 891f / 787f else 821f / 822f),
                contentScale = ContentScale.FillBounds,
                colorFilter = ColorFilter.tint(iconColor),
            )
        }
    }
}

@Composable
internal fun Group(
    title: String,
    insideMargin: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        SmallTitle(title, insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
        Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = insideMargin) { content() }
    }
}

@Composable
private fun Entry(title: String, enabled: Boolean = true, click: () -> Unit) {
    Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) { ArrowPreference(title = title, onClick = click, enabled = enabled) }
}

@Composable
private fun Detail(
    page: PageId,
    settings: HookSettings,
    lockscreenCapsuleSettings: LockscreenCapsuleSettings,
    cameras: CameraSettings,
    deviceProfile: DeviceProfileSettings,
    appearance: SettingsAppearanceSettings,
    musicWhitelist: Set<String>,
    dockSettings: DockSettings,
    homeRecentsSettings: HomeRecentsSettings,
    homeFolderSettings: HomeFolderSettings,
    homeMaterialSettings: HomeMaterialSettings,
    airDropSettings: AirDropSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    updateLockscreenCapsule: ((LockscreenCapsuleSettings) -> LockscreenCapsuleSettings) -> Unit,
    updateDock: ((DockSettings) -> DockSettings) -> Unit,
    updateHomeRecents: ((HomeRecentsSettings) -> HomeRecentsSettings) -> Unit,
    updateHomeFolder: ((HomeFolderSettings) -> HomeFolderSettings) -> Unit,
    updateHomeMaterial: ((HomeMaterialSettings) -> HomeMaterialSettings) -> Unit,
    updateCamera: ((CameraSettings) -> CameraSettings) -> Unit,
    updateDeviceProfile: ((DeviceProfileSettings) -> DeviceProfileSettings) -> Unit,
    updateAppearance: ((SettingsAppearanceSettings) -> SettingsAppearanceSettings) -> Unit,
    updateMusicWhitelist: (Set<String>) -> Unit,
    presetActions: ShadePresetActions,
    updateAirDrop: (((AirDropSettings) -> AirDropSettings) -> Unit),
    settingsApp: SettingsAppPreferences,
    appThemeStore: AppThemeSettingsStore,
    service: XposedService?,
    superXiaoAi: SuperXiaoAiPreferences,
    systemUpdate: SystemUpdatePreferences,
    updateSettingsApp: ((SettingsAppPreferences) -> SettingsAppPreferences) -> Unit,
    updateSuperXiaoAi: ((SuperXiaoAiPreferences) -> SuperXiaoAiPreferences) -> Unit,
    updateSystemUpdate: ((SystemUpdatePreferences) -> SystemUpdatePreferences) -> Unit,
    onEnterOobe: () -> Unit,
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
    onColorModeAssetAction: (String, Boolean, Boolean) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    screenRecorder: ScreenRecorderSettings,
    updateScreenRecorder: (((ScreenRecorderSettings) -> ScreenRecorderSettings) -> Unit),
    suggestedRestartTargets: Set<ScopeApplication>,
    onRestartedTargets: (Set<ScopeApplication>) -> Unit,
    openPage: (PageId) -> Unit,
    back: () -> Unit
) {
    when (page) {
        PageId.MODULE_SAFETY -> ModuleSafetyPage(
            openLogs = { openPage(PageId.MODULE_LOGS) },
            back = back,
            onEnterOobe = onEnterOobe,
        )
        PageId.MODULE_LOGS -> ModuleLogsPage(back)
        PageId.SHADE -> Shade(settings, update, presetActions, openPage, back)
        PageId.DESKTOP -> Desktop(dockSettings, updateDock, homeRecentsSettings, updateHomeRecents, homeFolderSettings, updateHomeFolder, homeMaterialSettings, updateHomeMaterial, openPage, back)
        PageId.DESKTOP_MATERIAL -> HomeMaterialPage(back)
        PageId.SHADE_PRESETS -> ShadePresets(settings, update, presetActions, back)
        PageId.SOFT_GLASS_PARAMETERS -> SoftGlassParametersPage(openPage, back)
        PageId.SOFT_GLASS_PRESETS -> SoftGlassPresetsPage(back)
        PageId.GESTURE_HANDLE_STYLE -> GestureHandleStylePage(back)
        PageId.ISLAND -> Island(openPage, onRequestNotificationPermission, back)
        PageId.ISLAND_COLLAPSED -> IslandCollapsedPage(back)
        PageId.ISLAND_EXPANDED -> IslandExpandedPage(openPage, back)
        PageId.ISLAND_EXPANDED_BACKGROUND -> IslandExpandedBackgroundPage(back)
        PageId.IOS_NOTIFICATION_CENTER -> IosNotificationCenterPage(openPage, back)
        PageId.IOS_NOTIFICATION_HEADER_SETTINGS -> IosNotificationHeaderSettingsPage(back)
        PageId.IOS_NOTIFICATION_CLOCK_GLASS -> IosNotificationClockGlassPage(back)
        PageId.UNLOCK_ISLAND -> UnlockIslandPage(back)
        PageId.NOTIFICATION_RESTRICTIONS -> NotificationRestrictions(back)
        PageId.NOTIFICATION_MEDIA_CARD -> NotificationMediaCard(back)
        PageId.CONTROL_CENTER_TOP_BUTTONS -> ControlCenterTopButtonsPage(back)
        PageId.STATUS -> Status(settings, update, openPage, back)
        PageId.STATUS_ADJUSTMENT -> StatusAdjustment(settings, update, back)
        PageId.STATUS_SIGNAL_CUSTOMIZATION -> StatusSignalCustomization(settings, update, openPage, back)
        PageId.STATUS_SIGNAL_TUNING -> StatusSignalTuning(settings, update, back)
        PageId.CONTROL -> Control(back)
        PageId.LOCK -> Lock(settings, lockscreenCapsuleSettings, update, openPage, back)
        PageId.CUSTOM_DEPTH_WALLPAPER -> CustomDepthWallpaperPage(back)
        PageId.LOCKSCREEN_MUSIC -> MusicLockscreenPage(settings, update, openPage, back)
        PageId.LOCKSCREEN_CAPSULE -> LockscreenCapsule(lockscreenCapsuleSettings, updateLockscreenCapsule, back)
        PageId.LOCKSCREEN_SHORTCUT_BACKGROUND -> LockscreenShortcutBackgroundPage(back)
        PageId.LIFT_TO_WAKE_SCHEDULE -> LiftToWakeSchedulePage(back)
        PageId.LOCKSCREEN_WIDGET_EDITOR -> LockscreenWidgetEditor(settings, update, back)
        PageId.LOCKSCREEN_WIDGET_BACKGROUND -> LockscreenWidgetBackgroundSettings(settings, update, back)
        PageId.LYRIC_LIBRARY -> LyricLibraryPage(back)
        PageId.RASTER_WALLPAPER -> RasterWallpaper(settings, update, onPickRasterImages, onApplyRasterWallpaper, back)
        PageId.SUPER_XIAOAI -> SuperXiaoAi(settings, superXiaoAi, updateSuperXiaoAi, openPage, back)
        PageId.CAMERA -> Camera(cameras, updateCamera, openPage, back)
        PageId.CAMERA_PALETTE -> CameraPalette(cameras, updateCamera, back)
        PageId.SYSTEM_UPDATE -> SystemUpdate(systemUpdate, updateSystemUpdate, back)
        PageId.SYSTEM_FRAMEWORK -> FrameworkPage(back)
    PageId.SYSTEM_SETTINGS -> SystemSettings(settingsApp, updateSettingsApp, deviceProfile, updateDeviceProfile, appearance, updateAppearance, openPage, onPickAppearanceLogo, onClearAppearanceLogo, back)
        PageId.DEVICE_PROFILE -> DeviceProfileEditor(deviceProfile, updateDeviceProfile, { openPage(PageId.DEVICE_PROFILE_PRESETS) }, back)
        PageId.DEVICE_PROFILE_PRESETS -> DeviceProfilePresetsPage(deviceProfile, updateDeviceProfile, { openPage(PageId.DEVICE_PROFILE_CLOUD_PRESETS) }, back)
        PageId.DEVICE_PROFILE_CLOUD_PRESETS -> CloudDeviceProfilePresetsPage(updateDeviceProfile, back)
        PageId.SETTINGS_ENTRY_LABELS -> SettingsEntryLabelsPage(settingsApp, updateSettingsApp, back)
        PageId.THEME_AND_COLOR -> ThemeAndColorPage(settings, update, appThemeStore, service, back)
        PageId.SETTINGS_APPEARANCE_HOME -> SettingsAppearancePage(APPEARANCE_SLOT_HOME, appearance, updateAppearance, onPickAppearanceHome, onClearAppearanceHome, back)
        PageId.SETTINGS_APPEARANCE_DEVICE -> SettingsAppearancePage(APPEARANCE_SLOT_DEVICE, appearance, updateAppearance, onPickAppearanceDevice, onClearAppearanceDevice, back)
            PageId.TUTORIAL_DEVICE_CARD -> TutorialDeviceCardSettings(appearance, updateAppearance, onPickAppearanceLogo, onClearAppearanceLogo, onPickTutorialDeviceImage, onClearTutorialDeviceImage, onPickCustomDeviceLogo, onClearCustomDeviceLogo, onPickStyle1UpdateBackground, onClearStyle1UpdateBackground, onPickStyle2DeviceImage, onClearStyle2DeviceImage, onPickStyle2DeviceLogo, onClearStyle2DeviceLogo, onPickStyle2UpdateBackground, onClearStyle2UpdateBackground, onColorModeAssetAction, back)
        PageId.ABOUT -> About(back, openPage, appThemeStore.settings.monetEnabled)
        PageId.LEGAL_DISCLAIMER -> LegalDocument(PageId.LEGAL_DISCLAIMER, back)
        PageId.LEGAL_PRIVACY -> LegalDocument(PageId.LEGAL_PRIVACY, back)
        PageId.LEGAL_USER_AGREEMENT -> LegalDocument(PageId.LEGAL_USER_AGREEMENT, back)
        PageId.SOFTWARE_UPDATE -> SoftwareUpdate(openPage, back)
        PageId.UPDATE_LOG -> UpdateLog(back)
        PageId.DISCLAIMER -> Disclaimer(back, captcha)
        PageId.LICENSE -> License(back)
        PageId.LANGUAGE -> LanguagePage(back)
        PageId.DONATE -> Donate(back)
        PageId.OPEN -> OpenSource(back)
        PageId.CONTRIBUTORS -> Contributors(back)
        PageId.REAR_SCREEN -> RearScreen(settings, update, musicWhitelist, updateMusicWhitelist, openPage, back)
        PageId.REAR_SCREEN_SHELL -> RearScreenshotShellPage(back)
        PageId.REAR_MINI_PLAYER -> RearMiniPlayerPage(back)
        PageId.REAR_CUSTOM_CENTER -> CustomRearScreenCenter(back)
        PageId.REAR_MUSIC_APPS -> RearMusicApps(musicWhitelist, updateMusicWhitelist, back)
        PageId.REAR_SWIPE_APP -> RearSwipeAppPicker(back)
        PageId.OTHER -> OtherPage(airDropSettings, updateAirDrop, screenRecorder, updateScreenRecorder, openPage, back)
        PageId.ENVIRONMENT_DETECTION -> EnvironmentDetectionPage(back)
        PageId.CONTACTS_DIALER -> ContactsDialerPage({ openPage(PageId.CONTACTS_DIALER_BACKGROUND) }, back)
        PageId.CONTACTS_DIALER_BACKGROUND -> ContactsDialerBackgroundPage(back)
        PageId.PORTAL -> PortalPage({ openPage(PageId.PORTAL_MATERIAL) }, back)
        PageId.PORTAL_MATERIAL -> PortalMaterialPage(back)
        PageId.APP_NAVIGATION -> AppNavigationPage(back)
        PageId.RESTART_SCOPES -> RestartScopesPage(
            suggestedTargets = suggestedRestartTargets,
            onRestartedTargets = onRestartedTargets,
            back = back,
        )
        PageId.SIMULATE_ISLAND -> IslandSimulationPage(onRequestNotificationPermission, back)
        PageId.SIMULATE_MEDIA_NOTIFICATION -> SimulateMediaNotificationPage(
            musicWhitelist,
            updateMusicWhitelist,
            onRequestNotificationPermission,
            back,
        )
    }
}

@Composable
private fun NotificationSimulationDialog(
    onRequestNotificationPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var title by remember {
        mutableStateOf(NotificationSimulator.normalTitle(context))
    }
    var content by remember {
        mutableStateOf(NotificationSimulator.normalContent(context))
    }
    var normalNotificationCount by remember { mutableIntStateOf(1) }
    var normalIntervalIndex by remember { mutableIntStateOf(0) }
    var delayFirstNormalNotification by remember { mutableStateOf(false) }
    var hasNormalNotificationImage by remember {
        mutableStateOf(NotificationSimulator.hasNormalImage(context))
    }
    val normalIntervalsMs = remember { longArrayOf(1_000L, 3_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L, 60_000L) }
    val pickNormalNotificationImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: error(tr("notification_image_invalid", "无法读取通知图片"))
        }.onSuccess { image ->
            NotificationSimulator.saveNormalImage(context, image)
            hasNormalNotificationImage = true
        }.onFailure {
            Toast.makeText(context, tr("notification_image_invalid", "无法读取通知图片"), Toast.LENGTH_SHORT).show()
        }
    }

    fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun sendNotification() {
        if (title.isBlank() || content.isBlank()) {
            Toast.makeText(context, tr("notification_content_required", "请输入通知标题和内容"), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasNotificationPermission()) {
            Toast.makeText(context, tr("notification_permission_required", "请先允许通知权限"), Toast.LENGTH_SHORT).show()
            onRequestNotificationPermission()
            return
        }
        val trimmedTitle = title.trim()
        val trimmedContent = content.trim()
        val sent = run {
            NotificationSimulator.saveNormalTitle(context, trimmedTitle)
            NotificationSimulator.saveNormalContent(context, trimmedContent)
            NotificationSimulator.sendNormalBatch(
                context,
                trimmedTitle,
                trimmedContent,
                normalNotificationCount,
                normalIntervalsMs[normalIntervalIndex],
                delayFirstNormalNotification,
            )
        }
        Toast.makeText(
            context,
            tr(if (sent) "notification_sent" else "notification_send_failed", if (sent) "通知已发送" else "通知发送失败"),
            Toast.LENGTH_SHORT,
        ).show()
        if (sent) onDismiss()
    }

    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        includeImePadding = false,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                tr("normal_notification_simulation", "普通通知模拟"),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
            )
            TextField(
                value = title,
                onValueChange = { title = it.take(100) },
                label = tr("notification_title", "通知标题"),
                useLabelAsPlaceholder = true,
                singleLine = true,
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = content,
                onValueChange = { content = it.take(500) },
                label = tr("notification_content", "通知内容"),
                useLabelAsPlaceholder = true,
                singleLine = false,
                cornerRadius = 24.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("notification_count", "模拟通知数量"),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    normalNotificationCount.toString(),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                )
            }
            Slider(
                value = normalNotificationCount.toFloat(),
                onValueChange = { normalNotificationCount = it.roundToInt().coerceIn(1, 10) },
                valueRange = 1f..10f,
                steps = 8,
                showKeyPoints = true,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("notification_interval", "发送间隔"),
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${normalIntervalsMs[normalIntervalIndex] / 1_000}s",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                )
            }
            Slider(
                value = normalIntervalIndex.toFloat(),
                onValueChange = { normalIntervalIndex = it.roundToInt().coerceIn(normalIntervalsMs.indices) },
                valueRange = 0f..normalIntervalsMs.lastIndex.toFloat(),
                steps = normalIntervalsMs.size - 2,
                showKeyPoints = true,
                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    tr("notification_delay_first", "首条通知在设定的发送间隔结束后发送"),
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.body1,
                )
                Switch(
                    checked = delayFirstNormalNotification,
                    onCheckedChange = { delayFirstNormalNotification = it },
                )
            }
            GlassDialogButton(
                onClick = {
                    if (hasNormalNotificationImage) {
                        NotificationSimulator.clearNormalImage(context)
                        hasNormalNotificationImage = false
                    } else {
                        pickNormalNotificationImage.launch(arrayOf("image/*"))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    tr(
                        if (hasNormalNotificationImage) "notification_image_uploaded" else "notification_image_upload",
                        if (hasNormalNotificationImage) "上传通知图片（已上传）" else "上传通知图片",
                    ),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("cancel", "取消")) }
                GlassDialogButton(
                    onClick = ::sendNotification,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("send", "发送")) }
            }
        }
    }
}

@Composable
private fun SimulateMediaNotificationPage(
    musicWhitelist: Set<String>,
    updateMusicWhitelist: (Set<String>) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    back: () -> Unit,
) = AppPage(tr("simulate_media_notification", "媒体通知模拟"), back) { padding, scroll ->
    val context = LocalContext.current
    var title by remember { mutableStateOf(MediaNotificationSimulator.title(context)) }
    var artist by remember { mutableStateOf(MediaNotificationSimulator.artist(context)) }
    var playing by remember { mutableStateOf(MediaNotificationSimulator.isPlaying(context)) }
    var positionMs by remember { mutableLongStateOf(MediaNotificationSimulator.position(context)) }
    var isSeeking by remember { mutableStateOf(false) }
    var cover by remember { mutableStateOf(MediaNotificationSimulator.cover(context)) }
    var showMetadataDialog by remember { mutableStateOf(false) }
    var titleDraft by remember { mutableStateOf(title) }
    var artistDraft by remember { mutableStateOf(artist) }

    LaunchedEffect(Unit) {
        while (true) {
            playing = MediaNotificationSimulator.isPlaying(context)
            if (!isSeeking) positionMs = MediaNotificationSimulator.position(context)
            delay(500)
        }
    }

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: error(tr("media_notification_cover_invalid", "无法读取封面图片"))
        }.onSuccess { selected ->
            MediaNotificationSimulator.saveCover(context, selected)
            cover = selected
            Toast.makeText(context, tr("media_notification_cover_saved", "封面已更新"), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, tr("media_notification_cover_invalid", "无法读取封面图片"), Toast.LENGTH_SHORT).show()
        }
    }

    fun hasNotificationPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun sendMediaNotification() {
        if (!hasNotificationPermission()) {
            Toast.makeText(context, tr("notification_permission_required", "请先允许通知权限"), Toast.LENGTH_SHORT).show()
            onRequestNotificationPermission()
            return
        }
        val sent = MediaNotificationSimulator.send(
            context,
            title.trim(),
            artist.trim(),
            playing,
            tr("media_notification_channel", "媒体通知模拟"),
            tr("media_notification_previous", "上一首"),
            tr("media_notification_play", "播放"),
            tr("media_notification_pause", "暂停"),
            tr("media_notification_next", "下一首"),
        )
        Toast.makeText(
            context,
            tr(
                if (sent) "media_notification_sent" else "media_notification_send_failed",
                if (sent) "媒体通知已发送" else "媒体通知发送失败",
            ),
            Toast.LENGTH_SHORT,
        ).show()
    }

    AppList(padding, scroll, 22) {
        item {
            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.24f)
                        .clip(RoundedCornerShape(28.dp))
                        .background(ComposeColor.White)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        bitmap = cover.asImageBitmap(),
                        contentDescription = tr("media_notification_replace_cover", "替换封面"),
                        modifier = Modifier
                            .fillMaxWidth(0.40f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { pickCover.launch(arrayOf("image/*")) },
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    titleDraft = title
                                    artistDraft = artist
                                    showMetadataDialog = true
                                },
                        ) {
                            Text(
                                title,
                                color = ComposeColor(0xFF171717),
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = FontWeight.Bold,
                                fontSize = 19.76.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                artist,
                                color = ComposeColor(0xFF606060),
                                style = MiuixTheme.textStyles.body2,
                                fontSize = 15.3.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(Modifier.fillMaxWidth()) {
                            MediaProgressTrack(
                                progress = positionMs.toFloat() / MediaNotificationSimulator.DURATION_MS.toFloat(),
                                onSeekStarted = { isSeeking = true },
                                onProgressChange = { progress ->
                                    positionMs = (MediaNotificationSimulator.DURATION_MS * progress).toLong()
                                },
                                onProgressChangeFinished = {
                                    isSeeking = false
                                    MediaNotificationSimulator.updatePosition(context, positionMs)
                                },
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "-${mediaTimeText(MediaNotificationSimulator.DURATION_MS - positionMs)}",
                                    color = ComposeColor(0xFF555555),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    mediaTimeText(MediaNotificationSimulator.DURATION_MS),
                                    color = ComposeColor(0xFF555555),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painterResource(R.drawable.ic_media_notification_previous),
                                contentDescription = tr("media_notification_previous", "上一首"),
                                modifier = Modifier.size(44.dp),
                                colorFilter = ColorFilter.tint(ComposeColor(0xFF222222)),
                            )
                            Image(
                                painterResource(
                                    if (playing) R.drawable.ic_media_notification_pause else R.drawable.ic_media_notification_play,
                                ),
                                contentDescription = tr(
                                    if (playing) "media_notification_pause" else "media_notification_play",
                                    if (playing) "暂停" else "播放",
                                ),
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable {
                                        playing = !playing
                                        MediaNotificationSimulator.updatePlaying(context, playing)
                                    },
                                colorFilter = ColorFilter.tint(ComposeColor(0xFF222222)),
                            )
                            Image(
                                painterResource(R.drawable.ic_media_notification_next),
                                contentDescription = tr("media_notification_next", "下一首"),
                                modifier = Modifier.size(44.dp),
                                colorFilter = ColorFilter.tint(ComposeColor(0xFF222222)),
                            )
                        }
                    }
                }
            }
        }
        item {
            Group(tr("media_notification_preview", "媒体通知")) {
                Text(
                    tr("media_notification_cover_hint", "点击封面替换封面，点击标题和 Artist 编辑信息"),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
                SwitchPreference(
                    title = tr("add_module_to_rear_media_whitelist", "添加模块应用到背屏媒体控件白名单"),
                    checked = context.packageName in musicWhitelist,
                    onCheckedChange = { enabled ->
                        val next = if (enabled) musicWhitelist + context.packageName else musicWhitelist - context.packageName
                        updateMusicWhitelist(next)
                    },
                )
                Box(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 20.dp)) {
                    GlassDialogButton(
                        onClick = ::sendMediaNotification,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(tr("media_notification_send", "发送媒体通知")) }
                }
            }
        }
    }

    WindowDialog(show = showMetadataDialog, onDismissRequest = { showMetadataDialog = false }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                tr("media_notification_edit_metadata", "编辑媒体信息"),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
            )
            TextField(
                value = titleDraft,
                onValueChange = { titleDraft = it.take(100) },
                label = tr("media_notification_title_label", "标题"),
                useLabelAsPlaceholder = true,
                singleLine = true,
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = artistDraft,
                onValueChange = { artistDraft = it.take(100) },
                label = tr("media_notification_artist_label", "Artist"),
                useLabelAsPlaceholder = true,
                singleLine = true,
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    onClick = { showMetadataDialog = false },
                    modifier = Modifier.weight(1f),
                ) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = {
                        if (titleDraft.isNotBlank() && artistDraft.isNotBlank()) {
                            title = titleDraft.trim()
                            artist = artistDraft.trim()
                            MediaNotificationSimulator.saveMetadata(context, title, artist)
                            showMetadataDialog = false
                        }
                    },
                    enabled = titleDraft.isNotBlank() && artistDraft.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("保存", "保存")) }
            }
        }
    }
}

@Composable
private fun MediaProgressTrack(
    progress: Float,
    onSeekStarted: () -> Unit,
    onProgressChange: (Float) -> Unit,
    onProgressChangeFinished: () -> Unit,
) {
    fun updateProgress(x: Float, width: Float) {
        if (width > 0f) onProgressChange((x / width).coerceIn(0f, 1f))
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .pointerInput(onProgressChange, onProgressChangeFinished) {
                detectDragGestures(
                    onDragStart = { offset ->
                        onSeekStarted()
                        updateProgress(offset.x, size.width.toFloat())
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        updateProgress(change.position.x, size.width.toFloat())
                    },
                    onDragEnd = onProgressChangeFinished,
                    onDragCancel = onProgressChangeFinished,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(ComposeColor(0xFFD7D7D7)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(ComposeColor(0xFF222222)),
            )
        }
    }
}

private fun mediaTimeText(milliseconds: Long): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L).toInt()
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun XiaoAiGlassSliders(value: XiaoAiGlassParameters, update: (XiaoAiGlassParameters) -> Unit) {
    ParameterIntSlide(tr("xiaoaiGlassBlur", "模糊强度"), value.blur, 0..200, "%", defaultValue = 100) { update(value.copy(blur = it)) }
    ParameterIntSlide(tr("xiaoaiGlassRefraction", "折射强度"), value.refraction, 0..200, "%", defaultValue = 100) { update(value.copy(refraction = it)) }
    ParameterIntSlide(tr("xiaoaiGlassSoftLight", "柔光强度"), value.softLight, 0..200, "%", defaultValue = 100) { update(value.copy(softLight = it)) }
    ParameterIntSlide(tr("xiaoaiGlassSaturation", "饱和度"), value.saturation, 0..200, "%", defaultValue = 100) { update(value.copy(saturation = it)) }
    ParameterIntSlide(tr("xiaoaiGlassBrightness", "亮度"), value.brightness, 0..200, "%", defaultValue = 100) { update(value.copy(brightness = it)) }
    // No opacity slider: the shader's layer alpha must stay at its native value or the glass layer
    // is dropped entirely. See SuperXiaoAiInputGlassSettings.
}

@Composable
private fun SuperXiaoAi(
    settings: HookSettings,
    superXiaoAi: SuperXiaoAiPreferences,
    update: ((SuperXiaoAiPreferences) -> SuperXiaoAiPreferences) -> Unit,
    openPage: (PageId) -> Unit,
    back: () -> Unit,
) = AppPage(tr("超级小爱与输入法", "超级小爱与输入法"), back, restartScopes = setOf(ScopeApplication.SUPER_XIAOAI, ScopeApplication.SUPER_XIAOAI_IME)) { padding, scroll ->
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val onFloatingChanged = LocalXiaoAiSettingsChanged.current
    val store = remember(context) { SuperXiaoAiInputGlassSettingsStore(context) }
    var floating by remember { mutableStateOf(store.settings) }
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        floating = store.settings
    }
    val updateFloating: ((SuperXiaoAiInputGlassSettings) -> SuperXiaoAiInputGlassSettings) -> Unit = { transform ->
        store.update(service, transform)
        floating = store.settings
        onFloatingChanged()
    }
    AppList(padding, scroll, 28) {
        item {
            Group(tr("xiaoaiFloatingInput", "超级小爱悬浮输入框")) {
                SwitchPreference(
                    title = tr("xiaoaiInputGlass", "为悬浮输入框添加柔光玻璃效果"),
                    checked = floating.enabled,
                    onCheckedChange = { value -> updateFloating { it.copy(enabled = value) } },
                )
            }
        }
        if (floating.enabled) {
            item {
                Group(tr("xiaoaiInputParameters", "超级小爱悬浮输入框柔光玻璃参数")) {
                    XiaoAiGlassSliders(floating.parameters) { value -> updateFloating { it.copy(parameters = value) } }
                }
            }
        }

        item {
            Group(tr("传送门", "传送门")) {
                ArrowPreference(title = tr("传送门", "传送门"), onClick = { openPage(PageId.PORTAL) })
            }
        }
        item {
            Group(tr("\u8f93\u5165\u6cd5\u5916\u89c2", "\u8f93\u5165\u6cd5\u5916\u89c2")) {
                SwitchPreference(
                    title = tr("\u5168\u5c40\u4f7f\u7528\u641c\u7d22\u9875\u5916\u89c2", "\u5168\u5c40\u4f7f\u7528\u641c\u7d22\u9875\u5916\u89c2"),
                    checked = superXiaoAi.globalSearchAppearance,
                    onCheckedChange = { enabled ->
                        update { it.copy(globalSearchAppearance = enabled) }
                    },
                )
            }
        }
        item {
            Group(tr("superXiaoAiRestrictions", "\u8f93\u5165\u6cd5\u529f\u80fd\u9650\u5236")) {
                SwitchPreference(title = tr("superXiaoAiBlacklist", "\u53bb\u9664\u9ed1\u540d\u5355\u8bcd\u5e93\u9650\u5236"), checked = superXiaoAi.blacklistUnblocked, onCheckedChange = { value -> update { it.copy(blacklistUnblocked = value) } })
                SwitchPreference(title = tr("superXiaoAiClipboard", "\u53bb\u9664\u526a\u5207\u677f\u9650\u5236"), checked = superXiaoAi.clipboardUnblocked, onCheckedChange = { value -> update { it.copy(clipboardUnblocked = value) } })
                SwitchPreference(title = tr("superXiaoAiAiSafety", "\u53bb\u9664 AI \u5b89\u5168\u9650\u5236"), checked = superXiaoAi.aiSafetyUnblocked, onCheckedChange = { value -> update { it.copy(aiSafetyUnblocked = value) } })
                SwitchPreference(title = tr("superXiaoAiVoiceSafety", "\u53bb\u9664\u8bed\u97f3\u8f93\u5165\u5b89\u5168\u9650\u5236"), checked = superXiaoAi.voiceSafetyUnblocked, onCheckedChange = { value -> update { it.copy(voiceSafetyUnblocked = value) } })
            }
        }
        item {
            Group(tr("superXiaoAiKeyboardAppearance", "\u952e\u76d8\u5916\u89c2")) {
                OverlayDropdownPreference(title = tr("superXiaoAiKeyboardColorMode", "\u952e\u76d8\u989c\u8272\u6a21\u5f0f"), items = listOf(tr("followSystem", "\u8ddf\u968f\u7cfb\u7edf"), tr("superXiaoAiLightMode", "\u6d45\u8272\u6a21\u5f0f"), tr("superXiaoAiDarkMode", "\u6df1\u8272\u6a21\u5f0f")), selectedIndex = superXiaoAi.keyboardColorMode, onSelectedIndexChange = { value -> update { it.copy(keyboardColorMode = value) } })
                SwitchPreference(title = tr("superXiaoAiKeyboardTuning", "\u952e\u76d8\u5916\u89c2\u53c2\u6570\u8c03\u6574"), checked = superXiaoAi.keyboardStyleEnabled, onCheckedChange = { value -> update { it.copy(keyboardStyleEnabled = value) } })
                if (superXiaoAi.keyboardStyleEnabled) {
                    ParameterDpSlide(tr("superXiaoAiKeyboardCorner", "\u952e\u76d8\u5706\u89d2"), superXiaoAi.keyboardCornerRadius, 0f..48f, defaultValue = 24f) { value -> update { it.copy(keyboardCornerRadius = value) } }
                    ParameterIntSlide(tr("superXiaoAiKeyboardOpacity", "\u952e\u76d8\u80cc\u666f\u4e0d\u900f\u660e\u5ea6"), superXiaoAi.keyboardOpacity, 0..100, "%", defaultValue = 56) { value -> update { it.copy(keyboardOpacity = value) } }
                    ParameterDpSlide(tr("superXiaoAiKeyboardBlur", "\u952e\u76d8\u80cc\u666f\u6a21\u7cca\u5ea6"), superXiaoAi.keyboardBlur, 0f..45f, defaultValue = 15f) { value -> update { it.copy(keyboardBlur = value) } }
                    ParameterIntSlide(tr("superXiaoAiKeyboardHighlight", "\u952e\u76d8\u9876\u90e8\u9ad8\u5149"), superXiaoAi.keyboardHighlight, 0..100, "%", defaultValue = 100) { value -> update { it.copy(keyboardHighlight = value) } }
                    ParameterIntSlide(tr("superXiaoAiKeyboardShadow", "\u952e\u76d8\u9634\u5f71\u5f3a\u5ea6"), superXiaoAi.keyboardShadow, 0..100, "%", defaultValue = 10) { value -> update { it.copy(keyboardShadow = value) } }
                    ParameterDpSlide(tr("superXiaoAiKeyboardStrokeWidth", "\u952e\u76d8\u8fb9\u7f18\u63cf\u8fb9\u5bbd\u5ea6"), superXiaoAi.keyboardStrokeWidth, 0f..4f, defaultValue = 2f) { value -> update { it.copy(keyboardStrokeWidth = value) } }
                    ParameterIntSlide(tr("superXiaoAiKeyboardBottomHighlight", "\u952e\u76d8\u5e95\u90e8\u9ad8\u5149"), superXiaoAi.keyboardBottomHighlight, 0..100, "%", defaultValue = 100) { value -> update { it.copy(keyboardBottomHighlight = value) } }
                }
            }
        }
    }
}

@Composable
private fun SystemUpdate(
    systemUpdate: SystemUpdatePreferences,
    update: ((SystemUpdatePreferences) -> SystemUpdatePreferences) -> Unit,
    back: () -> Unit,
) = AppPage(tr("\u7cfb\u7edf\u66f4\u65b0", "\u7cfb\u7edf\u66f4\u65b0"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UPDATE)) { padding, scroll ->
    var editingVersion by remember { mutableStateOf<SystemUpdateVersionField?>(null) }
    AppList(padding, scroll, 28) {
        item {
            Group(tr("\u66f4\u65b0\u63a7\u5236", "\u66f4\u65b0\u63a7\u5236")) {
                SwitchPreference(
                    title = tr("\u7981\u7528\u7cfb\u7edf\u66f4\u65b0", "\u7981\u7528\u7cfb\u7edf\u66f4\u65b0"),
                    summary = tr("\u963b\u6b62\u5f00\u673a\u3001\u5b9a\u65f6\u548c\u624b\u52a8\u89e6\u53d1\u7684\u7cfb\u7edf\u66f4\u65b0\u68c0\u67e5", "\u963b\u6b62\u5f00\u673a\u3001\u5b9a\u65f6\u548c\u624b\u52a8\u89e6\u53d1\u7684\u7cfb\u7edf\u66f4\u65b0\u68c0\u67e5"),
                    checked = systemUpdate.disabled,
                    onCheckedChange = { enabled -> update { it.copy(disabled = enabled) } },
                )
                SwitchPreference(
                    title = tr("\u79fb\u9664 OTA \u9650\u5236", "\u79fb\u9664 OTA \u9650\u5236"),
                    summary = tr("\u4ec5\u9002\u7528\u4e8e VAB \u8bbe\u5907\uff0c\u4f1a\u8df3\u8fc7 OTA \u6821\u9a8c", "\u4ec5\u9002\u7528\u4e8e VAB \u8bbe\u5907\uff0c\u4f1a\u8df3\u8fc7 OTA \u6821\u9a8c"),
                    checked = systemUpdate.otaLimitRemoved,
                    onCheckedChange = { enabled -> update { it.copy(otaLimitRemoved = enabled) } },
                )
            }
        }
        item {
            Group(tr("\u7248\u672c\u4f2a\u88c5", "\u7248\u672c\u4f2a\u88c5")) {
                SwitchPreference(
                    title = tr("\u542f\u7528\u7248\u672c\u4f2a\u88c5", "\u542f\u7528\u7248\u672c\u4f2a\u88c5"),
                    summary = tr("\u4ec5\u5bf9\u7cfb\u7edf\u66f4\u65b0\u5e94\u7528\u751f\u6548", "\u4ec5\u5bf9\u7cfb\u7edf\u66f4\u65b0\u5e94\u7528\u751f\u6548"),
                    checked = systemUpdate.versionSpoofEnabled,
                    onCheckedChange = { enabled -> update { it.copy(versionSpoofEnabled = enabled) } },
                )
                ArrowPreference(
                    title = tr("\u4f2a\u88c5\u7248\u672c", "\u4f2a\u88c5\u7248\u672c"),
                    summary = systemUpdate.version.ifBlank { tr("\u672a\u8bbe\u7f6e", "\u672a\u8bbe\u7f6e") },
                    enabled = systemUpdate.versionSpoofEnabled,
                    onClick = { editingVersion = SystemUpdateVersionField.SYSTEM },
                )
                ArrowPreference(
                    title = tr("\u4f2a\u88c5 SOTA \u7248\u672c", "\u4f2a\u88c5 SOTA \u7248\u672c"),
                    summary = systemUpdate.sotaVersion.ifBlank { tr("\u672a\u8bbe\u7f6e", "\u672a\u8bbe\u7f6e") },
                    enabled = systemUpdate.versionSpoofEnabled,
                    onClick = { editingVersion = SystemUpdateVersionField.SOTA },
                )
            }
        }
    }
    editingVersion?.let { field ->
        val current = when (field) {
            SystemUpdateVersionField.SYSTEM -> systemUpdate.version
            SystemUpdateVersionField.SOTA -> systemUpdate.sotaVersion
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
                        SystemUpdateVersionField.SYSTEM -> it.copy(version = value)
                        SystemUpdateVersionField.SOTA -> it.copy(sotaVersion = value)
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

@Composable
private fun StatusSignalTuningSlider(
    title: String,
    value: Float,
    defaultValue: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onValueChangeFinished: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    EditableSliderPreference(
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
        defaultValue = defaultValue,
    )
}

@Composable
private fun IosNotificationCenterPage(
    openPage: (PageId) -> Unit,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val iosStore = remember(context) { IosNotificationCenterSettingsStore(context) }
    var iosSettings by remember { mutableStateOf(iosStore.settings) }
    val headerStore = remember(context) { IosNotificationHeaderSettingsStore(context) }
    var headerSettings by remember { mutableStateOf(headerStore.settings) }
    val offsetStore = remember(context) { IosNotificationBottomOffsetSettingsStore(context) }
    var offsetDp by remember { mutableStateOf(offsetStore.offsetDp) }
    val shortcutStore = remember(context) { IosNotificationShortcutsSettingsStore(context) }
    var showLockscreenShortcuts by remember { mutableStateOf(shortcutStore.enabled) }
    var shortcutOffsetDp by remember { mutableIntStateOf(shortcutStore.bottomOffsetDp) }
    val layoutStore = remember(context) { IosNotificationLayoutSettingsStore(context) }
    var headerGapDp by remember { mutableIntStateOf(layoutStore.headerGapDp) }
    LaunchedEffect(service) {
        service?.let(iosStore::syncRemote)
        service?.let(headerStore::syncRemote)
        service?.let(offsetStore::syncRemote)
        service?.let(shortcutStore::syncRemote)
        service?.let(layoutStore::syncRemote)
        iosSettings = iosStore.settings
        headerSettings = headerStore.settings
        offsetDp = offsetStore.offsetDp
        showLockscreenShortcuts = shortcutStore.enabled
        shortcutOffsetDp = shortcutStore.bottomOffsetDp
        headerGapDp = layoutStore.headerGapDp
    }
    AppPage(tr("iosNotificationCenter", "iOS 风格通知中心"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD, ScopeApplication.THEME_MANAGER)) { p, scroll ->
        AppList(p, scroll, 28) {
            item {
                Group(tr("iosNotificationCenter", "iOS 风格通知中心")) {
                    SwitchPreference(
                        title = tr("iosNotificationCenter", "iOS 风格通知中心"),
                        summary = tr("iosNotificationCenterSummary", "下拉时展示圆角壁纸面板、锁屏时钟及景深效果。动态壁纸暂不支持。"),
                        checked = iosSettings.enabled,
                        onCheckedChange = { enabled ->
                            iosStore.update(service) { it.copy(enabled = enabled) }
                            iosSettings = iosStore.settings
                        },
                    )
                    SwitchPreference(
                        title = tr("iosNotificationHideClear", "隐藏通知中心清除按钮"),
                        summary = tr("iosNotificationHideClearSummary", "隐藏通知中心底部的清除按钮"),
                        checked = iosSettings.hideClearButton,
                        onCheckedChange = { hidden ->
                            iosStore.update(service) { it.copy(hideClearButton = hidden) }
                            iosSettings = iosStore.settings
                        },
                    )
                    SwitchPreference(
                        title = tr("iosNotificationShortcuts", "在通知中心添加锁屏快捷按钮"),
                        summary = tr("iosNotificationShortcutsSummary", "显示与锁屏相同的左右快捷按钮，并沿用原有操作"),
                        checked = showLockscreenShortcuts,
                        onCheckedChange = { enabled ->
                            shortcutStore.updateEnabled(service, enabled)
                            showLockscreenShortcuts = shortcutStore.enabled
                        },
                    )
                    if (iosSettings.enabled) {
                        OverlayDropdownPreference(
                            title = tr("iosNotificationWallpaper", "通知中心壁纸来源"),
                            items = listOf(
                                tr("iosNotificationLockWallpaper", "锁屏壁纸"),
                                tr("iosNotificationNoWallpaper", "不显示壁纸"),
                            ),
                            selectedIndex = if (iosSettings.wallpaper == 2) 1 else 0,
                            onSelectedIndexChange = { source ->
                                iosStore.update(service) { it.copy(wallpaper = if (source == 1) 2 else 0) }
                                iosSettings = iosStore.settings
                            },
                        )
                        ArrowPreference(
                            title = tr("iosNotificationHeaderSettings", "通知横条设置"),
                            summary = if (headerSettings.enabled) tr("已开启", "已开启") else tr("未开启", "未开启"),
                            onClick = { openPage(PageId.IOS_NOTIFICATION_HEADER_SETTINGS) },
                        )
                        ArrowPreference(
                            title = tr("iosNotificationClockGlass", "通知中心大时钟柔光玻璃"),
                            onClick = { openPage(PageId.IOS_NOTIFICATION_CLOCK_GLASS) },
                        )
                        EditableSliderPreference(
                            title = tr("iosNotificationHeaderGap", "通知横条和通知的间距"),
                            value = headerGapDp.toFloat(),
                            valueText = "$headerGapDp dp",
                            valueRange = -40f..64f,
                            steps = 103,
                            defaultValue = 0f,
                            centerValue = 0f,
                            onValueChange = { headerGapDp = it.roundToInt().coerceIn(-40, 64) },
                            onValueChangeFinished = { layoutStore.updateHeaderGap(service, headerGapDp) },
                        )
                        if (!showLockscreenShortcuts) {
                            EditableSliderPreference(
                                title = tr("iosNotificationBottomOffset", "通知下沉底部偏移"),
                                value = offsetDp.toFloat(),
                                valueText = if (offsetDp > 0) "+$offsetDp dp" else "$offsetDp dp",
                                valueRange = -40f..120f,
                                steps = 159,
                                defaultValue = 0f,
                                centerValue = 0f,
                                onValueChange = { offsetDp = it.roundToInt().coerceIn(-40, 120) },
                                onValueChangeFinished = { offsetStore.update(service, offsetDp) },
                            )
                        } else {
                            EditableSliderPreference(
                                title = tr("iosNotificationShortcutsBottomOffset", "快捷按钮模式通知下沉底部偏移"),
                                value = shortcutOffsetDp.toFloat(),
                                valueText = if (shortcutOffsetDp > 0) "+$shortcutOffsetDp dp" else "$shortcutOffsetDp dp",
                                valueRange = -40f..120f,
                                steps = 159,
                                defaultValue = 0f,
                                centerValue = 0f,
                                onValueChange = { shortcutOffsetDp = it.roundToInt().coerceIn(-40, 120) },
                                onValueChangeFinished = { shortcutStore.updateBottomOffset(service, shortcutOffsetDp) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IosNotificationClockGlassPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val changed = LocalNotificationSettingsChanged.current
    val store = remember(context) { IosNotificationLayoutSettingsStore(context) }
    var value by remember { mutableStateOf(store.clock) }
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        value = store.clock
    }
    val update: ((btm.m.os4.systemuihook.newnotificationcenter.IosNotificationClockSettings) -> btm.m.os4.systemuihook.newnotificationcenter.IosNotificationClockSettings) -> Unit = { transform ->
        store.updateClock(service, transform)
        value = store.clock
        changed()
    }
    AppPage(tr("iosNotificationClockGlass", "通知中心大时钟柔光玻璃"), back,
        restartScopes = setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD, ScopeApplication.THEME_MANAGER)) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(
                        title = tr("iosNotificationClockGlassEnabled", "启用大时钟柔光玻璃调整"),
                        checked = value.enabled,
                        onCheckedChange = { enabled -> update { it.copy(enabled = enabled) } },
                    )
                    AnimatedVisibility(visible = value.enabled) {
                        SwitchPreference(
                            title = tr("iosNotificationClockGlassUseGlobal", "使用全局柔光玻璃材质参数"),
                            checked = value.useGlobal,
                            onCheckedChange = { useGlobal -> update { it.copy(useGlobal = useGlobal) } },
                        )
                    }
                }
            }
            if (value.enabled && !value.useGlobal) item {
                Group(tr("iosNotificationClockGlassParameters", "大时钟柔光玻璃参数")) {
                    val glass = value.glass
                    ParameterIntSlide(tr("玻璃模糊", "玻璃模糊"), glass.refractionBlur, 0..200, "%", 100) { number ->
                        update { it.copy(glass = it.glass.copy(refractionBlur = number)) }
                    }
                    softGlassParameters.forEach { definition ->
                        ParameterIntSlide(
                            tr(definition.title, definition.title),
                            glass.adjustments[definition.key] ?: 0,
                            definition.adjustmentRange,
                            "%",
                            if (definition.key == "darker") -100 else 0,
                        ) { number ->
                            update { it.copy(glass = it.glass.copy(adjustments = it.glass.adjustments + (definition.key to number))) }
                        }
                    }
                    ParameterIntSlide(tr("softGlassBackgroundBlur", "背景模糊度"), glass.backgroundBlur, 0..200, "%", 100) { number ->
                        update { it.copy(glass = it.glass.copy(backgroundBlur = number)) }
                    }
                    ParameterIntSlide(tr("softGlassBackgroundScale", "背景缩放程度"), glass.backgroundScale, 0..200, "%", 100) { number ->
                        update { it.copy(glass = it.glass.copy(backgroundScale = number)) }
                    }
                    ParameterIntSlide(tr("背景不透明度", "背景不透明度"), glass.backgroundOpacity, 0..200, "%", 100) { number ->
                        update { it.copy(glass = it.glass.copy(backgroundOpacity = number)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun IosNotificationHeaderSettingsPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { IosNotificationHeaderSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    var showHeaderText by remember { mutableStateOf(false) }
    var showHeaderIcon by remember { mutableStateOf(false) }
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        settings = store.settings
    }
    fun update(transform: (IosNotificationHeaderSettings) -> IosNotificationHeaderSettings) {
        store.update(service, transform)
        settings = store.settings
    }
    AppPage(tr("iosNotificationHeaderSettings", "通知横条设置"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
        AppList(p, scroll, 28) {
            item {
                Group(tr("iosNotificationHeaderSettings", "通知横条设置")) {
                    SwitchPreference(
                        title = tr("iosNotificationHeader", "通知顶部显示通知横条"),
                        summary = tr("iosNotificationHeaderSummary", "在通知顶部显示可滚动的“通知中心”和清除按钮"),
                        checked = settings.enabled,
                        onCheckedChange = { enabled -> update { it.copy(enabled = enabled) } },
                    )
                    AnimatedVisibility(visible = settings.enabled) {
                        Column {
                            OverlayDropdownPreference(
                                title = tr("iosNotificationHeaderLeftContent", "自定义通知中心横条左侧文本"),
                                items = listOf(
                                    tr("iosNotificationHeaderContentDefault", "模块默认"),
                                    tr("iosNotificationHeaderContentHidden", "不显示"),
                                    tr("iosNotificationHeaderContentIcon", "图标"),
                                    tr("iosNotificationHeaderContentCustomText", "自定义文本"),
                                    tr("iosNotificationHeaderContentCustomIcon", "自定义图标"),
                                    tr("iosNotificationHeaderContentIconText", "图标加文本"),
                                    tr("iosNotificationHeaderContentCustomIconText", "自定义图标和文本"),
                                ),
                                selectedIndex = settings.leftContentMode,
                                onSelectedIndexChange = { value -> update { it.copy(leftContentMode = value) } },
                            )
                            if (settings.leftContentMode == 3 || settings.leftContentMode == 4 || settings.leftContentMode == 6) {
                                if (settings.leftContentMode == 3 || settings.leftContentMode == 6) {
                                    ArrowPreference(
                                        title = tr("iosNotificationHeaderEditText", "设置横条文本"),
                                        summary = settings.leftContentText.ifBlank { tr("iosNotificationHeaderDefaultText", "模块默认") },
                                        onClick = { showHeaderText = true },
                                    )
                                }
                                if (settings.leftContentMode == 4 || settings.leftContentMode == 6) {
                                    ArrowPreference(
                                        title = tr("iosNotificationHeaderEditIcon", "设置横条图标"),
                                        summary = if (settings.leftContentIcon.isBlank()) tr("iosNotificationHeaderNotImported", "未导入")
                                            else tr("iosNotificationHeaderIconImported", "已导入图标"),
                                        onClick = { showHeaderIcon = true },
                                    )
                                }
                            }
                            if (settings.leftContentMode == 5 || settings.leftContentMode == 6) {
                                ParameterIntSlide(
                                    tr("iosNotificationHeaderIconScale", "图标缩放"),
                                    settings.leftContentIconScale,
                                    -60..200,
                                    "%",
                                    0,
                                ) { value -> update { it.copy(leftContentIconScale = value) } }
                                ParameterIntSlide(
                                    tr("iosNotificationHeaderTextScale", "文本缩放"),
                                    settings.leftContentTextScale,
                                    -60..200,
                                    "%",
                                    0,
                                ) { value -> update { it.copy(leftContentTextScale = value) } }
                                ParameterIntSlide(
                                    tr("iosNotificationHeaderIconTextSpacing", "图标文本间距"),
                                    settings.leftContentIconTextSpacing,
                                    0..48,
                                    " dp",
                                    8,
                                ) { value -> update { it.copy(leftContentIconTextSpacing = value) } }
                            }
                            OverlayDropdownPreference(
                                title = tr("iosNotificationClearStyle", "清除按钮样式"),
                                items = listOf(
                                    tr("iosNotificationClearStyleIconText", "图标和文本"),
                                    tr("iosNotificationClearStyleIcon", "图标"),
                                    tr("iosNotificationClearStyleText", "文本"),
                                ),
                                selectedIndex = settings.clearButtonStyle,
                                onSelectedIndexChange = { value -> update { it.copy(clearButtonStyle = value) } },
                            )
                            OverlayDropdownPreference(
                                title = tr("iosNotificationClearMaterial", "清除按钮背景"),
                                items = listOf(
                                    tr("iosNotificationMaterialSoftGlass", "柔光玻璃"),
                                    tr("iosNotificationMaterialAdvanced", "高级材质"),
                                    tr("iosNotificationMaterialSolid", "纯色"),
                                ),
                                selectedIndex = settings.clearButtonMaterial,
                                onSelectedIndexChange = { value -> update { it.copy(clearButtonMaterial = value) } },
                            )
                            ParameterFloatSlide(tr("iosNotificationClearRadius", "清除按钮圆角"), settings.clearButtonCornerRadius, 0f..60f, 22f) { value -> update { it.copy(clearButtonCornerRadius = value) } }
                            when (settings.clearButtonMaterial) {
                                2 -> ShortcutBackgroundColorPreference(tr("iosNotificationClearPureColor", "纯色背景颜色"), settings.clearButtonPureColor) { value -> update { it.copy(clearButtonPureColor = value) } }
                                1 -> Column {
                                    ShortcutBackgroundColorPreference(tr("iosNotificationClearAdvancedColor", "高级材质颜色"), settings.clearButtonAdvancedColor) { value -> update { it.copy(clearButtonAdvancedColor = value) } }
                                    ParameterIntSlide(tr("iosNotificationClearAdvancedOpacity", "高级材质不透明度"), settings.clearButtonAdvancedOpacity, 0..100, "%", 14) { value -> update { it.copy(clearButtonAdvancedOpacity = value) } }
                                    ParameterIntSlide(tr("iosNotificationClearAdvancedBlur", "高级材质模糊半径"), settings.clearButtonAdvancedBlurRadius, 0..120, " px", 36) { value -> update { it.copy(clearButtonAdvancedBlurRadius = value) } }
                                    SwitchPreference(title = tr("iosNotificationClearAdvancedHighlight", "高级材质高光"), checked = settings.clearButtonAdvancedHighlight, onCheckedChange = { value -> update { it.copy(clearButtonAdvancedHighlight = value) } })
                                }
                                else -> Column {
                                    ShortcutBackgroundColorPreference(tr("iosNotificationClearSoftColor", "柔光玻璃颜色"), settings.clearButtonSoftGlassColor) { value -> update { it.copy(clearButtonSoftGlassColor = value) } }
                                    ParameterIntSlide(tr("iosNotificationClearSoftOpacity", "柔光玻璃不透明度"), settings.clearButtonSoftGlassOpacity, 0..100, "%", 10) { value -> update { it.copy(clearButtonSoftGlassOpacity = value) } }
                                    ParameterIntSlide(tr("iosNotificationClearSoftBackdropBlur", "柔光玻璃背景模糊"), settings.clearButtonSoftGlassBackdropBlurRadius, 0..120, " px", 80) { value -> update { it.copy(clearButtonSoftGlassBackdropBlurRadius = value) } }
                                    ParameterIntSlide(tr("iosNotificationClearSoftBlur", "柔光玻璃前景模糊"), settings.clearButtonSoftGlassBlurRadius, 0..120, " px", 36) { value -> update { it.copy(clearButtonSoftGlassBlurRadius = value) } }
                                    ParameterFloatSlide(tr("iosNotificationClearSoftLuminance", "柔光玻璃亮度"), settings.clearButtonSoftGlassLuminance, 0f..0.4f, .14f) { value -> update { it.copy(clearButtonSoftGlassLuminance = value) } }
                                    ParameterIntSlide(tr("iosNotificationClearSoftRefraction", "柔光玻璃折射度"), settings.clearButtonSoftGlassRefraction, -100..100, " x0.01", 0) { value -> update { it.copy(clearButtonSoftGlassRefraction = value) } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showHeaderText) IosNotificationHeaderTextDialog(
        current = settings.leftContentText,
        onSave = { value -> update { it.copy(leftContentText = value) }; showHeaderText = false },
        onDismiss = { showHeaderText = false },
    )
    if (showHeaderIcon) IosNotificationHeaderIconDialog(
        store = store,
        current = settings.leftContentIcon,
        onSave = { value -> update { it.copy(leftContentIcon = value) }; showHeaderIcon = false },
        onDismiss = { showHeaderIcon = false },
    )
}

@Composable
private fun IosNotificationHeaderTextDialog(
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(current) { mutableStateOf(current) }
    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("iosNotificationHeaderEditText", "设置横条文本"), style = MiuixTheme.textStyles.title3)
            TextField(
                value = draft,
                onValueChange = { draft = it.take(160) },
                label = tr("iosNotificationHeaderTextInput", "输入文本"),
                useLabelAsPlaceholder = true,
                singleLine = true,
                cornerRadius = 16.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(tr("iosNotificationHeaderCancel", "取消"))
                }
                GlassDialogButton(onClick = { draft = "" }, modifier = Modifier.weight(1f)) {
                    Text(tr("iosNotificationHeaderRestoreDefault", "恢复默认"))
                }
            }
            GlassDialogButton(
                onClick = { onSave(draft.trim()) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("iosNotificationHeaderSave", "保存")) }
        }
    }
}

@Composable
private fun IosNotificationHeaderIconDialog(
    store: IosNotificationHeaderSettingsStore,
    current: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var draft by remember(current) { mutableStateOf(current) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                draft = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 256 * 1024) { "Image is too large" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("Cannot read image")
                    store.importHeaderIcon(bytes)
                }
            } catch (_: Exception) {
                Toast.makeText(context, tr("iosNotificationHeaderIconImportFailed", "图标导入失败"), Toast.LENGTH_SHORT).show()
            } finally { busy = false }
        }
    }
    val preview = remember(draft) {
        runCatching {
            val bytes = Base64.decode(draft, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
    WindowDialog(show = true, onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("iosNotificationHeaderEditIcon", "设置横条图标"), style = MiuixTheme.textStyles.title3)
            if (preview == null) {
                Text(tr("iosNotificationHeaderNotImported", "未导入"), style = MiuixTheme.textStyles.body2)
            } else {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = tr("iosNotificationHeaderIconPreview", "横条图标预览"),
                    modifier = Modifier.size(56.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(
                    onClick = { picker.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) { Text(tr("iosNotificationHeaderImport", "导入")) }
                GlassDialogButton(
                    onClick = { draft = "" },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                ) { Text(tr("iosNotificationHeaderRestoreDefault", "恢复默认")) }
            }
            GlassDialogButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Text(tr("iosNotificationHeaderCancel", "取消"))
            }
            GlassDialogButton(
                onClick = { onSave(draft) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("iosNotificationHeaderSave", "保存")) }
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
) = AppPage(tr("系统界面与材质调整", "系统界面与材质调整"), back, restartScopes = setOf(
    ScopeApplication.SYSTEM_UI,
    ScopeApplication.AOD,
    ScopeApplication.THEME_MANAGER,
)) { p, scroll ->
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val gesturePrefs = remember(context) { context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE) }
    var hideGlobalGestureHandle by remember { mutableStateOf(gesturePrefs.getBoolean(KEY_HIDE_GLOBAL_GESTURE_HANDLE, false)) }
    LaunchedEffect(service) {
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()
            ?.putBoolean(KEY_HIDE_GLOBAL_GESTURE_HANDLE, gesturePrefs.getBoolean(KEY_HIDE_GLOBAL_GESTURE_HANDLE, false))
            ?.apply()
    }
    val softGlassStore = remember(context) { SoftGlassSettingsStore(context) }
    var softGlass by remember { mutableStateOf(softGlassStore.settings) }
    val glassChanged = LocalNotificationSettingsChanged.current
    LaunchedEffect(service) {
        softGlassStore.reload()
        service?.let(softGlassStore::syncRemote)
        softGlass = softGlassStore.settings
    }
    AppList(p, scroll, 28) {
        item {
            Group(tr("柔光玻璃参数调整", "柔光玻璃参数调整")) {
                SwitchPreference(
                    title = tr("柔光玻璃参数调整", "柔光玻璃参数调整"),
                    summary = tr("softGlassScope", "统一调整通知、控制中心与悬浮通知的柔光玻璃，保留系统浅色与深色材质差异。"),
                    checked = softGlass.enabled,
                    onCheckedChange = { enabled ->
                        softGlassStore.update(service) { it.copy(enabled = enabled) }
                        softGlass = softGlassStore.settings
                        glassChanged()
                    },
                )
                AnimatedVisibility(
                    visible = softGlass.enabled,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    Column {
                        ArrowPreference(title = tr("柔光玻璃参数调整", "柔光玻璃参数调整"), onClick = { openPage(PageId.SOFT_GLASS_PARAMETERS) })
                        ArrowPreference(title = tr("softGlassPresets", "柔光玻璃预设"), onClick = { openPage(PageId.SOFT_GLASS_PRESETS) })
                    }
                }
            }
        }

        item {
            Group(tr("\u754c\u9762", "\u754c\u9762")) {
                SwitchPreference(
                    title = tr("\u5168\u5c40\u9690\u85cf\u624b\u52bf\u63d0\u793a\u7ebf", "\u5168\u5c40\u9690\u85cf\u624b\u52bf\u63d0\u793a\u7ebf"),
                    checked = hideGlobalGestureHandle,
                    onCheckedChange = { enabled ->
                        hideGlobalGestureHandle = enabled
                        gesturePrefs.edit().putBoolean(KEY_HIDE_GLOBAL_GESTURE_HANDLE, enabled).apply()
                        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()
                            ?.putBoolean(KEY_HIDE_GLOBAL_GESTURE_HANDLE, enabled)?.apply()
                    },
                )
                ArrowPreference(
                    title = tr("\u624b\u52bf\u63d0\u793a\u7ebf\u6837\u5f0f\u81ea\u5b9a\u4e49", "\u624b\u52bf\u63d0\u793a\u7ebf\u6837\u5f0f\u81ea\u5b9a\u4e49"),
                    onClick = { openPage(PageId.GESTURE_HANDLE_STYLE) },
                )
                SwitchPreference(
                    title = tr("\u4f7f\u7528\u5168\u5c40\u4e3b\u9898\u540e\u4fdd\u6301\u67d4\u5149\u73bb\u7483", "\u4f7f\u7528\u5168\u5c40\u4e3b\u9898\u540e\u4fdd\u6301\u67d4\u5149\u73bb\u7483"),
                    checked = s.keepSoftGlassAfterGlobalTheme,
                    onCheckedChange = { enabled ->
                        update { it.copy(keepSoftGlassAfterGlobalTheme = enabled) }
                    },
                )
                SwitchPreference(
                    title = tr("\u53bb\u9664\u65f6\u949f\u6750\u8d28\u5bf9\u52a8\u6001\u58c1\u7eb8\u548c\u8d85\u7ea7\u58c1\u7eb8\u7684\u9650\u5236", "\u53bb\u9664\u65f6\u949f\u6750\u8d28\u5bf9\u52a8\u6001\u58c1\u7eb8\u548c\u8d85\u7ea7\u58c1\u7eb8\u7684\u9650\u5236"),
                    checked = s.removeClockMaterialLimit,
                    onCheckedChange = { enabled ->
                        update { it.copy(removeClockMaterialLimit = enabled) }
                    },
                )
            }
        }
        item {
            Group(tr("\u97f3\u91cf\u9762\u677f", "\u97f3\u91cf\u9762\u677f")) {
                VolumePanelPreferences(s, update)
            }
        }
    }

}

@Composable
internal fun SoftGlassHeaderTabs(tabs: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    val backdrop = LocalToolbarBackdrop.current
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val shape = remember { Capsule() }
    val glass = if (backdrop != null && isRuntimeShaderSupported()) Modifier.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(3.dp.toPx())
            lens(10.dp.toPx(), 24.dp.toPx(), depthEffect = true, chromaticAberration = true)
        },
        highlight = { Highlight.Default.copy(alpha = .62f) },
        onDrawSurface = { drawRect(surface.copy(alpha = .6f)) },
    ) else Modifier.background(surface, shape)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp)) {
        Box(
            Modifier.matchParentSize()
                .clip(shape)
                .then(glass),
        )
        Row(
            Modifier.fillMaxWidth().height(53.dp * 0.93f * 0.97f).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            tabs.forEachIndexed { index, label ->
                Box(
                    Modifier.weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .then(
                            if (index == selected) {
                                Modifier.background(MiuixTheme.colorScheme.onSurface.copy(alpha = .10f))
                            } else Modifier
                        )
                        .clickable { onSelected(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SoftGlassParametersPage(openPage: (PageId) -> Unit, back: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val changed = LocalNotificationSettingsChanged.current
    val store = remember(context) { SoftGlassSettingsStore(context) }
    var value by remember { mutableStateOf(store.settings) }
    LaunchedEffect(service) {
        store.reload()
        service?.let(store::syncRemote)
        value = store.settings
    }
    val updateGlass: ((SoftGlassSettings) -> SoftGlassSettings) -> Unit = { transform ->
        store.update(service, transform)
        value = store.settings
        changed()
    }
    AppPage(
        tr("柔光玻璃参数调整", "柔光玻璃参数调整"), back,
        restartScopes = setOf(ScopeApplication.SYSTEM_UI),
        header = {
            SoftGlassHeaderTabs(
                listOf(tr("玻璃参数调整", "玻璃参数调整"), tr("背景调整", "背景调整")),
                tab, { tab = it },
            )
        },
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Group(tr("softGlassPresets", "柔光玻璃预设")) {
                    ArrowPreference(title = tr("softGlassPresets", "柔光玻璃预设"), onClick = { openPage(PageId.SOFT_GLASS_PRESETS) })
                    ArrowPreference(
                        title = tr("softGlassReset", "恢复柔光玻璃默认参数"),
                        onClick = { updateGlass { it.resetParameters() } },
                    )
                }
            }
            if (tab == 0) {
                item {
                    Group(tr("softGlassOptics", "玻璃光学参数")) {
                        ParameterIntSlide(tr("玻璃模糊", "玻璃模糊"), value.refractionBlur, 0..200, "%", 100) { n ->
                            updateGlass { it.copy(refractionBlur = n) }
                        }
                        Text(
                            tr("softGlassOpticsHint", "0% 保持系统原值。边缘羽化越高，反射越弱；100% 关闭边缘反射。其余参数负值减弱、正值增强；原值为零时按小幅偏移调整。点击数值可精确输入或恢复默认。"),
                            modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        softGlassParameters.forEach { definition ->
                            ParameterIntSlide(tr(definition.title, definition.title), value.adjustments[definition.key] ?: 0, definition.adjustmentRange, "%", if (definition.key == "darker") -100 else 0) { n ->
                                updateGlass { it.copy(adjustments = it.adjustments + (definition.key to n)) }
                            }
                        }
                    }
                }
            } else if (tab == 1) {
                item {
                    Group(tr("背景调整", "背景调整")) {
                        Text(
                            tr("softGlassBackgroundHint", "统一调整通知中心、控制中心及柔光玻璃采样背景。100% 保持系统值，0% 关闭对应效果；不透明度保留系统展开动画，系统原值为零时不会额外着色。"),
                            modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        ParameterIntSlide(tr("softGlassBackgroundBlur", "背景模糊度"), value.backgroundBlur, 0..200, "%", 100) { n -> updateGlass { it.copy(backgroundBlur = n) } }
                        ParameterIntSlide(tr("softGlassBackgroundScale", "背景缩放程度"), value.backgroundScale, 0..200, "%", 100) { n -> updateGlass { it.copy(backgroundScale = n) } }
                        ParameterIntSlide(tr("背景不透明度", "背景不透明度"), value.backgroundOpacity, 0..200, "%", 100) { n -> updateGlass { it.copy(backgroundOpacity = n) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SoftGlassPresetsPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val changed = LocalNotificationSettingsChanged.current
    val store = remember(context) { SoftGlassSettingsStore(context) }
    var presets by remember { mutableStateOf(store.presets) }
    var showSave by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf(false) }
    var showNewPresetConfirmation by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<SoftGlassPreset?>(null) }
    var deleteConfirmation by remember { mutableStateOf<SoftGlassPreset?>(null) }
    var export by remember { mutableStateOf<SoftGlassPreset?>(null) }
    var currentSettings by remember { mutableStateOf(store.settings) }
    var appliedPresetId by remember { mutableStateOf<String?>(null) }
    var pendingJson by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<QrShareRequest?>(null) }
    var pendingQr by remember { mutableStateOf<QrShareRequest?>(null) }
    val coroutine = rememberCoroutineScope()
    fun toast(key: String) { Toast.makeText(context, tr(key, key), Toast.LENGTH_SHORT).show() }
    LaunchedEffect(service) {
        store.reload()
        service?.let(store::syncRemote)
        presets = store.presets
        currentSettings = store.settings
        appliedPresetId = store.appliedPresetId ?: (builtInSoftGlassPresets() + presets).firstOrNull {
            currentSettings.matchesSoftGlassParameters(it.settings)
        }?.id
    }
    DisposableEffect(store) {
        val preferences = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != "soft_glass_custom_presets_v1") {
                store.reload()
                currentSettings = store.settings
                appliedPresetId = store.appliedPresetId ?: appliedPresetId
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(appliedPresetId, currentSettings, selected?.id, showSave) {
        if (selected == null && !showSave) {
            val appliedBuiltIn = builtInSoftGlassPresets().firstOrNull { it.id == appliedPresetId }
            if (appliedBuiltIn != null && !currentSettings.matchesSoftGlassParameters(appliedBuiltIn.settings)) {
                store.applyPreset(service, appliedBuiltIn)
                currentSettings = store.settings
                changed()
            }
        }
    }
    fun applyPreset(preset: SoftGlassPreset) {
        store.reload()
        store.applyPreset(service, preset)
        store.setAppliedPreset(service, preset.id)
        currentSettings = store.settings
        appliedPresetId = preset.id
        changed()
        toast("预设已应用")
    }
    fun hasUnsavedPresetChanges(): Boolean {
        val baseline = (builtInSoftGlassPresets() + presets).firstOrNull { it.id == appliedPresetId }?.settings
            ?: return false
        return !currentSettings.matchesSoftGlassParameters(baseline)
    }
    fun resetAndOpenNewPreset() {
        store.update(service) { it.resetParameters() }
        currentSettings = store.settings
        appliedPresetId = null
        store.setAppliedPreset(service, null)
        changed()
        saveAs = false
        showSave = true
    }
    fun beginNewPreset() {
        if (hasUnsavedPresetChanges()) {
            showNewPresetConfirmation = true
            return
        }
        resetAndOpenNewPreset()
    }
    fun importPayload(payload: String) {
        runCatching { SoftGlassPresetCodec.decode(payload) }.onSuccess { preset ->
            store.reload()
            store.savePreset(service, preset)
            presets = store.presets
            applyPreset(preset)
            toast("预设已导入")
        }.onFailure { toast("导入失败") }
    }
    val jsonImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coroutine.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                        val buffer = CharArray(SoftGlassPresetCodec.MAX_PAYLOAD_LENGTH + 1)
                        var length = 0
                        while (length < buffer.size) {
                            val count = it.read(buffer, length, buffer.size - length)
                            if (count < 0) break
                            length += count
                        }
                        require(length <= SoftGlassPresetCodec.MAX_PAYLOAD_LENGTH)
                        String(buffer, 0, length)
                    } ?: error("Missing input")
                }
            }.onSuccess(::importPayload).onFailure { toast("导入失败") }
        }
    }
    val qrImport = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) coroutine.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    require(bounds.outWidth > 0 && bounds.outHeight > 0)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 1
                        while (bounds.outWidth / inSampleSize > 2048 || bounds.outHeight / inSampleSize > 2048) inSampleSize *= 2
                    }
                    val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: error("Missing image")
                    try { readPresetQrCode(bitmap) } finally { bitmap.recycle() }
                }
            }.onSuccess(::importPayload).onFailure { toast("导入失败") }
        }
    }
    val jsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val payload = pendingJson
        pendingJson = null
        if (uri != null && payload != null) coroutine.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(payload) } ?: error("Missing output")
                }
            }.onSuccess { toast("预设已导出") }.onFailure { toast("导出失败") }
        }
    }
    val qrExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val request = pendingQr
        pendingQr = null
        if (uri != null && request != null) coroutine.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val bitmap = createPresetQrCode(request.payload)
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } ?: error("Missing output")
                    } finally { bitmap.recycle() }
                }
            }.onSuccess { toast("预设已导出") }.onFailure { toast("导出失败") }
        }
    }
    fun display(preset: SoftGlassPreset): ShadePreset = ShadePreset(
        if (preset.builtIn) tr(preset.name, preset.name) else preset.name,
        SoftGlassPresetCodec.encode(preset), preset.builtIn,
    )
    AppPage(tr("softGlassPresets", "柔光玻璃预设"), back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Group(tr("softGlassPresets", "柔光玻璃预设")) {
                    Text(tr("softGlassPresetHint", "所有预设同时应用玻璃与背景参数，不会更改总开关。旧版单独预设缺少的部分恢复系统默认值。点击或长按预设可保存、另存为、使用、导出或删除。"), modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    ArrowPreference(title = tr("新建预设", "新建预设"), onClick = ::beginNewPreset)
                    ArrowPreference(title = tr("导入预设", "导入预设"), onClick = { showImport = true })
                }
            }
            item {
                Group(tr("内置预设", "内置预设")) {
                    builtInSoftGlassPresets().forEach { preset ->
                        val applied = appliedPresetId == preset.id
                        PresetRow(
                            display(preset),
                            applied = applied,
                            modified = applied && !currentSettings.matchesSoftGlassParameters(preset.settings),
                            onUse = { applyPreset(preset) },
                            onClick = { selected = preset },
                            onLongPress = { selected = preset },
                        )
                    }
                }
            }
            item {
                Group(tr("用户预设", "用户预设")) {
                    if (presets.isEmpty()) Text(tr("暂无保存的用户预设", "暂无保存的用户预设"), modifier = Modifier.padding(16.dp), style = MiuixTheme.textStyles.body2)
                    presets.forEach { preset ->
                        val applied = appliedPresetId == preset.id
                        PresetRow(
                            display(preset),
                            applied = applied,
                            modified = applied && !currentSettings.matchesSoftGlassParameters(preset.settings),
                            onUse = { applyPreset(preset) },
                            onClick = { selected = preset },
                            onLongPress = { selected = preset },
                        )
                    }
                }
            }
        }
    }
    SavePresetDialog(showSave, { showSave = false }, titleKey = if (saveAs) "另存为" else "新建预设") { name ->
        store.reload()
        val preset = SoftGlassPreset(name.trim(), currentSettings.copy(enabled = false).normalized())
        store.savePreset(service, preset)
        presets = store.presets
        appliedPresetId = preset.id
        store.setAppliedPreset(service, preset.id)
        showSave = false
        saveAs = false
        toast("预设已保存")
    }
    ImportPresetDialog(showImport, { showImport = false },
        { showImport = false; jsonImport.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        { showImport = false; qrImport.launch("image/*") },
    )
    NewPresetConfirmationDialog(
        show = showNewPresetConfirmation,
        onDismiss = { showNewPresetConfirmation = false },
        onConfirm = {
            showNewPresetConfirmation = false
            resetAndOpenNewPreset()
        },
    )
    selected?.let { preset ->
        SoftGlassPresetActionDialog(
            preset = display(preset),
            canSave = !preset.builtIn && !currentSettings.matchesSoftGlassParameters(preset.settings),
            onDismiss = { selected = null },
            onSave = {
                if (!preset.builtIn) {
                    val updated = preset.copy(settings = currentSettings.copy(enabled = false).normalized(), builtIn = false)
                    store.savePreset(service, updated)
                    presets = store.presets
                    appliedPresetId = updated.id
                    store.setAppliedPreset(service, updated.id)
                    selected = null
                    toast("预设已保存")
                }
            },
            onSaveAs = { selected = null; saveAs = true; showSave = true },
            onUse = { applyPreset(preset); selected = null },
            onExport = { selected = null; export = preset },
            onDelete = { selected = null; deleteConfirmation = preset },
            editableName = !preset.builtIn,
            onRename = { name ->
                if (!preset.builtIn && name.isNotBlank()) {
                    store.renamePreset(service, preset.id, name)
                    presets = store.presets
                    selected = presets.firstOrNull { it.id == preset.id }
                }
            },
        )
    }
    deleteConfirmation?.let { preset ->
        PresetDeleteConfirmationDialog(
            name = display(preset).name,
            onDismiss = { deleteConfirmation = null },
            onConfirm = {
                store.deletePreset(service, preset.id)
                presets = store.presets
                if (appliedPresetId == preset.id) appliedPresetId = null
                deleteConfirmation = null
            },
        )
    }
    export?.let { preset ->
        ExportPresetDialog(true, display(preset), { export = null },
            { pendingJson = SoftGlassPresetCodec.encode(preset); export = null; jsonExport.launch("HyperChanger-glass-preset.json") },
            { qr = QrShareRequest(display(preset).name, SoftGlassPresetCodec.encode(preset)); export = null },
        )
    }
    qr?.let { request ->
        QrShareDialog(request, { qr = null }, { pendingQr = request; qrExport.launch("HyperChanger-glass-preset.png") })
    }
}

@Composable
private fun VolumePanelPreferences(
    s: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val changed = LocalNotificationSettingsChanged.current
    val store = remember(context) { VolumePanelGlassSettingsStore(context) }
    var useGlobal by remember { mutableStateOf(store.useGlobal) }
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        useGlobal = store.useGlobal
    }
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
            SwitchPreference(
                title = tr("使用全局柔光玻璃参数", "使用全局柔光玻璃参数"),
                checked = useGlobal,
                onCheckedChange = { enabled ->
                    useGlobal = enabled
                    store.update(service, enabled)
                    changed()
                },
            )
            AnimatedVisibility(
                visible = !useGlobal,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
            ) {
                Column {
                    ParameterIntSlide(tr("背景不透明度", "背景不透明度"), s.volumePanelBackgroundOpacity, 0..100, "%", defaultValue = 100) { value ->
                        update { it.copy(volumePanelBackgroundOpacity = value) }
                    }
                    ParameterIntSlide(tr("背景模糊度", "背景模糊度"), s.volumePanelBlurRadius, 0..120, " px", defaultValue = 24) { value ->
                        update { it.copy(volumePanelBlurRadius = value) }
                    }
                    ParameterIntSlide(tr("玻璃模糊", "玻璃模糊"), s.volumePanelGlassStrength, 0..100, "%", defaultValue = 50) { value ->
                        update { it.copy(volumePanelGlassStrength = value) }
                    }
                }
            }

        }
    }

    VolumePanelLayoutControls()
}

@Composable
private fun VolumePanelLayoutControls() {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val changed = LocalNotificationSettingsChanged.current
    val store = remember(context) { VolumePanelLayoutSettingsStore(context) }
    var layout by remember { mutableStateOf(store.settings) }
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        layout = store.settings
    }
    fun save(value: VolumePanelLayoutSettings) {
        store.update(service, value)
        layout = store.settings
        changed()
    }
    SwitchPreference(
        title = tr("自定义音量条大小", "自定义音量条大小"),
        checked = layout.sizeEnabled,
        onCheckedChange = { save(layout.copy(sizeEnabled = it)) },
    )
    AnimatedVisibility(
        visible = layout.sizeEnabled,
        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
    ) {
        Column {
            ParameterIntSlide(tr("自定义音量条宽度", "自定义音量条宽度"), layout.width, -50..72, " dp", defaultValue = 0) { save(layout.copy(width = it)) }
            ParameterIntSlide(tr("自定义音量条高度", "自定义音量条高度"), layout.height, -64..96, " dp", defaultValue = 0) { save(layout.copy(height = it)) }
        }
    }
    SwitchPreference(
        title = tr("隐藏静音与勿扰按钮", "隐藏“静音”与“勿扰”按钮"),
        checked = layout.hideRinger,
        onCheckedChange = { save(layout.copy(hideRinger = it)) },
    )
    SwitchPreference(
        title = tr("隐藏音量条上半部分的展开按钮", "隐藏音量条上半部分的展开按钮"),
        checked = layout.hideExpand,
        onCheckedChange = { save(layout.copy(hideExpand = it)) },
    )
    AnimatedVisibility(
        visible = layout.hideExpand,
        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
    ) {
        SwitchPreference(
            title = tr("长按展开音量面板", "长按展开音量面板"),
            checked = layout.longPressExpand,
            onCheckedChange = { save(layout.copy(longPressExpand = it)) },
        )
    }
    SwitchPreference(
        title = tr("自定义音量圆角", "自定义音量圆角"),
        checked = layout.cornerEnabled,
        onCheckedChange = { save(layout.copy(cornerEnabled = it)) },
    )
    AnimatedVisibility(
        visible = layout.cornerEnabled,
        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
    ) {
        ParameterIntSlide(tr("圆角", "圆角"), layout.corner, -40..80, " dp", defaultValue = 0) { save(layout.copy(corner = it)) }
    }

}

@Composable
private fun GestureHandleStylePage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val localPrefs = remember(context) {
        context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    }
    var hideGlobal by remember { mutableStateOf(localPrefs.getBoolean(KEY_HIDE_GLOBAL_GESTURE_HANDLE, false)) }
    var customEnabled by remember { mutableStateOf(localPrefs.getBoolean(KEY_GESTURE_HANDLE_CUSTOM_ENABLED, false)) }
    var length by remember { mutableFloatStateOf(localPrefs.getFloat(KEY_GESTURE_HANDLE_LENGTH, 120f)) }
    var height by remember { mutableFloatStateOf(localPrefs.getFloat(KEY_GESTURE_HANDLE_HEIGHT, 5f)) }
    var bottom by remember { mutableFloatStateOf(localPrefs.getFloat(KEY_GESTURE_HANDLE_BOTTOM, 6f)) }
    var opacity by remember { mutableIntStateOf(localPrefs.getInt(KEY_GESTURE_HANDLE_OPACITY, 100)) }
    var hookMask by remember { mutableIntStateOf(localPrefs.getInt(KEY_GESTURE_HANDLE_HOOK_MASK, GESTURE_HANDLE_HOOK_LENGTH or GESTURE_HANDLE_HOOK_HEIGHT or GESTURE_HANDLE_HOOK_BOTTOM or GESTURE_HANDLE_HOOK_OPACITY)) }
    var showAdjustDialog by remember { mutableStateOf(false) }

    fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        localPrefs.edit().apply(block).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.apply(block)?.apply()
    }
    LaunchedEffect(service) {
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.apply {
            putBoolean(KEY_HIDE_GLOBAL_GESTURE_HANDLE, localPrefs.getBoolean(KEY_HIDE_GLOBAL_GESTURE_HANDLE, false))
            putBoolean(KEY_GESTURE_HANDLE_CUSTOM_ENABLED, customEnabled)
            putInt(KEY_GESTURE_HANDLE_HOOK_MASK, hookMask)
        }?.apply()
    }
    val enabled = !hideGlobal
    AppPage(tr("手势提示线样式自定义", "手势提示线样式自定义"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
        AppList(p, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(
                        title = tr("手势提示线样式自定义", "手势提示线样式自定义"),
                        checked = customEnabled,
                        enabled = enabled,
                        onCheckedChange = { value -> customEnabled = value; edit { putBoolean(KEY_GESTURE_HANDLE_CUSTOM_ENABLED, value) } },
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    ArrowPreference(
                        title = tr("调整项目", "调整项目"),
                        enabled = enabled && customEnabled,
                        onClick = { showAdjustDialog = true },
                    )
                    if (hookMask and GESTURE_HANDLE_HOOK_LENGTH != 0) {
                        ParameterFloatSlide(tr("手势提示线长度", "手势提示线长度"), length, 40f..240f, 120f, enabled = enabled && customEnabled) {
                            length = it; edit { putFloat(KEY_GESTURE_HANDLE_LENGTH, it) }
                        }
                    }
                    if (hookMask and GESTURE_HANDLE_HOOK_HEIGHT != 0) {
                        ParameterFloatSlide(tr("手势提示线高度", "手势提示线高度"), height, 1f..24f, 5f, enabled = enabled && customEnabled) {
                            height = it; edit { putFloat(KEY_GESTURE_HANDLE_HEIGHT, it) }
                        }
                    }
                    if (hookMask and GESTURE_HANDLE_HOOK_BOTTOM != 0) {
                        ParameterFloatSlide(tr("手势提示线底边距", "手势提示线底边距"), bottom, 0f..48f, 6f, enabled = enabled && customEnabled) {
                            bottom = it; edit { putFloat(KEY_GESTURE_HANDLE_BOTTOM, it) }
                        }
                    }
                    if (hookMask and GESTURE_HANDLE_HOOK_OPACITY != 0) {
                        ParameterIntSlide(tr("手势提示线不透明度", "手势提示线不透明度"), opacity, 0..100, "%", 100, enabled = enabled && customEnabled) {
                            opacity = it; edit { putInt(KEY_GESTURE_HANDLE_OPACITY, it) }
                        }
                    }
                }
            }
        }
    }
    GestureHandleAdjustmentDialog(
        show = showAdjustDialog,
        mask = hookMask,
        onDismiss = { showAdjustDialog = false },
        onApply = { value -> hookMask = value; edit { putInt(KEY_GESTURE_HANDLE_HOOK_MASK, value) }; showAdjustDialog = false },
    )
}

@Composable
private fun GestureHandleAdjustmentDialog(
    show: Boolean,
    mask: Int,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit,
) {
    var selected by remember(show, mask) { mutableIntStateOf(mask) }
    val items = listOf(
        GESTURE_HANDLE_HOOK_LENGTH to tr("手势提示线长度", "手势提示线长度"),
        GESTURE_HANDLE_HOOK_HEIGHT to tr("手势提示线高度", "手势提示线高度"),
        GESTURE_HANDLE_HOOK_BOTTOM to tr("手势提示线底边距", "手势提示线底边距"),
        GESTURE_HANDLE_HOOK_OPACITY to tr("手势提示线不透明度", "手势提示线不透明度"),
    )
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("调整项目", "调整项目"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            items.forEach { (bit, title) ->
                val toggle = { selected = if (selected and bit != 0) selected and bit.inv() else selected or bit }
                Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state = if (selected and bit != 0) ToggleableState.On else ToggleableState.Off, onClick = toggle)
                    Text(title, style = MiuixTheme.textStyles.body1, modifier = Modifier.padding(start = 10.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton({ onApply(selected) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("保存", "保存")) }
            }
        }
    }
}

@Composable
private fun ShadePresets(
    settings: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    actions: ShadePresetActions,
    back: () -> Unit,
) = AppPage(tr("\u9884\u8bbe", "\u9884\u8bbe"), back) { p, scroll ->
    var selectedPreset by remember { mutableStateOf<ShadePreset?>(null) }
    var deleteConfirmation by remember { mutableStateOf<ShadePreset?>(null) }
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
        item { Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
            presets.forEach { preset ->
                PresetRow(preset, onUse = { update { preset.applyTo(it) } }, onLongPress = { selectedPreset = preset })
            }
        } }
        item { SmallTitle(tr("\u7528\u6237\u9884\u8bbe", "\u7528\u6237\u9884\u8bbe"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp)) }
        item { Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
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
                selectedPreset = null
                deleteConfirmation = preset
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
    deleteConfirmation?.let { preset ->
        PresetDeleteConfirmationDialog(
            name = preset.name,
            onDismiss = { deleteConfirmation = null },
            onConfirm = {
                actions.deleteUserPreset(preset)
                deleteConfirmation = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetRow(
    preset: ShadePreset,
    applied: Boolean = false,
    modified: Boolean = false,
    onUse: () -> Unit,
    onClick: (() -> Unit)? = null,
    onLongPress: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick ?: onUse, onLongClick = onLongPress).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(preset.name, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
        val icon = when {
            !applied -> MiuixIcons.Regular.ChevronForward
            modified -> MiuixIcons.Regular.MoreCircle
            else -> MiuixIcons.Regular.Check
        }
        val color = when {
            modified -> ComposeColor(0xFFFFB200)
            applied -> ComposeColor(0xFF0088FF)
            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        }
        Image(icon, null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(color))
    }
}

private fun SoftGlassSettings.matchesSoftGlassParameters(other: SoftGlassSettings): Boolean =
    normalized().copy(enabled = false) == other.normalized().copy(enabled = false)

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
private fun SavePresetDialog(show: Boolean, onDismiss: () -> Unit, titleKey: String = "新建预设", onSave: (String) -> Unit) {
    var name by remember(show) { mutableStateOf("") }
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr(titleKey, titleKey), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
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
            GlassDialogButton(onJson, Modifier.fillMaxWidth()) { Text(tr("JSON", "JSON")) }
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
private fun SoftGlassPresetActionDialog(
    preset: ShadePreset,
    canSave: Boolean,
    editableName: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onUse: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
) {
    var editingName by remember(preset.name) { mutableStateOf(false) }
    var name by remember(preset.name) { mutableStateOf(preset.name) }
    fun commitName() {
        val normalized = name.trim().take(40)
        if (normalized.isNotBlank()) {
            onRename(normalized)
            editingName = false
        }
    }
    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (editingName) {
                TextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = tr("预设名", "预设名"),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    cornerRadius = 999.dp,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitName() }),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton({ editingName = false; name = preset.name }, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                    GlassDialogButton(::commitName, Modifier.weight(1f), enabled = name.trim().isNotBlank(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("保存", "保存")) }
                }
            } else {
                Text(
                    preset.name,
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(enabled = editableName) { editingName = true },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onSave, Modifier.weight(1f), enabled = canSave) { Text(tr("保存", "保存")) }
                GlassDialogButton(onSaveAs, Modifier.weight(1f)) { Text(tr("另存为", "另存为")) }
            }
            GlassDialogButton(onUse, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("使用", "使用")) }
            GlassDialogButton(onExport, Modifier.fillMaxWidth()) { Text(tr("导出", "导出")) }
            if (!preset.builtIn) GlassDialogButton(onDelete, Modifier.fillMaxWidth()) { Text(tr("删除", "删除")) }
            GlassDialogButton(onDismiss, Modifier.fillMaxWidth()) { Text(tr("取消", "取消")) }
        }
    }
}

@Composable
private fun NewPresetConfirmationDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("新建预设确认", "新建预设确认"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Text(tr("新建预设确认内容", "当前预设有未保存的修改。继续新建预设将恢复默认参数，是否继续？"), style = MiuixTheme.textStyles.body1)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(onConfirm, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("继续", "继续")) }
            }
        }
    }
}

@Composable
private fun PresetDeleteConfirmationDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(tr("确认删除预设", "确认删除预设"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Text(
                String.format(Locale.getDefault(), tr("确认删除预设内容", "确定要删除预设“%s”吗？删除后无法恢复。"), name),
                style = MiuixTheme.textStyles.body1,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(onConfirm, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("删除", "删除")) }
            }
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
            GlassDialogButton(onJson, Modifier.fillMaxWidth()) { Text(tr("JSON", "JSON")) }
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
private fun Island(
    openPage: (PageId) -> Unit,
    onRequestNotificationPermission: () -> Unit,
    back: () -> Unit,
) = AppPage(
    tr("\u901a\u77e5\u4e0e\u8d85\u7ea7\u5c9b", "\u901a\u77e5\u4e0e\u8d85\u7ea7\u5c9b"),
    back,
    restartScopes = setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.SETTINGS),
) { p, scroll ->
    var showNormalNotificationDialog by remember { mutableStateOf(false) }
    val (s, update) = rememberNotificationIslandSettings()
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val textColorStore = remember(context) { NotificationTextColorSettingsStore(context) }
    var textColors by remember { mutableStateOf(textColorStore.settings) }
    val stackBlurStore = remember(context) { NotificationStackBlurSettingsStore(context) }
    var stackBlurEnabled by remember { mutableStateOf(stackBlurStore.enabled) }
    var stackBlurOffset by remember { mutableIntStateOf(stackBlurStore.offset) }
    LaunchedEffect(service) {
        service?.let(textColorStore::syncRemote)
        textColors = textColorStore.settings
        service?.let(stackBlurStore::syncRemote)
        stackBlurEnabled = stackBlurStore.enabled
        stackBlurOffset = stackBlurStore.offset
    }
    AppList(p, scroll, 28) {
        item {
            Group(tr("iosNotificationCenter", "iOS 风格通知中心")) {
                ArrowPreference(
                    title = tr("iosNotificationCenter", "iOS 风格通知中心"),
                    summary = tr("iosNotificationCenterSummary", "下拉时展示圆角壁纸面板、锁屏时钟及景深效果。动态壁纸暂不支持。"),
                    onClick = { openPage(PageId.IOS_NOTIFICATION_CENTER) },
                )
            }
        }
        item {
            Group(tr("超级岛", "超级岛")) {
                ArrowPreference(
                    title = tr("islandCollapsedPage", "超级岛收起态"),
                    onClick = { openPage(PageId.ISLAND_COLLAPSED) },
                )
                ArrowPreference(
                    title = tr("islandExpandedPage", "超级岛展开态"),
                    onClick = { openPage(PageId.ISLAND_EXPANDED) },
                )
            }
        }
        item {
            Group(tr("notificationGroup", "通知")) {
                SwitchPreference(
                    title = tr("\u7edf\u4e00\u901a\u77e5\u80cc\u666f\u6750\u8d28", "\u7edf\u4e00\u901a\u77e5\u80cc\u666f\u6750\u8d28"),
                    checked = s.unifyNotificationMaterial,
                    onCheckedChange = { enabled ->
                        update { it.copy(unifyNotificationMaterial = enabled) }
                    },
                )
                OverlayDropdownPreference(
                    title = tr("自定义通知文本颜色", "自定义通知文本颜色"),
                    items = listOf(tr("系统默认", "系统默认"), tr("浅色", "浅色"), tr("深色", "深色"), tr("与系统默认相反", "与系统默认相反")),
                    selectedIndex = textColors.notification,
                    onSelectedIndexChange = { mode ->
                        textColorStore.update(service, textColors.copy(notification = mode))
                        textColors = textColorStore.settings
                    },
                )
                SwitchPreference(
                    title = tr("\u60ac\u6d6e\u901a\u77e5\u542f\u7528\u67d4\u5149\u73bb\u7483\u6750\u8d28", "\u60ac\u6d6e\u901a\u77e5\u542f\u7528\u67d4\u5149\u73bb\u7483\u6750\u8d28"),
                    checked = s.headsUpNotificationSoftGlass,
                    onCheckedChange = { enabled ->
                        update { it.copy(headsUpNotificationSoftGlass = enabled) }
                    },
                )
                OverlayDropdownPreference(
                    title = tr("自定义悬浮通知文本颜色", "自定义悬浮通知文本颜色"),
                    items = listOf(tr("系统默认", "系统默认"), tr("浅色", "浅色"), tr("深色", "深色"), tr("跟随状态栏颜色", "跟随状态栏颜色")),
                    selectedIndex = textColors.headsUp,
                    onSelectedIndexChange = { mode ->
                        textColorStore.update(service, textColors.copy(headsUp = mode))
                        textColors = textColorStore.settings
                    },
                )
                SwitchPreference(
                    title = tr("为通知堆叠添加模糊", "为通知堆叠添加模糊"),
                    checked = stackBlurEnabled,
                    onCheckedChange = { enabled ->
                        stackBlurStore.update(service, enabled)
                        stackBlurEnabled = stackBlurStore.enabled
                    },
                )
                AnimatedVisibility(
                    visible = stackBlurEnabled,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    ParameterIntSlide(
                        tr("自定义模糊度", "自定义模糊度"), stackBlurOffset,
                        NOTIFICATION_STACK_BLUR_OFFSET_RANGE_MIN..NOTIFICATION_STACK_BLUR_OFFSET_RANGE_MAX,
                        " px", defaultValue = 0,
                    ) { value ->
                        stackBlurStore.updateOffset(service, value)
                        stackBlurEnabled = stackBlurStore.enabled
                        stackBlurOffset = stackBlurStore.offset
                    }
                }
                SwitchPreference(
                    title = tr("notification_corner_radius_offset", "调整通知圆角大小"),
                    checked = s.notificationCornerRadiusOffsetEnabled,
                    onCheckedChange = { enabled -> update { it.copy(notificationCornerRadiusOffsetEnabled = enabled) } },
                )
                AnimatedVisibility(visible = s.notificationCornerRadiusOffsetEnabled) {
                    IntSlide(tr("notification_corner_radius_offset", "调整通知圆角大小"), s.notificationCornerRadiusOffset, -30..30, defaultValue = 0) { value ->
                        update { it.copy(notificationCornerRadiusOffset = value) }
                    }
                }
                ArrowPreference(title = tr("通知限制去除", "通知限制去除"), onClick = { openPage(PageId.NOTIFICATION_RESTRICTIONS) })
                ArrowPreference(title = tr("notificationMediaCard", "媒体卡片"), onClick = { openPage(PageId.NOTIFICATION_MEDIA_CARD) })
            }
        }
        item {
            Group(tr("notification_simulation", "通知模拟")) {
                ArrowPreference(
                    title = tr("normal_notification_simulation", "普通通知模拟"),
                    onClick = { showNormalNotificationDialog = true },
                )
                ArrowPreference(
                    title = tr("island_simulation", "超级岛模拟"),
                    onClick = { openPage(PageId.SIMULATE_ISLAND) },
                )
                ArrowPreference(
                    title = tr("simulate_media_notification", "媒体通知模拟"),
                    onClick = { openPage(PageId.SIMULATE_MEDIA_NOTIFICATION) },
                )
            }
        }
    }

    if (showNormalNotificationDialog) {
        NotificationSimulationDialog(
            onRequestNotificationPermission = onRequestNotificationPermission,
            onDismiss = { showNormalNotificationDialog = false },
        )
    }
}

@Composable
private fun IslandCollapsedPage(back: () -> Unit) = AppPage(
    tr("islandCollapsedPage", "超级岛收起态"),
    back,
    restartScopes = setOf(ScopeApplication.SYSTEM_UI),
) { p, scroll ->
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val widthStore = remember(context) { IslandWidthSettingsStore(context) }
    var widthSettings by remember { mutableStateOf(widthStore.settings) }
    val smoothingStore = remember(context) { IslandSmoothingSettingsStore(context) }
    var smoothingSettings by remember { mutableStateOf(smoothingStore.settings) }
    val spacingStore = remember(context) { IslandSpacingSettingsStore(context) }
    var spacingSettings by remember { mutableStateOf(spacingStore.settings) }
    val appearanceStore = remember(context) { DynamicIslandAppearanceSettingsStore(context) }
    var appearanceSettings by remember { mutableStateOf(appearanceStore.settings) }
    fun updateWidth(transform: (IslandWidthSettings) -> IslandWidthSettings) {
        widthStore.update(service, transform)
        widthSettings = widthStore.settings
    }
    fun updateSpacing(transform: (IslandSpacingSettings) -> IslandSpacingSettings) {
        spacingStore.update(service, transform)
        spacingSettings = spacingStore.settings
    }
    fun updateSmoothing(transform: (IslandSmoothingSettings) -> IslandSmoothingSettings) {
        smoothingStore.update(service, transform)
        smoothingSettings = smoothingStore.settings
    }
    fun updateAppearance(transform: (DynamicIslandAppearanceSettings) -> DynamicIslandAppearanceSettings) {
        appearanceStore.update(service, transform)
        appearanceSettings = appearanceStore.settings
    }
    LaunchedEffect(service) {
        service?.let(widthStore::syncRemote)
        widthSettings = widthStore.settings
        service?.let(smoothingStore::syncRemote)
        smoothingSettings = smoothingStore.settings
        service?.let(spacingStore::syncRemote)
        spacingSettings = spacingStore.settings
        service?.let(appearanceStore::syncRemote)
        appearanceSettings = appearanceStore.settings
    }
    AppList(p, scroll, 28) {
        item {
            Group(tr("islandCollapsedSmoothingGroup", "背景平滑")) {
                SwitchPreference(
                    title = tr("islandCollapsedSmoothing", "平滑收起时的超级岛背景"),
                    checked = smoothingSettings.enabled,
                    onCheckedChange = { enabled -> updateSmoothing { it.copy(enabled = enabled) } },
                )
                AnimatedVisibility(visible = smoothingSettings.enabled) {
                    ParameterIntSlide(
                        title = tr("islandCollapsedSmoothingPercent", "收起时的超级岛背景平滑度"),
                        value = smoothingSettings.percent,
                        range = 0..100,
                        suffix = "%",
                        defaultValue = ISLAND_SMOOTHING_DEFAULT_PERCENT,
                    ) { percent -> updateSmoothing { it.copy(percent = percent) } }
                }
            }
        }
        item {
            Group(tr("islandWidthGroup", "岛宽度")) {
                SwitchPreference(
                    title = tr("islandWidthCustomization", "自定义岛宽度"),
                    checked = widthSettings.enabled,
                    onCheckedChange = { enabled -> updateWidth { it.copy(enabled = enabled) } },
                )
                if (widthSettings.enabled) {
                    IntSlide(
                        tr("大岛最小宽度", "大岛最小宽度"),
                        widthSettings.bigMinWidth,
                        ISLAND_BIG_WIDTH_RANGE,
                        defaultValue = ISLAND_BIG_WIDTH_DEFAULT,
                    ) { value -> updateWidth { it.copy(bigMinWidth = value) } }
                    IntSlide(
                        tr("小岛最小宽度", "小岛最小宽度"),
                        widthSettings.smallMinWidth,
                        ISLAND_SMALL_WIDTH_RANGE,
                        defaultValue = ISLAND_SMALL_WIDTH_DEFAULT,
                    ) { value -> updateWidth { it.copy(smallMinWidth = value) } }
                }
            }
        }
        item {
            Group(tr("islandMediaIconGroup", "媒体图标")) {
                SwitchPreference(
                    title = tr("small_island_media_icon_corner_radius", "收起时的媒体图标圆角"),
                    checked = appearanceSettings.smallMediaIconCornerRadiusEnabled,
                    onCheckedChange = { enabled -> updateAppearance { it.copy(smallMediaIconCornerRadiusEnabled = enabled) } },
                )
                AnimatedVisibility(visible = appearanceSettings.smallMediaIconCornerRadiusEnabled) {
                    IntSlide(
                        tr("small_island_media_icon_corner_radius", "收起时的媒体图标圆角"),
                        appearanceSettings.smallMediaIconCornerRadius,
                        -64..96,
                        defaultValue = SMALL_ISLAND_MEDIA_ICON_CORNER_RADIUS_DEFAULT,
                    ) { value -> updateAppearance { it.copy(smallMediaIconCornerRadius = value) } }
                }
            }
        }
        item {
            Group(tr("islandSpacingGroup", "岛间距")) {
                SwitchPreference(
                    title = tr("islandSpacingEnabled", "自定义大小岛间距"),
                    checked = spacingSettings.enabled,
                    onCheckedChange = { enabled -> updateSpacing { it.copy(enabled = enabled) } },
                )
                AnimatedVisibility(visible = spacingSettings.enabled) {
                    ParameterIntSlide(
                        title = tr("islandSpacingOffset", "大小岛间距调整"),
                        value = spacingSettings.offsetDp,
                        range = ISLAND_SPACING_RANGE,
                        suffix = " dp",
                        defaultValue = ISLAND_SPACING_DEFAULT_OFFSET,
                    ) { offset -> updateSpacing { it.copy(offsetDp = offset) } }
                }
            }
        }
    }
}

@Composable
private fun IslandExpandedPage(openPage: (PageId) -> Unit, back: () -> Unit) = AppPage(
    tr("islandExpandedPage", "超级岛展开态"),
    back,
    restartScopes = setOf(ScopeApplication.SYSTEM_UI),
) { p, scroll ->
    val (s, update) = rememberNotificationIslandSettings()
    AppList(p, scroll, 28) {
        item {
            Group(tr("islandExpandedBackgroundGroup", "背景")) {
                SwitchPreference(
                    title = tr("超级岛展开态背景调整", "超级岛展开态背景调整"),
                    checked = s.expandedIslandBackgroundEnabled,
                    onCheckedChange = { enabled -> update { it.copy(expandedIslandBackgroundEnabled = enabled) } },
                )
                AnimatedVisibility(
                    visible = s.expandedIslandBackgroundEnabled,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    ArrowPreference(
                        title = tr("超级岛展开态背景参数", "超级岛展开态背景参数"),
                        onClick = { openPage(PageId.ISLAND_EXPANDED_BACKGROUND) },
                    )
                }
            }
        }
        item {
            Group(tr("islandExpandedMediaGroup", "媒体内容")) {
                SwitchPreference(
                    title = tr("media_island_info_vertical_offset", "调整超级岛歌曲信息行垂直定位"),
                    checked = s.mediaIslandInfoVerticalOffsetEnabled,
                    onCheckedChange = { enabled -> update { it.copy(mediaIslandInfoVerticalOffsetEnabled = enabled) } },
                )
                AnimatedVisibility(visible = s.mediaIslandInfoVerticalOffsetEnabled) {
                    IntSlide(
                        tr("media_island_info_vertical_offset", "调整超级岛歌曲信息行垂直定位"),
                        s.mediaIslandInfoVerticalOffset,
                        -50..50,
                        defaultValue = 0,
                    ) { value -> update { it.copy(mediaIslandInfoVerticalOffset = value) } }
                }
                SwitchPreference(
                    title = tr("hide_media_island_source_icon", "隐藏媒体超级岛来源图标"),
                    checked = s.hideMediaIslandSourceIcon,
                    onCheckedChange = { value -> update { it.copy(hideMediaIslandSourceIcon = value) } },
                )
                SwitchPreference(
                    title = tr("关闭媒体超级岛底部的氛围光感", "关闭媒体超级岛底部的氛围光感"),
                    checked = s.disableMediaIslandBottomGlow,
                    onCheckedChange = { value -> update { it.copy(disableMediaIslandBottomGlow = value) } },
                )
            }
        }
    }
}

@Composable
private fun IslandExpandedBackgroundPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { ExpandedIslandGlassSettingsStore(context) }
    var state by remember { mutableStateOf(store.settings) }
    val changed = LocalNotificationSettingsChanged.current
    val (s, update) = rememberNotificationIslandSettings()
    LaunchedEffect(service) { service?.let { store.syncRemote(it); state = store.settings } }
    fun updateGlass(transform: (ExpandedIslandGlassSettings) -> ExpandedIslandGlassSettings) {
        store.update(service, transform)
        state = store.settings
        changed()
    }
    AppPage(tr("超级岛展开态背景参数", "超级岛展开态背景参数"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
        AppList(p, scroll, 28) {
            item {
                Group(tr("柔光玻璃", "柔光玻璃")) {
                    SwitchPreference(
                        title = tr("使用全局柔光玻璃参数", "使用全局柔光玻璃参数"),
                        checked = state.useGlobal,
                        onCheckedChange = { value -> updateGlass { it.copy(useGlobal = value) } },
                    )
                    AnimatedVisibility(
                        visible = !state.useGlobal,
                        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                    ) {
                        SoftGlassMaterialSliders(state.glass, includeBackdrop = false) { transform ->
                            updateGlass { it.copy(glass = transform(it.glass)) }
                        }
                    }
                }
            }
            item {
                Group(tr("islandExpandedBackgroundGroup", "背景")) {
                    ParameterIntSlide(
                        tr("背景不透明度", "背景不透明度"),
                        s.expandedIslandBackgroundOpacity,
                        0..100,
                        "%",
                        defaultValue = 97,
                    ) { value -> update { it.copy(expandedIslandBackgroundOpacity = value) } }
                }
            }
        }
    }
}

@Composable
private fun rememberNotificationIslandSettings(): Pair<NotificationIslandSettings, ((NotificationIslandSettings) -> NotificationIslandSettings) -> Unit> {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { NotificationIslandSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    val onChanged = LocalNotificationSettingsChanged.current
    DisposableEffect(store) {
        val preferences = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            store.reload()
            settings = store.settings
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        settings = store.settings
    }
    return settings to { transform ->
        val before = store.settings
        store.update(service, transform)
        settings = store.settings
        if (before != settings) onChanged()
    }
}

@Composable
private fun UnlockIslandPage(back: () -> Unit) = AppPage(
    tr("unlockIslandPage", "解锁岛"),
    back,
    restartScopes = setOf(ScopeApplication.SYSTEM_UI),
) { padding, scroll ->
    val context = LocalContext.current
    val faceUnlockStore = remember(context) { LockscreenFaceUnlockSettingsStore(context) }
    val faceUnlockService by HookApplication.service.collectAsStateWithLifecycle()
    var faceUnlockSettings by remember { mutableStateOf(faceUnlockStore.settings) }
    fun updateFaceUnlock(transform: (LockscreenFaceUnlockSettings) -> LockscreenFaceUnlockSettings) {
        faceUnlockStore.update(faceUnlockService, transform)
        faceUnlockSettings = faceUnlockStore.settings
    }
    LaunchedEffect(faceUnlockService) {
        faceUnlockService?.let(faceUnlockStore::syncRemote)
        faceUnlockSettings = faceUnlockStore.settings
    }
    AppList(padding, scroll, 12) {
        item {
            Group(tr("unlockIslandPage", "解锁岛")) {
                SwitchPreference(
                    title = tr("disableFaceUnlockIcon", "禁用系统的人脸解锁图标"),
                    checked = faceUnlockSettings.disableIcon,
                    onCheckedChange = { value -> updateFaceUnlock { it.copy(disableIcon = value) } },
                )
                SwitchPreference(
                    title = tr("faceUnlockIsland", "人脸解锁岛"),
                    checked = faceUnlockSettings.unlockIsland,
                    onCheckedChange = { value -> updateFaceUnlock { it.copy(unlockIsland = value) } },
                )
                SwitchPreference(
                    title = tr("faceUnlockFirstFloat", "人脸解锁岛首次悬浮"),
                    checked = faceUnlockSettings.firstFloat,
                    onCheckedChange = { value -> updateFaceUnlock { it.copy(firstFloat = value) } },
                )
                SwitchPreference(
                    title = tr("faceUnlockKeepUntilHidden", "人脸解锁成功后保持显示"),
                    checked = faceUnlockSettings.keepUntilKeyguardHidden,
                    onCheckedChange = { value -> updateFaceUnlock { it.copy(keepUntilKeyguardHidden = value) } },
                )
                OverlayDropdownPreference(
                    title = tr("faceUnlockAnimationStyle", "人脸解锁岛动画"),
                    items = listOf(
                        tr("faceUnlockAnimationDefault", "默认"),
                        tr("faceUnlockAnimationLock", "解锁锁动画"),
                        tr("faceUnlockAnimationLock2", "解锁锁动画 2"),
                    ),
                    selectedIndex = when (faceUnlockSettings.animationStyle) {
                        "lock" -> 1
                        "lock_2" -> 2
                        else -> 0
                    },
                    onSelectedIndexChange = { index ->
                        updateFaceUnlock {
                            it.copy(animationStyle = listOf("default", "lock", "lock_2")[index.coerceIn(0, 2)])
                        }
                    },
                )
                SwitchPreference(
                    title = tr("unlockAllFocus", "解锁所有焦点通知与超级岛"),
                    checked = faceUnlockSettings.unlockAllFocus,
                    onCheckedChange = { value -> updateFaceUnlock { it.copy(unlockAllFocus = value) } },
                )
                SwitchPreference(
                    title = tr("unlockFocusAuth", "解锁焦点通知认证限制"),
                    checked = faceUnlockSettings.unlockFocusAuth,
                    onCheckedChange = { value -> updateFaceUnlock { it.copy(unlockFocusAuth = value) } },
                )
            }
        }
    }
}

@Composable
private fun NotificationRestrictions(back: () -> Unit) = AppPage(
    tr("通知限制去除", "通知限制去除"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI),
) { p, scroll ->
    val (s, update) = rememberNotificationIslandSettings()
    AppList(p, scroll) {
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                SwitchPreference(
                    title = tr("hide_notification_mini_window_bar", "隐藏通知底部的小窗下拉提示线"),
                    checked = s.hideNotificationMiniWindowBar,
                    onCheckedChange = { value -> update { it.copy(hideNotificationMiniWindowBar = value) } },
                )
                SwitchPreference(
                    title = tr("\u53bb\u9664\u7126\u70b9\u901a\u77e5\u4e0e\u8d85\u7ea7\u5c9b\u767d\u540d\u5355\u9650\u5236", "\u53bb\u9664\u7126\u70b9\u901a\u77e5\u4e0e\u8d85\u7ea7\u5c9b\u767d\u540d\u5355\u9650\u5236"),
                    checked = s.removeFocusAndIslandWhitelistLimit,
                    onCheckedChange = { value ->
                        update { it.copy(removeFocusAndIslandWhitelistLimit = value) }
                    },
                )
                SwitchPreference(
                    title = tr("\u4fdd\u7559\u901a\u77e5", "\u4fdd\u7559\u901a\u77e5"),
                    checked = s.keepNotifications,
                    onCheckedChange = { value -> update { it.copy(keepNotifications = value) } },
                )
                SwitchPreference(
                    title = tr("\u5141\u8bb8\u6240\u6709\u901a\u77e5\u5728\u9501\u5c4f\u4e0a\u663e\u793a", "\u5141\u8bb8\u6240\u6709\u901a\u77e5\u5728\u9501\u5c4f\u4e0a\u663e\u793a"),
                    checked = s.allowAllNotificationsOnLockscreen,
                    onCheckedChange = { value -> update { it.copy(allowAllNotificationsOnLockscreen = value) } },
                )
                SwitchPreference(
                    title = tr("\u5f3a\u5236\u6240\u6709\u901a\u77e5\u4e3a\u60ac\u6d6e\u901a\u77e5", "\u5f3a\u5236\u6240\u6709\u901a\u77e5\u4e3a\u60ac\u6d6e\u901a\u77e5"),
                    checked = s.forceAllNotificationsHeadsUp,
                    onCheckedChange = { value -> update { it.copy(forceAllNotificationsHeadsUp = value) } },
                )
                SwitchPreference(
                    title = tr("\u53bb\u9664\u901a\u77e5\u7684\u91cd\u8981\u7a0b\u5ea6\u9650\u5236", "\u53bb\u9664\u901a\u77e5\u7684\u91cd\u8981\u7a0b\u5ea6\u9650\u5236"),
                    checked = s.removeNotificationImportanceLimit,
                    onCheckedChange = { value -> update { it.copy(removeNotificationImportanceLimit = value) } },
                )
                SwitchPreference(
                    title = tr("去除已通过蓝牙设备解锁的Toast通知", "去除已通过蓝牙设备解锁的Toast通知"),
                    checked = s.removeBleUnlockToast,
                    onCheckedChange = { value -> update { it.copy(removeBleUnlockToast = value) } },
                )
                SwitchPreference(
                    title = tr("\u7981\u6b62\u6298\u53e0\u4e3a\u5386\u53f2\u901a\u77e5", "\u7981\u6b62\u6298\u53e0\u4e3a\u5386\u53f2\u901a\u77e5"),
                    checked = s.disableNotificationHistoryFolding,
                    onCheckedChange = { value -> update { it.copy(disableNotificationHistoryFolding = value) } },
                )
            }
        }
    }
}

@Composable
private fun NotificationMediaCard(back: () -> Unit) = AppPage(
    tr("notificationMediaCard", "媒体卡片"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI),
) { p, scroll ->
    val (s, update) = rememberNotificationIslandSettings()
    AppList(p, scroll) {
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                SwitchPreference(
                    title = tr("removeDynamicIslandMediaMiniBarWhitelistLimit", "去除媒体控件下拉条白名单限制"),
                    checked = s.removeDynamicIslandMediaMiniBarWhitelistLimit,
                    onCheckedChange = { value -> update { it.copy(removeDynamicIslandMediaMiniBarWhitelistLimit = value) } },
                )
                SwitchPreference(
                    title = tr("hide_system_media_source_icon", "隐藏系统媒体通知来源图标"),
                    checked = s.hideSystemMediaSourceIcon,
                    onCheckedChange = { value -> update { it.copy(hideSystemMediaSourceIcon = value) } },
                )
                IntSlide(tr("system_media_info_vertical_offset", "调整系统媒体通知歌曲信息行垂直定位"), s.systemMediaInfoVerticalOffset, -50..50, defaultValue = 0) { value ->
                    update { it.copy(systemMediaInfoVerticalOffset = value) }
                }
                IntSlide(tr("media_title_artist_spacing", "媒体标题与艺术家行间距"), s.mediaTitleArtistSpacing, -30..30, defaultValue = 0) { value ->
                    update { it.copy(mediaTitleArtistSpacing = value) }
                }
                IntSlide(tr("media_cover_corner_radius_offset", "调整媒体封面圆角大小"), s.mediaCoverCornerRadiusOffset, -30..30, defaultValue = 0) { value ->
                    update { it.copy(mediaCoverCornerRadiusOffset = value) }
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
) = AppPage(tr("\u72b6\u6001\u680f\u4e0e\u63a7\u5236\u4e2d\u5fc3", "\u72b6\u6001\u680f\u4e0e\u63a7\u5236\u4e2d\u5fc3"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val headerHeightStore = remember(context) { ControlCenterHeaderHeightSettingsStore(context) }
    var headerHeightOffset by remember { mutableIntStateOf(headerHeightStore.offsetDp) }
    var headerHeightSlider by remember(headerHeightOffset) { mutableFloatStateOf(headerHeightOffset.toFloat()) }
    val carrierStore = remember(context) { ControlCenterCarrierSettingsStore(context) }
    var carrierSettings by remember { mutableStateOf(carrierStore.settings) }
    var showCarrierTextDialog by remember { mutableStateOf(false) }
    val controlCenterClockStore = remember(context) { ControlCenterClockSettingsStore(context) }
    var controlCenterClockSettings by remember { mutableStateOf(controlCenterClockStore.settings) }
    val adjustmentStore = remember(context) { StatusBarAdjustmentSettingsStore(context) }
    var adjustmentSettings by remember { mutableStateOf(adjustmentStore.settings) }
    var showControlCenterClockFormatDialog by remember { mutableStateOf(false) }
    fun updateControlCenterClock(transform: (ControlCenterClockSettings) -> ControlCenterClockSettings) {
        controlCenterClockStore.update(service, transform)
        controlCenterClockSettings = controlCenterClockStore.settings
    }
    fun updateCarrier(transform: (ControlCenterCarrierSettings) -> ControlCenterCarrierSettings) {
        carrierStore.update(service, transform)
        carrierSettings = carrierStore.settings
    }
    fun updateAdjustment(transform: (StatusBarAdjustmentSettings) -> StatusBarAdjustmentSettings) {
        adjustmentStore.update(service, transform)
        adjustmentSettings = adjustmentStore.settings
    }
    LaunchedEffect(service) {
        service?.let(headerHeightStore::syncRemote)
        headerHeightOffset = headerHeightStore.offsetDp
        service?.let(carrierStore::syncRemote)
        carrierSettings = carrierStore.settings
        service?.let(controlCenterClockStore::syncRemote)
        controlCenterClockSettings = controlCenterClockStore.settings
        service?.let(adjustmentStore::syncRemote)
        adjustmentSettings = adjustmentStore.settings
    }
    AppList(p, scroll, 28) {
        item { Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
            ArrowPreference(title = tr("状态栏调整", "状态栏调整"), onClick = { open(PageId.STATUS_ADJUSTMENT) })
            StatusBarIconImportPreference()
        } }
        item {
            Group(tr("\u72b6\u6001\u680f\u663e\u793a", "\u72b6\u6001\u680f\u663e\u793a")) {
                SwitchPreference(
                    title = tr("\u9690\u85cf\u72b6\u6001\u680f\u7535\u6c60\u5185\u6587\u672c", "\u9690\u85cf\u72b6\u6001\u680f\u7535\u6c60\u5185\u6587\u672c"),
                    checked = s.hideStatusBarClockText,
                    onCheckedChange = { enabled -> update { it.copy(hideStatusBarClockText = enabled) } },
                )
                OverlayDropdownPreference(
                    title = tr("状态栏电池百分比显示", "状态栏电池百分比显示"),
                    items = batteryPercentageModeLabels(),
                    selectedIndex = adjustmentSettings.statusBarBatteryPercentMode,
                    onSelectedIndexChange = { mode -> updateAdjustment { it.copy(statusBarBatteryPercentMode = mode) } },
                )
                ArrowPreference(
                    title = tr("\u72b6\u6001\u680f\u4fe1\u53f7\u81ea\u5b9a\u4e49", "\u72b6\u6001\u680f\u4fe1\u53f7\u81ea\u5b9a\u4e49"),
                    onClick = { open(PageId.STATUS_SIGNAL_CUSTOMIZATION) },
                )
            }
        }
        item {
            Group(tr("\u63a7\u5236\u4e2d\u5fc3", "\u63a7\u5236\u4e2d\u5fc3")) {
                OverlayDropdownPreference(
                    title = tr("控制中心中电池百分比显示", "控制中心中电池百分比显示"),
                    items = batteryPercentageModeLabels(),
                    selectedIndex = adjustmentSettings.controlCenterBatteryPercentMode,
                    onSelectedIndexChange = { mode -> updateAdjustment { it.copy(controlCenterBatteryPercentMode = mode) } },
                )
                ArrowPreference(
                    title = tr("\u63a7\u5236\u4e2d\u5fc3\u5706\u89d2\u8be6\u7ec6\u8bbe\u7f6e", "\u63a7\u5236\u4e2d\u5fc3\u5706\u89d2\u8be6\u7ec6\u8bbe\u7f6e"),
                    onClick = { open(PageId.CONTROL) },
                )
                EditableSliderPreference(
                    title = tr("controlCenterHeaderHeight", "自定义控制中心顶栏高度"),
                    value = headerHeightSlider,
                    valueText = tr("controlCenterHeaderHeightValue", "%s dp").format(
                        headerHeightSlider.roundToInt().let { if (it > 0) "+$it" else it.toString() },
                    ),
                    valueRange = -30f..30f,
                    steps = 59,
                    defaultValue = 0f,
                    onValueChange = { headerHeightSlider = it },
                    onValueChangeFinished = {
                        headerHeightStore.update(service, headerHeightSlider.roundToInt())
                        headerHeightOffset = headerHeightStore.offsetDp
                    },
                )
                SwitchPreference(
                    title = tr("controlCenterBigClock", "在控制中心顶栏日期之上添加大时钟"),
                    checked = controlCenterClockSettings.enabled,
                    onCheckedChange = { enabled -> updateControlCenterClock { it.copy(enabled = enabled) } },
                )
                AnimatedVisibility(visible = controlCenterClockSettings.enabled) {
                    Column {
                        ArrowPreference(
                            title = tr("controlCenterClockFormat", "大时钟格式"),
                            summary = controlCenterClockSettings.format,
                            onClick = { showControlCenterClockFormatDialog = true },
                        )
                        ParameterFloatSlide(
                            title = tr("controlCenterClockSize", "大时钟大小"),
                            value = controlCenterClockSettings.sizeSp,
                            range = 8f..64f,
                            defaultValue = 48f,
                        ) { value -> updateControlCenterClock { it.copy(sizeSp = value) } }
                        ParameterFloatSlide(
                            title = tr("controlCenterClockGap", "大时钟与日期行间距"),
                            value = controlCenterClockSettings.gapDp,
                            range = -20f..40f,
                            defaultValue = 8f,
                        ) { value -> updateControlCenterClock { it.copy(gapDp = value) } }
                        SwitchPreference(
                            title = tr("controlCenterClockRedOne", "一加红\"1\""),
                            checked = controlCenterClockSettings.redOne,
                            onCheckedChange = { enabled -> updateControlCenterClock { it.copy(redOne = enabled) } },
                        )
                    }
                }
                OverlayDropdownPreference(
                    title = tr("controlCenterCarrierNetworkMarker", "运营商网络标识"),
                    items = listOf(
                        tr("不隐藏", "不隐藏"),
                        tr("controlCenterCarrierHideHd", "隐藏 HD"),
                        tr("controlCenterCarrierRealNetworkType", "替换为真实数据网络类型"),
                    ),
                    selectedIndex = carrierSettings.hdMode,
                    onSelectedIndexChange = { mode -> updateCarrier { it.copy(hdMode = mode) } },
                )
                SwitchPreference(
                    title = tr("controlCenterCarrierCustom", "自定义运营商文本"),
                    checked = carrierSettings.customEnabled,
                    onCheckedChange = { enabled -> updateCarrier { it.copy(customEnabled = enabled) } },
                )
                AnimatedVisibility(visible = carrierSettings.customEnabled) {
                    ArrowPreference(
                        title = tr("controlCenterCarrierText", "运营商文本"),
                        summary = carrierSettings.customText.ifBlank { tr("未设置", "未设置") },
                        onClick = { showCarrierTextDialog = true },
                    )
                }
                OverlayDropdownPreference(
                    title = tr("controlCenterCarrierHide", "隐藏运营商"),
                    items = listOf(
                        tr("不隐藏", "不隐藏"),
                        tr("隐藏卡1", "隐藏卡1"),
                        tr("隐藏卡2", "隐藏卡2"),
                        tr("controlCenterCarrierHideNonData", "隐藏非上网卡"),
                        tr("隐藏上网卡", "隐藏上网卡"),
                        tr("全部隐藏", "全部隐藏"),
                    ),
                    selectedIndex = carrierSettings.hideMode,
                    onSelectedIndexChange = { mode -> updateCarrier { it.copy(hideMode = mode) } },
                )
                OverlayDropdownPreference(
                    title = tr("控制中心 5G 开关磁贴", "控制中心 5G 开关磁贴"),
                    items = listOf(
                        tr("隐藏", "隐藏"),
                        tr("字重 Regular", "字重 Regular"),
                        tr("字重 Semi Bold", "字重 Semi Bold"),
                        tr("字重 Black", "字重 Black"),
                        tr("图形", "图形"),
                    ),
                    selectedIndex = s.controlCenter5GTileMode.coerceIn(0, 4),
                    onSelectedIndexChange = { value -> update { it.copy(controlCenter5GTileMode = value) } },
                )
                SwitchPreference(
                    title = tr("控制中心添加 Google 服务磁贴", "控制中心添加 Google 服务磁贴"),
                    checked = s.controlCenterGmsTileEnabled,
                    onCheckedChange = { value -> update { it.copy(controlCenterGmsTileEnabled = value) } },
                )
                SwitchPreference(
                    title = tr("\u63a7\u5236\u4e2d\u5fc3\u9690\u85cf\u5e95\u90e8\u7f16\u8f91\u6309\u94ae", "\u63a7\u5236\u4e2d\u5fc3\u9690\u85cf\u5e95\u90e8\u7f16\u8f91\u6309\u94ae"),
                    checked = s.hideControlCenterEditButton,
                    onCheckedChange = { enabled -> update { it.copy(hideControlCenterEditButton = enabled) } },
                )
                ArrowPreference(
                    title = tr("控制中心顶部按钮", "控制中心顶部按钮"),
                    onClick = { open(PageId.CONTROL_CENTER_TOP_BUTTONS) },
                )
            }
        }
    }
    if (showControlCenterClockFormatDialog && controlCenterClockSettings.enabled) {
        val invalidFormatText = tr("controlCenterClockInvalidFormat", "请输入有效的时间格式，例如 HH:mm:ss")
        DeviceProfileFieldDialog(
            title = tr("controlCenterClockFormat", "大时钟格式"),
            current = controlCenterClockSettings.format,
            inputLabel = tr("controlCenterClockFormat", "大时钟格式"),
            validationError = { pattern -> if (isValidControlCenterClockFormat(pattern)) null else invalidFormatText },
            onDismiss = { showControlCenterClockFormatDialog = false },
            onSave = { pattern ->
                updateControlCenterClock { it.copy(format = pattern) }
                showControlCenterClockFormatDialog = false
            },
        )
    }
    if (showCarrierTextDialog) {
        DeviceProfileFieldDialog(
            title = tr("controlCenterCarrierCustom", "自定义运营商文本"),
            current = carrierSettings.customText,
            inputLabel = tr("controlCenterCarrierText", "运营商文本"),
            onDismiss = { showCarrierTextDialog = false },
            onSave = { text ->
                updateCarrier { it.copy(customText = text) }
                showCarrierTextDialog = false
            },
        )
    }
}

@Composable
private fun ControlCenterTopButtonsPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { ControlCenterTopButtonsGlassSettingsStore(context) }
    var state by remember { mutableStateOf(store.settings) }
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        state = store.settings
    }
    fun update(transform: (ControlCenterTopButtonsGlassSettings) -> ControlCenterTopButtonsGlassSettings) {
        store.update(service, transform)
        state = store.settings
    }
    AppPage(tr("控制中心顶部按钮", "控制中心顶部按钮"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
        AppList(p, scroll) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(
                        title = tr("控制中心添加顶部控制按钮", "控制中心添加顶部控制按钮"),
                        checked = state.enabled,
                        onCheckedChange = { value -> update { it.copy(enabled = value) } },
                    )
                    AnimatedVisibility(
                        visible = state.enabled,
                        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                    ) {
                        SwitchPreference(
                            title = tr("横屏显示控制中心顶部控制按钮", "横屏显示控制中心顶部控制按钮"),
                            checked = state.showInLandscape,
                            onCheckedChange = { value -> update { it.copy(showInLandscape = value) } },
                        )
                    }
                }
            }
            item {
                AnimatedVisibility(
                    visible = state.enabled,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    Group(tr("材质设置", "材质设置")) {
                        ParameterFloatSlide(
                            title = tr("顶部按钮图标缩放", "顶部按钮图标缩放"),
                            value = state.iconScale,
                            range = .5f..2f,
                            defaultValue = 1f,
                        ) { value -> update { it.copy(iconScale = value) } }
                        OverlayDropdownPreference(
                            title = tr("顶部按钮背景材质", "顶部按钮背景材质"),
                            items = listOf(tr("不显示", "不显示"), tr("纯色", "纯色"), tr("高级材质", "高级材质"), tr("柔光玻璃", "柔光玻璃")),
                            selectedIndex = state.backgroundMode,
                            onSelectedIndexChange = { value -> update { it.copy(backgroundMode = value) } },
                        )
                        AnimatedVisibility(
                            visible = state.backgroundMode != CONTROL_CENTER_TOP_BUTTONS_MODE_NONE,
                            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                when (state.backgroundMode) {
                                    CONTROL_CENTER_TOP_BUTTONS_MODE_PURE -> {
                                        ShortcutBackgroundColorPreference(
                                            color = state.pureColor,
                                            onColorChange = { value -> update { it.copy(pureColor = value) } },
                                        )
                                        ParameterDpSlide(tr("顶部按钮背景圆形半径", "顶部按钮背景圆形半径"), state.pureRadius, 0f..22f, 22f) { value -> update { it.copy(pureRadius = value) } }
                                    }
                                    CONTROL_CENTER_TOP_BUTTONS_MODE_ADVANCED -> {
                                        ShortcutBackgroundColorPreference(
                                            title = tr("混色颜色", "混色颜色"),
                                            color = state.advancedColor,
                                            onColorChange = { value -> update { it.copy(advancedColor = value) } },
                                        )
                                        ParameterDpSlide(tr("顶部按钮背景圆形半径", "顶部按钮背景圆形半径"), state.advancedRadius, 0f..22f, 22f) { value -> update { it.copy(advancedRadius = value) } }
                                        ParameterIntSlide(tr("不透明度", "不透明度"), state.advancedOpacity, 0..100, "%", 14) { value -> update { it.copy(advancedOpacity = value) } }
                                        ParameterIntSlide(tr("背景模糊度", "背景模糊度"), state.advancedBlur, 0..40, " px", 40) { value -> update { it.copy(advancedBlur = value) } }
                                        SwitchPreference(
                                            title = tr("显示高光", "显示高光"),
                                            checked = state.advancedHighlight,
                                            onCheckedChange = { value -> update { it.copy(advancedHighlight = value) } },
                                        )
                                    }
                                    CONTROL_CENTER_TOP_BUTTONS_MODE_SOFT_GLASS -> {
                                        ParameterDpSlide(tr("顶部按钮背景圆形半径", "顶部按钮背景圆形半径"), state.softRadius, 0f..22f, 22f) { value -> update { it.copy(softRadius = value) } }
                                        SwitchPreference(
                                            title = tr("使用全局柔光玻璃参数", "使用全局柔光玻璃参数"),
                                            checked = state.useGlobal,
                                            onCheckedChange = { value -> update { it.copy(useGlobal = value) } },
                                        )
                                        AnimatedVisibility(
                                            visible = !state.useGlobal,
                                            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                                            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                                        ) {
                                            SoftGlassMaterialSliders(state.glass, includeBackdrop = true) { transform ->
                                                update { it.copy(glass = transform(it.glass)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusSignalCustomization(
    s: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    open: (PageId) -> Unit,
    back: () -> Unit,
) = AppPage(tr("\u72b6\u6001\u680f\u4fe1\u53f7\u81ea\u5b9a\u4e49", "\u72b6\u6001\u680f\u4fe1\u53f7\u81ea\u5b9a\u4e49"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
    var showCustomTextDialog by remember { mutableStateOf(false) }
    var customTextDraft by remember { mutableStateOf(s.mobileNetworkTypeCustomText) }

    AppList(p, scroll, 28) {
        item {
            Group(tr("\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b", "\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b")) {
                SwitchPreference(
                    title = tr("\u53cc\u6392\u4fe1\u53f7", "\u53cc\u6392\u4fe1\u53f7"),
                    summary = tr("\u5c06\u53cc\u5361\u4fe1\u53f7\u5408\u5e76\u4e3a\u4e0a\u4e0b\u53cc\u6392\u663e\u793a", "\u5c06\u53cc\u5361\u4fe1\u53f7\u5408\u5e76\u4e3a\u4e0a\u4e0b\u53cc\u6392\u663e\u793a"),
                    checked = s.stackedMobileSignalEnabled,
                    onCheckedChange = { enabled -> update { it.copy(stackedMobileSignalEnabled = enabled) } },
                )
                ArrowPreference(
                    title = tr("\u4fe1\u53f7\u4e0e\u7f51\u7edc\u7c7b\u578b\u8c03\u6574", "\u4fe1\u53f7\u4e0e\u7f51\u7edc\u7c7b\u578b\u8c03\u6574"),
                    onClick = { open(PageId.STATUS_SIGNAL_TUNING) },
                )
OverlayDropdownPreference(
                    title = tr("\u9690\u85cf\u7cfb\u7edf\u9ed8\u8ba4\u4fe1\u53f7\u56fe\u6807", "\u9690\u85cf\u7cfb\u7edf\u9ed8\u8ba4\u4fe1\u53f7\u56fe\u6807"),
                    items = listOf(tr("\u4e0d\u9690\u85cf", "\u4e0d\u9690\u85cf"), tr("\u9690\u85cf\u975e\u4e0a\u7f51\u5361", "\u9690\u85cf\u975e\u4e0a\u7f51\u5361"), tr("\u5168\u90e8\u9690\u85cf", "\u5168\u90e8\u9690\u85cf")),
                    // Dual-row rendering always suppresses the original signal icons.
                    // Keep the saved selection untouched so it is restored when disabled.
                    selectedIndex = if (s.stackedMobileSignalEnabled) 2 else s.mobileSignalHideMode.coerceIn(0, 2),
                    enabled = !s.stackedMobileSignalEnabled,
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
                        SwitchPreference(
                            title = tr("\u72ec\u7acb\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b\u7c97\u4f53\u663e\u793a", "\u72ec\u7acb\u79fb\u52a8\u7f51\u7edc\u7c7b\u578b\u7c97\u4f53\u663e\u793a"),
                            checked = s.mobileNetworkTypeBold,
                            onCheckedChange = { value -> update { it.copy(mobileNetworkTypeBold = value) } },
                        )
                    }
                }
            }
        }
        item {
            Group(tr("\u56fe\u6807\u663e\u793a", "\u56fe\u6807\u663e\u793a")) {
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
}

@Composable
private fun StatusSignalTuning(
    s: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    back: () -> Unit,
) = AppPage(
    tr("\u4fe1\u53f7\u4e0e\u7f51\u7edc\u7c7b\u578b\u8c03\u6574", "\u4fe1\u53f7\u4e0e\u7f51\u7edc\u7c7b\u578b\u8c03\u6574"),
    back,
    restartScopes = setOf(ScopeApplication.SYSTEM_UI),
) { p, scroll ->
    AppList(p, scroll, 28) {
        item {
            Group(tr("调整双排信号", "调整双排信号")) {
                StatusSignalTuningSlider(
                    title = tr("缩放", "缩放"),
                    value = s.stackedMobileSignalScale,
                    defaultValue = 1f,
                    range = 0.1f..3f,
                    suffix = "x",
                    onValueChangeFinished = { value -> update { it.copy(stackedMobileSignalScale = value) } },
                )
                StatusSignalTuningSlider(
                    title = tr("上下偏移量", "上下偏移量"),
                    value = s.stackedMobileSignalVerticalOffset,
                    defaultValue = 0f,
                    range = -8f..8f,
                    suffix = "dp",
                    onValueChangeFinished = { value -> update { it.copy(stackedMobileSignalVerticalOffset = value) } },
                )
                StatusSignalTuningSlider(
                    title = tr("左间距", "左间距"),
                    value = s.stackedMobileSignalLeftMargin,
                    defaultValue = 0f,
                    range = -8f..8f,
                    suffix = "dp",
                    onValueChangeFinished = { value -> update { it.copy(stackedMobileSignalLeftMargin = value) } },
                )
                StatusSignalTuningSlider(
                    title = tr("右间距", "右间距"),
                    value = s.stackedMobileSignalRightMargin,
                    defaultValue = 0f,
                    range = -8f..8f,
                    suffix = "dp",
                    onValueChangeFinished = { value -> update { it.copy(stackedMobileSignalRightMargin = value) } },
                )
            }
        }
        item {
            Group(tr("调整移动网络类型", "调整移动网络类型")) {
                StatusSignalTuningSlider(
                    title = tr("缩放", "缩放"),
                    value = s.mobileNetworkTypeScale,
                    defaultValue = 1f,
                    range = 0.1f..3f,
                    suffix = "x",
                    onValueChangeFinished = { value -> update { it.copy(mobileNetworkTypeScale = value) } },
                )
                StatusSignalTuningSlider(
                    title = tr("上下偏移量", "上下偏移量"),
                    value = s.mobileNetworkTypeVerticalOffset,
                    defaultValue = 0f,
                    range = -8f..8f,
                    suffix = "dp",
                    onValueChangeFinished = { value -> update { it.copy(mobileNetworkTypeVerticalOffset = value) } },
                )
                StatusSignalTuningSlider(
                    title = tr("左间距", "左间距"),
                    value = s.mobileNetworkTypeLeftMargin,
                    defaultValue = 0f,
                    range = -8f..8f,
                    suffix = "dp",
                    onValueChangeFinished = { value -> update { it.copy(mobileNetworkTypeLeftMargin = value) } },
                )
                StatusSignalTuningSlider(
                    title = tr("右间距", "右间距"),
                    value = s.mobileNetworkTypeRightMargin,
                    defaultValue = 0f,
                    range = -8f..8f,
                    suffix = "dp",
                    onValueChangeFinished = { value -> update { it.copy(mobileNetworkTypeRightMargin = value) } },
                )
            }
        }
    }
}

private fun batteryPercentageModeLabels() = listOf(
    tr("系统默认", "系统默认"),
    tr("显示在电池内", "显示在电池内"),
    tr("显示在电池外", "显示在电池外"),
    tr("不显示百分比", "不显示百分比"),
)

@Composable
private fun StatusAdjustment(
    s: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    back: () -> Unit,
) = AppPage(tr("状态栏调整", "状态栏调整"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { StatusBarAdjustmentSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    val spacingStore = remember(context) { StatusBarIconSpacingSettingsStore(context) }
    var spacingPercent by remember { mutableIntStateOf(spacingStore.spacingPercent) }
    var spacingSlider by remember(spacingPercent) { mutableFloatStateOf(spacingPercent.toFloat()) }
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        settings = store.settings
        service?.let(spacingStore::syncRemote)
        spacingPercent = spacingStore.spacingPercent
    }
    fun save(transform: (StatusBarAdjustmentSettings) -> StatusBarAdjustmentSettings) {
        store.update(service, transform)
        settings = store.settings
    }
    AppList(p, scroll, 28) {
        item { Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
            Dim(tr("时钟大小", "时钟大小"), s.clockEnabled, { v -> update { it.copy(clockEnabled = v) } }, s.clockSize, 10f..24f, defaultValue = 14.8f) { v -> update { it.copy(clockSize = v) } }
            RelativePercentSlide(
                tr("状态栏图标大小", "状态栏图标大小"),
                settings.iconSizePercent,
            ) { value -> save { it.copy(iconSizePercent = value) } }
            EditableSliderPreference(
                title = tr("statusBarIconSpacing", "状态栏图标间距"),
                value = spacingSlider,
                valueText = if (spacingSlider.roundToInt() == 0) {
                    tr("statusBarIconSpacingDefault", "0%（系统默认）")
                } else {
                    tr("statusBarIconSpacingValue", "%s%%").format(
                        spacingSlider.roundToInt().let { if (it > 0) "+$it" else it.toString() },
                    )
                },
                valueRange = STATUS_BAR_ICON_SPACING_RANGE.first.toFloat()..STATUS_BAR_ICON_SPACING_RANGE.last.toFloat(),
                steps = 199,
                defaultValue = 0f,
                centerValue = 0f,
                onValueChange = { spacingSlider = it },
                onValueChangeFinished = {
                    spacingStore.update(service, spacingSlider.roundToInt())
                    spacingPercent = spacingStore.spacingPercent
                },
            )
            DeltaDim(tr("右边距", "右边距"), s.paddingEnd) { v -> update { it.copy(paddingEnd = v, paddingEndEnabled = true, paddingEndLegacyAbsolute = null) } }
            Dim(tr("左边距", "左边距"), s.paddingStartEnabled, { v -> update { it.copy(paddingStartEnabled = v) } }, s.paddingStart, 0f..32f, defaultValue = 12.5f) { v -> update { it.copy(paddingStart = v) } }
            Dim(tr("状态栏高度", "状态栏高度"), s.heightEnabled, { v -> update { it.copy(heightEnabled = v) } }, s.statusBarHeight.toFloat(), 24f..72f, defaultValue = 40f) { v -> update { it.copy(statusBarHeight = v.toInt()) } }
            DeltaDim(tr("上边距", "上边距"), s.paddingTop) { v -> update { it.copy(paddingTop = v, paddingTopEnabled = true, paddingTopLegacyAbsolute = null) } }
        } }
    }
}

@Composable
private fun Control(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val changed = LocalNotificationSettingsChanged.current
    val store = remember(context) { ControlCenterRadiusSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        settings = store.settings
    }
    fun save(value: ControlCenterRadiusSettings) {
        store.update(service, value)
        settings = store.settings
        changed()
    }
    AppPage(tr("控制中心", "控制中心"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI)) { p, scroll ->
        AppList(p, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(
                        title = tr("自定义控制中心圆角", "自定义控制中心圆角"),
                        checked = settings.enabled,
                        onCheckedChange = { save(settings.copy(enabled = it)) },
                    )
                }
            }
            item {
                AnimatedVisibility(
                    visible = settings.enabled,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    Group(tr("圆角参数调整", "圆角参数调整")) {
                        ParameterIntSlide(tr("顶部控制按钮圆角", "顶部控制按钮圆角"), settings.topButtons, -40..80, " dp", defaultValue = 0) { save(settings.copy(topButtons = it)) }
                        ParameterIntSlide(tr("媒体卡片圆角", "媒体卡片圆角"), settings.mediaCard, -40..80, " dp", defaultValue = 0) { save(settings.copy(mediaCard = it)) }
                        ParameterIntSlide(tr("音量 / 亮度条圆角", "音量 / 亮度条圆角"), settings.sliders, -40..80, " dp", defaultValue = 0) { save(settings.copy(sliders = it)) }
                        ParameterIntSlide(tr("圆形按钮圆角", "圆形按钮圆角"), settings.smallButtons, -40..80, " dp", defaultValue = 0) { save(settings.copy(smallButtons = it)) }
                        ParameterIntSlide(tr("融合台设备中心卡片圆角", "融合台设备中心卡片圆角"), settings.deviceCenter, -40..80, " dp", defaultValue = 0) { save(settings.copy(deviceCenter = it)) }
                        ParameterIntSlide(tr("展开面板圆角", "展开面板圆角"), settings.expandedPanel, -40..80, " dp", defaultValue = 0) { save(settings.copy(expandedPanel = it)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomDepthWallpaperPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { CustomDepthWallpaperSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        busy = true
        status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val imported = importCustomDepthWallpaperZip(context, uri)
                    val selected = imported.defaultPack
                    activateCustomDepthWallpaper(store, service, imported, selected)
                }
            }
            result.onSuccess {
                settings = store.settings
                status = tr("景深壁纸导入成功，请重启系统界面、壁纸和息屏应用", "景深壁纸导入成功，请重启系统界面、壁纸和息屏应用")
            }.onFailure {
                status = tr("景深壁纸导入失败", "景深壁纸导入失败") + ": " + (it.message ?: tr("未知错误", "未知错误"))
            }
            busy = false
        }
    }
    LaunchedEffect(service) {
        service?.let {
            runCatching { store.syncRemote(it) }
            settings = store.settings
        }
    }
    AppPage(
        tr("自定义景深壁纸", "自定义景深壁纸"),
        back,
        restartScopes = setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.WALLPAPER, ScopeApplication.AOD, ScopeApplication.THEME_MANAGER),
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Group(tr("壁纸包", "壁纸包")) {
                    ArrowPreference(
                        title = tr("导入壁纸 ZIP", "导入壁纸 ZIP"),
                        summary = if (busy) tr("正在导入…", "正在导入…") else tr("选择 HyperChanger 景深壁纸包", "选择 HyperChanger 景深壁纸包"),
                        enabled = !busy,
                        onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    )
                    if (settings.installedPacks.isNotEmpty()) {
                        OverlayDropdownPreference(
                            title = tr("当前壁纸包", "当前壁纸包"),
                            items = settings.installedPacks,
                            selectedIndex = settings.installedPacks.indexOf(settings.activePack).coerceAtLeast(0),
                            enabled = !busy,
                            onSelectedIndexChange = { index ->
                                runCatching {
                                    selectInstalledCustomDepthWallpaper(store, service, settings.installedPacks[index])
                                    settings = store.settings
                                }.onFailure { status = it.message ?: tr("切换失败", "切换失败") }
                            },
                        )
                    } else {
                        ArrowPreference(
                            title = tr("已安装壁纸包", "已安装壁纸包"),
                            summary = tr("没有找到有效的壁纸包", "没有找到有效的壁纸包"),
                            enabled = false,
                            onClick = {},
                        )
                    }
                    SwitchPreference(
                        title = if (settings.enabled) tr("启用景深壁纸", "启用景深壁纸") else tr("停用景深壁纸", "停用景深壁纸"),
                        checked = settings.enabled,
                        enabled = settings.activePack.isNotBlank() && !busy,
                        onCheckedChange = { enabled ->
                            store.update(service) { it.copy(enabled = enabled) }
                            settings = store.settings
                            status = tr("需要重启系统界面、壁纸和息屏应用", "需要重启系统界面、壁纸和息屏应用")
                        },
                    )
                }
            }
            if (status != null) {
                item {
                    Text(
                        status!!,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Lock(
    s: HookSettings,
    capsule: LockscreenCapsuleSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    open: (PageId) -> Unit,
    back: () -> Unit,
) = AppPage(
    tr("\u9501\u5c4f", "\u9501\u5c4f"),
    back,
    restartScopes = setOf(
        ScopeApplication.SYSTEM_UI,
        ScopeApplication.WALLPAPER,
        ScopeApplication.AOD,
    ),
) { p, scroll ->
    val context = LocalContext.current
    val shortcutGestureStore = remember(context) { LockscreenShortcutBackgroundSettingsStore(context) }
    val shortcutGestureService by HookApplication.service.collectAsStateWithLifecycle()
    var shortcutGestureMode by remember { mutableIntStateOf(shortcutGestureStore.settings.gestureMode) }
    LaunchedEffect(shortcutGestureService) {
        shortcutGestureService?.let {
            shortcutGestureStore.syncRemote(it)
            shortcutGestureMode = shortcutGestureStore.settings.gestureMode
        }
    }
    val updateShortcutGesture: (Int) -> Unit = { value ->
        shortcutGestureStore.updateGestureMode(shortcutGestureService, value)
        shortcutGestureMode = shortcutGestureStore.settings.gestureMode
    }
    val clockHorizontalStore = remember(context) { LockscreenClockHorizontalSettingsStore(context) }
    val clockHorizontalService by HookApplication.service.collectAsStateWithLifecycle()
    var clockHorizontalEnabled by remember { mutableStateOf(clockHorizontalStore.enabled) }
    val lockscreenNotificationOffsetStore = remember(context) {
        LockscreenNotificationBottomOffsetSettingsStore(context)
    }
    var lockscreenNotificationOffsetSettings by remember {
        mutableStateOf(lockscreenNotificationOffsetStore.settings)
    }
    LaunchedEffect(clockHorizontalService) {
        clockHorizontalService?.let {
            clockHorizontalStore.syncRemote(it)
            clockHorizontalEnabled = clockHorizontalStore.enabled
            lockscreenNotificationOffsetStore.syncRemote(it)
            lockscreenNotificationOffsetSettings = lockscreenNotificationOffsetStore.settings
        }
    }

    var showSaveWallpaperDialog by remember { mutableStateOf(false) }
    val pickEditorBackground = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val target = lockscreenEditorBackgroundFile(context)
            val staging = File(target.parentFile, "${target.name}.new")
            context.contentResolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            } ?: error("open image failed")
            if (!staging.renameTo(target)) error("replace image failed")
            val mime = context.contentResolver.getType(uri).orEmpty().ifBlank { "image/*" }
            update { it.copy(lockscreenEditorBackgroundMime = mime) }
            Toast.makeText(context, tr("已导入", "已导入"), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, tr("导入失败", "导入失败"), Toast.LENGTH_SHORT).show()
        }
    }
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
                ArrowPreference(
                    title = tr("保存当前壁纸", "保存当前壁纸"),
                    onClick = { showSaveWallpaperDialog = true },
                )
                OverlayDropdownPreference(
                    title = tr("锁屏编辑页背景自定义", "锁屏编辑页背景自定义"),
                    items = listOf(
                        tr("系统默认", "系统默认"),
                        tr("同步锁屏壁纸", "同步锁屏壁纸"),
                        tr("自定义", "自定义"),
                    ),
                    selectedIndex = s.lockscreenEditorBackgroundMode.coerceIn(
                        LOCKSCREEN_EDITOR_BACKGROUND_SYSTEM,
                        LOCKSCREEN_EDITOR_BACKGROUND_CUSTOM,
                    ),
                    onSelectedIndexChange = { value ->
                        update { it.copy(lockscreenEditorBackgroundMode = value) }
                    },
                )
                AnimatedVisibility(
                    visible = s.lockscreenEditorBackgroundMode != LOCKSCREEN_EDITOR_BACKGROUND_SYSTEM,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    Column {
                        ParameterIntSlide(
                            title = tr("背景图模糊度", "背景图模糊度"),
                            value = s.lockscreenEditorBackgroundBlur,
                            range = 0..100,
                            suffix = "%",
                            defaultValue = 0,
                        ) { value -> update { it.copy(lockscreenEditorBackgroundBlur = value) } }
                        ParameterIntSlide(
                            title = tr("背景图不透明度", "背景图不透明度"),
                            value = s.lockscreenEditorBackgroundOpacity,
                            range = 0..100,
                            suffix = "%",
                            defaultValue = 100,
                        ) { value -> update { it.copy(lockscreenEditorBackgroundOpacity = value) } }
                    }
                }
                AnimatedVisibility(
                    visible = s.lockscreenEditorBackgroundMode == LOCKSCREEN_EDITOR_BACKGROUND_CUSTOM,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    Column {
                        ArrowPreference(
                            title = tr("选择自定义图片", "选择自定义图片"),
                            summary = s.lockscreenEditorBackgroundMime.ifBlank { tr("未选择", "未选择") },
                            onClick = { pickEditorBackground.launch(arrayOf("image/*")) },
                        )
                        ArrowPreference(
                            title = tr("清除自定义图片", "清除自定义图片"),
                            summary = if (s.lockscreenEditorBackgroundMime.isBlank()) tr("无图片", "无图片") else tr("已选择", "已选择"),
                            onClick = {
                                lockscreenEditorBackgroundFile(context).delete()
                                update { it.copy(lockscreenEditorBackgroundMime = "") }
                            },
                        )
                    }
                }
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
            Group(tr("\u65f6\u949f\u4e0e\u72b6\u6001\u680f", "\u65f6\u949f\u4e0e\u72b6\u6001\u680f")) {
                SwitchPreference(
                    title = tr("去除时钟的水平位置移动限制", "去除时钟的水平位置移动限制"),
                    checked = clockHorizontalEnabled,
                    onCheckedChange = { value ->
                        clockHorizontalStore.update(clockHorizontalService, value)
                        clockHorizontalEnabled = value
                    },
                )
                SwitchPreference(
                    title = tr("\u9501\u5c4f\u5927\u65f6\u949f\u5f3a\u5236\u663e\u793a\u5192\u53f7", "\u9501\u5c4f\u5927\u65f6\u949f\u5f3a\u5236\u663e\u793a\u5192\u53f7"),
                    checked = s.lockscreenClockColonForceVisible,
                    onCheckedChange = { value ->
                        update { it.copy(lockscreenClockColonForceVisible = value) }
                    },
                )
                SwitchPreference(
                    title = tr("\u53bb\u9664\u9501\u5c4f\u5927\u65f6\u949f\u5bbd\u5ea6\u9650\u5236", "\u53bb\u9664\u9501\u5c4f\u5927\u65f6\u949f\u5bbd\u5ea6\u9650\u5236"),
                    checked = s.lockscreenBigClockWidthLimitRemoved,
                    onCheckedChange = { value ->
                        update { it.copy(lockscreenBigClockWidthLimitRemoved = value) }
                    },
                )
                OverlayDropdownPreference(
                    title = tr("\u9690\u85cf\u9501\u5c4f\u8fd0\u8425\u5546", "\u9690\u85cf\u9501\u5c4f\u8fd0\u8425\u5546"),
                    items = listOf(
                        tr("\u4e0d\u9690\u85cf", "\u4e0d\u9690\u85cf"),
                        tr("\u9690\u85cf\u53611", "\u9690\u85cf\u53611"),
                        tr("\u9690\u85cf\u53612", "\u9690\u85cf\u53612"),
                        tr("\u9690\u85cf\u975e\u4e0a\u7f51\u5361", "\u9690\u85cf\u975e\u4e0a\u7f51\u5361"),
                        tr("\u9690\u85cf\u4e0a\u7f51\u5361", "\u9690\u85cf\u4e0a\u7f51\u5361"),
                    ),
                    selectedIndex = s.lockscreenCarrierHideMode.coerceIn(0, 4),
                    onSelectedIndexChange = { value -> update { it.copy(lockscreenCarrierHideMode = value) } },
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
            Group(tr("\u901a\u77e5\u4e0e\u8d85\u7ea7\u5c9b", "\u901a\u77e5\u4e0e\u8d85\u7ea7\u5c9b")) {
                SwitchPreference(
                    title = tr(
                        "lockscreenNotificationBottomOffset",
                        "调整锁屏通知下沉底部位置",
                    ),
                    summary = tr(
                        "lockscreenNotificationBottomOffsetSummary",
                        "开启后可调整位置；0 为系统默认值，负值向下移动，正值向上移动",
                    ),
                    checked = lockscreenNotificationOffsetSettings.enabled,
                    onCheckedChange = { enabled ->
                        lockscreenNotificationOffsetStore.update(clockHorizontalService) {
                            it.copy(enabled = enabled)
                        }
                        lockscreenNotificationOffsetSettings = lockscreenNotificationOffsetStore.settings
                    },
                )
                AnimatedVisibility(
                    visible = lockscreenNotificationOffsetSettings.enabled,
                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                ) {
                    IntSlide(
                        title = tr(
                            "lockscreenNotificationBottomOffsetValue",
                            "锁屏通知中心下沉底部偏移",
                        ),
                        value = lockscreenNotificationOffsetSettings.offsetDp,
                        range = -40..64,
                        defaultValue = 0,
                    ) { value ->
                        lockscreenNotificationOffsetStore.update(clockHorizontalService) {
                            it.copy(offsetDp = value)
                        }
                        lockscreenNotificationOffsetSettings = lockscreenNotificationOffsetStore.settings
                    }
                }
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
                ArrowPreference(title = tr("unlockIslandPage", "解锁岛"), onClick = { open(PageId.UNLOCK_ISLAND) })
            }
        }
        item {
            Group(tr("lockscreenMediaFeatures", "锁屏媒体")) {
                ArrowPreference(
                    title = tr("音乐锁屏", "音乐锁屏"),
                    summary = if (s.lockscreenMusicLockscreenEnabled) tr("已开启", "已开启") else tr("未开启", "未开启"),
                    onClick = { open(PageId.LOCKSCREEN_MUSIC) },
                )
                ArrowPreference(
                    title = tr("锁屏胶囊", "锁屏胶囊"),
                    summary = if (capsule.enabled) tr("已开启", "已开启") else tr("未开启", "未开启"),
                    onClick = { open(PageId.LOCKSCREEN_CAPSULE) },
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
                SwitchPreference(
                    title = tr("\u606f\u5c4f\u663e\u793a\u65f6\u9690\u85cf\u9501\u5c4f\u5c0f\u7ec4\u4ef6", "\u606f\u5c4f\u663e\u793a\u65f6\u9690\u85cf\u9501\u5c4f\u5c0f\u7ec4\u4ef6"),
                    checked = s.lockscreenWidgetHideOnAod,
                    onCheckedChange = { value ->
                        update { it.copy(lockscreenWidgetHideOnAod = value) }
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
                            title = tr("锁屏小组件背景调整", "锁屏小组件背景调整"),
                            summary = lockscreenWidgetBackgroundModeLabel(s.lockscreenWidgetBackgroundMode),
                            onClick = { open(PageId.LOCKSCREEN_WIDGET_BACKGROUND) },
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
                            title = tr("\u5c0f\u7ec4\u4ef6\u6587\u672c\u989c\u8272", "\u5c0f\u7ec4\u4ef6\u6587\u672c\u989c\u8272"),
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
            Group(tr("\u9501\u5c4f\u5e95\u90e8", "\u9501\u5c4f\u5e95\u90e8")) {
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
            Group(tr("快捷功能背景", "快捷功能背景")) {
                ArrowPreference(
                    title = tr("锁屏快捷功能背景", "锁屏快捷功能背景"),
                    onClick = { open(PageId.LOCKSCREEN_SHORTCUT_BACKGROUND) },
                )
                OverlayDropdownPreference(
                    title = tr("快捷功能按钮启用手势", "快捷功能按钮启用手势"),
                    items = listOf(tr("系统默认", "系统默认"), tr("添加长按手势", "添加长按手势"), tr("仅长按手势", "仅长按手势")),
                    selectedIndex = shortcutGestureMode,
                    onSelectedIndexChange = { value -> updateShortcutGesture(value) },
                )
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
    SaveCurrentWallpaperDialog(
        show = showSaveWallpaperDialog,
        onDismiss = { showSaveWallpaperDialog = false },
    )
    }

@Composable
private fun MusicLockscreenPage(
    s: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    open: (PageId) -> Unit,
    back: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { MusicLockscreenSettingsStore(context) }
    val changed = LocalNotificationSettingsChanged.current
    val conflicts = rememberMusicLockscreenConflicts()
    var masterEnabled by remember { mutableStateOf(store.enabled) }
    val visible = masterEnabled && !conflicts.musicBlocked
    LaunchedEffect(service) {
        service?.let { store.syncRemote(it); masterEnabled = store.enabled }
    }
    AppPage(
        tr("音乐锁屏", "音乐锁屏"), back,
        restartScopes = setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.WALLPAPER),
        header = {
            AnimatedVisibility(
                visible = visible,
                enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
            ) {
                SoftGlassHeaderTabs(
                    listOf(tr("音乐锁屏", "音乐锁屏"), tr("锁屏歌词", "锁屏歌词")),
                    tab, { tab = it },
                )
            }
        },
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Group(tr("musicMaster", "音乐锁屏总开关")) {
                    SwitchPreference(
                        title = tr("musicMaster", "音乐锁屏总开关"),
                        summary = when {
                            conflicts.musicBlocked -> tr("musicHmcAllConflict", "检测到 HMC 已启用音乐锁屏和歌词，已自动停用对应功能")
                            conflicts.coverConflict -> tr("musicHmcCoverConflict", "检测到 HMC 已启用音乐锁屏，已停用本模块封面和壁纸替换")
                            conflicts.lyricsConflict -> tr("musicHmcLyricsConflict", "检测到 HMC 已启用锁屏歌词，已停用本模块锁屏歌词")
                            else -> tr("musicMasterSummary", "统一控制音乐锁屏和锁屏歌词；关闭后隐藏下方设置")
                        },
                        checked = visible,
                        enabled = !conflicts.musicBlocked,
                        onCheckedChange = { value ->
                            store.update(service, value)
                            masterEnabled = value
                            changed()
                        },
                    )
                }
            }
            item {
                AnimatedVisibility(
                    visible = visible,
                    enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
                ) {
                    if (tab == 0) {
                        Group(tr("音乐锁屏", "音乐锁屏")) {
                            SwitchPreference(
                                title = tr("音乐锁屏", "音乐锁屏"),
                                summary = if (conflicts.coverConflict) tr("musicHmcCoverConflict", "检测到 HMC 已启用音乐锁屏，已停用本模块封面和壁纸替换")
                                    else tr("需要同时重启“系统界面”和“壁纸”应用", "需要同时重启“系统界面”和“壁纸”应用"),
                                checked = s.lockscreenMusicLockscreenEnabled && !conflicts.coverConflict,
                                enabled = !conflicts.coverConflict,
                                onCheckedChange = { value -> update { it.copy(lockscreenMusicLockscreenEnabled = value) } },
                            )
                            AnimatedVisibility(visible = s.lockscreenMusicLockscreenEnabled && !conflicts.coverConflict) {
                                HyperMusicCoverOptions(HyperMusicCoverSection.COVER)
                            }
                        }
                    } else {
                        Group(tr("锁屏歌词", "锁屏歌词")) {
                            SwitchPreference(
                                title = tr("锁屏歌词", "锁屏歌词"),
                                summary = if (conflicts.lyricsConflict) tr("musicHmcLyricsConflict", "检测到 HMC 已启用锁屏歌词，已停用本模块锁屏歌词")
                                    else tr("hmcLyricsSummary", "支持歌词库、播放器歌词、词幕和在线歌词"),
                                checked = s.lockscreenMusicLyricsEnabled && !conflicts.lyricsConflict,
                                enabled = !conflicts.lyricsConflict,
                                onCheckedChange = { value -> update { it.copy(lockscreenMusicLyricsEnabled = value) } },
                            )
                            AnimatedVisibility(visible = s.lockscreenMusicLyricsEnabled && !conflicts.lyricsConflict) {
                                Column {
                                    HyperMusicCoverOptions(HyperMusicCoverSection.LYRICS)
                                    SwitchPreference(
                                        title = tr("HDR 高光歌词", "HDR 高光歌词"),
                                        summary = tr("长音结尾发光时在 HDR 屏幕上以更高亮度显示", "长音结尾发光时在 HDR 屏幕上以更高亮度显示"),
                                        checked = s.lockscreenMusicLyricsHdrEnabled,
                                        onCheckedChange = { value -> update { it.copy(lockscreenMusicLyricsHdrEnabled = value) } },
                                    )
                                    SwitchPreference(
                                        title = tr("播放歌词时屏幕常亮", "播放歌词时屏幕常亮"),
                                        summary = tr("锁屏显示歌词且正在播放时不自动息屏，暂停后恢复", "锁屏显示歌词且正在播放时不自动息屏，暂停后恢复"),
                                        checked = s.lockscreenMusicLyricsKeepScreenOn,
                                        onCheckedChange = { value -> update { it.copy(lockscreenMusicLyricsKeepScreenOn = value) } },
                                    )
                                }
                            }
                            ArrowPreference(title = tr("歌词库", "歌词库"), onClick = { open(PageId.LYRIC_LIBRARY) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LockscreenShortcutBackgroundPage(back: () -> Unit) {
    val changed = LocalNotificationSettingsChanged.current
    val context = LocalContext.current
    val store = remember(context) { LockscreenShortcutBackgroundSettingsStore(context) }
    val service by HookApplication.service.collectAsStateWithLifecycle()
    var state by remember { mutableStateOf(store.settings) }
    LaunchedEffect(service) { service?.let { store.syncRemote(it); state = store.settings } }
    fun updateBackground(transform: (LockscreenShortcutBackgroundSettings) -> LockscreenShortcutBackgroundSettings) {
        store.update(service, transform)
        state = store.settings
        changed()
    }
    AppPage(tr("锁屏快捷功能背景", "锁屏快捷功能背景"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD)) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(title = tr("为锁屏快捷功能按钮添加背景", "为锁屏快捷功能按钮添加背景"), checked = state.enabled, onCheckedChange = { enabled ->
                        updateBackground { it.copy(enabled = enabled) }
                    })
                    AnimatedVisibility(state.enabled, enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f), exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f)) {
                        Column {
                            OverlayDropdownPreference(title = tr("快捷功能按钮背景材质", "快捷功能按钮背景材质"), items = listOf(tr("柔光玻璃", "柔光玻璃"), tr("高级材质", "高级材质"), tr("纯色", "纯色")), selectedIndex = when (state.backgroundMode) { 3 -> 0; 2 -> 1; else -> 2 }, onSelectedIndexChange = { index -> updateBackground { it.copy(backgroundMode = when (index) { 0 -> 3; 1 -> 2; else -> 1 }) } })
                            FloatSlide(tr("快捷功能按钮背景圆形半径", "快捷功能按钮背景圆形半径"), state.circleRadius, 10f..60f, defaultValue = 48f) { value -> updateBackground { it.copy(circleRadius = value) } }
                            Dim(title = tr("快捷功能按钮背景圆角半径", "快捷功能按钮背景圆角半径"), enabled = state.radiusEnabled, changeEnabled = { value -> updateBackground { it.copy(radiusEnabled = value) } }, value = state.radius, range = 0f..60f, defaultValue = 24f) { value -> updateBackground { it.copy(radius = value) } }
                            Dim(title = tr("快捷功能按钮间距", "快捷功能按钮间距"), enabled = state.spacingEnabled, changeEnabled = { value -> updateBackground { it.copy(spacingEnabled = value) } }, value = state.spacing, range = 0f..48f, defaultValue = 0f) { value -> updateBackground { it.copy(spacing = value) } }
                            Dim(title = tr("快捷功能按钮图标大小", "快捷功能按钮图标大小"), enabled = state.iconSizeEnabled, changeEnabled = { value -> updateBackground { it.copy(iconSizeEnabled = value) } }, value = state.iconSize, range = 16f..64f, defaultValue = 32f) { value -> updateBackground { it.copy(iconSize = value) } }
                        }
                    }
                }
            }
            item {
                AnimatedVisibility(state.enabled, enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f), exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f)) {
                    Column {
                        SmallTitle(tr("材质参数调整", "材质参数调整"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp))
                        Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                            when (state.backgroundMode) {
                                1 -> ShortcutBackgroundColorPreference(color = state.pureColor, onColorChange = { value -> updateBackground { it.copy(pureColor = value) } })
                                2 -> Column {
                                    ShortcutBackgroundColorPreference(color = state.advancedColor, onColorChange = { value -> updateBackground { it.copy(advancedColor = value) } })
                                    ParameterIntSlide(tr("不透明度", "不透明度"), state.advancedOpacity, 0..100, suffix = "%", defaultValue = 14) { value -> updateBackground { it.copy(advancedOpacity = value) } }
                                    ParameterIntSlide(tr("softGlassBackgroundBlur", "背景模糊度"), state.advancedBlur, 0..120, defaultValue = 80) { value -> updateBackground { it.copy(advancedBlur = value) } }
                                    SwitchPreference(title = tr("显示高光", "显示高光"), checked = state.advancedHighlight, onCheckedChange = { value -> updateBackground { it.copy(advancedHighlight = value) } })
                                }
                                else -> Column {
                                    SwitchPreference(title = tr("使用全局柔光玻璃参数", "使用全局柔光玻璃参数"), checked = state.useGlobalGlass, onCheckedChange = { value -> updateBackground { it.copy(useGlobalGlass = value) } })
                                    AnimatedVisibility(!state.useGlobalGlass, enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f), exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f)) {
                                        Column {
                                            SoftGlassMaterialSliders(state.glass) { transform ->
                                                updateBackground { it.copy(glass = transform(it.glass)) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SoftGlassMaterialSliders(
    glass: SoftGlassSettings,
    includeBackdrop: Boolean = true,
    onChange: ((SoftGlassSettings) -> SoftGlassSettings) -> Unit,
) {
    Column {
        ParameterIntSlide(tr("玻璃模糊", "玻璃模糊"), glass.refractionBlur, 0..200, "%", 100) { value -> onChange { it.copy(refractionBlur = value) } }
        softGlassParameters.filter { it.key != "darker" }.forEach { definition ->
            ParameterIntSlide(tr(definition.title, definition.title), glass.adjustments[definition.key] ?: if (definition.key == "darker") -100 else 0, definition.adjustmentRange, "%", if (definition.key == "darker") -100 else 0) { value -> onChange { it.copy(adjustments = it.adjustments + (definition.key to value)) } }
        }
        if (includeBackdrop) {
            ParameterIntSlide(tr("softGlassBackgroundBlur", "背景模糊度"), glass.backgroundBlur, 0..200, "%", 100) { value -> onChange { it.copy(backgroundBlur = value) } }
            ParameterIntSlide(tr("softGlassBackgroundScale", "背景缩放程度"), glass.backgroundScale, 0..200, "%", 100) { value -> onChange { it.copy(backgroundScale = value) } }
            ParameterIntSlide(tr("背景不透明度", "背景不透明度"), glass.backgroundOpacity, 0..200, "%", 100) { value -> onChange { it.copy(backgroundOpacity = value) } }
        }
        ParameterIntSlide(tr("压暗强度", "压暗强度"), glass.adjustments["darker"] ?: -100, -100..200, "%", -100) { value -> onChange { it.copy(adjustments = it.adjustments + ("darker" to value)) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LyricLibraryPage(back: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { LyricLibraryStore(context) }
    var entries by remember { mutableStateOf(store.entries()) }
    var showEditor by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf("") }
    var lyricBytes by remember { mutableStateOf<ByteArray?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf(emptyList<LocalLyricEntry>()) }

    fun closeSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun toggleSelection(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun openEditor(entry: LocalLyricEntry?) {
        editingId = entry?.id
        title = entry?.title.orEmpty()
        artist = entry?.artist.orEmpty()
        aliases = entry?.aliases.orEmpty()
        lyricBytes = null
        showEditor = true
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.takeIf { it.isNotEmpty() }
                ?: error("empty lyric file")
        }.onSuccess { lyricBytes = it }.onFailure {
            Toast.makeText(context, tr("歌词文件导入失败", "歌词文件导入失败"), Toast.LENGTH_SHORT).show()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use(store::importZip)
                ?: error("unable to open zip")
            entries = store.entries()
        }.onSuccess {
            Toast.makeText(context, tr("歌词库已导入", "歌词库已导入"), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, tr("歌词库导入失败", "歌词库导入失败"), Toast.LENGTH_SHORT).show()
        }
    }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val selected = pendingExport
        pendingExport = emptyList()
        if (uri == null || selected.isEmpty()) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { store.writeZip(selected, it) }
                ?: error("unable to create zip")
        }.onSuccess {
            Toast.makeText(context, tr("歌词已导出", "歌词已导出"), Toast.LENGTH_SHORT).show()
            closeSelection()
        }.onFailure {
            Toast.makeText(context, tr("歌词导出失败", "歌词导出失败"), Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler(enabled = selectionMode) { closeSelection() }

    AppPage(
        title = tr("歌词库", "歌词库"),
        onBack = back,
        actions = {
            AnimatedVisibility(
                visible = !selectionMode,
                enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .9f),
                exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .9f),
            ) {
                Row {
                    GlassToolbarIconButton(
                        icon = MiuixIcons.Regular.Import,
                        description = tr("lyrics_import", "导入"),
                        onClick = {
                            importer.launch(
                                arrayOf("application/zip", "application/x-zip-compressed"),
                            )
                        },
                    )
                    GlassToolbarIconButton(
                        icon = MiuixIcons.Regular.Add,
                        description = tr("添加", "添加"),
                        onClick = { openEditor(null) },
                    )
                }
            }
        },
        floatingToolbarPosition = ToolbarPosition.BottomCenter,
        floatingToolbar = {
            AnimatedVisibility(
                visible = selectionMode,
                enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = .86f),
                exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = .9f),
            ) {
                GlassLyricFloatingToolbar {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            selectedIds = if (selectedIds.size == entries.size) {
                                emptySet()
                            } else {
                                entries.mapTo(linkedSetOf(), LocalLyricEntry::id)
                            }
                        }) {
                            Icon(
                                MiuixIcons.Regular.SelectAll,
                                tr("lyrics_select_all", "全选"),
                                Modifier.size(26.4.dp),
                            )
                        }
                        IconButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                pendingExport = entries.filter { it.id in selectedIds }
                                val stamp = SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date())
                                exporter.launch("HyperChanger_Lyrics_$stamp.zip")
                            },
                        ) {
                            Icon(
                                MiuixIcons.Regular.Download,
                                tr("lyrics_export", "导出"),
                                Modifier.size(26.4.dp),
                            )
                        }
                        IconButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = { confirmBatchDelete = true },
                        ) {
                            Icon(
                                MiuixIcons.Regular.Delete,
                                tr("lyrics_delete_selected", "删除"),
                                Modifier.size(26.4.dp),
                                tint = ComposeColor(0xFFD32F2F),
                            )
                        }
                        IconButton(onClick = ::closeSelection) {
                            Icon(
                                MiuixIcons.Regular.Close,
                                tr("lyrics_close_selection", "关闭"),
                                Modifier.size(26.4.dp),
                            )
                        }
                    }
                }
            }
        },
    ) { padding, scroll ->
        AppList(padding, scroll, if (selectionMode) 112 else 28) {
            items(entries, key = LocalLyricEntry::id) { entry ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 22.5.dp,
                    insideMargin = PaddingValues(16.dp),
                    onClick = {
                        if (selectionMode) toggleSelection(entry.id) else openEditor(entry)
                    },
                    onLongPress = {
                        selectionMode = true
                        selectedIds = selectedIds + entry.id
                    },
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.title,
                            style = MiuixTheme.textStyles.body1,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${entry.artist} · ${entry.type}",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    AnimatedVisibility(
                        visible = selectionMode,
                        enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 2 },
                        exit = fadeOut(tween(120)) + slideOutHorizontally(tween(160)) { it / 2 },
                    ) {
                        Checkbox(
                            state = if (entry.id in selectedIds) ToggleableState.On else ToggleableState.Off,
                            onClick = { toggleSelection(entry.id) },
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    }
                }
            }
        }
    }

    if (showEditor) {
        val canSave = title.isNotBlank() && artist.isNotBlank()
            && (editingId != null || lyricBytes != null)
        WindowDialog(show = true, onDismissRequest = { showEditor = false }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = title,
                    onValueChange = { title = it.take(256) },
                    label = tr("歌曲标题", "歌曲标题"),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    cornerRadius = 999.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = artist,
                    onValueChange = { artist = it.take(256) },
                    label = tr("歌曲艺术家", "歌曲艺术家"),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    cornerRadius = 999.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = aliases,
                    onValueChange = { aliases = it.take(1024) },
                    label = tr("歌曲别名（使用逗号分隔）", "歌曲别名（使用逗号分隔）"),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    cornerRadius = 999.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
                GlassDialogButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (editingId != null || lyricBytes != null) {
                            tr("歌词文件 · 已导入", "歌词文件 · 已导入")
                        } else {
                            tr("歌词文件", "歌词文件")
                        },
                    )
                }
                if (editingId != null) {
                    GlassDialogButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tr("删除", "删除"), color = ComposeColor(0xFFD32F2F))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton(
                        onClick = { showEditor = false },
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("取消", "取消")) }
                    GlassDialogButton(
                        onClick = {
                            runCatching {
                                store.save(editingId, title, artist, aliases, lyricBytes)
                                entries = store.entries()
                            }.onSuccess {
                                showEditor = false
                            }.onFailure {
                                Toast.makeText(context, tr("歌词保存失败", "歌词保存失败"), Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = canSave,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text(tr("保存", "保存")) }
                }
            }
        }
    }

    if (confirmDelete) {
        WindowDialog(show = true, onDismissRequest = { confirmDelete = false }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    tr("删除这首歌？", "删除这首歌？"),
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton(
                        onClick = { confirmDelete = false },
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("取消", "取消")) }
                    GlassDialogButton(
                        onClick = {
                            editingId?.let(store::delete)
                            entries = store.entries()
                            confirmDelete = false
                            showEditor = false
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("删除", "删除"), color = ComposeColor(0xFFD32F2F)) }
                }
            }
        }
    }

    if (confirmBatchDelete) {
        WindowDialog(show = true, onDismissRequest = { confirmBatchDelete = false }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    tr("删除所选歌词？", "删除所选歌词？"),
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.Bold,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton(
                        onClick = { confirmBatchDelete = false },
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("取消", "取消")) }
                    GlassDialogButton(
                        onClick = {
                            store.delete(selectedIds)
                            entries = store.entries()
                            confirmBatchDelete = false
                            closeSelection()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("删除", "删除"), color = ComposeColor(0xFFD32F2F)) }
                }
            }
        }
    }
}

/**
 * Capsule tab strip used by the schedule page. The module has no page-level Backdrop context (only
 * `LocalDialogBackdrop` inside glass dialogs), so the glass is approximated with the same layered
 * recipe: a translucent tinted container, a pill behind the active label, and a capsule clip.
 */
@Composable
private fun GlassSegmentedTabs(
    tabs: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = MiuixTheme.colorScheme.surfaceContainer
    val indicator = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.08f)
    val backdrop = rememberLayerBackdrop()
    val shape = remember { Capsule() }
    Row(
        modifier
            .fillMaxWidth()
            .height(42.dp * 0.93f * 0.97f)
            .clip(shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(6.dp.toPx())
                    lens(18.dp.toPx(), 44.dp.toPx(), depthEffect = true, chromaticAberration = true)
                },
                highlight = { Highlight.Default.copy(alpha = 0.62f) },
                shadow = { Shadow.Default.copy(radius = 10.dp, color = androidx.compose.ui.graphics.Color.Black, alpha = 0.28f) },
                innerShadow = null,
                onDrawSurface = { drawRect(container.copy(alpha = 0.82f)) },
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(shape)
                    .then(if (active) Modifier.background(indicator) else Modifier)
                    .clickable { onSelected(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun LiftToWakeSchedulePage(back: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { LiftToWakeSchedulePreferencesStore(context) }
    val service by HookApplication.service.collectAsStateWithLifecycle()
    var schedule by remember { mutableStateOf(store.settings) }
    LaunchedEffect(service) {
        service?.let {
            store.syncRemote(it)
            schedule = store.settings
        }
    }
    val update: ((LiftToWakeSchedulePreferences) -> LiftToWakeSchedulePreferences) -> Unit = { transform ->
        store.update(service, transform)
        schedule = store.settings
    }
    var tab by remember { mutableStateOf(0) }
    fun pickTime(editingStart: Boolean) {
        val base = if (editingStart) schedule.startMinutes else schedule.endMinutes
        android.app.TimePickerDialog(
            context,
            { _, hour, minute ->
                val minutes = hour * 60 + minute
                update { current ->
                    if (editingStart) current.copy(startMinutes = minutes) else current.copy(endMinutes = minutes)
                }
            },
            base / 60,
            base % 60,
            true,
        ).show()
    }
    AppPage(
        tr("定时抬起亮屏", "定时抬起亮屏"),
        back,
        restartScopes = setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD),
        header = {
            GlassSegmentedTabs(
                tabs = listOf(tr("开启时间", "开启时间"), tr("结束时间", "结束时间")),
                selected = tab,
                onSelected = { tab = it },
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
        },
    ) { padding, scroll ->
        AppList(padding, scroll) {
            item {
                Group(tr("时间段", "时间段")) {
                    val editingStart = tab == 0
                    val currentMinutes = if (editingStart) schedule.startMinutes else schedule.endMinutes
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NumberPicker(
                            value = currentMinutes / 60,
                            onValueChange = { hour ->
                                update { current ->
                                    val minutes = hour * 60 + currentMinutes % 60
                                    if (editingStart) current.copy(startMinutes = minutes) else current.copy(endMinutes = minutes)
                                }
                            },
                            range = 0..23,
                            wrapAround = true,
                            modifier = Modifier.weight(1f),
                        )
                        Text(text = ":", modifier = Modifier.padding(horizontal = 8.dp))
                        NumberPicker(
                            value = currentMinutes % 60,
                            onValueChange = { minute ->
                                update { current ->
                                    val minutes = currentMinutes / 60 * 60 + minute
                                    if (editingStart) current.copy(startMinutes = minutes) else current.copy(endMinutes = minutes)
                                }
                            },
                            range = 0..59,
                            wrapAround = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            item {
                Group(tr("预览", "预览")) {
                    ArrowPreference(
                        title = tr("生效时间段", "生效时间段"),
                        summary = schedule.windowLabel(),
                        onClick = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun LockscreenCapsule(
    capsule: LockscreenCapsuleSettings,
    update: ((LockscreenCapsuleSettings) -> LockscreenCapsuleSettings) -> Unit,
    back: () -> Unit,
) {
    val conflicts = rememberMusicLockscreenConflicts()
    val capsuleEnabled = capsule.enabled && !conflicts.capsuleConflict
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val glassStore = remember(context) { LockscreenCapsuleGlassSettingsStore(context) }
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val changed = LocalNotificationSettingsChanged.current
    var glassState by remember { mutableStateOf(glassStore.settings) }
    LaunchedEffect(service) { service?.let { glassStore.syncRemote(it); glassState = glassStore.settings } }
    fun updateGlass(transform: (LockscreenCapsuleGlassSettings) -> LockscreenCapsuleGlassSettings) {
        glassStore.update(service, transform)
        glassState = glassStore.settings
        changed()
    }
    AppPage(
        tr("锁屏胶囊", "锁屏胶囊"), back,
        restartScopes = setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD),
        header = {
            AnimatedVisibility(
                visible = capsuleEnabled,
                enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
            ) {
                SoftGlassHeaderTabs(
                    listOf(tr("hmcAppearanceTab", "外观"), tr("hmcFeaturesTab", "功能")),
                    tab, { tab = it },
                )
            }
        },
    ) { p, scroll ->
        AppList(p, scroll, 28) {
            item {
                Group(tr("锁屏胶囊", "锁屏胶囊")) {
                    SwitchPreference(
                        title = tr("开启锁屏胶囊", "开启锁屏胶囊"),
                        summary = if (conflicts.capsuleConflict) tr("musicHmcCapsuleConflict", "检测到 HMC 已启用锁屏胶囊，已停用本模块锁屏胶囊")
                            else tr("显示在底部快捷按钮之间，跟随当前媒体会话", "显示在底部快捷按钮之间，跟随当前媒体会话"),
                        checked = capsuleEnabled,
                        enabled = !conflicts.capsuleConflict,
                        onCheckedChange = { value -> update { it.copy(enabled = value) } },
                    )
                }
            }
            if (tab == 0) {
                item {
                    AnimatedVisibility(
                        visible = capsuleEnabled,
                        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
                    ) {
                        Group(tr("hmcAppearanceTab", "外观")) {
                            Column {
                                HyperMusicCoverOptions(HyperMusicCoverSection.CAPSULE_APPEARANCE)
                                FloatSlide(tr("锁屏胶囊宽度 (dp)", "锁屏胶囊宽度 (dp)"), capsule.width, 160f..360f, defaultValue = 240f) { value -> update { it.copy(width = value) } }
                                FloatSlide(tr("锁屏胶囊高度 (dp)", "锁屏胶囊高度 (dp)"), capsule.height, 10f..60f, defaultValue = 36f) { value -> update { it.copy(height = value) } }
                                FloatSlide(tr("媒体封面圆角 (dp)", "媒体封面圆角 (dp)"), capsule.artworkCornerRadius, 0f..60f, defaultValue = 12f) { value -> update { it.copy(artworkCornerRadius = value) } }
                            }
                        }
                    }
                }
                item {
                    AnimatedVisibility(
                        visible = capsuleEnabled,
                        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
                    ) {
                        Group(tr("背景材质", "背景材质")) {
                            OverlayDropdownPreference(
                                title = tr("锁屏胶囊背景", "锁屏胶囊背景"),
                                items = listOf(tr("跟随快捷功能背景", "跟随快捷功能背景"), tr("纯色", "纯色"), tr("高级材质", "高级材质"), tr("柔光玻璃", "柔光玻璃")),
                                selectedIndex = capsule.backgroundMode,
                                onSelectedIndexChange = { value -> update { it.copy(backgroundMode = value) } },
                            )
                        AnimatedVisibility(visible = capsule.backgroundMode == 1) {
                            ShortcutBackgroundColorPreference(
                                title = tr("锁屏胶囊背景颜色", "锁屏胶囊背景颜色"),
                                color = capsule.pureColor,
                                onColorChange = { value -> update { it.copy(pureColor = value) } },
                            )
                        }
                        AnimatedVisibility(visible = capsule.backgroundMode == 2) {
                            Column {
                                ShortcutBackgroundColorPreference(
                                    title = tr("锁屏胶囊混色颜色", "锁屏胶囊混色颜色"),
                                    color = capsule.advancedMaterialColor,
                                    onColorChange = { value -> update { it.copy(advancedMaterialColor = value) } },
                                )
                                ParameterIntSlide(tr("锁屏胶囊不透明度", "锁屏胶囊不透明度"), capsule.advancedMaterialOpacity, 0..100, "%", defaultValue = 14) { value -> update { it.copy(advancedMaterialOpacity = value) } }
                                ParameterIntSlide(tr("锁屏胶囊背景模糊度", "锁屏胶囊背景模糊度"), capsule.advancedMaterialBlurRadius, 0..40, defaultValue = 40) { value -> update { it.copy(advancedMaterialBlurRadius = value) } }
                                SwitchPreference(
                                    title = tr("锁屏胶囊显示高光", "锁屏胶囊显示高光"),
                                    checked = capsule.advancedMaterialHighlight,
                                    onCheckedChange = { value -> update { it.copy(advancedMaterialHighlight = value) } },
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = capsule.backgroundMode == 3,
                            enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                            exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                        ) {
                            Column {
                                SmallTitle(tr("锁屏胶囊柔光玻璃参数", "锁屏胶囊柔光玻璃参数"))
                                SwitchPreference(
                                    title = tr("使用全局柔光玻璃参数", "使用全局柔光玻璃参数"),
                                    checked = glassState.useGlobal,
                                    onCheckedChange = { value -> updateGlass { it.copy(useGlobal = value) } },
                                )
                                AnimatedVisibility(
                                    visible = !glassState.useGlobal,
                                    enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = .96f),
                                    exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = .96f),
                                ) {
                                    SoftGlassMaterialSliders(glassState.glass) { transform ->
                                        updateGlass { it.copy(glass = transform(it.glass)) }
                                    }
                                }
                            }
                        }
                        }
                    }
                }
            } else {
                item {
                    AnimatedVisibility(
                        visible = capsuleEnabled,
                        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
                    ) {
                        Group(tr("hmcFeaturesTab", "功能")) {
                            Column {
                                HyperMusicCoverOptions(HyperMusicCoverSection.CAPSULE_FEATURES)
                                SwitchPreference(
                                    title = tr("锁屏胶囊歌词", "锁屏胶囊歌词"),
                                    checked = capsule.lyricsEnabled,
                                    onCheckedChange = { value -> update { it.copy(lyricsEnabled = value) } },
                                )
                                OverlayDropdownPreference(
                                    title = tr("锁屏媒体通知", "锁屏媒体通知"),
                                    items = listOf(tr("不隐藏", "不隐藏"), tr("始终隐藏", "始终隐藏"), tr("动态显示", "动态显示")),
                                    selectedIndex = capsule.mediaNotificationMode,
                                    onSelectedIndexChange = { value -> update { it.copy(mediaNotificationMode = value) } },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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

@Composable
private fun RearScreenWallpaperLimitDialog(
    show: Boolean,
    current: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var value by remember(show, current) { mutableStateOf(current.toString()) }
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                tr("自定义背屏壁纸数量", "自定义背屏壁纸数量"),
                style = MiuixTheme.textStyles.title3,
                fontWeight = FontWeight.Bold,
            )
            TextField(
                value = value,
                onValueChange = { value = it.filter(Char::isDigit).take(3) },
                label = tr("数量（20-200）", "数量（20-200）"),
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = { onSave(value.toIntOrNull()?.coerceIn(20, 200) ?: current.coerceIn(20, 200)) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("保存", "保存")) }
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
    val surface: Boolean = true,
)

private enum class LockscreenWidgetEditorKind {
    DETAIL_WEATHER, DETAIL_BATTERY, COMPACT_WEATHER, SUN, STEPS, STEPS_WIDE, SIGNATURE,
    SCHEDULE, HUMIDITY, AQI, FEELS_LIKE, WIND, STAND, ALARM,
}

private fun lockscreenWidgetEditorItems() = listOf(
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER, tr("详细天气", "详细天气"), LockscreenWidgetEditorKind.DETAIL_WEATHER, wide = true, surface = false),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY, tr("详细电量", "详细电量"), LockscreenWidgetEditorKind.DETAIL_BATTERY, wide = true, surface = false),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER_BACKGROUND, tr("详细天气（有背景）", "详细天气（有背景）"), LockscreenWidgetEditorKind.DETAIL_WEATHER, wide = true),
    LockscreenWidgetEditorItem(LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY_BACKGROUND, tr("详细电量（有背景）", "详细电量（有背景）"), LockscreenWidgetEditorKind.DETAIL_BATTERY, wide = true),
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

private fun lockscreenWidgetBackgroundModeLabel(mode: Int): String = when (mode) {
    LOCKSCREEN_WIDGET_BACKGROUND_PURE -> tr("纯色", "纯色")
    LOCKSCREEN_WIDGET_BACKGROUND_ADVANCED -> tr("高级材质", "高级材质")
    LOCKSCREEN_WIDGET_BACKGROUND_SOFT_GLASS -> tr("柔光玻璃", "柔光玻璃")
    else -> tr("跟随快捷按钮背景", "跟随快捷按钮背景")
}

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

private fun lockscreenEditorBackgroundFile(context: Context): File =
    File(context.filesDir, "lockscreen_editor_background.bin")

@Composable
private fun SaveCurrentWallpaperDialog(show: Boolean, onDismiss: () -> Unit) {
    if (!show) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { context.getSharedPreferences("wallpaper_export", Context.MODE_PRIVATE) }
    var directory by remember {
        mutableStateOf(preferences.getString("tree_uri", null)?.let(Uri::parse)?.takeIf { saved ->
            context.contentResolver.persistedUriPermissions.any { it.uri == saved && it.isWritePermission }
        })
    }
    var saveLock by remember { mutableStateOf(true) }
    var saveHome by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    val chooseDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val granted = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }.isSuccess
            if (granted) {
                directory = uri
                preferences.edit().putString("tree_uri", uri.toString()).apply()
            } else {
                Toast.makeText(context, tr("壁纸保存失败", "壁纸保存失败"), Toast.LENGTH_SHORT).show()
            }
        }
    }
    WindowDialog(show = true, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("保存当前壁纸", "保存当前壁纸"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state = if (saveLock) ToggleableState.On else ToggleableState.Off, onClick = { saveLock = !saveLock })
                Text(tr("锁屏壁纸", "锁屏壁纸"), modifier = Modifier.padding(start = 8.dp).clickable { saveLock = !saveLock })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state = if (saveHome) ToggleableState.On else ToggleableState.Off, onClick = { saveHome = !saveHome })
                Text(tr("桌面壁纸", "桌面壁纸"), modifier = Modifier.padding(start = 8.dp).clickable { saveHome = !saveHome })
            }
            GlassDialogButton(onClick = { chooseDirectory.launch(directory) }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("选择输出目录", "选择输出目录"))
            }
            Text(
                directory?.let(::wallpaperExportDirectoryLabel) ?: tr("未选择目录", "未选择目录"),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = {
                        val tree = directory ?: return@GlassDialogButton
                        saving = true
                        scope.launch {
                            val success = withContext(Dispatchers.IO) {
                                runCatching { exportCurrentWallpapers(context, tree, saveLock, saveHome) }
                                    .onFailure { error -> Log.e("HyperChanger", "Wallpaper export failed", error) }
                                    .isSuccess
                            }
                            saving = false
                            Toast.makeText(context, if (success) tr("壁纸保存成功", "壁纸保存成功") else tr("壁纸保存失败", "壁纸保存失败"), Toast.LENGTH_SHORT).show()
                            if (success) onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = directory != null && (saveLock || saveHome) && !saving,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("保存", "保存")) }
            }
        }
    }
}

private fun wallpaperExportDirectoryLabel(tree: Uri): String {
    val id = android.provider.DocumentsContract.getTreeDocumentId(tree)
    val volume = id.substringBefore(':')
    val path = id.substringAfter(':', "").trimStart('/')
    return when (volume) {
        "primary" -> "/sdcard" + if (path.isEmpty()) "" else "/$path"
        else -> "/storage/$volume" + if (path.isEmpty()) "" else "/$path"
    }
}

private fun exportCurrentWallpapers(context: Context, tree: Uri, lock: Boolean, home: Boolean) {
    val wallpaperManager = WallpaperManager.getInstance(context)
    val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    fun save(which: Int, label: String) {
        val bitmap = runCatching {
            wallpaperManager.getWallpaperFile(which)?.use { descriptor ->
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor)
            }
        }.getOrNull() ?: run {
            val drawable = wallpaperManager.getDrawable(which) ?: error("wallpaper unavailable")
            if (drawable is BitmapDrawable) drawable.bitmap else {
                Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { result ->
                    val canvas = Canvas(result)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
            }
        }
        val document = android.provider.DocumentsContract.createDocument(
            context.contentResolver,
            android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree)),
            "image/png",
            "HyperChanger-$label-$timestamp.png",
        ) ?: error("create document failed")
        context.contentResolver.openOutputStream(document)?.use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        } ?: error("open output failed")
    }
    if (lock) save(WallpaperManager.FLAG_LOCK, "lockscreen")
    if (home) save(WallpaperManager.FLAG_SYSTEM, "home")
}

@Composable
private fun LockscreenWidgetBackgroundSettings(
    settings: HookSettings,
    update: ((HookSettings) -> HookSettings) -> Unit,
    back: () -> Unit,
) = AppPage(
    tr("锁屏小组件背景调整", "锁屏小组件背景调整"),
    back,
    restartScopes = setOf(ScopeApplication.SYSTEM_UI, ScopeApplication.AOD),
) { padding, scroll ->
    val mode = settings.lockscreenWidgetBackgroundMode.coerceIn(
        LOCKSCREEN_WIDGET_BACKGROUND_FOLLOW_SHORTCUT,
        LOCKSCREEN_WIDGET_BACKGROUND_SOFT_GLASS,
    )
    AppList(padding, scroll, 28) {
        item {
            Card(Modifier.fillMaxWidth()) {
                OverlayDropdownPreference(
                    title = tr("锁屏小组件背景类型", "锁屏小组件背景类型"),
                    items = listOf(
                        tr("跟随快捷按钮背景", "跟随快捷按钮背景"),
                        tr("纯色", "纯色"),
                        tr("高级材质", "高级材质"),
                        tr("柔光玻璃", "柔光玻璃"),
                    ),
                    selectedIndex = mode,
                    onSelectedIndexChange = { value ->
                        update {
                            it.copy(
                                lockscreenWidgetBackgroundMode = value.coerceIn(
                                    LOCKSCREEN_WIDGET_BACKGROUND_FOLLOW_SHORTCUT,
                                    LOCKSCREEN_WIDGET_BACKGROUND_SOFT_GLASS,
                                ),
                            )
                        }
                    },
                )
            }
        }
        item {
            AnimatedVisibility(
                visible = mode != LOCKSCREEN_WIDGET_BACKGROUND_FOLLOW_SHORTCUT,
                enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = .96f),
                exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = .96f),
            ) {
                Group(tr("参数调整", "参数调整")) {
                    when (mode) {
                        LOCKSCREEN_WIDGET_BACKGROUND_PURE -> {
                            ShortcutBackgroundColorPreference(
                                color = settings.lockscreenWidgetPureColor,
                                onColorChange = { value ->
                                    update { it.copy(lockscreenWidgetPureColor = value) }
                                },
                            )
                        }
                        LOCKSCREEN_WIDGET_BACKGROUND_ADVANCED -> {
                            ShortcutBackgroundColorPreference(
                                title = tr("混色颜色", "混色颜色"),
                                color = settings.lockscreenWidgetAdvancedMaterialColor,
                                onColorChange = { value ->
                                    update { it.copy(lockscreenWidgetAdvancedMaterialColor = value) }
                                },
                            )
                            ParameterIntSlide(
                                title = tr("不透明度", "不透明度"),
                                value = settings.lockscreenWidgetAdvancedMaterialOpacity,
                                range = 0..100,
                                suffix = "%",
                                defaultValue = 14,
                            ) { value ->
                                update { it.copy(lockscreenWidgetAdvancedMaterialOpacity = value) }
                            }
                            ParameterIntSlide(
                                title = tr("背景模糊度", "背景模糊度"),
                                value = settings.lockscreenWidgetAdvancedMaterialBlurRadius,
                                range = 0..40,
                                defaultValue = 40,
                            ) { value ->
                                update { it.copy(lockscreenWidgetAdvancedMaterialBlurRadius = value) }
                            }
                            SwitchPreference(
                                title = tr("显示高光", "显示高光"),
                                checked = settings.lockscreenWidgetAdvancedMaterialHighlight,
                                onCheckedChange = { value ->
                                    update { it.copy(lockscreenWidgetAdvancedMaterialHighlight = value) }
                                },
                            )
                        }
                        LOCKSCREEN_WIDGET_BACKGROUND_SOFT_GLASS -> {
                            ShortcutBackgroundColorPreference(
                                title = tr("混色颜色", "混色颜色"),
                                color = settings.lockscreenWidgetSoftGlassColor,
                                onColorChange = { value ->
                                    update { it.copy(lockscreenWidgetSoftGlassColor = value) }
                                },
                            )
                            ParameterIntSlide(
                                title = tr("不透明度", "不透明度"),
                                value = settings.lockscreenWidgetSoftGlassOpacity,
                                range = 0..100,
                                suffix = "%",
                                defaultValue = 10,
                            ) { value ->
                                update { it.copy(lockscreenWidgetSoftGlassOpacity = value) }
                            }
                            ParameterIntSlide(
                                title = tr("背景模糊度", "背景模糊度"),
                                value = settings.lockscreenWidgetSoftGlassBackdropBlurRadius,
                                range = 0..40,
                                defaultValue = 40,
                            ) { value ->
                                update { it.copy(lockscreenWidgetSoftGlassBackdropBlurRadius = value) }
                            }
                            ParameterIntSlide(
                                title = tr("Glass 模糊度", "Glass 模糊度"),
                                value = settings.lockscreenWidgetSoftGlassBlurRadius,
                                range = 0..40,
                                defaultValue = 36,
                            ) { value ->
                                update { it.copy(lockscreenWidgetSoftGlassBlurRadius = value) }
                            }
                            ParameterFloatSlide(
                                title = tr("柔光强度", "柔光强度"),
                                value = settings.lockscreenWidgetSoftGlassLuminance,
                                range = 0f..0.4f,
                                defaultValue = .14f,
                            ) { value ->
                                update { it.copy(lockscreenWidgetSoftGlassLuminance = value) }
                            }
                        }
                    }
                }
            }
        }
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
    val requestCalendarPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                tr("calendar_permission_required", "请先允许读取日程权限"),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    fun requestCalendarPermissionIfNeeded() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALENDAR,
            ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestCalendarPermission.launch(Manifest.permission.READ_CALENDAR)
    }
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
        val counterpart = when (item.flag) {
            LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER -> LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER_BACKGROUND
            LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER_BACKGROUND -> LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER
            LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY -> LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY_BACKGROUND
            LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY_BACKGROUND -> LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY
            else -> null
        }
        val currentWithoutCounterpart = if (enabled && counterpart != null) {
            current and counterpart.inv()
        } else {
            current
        }
        val next = if (enabled) {
            currentWithoutCounterpart or item.flag
        } else {
            currentWithoutCounterpart and item.flag.inv()
        }
        val layoutWithoutCounterpart = selectedItems.filter { it.flag != counterpart }
        if (enabled && !lockscreenWidgetCanAdd(layoutWithoutCounterpart, item)) {
            Toast.makeText(context, tr("当前布局已达到组件上限", "当前布局已达到组件上限"), Toast.LENGTH_SHORT).show()
            return
        }
        if (enabled && item.flag == LOCKSCREEN_WIDGET_ITEM_SCHEDULE) {
            requestCalendarPermissionIfNeeded()
        }
        if (next != 0) update {
            val order = lockscreenWidgetItemsInOrder(it.lockscreenWidgetItems, it.lockscreenWidgetOrder)
                .map(LockscreenWidgetEditorItem::flag)
                .let { existing ->
                    if (enabled) {
                        existing.map { flag -> if (flag == counterpart) item.flag else flag }
                            .let { replaced ->
                                if (item.flag in replaced) replaced else replaced + item.flag
                            }
                            .distinct()
                    } else {
                        existing.filter { flag -> flag != item.flag }
                    }
                }
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
                        .clip(RoundedCornerShape(22.5.dp))
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
                Box(Modifier.matchParentSize().clip(RoundedCornerShape(22.5.dp))) {
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
                cornerRadius = 22.5.dp,
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
                        EditableSliderPreference(
                            value = settings.lockscreenWidgetSignatureScale.toFloat(),
                            onValueChange = { value ->
                                update { it.copy(lockscreenWidgetSignatureScale = value.roundToInt()) }
                            },
                            onValueChangeFinished = {},
                            title = tr("签名缩放", "签名缩放"),
                            valueText = "${settings.lockscreenWidgetSignatureScale}%",
                            valueRange = 25f..200f,
                            steps = 174,
                            defaultValue = 100f,
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
        !item.surface && (item.kind == LockscreenWidgetEditorKind.DETAIL_WEATHER ||
            item.kind == LockscreenWidgetEditorKind.DETAIL_BATTERY) -> RoundedCornerShape(18.dp)
        else -> RoundedCornerShape(32.dp)
    }
    val showSurface = item.surface &&
        (item.kind != LockscreenWidgetEditorKind.SIGNATURE || signatureBackground)
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
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = if (item.wide) 9.dp else 4.dp)
                    .then(
                        if (item.surface &&
                            (item.kind == LockscreenWidgetEditorKind.DETAIL_WEATHER ||
                                item.kind == LockscreenWidgetEditorKind.DETAIL_BATTERY)
                        ) {
                            Modifier.graphicsLayer {
                                scaleX = .88f
                                scaleY = .88f
                            }
                        } else {
                            Modifier
                        },
                    ),
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

private fun statusBarIconsLeftSummary(mask: Int): String = listOf(
    1 to tr("\u7f51\u901f", "\u7f51\u901f"),
    2 to tr("\u95f9\u949f", "\u95f9\u949f"),
    4 to tr("\u58f0\u97f3\u60c5\u666f", "\u58f0\u97f3\u60c5\u666f"),
    8 to tr("\u52ff\u6270\u6a21\u5f0f", "\u52ff\u6270\u6a21\u5f0f"),
).filter { mask and it.first != 0 }.joinToString(" / ") { it.second }.ifBlank { tr("\u672a\u9009\u62e9", "\u672a\u9009\u62e9") }

@Composable
private fun StatusBarIconsLeftDialog(
    show: Boolean,
    mask: Int,
    onDismiss: () -> Unit,
    onApply: (Int) -> Unit,
) {
    var selected by remember(show, mask) { mutableIntStateOf(mask) }
    val items = listOf(
        1 to tr("\u7f51\u901f", "\u7f51\u901f"),
        2 to tr("\u95f9\u949f", "\u95f9\u949f"),
        4 to tr("\u58f0\u97f3\u60c5\u666f", "\u58f0\u97f3\u60c5\u666f"),
        8 to tr("\u52ff\u6270\u6a21\u5f0f", "\u52ff\u6270\u6a21\u5f0f"),
    )
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("\u9009\u62e9\u8981\u5de6\u79fb\u7684\u72b6\u6001\u680f\u56fe\u6807", "\u9009\u62e9\u8981\u5de6\u79fb\u7684\u72b6\u6001\u680f\u56fe\u6807"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            items.forEach { (bit, title) ->
                val toggle = { selected = if (selected and bit != 0) selected and bit.inv() else selected or bit }
                Row(Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(state = if (selected and bit != 0) ToggleableState.On else ToggleableState.Off, onClick = toggle, colors = CheckboxDefaults.checkboxColors(uncheckedForegroundColor = ComposeColor(0x808F8F8F)))
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
                    Checkbox(
                        state = if (selected and bit != 0) ToggleableState.On else ToggleableState.Off,
                        onClick = toggle,
                        colors = CheckboxDefaults.checkboxColors(
                            uncheckedForegroundColor = ComposeColor(0x808F8F8F),
                        ),
                    )
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
                        defaultValue = 1f,
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
    enabled: Boolean = true,
    onColorChange: (Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled) { showPicker = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.body1,
            color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurface.copy(alpha = .38f),
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
private fun ThemeSeedColorPreference(
    title: String,
    color: Int,
    onPreviewColor: (Int) -> Unit,
    onDismissPreview: () -> Unit,
    onColorChange: (Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { showPicker = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
        Box(
            Modifier.size(28.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = .45f), CircleShape)
                .padding(2.dp)
                .background(ComposeColor(color), CircleShape),
        )
    }
    var draftColor by remember(showPicker, color) { mutableIntStateOf(color) }
    WindowDialog(
        show = showPicker,
        onDismissRequest = {
            showPicker = false
            onDismissPreview()
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            ColorPalette(
                color = ComposeColor(draftColor or 0xFF000000.toInt()),
                onColorChanged = { selected ->
                    draftColor = selected.toArgb() or 0xFF000000.toInt()
                    onPreviewColor(draftColor)
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(
                    onClick = {
                        showPicker = false
                        onDismissPreview()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = {
                        onColorChange(draftColor)
                        showPicker = false
                        onDismissPreview()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("确定", "确定")) }
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
    AppList(p, scroll, 28) { item { Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
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
        SwitchPreference(title = tr("\u89e3\u9501\u76f8\u518c\u7f16\u8f91 Xiaomi 18 \u7cfb\u5217\u65b0\u529f\u80fd", "\u89e3\u9501\u76f8\u518c\u7f16\u8f91 Xiaomi 18 \u7cfb\u5217\u65b0\u529f\u80fd"), checked = c.galleryPaletteUnlocked, onCheckedChange = { v -> update { it.copy(galleryPaletteUnlocked = v) } })
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
                PaletteSlider(tr("风格强度", "风格强度"), c.paletteIntensity, 0f..1f, "${(c.paletteIntensity * 100).toInt()}%", defaultValue = 1f) { value -> update { it.copy(paletteIntensity = value) } }
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
                PaletteSlider(tr("曝光", "曝光"), c.paletteExposure, -1f..1f, signedExposure(c.paletteExposure), defaultValue = 0f) { value -> update { it.copy(paletteExposure = value) } }
                PaletteSlider(tr("对比度", "对比度"), c.paletteContrast, 0f..2f, "${(c.paletteContrast * 100).toInt()}%", defaultValue = 1f) { value -> update { it.copy(paletteContrast = value) } }
                PaletteSlider(tr("饱和度", "饱和度"), c.paletteSaturation, 0f..1.8f, "${(c.paletteSaturation * 100).toInt()}%", defaultValue = 1f) { value -> update { it.copy(paletteSaturation = value) } }
                PaletteSlider(tr("暖色", "暖色"), c.paletteWarmth, -1f..1f, signedPaletteValue(c.paletteWarmth), defaultValue = 0f) { value -> update { it.copy(paletteWarmth = value) } }
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
                    PaletteSlider(tr("保护强度", "保护强度"), c.skinToneProtectionAmount, 0f..1f, "${(c.skinToneProtectionAmount * 100).toInt()}%", defaultValue = .65f) { value -> update { it.copy(skinToneProtectionAmount = value) } }
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
internal fun EditableSliderPreference(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    title: String?,
    valueText: String?,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    defaultValue: Float = valueRange.start,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    centerValue: Float? = null,
) {
    var showValueDialog by remember { mutableStateOf(false) }
    // Asymmetric signed ranges still place their neutral value at the physical midpoint.
    val center = centerValue?.takeIf { it > valueRange.start && it < valueRange.endInclusive }
    val sliderValue = if (center == null) value else if (value <= center) {
        .5f * (value - valueRange.start) / (center - valueRange.start)
    } else {
        .5f + .5f * (value - center) / (valueRange.endInclusive - center)
    }
    MiuixSliderPreference(
        value = sliderValue,
        onValueChange = { position ->
            val adjusted = if (center == null) position else if (position <= .5f) {
                valueRange.start + position * 2f * (center - valueRange.start)
            } else {
                center + (position - .5f) * 2f * (valueRange.endInclusive - center)
            }
            onValueChange(if (center == null) adjusted else normalizeSliderValue(adjusted, valueRange, steps))
        },
        onValueChangeFinished = onValueChangeFinished,
        title = title,
        valueText = valueText,
        valueRange = if (center == null) valueRange else 0f..1f,
        steps = if (center == null) steps else 0,
        enabled = enabled,
        modifier = modifier,
        onClick = if (enabled) ({ showValueDialog = true }) else null,
    )
    if (showValueDialog) {
        SliderValueDialog(
            sliderTitle = title.orEmpty(),
            value = value,
            defaultValue = defaultValue,
            valueRange = valueRange,
            steps = steps,
            onDismiss = { showValueDialog = false },
            onSave = { adjustedValue ->
                onValueChange(adjustedValue)
                onValueChangeFinished()
                showValueDialog = false
            },
        )
    }
}

@Composable
private fun SliderValueDialog(
    sliderTitle: String,
    value: Float,
    defaultValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onDismiss: () -> Unit,
    onSave: (Float) -> Unit,
) {
    var draft by remember(value) { mutableStateOf(formatSliderInput(value)) }
    val enteredValue = draft.trim().toFloatOrNull()
    val canSave = enteredValue != null &&
        enteredValue.isFinite() &&
        enteredValue >= valueRange.start &&
        enteredValue <= valueRange.endInclusive

    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        imeBottomSafetyFraction = .175f,
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("数值调整", "数值调整"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            Text(sliderTitle, style = MiuixTheme.textStyles.body2)
            TextField(
                value = draft,
                onValueChange = { draft = it },
                label = sliderTitle,
                useLabelAsPlaceholder = true,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (canSave) {
                            onSave(normalizeSliderValue(enteredValue!!, valueRange, steps))
                            onDismiss()
                        }
                    },
                ),
                cornerRadius = 999.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            GlassDialogButton(
                onClick = { draft = formatSliderInput(defaultValue.coerceIn(valueRange.start, valueRange.endInclusive)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("恢复默认数值", "恢复默认数值")) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton(
                    onClick = { onSave(normalizeSliderValue(enteredValue!!, valueRange, steps)) },
                    enabled = canSave,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(tr("保存", "保存")) }
            }
        }
    }
}

private fun normalizeSliderValue(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
): Float {
    if (steps <= 0) return value.coerceIn(valueRange.start, valueRange.endInclusive)
    val interval = (valueRange.endInclusive - valueRange.start) / (steps + 1)
    if (interval <= 0f) return valueRange.start
    return (valueRange.start + ((value - valueRange.start) / interval).roundToInt() * interval)
        .coerceIn(valueRange.start, valueRange.endInclusive)
}

private fun formatSliderInput(value: Float): String {
    val rounded = (value * 100_000f).roundToInt() / 100_000f
    return if (rounded == rounded.roundToInt().toFloat()) rounded.roundToInt().toString()
    else "%.5f".format(Locale.US, rounded).trimEnd('0').trimEnd('.')
}

@Composable
private fun PaletteSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    defaultValue: Float = range.start,
    onValueFinished: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    EditableSliderPreference(
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { onValueFinished(current.coerceIn(range.start, range.endInclusive)) },
        title = title,
        valueText = valueText,
        valueRange = range,
        steps = ((range.endInclusive - range.start) * 20f).toInt().coerceAtLeast(0) - 1,
        defaultValue = defaultValue,
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
private fun Dim(title: String, enabled: Boolean, changeEnabled: (Boolean) -> Unit, value: Float, range: ClosedFloatingPointRange<Float>, defaultValue: Float = range.start, save: (Float) -> Unit) {
    SwitchPreference(title = tr("\u81ea\u5b9a\u4e49$title", "\u81ea\u5b9a\u4e49$title"), checked = enabled, onCheckedChange = changeEnabled)
    if (enabled) FloatSlide(title, value, range, defaultValue, save)
}

@Composable
private fun DeltaDim(title: String, value: Float, save: (Float) -> Unit) {
    FloatSlide(title, value, -35f..35f, 0f, save)
}

@Composable
private fun RelativePercentSlide(title: String, value: Float, save: (Float) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value) }
    EditableSliderPreference(
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { save(current.roundToInt().toFloat()) },
        title = title,
        valueText = if (current.roundToInt() == 0) {
            tr("statusBarIconSizeDefault", "0%（系统默认）")
        } else {
            tr("statusBarIconSizePercent", "%s%%").format(
                current.roundToInt().let { if (it > 0) "+$it" else it.toString() },
            )
        },
        valueRange = STATUS_BAR_ICON_SIZE_PERCENT_RANGE,
        steps = 199,
        defaultValue = STATUS_BAR_ICON_SIZE_PERCENT_DEFAULT,
        centerValue = STATUS_BAR_ICON_SIZE_PERCENT_DEFAULT,
    )
}

@Composable
private fun Corner(title: String, enabled: Boolean, changeEnabled: (Boolean) -> Unit, value: Float, defaultValue: Float = 24f, save: (Float) -> Unit) {
    SwitchPreference(title = tr("\u81ea\u5b9a\u4e49$title\u5706\u89d2", "\u81ea\u5b9a\u4e49$title\u5706\u89d2"), checked = enabled, onCheckedChange = changeEnabled)
    if (enabled) FloatSlide(title, value, 0f..60f, defaultValue, save)
}

@Composable
private fun FloatSlide(title: String, value: Float, range: ClosedFloatingPointRange<Float>, defaultValue: Float = range.start, save: (Float) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value) }
    EditableSliderPreference(value = current, onValueChange = { current = it }, onValueChangeFinished = { save((current * 10f).toInt() / 10f) }, title = title, valueText = "${(current * 10f).toInt() / 10f} dp", valueRange = range, steps = ((range.endInclusive - range.start) * 10f).toInt() - 1, defaultValue = defaultValue)
}

@Composable
private fun IntSlide(title: String, value: Int, range: IntRange, defaultValue: Int = range.first, save: (Int) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    EditableSliderPreference(value = current, onValueChange = { current = it }, onValueChangeFinished = { save(current.toInt().coerceIn(range.first, range.last)) }, title = title, valueText = "${current.toInt()} dp", valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1, defaultValue = defaultValue.toFloat())
}

@Composable
private fun ParameterIntSlide(
    title: String,
    value: Int,
    range: IntRange,
    suffix: String = "",
    defaultValue: Int = range.first,
    enabled: Boolean = true,
    save: (Int) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    EditableSliderPreference(
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { save(current.toInt().coerceIn(range.first, range.last)) },
        title = title,
        valueText = "${current.toInt()}$suffix",
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
        defaultValue = defaultValue.toFloat(),
        enabled = enabled,
    )
}

@Composable
private fun ParameterDpSlide(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    defaultValue: Float = range.start,
    save: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    EditableSliderPreference(
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = {
            save((current * 100f).roundToInt().coerceIn(
                (range.start * 100f).roundToInt(),
                (range.endInclusive * 100f).roundToInt(),
            ) / 100f)
        },
        title = title,
        valueText = String.format(Locale.US, "%.2f dp", current.coerceIn(range.start, range.endInclusive)),
        valueRange = range,
        steps = ((range.endInclusive - range.start) * 100f).roundToInt().coerceAtLeast(0) - 1,
        defaultValue = defaultValue,
    )
}

@Composable
private fun ParameterFloatSlide(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    defaultValue: Float = range.start,
    enabled: Boolean = true,
    save: (Float) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value) }
    EditableSliderPreference(
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { save((current * 100f).toInt() / 100f) },
        title = title,
        valueText = "${(current * 100f).toInt() / 100f}",
        valueRange = range,
        steps = ((range.endInclusive - range.start) * 100f).toInt() - 1,
        defaultValue = defaultValue,
        enabled = enabled,
    )
}

/**
 * Startup prompt for the module's own release feed. It only announces the new version and routes the
 * user to the software update page, which stays the single place that downloads and installs.
 */
@Composable
private fun UpdateAvailableDialog(
    show: Boolean,
    currentVersion: String,
    latestVersion: String,
    onDismiss: () -> Unit,
    onViewUpdate: () -> Unit,
) {
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Text(
            tr("检测到新版本", "检测到新版本"),
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "${tr("当前版本", "当前版本")}：$currentVersion",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "${tr("最新版本", "最新版本")}：$latestVersion",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
            GlassDialogButton(
                onClick = onViewUpdate,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(tr("查看更新", "查看更新")) }
        }
    }
}

@Composable
private fun SoftwareUpdate(openPage: (PageId) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var channel by rememberSaveable { mutableStateOf("github") }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }
    var downloadId by remember { mutableStateOf<Long?>(null) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloaded by remember { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    val installPreferences = remember { context.getSharedPreferences("software_update", Context.MODE_PRIVATE) }
    var installMode by remember { mutableStateOf(installPreferences.getString("install_mode", "manual") ?: "manual") }
    val updateInfo = (state as? UpdateState.Ready)?.info
    val hasUpdate = updateInfo?.versionCode?.let { it > BuildConfig.VERSION_CODE.toLong() } == true

    fun checkUpdate() {
        state = UpdateState.Checking
        downloaded = false
        scope.launch {
            state = withContext(Dispatchers.IO) {
                runCatching {
                    val info = fetchUpdateInfo(channel)
                    val note = fetchReleaseNote(info.releaseNoteUrl)
                    UpdateState.Ready(info, note)
                }.getOrElse { UpdateState.Error }
            }
        }
    }
    LaunchedEffect(channel) {
        lastUpdateChannel = channel
        checkUpdate()
    }
    LaunchedEffect(Unit) {
        while (true) {
            rootAvailable = hasRootAccess()
            delay(10_000)
        }
    }
    LaunchedEffect(rootAvailable) {
        if (rootAvailable) {
            installMode = installPreferences.getString("install_mode", "manual") ?: "manual"
        }
    }
    LaunchedEffect(downloadId) {
        val id = downloadId ?: return@LaunchedEffect
        val manager = context.getSystemService(DownloadManager::class.java) ?: return@LaunchedEffect
        while (true) {
            val cursor = manager.query(DownloadManager.Query().setFilterById(id))
            val active = cursor?.use {
                if (!it.moveToFirst()) return@use false
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val current = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                if (total > 0) downloadProgress = ((current * 100L) / total).toInt().coerceIn(0, 100)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { downloaded = true; false }
                    DownloadManager.STATUS_FAILED -> { downloadId = null; false }
                    else -> true
                }
            } ?: false
            if (!active) break
            delay(500)
        }
    }
    fun startDownload() {
        val info = updateInfo ?: return
        if (downloaded) {
            if (installMode == "silent" && rootAvailable) {
                installing = true
                scope.launch {
                    val success = withContext(Dispatchers.IO) { installDownloadedApk(context, downloadId!!) }
                    installing = false
                    if (!success) Toast.makeText(context, tr("静默安装失败，请改用手动安装", "静默安装失败，请改用手动安装"), Toast.LENGTH_SHORT).show()
                }
                return
            }
            val uri = context.getSystemService(DownloadManager::class.java)
                ?.getUriForDownloadedFile(downloadId ?: return)
            if (uri != null) {
                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
            return
        }
        if (downloadId != null) {
            context.getSystemService(DownloadManager::class.java)?.remove(downloadId!!)
            downloadId = null
            downloadProgress = 0
            downloaded = false
            return
        }
        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("${tr("HyperChanger", "HyperChanger")} v${info.versionName}")
            setDescription(tr("下载更新", "下载更新"))
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "HyperChanger-v${info.versionName.replace(' ', '-')}.apk")
            setMimeType("application/vnd.android.package-archive")
        }
        downloadId = context.getSystemService(DownloadManager::class.java)?.enqueue(request)
    }

    AppPage(tr("软件更新", "软件更新"), back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    OverlayDropdownPreference(
                        title = tr("更新通道", "更新通道"),
                        items = listOf(tr("GitHub", "GitHub"), tr("Gitee", "Gitee")),
                        selectedIndex = if (channel == "github") 0 else 1,
                        showValueOnEnd = true,
                        onSelectedIndexChange = { channel = if (it == 0) "github" else "gitee" },
                    )
                    OverlayDropdownPreference(
                        title = tr("安装方式", "安装方式"),
                        items = listOf(tr("手动安装", "手动安装"), tr("静默安装", "静默安装")),
                        selectedIndex = if (installMode == "silent" && rootAvailable) 1 else 0,
                        enabled = rootAvailable,
                        showValueOnEnd = true,
                        onSelectedIndexChange = { index ->
                            installMode = if (index == 1) "silent" else "manual"
                            installPreferences.edit().putString("install_mode", installMode).apply()
                        },
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 11.4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${tr("HyperChanger", "HyperChanger")} v${if (hasUpdate) updateInfo?.versionName else BuildConfig.VERSION_NAME}",
                                style = MiuixTheme.textStyles.body1.copy(fontSize = MiuixTheme.textStyles.body1.fontSize * 1.2f),
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                when (state) {
                                    UpdateState.Checking -> tr("检查更新中", "检查更新中")
                                    UpdateState.Error -> tr("检查更新失败", "检查更新失败")
                                    is UpdateState.Ready -> if (hasUpdate) {
                                        "${tr("检测到新版本", "检测到新版本")} · ${formatApkSize(updateInfo?.apkSize ?: 0L)}"
                                    } else tr("已是最新版本", "已是最新版本")
                                },
                                style = MiuixTheme.textStyles.body2.copy(fontSize = MiuixTheme.textStyles.body2.fontSize * 1.2f),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                    when (val current = state) {
                        UpdateState.Checking -> Text(tr("检查更新中......", "检查更新中......"), color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .6f), modifier = Modifier.padding(top = 18.dp))
                        UpdateState.Error -> Text(tr("暂时无法获取更新信息", "暂时无法获取更新信息"), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 18.dp))
                        is UpdateState.Ready -> {
                            Text(stripMarkdown(current.note).lines().take(6).joinToString("\n"), style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(top = 18.dp, bottom = 0.dp))
                            Text(
                                tr("了解更多...", "了解更多..."),
                                color = ComposeColor(0xFF1976D2),
                                style = MiuixTheme.textStyles.body1,
                                modifier = Modifier.padding(top = 8.dp).clickable { openPage(PageId.UPDATE_LOG) },
                            )
                            if (hasUpdate) {
                                HorizontalDivider(modifier = Modifier.padding(top = 14.dp, bottom = 12.dp))
                                val buttonBackdrop = rememberLayerBackdrop()
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    Box(
                                        Modifier.matchParentSize()
                                            .layerBackdrop(buttonBackdrop)
                                            .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = .12f)),
                                    )
                                    UpdateToolbarButton(
                                        onClick = ::startDownload,
                                        text = when {
                                            installing -> tr("安装中", "安装中")
                                            downloaded -> tr("立即安装", "立即安装")
                                            downloadId != null -> "${tr("下载中", "下载中")} $downloadProgress%"
                                            else -> tr("立即下载", "立即下载")
                                        },
                                        primary = true,
                                        enabled = !installing,
                                        modifier = Modifier.fillMaxWidth(),
                                        backdrop = buttonBackdrop,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class UpdateInfo(val versionCode: Long, val versionName: String, val releaseNoteUrl: String, val apkUrl: String, val apkSize: Long)
private var lastUpdateChannel = "github"

/** Set once the startup check resolved an answer, so it is not repeated within one process. */
private var startupUpdateChecked = false

/** The newer build the startup check found, kept at process scope so recomposition cannot lose it. */
private var startupUpdatePending: UpdateInfo? = null

private fun stripMarkdown(value: String): String = value
    .replace(Regex("^\\s{0,3}#{1,6}\\s*", RegexOption.MULTILINE), "")
    .replace(Regex("[*_~`]+"), "")
    .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
    .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("^\\s*[-+*]\\s+", RegexOption.MULTILINE), "")
    .replace(Regex("^\\s*\\d+[.)]\\s+", RegexOption.MULTILINE), "")

private fun hasRootAccess(): Boolean = runCatching {
    Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).let { process ->
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.errorStream.close()
        process.waitFor(2, TimeUnit.SECONDS) && output.contains("uid=0")
    }
}.getOrDefault(false)

private fun installDownloadedApk(context: Context, downloadId: Long): Boolean = runCatching {
    val manager = context.getSystemService(DownloadManager::class.java) ?: return@runCatching false
    val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return@runCatching false
    val uri = cursor.use {
        if (!it.moveToFirst()) return@use null
        it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
    } ?: return@runCatching false
    val path = Uri.parse(uri).path ?: return@runCatching false
    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm install -r $path"))
    process.inputStream.close()
    process.errorStream.close()
    process.waitFor() == 0
}.getOrDefault(false)

@Composable
internal fun UpdateToolbarButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    backdrop: LayerBackdrop? = LocalToolbarBackdrop.current,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(50.dp),
    surfaceColor: ComposeColor? = null,
    blurRadius: Dp = TOOLBAR_GLASS_BLUR_RADIUS,
    leadingIcon: (@Composable () -> Unit)? = null,
    allowDrag: Boolean = true,
) {
    val animationScope = rememberCoroutineScope()
    val drag = remember(animationScope) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = 0f,
            valueRange = -1f..1f,
            visibilityThreshold = .001f,
            initialScale = 1f,
            pressedScale = 1.035f,
            onDragStopped = { animateToValue(0f) },
            onDrag = { size, amount -> updateValue((targetValue + amount.x / size.width.coerceAtLeast(1)).coerceIn(-1f, 1f)) },
        )
    }
    val highlight = remember(animationScope) {
        InteractiveHighlight(animationScope) { size, offset -> Offset(offset.x.coerceIn(0f, size.width), offset.y.coerceIn(0f, size.height)) }
    }
    val tint = surfaceColor ?: if (primary) ComposeColor(0xFF0088FF).copy(alpha = .92f) else MiuixTheme.colorScheme.surfaceContainer.copy(alpha = .82f)
    Box(
        modifier.height(48.dp)
            .then(if (enabled) highlight.gestureModifier else Modifier)
            .then(if (enabled && allowDrag) drag.modifier else Modifier)
            .clip(shape)
            .then(if (backdrop != null) Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = { vibrancy(); if (blurRadius > 0.dp) blur(blurRadius.toPx()); toolbarGlassLens(drag.pressProgress) },
                highlight = { toolbarGlassHighlight(drag.pressProgress) },
                shadow = { Shadow.Default.copy(radius = 5.dp, color = ComposeColor.Black, alpha = .24f) },
                innerShadow = null,
                onDrawSurface = { drawRoundRect(tint) },
            ) else Modifier.background(tint))
            .graphicsLayer {
                alpha = if (enabled) 1f else .42f
                scaleX = drag.scaleX
                scaleY = drag.scaleY
                translationX = drag.value * 4.dp.toPx()
            }
            .then(if (enabled) highlight.modifier else Modifier)
            .clickable(enabled = enabled, interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            leadingIcon?.let { it(); Spacer(Modifier.width(9.dp)) }
            Text(text, color = if (primary) ComposeColor.White else MiuixTheme.colorScheme.onSurface, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatApkSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
    else -> "$bytes B"
}
private sealed interface UpdateState {
    data object Checking : UpdateState
    data object Error : UpdateState
    data class Ready(val info: UpdateInfo, val note: String) : UpdateState
}

private fun fetchUpdateInfo(channel: String): UpdateInfo {
    val url = if (channel == "github") "https://coldp.github.io/hyperchanger-updateinfo/update.json" else "https://gitee.com/btm_m/HyperChanger/raw/main/update.json"
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        requestMethod = "GET"
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "HyperChanger/${BuildConfig.VERSION_NAME}")
    }
    return try {
        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val versionCode = json.optString("VersionCode").toLongOrNull() ?: json.optLong("VersionCode", 0L)
        UpdateInfo(versionCode, json.getString("VersionName"), json.getString("ReleaseNoteURL"), json.getString("APKURL"), json.optLong("APKSize", 0L))
    } finally {
        connection.disconnect()
    }
}

private fun fetchReleaseNote(url: String): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "HyperChanger/${BuildConfig.VERSION_NAME}")
    }
    return try {
        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        connection.inputStream.bufferedReader().use { it.readText() }.trim()
    } finally {
        connection.disconnect()
    }
}

private fun markdownAnnotatedString(markdown: String): AnnotatedString = buildAnnotatedString {
    markdown.lines().forEachIndexed { index, line ->
        val heading = Regex("^\\s*#{1,6}\\s+(.*)$").find(line)
        val content = heading?.groupValues?.get(1) ?: line
        val headingStyle = if (heading != null) SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp) else SpanStyle()
        withStyle(headingStyle) {
            var cursor = 0
            Regex("(\\*\\*|__)(.+?)\\1|`(.+?)`|\\[([^]]+)]\\(([^)]+)\\)").findAll(content).forEach { match ->
                append(content.substring(cursor, match.range.first))
                when {
                    match.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[2]) }
                    match.groupValues[3].isNotEmpty() -> withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) { append(match.groupValues[3]) }
                    else -> append(match.groupValues[4])
                }
                cursor = match.range.last + 1
            }
            append(content.substring(cursor))
        }
        if (index < markdown.lines().lastIndex) append("\n")
    }
}

@Composable
private fun UpdateLog(back: () -> Unit) {
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
            note = withContext(Dispatchers.IO) {
            runCatching { fetchReleaseNote(fetchUpdateInfo(lastUpdateChannel).releaseNoteUrl) }.getOrNull()
        }
    }
    AppPage(tr("更新日志", "更新日志"), back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
                    Text(note?.let(::markdownAnnotatedString) ?: AnnotatedString(tr("加载中......", "加载中......")), style = MiuixTheme.textStyles.body2)
                }
            }
        }
    }
}

@Composable
private fun aboutCardColors() = CardDefaults.defaultColors(
    MiuixTheme.colorScheme.surfaceContainer.copy(
        alpha = if (MiuixTheme.colorScheme.surface.luminance() < .5f) .60f else .65f,
    ),
)

private fun legalLanguage(context: Context): String {
    val prefs = context.getSharedPreferences("languages", Context.MODE_PRIVATE)
    val selected = prefs.getString("selected", "system") ?: "system"
    return when {
        selected == "en" || selected == "ja" || selected == "zh" -> selected
        selected == "system" -> when (systemLanguage(context)) {
            "en" -> "en"
            "ja" -> "ja"
            else -> "zh"
        }
        else -> "zh"
    }
}

private fun setLauncherIconVisible(context: Context, visible: Boolean) {
    runCatching {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, "${BuildConfig.APPLICATION_ID}.LauncherActivity"),
            if (visible) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}

private fun legalAssetName(page: PageId): String = when (page) {
    PageId.LEGAL_PRIVACY -> "privacy.txt"
    PageId.LEGAL_USER_AGREEMENT -> "user_agreement.txt"
    else -> "disclaimer.txt"
}

@Composable
private fun LegalDocument(page: PageId, back: () -> Unit) {
    val context = LocalContext.current
    val language = legalLanguage(context)
    val title = when (page) {
        PageId.LEGAL_PRIVACY -> tr("隐私政策", "隐私政策")
        PageId.LEGAL_USER_AGREEMENT -> tr("用户协议", "用户协议")
        else -> tr("免责声明", "免责声明")
    }
    val content = remember(page, language) {
        val file = legalAssetName(page)
        runCatching {
            context.assets.open("legal/$language/$file").bufferedReader().use { it.readText() }
        }.getOrElse {
            context.assets.open("legal/zh/$file").bufferedReader().use { it.readText() }
        }
    }
    AppPage(title, back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(18.dp)) {
                    Text(content, style = MiuixTheme.textStyles.body1)
                }
            }
        }
    }
}

@Composable
internal fun DeviceInfoItem(title: String, summary: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MiuixTheme.textStyles.body1)
        Text(
            summary,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun About(back: () -> Unit, openPage: (PageId) -> Unit, monetEnabled: Boolean) = AppPage(tr("\u5173\u4e8e", "\u5173\u4e8e"), back, showLargeTitle = false, pageBackground = { AboutMaterialBackground(Modifier.matchParentSize(), monetEnabled) }) { padding, scroll ->
    val context = LocalContext.current
    val unavailable = tr("未检测到", "未检测到")
    val marketName = remember { OsCompatibility.systemProperty("ro.product.marketname").ifBlank { unavailable } }
    val systemVersion = remember {
        OsCompatibility.systemProperty("ro.mi.os.version.incremental")
            .removePrefix("OS")
            .ifBlank { unavailable }
    }
    val androidVersion = remember { "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})" }
    var versionTaps by remember { mutableIntStateOf(0) }
    var confettiKey by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val fadeDistancePx = with(LocalDensity.current) { 520.dp.toPx() }
    val fade by remember { derivedStateOf { (listState.firstVisibleItemScrollOffset / fadeDistancePx).coerceIn(0f, 1f) } }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection), state = listState, contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 4.dp, 16.dp, padding.calculateBottomPadding() + 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            AboutAdvancedMaterialHeader(
                onLogoClick = {},
                onVersionClick = {
                    versionTaps++
                    if (versionTaps >= 5) {
                        versionTaps = 0
                        confettiKey++
                    }
                },
            )
        }
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, colors = aboutCardColors()) {
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
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, colors = aboutCardColors()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    DeviceInfoItem(marketName, tr("设备型号", "设备型号"))
                    DeviceInfoItem(androidVersion, tr("Android 版本", "Android 版本"))
                    DeviceInfoItem(systemVersion, tr("HyperOS 版本", "HyperOS 版本"))
                    DeviceInfoItem("HyperOS 4 (Android 17)", tr("HyperChanger 适配版本", "HyperChanger 适配版本"))
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, colors = aboutCardColors()) {
                ArrowPreference(title = tr("license", "LICENSE"), summary = tr("project_license_name", "AGPL-3.0（组合分发）"), onClick = { openPage(PageId.LICENSE) })
                ArrowPreference(title = tr("githubRepository", "GitHub Repository"), summary = "github.com/ColdP/HyperChanger", onClick = { openUrl(context, "https://github.com/ColdP/HyperChanger") })
                ArrowPreference(title = tr("telegramGroup", "Telegram 群组"), summary = "t.me/HyperChanger", onClick = { openUrl(context, "https://t.me/HyperChanger") })
                ArrowPreference(title = tr("qqGroup", "QQ 群"), summary = tr("qqGroupNumber", "群号: 429188055"), onClick = { openUrl(context, "https://qm.qq.com/q/rQhbQbiYLe") })
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, colors = aboutCardColors()) {
                ArrowPreference(title = tr("用户协议", "用户协议"), onClick = { openPage(PageId.LEGAL_USER_AGREEMENT) })
                ArrowPreference(title = tr("免责声明", "免责声明"), onClick = { openPage(PageId.LEGAL_DISCLAIMER) })
                ArrowPreference(title = tr("隐私政策", "隐私政策"), onClick = { openPage(PageId.LEGAL_PRIVACY) })
                ArrowPreference(title = tr("\u672c\u9879\u76ee\u57fa\u4e8e MIUIX \u6784\u5efa", "\u672c\u9879\u76ee\u57fa\u4e8e MIUIX \u6784\u5efa"), onClick = { openUrl(context, "https://compose-miuix-ui.github.io/miuix/") })
                ArrowPreference(title = tr("\u5f00\u6e90\u4ee3\u7801\u58f0\u660e", "\u5f00\u6e90\u4ee3\u7801\u58f0\u660e"), onClick = { openPage(PageId.OPEN) })
                ArrowPreference(title = tr("contributors", "\u8d21\u732e\u8005"), onClick = { openPage(PageId.CONTRIBUTORS) })
            }
        }
        item { Text("\u00a9 ${Year.now().value} btm_m", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .56f), modifier = Modifier.padding(start = 12.dp)) }
    }
    if (confettiKey > 0) {
        CompletionConfetti(
            playKey = confettiKey,
            modifier = Modifier.fillMaxSize(),
            pieceCount = 200,
            restingCountRange = 3..7,
            durationMillis = 7_000,
        )
    }
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
private fun Disclaimer(back: () -> Unit, captcha: String) = AppPage("免责声明", back) { padding, scroll ->
    AppList(padding, scroll, 28) {
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(18.dp)) {
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

private const val CONTRIBUTORS_URL = "https://coldp.github.io/hyperchanger-updateinfo/contributors.json"

private data class Contributor(
    val name: String,
    val avatarUrl: String,
    val githubLink: String,
    val descriptions: Map<String, String>,
)

private data class ContributorsResult(
    val contributors: List<Contributor> = emptyList(),
    val error: Boolean = false,
)

private fun fetchContributors(): Result<List<Contributor>> = runCatching {
    val connection = (URL(CONTRIBUTORS_URL).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        requestMethod = "GET"
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "HyperChanger/${BuildConfig.VERSION_NAME}")
    }
    try {
        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
        buildList {
            for (index in 0 until array.length()) {
                val objectValue = array.optJSONObject(index) ?: continue
                val name = objectValue.optString("name").trim()
                if (name.isBlank()) continue
                val descriptionObject = objectValue.optJSONObject("description")
                val descriptions = buildMap {
                    descriptionObject?.keys()?.forEach { key ->
                        descriptionObject.optString(key).trim().takeIf { it.isNotBlank() }?.let { put(key, it) }
                    }
                }
                add(
                    Contributor(
                        name = name,
                        avatarUrl = objectValue.optString("avatar").trim(),
                        githubLink = objectValue.optString("github_link").trim(),
                        descriptions = descriptions,
                    )
                )
            }
        }
    } finally {
        connection.disconnect()
    }
}

private fun loadContributorAvatar(url: String): Bitmap? = runCatching {
    if (url.isBlank()) return@runCatching null
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 15_000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "HyperChanger/${BuildConfig.VERSION_NAME}")
    }
    try {
        if (connection.responseCode !in 200..299) return@runCatching null
        connection.inputStream.use { BitmapFactory.decodeStream(it) }
    } finally {
        connection.disconnect()
    }
}.getOrNull()

private fun contributorDescription(context: android.content.Context, descriptions: Map<String, String>): String {
    if (descriptions.isEmpty()) return ""
    val prefs = context.getSharedPreferences("languages", android.content.Context.MODE_PRIVATE)
    val selection = prefs.getString("selected", "system") ?: "system"
    val locale = when (selection) {
        "system" -> systemLanguage(context)
        "zh", "en", "ja" -> selection
        else -> loadLanguagePacks(prefs).firstOrNull { it.name == selection }?.let {
            runCatching { JSONObject(it.json).optString("locale") }.getOrDefault("")
        }.orEmpty()
    }.lowercase(Locale.ROOT)
    return descriptions[locale]
        ?: descriptions[locale.substringBefore('-')]
        ?: descriptions["en"]
        ?: descriptions["zh"]
        ?: descriptions.values.firstOrNull().orEmpty()
}

@Composable
private fun Contributors(back: () -> Unit) {
    val context = LocalContext.current
    var reloadToken by remember { mutableIntStateOf(0) }
    val result by produceState<ContributorsResult?>(initialValue = null, reloadToken) {
        value = withContext(Dispatchers.IO) { fetchContributors() }.fold(
            onSuccess = { ContributorsResult(contributors = it) },
            onFailure = { ContributorsResult(error = true) },
        )
    }
    val entries = result?.contributors.orEmpty().map {
        ContributorGalleryEntry(it.name, it.avatarUrl, it.githubLink, contributorDescription(context, it.descriptions))
    }
    val gallery = rememberContributorGalleryState(entries) { url ->
        withContext(Dispatchers.IO) { loadContributorAvatar(url) }
    }
    val ambient = contributorAmbientColors(entries, gallery)
    val ambientBackdrop = rememberLayerBackdrop()
    AppPage(
        tr("contributors", "\u8d21\u732e\u8005"), back,
        pageBackground = { ContributorAmbientBackground(ambient, Modifier.fillMaxSize().layerBackdrop(ambientBackdrop)) },
    ) { padding, scroll ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val availableHeight = (maxHeight - padding.calculateTopPadding() - 4.dp).coerceAtLeast(200.dp)
            LazyColumn(
                Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
                userScrollEnabled = false,
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    result == null -> item {
                        Text(tr("contributors_loading", "正在加载贡献者"), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    result!!.error -> item {
                        Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
                            Text(tr("contributors_load_failed", "无法加载贡献者列表"), style = MiuixTheme.textStyles.body1)
                            Button(onClick = { reloadToken++ }, modifier = Modifier.padding(top = 12.dp), colors = ButtonDefaults.buttonColorsPrimary()) {
                                Text(tr("retry", "重试"))
                            }
                        }
                    }
                    result!!.contributors.isEmpty() -> item {
                        Text(tr("contributors_empty", "暂无贡献者信息"), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    else -> item {
                        ContributorsGallery(
                            contributors = entries,
                            state = gallery,
                            ambientBackdrop = ambientBackdrop,
                            openProfile = { openUrl(context, it) },
                            availableHeight = availableHeight,
                        )
                    }
                }
            }
        }
    }
}

private data class OpenProject(
    val name: String,
    val version: String,
    val description: String,
    val url: String,
    val license: String = tr("Apache License 2.0", "Apache License 2.0"),
)
private fun openProjects() = listOf(
    OpenProject("MIUIX", "0.9.3", tr("HyperOS \u98ce\u683c\u754c\u9762\u3001\u504f\u597d\u8bbe\u7f6e\u3001\u56fe\u6807\u4e0e\u6a21\u7cca\u6548\u679c", "HyperOS \u98ce\u683c\u754c\u9762\u3001\u504f\u597d\u8bbe\u7f6e\u3001\u56fe\u6807\u4e0e\u6a21\u7cca\u6548\u679c"), "https://github.com/compose-miuix-ui/miuix"),
    OpenProject("LSPosed API", "102", tr("LSPosed \u6a21\u5757 API \u4e0e\u670d\u52a1\u901a\u4fe1", "LSPosed \u6a21\u5757 API \u4e0e\u670d\u52a1\u901a\u4fe1"), "https://github.com/LSPosed/LSPosed"),
    OpenProject("Backdrop / AndroidLiquidGlass", "2.0.0", tr("\u6db2\u6001\u73bb\u7483\u6e32\u67d3\u4e0e\u5e95\u90e8\u5bfc\u822a\u4ea4\u4e92", "\u6db2\u6001\u73bb\u7483\u6e32\u67d3\u4e0e\u5e95\u90e8\u5bfc\u822a\u4ea4\u4e92"), "https://github.com/Kyant0/AndroidLiquidGlass"),
    OpenProject("Compose Multiplatform", "1.11.x", tr("\u58f0\u660e\u5f0f\u754c\u9762\u3001\u5e03\u5c40\u4e0e\u52a8\u753b", "\u58f0\u660e\u5f0f\u754c\u9762\u3001\u5e03\u5c40\u4e0e\u52a8\u753b"), "https://github.com/JetBrains/compose-multiplatform"),
    OpenProject("AndroidX", tr("\u591a\u4e2a\u7ec4\u4ef6", "\u591a\u4e2a\u7ec4\u4ef6"), tr("Activity\u3001Lifecycle\u3001Core \u7b49 Android \u57fa\u7840\u5e93", "Activity\u3001Lifecycle\u3001Core \u7b49 Android \u57fa\u7840\u5e93"), "https://github.com/androidx/androidx"),
    OpenProject(tr("HyperMusicCover", "HyperMusicCover"), tr("hmcSnapshot", "源码快照 2026-10-30"), tr("hmcAttribution", "音乐锁屏、歌词与锁屏胶囊；保留旧通知中心实现"), "https://github.com/zyl6932/HyperMusicCover", tr("hmcLicense", "AGPL-3.0-only；部分代码为 Apache-2.0 / GPL-3.0")),
    OpenProject(
        tr("HyperIsland", "HyperIsland"),
        tr("3.1.8", "3.1.8"),
        tr("hyperIslandSmoothingAttribution", "超级岛相关代码"),
        "https://github.com/1812z/HyperIsland",
        tr("MIT License", "MIT License"),
    ),
)

@Composable
private fun OpenSource(back: () -> Unit) = AppPage(tr("\u5f00\u6e90\u4ee3\u7801\u58f0\u660e", "\u5f00\u6e90\u4ee3\u7801\u58f0\u660e"), back) { padding, scroll ->
    val context = LocalContext.current
    val projects = openProjects()
    AppList(padding, scroll, 28) {
        item { Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) { Text(tr("HyperChanger \u4f7f\u7528\u4e86\u4ee5\u4e0b\u5f00\u6e90\u9879\u76ee\u3002\u611f\u8c22\u6240\u6709\u9879\u76ee\u4f5c\u8005\u4e0e\u8d21\u732e\u8005\u3002", "HyperChanger \u4f7f\u7528\u4e86\u4ee5\u4e0b\u5f00\u6e90\u9879\u76ee\u3002\u611f\u8c22\u6240\u6709\u9879\u76ee\u4f5c\u8005\u4e0e\u8d21\u732e\u8005\u3002"), style = MiuixTheme.textStyles.body1) } }
        item { SmallTitle(tr("\u754c\u9762\u3001\u529f\u80fd\u4e0e\u5e73\u53f0", "\u754c\u9762\u3001\u529f\u80fd\u4e0e\u5e73\u53f0"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp)) }
        items(projects.size) { i ->
            val item = projects[i]
            Card(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 22.5.dp,
                insideMargin = PaddingValues(16.dp),
                onClick = { openUrl(context, item.url) },
                showIndication = true,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold)
                        Text("${item.version} \u00b7 ${item.license}", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 3.dp))
                        Text(item.description, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(top = 6.dp))
                    }
                    Image(MiuixIcons.Regular.ChevronForward, null, Modifier.padding(start = 12.dp).size(22.dp), colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onSurfaceVariantSummary))
                }
            }
        }
    }
}

@Composable
internal fun AppPage(
    title: String,
    onBack: (() -> Unit)? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    restartScopes: Set<ScopeApplication> = emptySet(),
    restartEnabled: Boolean = true,
    compactTopBar: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    floatingToolbarPosition: ToolbarPosition = ToolbarPosition.BottomCenter,
    floatingToolbar: @Composable () -> Unit = {},
    showLargeTitle: Boolean = true,
    pageBackground: (@Composable BoxScope.() -> Unit)? = null,
    header: @Composable () -> Unit = {},
    content: @Composable (PaddingValues, ScrollBehavior) -> Unit,
) {
    val context = LocalContext.current
    var showRestartScopeDialog by remember { mutableStateOf(false) }
    val scroll = MiuixScrollBehavior()
    val backdrop = rememberLayerBackdrop()
    val surface = MiuixTheme.colorScheme.surface
    val progressiveBackdrop = rememberMiuixLayerBackdrop {
        drawRect(surface)
        drawContent()
    }
    val collapsedFraction = scroll.state.collapsedFraction.coerceIn(0f, 1f)
    val topBarMaterialProgress by animateFloatAsState(
        targetValue = collapsedFraction,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "topBarMaterialProgress",
    )
    CompositionLocalProvider(
        LocalToolbarBackdrop provides backdrop,
        LocalToolbarCollapsed provides collapsedFraction,
        LocalDialogBackdrop provides backdrop,
    ) {
    Box(Modifier.fillMaxSize().background(surface)) {
    pageBackground?.let {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) { it() }
    }
    Scaffold(
        containerColor = ComposeColor.Transparent,
        floatingToolbar = floatingToolbar,
        floatingToolbarPosition = floatingToolbarPosition,
        topBar = {
            ProgressiveTopBarMaterial(
                backdrop = progressiveBackdrop,
                collapsedFraction = topBarMaterialProgress,
                legacyBackdrop = pageBackground?.let { backdrop },
            ) {
                Column(Modifier.fillMaxWidth()) {
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
                        Box(Modifier.fillMaxWidth()) {
                            TopAppBar(
                                title = "",
                                largeTitle = if (showLargeTitle) title else "",
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
                            // Overlay the collapsed title without contributing another row's height.
                            Box(Modifier.matchParentSize()) {
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
                    header()
                }
            }
        }
    ) { padding ->
        val contentModifier = Modifier.fillMaxSize().layerBackdrop(backdrop)
        Box(
            if (pageBackground == null) contentModifier.miuixLayerBackdrop(progressiveBackdrop) else contentModifier
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
}

@Composable
private fun AboutAdvancedMaterialHeader(onLogoClick: () -> Unit, onVersionClick: () -> Unit) {
    val density = LocalDensity.current
    val dark = MiuixTheme.colorScheme.surface.luminance() < .5f
    val versionColor = MiuixTheme.colorScheme.onSurface.copy(alpha = .78f).toArgb()
    BoxWithConstraints(Modifier.fillMaxWidth().height(310.dp).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        val width = maxWidth - 18.dp
        val logoSize = width * (.94f / 3.91f) * 2.78f * .8f * .95f
        val textWidth = width * (2.97f / 3.91f)
        AndroidView(factory = { HyperCeilerBrandView(it) }, update = { view ->
            view.configure(with(density) { logoSize.roundToPx() }, with(density) { textWidth.roundToPx() }, "${tr("Aikotoba", "Aikotoba")} | ${BuildConfig.VERSION_NAME}-${BuildConfig.BUILD_DATE}", versionColor, dark, onLogoClick, onVersionClick)
        }, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun License(back: () -> Unit) {
    val context = LocalContext.current
    val licenseText = remember(context) {
        context.assets.open("licenses/AGPL-3.0-or-later.txt").bufferedReader().use { it.readText() } + "\n\n" +
            context.assets.open("licenses/HyperMusicCover-GPL-3.0.txt").bufferedReader().use { it.readText() }
    }
    val upstreamNotices = remember(context) {
        context.assets.open("NOTICE").bufferedReader().use { it.readText() } + "\n\n" +
            context.assets.open("licenses/HyperMusicCover-NOTICE.txt").bufferedReader().use { it.readText() }
    }
    AppPage(tr("license", "LICENSE"), back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
                    Text(tr("project_license_name", "AGPL-3.0（组合分发）"), style = MiuixTheme.textStyles.title3)
                    Text(
                        tr("project_license_scope", "本组合分发按 GNU Affero 通用公共许可证第 3 版提供。HyperChanger 原创代码保留 AGPL-3.0-or-later 授权；导入的新版 HyperMusicCover 为 AGPL-3.0-only，部分文件保留 Apache-2.0 或 GPL-3.0。请参阅 NOTICE 与下列许可原文，分发时提供对应源代码及修改说明。"),
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    ArrowPreference(
                        title = tr("project_license_source", "查看项目源代码"),
                        onClick = { openUrl(context, "https://github.com/ColdP/HyperChanger") },
                    )
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(16.dp)) {
                    Text(licenseText, style = MiuixTheme.textStyles.body2)
                    Text(tr("hmcNotices", "HyperMusicCover 许可与修改说明"), style = MiuixTheme.textStyles.title3, modifier = Modifier.padding(top = 16.dp))
                    Text(upstreamNotices, style = MiuixTheme.textStyles.body2)
                }
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
    var deleteConfirmationPack by remember { mutableStateOf<LanguagePack?>(null) }
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
    fun deletePack(pack: LanguagePack) {
        val updatedPacks = packs.filterNot { it.name == pack.name }
        packs = updatedPacks
        saveLanguagePacks(prefs, updatedPacks)
        menuPack = null
        if (selected == pack.selection) {
            selected = "system"
            applyLanguageSelection(context, prefs, "system", updatedPacks)
        }
        languageChanged()
    }
    AppPage(tr("language", "语言"), back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            builtInEntries.forEach { pack ->
                item {
                    LanguageCard(pack, selected == pack.selection, onSelect = { select(pack) }, onLongPress = { menuPack = pack })
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
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
                    GlassDialogButton({ menuPack = null; deleteConfirmationPack = pack }, Modifier.fillMaxWidth()) { Text(tr("删除", "删除"), color = ComposeColor(0xFFD32F2F)) }
                }
                GlassDialogButton({ showExport(pack) }, Modifier.fillMaxWidth()) { Text(tr("export", "导出")) }
                GlassDialogButton({ menuPack = null }, Modifier.fillMaxWidth()) { Text(tr("cancel", "取消")) }
            }
        }
    }
    deleteConfirmationPack?.let { pack ->
        WindowDialog(show = true, onDismissRequest = { deleteConfirmationPack = null }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("deleteLanguage", tr("删除语言？", "删除语言？")), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                Text(tr("deleteLanguageSummary", tr("此语言包将被永久删除。", "此语言包将被永久删除。")), style = MiuixTheme.textStyles.body2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassDialogButton({ deleteConfirmationPack = null }, Modifier.weight(1f)) { Text(tr("cancel", tr("取消", "取消"))) }
                    GlassDialogButton({ deleteConfirmationPack = null; deletePack(pack) }, Modifier.weight(1f)) {
                        Text(tr("删除", "删除"), color = ComposeColor(0xFFD32F2F))
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageCard(pack: LanguagePack, selected: Boolean, onSelect: () -> Unit, onLongPress: () -> Unit) {
    Card(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(22.5.dp))
            .combinedClickable(onClick = onSelect, onLongClick = onLongPress),
        cornerRadius = 22.5.dp,
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
            item { Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(12.dp)) { TextField(value = text, onValueChange = { text = it; dirty = true }, modifier = Modifier.fillMaxWidth(), singleLine = false, label = tr("json", "JSON")) } }
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
private fun GlassLyricFloatingToolbar(
    content: @Composable () -> Unit,
) {
    val backdrop = LocalToolbarBackdrop.current
    val animationScope = rememberCoroutineScope()
    val touchHighlight = remember(animationScope) {
        InteractiveHighlight(
            animationScope = animationScope,
            radiusMultiplier = .4f,
            surfaceAlpha = 0f,
            falloffMultiplier = .25f,
        ) { size, offset ->
            Offset(
                offset.x.coerceIn(0f, size.width),
                offset.y.coerceIn(0f, size.height),
            )
        }
    }
    val surface = MiuixTheme.colorScheme.surfaceContainer
    val isLightTheme = surface.luminance() > .5f
    val tint = (if (isLightTheme) ComposeColor(0xFFF8F8F8) else surface).copy(alpha = .80f)
    val shadowColor = if (isLightTheme) ComposeColor.Gray else ComposeColor.Black
    val shape = RoundedCornerShape(50.dp)
    val glassModifier = if (backdrop != null && isRuntimeShaderSupported()) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                blur(3.dp.toPx())
                lens(12.dp.toPx(), 32.dp.toPx(), chromaticAberration = true)
            },
            highlight = { Highlight.Default.copy(alpha = .70f) },
            shadow = {
                Shadow.Default.copy(
                    radius = 12.dp,
                    color = shadowColor,
                    alpha = .35f,
                )
            },
            innerShadow = null,
            onDrawSurface = { drawRect(tint) },
        )
    } else {
        Modifier.clip(shape).background(tint)
    }
    FloatingToolbar(
        modifier = glassModifier
            .then(touchHighlight.gestureModifier)
            .then(touchHighlight.modifier),
        color = ComposeColor.Transparent,
        cornerRadius = 50.dp,
        shadowElevation = 0.dp,
        showDivider = false,
        content = content,
    )
}

@Composable
internal fun GlassToolbarIconButton(
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
private fun ProgressiveTopBarMaterial(
    backdrop: MiuixLayerBackdrop,
    collapsedFraction: Float,
    legacyBackdrop: LayerBackdrop? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val minimumContentHeightPx = with(density) { PROGRESSIVE_TOP_BAR_MINIMUM_CONTENT_HEIGHT.dp.roundToPx() }
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val foreground = subcompose("progressive-top-bar-foreground") {
            Box(Modifier.fillMaxWidth(), content = content)
        }.map { measurable -> measurable.measure(constraints.copy(minHeight = 0)) }
        val width = (foreground.maxOfOrNull { it.width } ?: constraints.minWidth)
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (foreground.maxOfOrNull { it.height } ?: constraints.minHeight)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        // Extend only the Miuix material; keep the title-bar layout height unchanged.
        val baseMaterialHeight = height.coerceAtLeast(minimumContentHeightPx)
        val materialHeight = if (legacyBackdrop == null) {
            (baseMaterialHeight * PROGRESSIVE_TOP_BAR_HEIGHT_FACTOR).toInt()
        } else {
            baseMaterialHeight
        }
        val background = subcompose("progressive-top-bar-background") {
            if (legacyBackdrop != null) {
                LegacyProgressiveTopBarBackground(
                    backdrop = legacyBackdrop,
                    progress = collapsedFraction,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ProgressiveTopBarBackground(
                    backdrop = backdrop,
                    progress = collapsedFraction,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }.map { measurable -> measurable.measure(Constraints.fixed(width, materialHeight)) }
        layout(width, height) {
            background.forEach { it.placeRelative(0, 0) }
            foreground.forEach { it.placeRelative(0, 0) }
        }
    }
}

@Composable
private fun LegacyProgressiveTopBarBackground(
    backdrop: LayerBackdrop,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    if (progress <= 0f) return
    Box(modifier.graphicsLayer { alpha = progress }) {
        Box(
            Modifier.matchParentSize()
                .expandDrawHeight(TOP_BAR_MATERIAL_HEIGHT_FACTOR)
                .drawPlainBackdrop(
                    backdrop = backdrop,
                    shape = { androidx.compose.ui.graphics.RectangleShape },
                    effects = {
                        blur(10.dp.toPx())
                        runtimeShaderEffect(
                            key = "about-progressive-blur-alpha-mask",
                            shaderString = PROGRESSIVE_BLUR_ALPHA_MASK_SHADER,
                            uniformShaderName = "content",
                        ) { setFloatUniform("size", size.width, size.height) }
                    },
                )
        )
    }
}

@Composable
private fun ProgressiveTopBarBackground(
    backdrop: MiuixLayerBackdrop,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    if (progress <= 0f) return
    val surface = MiuixTheme.colorScheme.surface
    Box(modifier.graphicsLayer { alpha = progress }) {
        Box(
            Modifier.matchParentSize().progressiveTextureBlur(
                backdrop = backdrop,
                shape = androidx.compose.ui.graphics.RectangleShape,
                blurRadius = PROGRESSIVE_TOP_BAR_BLUR_RADIUS,
                gradient = ProgressiveBlur.Top.copy(startFraction = PROGRESSIVE_TOP_BAR_BLUR_FADE_START),
            )
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to surface.copy(alpha = PROGRESSIVE_TOP_BAR_OVERLAY_ALPHA),
                    PROGRESSIVE_TOP_BAR_OVERLAY_END to ComposeColor.Transparent,
                    1f to ComposeColor.Transparent,
                )
            )
        )
    }
}

private const val PROGRESSIVE_TOP_BAR_BLUR_RADIUS = 14f
private const val PROGRESSIVE_TOP_BAR_BLUR_FADE_START = 0.60f
private const val PROGRESSIVE_TOP_BAR_OVERLAY_ALPHA = 0.94f
private const val PROGRESSIVE_TOP_BAR_OVERLAY_END = 0.80f
private const val PROGRESSIVE_TOP_BAR_MINIMUM_CONTENT_HEIGHT = 56f
private const val PROGRESSIVE_TOP_BAR_HEIGHT_FACTOR = 1.22f
private const val TOP_BAR_MATERIAL_HEIGHT_FACTOR = 1.4926275f
private const val TOOLBAR_GLASS_SURFACE_ALPHA = .80f
internal val TOOLBAR_GLASS_BLUR_RADIUS = 2.dp
private val TOOLBAR_GLASS_LENS_HEIGHT = 16.dp
private val TOOLBAR_GLASS_LENS_AMOUNT = 32.dp

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

internal fun BackdropEffectScope.toolbarGlassLens(progress: Float = 0f) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    lens(
        (TOOLBAR_GLASS_LENS_HEIGHT + 4.dp * clampedProgress).toPx(),
        (TOOLBAR_GLASS_LENS_AMOUNT + 8.dp * clampedProgress).toPx(),
        depthEffect = true,
        chromaticAberration = true,
    )
}

internal fun toolbarGlassHighlight(progress: Float = 0f): Highlight {
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
internal fun OverlayDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showValueOnEnd: Boolean = false,
    disabledIndices: Set<Int> = emptySet(),
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
        showValueOnEnd = showValueOnEnd,
        disabledIndices = disabledIndices,
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
    size: Dp = 46.dp,
    surfaceColor: ComposeColor? = null,
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
    val tint = (surfaceColor ?: surface).copy(alpha = TOOLBAR_GLASS_SURFACE_ALPHA)
    val backdrop = LocalToolbarBackdrop.current
    val collapsedFraction = LocalToolbarCollapsed.current
    Box(
        Modifier.padding(end = 4.dp).size(size)
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

@Composable
internal fun AppList(padding: PaddingValues, scroll: ScrollBehavior, bottom: Int = 112, items: LazyListScope.() -> Unit) {
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

private enum class HyperMusicCoverSection { COVER, LYRICS, CAPSULE_APPEARANCE, CAPSULE_FEATURES }

@Composable
private fun HyperMusicCoverOptions(section: HyperMusicCoverSection) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsStateWithLifecycle()
    val store = remember(context) { HyperMusicCoverSettingsStore(context) }
    var state by remember { mutableStateOf(store.settings) }
    LaunchedEffect(service) { service?.let { store.syncRemote(it); state = store.settings } }
    fun update(transform: (HyperMusicCoverSettings) -> HyperMusicCoverSettings) {
        store.update(service, transform); state = store.settings
    }
    Column {
        when (section) {
            HyperMusicCoverSection.CAPSULE_APPEARANCE -> {
                OverlayDropdownPreference(title = tr("hmcIslandStyle", "胶囊排列方式"), items = listOf(tr("hmcRow", "并排"), tr("hmcStack", "堆叠")), selectedIndex = state.islandStyle, onSelectedIndexChange = { value -> update { it.copy(islandStyle = value) } })
                SwitchPreference(title = tr("hmcAdaptiveWidth", "适应快捷按钮剩余宽度"), checked = state.adaptiveWidth, onCheckedChange = { value -> update { it.copy(adaptiveWidth = value) } })
                SwitchPreference(title = tr("hmcStatusAtDate", "状态信息跟随日期显示"), checked = state.statusAtDate, onCheckedChange = { value -> update { it.copy(statusAtDate = value) } })
                SwitchPreference(title = tr("hmcFingerprintLift", "避让低位屏下指纹"), checked = state.fingerprintLift, onCheckedChange = { value -> update { it.copy(fingerprintLift = value) } })
            }
            HyperMusicCoverSection.CAPSULE_FEATURES -> {
                SwitchPreference(title = tr("hmcOrdinary", "普通通知加入胶囊"), checked = state.ordinaryNotifications, onCheckedChange = { value -> update { it.copy(ordinaryNotifications = value) } })
                SwitchPreference(title = tr("hmcGroupByApp", "按应用分组通知"), checked = state.groupByApp, onCheckedChange = { value -> update { it.copy(groupByApp = value) } })
                SwitchPreference(title = tr("hmcNavigationKeepOn", "锁屏导航时保持屏幕常亮"), checked = state.navigationKeepOn, onCheckedChange = { value -> update { it.copy(navigationKeepOn = value) } })
            }
            HyperMusicCoverSection.COVER -> {
                OverlayDropdownPreference(title = tr("hmcCoverStyle", "音乐封面样式"), items = listOf(tr("hmcFullCover", "全屏封面"), tr("hmcCardCover", "方形封面"), tr("hmcKeepWallpaper", "保留壁纸")), selectedIndex = state.coverStyle, onSelectedIndexChange = { value -> update { it.copy(coverStyle = value) } })
                SwitchPreference(title = tr("hmcAutoEnter", "自动进入音乐锁屏"), checked = state.autoEnter, onCheckedChange = { value -> update { it.copy(autoEnter = value) } })
                SwitchPreference(title = tr("hmcMotion", "Apple Music 动态封面"), checked = state.motionCover, onCheckedChange = { value -> update { it.copy(motionCover = value) } })
                SwitchPreference(title = tr("hmcMotionData", "允许使用移动数据加载动态封面"), checked = state.motionOnData, onCheckedChange = { value -> update { it.copy(motionOnData = value) } })
                FloatSlide(tr("hmcClockSize", "音乐锁屏时钟比例"), state.clockSize, 0.05f..1f, 0.09f) { value -> update { it.copy(clockSize = value) } }
            }
            HyperMusicCoverSection.LYRICS -> {
                SwitchPreference(title = tr("hmcOnline", "在线查找歌词"), checked = state.lyricsOnline, onCheckedChange = { value -> update { it.copy(lyricsOnline = value) } })
                SwitchPreference(title = tr("hmcTranslation", "显示歌词翻译"), checked = state.lyricsTranslation, onCheckedChange = { value -> update { it.copy(lyricsTranslation = value) } })
                SwitchPreference(title = tr("hmcRomanization", "显示歌词罗马音"), checked = state.lyricsRomanization, onCheckedChange = { value -> update { it.copy(lyricsRomanization = value) } })
                OverlayDropdownPreference(title = tr("hmcAlign", "歌词对齐"), items = listOf(tr("hmcLeft", "左对齐"), tr("hmcCenter", "居中"), tr("hmcRight", "右对齐")), selectedIndex = state.lyricsAlign, onSelectedIndexChange = { value -> update { it.copy(lyricsAlign = value) } })
                FloatSlide(tr("hmcLyricSize", "主歌词字号 (sp)"), state.lyricsSize, 18f..36f, 25f) { value -> update { it.copy(lyricsSize = value) } }
                FloatSlide(tr("hmcBackgroundSize", "背景歌词字号 (sp)"), state.lyricsBackgroundSize, 10f..30f, 17f) { value -> update { it.copy(lyricsBackgroundSize = value) } }
                val appearance = JSONObject(state.lyricAppearance)
                fun updateAppearance(key: String, value: Any) { update { it.copy(lyricAppearance = JSONObject(it.lyricAppearance).put(key, value).toString()) } }
                OverlayDropdownPreference(title = tr("hmcMainColorMode", "主歌词颜色模式"), items = listOf(tr("hmcOfficial", "默认颜色"), tr("hmcFromCover", "跟随封面"), tr("hmcCustom", "自定义颜色")), selectedIndex = appearance.optInt("mainMode", 0), onSelectedIndexChange = { updateAppearance("mainMode", it) })
                if (appearance.optInt("mainMode") == 2) ShortcutBackgroundColorPreference(title = tr("hmcMainColor", "主歌词颜色"), color = appearance.optInt("mainColor", -1), onColorChange = { updateAppearance("mainColor", it) })
                FloatSlide(tr("hmcMainOpacity", "主歌词不透明度"), appearance.optDouble("mainOpacity", 1.0).toFloat(), 0f..1f, 1f) { updateAppearance("mainOpacity", it) }
                OverlayDropdownPreference(title = tr("hmcBgColorMode", "背景歌词颜色模式"), items = listOf(tr("hmcOfficial", "默认颜色"), tr("hmcFromCover", "跟随封面"), tr("hmcCustom", "自定义颜色")), selectedIndex = appearance.optInt("bgMode", 0), onSelectedIndexChange = { updateAppearance("bgMode", it) })
                if (appearance.optInt("bgMode") == 2) ShortcutBackgroundColorPreference(title = tr("hmcBgColor", "背景歌词颜色"), color = appearance.optInt("bgColor", -1), onColorChange = { updateAppearance("bgColor", it) })
                FloatSlide(tr("hmcBgOpacity", "背景歌词不透明度"), appearance.optDouble("bgOpacity", 0.72).toFloat(), 0f..1f, .72f) { updateAppearance("bgOpacity", it) }
            }
        }
    }
}
