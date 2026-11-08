// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import org.json.JSONArray
import org.json.JSONObject

const val DEVICE_INTERFACE_STYLE_FOUR = 4
const val APPEARANCE_SLOT_STYLE4_BACKGROUND = "style4_background"
const val APPEARANCE_SLOT_STYLE4_LOGO = "style4_logo"

internal fun styleFourSpacing(value: Float) = value * 1.43f
internal fun styleFourContentInset(base: Float, adjustment: Float, extra: Float = 0f) =
    (styleFourSpacing(base) + adjustment + extra).coerceAtLeast(0f)

/** Nested in the dedicated appearance payload; the existing store synchronizes it locally/remotely. */
data class DeviceStyleFourSettings(
    val backgroundMime: String = "", val backgroundVersion: Long = 0,
    val logoMime: String = "", val logoVersion: Long = 0,
    val backgroundBlur: Int = 0, val backgroundX: Int = 0, val backgroundY: Int = 0,
    val backgroundScale: Int = 0, val bannerHeightScale: Int = 0,
    val cornerRadius: Int = 24, val shadow: Int = 12,
    val logoAlignment: Int = 1, val logoX: Int = 0, val logoY: Int = 0,
    val logoScale: Int = 0, val logoColorMode: Int = 0,
    val versionVisible: Boolean = false, val versionText: String = "",
    val versionScale: Int = 0, val versionColorMode: Int = 0, val versionSpacing: Int = 8,
    val modelText: String = "", val modelScale: Int = 0, val modelAlignment: Int = 2,
    val modelColorMode: Int = 0, val modelTopGap: Int = 32, val modelBottomGap: Int = 32,
    val valueScale: Int = 0, val labelScale: Int = 0, val rowHeightScale: Int = 0,
    val contentMargin: Int = 0,
    val rowGap: Int = 0, val textSpacing: Int = 8, val alignment: Int = 2,
    val parameterColorMode: Int = 0,
    val pairMemoryStorage: Boolean = true, val dividersVisible: Boolean = true,
    val markersVisible: Boolean = true, val markerWidth: Int = 3, val markerHeight: Int = 13,
    val markerSpacing: Int = 6,
    val markerColor: Int = 0xFFFF3B48.toInt(), val markerPosition: Int = 0,
    val visibleRows: List<Boolean> = List(8) { true },
    val rowValues: List<String> = List(8) { "" },
) {
    internal fun json() = JSONObject().apply {
        put("backgroundMime", backgroundMime); put("backgroundVersion", backgroundVersion)
        put("logoMime", logoMime); put("logoVersion", logoVersion)
        put("backgroundBlur", backgroundBlur); put("backgroundX", backgroundX); put("backgroundY", backgroundY)
        put("backgroundScale", backgroundScale); put("bannerHeightScale", bannerHeightScale)
        put("cornerRadius", cornerRadius); put("shadow", shadow)
        put("logoAlignment", logoAlignment); put("logoX", logoX); put("logoY", logoY)
        put("logoScale", logoScale); put("logoColorMode", logoColorMode)
        put("versionVisible", versionVisible); put("versionText", versionText); put("versionScale", versionScale)
        put("versionColorMode", versionColorMode); put("versionSpacing", versionSpacing)
        put("modelText", modelText); put("modelScale", modelScale); put("modelAlignment", modelAlignment)
        put("modelColorMode", modelColorMode); put("modelTopGap", modelTopGap); put("modelBottomGap", modelBottomGap)
        put("valueScale", valueScale); put("labelScale", labelScale); put("rowHeightScale", rowHeightScale)
        put("contentMargin", contentMargin)
        put("rowGap", rowGap); put("textSpacing", textSpacing); put("alignment", alignment)
        put("parameterColorMode", parameterColorMode)
        put("pairMemoryStorage", pairMemoryStorage); put("dividersVisible", dividersVisible)
        put("markersVisible", markersVisible); put("markerWidth", markerWidth); put("markerHeight", markerHeight)
        put("markerSpacing", markerSpacing); put("markerColor", markerColor); put("markerPosition", markerPosition)
        put("visibleRows", JSONArray(visibleRows)); put("rowValues", JSONArray(rowValues))
    }

    companion object {
        internal fun decode(j: JSONObject?) : DeviceStyleFourSettings {
            if (j == null) return DeviceStyleFourSettings()
            fun n(key: String, default: Int = 0, range: IntRange = -100..100) = j.optInt(key, default).coerceIn(range)
            fun text(key: String) = j.optString(key, "").take(256).trim()
            return DeviceStyleFourSettings(
                backgroundMime = text("backgroundMime"), backgroundVersion = j.optLong("backgroundVersion"),
                logoMime = text("logoMime"), logoVersion = j.optLong("logoVersion"),
                backgroundBlur = n("backgroundBlur", 0, 0..100), backgroundX = n("backgroundX"), backgroundY = n("backgroundY"),
                backgroundScale = n("backgroundScale"), bannerHeightScale = n("bannerHeightScale"),
                cornerRadius = n("cornerRadius", 24, 0..80), shadow = n("shadow", 12, 0..40),
                logoAlignment = n("logoAlignment", 1, 0..2), logoX = n("logoX"), logoY = n("logoY"),
                logoScale = n("logoScale"), logoColorMode = n("logoColorMode", 0, 0..2),
                versionVisible = j.optBoolean("versionVisible", false), versionText = text("versionText"),
                versionScale = n("versionScale"), versionColorMode = n("versionColorMode", 0, 0..2), versionSpacing = n("versionSpacing", 8, 0..100),
                modelText = text("modelText"), modelScale = n("modelScale"), modelAlignment = n("modelAlignment", 2, 0..2),
                modelColorMode = n("modelColorMode", 0, 0..2), modelTopGap = n("modelTopGap", 32, 0..120), modelBottomGap = n("modelBottomGap", 32, 0..120),
                valueScale = n("valueScale"), labelScale = n("labelScale"), rowHeightScale = n("rowHeightScale"),
                contentMargin = n("contentMargin", 0, -32..64),
                rowGap = n("rowGap", 0, 0..80), textSpacing = n("textSpacing", 8, 0..60), alignment = n("alignment", 2, 0..2),
                parameterColorMode = n("parameterColorMode", n("valueColorMode", n("labelColorMode", 0, 0..2), 0..2), 0..2),
                pairMemoryStorage = j.optBoolean("pairMemoryStorage", true), dividersVisible = j.optBoolean("dividersVisible", true),
                markersVisible = j.optBoolean("markersVisible", true), markerWidth = n("markerWidth", 3, 1..20),
                markerHeight = n("markerHeight", 13, 1..60), markerSpacing = n("markerSpacing", 6, 0..60),
                markerColor = j.optInt("markerColor", j.optJSONArray("markerColors")?.optInt(1, 0xFFFF3B48.toInt()) ?: 0xFFFF3B48.toInt()),
                markerPosition = n("markerPosition", 0, 0..1),
                visibleRows = List(8) { j.optJSONArray("visibleRows")?.optBoolean(it, true) ?: true },
                rowValues = List(8) { j.optJSONArray("rowValues")?.optString(it, "").orEmpty().take(256).trim() },
            )
        }
    }
}
