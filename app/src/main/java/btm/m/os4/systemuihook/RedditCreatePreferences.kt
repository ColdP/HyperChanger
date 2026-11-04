package btm.m.os4.systemuihook

import android.content.SharedPreferences
import androidx.compose.runtime.*
import btm.m.liquidglass.hook.RedditCreateSettings
import io.github.libxposed.service.XposedService
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun RedditCreatePreferences(preferences: SharedPreferences, service: XposedService?, onChanged: () -> Unit) {
    var settings by remember(preferences, service) { mutableStateOf(RedditCreateSettings.load(preferences)) }
    var showColorDialog by remember { mutableStateOf(false) }
    fun save(value: RedditCreateSettings.Settings) {
        settings = value
        RedditCreateSettings.save(preferences, service, value)
        onChanged()
    }
    Group(tr("redditCreateButton", "创建按钮")) {
        SwitchPreference(
            title = tr("redditSeparateCreate", "创建按钮独立"), checked = settings.separate,
            onCheckedChange = { save(settings.copy(separate = it)); if (!it) showColorDialog = false },
        )
        PortalAppearanceVisibility(settings.separate) {
            androidx.compose.foundation.layout.Column {
                OverlayDropdownPreference(
                    title = tr("redditCreateIconColor", "独立创建按钮图标颜色"),
                    items = listOf(tr("自动", "自动"), tr("浅色", "浅色"), tr("深色", "深色")),
                    selectedIndex = RedditCreateSettings.iconModes.indexOf(settings.iconMode),
                    onSelectedIndexChange = { save(settings.copy(iconMode = RedditCreateSettings.iconModes[it])) },
                )
                OverlayDropdownPreference(
                    title = tr("redditCreateBackground", "独立创建按钮背景颜色"),
                    items = listOf(tr("xhsPublishFollowDefault", "跟随默认"), tr("自定义", "自定义")),
                    selectedIndex = RedditCreateSettings.backgroundModes.indexOf(settings.backgroundMode),
                    onSelectedIndexChange = {
                        save(settings.copy(backgroundMode = RedditCreateSettings.backgroundModes[it]))
                        if (it == 0) showColorDialog = false
                    },
                )
                PortalAppearanceVisibility(settings.backgroundMode == "custom") {
                    NavigationAccentPreference(
                        color = settings.background, enabled = true,
                        title = tr("redditCreateCustomBackground", "自定义创建按钮背景颜色"),
                        onClick = { showColorDialog = true },
                    )
                }
            }
        }
    }
    NavigationAccentDialog(
        show = showColorDialog && settings.separate && settings.backgroundMode == "custom",
        initialColor = settings.background,
        defaultColor = RedditCreateSettings.DEFAULT_BACKGROUND,
        onDismiss = { showColorDialog = false },
        onSave = { save(settings.copy(background = it)); showColorDialog = false },
        title = tr("redditCreateBackground", "独立创建按钮背景颜色"),
    )
}
