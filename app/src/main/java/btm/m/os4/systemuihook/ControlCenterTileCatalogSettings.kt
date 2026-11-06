// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import org.json.JSONObject

internal const val KEY_CC_TILE_SIZE_MEMORY = "control_center_tile_size_memory_v1"

/** Separate persistence for sizes retained when a tile leaves the upper panel. */
internal object CcTileSizeMemory {
    fun valid(sizes: Map<String, Pair<Int, Int>>) = sizes.all { (id, size) ->
        id in CcTileCatalog.ids && id != "brightness" && id != "volume" &&
            size.first in 1..4 && size.second in 1..if (id == "media") 3 else 2
    }
    fun read(p: SharedPreferences): Map<String, Pair<Int, Int>> = runCatching {
        val json = JSONObject(p.getString(KEY_CC_TILE_SIZE_MEMORY, "{}") ?: "{}")
        json.keys().asSequence().associateWith { id ->
            json.getJSONArray(id).let { it.getInt(0) to it.getInt(1) }
        }.also { require(valid(it)) }
    }.getOrDefault(emptyMap())
    fun write(p: SharedPreferences, sizes: Map<String, Pair<Int, Int>>) {
        val json = JSONObject()
        sizes.forEach { (id, size) -> json.put(id, org.json.JSONArray(listOf(size.first, size.second))) }
        p.edit().putString(KEY_CC_TILE_SIZE_MEMORY, json.toString()).apply()
    }
    fun completePreset(values: JSONObject) {
        if (!values.has(KEY_CC_TILE_SIZE_MEMORY)) values.put(KEY_CC_TILE_SIZE_MEMORY,
            JSONObject().put("type", "string").put("value", "{}"))
    }
}

internal data class CcCatalogTile(val id: String, val zh: String, val spec: String? = id) {
    fun title() = tr("cc_tile_$id", zh)
}

internal object CcTileCatalog {
    val entries = listOf(
        CcCatalogTile("wlan", "WLAN", "wifi"),
        CcCatalogTile("data", "移动数据", "cell"),
        CcCatalogTile("media", "媒体播放", null),
        CcCatalogTile("brightness", "亮度", null),
        CcCatalogTile("volume", "音量", null),
        CcCatalogTile("bt", "蓝牙"),
        CcCatalogTile("location", "位置信息"),
        CcCatalogTile("nfc", "NFC"),
        CcCatalogTile("hotspot", "热点"),
        CcCatalogTile("flashlight", "手电筒"),
        CcCatalogTile("settings", "设置"),
        CcCatalogTile("airplane", "飞行模式"),
        CcCatalogTile("autobrightness", "自动亮度"),
        CcCatalogTile("misharetile", "小米互传"),
        CcCatalogTile("rotation", "方向锁定"),
        CcCatalogTile("gps", "定位服务"),
        CcCatalogTile("quietmode", "勿扰模式"),
        CcCatalogTile("night", "深色模式"),
        CcCatalogTile("mute", "静音"),
        CcCatalogTile("screenrecordertile", "屏幕录制"),
        CcCatalogTile("dolbyatomssound", "杜比全景声"),
        CcCatalogTile("custom_5G", "5G"),
        CcCatalogTile("voicetrans", "对话翻译"),
        CcCatalogTile("invisible", "隐身模式", null),
        CcCatalogTile("aisubtitles", "实时字幕"),
        CcCatalogTile("wirelesspower", "无线反向充电"),
        CcCatalogTile("performance", "性能模式", null),
        CcCatalogTile("calculator", "计算器", null),
        CcCatalogTile("aitranslate", "翻译"),
        CcCatalogTile("screenlock", "锁屏"),
        CcCatalogTile("screenshot", "截屏"),
        CcCatalogTile("papermode", "护眼模式"),
        CcCatalogTile("milinkcast", "投屏"),
        CcCatalogTile("vibrate", "振动"),
        CcCatalogTile("carsickness", "晕车缓解"),
        CcCatalogTile("search", "搜索", null),
        CcCatalogTile("taskmanager", "运行中的应用"),
        CcCatalogTile("freeformhang", "迷你小窗"),
        CcCatalogTile("carwith", "CarWith", null),
        CcCatalogTile("scanner", "扫一扫"),
        CcCatalogTile("superpower", "超级省电", null),
        CcCatalogTile("custom_GMS", "Google 服务"),
    )
    val ids = entries.map { it.id }.toSet()
    /** Standalone vectors extracted from the audited OEM APKs; app services keep their own icons. */
    fun previewIcons(context: Context): Map<String, Drawable?> = mapOf(
        "bt" to context.getDrawable(R.drawable.hc_cc_preview_bt),
        "location" to context.getDrawable(R.drawable.hc_cc_preview_location),
        "gps" to context.getDrawable(R.drawable.hc_cc_preview_gps),
        "nfc" to context.getDrawable(R.drawable.hc_cc_preview_nfc),
        "hotspot" to context.getDrawable(R.drawable.hc_cc_preview_hotspot),
        "flashlight" to context.getDrawable(R.drawable.hc_cc_preview_flashlight),
        "settings" to context.getDrawable(R.drawable.hc_cc_preview_settings),
        "airplane" to context.getDrawable(R.drawable.hc_cc_preview_airplane),
        "autobrightness" to context.getDrawable(R.drawable.hc_cc_preview_autobrightness),
        "misharetile" to context.getDrawable(R.drawable.hc_cc_preview_misharetile),
        "rotation" to context.getDrawable(R.drawable.hc_cc_preview_rotation),
        "quietmode" to context.getDrawable(R.drawable.hc_cc_preview_quietmode),
        "night" to context.getDrawable(R.drawable.hc_cc_preview_night),
        "mute" to context.getDrawable(R.drawable.hc_cc_preview_mute),
        "screenrecordertile" to context.getDrawable(R.drawable.hc_cc_preview_screenrecordertile),
        "dolbyatomssound" to context.getDrawable(R.drawable.hc_cc_preview_dolbyatomssound),
        "voicetrans" to context.getDrawable(R.drawable.hc_cc_preview_voicetrans),
        "aisubtitles" to context.getDrawable(R.drawable.hc_cc_preview_aisubtitles),
        "wirelesspower" to context.getDrawable(R.drawable.hc_cc_preview_wirelesspower),
        "aitranslate" to context.getDrawable(R.drawable.hc_cc_preview_aitranslate),
        "screenlock" to context.getDrawable(R.drawable.hc_cc_preview_screenlock),
        "screenshot" to context.getDrawable(R.drawable.hc_cc_preview_screenshot),
        "papermode" to context.getDrawable(R.drawable.hc_cc_preview_papermode),
        "milinkcast" to context.getDrawable(R.drawable.hc_cc_preview_milinkcast),
        "vibrate" to context.getDrawable(R.drawable.hc_cc_preview_vibrate),
        "carsickness" to context.getDrawable(R.drawable.hc_cc_preview_carsickness),
        "taskmanager" to context.getDrawable(R.drawable.hc_cc_preview_taskmanager),
        "freeformhang" to context.getDrawable(R.drawable.hc_cc_preview_freeformhang),
        "scanner" to context.getDrawable(R.drawable.hc_cc_preview_scanner),
        "wlan" to context.getDrawable(R.drawable.hc_cc_preview_wlan),
        "data" to context.getDrawable(R.drawable.hc_cc_preview_data),
        "media" to context.getDrawable(R.drawable.hc_cc_preview_media),
        "search" to context.getDrawable(R.drawable.hc_cc_preview_search),
        "carwith" to context.getDrawable(R.drawable.hc_cc_preview_carwith),
        "invisible" to context.getDrawable(R.drawable.hc_cc_preview_invisible),
        "performance" to context.getDrawable(R.drawable.hc_cc_preview_performance),
        "superpower" to context.getDrawable(R.drawable.hc_cc_preview_superpower),
        "custom_5G" to context.getDrawable(R.drawable.ic_control_center_5g_regular),
        "custom_GMS" to context.getDrawable(R.drawable.ic_control_center_google),
    ) + serviceBindings(context).filterValues { it.second != null }.mapValues { it.value.second }
    fun available(p: SharedPreferences) = entries.filter {
        it.id != "brightness" && it.id != "volume" &&
            (it.id != "custom_5G" || p.getInt(KEY_CONTROL_CENTER_5G_TILE_MODE, 0) != 0) &&
            (it.id != "custom_GMS" || p.getBoolean(KEY_CONTROL_CENTER_GMS_TILE_ENABLED, false))
    }

    /** Resolve OEM app services from their actual manifests, including OEM-disabled tiles. */
    fun serviceBindings(context: Context): Map<String, Pair<String, Drawable?>> = runCatching {
        context.packageManager.queryIntentServices(Intent("android.service.quicksettings.action.QS_TILE"),
            PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.GET_META_DATA)
            .mapNotNull { resolve ->
                val s = resolve.serviceInfo ?: return@mapNotNull null
                if (s.permission != "android.permission.BIND_QUICK_SETTINGS_TILE" || !s.exported) return@mapNotNull null
                val id = when {
                    s.packageName == "com.miui.securitycenter" && s.name.endsWith(".InvisibleModeTileService") -> "invisible"
                    s.packageName == "com.miui.securitycenter" && s.name.endsWith(".PerformanceModeTileService") -> "performance"
                    s.packageName == "com.miui.securitycenter" && s.name.endsWith(".SuperPowerTileService") -> "superpower"
                    s.packageName == "com.miui.calculator" -> "calculator"
                    s.packageName == "com.android.quicksearchbox" -> "search"
                    s.packageName in setOf("com.miui.carlink", "com.xiaomi.carlink", "com.miui.carwith") -> "carwith"
                    else -> return@mapNotNull null
                }
                id to ("custom(${ComponentName(s.packageName, s.name).flattenToShortString()})" to
                    runCatching { s.loadIcon(context.packageManager) }.getOrNull())
            }.sortedBy { it.second.first }.distinctBy { it.first }.toMap()
    }.getOrDefault(emptyMap())

    fun specs(context: Context, p: SharedPreferences, tiles: List<CcTile>): Map<String, String> {
        val services = serviceBindings(context)
        return available(p).mapNotNull { entry ->
            if (tiles.none { it.id == entry.id }) null
            else (entry.spec ?: services[entry.id]?.first)?.let { entry.id to it }
        }.toMap()
    }
}
