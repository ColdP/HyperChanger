// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import android.graphics.drawable.Drawable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.*

internal fun ccTileTitle(id: String) = CcTileCatalog.entries.firstOrNull { it.id == id }?.title()
    ?: tr("cc_layout_title", "控制中心磁贴布局自定义")

@Composable
internal fun ControlCenterTileLayoutPage(back: () -> Unit) = AppPage(
    tr("cc_layout_title", "控制中心磁贴布局自定义"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI),
) { padding, scroll ->
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context) { ControlCenterTileLayoutSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    val listState = rememberLazyListState()
    val local = remember(context) { context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE) }
    var available by remember { mutableStateOf(CcTileCatalog.available(local).map { it.id }) }
    val serviceIcons = remember(context) { CcTileCatalog.previewIcons(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var previewMedia by remember { mutableStateOf<CcPreviewMedia?>(null) }
    LaunchedEffect(context, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (true) {
                    try {
                        previewMedia = CcPreviewMediaReader.read(context)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        previewMedia = null
                        break
                    }
                    delay(5000)
                }
            } finally {
                previewMedia = null
            }
        }
    }
    DisposableEffect(local) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_CONTROL_CENTER_5G_TILE_MODE || key == KEY_CONTROL_CENTER_GMS_TILE_ENABLED)
                available = CcTileCatalog.available(local).map { it.id }
        }
        local.registerOnSharedPreferenceChangeListener(listener)
        onDispose { local.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var confirmReset by remember { mutableStateOf(false) }
    val scheme = MiuixTheme.colorScheme
    val tileColor = lerp(scheme.surfaceContainer, scheme.primary, .08f)
    val editorColors = CcEditorColors(
        tile = tileColor.toArgb(), activeTile = lerp(tileColor, scheme.primary, .14f).toArgb(),
        foreground = scheme.onSurface.toArgb(), media = scheme.onSurfaceVariantSummary.toArgb(),
        guide = scheme.onSurface.copy(alpha = .12f).toArgb(), drop = scheme.primary.copy(alpha = .55f).toArgb(),
        handle = scheme.onSurface.toArgb(), badge = scheme.onSurface.toArgb(), badgeText = scheme.surface.toArgb(),
        card = scheme.surfaceContainer.toArgb(),
    )
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        settings = store.settings
    }
    AppList(padding, scroll, 28, state = listState) {
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp) {
                SwitchPreference(title = tr("cc_layout_enable", "启用自定义布局"), checked = settings.enabled,
                    onCheckedChange = {
                        settings = settings.copy(enabled = it)
                        store.update(service, settings)
                    })
                SwitchPreference(title = tr("cc_media_hide_source", "隐藏媒体来源图标"), checked = settings.mediaSourceIconHidden,
                    enabled = settings.enabled,
                    onCheckedChange = {
                        settings = settings.copy(mediaSourceIconHidden = it)
                        store.update(service, settings)
                    })
                SwitchPreference(title = tr("cc_small_tile_auto_fill", "小磁贴自动补位"), checked = settings.smallTileAutoFill,
                    enabled = settings.enabled,
                    onCheckedChange = {
                        settings = settings.copy(smallTileAutoFill = it)
                        store.update(service, settings)
                    })
                ArrowPreference(title = tr("cc_layout_reset", "还原初始布局"), onClick = { confirmReset = true })
            }
        }
        item {
            AndroidView(
                factory = { CcLayoutEditorView(it) },
                modifier = Modifier.fillMaxWidth().wrapContentHeight(),
                update = { editor ->
                    editor.setColors(editorColors)
                    editor.setMedia(previewMedia)
                    editor.onAutoScroll = { listState.dispatchRawDelta(it) }
                    editor.onCommit = { tiles, sizes ->
                        settings = settings.copy(tiles = tiles, rememberedSizes = sizes)
                        store.update(service, settings)
                    }
                    editor.setContent(settings.tiles, available, settings.rememberedSizes, serviceIcons)
                },
                onRelease = { it.release() },
            )
        }
    }

    if (confirmReset) WindowDialog(show = true, onDismissRequest = { confirmReset = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(tr("cc_layout_reset_confirm", "即将恢复初始控制中心布局，是否确认？"),
                modifier = Modifier.fillMaxWidth(), style = MiuixTheme.textStyles.title3, textAlign = TextAlign.Start)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassDialogButton(onClick = { confirmReset = false }, modifier = Modifier.weight(1f)) {
                    Text(tr("取消", "取消"))
                }
                GlassDialogButton(onClick = {
                    settings = settings.copy(tiles = CcTileLayouts.initial, rememberedSizes = emptyMap())
                    store.update(service, settings)
                    confirmReset = false
                }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColorsPrimary()) {
                    Text(tr("确认", "确认"))
                }
            }
        }
    }
}

internal data class CcEditorColors(
    val tile: Int, val activeTile: Int, val foreground: Int, val media: Int,
    val guide: Int, val drop: Int, val handle: Int, val badge: Int, val badgeText: Int, val card: Int,
)

/** Native layout editor; media preview reads a snapshot without editing the system. */
internal class CcLayoutEditorView(context: Context) : View(context) {
    var onCommit: (List<CcTile>, Map<String, Pair<Int, Int>>) -> Unit = { _, _ -> }
    var onAutoScroll: (Float) -> Float = { 0f }
    private var available = emptyList<String>()
    private var sizes = emptyMap<String, Pair<Int, Int>>()
    private var icons = emptyMap<String, Drawable?>()
    private var media: CcPreviewMedia? = null
    private var baseSizes = sizes
    private var baseAvailable = available
    private var sourceLower = false
    private var targetLower = false
    private var origin = RectF()
    private var rawPointerY = 0f
    private var upperHeight = 0f
    private var lowerHeight = 0f
    private var transition = 1f
    private val contentNode = RenderNode("cc-layout-preview")
    private val lowerIds get() = available.filter { id -> tiles.none { it.id == id } }
    private val lowerTop get() = upperHeight + gap
    private val labelHeight get() = 32f * density
    private val lowerStep get() = step + labelHeight
    private fun upperTarget(): Float {
        val rows = if (dragging && tiles.size < base.size) maxOf(CcTileLayouts.rows(base), CcTileLayouts.rows(tiles))
            else CcTileLayouts.rows(tiles)
        return 2 * pad + rows * step - gap
    }
    private fun lowerTarget() = 2 * pad + maxOf(1, (lowerIds.size + 3) / 4) * lowerStep - gap
    private val autoScroll = object : Runnable {
        override fun run() {
            if (!dragging) return
            val visible = Rect()
            if (getGlobalVisibleRect(visible)) {
                val edge = 64f * density
                val delta = when {
                    rawPointerY < visible.top + edge -> -minOf(18f * density, (visible.top + edge - rawPointerY) * .18f)
                    rawPointerY > visible.bottom - edge -> minOf(18f * density, (rawPointerY - visible.bottom + edge) * .18f)
                    else -> 0f
                }
                if (delta != 0f && onAutoScroll(delta) != 0f) {
                    val location = IntArray(2); getLocationOnScreen(location)
                    dy = rawPointerY - location[1] - startY
                    updateDrag()
                }
            }
            postOnAnimation(this)
        }
    }
    private var tiles = CcTileLayouts.initial
    private var base = tiles
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val pad get() = 6f * density
    // Fixed concentric corners: 22.5dp card = 6dp inset + 16.5dp tile.
    private val tileRadius get() = 16.5f * density
    private var colors: CcEditorColors? = null
    private val gap get() = 12f * density
    private val cell get() = ((measuredWidth - 2 * pad - 3 * gap) / 4f).coerceAtLeast(1f)
    private val step get() = cell + gap
    private var active: CcTile? = null
    private var dragging = false
    private var resizing = false
    private var pointer = -1
    private var startX = 0f
    private var startY = 0f
    private var dx = 0f
    private var dy = 0f
    private val rects = mutableMapOf<String, RectF>()
    private var animator: ValueAnimator? = null
    private var animationUpperTarget = 0f
    private var animationLowerTarget = 0f
    private var compactness = 0f
    private var shapeAnimator: ValueAnimator? = null
    private val longPress = Runnable {
        if (active != null) {
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            postOnAnimation(autoScroll)
            invalidate()
        }
    }
    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    fun setContent(value: List<CcTile>, choices: List<String>, memory: Map<String, Pair<Int, Int>>, drawables: Map<String, Drawable?>) {
        if (active != null) return
        icons = drawables
        if (tiles != value || available != choices || sizes != memory) {
            tiles = value; available = choices; sizes = memory; rects.clear()
            upperHeight = upperTarget(); lowerHeight = lowerTarget()
            requestLayout(); invalidate()
        }
        contentDescription = tr("cc_layout_drag_help", "长按磁贴在上下卡片间拖动；拖动右下角调整大小。亮度和音量不能移出。")
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val newWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (newWidth != measuredWidth || upperHeight == 0f) {
            setMeasuredDimension(newWidth, 0)
            upperHeight = upperTarget(); lowerHeight = lowerTarget()
        }
        setMeasuredDimension(newWidth, resolveSize((upperHeight + gap + lowerHeight).roundToInt(), heightMeasureSpec))
    }
    private fun radius(r: RectF) = tileRadius
        .coerceAtMost(minOf(r.width(), r.height()) / 2)
    fun setMedia(value: CcPreviewMedia?) {
        if (media != value) { media = value; invalidate() }
    }
    fun setColors(value: CcEditorColors) {
        if (colors != value) { colors = value; invalidate() }
    }
    fun release() { uiHandler.removeCallbacks(longPress); removeCallbacks(autoScroll); animator?.cancel(); shapeAnimator?.cancel() }
    override fun onDetachedFromWindow() { cancelGesture(); release(); super.onDetachedFromWindow() }
    private fun rect(t: CcTile) = RectF(pad + t.col * step, pad + t.row * step,
        pad + t.col * step + t.w * cell + (t.w - 1) * gap, pad + t.row * step + t.h * cell + (t.h - 1) * gap)

    private fun round(canvas: Canvas, r: RectF, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL; paint.color = color
        canvas.drawRoundRect(r, radius, radius, paint)
    }
    private fun lowerRect(id: String): RectF {
        val index = lowerIds.indexOf(id).coerceAtLeast(0)
        val x = pad + index % 4 * step
        val y = lowerTop + pad + index / 4 * lowerStep
        return RectF(x, y, x + cell, y + cell)
    }
    private fun targetRect(id: String) = tiles.firstOrNull { it.id == id }?.let(::rect) ?: lowerRect(id)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cell <= 0f) return
        val palette = colors ?: return
        round(canvas, RectF(0f, 0f, width.toFloat(), upperHeight), 22.5f * density, palette.card)
        round(canvas, RectF(0f, lowerTop, width.toFloat(), lowerTop + lowerHeight), 22.5f * density, palette.card)
        if (dragging || resizing) {
            paint.style = Paint.Style.STROKE; paint.color = palette.guide; paint.strokeWidth = density
            paint.pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
            for (r in 0 until CcTileLayouts.rows(tiles)) for (c in 0..3)
                canvas.drawRoundRect(rect(CcTile("", c, r, 1, 1)), tileRadius, tileRadius, paint)
            if (!targetLower) tiles.firstOrNull { it.id == active?.id }?.let {
                paint.color = palette.drop; paint.strokeWidth = 2f * density
                canvas.drawRoundRect(rect(it), tileRadius, tileRadius, paint)
            }
            paint.pathEffect = null
        }
        // Blur only moving tile content; the card surface and the dragged item stay sharp.
        val radius = sin(transition * PI).toFloat().coerceAtLeast(0f) * 4f * density
        fun drawContents(target: Canvas) {
            tiles.filter { it.id != active?.id }.forEach { drawTile(target, it, rects[it.id] ?: rect(it)) }
            lowerIds.filter { it != active?.id }.forEach { drawAvailable(target, it, rects[it] ?: lowerRect(it)) }
        }
        if (canvas.isHardwareAccelerated && radius > .05f) {
            val visible = Rect()
            if (!getLocalVisibleRect(visible)) visible.set(0, 0, width, minOf(height, resources.displayMetrics.heightPixels))
            contentNode.setPosition(visible.left, visible.top, visible.right, visible.bottom)
            val recording = contentNode.beginRecording(visible.width(), visible.height())
            try { recording.translate(-visible.left.toFloat(), -visible.top.toFloat()); drawContents(recording) }
            finally { contentNode.endRecording() }
            contentNode.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            canvas.drawRenderNode(contentNode)
        } else drawContents(canvas)
        active?.let { tile ->
            val r = RectF(origin)
            if (dragging) {
                r.offset(dx, dy)
                // Morph between the remembered upper size and the lower 1×1 size.
                val expandedWidth = tile.w * cell + (tile.w - 1) * gap
                val expandedHeight = tile.h * cell + (tile.h - 1) * gap
                val w = expandedWidth + (cell - expandedWidth) * compactness
                val h = expandedHeight + (cell - expandedHeight) * compactness
                val cx = r.centerX(); val cy = r.centerY()
                r.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            }
            if (resizing) {
                r.right = r.left + (r.width() + dx).coerceIn(cell * .75f, 4 * cell + 3 * gap)
                r.bottom = r.top + (r.height() + dy).coerceIn(cell * .75f, 3 * cell + 2 * gap)
            }
            if (targetLower) drawAvailable(canvas, tile.id, r)
            else drawTile(canvas, tiles.firstOrNull { it.id == tile.id } ?: tile, r)
        }
    }
    private fun drawAvailable(c: Canvas, id: String, r: RectF) {
        val palette = colors ?: return
        round(c, r, radius(r), if (active?.id == id && dragging) palette.activeTile else palette.tile)
        icon(c, id, r.centerX() - 16f * density, r.centerY() - 16f * density, 32f * density)
        paint.style = Paint.Style.FILL; paint.color = palette.foreground
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        paint.textSize = 11f * density; paint.textAlign = Paint.Align.CENTER
        // Two lines keep long localized names legible below a fixed square tile.
        val textPaint = android.text.TextPaint(paint).apply { textAlign = Paint.Align.LEFT }
        val layout = android.text.StaticLayout.Builder.obtain(ccTileTitle(id), 0, ccTileTitle(id).length,
            textPaint, r.width().roundToInt().coerceAtLeast(1))
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setIncludePad(false)
            .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        c.save(); c.translate(r.left, r.bottom + 5f * density); layout.draw(c); c.restore()
    }
    private fun drawTile(c: Canvas, t: CcTile, r: RectF) {
        val palette = colors ?: return
        val radius = radius(r)
        round(c, r, radius, if (active?.id == t.id && (dragging || resizing)) palette.activeTile else palette.tile)
        c.save(); c.clipPath(Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) })
        val scale = minOf(1f, cell / (88f * density))
        val u = density * scale
        if (t.slider) {
            icon(c, t.id, r.centerX() - 16 * u, r.bottom - 48 * u, 32 * u)
        } else if (t.id == "media") {
            val gray = palette.media
            fun block(x: Float, y: Float, w: Float, h: Float, rad: Float) = round(c, RectF(x, y, x + maxOf(0f, w), y + h), rad, gray)
            fun cover(x: Float, y: Float, size: Float) {
                // Cover uses a fixed 10dp radius; a 6.5dp inset aligns its corner center
                // with the enclosing 16.5dp tile. No module radius preference is read.
                if (size <= 0f) return
                val art = media?.artwork
                val corner = minOf(10f * density, size / 2)
                if (art == null) block(x, y, size, size, corner)
                else {
                    val target = RectF(x, y, x + size, y + size)
                    val side = minOf(art.width, art.height)
                    val left = (art.width - side) / 2; val top = (art.height - side) / 2
                    c.save()
                    c.clipPath(Path().apply { addRoundRect(target, corner, corner, Path.Direction.CW) })
                    paint.style = Paint.Style.FILL; paint.color = Color.WHITE; paint.isFilterBitmap = true
                    c.drawBitmap(art, Rect(left, top, left + side, top + side), target, paint)
                    c.restore()
                }
            }
            fun label(value: String?, x: Float, y: Float, width: Float, height: Float,
                      placeholderWidth: Float, bold: Boolean) {
                if (value == null) { block(x, y, placeholderWidth, height, height / 2); return }
                paint.style = Paint.Style.FILL; paint.color = if (bold) palette.foreground else palette.media
                paint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
                paint.textAlign = Paint.Align.LEFT; paint.textSize = height
                val text = android.text.TextUtils.ellipsize(value.replace('\n', ' ').replace('\r', ' '),
                    android.text.TextPaint(paint), width.coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END)
                c.drawText(text.toString(), x, y + height / 2 - (paint.ascent() + paint.descent()) / 2, paint)
            }
            when {
                t.w == 1 && t.h == 1 -> { val inset = 6.5f * density; val s = minOf(r.width(), r.height()) - 2 * inset; cover(r.left + inset, r.top + inset, s) }
                t.h == 1 || t.w >= 3 -> {
                    val inset = 6.5f * density
                    val s = minOf(r.height() - 2 * inset, r.width() * .43f)
                    val x = r.left + inset; val y = r.top + inset
                    cover(x, y, s)
                    val lineX = x + s + 16 * u; val lineW = r.right - lineX - 18 * u
                    label(media?.title, lineX, r.centerY() - 18 * u, lineW, 16 * u, lineW, true)
                    label(media?.artist, lineX, r.centerY() + 8 * u, lineW, 14 * u, lineW * .6f, false)
                }
                else -> {
                    val inset = 6.5f * density
                    val x = r.left + inset; val y = r.top + inset
                    val s = minOf(68 * u, r.width() - 2 * inset)
                    cover(x, y, s)
                    val textWidth = r.right - x - 12 * u
                    label(media?.title, x, y + s + 16 * u, textWidth, 18 * u, (r.width() - 40 * u) * .74f, true)
                    label(media?.artist, x, y + s + 44 * u, textWidth, 16 * u, (r.width() - 40 * u) * .42f, false)
                }
            }
        } else {
            val vertical = t.w == 1
            val small = t.w == 1 && t.h == 1
            val iconSize = 32 * u
            val x = if (vertical) r.centerX() - iconSize / 2 else r.left + 20 * u
            val y = if (vertical && !small) r.centerY() - 30 * u else r.centerY() - iconSize / 2
            icon(c, t.id, x, y, iconSize)
            if (!small) {
                paint.color = palette.foreground; paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                paint.textSize = (if (vertical) 11 else 21) * u
                paint.style = Paint.Style.FILL
                val available = if (vertical) r.width() - 12 * u else r.right - (x + iconSize + 12 * u) - 12 * u
                val title = android.text.TextUtils.ellipsize(ccTileTitle(t.id), android.text.TextPaint(paint), available.coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END).toString()
                paint.textAlign = if (vertical) Paint.Align.CENTER else Paint.Align.LEFT
                c.drawText(title, if (vertical) r.centerX() else x + iconSize + 12 * u,
                    if (vertical) y + iconSize + 17 * u else r.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
        if (!t.slider) {
            paint.color = palette.handle; paint.style = Paint.Style.STROKE; paint.strokeWidth = 4.8f * u
            paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            val right = r.right - 11 * u; val bottom = r.bottom - 11 * u
            val p = Path().apply { moveTo(right, bottom - 20 * u); lineTo(right, bottom - 15 * u); quadTo(right, bottom, right - 15 * u, bottom); lineTo(right - 20 * u, bottom) }
            c.drawPath(p, paint)
            if (resizing && t.id == active?.id) {
                val badge = RectF(r.right - 56 * u, r.top + 8 * u, r.right - 8 * u, r.top + 29 * u)
                round(c, badge, 11 * u, palette.badge)
                paint.color = palette.badgeText; paint.textSize = 11 * u; paint.textAlign = Paint.Align.CENTER
                c.drawText("${t.w}×${t.h}", badge.centerX(), badge.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
        c.restore()
    }
    private fun icon(c: Canvas, id: String, x: Float, y: Float, s: Float) {
        val color = colors?.foreground ?: return
        if (id == "brightness" || id == "volume") {
            // Preserve the original slider preview icons and geometry.
            c.save(); c.translate(x, y); c.scale(s / 36f, s / 36f)
            paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
            paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            fun path(block: Path.() -> Unit) { c.drawPath(Path().apply(block), paint) }
            if (id == "brightness") {
                c.drawCircle(18f,18f,6f,paint)
                repeat(8) { i -> val a = i * PI / 4; c.drawLine(18 + 11f * cos(a).toFloat(),18 + 11f * sin(a).toFloat(),18 + 15f * cos(a).toFloat(),18 + 15f * sin(a).toFloat(),paint) }
            } else {
                path { moveTo(7.5f,14.5f); lineTo(12.5f,14.5f); lineTo(19f,9f); lineTo(19f,27f); lineTo(12.5f,21.5f); lineTo(7.5f,21.5f); close() }
                path { moveTo(23.5f,13.5f); quadTo(27f,18f,23.5f,22.5f) }
                path { moveTo(27f,10f); quadTo(34f,18f,27f,26f) }
            }
            c.restore(); return
        }
        val drawable = icons[id] ?: return
        c.save(); c.translate(x, y)
        drawable.mutate().setTint(color)
        drawable.setBounds(0, 0, s.roundToInt(), s.roundToInt())
        drawable.draw(c)
        c.restore()
    }
    private fun animateTo(next: List<CcTile>) {
        if (next == tiles && animator?.isRunning == true &&
            abs(animationUpperTarget - upperTarget()) < .5f && abs(animationLowerTarget - lowerTarget()) < .5f) return
        if (next == tiles && abs(upperHeight - upperTarget()) < .5f && abs(lowerHeight - lowerTarget()) < .5f) return
        val allIds = (tiles.map { it.id } + available).distinct()
        val from = allIds.associateWith { RectF(rects[it] ?: targetRect(it)) }
        val oldUpper = upperHeight; val oldLower = lowerHeight
        animator?.cancel(); tiles = next
        val targetUpper = upperTarget(); val targetLowerHeight = lowerTarget()
        animationUpperTarget = targetUpper; animationLowerTarget = targetLowerHeight
        // Compute destinations against final card geometry, not an intermediate animation frame.
        upperHeight = targetUpper; lowerHeight = targetLowerHeight
        val destinations = allIds.associateWith(::targetRect)
        upperHeight = oldUpper; lowerHeight = oldLower
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)
            addUpdateListener {
                val f = it.animatedValue as Float; transition = f
                upperHeight = oldUpper + (targetUpper - oldUpper) * f
                lowerHeight = oldLower + (targetLowerHeight - oldLower) * f
                allIds.forEach { id ->
                    val a = from.getValue(id); val b = destinations.getValue(id)
                    rects[id] = RectF(a.left+(b.left-a.left)*f, a.top+(b.top-a.top)*f,
                        a.right+(b.right-a.right)*f, a.bottom+(b.bottom-a.bottom)*f)
                }
                requestLayout(); invalidate()
            }
            start()
        }
    }
    private fun updateDrag() {
        val t = active ?: return
        val y = startY + dy
        if (!resizing) {
            // Use the original boundary for this gesture to prevent height animation oscillation.
            val boundary = 2 * pad + CcTileLayouts.rows(base) * step - gap + gap / 2
            val lower = !t.slider && y > boundary
            if (targetLower != lower) {
                targetLower = lower
                shapeAnimator?.cancel()
                shapeAnimator = ValueAnimator.ofFloat(compactness, if (lower) 1f else 0f).apply {
                    duration = 220
                    interpolator = android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f)
                    addUpdateListener { compactness = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
            if (targetLower) {
                animateTo(if (sourceLower) base else CcTileLayouts.remove(base, t.id))
                invalidate(); return
            }
            val col = ((startX + dx - pad - cell / 2) / step).roundToInt().coerceIn(0, 4 - t.w)
            val row = ((y - pad - cell / 2) / step).roundToInt().coerceIn(0, CcTileLayouts.rows(base))
            animateTo(if (sourceLower) CcTileLayouts.add(base, t, col, row)
                else CcTileLayouts.solve(base, t.id, (t.col + dx / step).roundToInt(), (t.row + dy / step).roundToInt(), t.w, t.h))
        } else {
            val w = ((t.w * step + dx) / step).roundToInt().coerceIn(1, 4)
            val h = ((t.h * step + dy) / step).roundToInt().coerceIn(1, if (t.id == "media") 3 else 2)
            animateTo(CcTileLayouts.solve(base, t.id, minOf(t.col, 4-w), t.row, w, h))
        }
        invalidate()
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val upper = tiles.lastOrNull { rect(it).contains(e.x, e.y) }
                val lower = if (upper == null) lowerIds.lastOrNull {
                    val r = lowerRect(it); r.bottom += labelHeight; r.contains(e.x, e.y)
                } else null
                val hit = upper ?: lower?.let { id ->
                    val size = sizes[id] ?: (if(id == "media") 2 to 2 else 1 to 1)
                    CcTile(id, 0, 0, size.first, size.second)
                } ?: return false
                animator?.end(); rects.clear()
                base = tiles; baseSizes = sizes; baseAvailable = available
                active = hit; sourceLower = lower != null; targetLower = sourceLower
                shapeAnimator?.cancel(); compactness = if (sourceLower) 1f else 0f
                origin = if (sourceLower) lowerRect(hit.id) else rect(hit)
                pointer = e.getPointerId(0); startX = e.x; startY = e.y; rawPointerY = e.rawY; dx = 0f; dy = 0f
                resizing = !sourceLower && !hit.slider && e.x >= origin.right - 32 * density && e.y >= origin.bottom - 32 * density
                if (resizing) parent?.requestDisallowInterceptTouchEvent(true) else uiHandler.postDelayed(longPress, 220)
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = e.findPointerIndex(pointer)
                if (active == null) return false
                if (index < 0) { cancelGesture(); return true }
                dx = e.getX(index) - startX; dy = e.getY(index) - startY; rawPointerY = e.rawY
                if (!dragging && !resizing) {
                    if (hypot(dx, dy) > ViewConfiguration.get(context).scaledTouchSlop) cancelGesture()
                    return true
                }
                updateDrag(); return true
            }
            MotionEvent.ACTION_UP -> {
                if (active == null) return true
                val changed = dragging || resizing
                uiHandler.removeCallbacks(longPress); removeCallbacks(autoScroll)
                shapeAnimator?.cancel()
                if (changed) {
                    sizes = sizes + base.filterNot { it.slider }.associate { it.id to (it.w to it.h) } +
                        tiles.filterNot { it.slider }.associate { it.id to (it.w to it.h) }
                }
                active = null; dragging = false; resizing = false; pointer = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                if (changed) { animateTo(tiles); onCommit(tiles, sizes) } else performClick()
                invalidate(); return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { cancelGesture(); return true }
        }
        return true
    }
    private fun cancelGesture() {
        uiHandler.removeCallbacks(longPress); removeCallbacks(autoScroll)
        if (active != null) {
            animator?.cancel(); shapeAnimator?.cancel(); tiles = base; sizes = baseSizes; available = baseAvailable
            rects.clear(); active = null; dragging = false; resizing = false; pointer = -1; transition = 1f
            upperHeight = upperTarget(); lowerHeight = lowerTarget()
            parent?.requestDisallowInterceptTouchEvent(false)
            requestLayout(); invalidate()
        }
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
