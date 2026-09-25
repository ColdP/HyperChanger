package btm.m.os4.systemuihook

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.github.libxposed.service.XposedService
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import btm.m.liquidglass.AppColorMode
import btm.m.liquidglass.LabelMode
import btm.m.liquidglass.NavigationStyle
import btm.m.liquidglass.ScopedSettings
import btm.m.liquidglass.rememberMomentumPredictiveBack
import btm.m.liquidglass.momentumBackTransform

private data class AppNavItem(val name: String, val packageName: String)

private val appNavItems = listOf(
    AppNavItem("小米商城", "com.xiaomi.shop"),
    AppNavItem("小米钱包", "com.mipay.wallet"),
)

@Composable
internal fun AppNavigationPage(back: () -> Unit) {
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val prefs = remember(service) {
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
            ?: context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, 0)
    }
    var selected by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val selectedItem = appNavItems.firstOrNull { it.packageName == selected }
    if (selectedItem != null) {
        val settingsBack = rememberMomentumPredictiveBack(
            enabled = true,
            onBack = { selected = null },
        )
        Box(Modifier.fillMaxSize().then(Modifier.momentumBackTransform(settingsBack))) {
            AppNavigationSettings(selectedItem, prefs, { revision++ }) { selected = null }
        }
        return
    }
    AppPage(tr("应用底部导航", "应用底部导航"), back, restartScopes = setOf(ScopeApplication.XIAOMI_STORE, ScopeApplication.XIAOMI_WALLET), restartEnabled = service != null) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item { SmallTitle(tr("支持的应用", "支持的应用"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp)) }
            items(appNavItems, key = { it.packageName }) { item ->
                AppNavigationCard(item, prefs, revision) { selected = item.packageName }
            }
        }
    }
}

@Composable
private fun AppNavigationCard(item: AppNavItem, prefs: android.content.SharedPreferences, revision: Int, onOpen: () -> Unit) {
    val context = LocalContext.current
    val info = remember(item.packageName, revision) { runCatching { context.packageManager.getApplicationInfo(item.packageName, 0) }.getOrNull() }
    val packageInfo = remember(item.packageName, revision) { runCatching { context.packageManager.getPackageInfo(item.packageName, 0) }.getOrNull() }
    val enabled = remember(item.packageName, revision) { ScopedSettings.getBoolean(prefs, item.packageName, ScopedSettings.KEY_ENABLED, true) }
    val icon = remember(info) { info?.loadIcon(context.packageManager)?.toBitmap(96, 96)?.asImageBitmap() }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen), cornerRadius = 22.5.dp, insideMargin = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Image(it, tr("应用图标", "应用图标"), Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.packageName, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(packageInfo?.let { "${it.versionName ?: "?"} (${it.longVersionCode})" } ?: tr("未安装", "未安装"), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Switch(checked = enabled, onCheckedChange = { prefs.edit().putBoolean(ScopedSettings.key(item.packageName, ScopedSettings.KEY_ENABLED), it).apply() })
        }
    }
}

@Composable
private fun AppNavigationSettings(appItem: AppNavItem, prefs: android.content.SharedPreferences, onChanged: () -> Unit, back: () -> Unit) {
    val enabledKey = ScopedSettings.key(appItem.packageName, ScopedSettings.KEY_ENABLED)
    var enabled by remember(appItem.packageName) { mutableStateOf(ScopedSettings.getBoolean(prefs, appItem.packageName, ScopedSettings.KEY_ENABLED, true)) }
    var styleIndex by remember(appItem.packageName) { mutableIntStateOf(NavigationStyle.fromPreference(ScopedSettings.getString(prefs, appItem.packageName, NavigationStyle.PREFERENCE_KEY, NavigationStyle.DEFAULT_VALUE)).ordinal) }
    var labelIndex by remember(appItem.packageName) { mutableIntStateOf(LabelMode.fromPreference(ScopedSettings.getLabelMode(prefs, appItem.packageName)).ordinal) }
    var colorIndex by remember(appItem.packageName) { mutableIntStateOf(AppColorMode.fromPreference(ScopedSettings.getString(prefs, appItem.packageName, ScopedSettings.KEY_COLOR_MODE, AppColorMode.DEFAULT_VALUE)).ordinal) }
    var walletTabs by remember(appItem.packageName) { mutableStateOf(ScopedSettings.getWalletVisibleTabs(prefs)) }
    AppPage(appItem.name, back) { padding, scroll ->
        AppList(padding, scroll, 28) {
            item {
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    SwitchPreference(title = tr("启用自定义底栏", "启用自定义底栏"), summary = tr("关闭后使用应用原始导航栏", "关闭后使用应用原始导航栏"), checked = enabled, onCheckedChange = { value -> enabled = value; prefs.edit().putBoolean(enabledKey, value).apply(); onChanged() })
                    OverlayDropdownPreference(title = tr("导航栏样式", "导航栏样式"), items = NavigationStyle.entries.map { tr(it.displayName, it.displayName) }, selectedIndex = styleIndex, enabled = enabled) { value -> styleIndex = value; prefs.edit().putString(ScopedSettings.key(appItem.packageName, NavigationStyle.PREFERENCE_KEY), NavigationStyle.entries[value].preferenceValue).apply(); onChanged() }
                    OverlayDropdownPreference(title = tr("标签显示方式", "标签显示方式"), items = LabelMode.entries.map { tr(it.displayName, it.displayName) }, selectedIndex = labelIndex, enabled = enabled) { value -> labelIndex = value; prefs.edit().putString(ScopedSettings.key(appItem.packageName, ScopedSettings.KEY_LABEL_MODE), LabelMode.entries[value].preferenceValue).apply(); onChanged() }
                    OverlayDropdownPreference(title = tr("颜色模式", "颜色模式"), items = AppColorMode.entries.map { tr(it.displayName, it.displayName) }, selectedIndex = colorIndex, enabled = enabled) { value -> colorIndex = value; prefs.edit().putString(ScopedSettings.key(appItem.packageName, ScopedSettings.KEY_COLOR_MODE), AppColorMode.entries[value].preferenceValue).apply(); onChanged() }
                }
            }
            if (appItem.packageName == "com.mipay.wallet") item {
                SmallTitle(tr("钱包导航标签", "钱包导航标签"), insideMargin = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp))
                Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                    ScopedSettings.WALLET_TAB_IDS.forEach { tab ->
                        val checked = tab in walletTabs
                        SwitchPreference(title = walletTabName(tab), checked = checked, enabled = enabled && (!checked || walletTabs.size > 1), onCheckedChange = { value ->
                            walletTabs = if (value) walletTabs + tab else walletTabs - tab
                            prefs.edit().putStringSet(ScopedSettings.key(appItem.packageName, ScopedSettings.KEY_VISIBLE_TABS), walletTabs).apply(); onChanged()
                        })
                    }
                }
            }
        }
    }
}

private fun walletTabName(id: String) = when (id) {
    "HOME" -> tr("首页", "首页"); "SAVINGS" -> tr("余额宝", "余额宝"); "SHORT_DRAMA" -> tr("短剧", "短剧"); "LOAN" -> tr("借钱", "借钱"); else -> tr("我的", "我的")
}
