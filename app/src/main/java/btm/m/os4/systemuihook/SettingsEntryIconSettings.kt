// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import io.github.libxposed.service.XposedService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal const val KEY_SETTINGS_ENTRY_ICONS_REVISION = "settings_entry_icons_revision"
internal const val SETTINGS_ENTRY_ICON_AUTHORITY = "btm.m.os4.systemuihook.settingsentryicons"
internal const val SETTINGS_ENTRY_ICON_FILE = "settings_entry_icons.zip"
private const val KEY_ICON_SOURCE_NAME = "settings_entry_icons_source_name"

internal data class SettingsEntryIconEntry(
    val id: String,
    val title: String,
    val resources: List<String>,
    val headerIds: List<String> = listOf(id),
)

// Resource names, rather than numeric resource IDs, survive Settings updates.
internal val settingsEntryIconGroups = listOf(
    listOf(
        SettingsEntryIconEntry("my_device", "我的设备", listOf("ic_my_device")),
        SettingsEntryIconEntry("sub_screen", "妙享背屏", listOf("ic_sub_screen", "ic_rear_display")),
        SettingsEntryIconEntry("fold_screen_settings", "折叠屏专区", listOf("ic_fold_screen_settings")),
        SettingsEntryIconEntry("tablet_screen_settings", "平板专区", listOf("ic_tablet_screen_settings")),
    ),
    listOf(
        SettingsEntryIconEntry("wifi_settings", "WLAN", listOf("ic_wifi_settings")),
        SettingsEntryIconEntry("bluetooth_settings", "蓝牙", listOf("ic_bluetooth_settings")),
        SettingsEntryIconEntry("mobile_network_settings", "移动网络", listOf("ic_settings_dualsim"), listOf("mobile_network_settings", "msim_settings")),
        SettingsEntryIconEntry("interconnection_settings", "设备互联", listOf("ic_interconnection")),
        SettingsEntryIconEntry("wifi_tether_settings", "个人热点", listOf("ic_portable_wlan_hotspot_settings")),
        SettingsEntryIconEntry("vpn_settings", "VPN", listOf("ic_vpn")),
        SettingsEntryIconEntry("wireless_settings", "更多连接", listOf("ic_device_connection", "ic_settings_wireless")),
    ),
    listOf(SettingsEntryIconEntry("voice_assist", "小米澎湃AI", listOf("ic_hyper_ai", "xiaoai"), listOf("voice_assist", "xiao_mi_hyperos_ai"))),
    listOf(
        SettingsEntryIconEntry("wallpaper_settings", "系统个性化", listOf("com_android_thememanager", "ic_wallpaper_settings"), listOf("personalize_title", "wallpaper_settings")),
        SettingsEntryIconEntry("unlock_set_settings", "锁屏", listOf("ic_unlock_set_settings")),
        SettingsEntryIconEntry("notification_settings", "通知与状态栏", listOf("ic_notification_center"), listOf("notification_status_bar", "notification_settings", "notification_status_bar_settings")),
        SettingsEntryIconEntry("launcher_settings", "桌面", listOf("ic_miui_home_settings")),
        SettingsEntryIconEntry("screen_settings", "显示与亮度", listOf("ic_display_settings")),
        SettingsEntryIconEntry("sound_settings", "声音与触感", listOf("ic_sound_settings")),
    ),
    listOf(
        SettingsEntryIconEntry("security_settings", "指纹、面部与密码", listOf("ic_security_settings_settings")),
        SettingsEntryIconEntry("privacy_protection_settings", "隐私与安全", listOf("ic_privacy_protection", "ic_security_center_settings"), listOf("privacy_protection_settings", "privacy_settings2")),
        SettingsEntryIconEntry("application_settings", "应用设置", listOf("ic_system_app_settings")),
        SettingsEntryIconEntry("battery_settings_new", "省电与电池", listOf("ic_battery_settings")),
    ),
    listOf(
        SettingsEntryIconEntry("app_timer", "健康使用手机", listOf("healthy_use_device_icon", "ic_app_timer", "ic_google_wellbeing")),
        SettingsEntryIconEntry("minors_control", "未成年人模式", listOf("ic_minors_control")),
        SettingsEntryIconEntry("accessibility_function", "辅助功能", listOf("ic_accessibility_function", "ic_accessibility_generic")),
        SettingsEntryIconEntry("header_google_settings", "Google 服务", listOf("ic_google_settings")),
        SettingsEntryIconEntry("other_advanced_settings", "更多设置", listOf("ic_other_advanced_settings")),
        SettingsEntryIconEntry("bugreport_settings", "问题反馈", listOf("ic_bugreport_settings"), listOf("feedback_services_settings", "bugreport_settings")),
    ),
)
internal val settingsEntryIconCatalog = settingsEntryIconGroups.flatten()

internal data class SettingsEntryIconPack(val name: String, val icons: Map<String, Bitmap>)

internal fun readSettingsEntryIconRevision(prefs: SharedPreferences): Long =
    prefs.getLong(KEY_SETTINGS_ENTRY_ICONS_REVISION, 0L)

internal class SettingsEntryIconStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    private val archive = AtomicFile(File(app.filesDir, SETTINGS_ENTRY_ICON_FILE))

    fun load(): SettingsEntryIconPack = runCatching {
        archive.openRead().use { readSettingsEntryIconArchive(it) }
            .let { SettingsEntryIconPack(prefs.getString(KEY_ICON_SOURCE_NAME, "").orEmpty(), it) }
    }.getOrDefault(SettingsEntryIconPack("", emptyMap()))

    fun read(uri: Uri): SettingsEntryIconPack {
        val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }.orEmpty()
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.iconBytes(128 * 1024 * 1024) }
            ?: error("Cannot open icon archive")
        val candidates = mutableMapOf<String, Pair<Int, ByteArray>>()
        val budget = longArrayOf(256L * 1024 * 1024)
        val count = intArrayOf(0)
        fun scan(source: ByteArray, depth: Int) {
            require(depth <= 3) { "Archive nesting too deep" }
            ZipInputStream(source.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++count[0] <= 4096) { "Too many archive entries" }
                    val path = entry.name.replace('\\', '/').trimStart('/')
                    val nested = path.substringAfterLast('/') == "com.android.settings" || path.endsWith(".zip", true)
                    val relative = path.removePrefix("com.android.settings/")
                    val night = relative.startsWith("nightmode/")
                    val normal = relative.removePrefix("nightmode/")
                    val folder = normal.substringBeforeLast('/', "")
                    val resource = normal.substringAfterLast('/').substringBeforeLast('.')
                    val catalogEntry = settingsEntryIconCatalog.firstOrNull { resource in it.resources }
                    val supported = folder.matches(Regex("res/drawable(?:-[a-zA-Z0-9-]+)?")) &&
                        normal.substringAfterLast('.').lowercase() in setOf("png", "webp", "jpg", "jpeg") && catalogEntry != null
                    val output = if (nested || supported) ByteArrayOutputStream() else null
                    val buffer = ByteArray(8192)
                    var size = 0
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        size += n
                        budget[0] -= n
                        require(budget[0] >= 0 && (output == null || size <= if (nested) 96 * 1024 * 1024 else 8 * 1024 * 1024)) { "Archive entry too large" }
                        output?.write(buffer, 0, n)
                    }
                    if (nested) scan(requireNotNull(output).toByteArray(), depth + 1)
                    else if (supported) {
                        val item = requireNotNull(catalogEntry)
                        val density = listOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi").indexOf(folder.substringAfterLast('-')) + 1
                        // Any ordinary asset outranks every nightmode asset. No day/night split is stored.
                        val score = (if (night) 0 else 1000) + (item.resources.size - item.resources.indexOf(resource)) * 10 + density
                        if (score > (candidates[item.id]?.first ?: -1)) candidates[item.id] = score to requireNotNull(output).toByteArray()
                    }
                }
            }
        }
        scan(bytes, 0)
        val icons = candidates.mapNotNull { (id, value) -> decodeSettingsEntryIcon(value.second)?.let { id to it } }.toMap()
        require(icons.isNotEmpty()) { "No supported Settings icons" }
        return SettingsEntryIconPack(name, icons)
    }

    fun readImage(uri: Uri): Bitmap = app.contentResolver.openInputStream(uri)?.use {
        decodeSettingsEntryIcon(it.iconBytes(16 * 1024 * 1024))
    } ?: error("Unsupported image")

    @Synchronized
    fun save(pack: SettingsEntryIconPack, service: XposedService?) {
        // Atomic archive swap happens before publishing its revision. Empty ZIP is the saved reset.
        val output = archive.startWrite()
        try {
            ZipOutputStream(output).use { zip ->
                pack.icons.forEach { (id, bitmap) ->
                    require(settingsEntryIconCatalog.any { it.id == id })
                    zip.putNextEntry(ZipEntry("$id.png"))
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                    zip.closeEntry()
                }
                zip.finish()
                // AtomicFile must fsync the still-open underlying stream.
                archive.finishWrite(output)
            }
        } catch (error: Throwable) {
            archive.failWrite(output)
            throw error
        }
        val revision = maxOf(System.currentTimeMillis(), readSettingsEntryIconRevision(prefs) + 1)
        prefs.edit().putString(KEY_ICON_SOURCE_NAME, pack.name).putLong(KEY_SETTINGS_ENTRY_ICONS_REVISION, revision).commit()
        syncRemote(service)
    }

    fun syncRemote(service: XposedService?) {
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()
            ?.putLong(KEY_SETTINGS_ENTRY_ICONS_REVISION, readSettingsEntryIconRevision(prefs))?.apply()
    }
}

private fun InputStream.iconBytes(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        require(output.size() + n <= limit) { "File too large" }
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}

internal fun decodeSettingsEntryIcon(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..16384 || bounds.outHeight !in 1..16384) return null
    val options = BitmapFactory.Options().apply {
        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize.coerceAtLeast(1) > 512) {
            inSampleSize = inSampleSize.coerceAtLeast(1) * 2
        }
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    val maxSide = maxOf(bitmap.width, bitmap.height)
    if (maxSide <= 256) return bitmap
    return Bitmap.createScaledBitmap(bitmap, (bitmap.width * 256 / maxSide).coerceAtLeast(1), (bitmap.height * 256 / maxSide).coerceAtLeast(1), true)
        .also { if (it !== bitmap) bitmap.recycle() }
}

internal fun readSettingsEntryIconArchive(input: InputStream): Map<String, Bitmap> {
    val icons = mutableMapOf<String, Bitmap>()
    ZipInputStream(input).use { zip ->
        repeat(settingsEntryIconCatalog.size + 1) {
            val entry = zip.nextEntry ?: return icons
            val id = entry.name.removeSuffix(".png")
            require(settingsEntryIconCatalog.any { it.id == id })
            decodeSettingsEntryIcon(zip.iconBytes(2 * 1024 * 1024))?.let { icons[id] = it }
        }
    }
    return icons
}
