// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

private const val SAFETY_PREFIX = "module_safety_v1_"
internal const val KEY_MODULE_ENABLED = SAFETY_PREFIX + "enabled"
internal const val KEY_SYSTEM_UI_SAFE = SAFETY_PREFIX + "system_ui"
internal const val KEY_PARTIAL_SAFE = SAFETY_PREFIX + "partial"
internal const val KEY_SAFE_SECTIONS = SAFETY_PREFIX + "sections"
internal const val KEY_LOG_ENABLED = SAFETY_PREFIX + "logging"
internal const val KEY_LOG_DETAIL = SAFETY_PREFIX + "detail"
internal const val KEY_LOG_RETENTION = SAFETY_PREFIX + "retention"

enum class SafetySection(val title: String) {
    FRAMEWORK("系统框架"), GLASS("柔光玻璃参数调整"), DESKTOP("桌面"),
    STATUS_BAR("状态栏"), CONTROL_CENTER("控制中心"), LOCKSCREEN("锁屏"),
    NOTIFICATIONS("通知相关"), ISLAND("超级岛相关"), SETTINGS("设置"), NAVIGATION("底部导航栏"), GESTURE_HANDLE("全局手势提示线"),
}

internal data class ModuleSafetySettings(
    val enabled: Boolean = true,
    val systemUiSafe: Boolean = false,
    val partialSafe: Boolean = false,
    val sections: Set<SafetySection> = emptySet(),
    val logEnabled: Boolean = true,
    val detailedLogs: Boolean = false,
    val retentionDays: Int = 7,
) {
    fun allows(section: SafetySection) = enabled && !(partialSafe && section in sections)
    fun allowsPackage(packageName: String): Boolean = enabled && when (packageName) {
        "system" -> allows(SafetySection.FRAMEWORK)
        "com.android.systemui", "miui.systemui.plugin" -> !systemUiSafe
        "com.miui.home" -> allows(SafetySection.DESKTOP)
        "com.android.settings", "com.xiaomi.misettings" -> allows(SafetySection.SETTINGS)
        "com.miui.aod", "com.miui.miwallpaper" -> allows(SafetySection.LOCKSCREEN)
        else -> true
    }
}

internal fun readModuleSafety(p: SharedPreferences) = ModuleSafetySettings(
    enabled = p.getBoolean(KEY_MODULE_ENABLED, true),
    systemUiSafe = p.getBoolean(KEY_SYSTEM_UI_SAFE, false),
    partialSafe = p.getBoolean(KEY_PARTIAL_SAFE, false),
    sections = p.getStringSet(KEY_SAFE_SECTIONS, emptySet()).orEmpty().mapNotNull { key ->
        SafetySection.entries.firstOrNull { it.name == key }
    }.toSet(),
    logEnabled = p.getBoolean(KEY_LOG_ENABLED, true),
    detailedLogs = p.getBoolean(KEY_LOG_DETAIL, false),
    retentionDays = p.getInt(KEY_LOG_RETENTION, 7).takeIf { it in listOf(3, 7, 14, 30, 120) } ?: 7,
)

internal class ModuleSafetySettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings = readModuleSafety(local); private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = readModuleSafety(if (local.contains(KEY_MODULE_ENABLED)) local else remote)
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (ModuleSafetySettings) -> ModuleSafetySettings) {
        settings = transform(readModuleSafety(local))
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, s: ModuleSafetySettings) {
        p.edit().putBoolean(KEY_MODULE_ENABLED, s.enabled)
            .putBoolean(KEY_SYSTEM_UI_SAFE, s.systemUiSafe)
            .putBoolean(KEY_PARTIAL_SAFE, s.partialSafe)
            .putStringSet(KEY_SAFE_SECTIONS, s.sections.map { it.name }.toSet())
            .putBoolean(KEY_LOG_ENABLED, s.logEnabled).putBoolean(KEY_LOG_DETAIL, s.detailedLogs)
            .putInt(KEY_LOG_RETENTION, s.retentionDays).apply()
    }
}

/** One immutable installation policy per process. Changes require process/device restart. */
object ModuleSafetyRuntime {
    @Volatile private var policy: ModuleSafetySettings? = null

    @JvmStatic
    fun initialize(prefs: SharedPreferences, packageName: String): Boolean {
        synchronized(this) { if (policy == null) policy = readModuleSafety(prefs) }
        return policy!!.allowsPackage(packageName)
    }

    @JvmStatic
    fun allows(section: SafetySection): Boolean = policy?.allows(section) ?: true

    @JvmStatic
    fun allowsSharedShade(): Boolean = allows(SafetySection.GLASS) &&
        allows(SafetySection.NOTIFICATIONS) && allows(SafetySection.CONTROL_CENTER)
}

/** Flag byte consumed in the launcher, before feature patch registration. */
internal fun moduleSafetyNativeFlags(p: SharedPreferences): Int {
    val s = readModuleSafety(p)
    return (if (s.allows(SafetySection.DESKTOP)) 0 else 1) or
        (if (s.logEnabled) 0 else 2) or (if (s.detailedLogs) 4 else 0)
}
