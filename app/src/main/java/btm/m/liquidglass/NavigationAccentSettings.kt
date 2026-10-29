package btm.m.liquidglass

import android.content.SharedPreferences

/** Per-application accent colors used by the custom floating navigation bars. */
object NavigationAccentSettings {
    const val KEY = "accent_color"

    private val defaults = mapOf(
        "com.apple.android.music" to 0xFF0088FF.toInt(),
        "com.sina.weibo" to 0xFFFF9933.toInt(),
        "com.xingin.xhs" to 0xFFFF2442.toInt(),
        "com.xiaomi.shop" to 0xFFFF6900.toInt(),
        "com.mipay.wallet" to 0xFF0088FF.toInt(),
        "com.mi.health" to 0xFFFF6900.toInt(),
        "com.lucky.luckyclient" to 0xFF182D71.toInt(),
        "com.tencent.wemeet.app" to 0xFF0052D9.toInt(),
        "cmb.pb" to 0xFFD11A2D.toInt(),
        "com.tencent.mm" to 0xFF07C160.toInt(),
        "com.tencent.mobileqq" to 0xFF12B7F5.toInt(),
        "com.xunmeng.pinduoduo" to 0xFFE02E24.toInt(),
        "com.instagram.android" to 0xFFE1306C.toInt(),
        "com.twitter.android" to 0xFF1D9BF0.toInt(),
    )

    @JvmStatic
    fun defaultColor(packageName: String): Int = defaults[packageName] ?: 0xFF0088FF.toInt()

    @JvmStatic
    fun getColor(preferences: SharedPreferences?, packageName: String): Int {
        val fallback = defaultColor(packageName)
        return ScopedSettings.getInt(preferences, packageName, KEY, fallback) or 0xFF000000.toInt()
    }

    @JvmStatic
    fun saveColor(preferences: SharedPreferences, packageName: String, color: Int) {
        preferences.edit().putInt(ScopedSettings.key(packageName, KEY), color or 0xFF000000.toInt()).apply()
    }

    @JvmStatic
    fun resetColor(preferences: SharedPreferences, packageName: String) {
        preferences.edit().remove(ScopedSettings.key(packageName, KEY)).apply()
    }
}
