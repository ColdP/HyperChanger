// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.Rect
import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import android.util.Xml
import androidx.core.graphics.PathParser
import java.io.File
import java.io.InputStream
import kotlin.math.max
import org.xmlpull.v1.XmlPullParser

object LogoDrawableLoader {
    fun load(context: Context, source: SettingsAppearanceSource): Drawable? {
        return runCatching {
            val bytes = context.contentResolver.openInputStream(source.uri)?.use { it.readBytes() } ?: return@runCatching null
            val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            if (text.startsWith("<svg", ignoreCase = true) || text.contains("<svg", ignoreCase = true)) {
                parseSvg(text)
            } else if (text.startsWith("<vector", ignoreCase = true) || text.startsWith("<?xml", ignoreCase = true) && text.contains("<vector", ignoreCase = true)) {
                val parser = Xml.newPullParser().apply { setInput(bytes.inputStream(), "UTF-8") }
                Drawable.createFromXml(context.resources, parser)
            } else {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, source.uri))
            }
        }.getOrNull()
    }

    fun forBackground(drawable: Drawable, scale: Float): Drawable = AspectDrawable(drawable, scale.coerceIn(0.5f, 2f))

    /** Loads a persisted local asset without relying on a document-provider URI grant. */
    fun loadFile(context: Context, file: File): Drawable? = runCatching {
        if (!file.isFile) return@runCatching null
        loadBytes(context, file.readBytes())
    }.getOrNull()

    fun loadBytes(context: Context, bytes: ByteArray): Drawable? {
        val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return when {
            text.startsWith("<svg", ignoreCase = true) || text.contains("<svg", ignoreCase = true) -> parseSvg(text)
            text.startsWith("<vector", ignoreCase = true) ||
                text.startsWith("<?xml", ignoreCase = true) && text.contains("<vector", ignoreCase = true) -> {
                val parser = Xml.newPullParser().apply { setInput(bytes.inputStream(), "UTF-8") }
                Drawable.createFromXml(context.resources, parser)
            }
            else -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { BitmapDrawable(context.resources, it) }
        }
    }

    private fun loadXmlOrSvg(context: Context, source: SettingsAppearanceSource): Drawable? {
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            val bytes = input.readBytes()
            val text = bytes.toString(Charsets.UTF_8)
            if (text.contains("<svg", ignoreCase = true)) return parseSvg(text)
            val parser = Xml.newPullParser().apply { setInput(bytes.inputStream(), "UTF-8") }
            return Drawable.createFromXml(context.resources, parser)
        }
        return null
    }

    private fun parseSvg(text: String): Drawable? {
        val paths = Regex("<path\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(text).mapNotNull { match ->
            val attrs = match.groupValues[1]
            val data = Regex("\\bd\\s*=\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1) ?: return@mapNotNull null
            // android.util.PathParser is hidden on recent Android versions; use AndroidX's public parser.
            val path = runCatching { PathParser.createPathFromPathData(data) }.getOrNull()
                ?: return@mapNotNull null
            val fill = Regex("\\bfill\\s*=\\s*[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE).find(attrs)?.groupValues?.get(1)
            path to parseColor(fill)
        }.toList()
        if (paths.isEmpty()) return null
        val viewBox = Regex("\\bviewBox\\s*=\\s*[\\\"']\\s*([-+0-9.eE]+)[, ]+([-+0-9.eE]+)[, ]+([-+0-9.eE]+)[, ]+([-+0-9.eE]+)", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.drop(1)?.mapNotNull { it.toFloatOrNull() }
        val width = Regex("\\bwidth\\s*=\\s*[\\\"']\\s*([-+0-9.eE]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toFloatOrNull()
        val height = Regex("\\bheight\\s*=\\s*[\\\"']\\s*([-+0-9.eE]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toFloatOrNull()
        val minX = viewBox?.getOrNull(0) ?: 0f
        val minY = viewBox?.getOrNull(1) ?: 0f
        val sourceWidth = max(1f, viewBox?.getOrNull(2) ?: width ?: 100f)
        val sourceHeight = max(1f, viewBox?.getOrNull(3) ?: height ?: sourceWidth)
        return SvgPathDrawable(paths, minX, minY, sourceWidth, sourceHeight)
    }

    private fun parseColor(value: String?): Int = when {
        value == null || value.equals("none", true) -> Color.WHITE
        value.startsWith("#") -> runCatching { Color.parseColor(value) }.getOrDefault(Color.WHITE)
        value.equals("black", true) -> Color.BLACK
        value.equals("white", true) -> Color.WHITE
        else -> Color.WHITE
    }
}

private class AspectDrawable(
    private val child: Drawable,
    private val scale: Float,
) : Drawable() {
    private val sourceWidth = child.intrinsicWidth.takeIf { it > 0 } ?: 1
    private val sourceHeight = child.intrinsicHeight.takeIf { it > 0 } ?: 1

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val fit = minOf(b.width().toFloat() / sourceWidth, b.height().toFloat() / sourceHeight) * scale
        val width = (sourceWidth * fit).toInt().coerceAtLeast(1)
        val height = (sourceHeight * fit).toInt().coerceAtLeast(1)
        val left = b.left + (b.width() - width) / 2
        val top = b.top + (b.height() - height) / 2
        child.bounds = Rect(left, top, left + width, top + height)
        child.draw(canvas)
    }

    override fun setAlpha(alpha: Int) { child.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { child.colorFilter = filter; invalidateSelf() }
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = (sourceWidth * scale).toInt()
    override fun getIntrinsicHeight(): Int = (sourceHeight * scale).toInt()
}

private class SvgPathDrawable(
    private val paths: List<Pair<Path, Int>>,
    private val sourceMinX: Float,
    private val sourceMinY: Float,
    private val sourceWidth: Float,
    private val sourceHeight: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        val scale = minOf(bounds.width() / sourceWidth, bounds.height() / sourceHeight)
        canvas.save()
        canvas.translate(
            bounds.left + (bounds.width() - sourceWidth * scale) / 2f - sourceMinX * scale,
            bounds.top + (bounds.height() - sourceHeight * scale) / 2f - sourceMinY * scale,
        )
        canvas.scale(scale, scale)
        paths.forEach { (path, color) -> paint.color = color; canvas.drawPath(path, paint) }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android API") override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = sourceWidth.toInt()
    override fun getIntrinsicHeight(): Int = sourceHeight.toInt()
}
