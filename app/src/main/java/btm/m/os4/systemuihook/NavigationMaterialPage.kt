package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import btm.m.liquidglass.*
import btm.m.liquidglass.hook.CustomNavigation
import btm.m.liquidglass.hook.ExtractedNavIcons
import btm.m.liquidglass.hook.HostTab
import btm.m.liquidglass.hook.XiaohongshuPublishSettings
import btm.m.liquidglass.hook.RedditCreateSettings
import btm.m.liquidglass.hook.appleMusicTabIcon
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun rememberNavigationMaterialRevision(p: SharedPreferences): Int {
    var revision by remember(p) { mutableIntStateOf(0) }
    DisposableEffect(p) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || (NavigationMaterialSettings.isPreference(key) || HyperDockedNavigationSettings.isPreference(key))) revision++
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        onDispose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return revision
}

@Composable
internal fun rememberModuleNavigationMaterial(style: String): NavigationMaterial {
    val context = LocalContext.current
    val p = remember(context) { context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE) }
    val revision = rememberNavigationMaterialRevision(p)
    return remember(p, revision, style) {
        val navigationStyle = NavigationStyle.fromPreference(style)
        if (NavigationMaterialSettings.moduleEnabled(p)) NavigationMaterialSettings.read(p, navigationStyle)
        else NavigationMaterialSettings.defaults(navigationStyle)
    }
}

@Composable
private fun rememberNavigationMaterialDraft(
    prefs: SharedPreferences, style: NavigationStyle, packageName: String?, service: io.github.libxposed.service.XposedService?,
): MutableState<NavigationMaterial> {
    // Keep the state object (and callbacks referring to it) stable across preference notifications.
    // In particular, toggling module consumption must never reconstruct a slider's draft.
    val draft = remember(prefs, style, packageName) {
        mutableStateOf(NavigationMaterialSettings.read(prefs, style, packageName))
    }
    val currentService by rememberUpdatedState(service)
    DisposableEffect(prefs, style, packageName) {
        var persisted = draft.value
        fun refresh() {
            val value = NavigationMaterialSettings.read(prefs, style, packageName)
            if (value != persisted) {
                persisted = value
                draft.value = value
            }
        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || NavigationMaterialSettings.isRecipePreference(key, style, packageName)) refresh()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        refresh()
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            // A cancelled drag or page/tab exit may not deliver onValueChangeFinished.
            if (draft.value != persisted) NavigationMaterialSettings.save(prefs, currentService, style, draft.value, packageName)
        }
    }
    return draft
}

@Composable
internal fun NavigationMaterialPage(packageName: String?, back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val prefs = remember(context) { context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE) }
    val revision = rememberNavigationMaterialRevision(prefs)
    val dockedGlass = rememberHyperDockedGlassEnabled()
    val availableStyles = NavigationMaterialSettings.styles.filter { it != NavigationStyle.HYPER_OS || dockedGlass }
    var tab by rememberSaveable(packageName) { mutableIntStateOf(if (packageName == null) 0 else
        availableStyles.indexOf(NavigationStyle.fromPreference(ScopedSettings.getString(prefs, packageName,
            NavigationStyle.PREFERENCE_KEY, NavigationStyle.DEFAULT_VALUE))).coerceAtLeast(0)) }
    val activeTab = tab.takeIf { it in availableStyles.indices } ?: 0
    val style = availableStyles[activeTab]
    val defaults = NavigationMaterialSettings.defaults(style)
    var material by rememberNavigationMaterialDraft(prefs, style, packageName, service)
    var moduleEnabled by remember(revision) { mutableStateOf(NavigationMaterialSettings.moduleEnabled(prefs)) }
    var imageRevision by remember { mutableIntStateOf(0) }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmParameters by remember { mutableStateOf(false) }
    var imageBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val image by produceState<Bitmap?>(null, imageRevision) {
        value = withContext(Dispatchers.IO) { runCatching {
            val file = File(context.filesDir, NavigationMaterialSettings.PREVIEW_FILE)
            if (file.isFile) decodeNavigationPreview(file.readBytes()) else null
        }.getOrNull() }
    }
    val imageError = tr("navMaterialImageError", "无法读取或保存预览图片，请选择其他图片。")
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !imageBusy) {
            imageBusy = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { saveNavigationPreviewImage(context, uri) } }
                    .onSuccess { imageRevision++ }
                    .onFailure { Toast.makeText(context, imageError, Toast.LENGTH_LONG).show() }
                imageBusy = false
            }
        }
    }
    LaunchedEffect(service) {
        service?.let { NavigationMaterialSettings.syncPreferences(prefs, it.getRemotePreferences(REMOTE_PREFERENCE_GROUP)) }
    }
    fun save() = NavigationMaterialSettings.save(prefs, service, style, material, packageName)
    AppPage(
        title = if (packageName == null) tr("navMaterialTitle", "底部导航栏材质参数调整") else tr("navMaterialAppTitle", "材质参数单独调整"),
        onBack = back,
        restartScopes = ScopeApplication.entries.filter { target ->
            if (packageName != null) target.packageName == packageName else ScopedSettings.apps.any { it.packageName == target.packageName }
        }.toSet(),
        restartEnabled = service != null,
        restartScrollableList = packageName == null,
        header = {
            SoftGlassHeaderTabs(availableStyles.map { when (it) {
                NavigationStyle.HYPER_OS -> tr("navMaterialDocked", "贴边导航栏")
                NavigationStyle.HYPER_OS_FLOATING -> tr("HyperOS", "HyperOS")
                NavigationStyle.LIQUID_GLASS -> tr("液态玻璃", "液态玻璃")
                NavigationStyle.HARMONY_OS_FLOATING -> tr("HarmonyOS", "HarmonyOS")
            } }, activeTab, widthScale = if (dockedGlass) 1.2f else 1f,
                finalWidthScale = if (dockedGlass) .95f else 1f) { tab = it }
        },
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                val backdrop = rememberLayerBackdrop()
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                    Box(Modifier.fillMaxWidth().height(310.dp).clip(RoundedCornerShape(22.5.dp))) {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).pointerInput(imageBusy) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                if (imageBusy) return@awaitEachGesture
                                val up = withTimeoutOrNull(5000L) { waitForUpOrCancellation() }
                                if (up != null) {
                                    up.consume()
                                    picker.launch(arrayOf("image/*"))
                                } else if (currentEvent.changes.any { it.id == down.id && it.pressed }) {
                                    confirmRestore = true
                                    currentEvent.changes.forEach { it.consume() }
                                }
                            }
                        }) {
                            if (image != null) Image(requireNotNull(image).asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Image(painterResource(R.drawable.lockscreen_widget_preview_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        NavigationMaterialPreview(packageName, prefs, style, material, backdrop)
                    }
                }
            }
            item {
                key(packageName, style) {
                Group(tr("参数调整", "参数调整")) {
                    MaterialPercent(tr("navMaterialBlur", "模糊度"), material.blur, 300, defaults.blur,
                        { material = material.copy(blur = it) }, { save() })
                    MaterialPercent(tr("navMaterialOpacity", "背景不透明度"), material.opacity, 250, defaults.opacity,
                        { material = material.copy(opacity = it) }, { save() })
                    MaterialPercent(tr("navMaterialRefraction", "折射强度"), material.refraction, 200, defaults.refraction,
                        { material = material.copy(refraction = it) }, { save() })
                    MaterialPercent(tr("navMaterialThickness", "折射厚度"), material.thickness, 200, defaults.thickness,
                        { material = material.copy(thickness = it) }, { save() })
                    MaterialPercent(tr("navMaterialHighlight", "高光强度"), material.highlight, 200, defaults.highlight,
                        { material = material.copy(highlight = it) }, { save() })
                    if (style == NavigationStyle.HYPER_OS_FLOATING) {
                        MaterialPercent(tr("navMaterialSelectionOpacity", "选中胶囊不透明度"), material.selectionOpacity, 300, defaults.selectionOpacity,
                            { material = material.copy(selectionOpacity = it) }, { save() })
                    } else if (style != NavigationStyle.HYPER_OS) {
                        MaterialPercent(tr("navMaterialPressBoost", "按压折射增强"), material.pressBoost, 200, defaults.pressBoost,
                            { material = material.copy(pressBoost = it) }, { save() })
                    }
                    SwitchPreference(title = tr("navMaterialDispersion", "色散"),
                        checked = material.dispersion ?: (style == NavigationStyle.LIQUID_GLASS || style == NavigationStyle.HYPER_OS),
                        onCheckedChange = { material = material.copy(dispersion = it); save() })
                    SharedViewArrowPreference(title = tr("navMaterialResetParameters", "恢复默认参数"), onClick = { confirmParameters = true })
                    if (packageName == null) {
                        SwitchPreference(
                            title = tr("navMaterialModule", "HyperChanger 模块内底部导航栏使用同样的材质参数"),
                            checked = moduleEnabled,
                            onCheckedChange = { moduleEnabled = it; NavigationMaterialSettings.saveModule(prefs, service, it) },
                        )
                    }
                }
                }
            }
        }
    }
    WindowDialog(show = confirmParameters, title = tr("navMaterialResetParameters", "恢复默认参数"), onDismissRequest = { confirmParameters = false }) {
        Text(tr("navMaterialResetConfirm", "确定恢复当前样式的默认材质参数吗？"))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton(onClick = { confirmParameters = false }, modifier = Modifier.weight(1f)) { Text(tr("取消", "取消")) }
            GlassDialogButton(onClick = { confirmParameters = false; material = defaults; save() }, modifier = Modifier.weight(1f)) { Text(tr("恢复默认", "恢复默认")) }
        }
    }
    WindowDialog(show = confirmRestore, title = tr("navMaterialRestoreImage", "恢复默认预览图"), onDismissRequest = { confirmRestore = false }) {
        Text(tr("navMaterialRestoreConfirm", "确定移除自定义预览图并恢复默认图片吗？"))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton(onClick = { confirmRestore = false }, modifier = Modifier.weight(1f)) { Text(tr("取消", "取消")) }
            GlassDialogButton(onClick = {
                confirmRestore = false
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) {
                        val file = File(context.filesDir, NavigationMaterialSettings.PREVIEW_FILE)
                        check(!file.exists() || file.delete())
                    } }.onSuccess { imageRevision++ }.onFailure { Toast.makeText(context, imageError, Toast.LENGTH_LONG).show() }
                }
            }, modifier = Modifier.weight(1f)) { Text(tr("恢复默认", "恢复默认")) }
        }
    }
}

@Composable
private fun MaterialPercent(title: String, value: Int, max: Int, default: Int, change: (Int) -> Unit, save: () -> Unit) {
    EditableSliderPreference(title = title, value = value.toFloat(), valueText = tr("navMaterialPercent", "%d%%").format(value), valueRange = 0f..max.toFloat(),
        steps = max - 1, defaultValue = default.toFloat(), onValueChange = { change(it.roundToInt()) }, onValueChangeFinished = save)
}

internal fun decodeNavigationPreview(bytes: ByteArray): Bitmap {
    require(bytes.size <= 30 * 1024 * 1024)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1920) sample *= 2
    return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }))
}

private fun saveNavigationPreviewImage(context: Context, uri: Uri) {
    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 30 * 1024 * 1024)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    val bitmap = decodeNavigationPreview(bytes)
    try {
        val file = AtomicFile(File(context.filesDir, NavigationMaterialSettings.PREVIEW_FILE))
        file.baseFile.parentFile?.mkdirs()
        val out = file.startWrite()
        try { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)); file.finishWrite(out) }
        catch (error: Throwable) { file.failWrite(out); throw error }
    } finally { bitmap.recycle() }
}

@Composable
private fun BoxScope.NavigationMaterialPreview(
    packageName: String?, prefs: SharedPreferences, style: NavigationStyle, material: NavigationMaterial, backdrop: com.kyant.backdrop.Backdrop,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val selected = remember(packageName, style) { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var snapshotRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) snapshotRevision++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var actionPressed by remember { mutableStateOf(false) }
    val source by produceState<Pair<List<MaterialPreviewTab>, MaterialPreviewTab?>?>(null, packageName, snapshotRevision) {
        value = withContext(Dispatchers.IO) { loadNavigationPreview(context, packageName) }
    }
    val publish = XiaohongshuPublishSettings.load(prefs)
    val create = RedditCreateSettings.load(prefs)
    val raw = source?.first.orEmpty().toMutableList()
    var action = source?.second
    if (packageName == XiaohongshuPublishSettings.PACKAGE && raw.isNotEmpty()) {
        val existing = raw.firstOrNull { it.kind == "PUBLISH" } ?: action?.takeIf { it.kind == "PUBLISH" }
        raw.removeAll { it.kind == "PUBLISH" }
        if (publish.separate) action = existing?.copy(image = remember(context) { context.getDrawable(R.drawable.xhs_nav_red_view_add)?.toBitmap(96, 96)?.asImageBitmap() })
        else {
            existing?.let { raw.add(it.position.takeIf { pos -> pos >= 0 }?.coerceAtMost(raw.size) ?: minOf(2, raw.size), it.copy(image = remember(context) { context.getDrawable(R.drawable.xhs_nav_red_view_new_post_v2)?.toBitmap(96, 96)?.asImageBitmap() })) }
            action = null
        }
    }
    if (packageName == RedditCreateSettings.PACKAGE && raw.isNotEmpty()) {
        val existing = raw.firstOrNull { it.kind == "PUBLISH" } ?: action?.takeIf { it.kind == "PUBLISH" }
        raw.removeAll { it.kind == "PUBLISH" }
        if (create.separate) action = existing
        else {
            existing?.let { raw.add(it.position.takeIf { pos -> pos >= 0 }?.coerceAtMost(raw.size) ?: minOf(2, raw.size), it) }
            action = null
        }
    }
    if (raw.isEmpty()) {
        Text(tr("navMaterialLoadTabs", "请先打开一次目标应用，载入当前底部导航栏的实际 Tab 和图标。"),
            modifier = Modifier.align(Alignment.Center).padding(24.dp), color = Color.White)
        return
    }
    val entries = raw.toList()
    fun click(index: Int) { if (entries[index].kind == "PUBLISH") actionPressed = !actionPressed else selected.intValue = index }
    if (actionPressed) Text(tr("navMaterialActionPreview", "按钮已响应"), modifier = Modifier.align(Alignment.Center).padding(12.dp), color = Color.White)
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(118.dp)) {
        CustomNavigation(sourceView = view,
            tabs = entries.mapIndexed { index, tab -> HostTab(tr(tab.label, tab.label), tab.kind,
                iconOnly = packageName == XiaohongshuPublishSettings.PACKAGE && tab.kind == "PUBLISH") { click(index) } },
            selectedIndex = selected,
            blurRadius = if (packageName == null) (if (style == NavigationStyle.LIQUID_GLASS) 3 else 18)
                else ScopedSettings.getBlurRadius(prefs, packageName, style.preferenceValue),
            labelMode = if (packageName == null) LabelMode.DEFAULT_VALUE else ScopedSettings.getLabelMode(prefs, packageName),
            navigationStyle = style.preferenceValue, advancedMaterial = true,
            colorMode = if (packageName == null) AppColorMode.DEFAULT_VALUE else ScopedSettings.getString(prefs, packageName, ScopedSettings.KEY_COLOR_MODE, AppColorMode.DEFAULT_VALUE),
            material = material, adaptiveFloatingWidth = true, adaptiveLiquidWidth = true, liquidBarWidthFraction = 1f,
            accentColorOverride = Color(NavigationAccentSettings.getColor(prefs, packageName.orEmpty())),
            hyperFloatingAccentEnabled = packageName != null && HyperFloatingAccentSettings.isEnabled(prefs, packageName),
            hyperDockedAccentEnabled = packageName != null && HyperDockedNavigationSettings.accentEnabled(prefs, packageName),
            hyperDockedLiquidGlassEnabled = rememberHyperDockedGlassEnabled(),
            applyNavigationBarInsets = false,
            backdropOverride = backdrop, onHostPreDraw = {},
            tabIconContent = { index, color ->
                val tab = entries[index]
                val icon = if (selected.intValue == index) tab.selectedImage ?: tab.image else tab.image
                if (icon != null) Image(icon, null, Modifier.size(26.dp), colorFilter =
                    if (tab.preserveColor || (packageName == XiaohongshuPublishSettings.PACKAGE && tab.kind == "PUBLISH")) null else ColorFilter.tint(color))
                else {
                    val vector = when (packageName) {
                        "com.apple.android.music" -> appleMusicTabIcon(index)
                        "com.sina.weibo" -> when (tab.kind) { "HOME" -> ExtractedNavIcons.WeiboHome; "DISCOVER" -> ExtractedNavIcons.WeiboDiscover; "MESSAGES" -> ExtractedNavIcons.WeiboMessages; else -> ExtractedNavIcons.WeiboProfile }
                        else -> null
                    }
                    vector?.let { Image(it, null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(color)) }
                }
            },
            trailingAction = action?.let { item -> HostTab(tr(item.label, item.label), if (item.kind == "PUBLISH") "publish" else "search", iconBitmap = item.image,
                actionBackgroundColor = if (packageName == XiaohongshuPublishSettings.PACKAGE && publish.backgroundMode == "custom") Color(publish.background) else if (packageName == RedditCreateSettings.PACKAGE && create.backgroundMode == "custom") Color(create.background) else null,
                actionIconColor = if (packageName == XiaohongshuPublishSettings.PACKAGE && (publish.backgroundMode == "custom" || publish.iconMode != "auto")) Color(publish.iconColor()) else if (packageName == RedditCreateSettings.PACKAGE && (create.backgroundMode == "custom" || create.iconMode != "auto")) Color(create.iconColor()) else null,
                actionFollowsNavigationBackground = (packageName == XiaohongshuPublishSettings.PACKAGE && publish.backgroundMode == "default") ||
                    (packageName == RedditCreateSettings.PACKAGE && create.backgroundMode == "default")) { actionPressed = !actionPressed } },
        )
    }
}

private data class MaterialPreviewTab(
    val label: String,
    val kind: String,
    val image: ImageBitmap?,
    val position: Int = -1,
    val selectedImage: ImageBitmap? = null,
    val preserveColor: Boolean = false,
)

// The slightly heavier outline belongs to the material preview only, never the shared asset.
private fun personalPreviewOutline(source: Bitmap): ImageBitmap {
    val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(result)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    val radius = source.width / 69f * .85f
    for (step in 0 until 8) {
        val angle = step * Math.PI / 4
        canvas.drawBitmap(source, (kotlin.math.cos(angle) * radius).toFloat(), (kotlin.math.sin(angle) * radius).toFloat(), paint)
    }
    canvas.drawBitmap(source, 0f, 0f, paint)
    return result.asImageBitmap()
}

private fun loadNavigationPreview(context: Context, packageName: String?): Pair<List<MaterialPreviewTab>, MaterialPreviewTab?>? = runCatching {
    fun drawable(id: Int) = context.getDrawable(id)?.toBitmap(96, 96)?.asImageBitmap()
    if (packageName == null) {
        val me = context.assets.open("navigation/pinduoduo/personal_normal.png").use { stream ->
            BitmapFactory.decodeStream(stream)?.let { bitmap ->
                try { personalPreviewOutline(bitmap) } finally { bitmap.recycle() }
            }
        }
        val meSelected = context.assets.open("navigation/pinduoduo/personal_selected.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
        return@runCatching listOf(MaterialPreviewTab("首页", "HOME", drawable(R.drawable.x_nav_home_stroke), selectedImage = drawable(R.drawable.x_nav_home)),
            MaterialPreviewTab("搜索", "SEARCH", drawable(R.drawable.x_nav_search_stroke), selectedImage = drawable(R.drawable.x_nav_search)),
            MaterialPreviewTab("消息", "MESSAGES", drawable(R.drawable.x_nav_chat_stroke), selectedImage = drawable(R.drawable.x_nav_chat)),
            MaterialPreviewTab("我", "PROFILE", me, selectedImage = meSelected)) to null
    }
    val file = navigationPreviewFile(context, packageName)
    if (!file.isFile && packageName == XiaohongshuPublishSettings.PACKAGE) return@runCatching listOf(
        MaterialPreviewTab("首页", "HOME", drawable(R.drawable.xhs_nav_red_view_home)),
        MaterialPreviewTab("市集", "MARKET", drawable(R.drawable.xhs_nav_red_view_store)),
        MaterialPreviewTab("发布", "PUBLISH", drawable(R.drawable.xhs_nav_red_view_new_post_v2)),
        MaterialPreviewTab("消息", "MESSAGES", drawable(R.drawable.xhs_nav_red_view_message_b)),
        MaterialPreviewTab("我的", "PROFILE", drawable(R.drawable.xhs_nav_red_view_me))) to null
    val json = JSONObject(file.readText())
    fun item(data: JSONObject): MaterialPreviewTab {
        val bitmap = data.optString("icon").takeIf { it.isNotEmpty() }?.let {
            val bytes = Base64.decode(it, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
        return MaterialPreviewTab(data.getString("label"), data.getString("kind"), bitmap, data.optInt("position", -1), preserveColor = data.optBoolean("preserveColor", false))
    }
    val tabs = json.getJSONArray("tabs")
    (0 until tabs.length()).map { item(tabs.getJSONObject(it)) } to json.optJSONObject("action")?.let(::item)
}.getOrNull()
