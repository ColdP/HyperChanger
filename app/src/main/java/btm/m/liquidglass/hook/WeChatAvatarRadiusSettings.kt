package btm.m.liquidglass.hook

import android.content.SharedPreferences
import btm.m.liquidglass.ScopedSettings

/** Separate per-app preference for WeChat avatar corner tuning. */
object WeChatAvatarRadiusSettings {
    const val KEY = "avatar_radius_offset"
    const val DEFAULT = 0
    const val MIN = -64
    const val MAX = 96

    @JvmStatic
    fun getOffset(preferences: SharedPreferences?, packageName: String = WeChatNavigation.PACKAGE): Int =
        ScopedSettings.getInt(preferences, packageName, KEY, DEFAULT).coerceIn(MIN, MAX)

    @JvmStatic
    fun saveOffset(preferences: SharedPreferences, packageName: String, value: Int) {
        preferences.edit().putInt(ScopedSettings.key(packageName, KEY), value.coerceIn(MIN, MAX)).apply()
    }

    /** Maps -64..96 to a bitmap radius factor, with zero preserving WeChat's 0.5 default. */
    @JvmStatic
    fun radiusFactor(offset: Int): Float = if (offset <= 0) {
        .5f * (offset - MIN) / (0 - MIN)
    } else {
        .5f + .5f * offset / MAX
    }.coerceIn(0f, 1f)
}
