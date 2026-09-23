// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

/** Centralized HyperOS version gate shared by the app and every Xposed entry. */
object OsCompatibility {
    private const val PROPERTY = "ro.mi.os.version.code"

    @JvmStatic
    fun systemProperty(key: String): String = runCatching {
        val type = Class.forName("android.os.SystemProperties")
        type.getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    }.getOrDefault("").trim()

    @JvmStatic
    fun versionCode(): String = systemProperty(PROPERTY)

    @JvmStatic
    fun areHooksAllowed(): Boolean = versionCode().isNotEmpty()

    @JvmStatic
    fun isHyperOs4(): Boolean = versionCode() == "4"
}
