// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Render the imported native JSON over its clean photograph, using live module identity.
 * Supports native Layout/TextView/ImageView, fonts, badges, direct/fill/select assets and time.
 * Unknown dynamic inputs remain empty instead of baking a second watermark into the package.
 */
internal fun renderCameraWatermarkPreview(
    context: Context, pack: CameraWatermarkPackage, identity: CameraWatermarkIdentity,
    suffixes: Int, maximum: Int = 1024, now: Long = System.currentTimeMillis(),
): Bitmap? {
    val background = decodeWatermarkPreview(pack.preview, maximum) ?: return null
    val output = Bitmap.createBitmap(maximum, maximum, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val side = minOf(background.width, background.height)
    canvas.drawBitmap(background, Rect((background.width-side)/2, (background.height-side)/2,
        (background.width+side)/2, (background.height+side)/2), Rect(0, 0, maximum, maximum), Paint(Paint.FILTER_BITMAP_FLAG))
    background.recycle()
    val config = JSONObject(requireNotNull(pack.resources["config.json"]).toString(Charsets.UTF_8))
    val group = config.getJSONArray("layout_group").getJSONObject(0)
    val key = listOf("bottom_middle", "bottom", "layout").firstOrNull(group::has) ?: group.keys().asSequence().first()
    val roots = group.optJSONArray(key) ?: return output
    val scale = maximum / 1080f
    canvas.save(); canvas.scale(scale, scale)
    val renderer = WatermarkCanvasRenderer(context, pack, config, identity, suffixes, now)
    try { for (i in 0 until roots.length()) renderer.drawRoot(canvas, roots.getJSONObject(i)) }
    finally { renderer.close(); canvas.restore() }
    return output
}

private class WatermarkCanvasRenderer(
    val context: Context, val pack: CameraWatermarkPackage, val config: JSONObject,
    val identity: CameraWatermarkIdentity, val suffixes: Int, val now: Long,
) {
    private val fonts = config.optJSONObject("wmRes")?.optJSONObject("fonts") ?: JSONObject()
    private val images = mutableMapOf<String, Bitmap?>()
    private val typefaces = mutableMapOf<String, Typeface>()
    private val model = splitCameraWatermarkModel(identity.model, suffixes)
    private fun foreground(index: Int): String = config.optJSONObject("wmRes")?.optJSONArray("background")
        ?.optJSONObject(0)?.optJSONArray("foreground")?.optString(index).orEmpty()
    private data class Node(val json: JSONObject, val width: Float, val height: Float, val children: List<Node>,
        val text: String = "", val paint: Paint? = null, val image: Bitmap? = null)
    private fun dp(s: String): Float = s.removeSuffix("dp").removeSuffix("px").toFloatOrNull() ?: 0f
    private fun margin(n: Node, key: String) = dp(n.json.optString(key))
    private fun font(j: JSONObject): Paint {
        val f = fonts.optJSONObject(j.optString("font_ref")) ?: JSONObject()
        val type = f.optString("typeface", "default")
        val face = typefaces.getOrPut(type) {
            if (type.startsWith("@file=")) runCatching {
                val bytes = requireNotNull(pack.resources[type.removePrefix("@file=")])
                val file = File(context.cacheDir, "camera-watermark-fonts/${CameraWatermarkZip.hash(bytes)}.ttf")
                if (!file.isFile) { file.parentFile?.mkdirs(); file.writeBytes(bytes) }
                Typeface.createFromFile(file)
            }.getOrDefault(Typeface.DEFAULT) else Typeface.create("sans-serif", Typeface.NORMAL)
        }
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face; textSize = f.optDouble("size", 28.0).toFloat()
            val raw = f.optString("color")
            val resolved = if (raw.startsWith("@foreground=")) foreground(raw.substringAfter('=').toIntOrNull() ?: 0) else raw
            color = runCatching { Color.parseColor(resolved) }.getOrDefault(Color.WHITE)
            setShadowLayer(1f, 0f, 0f, 0x80000000.toInt())
        }
    }
    private fun image(j: JSONObject): Bitmap? {
        val path = j.optString("path")
        val fields = path.split(';').mapNotNull { p -> p.indexOf('=').takeIf { it >= 0 }?.let { p.take(it) to p.substring(it+1) } }.toMap()
        if (path.contains("path=hc_by_leica")) return images.getOrPut(path) {
            val color = if (fields["pathType"] == "select") foreground(fields["foreground"]?.toIntOrNull() ?: 0)
                else fields["path"].orEmpty().removePrefix("hc_by_leica_").removeSuffix(".webp")
            CameraWatermarkGlyph.bitmap(runCatching { Color.parseColor(color) }.getOrDefault(Color.WHITE))
        }
        val raw = fields["path"].orEmpty().replace("\${logo}", identity.brand.lowercase(Locale.ROOT))
        val file = when (fields["pathType"]) {
            "direct" -> raw
            "fill", "select" -> "${raw}_${foreground(fields["foreground"]?.toIntOrNull() ?: 0).ifBlank { "white" }}.webp"
            else -> raw.removePrefix("@img=")
        }
        return images.getOrPut(file) {
            pack.resources[file]?.let { decodeWatermarkPreview(it, 1280) }
        }
    }
    private fun text(j: JSONObject, separate: Boolean, leica: Boolean): String {
        val raw = j.optString("text")
        return when {
            raw.startsWith("@model=") -> renderCameraWatermarkModel(raw.removePrefix("@model="), identity.brand, model, j.optBoolean("text_upper"), separate, leica)
            raw.startsWith("@time=") -> SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault()).format(Date(now))
            raw.startsWith("@") -> ""
            else -> raw
        }
    }
    private fun measure(input: JSONObject, separate: Boolean = false, leica: Boolean = false): Node {
        if (input.optString("id") == "src_image_view") return Node(input, 1080f, 1080f, emptyList())
        if (input.has("enable") && !input.optBoolean("enable")) return Node(input, 0f, 0f, emptyList())
        var j = input
        val raw = j.optJSONArray("childViews")
        val nodes = raw?.let { (0 until it.length()).mapNotNull(it::optJSONObject) }.orEmpty()
        val hasModel = nodes.any { it.optString("text").contains("@{series}") }
        val hasSuffix = nodes.any { it.optString("text").contains("@{versionName}") && !it.optString("text").contains("@{series}") }
        if (hasModel && model.hasLeica) {
            val main = nodes.first { it.optString("text").contains("@{series}") }
            val size = fonts.optJSONObject(main.optString("font_ref"))?.optDouble("size", 30.0)?.toFloat() ?: 30f
            val mainFont = fonts.optJSONObject(main.optString("font_ref"))
            j = addCameraLeicaSuffix(j, model.byLeica, size, mainFont?.optString("color", "#FFFFFFFF") ?: "#FFFFFFFF") ?: j
        }
        val childrenJson = j.optJSONArray("childViews")
        val children = childrenJson?.let { a -> (0 until a.length()).map { measure(a.getJSONObject(it), hasSuffix, hasModel && model.hasLeica) } }.orEmpty()
        val orientation = j.optString("orientation")
        var width = when (orientation) {
            "horizontal" -> children.sumOf { (it.width + margin(it, "margin_left")).toDouble() }.toFloat()
            else -> children.maxOfOrNull { it.width + maxOf(0f, margin(it, "margin_left")) } ?: 0f
        }
        var height = when (orientation) {
            "vertical" -> children.sumOf { (it.height + margin(it, "margin_top")).toDouble() }.toFloat()
            else -> children.maxOfOrNull { it.height + maxOf(0f, margin(it, "margin_top")) } ?: 0f
        }
        var label = ""; var paint: Paint? = null; var bitmap: Bitmap? = null
        when (j.optString("type")) {
            "TextView" -> {
                paint = font(j); label = text(j, separate, leica)
                if (label.isNotEmpty()) {
                    val rect = j.optJSONObject("rect_params")
                    width = paint.measureText(label) + (rect?.optDouble("rect_text_width_gap", 0.0)?.toFloat() ?: 0f) * 2
                    height = maxOf(paint.textSize * 1.24f, dp(rect?.optString("rect_height").orEmpty()))
                }
            }
            "ImageView" -> {
                bitmap = image(j)
                if (bitmap != null) {
                    height = dp(j.optString("display_height")).takeIf { it > 0f } ?: bitmap.height.toFloat()
                    width = dp(j.optString("display_width")).takeIf { it > 0f } ?: height * bitmap.width / bitmap.height
                }
            }
        }
        dp(j.optString("layout_width")).takeIf { it > 0 }?.let { width = it }
        dp(j.optString("layout_height")).takeIf { it > 0 }?.let { height = it }
        if (j.optString("layout_width") == "match_parent") width = 1080f
        return Node(j, width, height, children, label, paint, bitmap)
    }
    fun drawRoot(canvas: Canvas, root: JSONObject) = draw(canvas, measure(root), 0f, 0f)
    private fun draw(canvas: Canvas, n: Node, x: Float, y: Float) {
        if (n.json.optString("id") == "src_image_view") return
        val background = n.json.optString("background")
        if (background.startsWith("@foreground=")) {
            val colors = config.optJSONObject("wmRes")?.optJSONArray("background")?.optJSONObject(0)?.optJSONArray("foreground")
            val value = colors?.optString(background.substringAfter('=').toIntOrNull() ?: 0).orEmpty()
            val color = runCatching { Color.parseColor(value) }.getOrDefault(Color.TRANSPARENT)
            canvas.drawRect(x, y, x+n.width, y+n.height, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        if (n.text.isNotEmpty()) n.paint?.let { p ->
            val rect = n.json.optJSONObject("rect_params")
            if (rect != null) canvas.drawRoundRect(x, y, x+n.width, y+n.height,
                6f, 6f, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = rect.optDouble("rect_line_width", 2.0).toFloat() })
            canvas.drawText(n.text, x+(n.width-p.measureText(n.text))/2, y+(n.height-p.fontMetrics.ascent-p.fontMetrics.descent)/2, p)
        }
        n.image?.let { canvas.drawBitmap(it, null, RectF(x, y, x+n.width, y+n.height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) }
        val orientation = n.json.optString("orientation")
        var cursor = 0f
        for (c in n.children) {
            val g = c.json.optString("layout_gravity").ifBlank {
                if (orientation == "overlap") c.json.optString("gravity") else n.json.optString("gravity")
            }
            var cx = when { "right" in g -> n.width-c.width; "center_horizontal" in g || g == "center" -> (n.width-c.width)/2; else -> 0f }
            var cy = when { "bottom" in g -> n.height-c.height; "center_vertical" in g || g == "center" -> (n.height-c.height)/2; else -> 0f }
            if (orientation == "horizontal") { cursor += margin(c,"margin_left"); cx=cursor; cursor+=c.width }
            else cx+=margin(c,"margin_left")
            if (orientation == "vertical") { cursor+=margin(c,"margin_top"); cy=cursor; cursor+=c.height }
            else cy+=margin(c,"margin_top")
            draw(canvas,c,x+cx,y+cy)
        }
    }
    fun close() { images.values.filterNotNull().forEach(Bitmap::recycle) }
}
