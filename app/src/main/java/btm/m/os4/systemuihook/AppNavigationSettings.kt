package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import btm.m.liquidglass.AppColorMode
import btm.m.liquidglass.NavigationAccentSettings
import btm.m.liquidglass.HyperFloatingAccentSettings
import btm.m.liquidglass.HyperDockedNavigationSettings
import btm.m.liquidglass.NavigationMaterialSettings
import btm.m.liquidglass.NavigationBadgeSettings
import btm.m.liquidglass.NavigationStyle
import btm.m.liquidglass.NavigationOffsetSettings
import btm.m.liquidglass.NavigationScaleSettings
import btm.m.liquidglass.NavigationCornerSettings
import btm.m.liquidglass.ScopedSettings
import btm.m.liquidglass.hook.WeChatAvatarRadiusSettings
import btm.m.liquidglass.hook.XiaohongshuPublishSettings
import btm.m.liquidglass.hook.RedditCreateSettings
import io.github.libxposed.service.XposedService
import org.json.JSONArray
import org.json.JSONObject

/** Local persistence for the navigation settings previously written only to the framework. */
internal class AppNavigationSettingsStore internal constructor(val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE))

    fun edit(service: XposedService?, update: SharedPreferences.Editor.() -> Unit) {
        preferences.edit().apply(update).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.apply(update)?.apply()
    }

    fun syncRemote(service: XposedService) = syncPreferences(service.getRemotePreferences(REMOTE_PREFERENCE_GROUP))

    internal fun syncPreferences(remote: SharedPreferences) {
        val localValues = preferences.all.filterKeys { isAppNavigationPreference(it) && !NavigationCornerSettings.isPreference(it) && !NavigationScaleSettings.isPreference(it) && !NavigationOffsetSettings.isPreference(it) && !XiaohongshuPublishSettings.isPreference(it) && !RedditCreateSettings.isPreference(it) && !HyperFloatingAccentSettings.isPreference(it) && !NavigationMaterialSettings.isPreference(it) && !HyperDockedNavigationSettings.isPreference(it) }
        val remoteValues = remote.all.filterKeys { isAppNavigationPreference(it) && !NavigationCornerSettings.isPreference(it) && !NavigationScaleSettings.isPreference(it) && !NavigationOffsetSettings.isPreference(it) && !XiaohongshuPublishSettings.isPreference(it) && !RedditCreateSettings.isPreference(it) && !HyperFloatingAccentSettings.isPreference(it) && !NavigationMaterialSettings.isPreference(it) && !HyperDockedNavigationSettings.isPreference(it) }
        // Recover installations made by older versions; subsequent page edits save both copies.
        val merged = remoteValues + localValues
        writeValues(preferences, merged)
        writeValues(remote, merged)
        NavigationOffsetSettings.syncPreferences(preferences, remote)
        NavigationScaleSettings.syncPreferences(preferences, remote)
        NavigationCornerSettings.syncPreferences(preferences, remote)
        XiaohongshuPublishSettings.syncPreferences(preferences, remote)
        RedditCreateSettings.syncPreferences(preferences, remote)
        HyperFloatingAccentSettings.syncPreferences(preferences, remote)
        NavigationMaterialSettings.syncPreferences(preferences, remote)
        HyperDockedNavigationSettings.syncPreferences(preferences, remote)
    }

    private fun writeValues(target: SharedPreferences, values: Map<String, *>) {
        val editor = target.edit()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putFrameworkStringSet(key, value.filterIsInstance<String>())
            }
        }
        editor.apply()
    }
}

private val appNavigationSettingNames = setOf(
    ScopedSettings.KEY_MODULE_ENABLED,
    ScopedSettings.KEY_ENABLED,
    NavigationStyle.PREFERENCE_KEY,
    ScopedSettings.KEY_LABEL_MODE,
    ScopedSettings.KEY_SHOW_LABELS,
    ScopedSettings.KEY_COLOR_MODE,
    ScopedSettings.KEY_BLUR_RADIUS,
    ScopedSettings.KEY_LIQUID_BLUR_RADIUS,
    ScopedSettings.KEY_HYPER_BLUR_RADIUS,
    ScopedSettings.KEY_ADVANCED_MATERIAL,
    NavigationAccentSettings.KEY,
    NavigationBadgeSettings.KEY_SHOW_BADGES,
)

internal fun isAppNavigationPreference(key: String): Boolean =
    NavigationCornerSettings.isPreference(key) ||
    NavigationScaleSettings.isPreference(key) ||
    HyperDockedNavigationSettings.isPreference(key) ||
    NavigationMaterialSettings.isPreference(key) ||
    HyperFloatingAccentSettings.isPreference(key) ||
    XiaohongshuPublishSettings.isPreference(key) ||
    RedditCreateSettings.isPreference(key) ||
    NavigationOffsetSettings.isPreference(key) ||
        key.startsWith("scope.") && key.substringAfterLast('.') in appNavigationSettingNames ||
        key == ScopedSettings.key("com.mipay.wallet", ScopedSettings.KEY_VISIBLE_TABS) ||
        key == ScopedSettings.key("com.tencent.mm", WeChatAvatarRadiusSettings.KEY)

/** Export defaults explicitly so a new preset can also restore a default over a custom value. */
internal fun completeAppNavigationPreset(values: JSONObject, preferences: SharedPreferences) {
    NavigationOffsetSettings.completePreset(values)
    NavigationScaleSettings.completePreset(values)
    NavigationCornerSettings.completePreset(values)
    XiaohongshuPublishSettings.completePreset(values)
    RedditCreateSettings.completePreset(values)
    HyperFloatingAccentSettings.completePreset(values)
    NavigationMaterialSettings.completePreset(values)
    HyperDockedNavigationSettings.completePreset(values)
    fun put(packageName: String, setting: String, type: String, value: Any) {
        val key = ScopedSettings.key(packageName, setting)
        if (!values.has(key)) values.put(key, JSONObject().put("type", type).put("value", value))
    }
    ScopedSettings.apps.forEach { app ->
        val pkg = app.packageName
        put(pkg, ScopedSettings.KEY_MODULE_ENABLED, "boolean", true)
        put(pkg, ScopedSettings.KEY_ENABLED, "boolean", true)
        put(pkg, NavigationStyle.PREFERENCE_KEY, "string", NavigationStyle.DEFAULT_VALUE)
        put(pkg, ScopedSettings.KEY_LABEL_MODE, "string", ScopedSettings.getLabelMode(preferences, pkg))
        put(pkg, ScopedSettings.KEY_COLOR_MODE, "string", AppColorMode.DEFAULT_VALUE)
        put(pkg, ScopedSettings.KEY_ADVANCED_MATERIAL, "boolean", true)
        put(pkg, ScopedSettings.KEY_LIQUID_BLUR_RADIUS, "int",
            ScopedSettings.getBlurRadius(preferences, pkg, NavigationStyle.LIQUID_GLASS.preferenceValue))
        put(pkg, ScopedSettings.KEY_HYPER_BLUR_RADIUS, "int",
            ScopedSettings.getBlurRadius(preferences, pkg, NavigationStyle.HYPER_OS.preferenceValue))
        put(pkg, NavigationAccentSettings.KEY, "int", NavigationAccentSettings.defaultColor(pkg))
        if (pkg == "com.tencent.mobileqq" || pkg == "com.tencent.mm") {
            put(pkg, NavigationBadgeSettings.KEY_SHOW_BADGES, "boolean", NavigationBadgeSettings.DEFAULT)
        }
    }
    put("com.mipay.wallet", ScopedSettings.KEY_VISIBLE_TABS, "string_set", JSONArray(ScopedSettings.WALLET_TAB_IDS.toList()))
    put("com.tencent.mm", WeChatAvatarRadiusSettings.KEY, "int", WeChatAvatarRadiusSettings.DEFAULT)
}
