package btm.m.os4.systemuihook

import android.content.SharedPreferences
import androidx.compose.runtime.*
import btm.m.liquidglass.hook.XiaohongshuPublishSettings
import io.github.libxposed.service.XposedService
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun XiaohongshuPublishPreferences(preferences: SharedPreferences, service: XposedService?, onChanged: () -> Unit) {
    var settings by remember(preferences, service) { mutableStateOf(XiaohongshuPublishSettings.load(preferences)) }
    var showColorDialog by remember { mutableStateOf(false) }
    fun save(value: XiaohongshuPublishSettings.Settings) {
        settings = value
        XiaohongshuPublishSettings.save(preferences, service, value)
        onChanged()
    }
    Group(tr("xhsPublishButton", "发布按钮")) {
        SwitchPreference(
            title = tr("xhsSeparatePublish", "发布按钮独立"), checked = settings.separate,
            onCheckedChange = { save(settings.copy(separate = it)); if (!it) showColorDialog = false },
        )
        PortalAppearanceVisibility(settings.separate) {
            androidx.compose.foundation.layout.Column {
                OverlayDropdownPreference(
                    title = tr("xhsPublishIconColor", "独立发布按钮图标颜色"),
                    items = listOf(tr("自动", "自动"), tr("浅色", "浅色"), tr("深色", "深色")),
                    selectedIndex = XiaohongshuPublishSettings.iconModes.indexOf(settings.iconMode),
                    onSelectedIndexChange = { save(settings.copy(iconMode = XiaohongshuPublishSettings.iconModes[it])) },
                )
                OverlayDropdownPreference(
                    title = tr("xhsPublishBackground", "独立发布按钮背景颜色"),
                    items = listOf(tr("xhsPublishFollowDefault", "跟随默认"), tr("自定义", "自定义")),
                    selectedIndex = XiaohongshuPublishSettings.backgroundModes.indexOf(settings.backgroundMode),
                    onSelectedIndexChange = {
                        save(settings.copy(backgroundMode = XiaohongshuPublishSettings.backgroundModes[it]))
                        if (it == 0) showColorDialog = false
                    },
                )
                PortalAppearanceVisibility(settings.backgroundMode == "custom") {
                    NavigationAccentPreference(
                        color = settings.background, enabled = true,
                        title = tr("xhsPublishCustomBackground", "自定义发布按钮背景颜色"),
                        onClick = { showColorDialog = true },
                    )
                }
            }
        }
    }
    NavigationAccentDialog(
        show = showColorDialog && settings.separate && settings.backgroundMode == "custom",
        initialColor = settings.background,
        defaultColor = XiaohongshuPublishSettings.DEFAULT_BACKGROUND,
        onDismiss = { showColorDialog = false },
        onSave = { save(settings.copy(background = it)); showColorDialog = false },
        title = tr("xhsPublishBackground", "独立发布按钮背景颜色"),
    )
}
