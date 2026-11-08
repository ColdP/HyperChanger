// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import io.github.libxposed.service.XposedService

/** One version contract for the settings app, presets and the camera entry point. */
internal object CameraVersionCompatibility {
    const val VERSION_NAME = "6.8.002090.2"
    const val VERSION_CODE = 680020902L

    @JvmStatic
    fun matches(name: String?, code: Long): Boolean = name == VERSION_NAME && code == VERSION_CODE

    private fun matches(info: PackageInfo?): Boolean =
        info != null && matches(info.versionName, info.longVersionCode)

    fun isSupported(context: Context): Boolean = runCatching {
        matches(context.packageManager.getPackageInfo(CameraModuleConfig.TARGET_PACKAGE, 0))
    }.getOrDefault(false)

    /** Loaded runs before currentApplication exists. Inspect the delivered APK,
     * without initializing camera classes or relying on stale remote settings. */
    @JvmStatic
    fun isSupportedApk(info: ApplicationInfo): Boolean =
        info.packageName == CameraModuleConfig.TARGET_PACKAGE && runCatching {
            val packageInfo = info.sourceDir?.let { path ->
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivityThread").invoke(null)?.let { thread ->
                        Class.forName("android.app.ActivityThread")
                            .getMethod("getSystemContext").invoke(thread) as? Context
                    }?.packageManager?.getPackageArchiveInfo(path, 0)
            }
            matches(packageInfo)
        }.getOrDefault(false)

    fun constrain(context: Context, value: CameraSettings): CameraSettings =
        if (isSupported(context)) value else value.copy(masterEnabled = false)

    /** Reuse the existing master key; compatibility is derived, never a setting. */
    fun enforce(context: Context, service: XposedService? = null) {
        if (isSupported(context)) return
        val local = context.getSharedPreferences(CameraModuleConfig.PREFERENCE_GROUP, Context.MODE_PRIVATE)
        if (local.getBoolean(CameraModuleConfig.KEY_MASTER_ENABLED, true)) {
            local.edit().putBoolean(CameraModuleConfig.KEY_MASTER_ENABLED, false).apply()
        }
        service?.getRemotePreferences(CameraModuleConfig.PREFERENCE_GROUP)?.let {
            if (it.getBoolean(CameraModuleConfig.KEY_MASTER_ENABLED, true)) {
                it.edit().putBoolean(CameraModuleConfig.KEY_MASTER_ENABLED, false).apply()
            }
        }
    }

}
