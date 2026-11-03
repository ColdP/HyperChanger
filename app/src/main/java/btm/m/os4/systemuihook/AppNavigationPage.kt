package btm.m.os4.systemuihook

import android.graphics.drawable.Drawable
import android.content.SharedPreferences
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.libxposed.service.XposedService
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import btm.m.liquidglass.AppColorMode
import btm.m.liquidglass.LabelMode
import btm.m.liquidglass.NavigationStyle
import btm.m.liquidglass.ScopedSettings
import btm.m.liquidglass.NavigationAccentSettings
import btm.m.liquidglass.NavigationBadgeSettings
import btm.m.liquidglass.hook.WeChatAvatarRadiusSettings
import btm.m.liquidglass.rememberMomentumPredictiveBack
import btm.m.liquidglass.momentumBackTransform

private data class AppNavItem(val name: String, val packageName: String)

private val appNavItems = listOf(
    AppNavItem("QQ", "com.tencent.mobileqq"),
    AppNavItem("Apple Music", "com.apple.android.music"),
    AppNavItem("微博", "com.sina.weibo"),
    AppNavItem("小红书", "com.xingin.xhs"),
    AppNavItem("小米商城", "com.xiaomi.shop"),
    AppNavItem("小米钱包", "com.mipay.wallet"),
    AppNavItem("小米运动健康", "com.mi.health"),
    AppNavItem("瑞幸咖啡", "com.lucky.luckyclient"),
    AppNavItem("腾讯会议", "com.tencent.wemeet.app"),
    AppNavItem("招商银行", "cmb.pb"),
    AppNavItem("微信", "com.tencent.mm"),
    AppNavItem("拼多多", "com.xunmeng.pinduoduo"),
    AppNavItem("Instagram", "com.instagram.android"),
    AppNavItem("Pinterest", "com.pinterest"),
    AppNavItem("X", "com.twitter.android"),
    AppNavItem("GitHub", "com.github.android"),
)

@Composable
internal fun AppNavigationPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context) { AppNavigationSettingsStore(context) }
    var selected by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(store, lifecycleOwner) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || isAppNavigationPreference(key)) revision++
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            store.preferences.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    var lastSelected by remember { mutableStateOf<AppNavItem?>(null) }
    val overviewBlur by animateFloatAsState(if (selected != null) 12f else 0f, tween(252), label = "appNavigationOverviewBlur")
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().blur(overviewBlur.dp)) {
            AppPage(
                tr("应用底部导航", "应用底部导航"), back,
                restartScopes = appNavItems.mapNotNull { item ->
                    ScopeApplication.entries.firstOrNull { it.packageName == item.packageName }
                }.toSet(),
                restartEnabled = service != null,
                restartScrollableList = true,
            ) { padding, scroll ->
                AppList(padding, scroll, 28) {
                    item { SmallTitle(tr("支持的应用", "支持的应用"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp)) }
                    items(appNavItems, key = { it.packageName }) { item ->
                        AppNavigationCard(item, store, service, revision) {
                            lastSelected = item
                            selected = item.packageName
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = selected != null,
            enter = slideInHorizontally(tween(252, easing = FastOutSlowInEasing)) { it / 7 } +
                fadeIn(tween(189)) + scaleIn(tween(252, easing = FastOutSlowInEasing), initialScale = .985f),
            exit = slideOutHorizontally(tween(207, easing = FastOutSlowInEasing)) { -it / 3 } +
                fadeOut(tween(162)) + scaleOut(tween(207, easing = FastOutSlowInEasing), targetScale = .975f),
        ) {
            val selectedItem = lastSelected ?: return@AnimatedVisibility
            val transitionBlur by transition.animateFloat(
                transitionSpec = { tween(207, easing = FastOutSlowInEasing) }, label = "appNavigationDetailBlur",
            ) { if (it == EnterExitState.Visible) 0f else 12f }
            val settingsBack = rememberMomentumPredictiveBack(
                enabled = selected != null,
                onBack = { selected = null },
            )
            Box(Modifier.fillMaxSize().blur(transitionBlur.dp).momentumBackTransform(settingsBack)) {
                key(selectedItem.packageName) {
                    AppNavigationSettings(selectedItem, store, service, { revision++ }) { selected = null }
                }
            }
        }
    }
}

@Composable
private fun AppNavigationCard(item: AppNavItem, store: AppNavigationSettingsStore, service: XposedService?, revision: Int, onOpen: () -> Unit) {
    val prefs = store.preferences
    val context = LocalContext.current
    val info = remember(item.packageName, revision) { runCatching { context.packageManager.getApplicationInfo(item.packageName, 0) }.getOrNull() }
    val packageInfo = remember(item.packageName, revision) { runCatching { context.packageManager.getPackageInfo(item.packageName, 0) }.getOrNull() }
    var enabled by remember(item.packageName, revision, service) { mutableStateOf(ScopedSettings.getBoolean(prefs, item.packageName, ScopedSettings.KEY_ENABLED, true)) }
    val icon = remember(info) { info?.loadIcon(context.packageManager)?.toBitmap(96, 96)?.asImageBitmap() }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), cornerRadius = 22.5.dp, insideMargin = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Image(it, tr("应用图标", "应用图标"), Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tr(item.name, item.name), style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.packageName, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(packageInfo?.let { "${it.versionName ?: "?"} (${it.longVersionCode})" } ?: tr("未安装", "未安装"), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(28.dp).background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .35f)))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    store.edit(service) {
                        putBoolean(
                            ScopedSettings.key(item.packageName, ScopedSettings.KEY_ENABLED),
                            it
                        )
                    }
                }
            )
        }
    }
}

@Composable
private fun AppNavigationSettings(appItem: AppNavItem, store: AppNavigationSettingsStore, service: XposedService?, onChanged: () -> Unit, back: () -> Unit) {
    val prefs = store.preferences
    val enabledKey = ScopedSettings.key(appItem.packageName, ScopedSettings.KEY_ENABLED)
    var enabled by remember(appItem.packageName, service) { mutableStateOf(ScopedSettings.getBoolean(prefs, appItem.packageName, ScopedSettings.KEY_ENABLED, true)) }
    var styleIndex by remember(appItem.packageName, service) { mutableIntStateOf(NavigationStyle.fromPreference(ScopedSettings.getString(prefs, appItem.packageName, NavigationStyle.PREFERENCE_KEY, NavigationStyle.DEFAULT_VALUE)).ordinal) }
    var labelIndex by remember(appItem.packageName, service) { mutableIntStateOf(LabelMode.fromPreference(ScopedSettings.getLabelMode(prefs, appItem.packageName)).ordinal) }
    var colorIndex by remember(appItem.packageName, service) { mutableIntStateOf(AppColorMode.fromPreference(ScopedSettings.getString(prefs, appItem.packageName, ScopedSettings.KEY_COLOR_MODE, AppColorMode.DEFAULT_VALUE)).ordinal) }
    var accentColor by remember(appItem.packageName, service) { mutableIntStateOf(NavigationAccentSettings.getColor(prefs, appItem.packageName)) }
    var showBadges by remember(appItem.packageName, service) { mutableStateOf(NavigationBadgeSettings.getShowBadges(prefs, appItem.packageName)) }
    var showAccentDialog by remember { mutableStateOf(false) }
    val accentEnabled = enabled && NavigationStyle.entries.getOrNull(styleIndex) in setOf(
        NavigationStyle.LIQUID_GLASS,
        NavigationStyle.HARMONY_OS_FLOATING,
    )
    var walletTabs by remember(appItem.packageName, service) { mutableStateOf(ScopedSettings.getWalletVisibleTabs(prefs)) }
    var avatarRadius by remember(appItem.packageName, service) { mutableFloatStateOf(WeChatAvatarRadiusSettings.getOffset(prefs, appItem.packageName).toFloat()) }
    AppPage(tr(appItem.name, appItem.name), back,
        restartScopes = ScopeApplication.entries.filter { it.packageName == appItem.packageName }.toSet(),
        restartEnabled = service != null,
    ) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(title = tr("启用自定义底栏", "启用自定义底栏"), summary = tr("关闭后使用应用原始导航栏", "关闭后使用应用原始导航栏"), checked = enabled, onCheckedChange = { value -> enabled = value; store.edit(service) { putBoolean(enabledKey, value) }; onChanged() })
                    OverlayDropdownPreference(title = tr("导航栏样式", "导航栏样式"), items = NavigationStyle.entries.map { tr(it.displayName, it.displayName) }, selectedIndex = styleIndex, enabled = enabled) { value -> styleIndex = value; store.edit(service) { putString(ScopedSettings.key(appItem.packageName, NavigationStyle.PREFERENCE_KEY), NavigationStyle.entries[value].preferenceValue) }; onChanged() }
                    OverlayDropdownPreference(title = tr("标签显示方式", "标签显示方式"), items = LabelMode.entries.map { tr(it.displayName, it.displayName) }, selectedIndex = labelIndex, enabled = enabled) { value -> labelIndex = value; store.edit(service) { putString(ScopedSettings.key(appItem.packageName, ScopedSettings.KEY_LABEL_MODE), LabelMode.entries[value].preferenceValue) }; onChanged() }
                    OverlayDropdownPreference(title = tr("颜色模式", "颜色模式"), items = AppColorMode.entries.map { tr(it.displayName, it.displayName) }, selectedIndex = colorIndex, enabled = enabled) { value -> colorIndex = value; store.edit(service) { putString(ScopedSettings.key(appItem.packageName, ScopedSettings.KEY_COLOR_MODE), AppColorMode.entries[value].preferenceValue) }; onChanged() }
                    if (appItem.packageName == "com.tencent.mobileqq" || appItem.packageName == "com.tencent.mm") {
                        SwitchPreference(
                            title = tr("显示小红点", "显示小红点"),
                            summary = tr("在自定义底栏图标旁显示未读提示", "在自定义底栏图标旁显示未读提示"),
                            checked = showBadges,
                            enabled = enabled,
                            onCheckedChange = { value ->
                                showBadges = value
                                store.edit(service) { putBoolean(ScopedSettings.key(appItem.packageName, NavigationBadgeSettings.KEY_SHOW_BADGES), value) }
                                onChanged()
                            },
                        )
                    }
                    NavigationAccentPreference(
                        color = accentColor,
                        enabled = accentEnabled,
                        onClick = { if (accentEnabled) showAccentDialog = true },
                    )
                }
            }
            if (appItem.packageName == "com.mipay.wallet") item {
                SmallTitle(tr("钱包导航标签", "钱包导航标签"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp))
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    ScopedSettings.WALLET_TAB_IDS.forEach { tab ->
                        val checked = tab in walletTabs
                        SwitchPreference(title = walletTabName(tab), checked = checked, enabled = enabled && (!checked || walletTabs.size > 1), onCheckedChange = { value ->
                            walletTabs = if (value) walletTabs + tab else walletTabs - tab
                            store.edit(service) { putFrameworkStringSet(ScopedSettings.key(appItem.packageName, ScopedSettings.KEY_VISIBLE_TABS), walletTabs) }; onChanged()
                        })
                    }
                }
            }
            if (appItem.packageName == "com.tencent.mm") item {
                SmallTitle(tr("微信头像圆角", "微信头像圆角"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp))
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    EditableSliderPreference(
                        title = tr("头像圆角半径", "头像圆角半径"),
                        value = avatarRadius,
                        valueText = if (avatarRadius == 0f) tr("微信默认", "微信默认") else avatarRadius.toInt().toString(),
                        valueRange = WeChatAvatarRadiusSettings.MIN.toFloat()..WeChatAvatarRadiusSettings.MAX.toFloat(),
                        steps = 159,
                        defaultValue = 0f,
                        enabled = enabled,
                        onValueChange = { avatarRadius = it },
                        onValueChangeFinished = {
                            store.edit(service) {
                                putInt(ScopedSettings.key(appItem.packageName, WeChatAvatarRadiusSettings.KEY),
                                    avatarRadius.toInt().coerceIn(WeChatAvatarRadiusSettings.MIN, WeChatAvatarRadiusSettings.MAX))
                            }
                            onChanged()
                        },
                    )
                }
            }
        }
    }
    NavigationAccentDialog(
        show = showAccentDialog,
        initialColor = accentColor,
        defaultColor = NavigationAccentSettings.defaultColor(appItem.packageName),
        onDismiss = { showAccentDialog = false },
        onSave = { selected ->
            accentColor = selected
            store.edit(service) {
                putInt(ScopedSettings.key(appItem.packageName, NavigationAccentSettings.KEY), selected or 0xFF000000.toInt())
            }
            onChanged()
            showAccentDialog = false
        },
    )
}

@Composable
private fun NavigationAccentPreference(color: Int, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("底栏强调色", "底栏强调色"), modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1, color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurface.copy(alpha = .38f))
        Box(Modifier.size(28.dp).background(MiuixTheme.colorScheme.outline.copy(alpha = .45f), androidx.compose.foundation.shape.CircleShape).padding(2.dp).background(ComposeColor(color), androidx.compose.foundation.shape.CircleShape))
    }
}

@Composable
private fun NavigationAccentDialog(
    show: Boolean,
    initialColor: Int,
    defaultColor: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
) {
    var draftColor by remember(show, initialColor) { mutableIntStateOf(initialColor) }
    WindowDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("底栏强调色", "底栏强调色"), style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
            ColorPalette(
                color = ComposeColor(draftColor or 0xFF000000.toInt()),
                onColorChanged = { draftColor = it.toArgb() or 0xFF000000.toInt() },
                modifier = Modifier.fillMaxWidth(),
            )
            GlassDialogButton(onClick = { draftColor = defaultColor }, modifier = Modifier.fillMaxWidth()) {
                Text(tr("恢复默认", "恢复默认"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassDialogButton(onDismiss, Modifier.weight(1f)) { Text(tr("取消", "取消")) }
                GlassDialogButton({ onSave(draftColor) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) { Text(tr("保存", "保存")) }
            }
        }
    }
}

private fun walletTabName(id: String) = when (id) {
    "HOME" -> tr("首页", "首页"); "SAVINGS" -> tr("余额宝", "余额宝"); "SHORT_DRAMA" -> tr("短剧", "短剧"); "LOAN" -> tr("借钱", "借钱"); else -> tr("我的", "我的")
}
