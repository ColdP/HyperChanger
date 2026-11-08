// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.graphics.*
import android.util.Base64
import java.io.File
import org.json.JSONObject
import org.json.JSONArray

/** Only the by Leica wordmark cropped from the camera's native composite logo. */
internal object CameraWatermarkGlyph {
    private const val MASK = "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"
    fun bitmap(color: Int = Color.WHITE): Bitmap {
        val bytes = Base64.decode(MASK, Base64.DEFAULT)
        val source = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        })
        source.recycle()
        return output
    }
    @Synchronized fun materialize(directory: File) {
        val colors = mutableMapOf("white" to "FFFFFFFF", "black" to "FF000000", "CCFFFFFF" to "CCFFFFFF",
            "74380B" to "FF74380B", "B66E18" to "FFB66E18", "CE9238" to "FFCE9238", "DE1010" to "FFDE1010",
            "E2433B" to "FFE2433B", "E6A54F" to "FFE6A54F", "F4CA32" to "FFF4CA32", "F6C566" to "FFF6C566",
            "FFE099" to "FFFFE099", "FFF383" to "FFFFF383", "yellow" to "FFFFFF00")
        // A foreground slot can contain a color literal, not only an asset suffix
        // such as black/white. Keep its exact spelling in the native filename.
        val config = JSONObject(File(directory, "config.json").readText())
        fun collect(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { collect(value.opt(it)) }
                is JSONArray -> (0 until value.length()).forEach { collect(value.opt(it)) }
                is String -> if (value.matches(Regex("#[a-fA-F0-9]{6}([a-fA-F0-9]{2})?"))) {
                    colors[value] = "%08X".format(Color.parseColor(value))
                }
            }
        }
        collect(config.optJSONObject("wmRes"))
        for ((name, hex) in colors) {
            val file = File(directory, "hc_by_leica_$name.webp")
            if (!file.isFile) {
                val image = bitmap(hex.toLong(16).toInt())
                try { file.outputStream().use { check(image.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it)) } }
                finally { image.recycle() }
            }
        }
    }
}

/** Clone the row rather than mutating the cached config. Keep the native suffix node intact. */
internal fun addCameraLeicaSuffix(row: JSONObject, styled: Boolean, size: Float, color: String = "@foreground=3"): JSONObject? {
    val children = row.optJSONArray("childViews") ?: return null
    val nodes = (0 until children.length()).mapNotNull { children.optJSONObject(it) }
    val main = nodes.firstOrNull { it.optString("text").contains("@{series}") } ?: return null
    if (nodes.any { it.optString("id").startsWith("hc_by_leica") }) return null
    val after = nodes.indexOfLast { it.optString("text").contains("@{series}") || it.optString("text").contains("@{versionName}") }
    val suffix = JSONObject().put("id", "hc_by_leica").put("type", if (styled) "ImageView" else "TextView")
        .put("margin_left", "8dp").put("margin_top", "0dp").put("layout_gravity", "center_vertical")
        .put("support_scale", true).put("support_alpha", true)
    // select respects the explicit image basename in both preview and the native
    // export resolver. fill selects the device BRAND and is unsupported by export.
    val path = if (color.startsWith("@foreground=")) {
        val index = color.substringAfter('=').toIntOrNull() ?: return null
        if (index < 0) return null
        "pathType=select;path=hc_by_leica;foreground=$index"
    } else if (color.matches(Regex("#[a-fA-F0-9]{6}([a-fA-F0-9]{2})?"))) {
        "pathType=direct;path=hc_by_leica_$color.webp"
    } else return null
    if (styled) suffix.put("path", path)
        .put("display_height", "${size * 1.1f}dp").put("display_width", "0dp")
    else suffix.put("text", "by Leica").put("font_ref", main.optString("font_ref"))
    val result = JSONArray()
    nodes.forEachIndexed { index, node -> result.put(node); if (index == after) result.put(suffix) }
    return JSONObject(row.toString()).put("childViews", result)
}
