package btm.m.liquidglass

import android.content.SharedPreferences

data class ScopeApp(
    val displayName: String,
    val packageName: String
)

object ScopedSettings {
    const val KEY_MODULE_ENABLED = "module_enabled"
    const val KEY_ENABLED = "enabled"
    const val KEY_SHOW_LABELS = "show_labels"
    const val KEY_LABEL_MODE = "label_mode"
    const val KEY_BLUR_RADIUS = "blur_radius"
    const val KEY_LIQUID_BLUR_RADIUS = "liquid_blur_radius"
    const val KEY_HYPER_BLUR_RADIUS = "hyper_blur_radius"
    const val KEY_ADVANCED_MATERIAL = "advanced_material"
    const val KEY_COLOR_MODE = "color_mode"
    const val KEY_VISIBLE_TABS = "visible_tabs"
    @JvmField
    val WALLET_TAB_IDS = linkedSetOf("HOME", "SAVINGS", "SHORT_DRAMA", "LOAN", "PROFILE")

    val apps = listOf(
        ScopeApp("QQ", "com.tencent.mobileqq"),
        ScopeApp("TIM", "com.tencent.tim"),
        ScopeApp("小红书", "com.xingin.xhs"),
        ScopeApp("网易云音乐", "com.netease.cloudmusic"),
        ScopeApp("QQ音乐", "com.tencent.qqmusic"),
        ScopeApp("Apple Music", "com.apple.android.music"),
        ScopeApp("Reddit", "com.reddit.frontpage"),
        ScopeApp("微博", "com.sina.weibo"),
        ScopeApp("小米商城", "com.xiaomi.shop"),
        ScopeApp("小米钱包", "com.mipay.wallet")
    )

    @JvmStatic
    fun key(packageName: String, setting: String): String = "scope.$packageName.$setting"

    @JvmStatic
    fun getBoolean(preferences: SharedPreferences?, packageName: String, setting: String, defaultValue: Boolean): Boolean {
        if (preferences == null) return defaultValue
        val scopedKey = key(packageName, setting)
        return if (preferences.contains(scopedKey)) preferences.getBoolean(scopedKey, defaultValue)
        else defaultValue
    }

    @JvmStatic
    fun getInt(preferences: SharedPreferences?, packageName: String, setting: String, defaultValue: Int): Int {
        if (preferences == null) return defaultValue
        val scopedKey = key(packageName, setting)
        return if (preferences.contains(scopedKey)) preferences.getInt(scopedKey, defaultValue)
        else defaultValue
    }

    @JvmStatic
    fun getBlurRadius(
        preferences: SharedPreferences?,
        packageName: String,
        navigationStyle: String,
        defaultValue: Int = 18
    ): Int {
        val style = NavigationStyle.fromPreference(navigationStyle)
        val styleKey = if (style == NavigationStyle.LIQUID_GLASS) {
            KEY_LIQUID_BLUR_RADIUS
        } else {
            KEY_HYPER_BLUR_RADIUS
        }
        val styleDefault = if (style == NavigationStyle.LIQUID_GLASS) 3 else defaultValue
        val legacyValue = getInt(preferences, packageName, KEY_BLUR_RADIUS, styleDefault)
        return getInt(preferences, packageName, styleKey, legacyValue)
    }

    @JvmStatic
    fun getString(preferences: SharedPreferences?, packageName: String, setting: String, defaultValue: String): String {
        if (preferences == null) return defaultValue
        val scopedKey = key(packageName, setting)
        return if (preferences.contains(scopedKey)) preferences.getString(scopedKey, defaultValue) ?: defaultValue
        else defaultValue
    }

    @JvmStatic
    fun getStringSet(
        preferences: SharedPreferences?,
        packageName: String,
        setting: String,
        defaultValue: Set<String>
    ): Set<String> {
        if (preferences == null) return defaultValue
        val scopedKey = key(packageName, setting)
        return if (preferences.contains(scopedKey)) {
            preferences.getStringSet(scopedKey, defaultValue)?.toSet() ?: defaultValue
        } else {
            defaultValue
        }
    }

    @JvmStatic
    fun getWalletVisibleTabs(preferences: SharedPreferences?): Set<String> =
        getStringSet(preferences, "com.mipay.wallet", KEY_VISIBLE_TABS, WALLET_TAB_IDS)
            .intersect(WALLET_TAB_IDS)
            .ifEmpty { WALLET_TAB_IDS }

    @JvmStatic
    fun getLabelMode(
        preferences: SharedPreferences?,
        packageName: String,
        defaultValue: String = LabelMode.DEFAULT_VALUE
    ): String {
        if (preferences == null) return defaultValue
        val scoped = preferences.all[key(packageName, KEY_LABEL_MODE)]
        if (scoped is String) return LabelMode.fromPreference(scoped).preferenceValue
        // Migrate the old boolean setting: true meant icon + text, false meant icon only.
        val oldScoped = preferences.all[key(packageName, KEY_SHOW_LABELS)]
        if (oldScoped is Boolean) return if (oldScoped) LabelMode.ICON_AND_TEXT.preferenceValue else LabelMode.ICON_ONLY.preferenceValue
        return defaultValue
    }
}
