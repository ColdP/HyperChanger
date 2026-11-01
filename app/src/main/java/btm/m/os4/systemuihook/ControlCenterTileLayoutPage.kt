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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.*

internal fun ccTileTitle(id: String) = when (id) {
    "wlan" -> tr("cc_layout_wlan", "WLAN")
    "data" -> tr("cc_layout_data", "数据网络")
    "media" -> tr("cc_layout_media", "媒体播放")
    "brightness" -> tr("cc_layout_brightness", "亮度")
    else -> tr("cc_layout_volume", "音量")
}

@Composable
internal fun ControlCenterTileLayoutPage(back: () -> Unit) = AppPage(
    tr("cc_layout_title", "控制中心磁贴布局自定义"), back, restartScopes = setOf(ScopeApplication.SYSTEM_UI),
) { padding, scroll ->
    val context = LocalContext.current
    val service by HookApplication.service.collectAsState()
    val store = remember(context) { ControlCenterTileLayoutSettingsStore(context) }
    var settings by remember { mutableStateOf(store.settings) }
    var preview by remember { mutableStateOf(settings.tiles) }
    var confirmReset by remember { mutableStateOf(false) }
    val scheme = MiuixTheme.colorScheme
    val tileColor = lerp(scheme.surfaceContainer, scheme.primary, .08f)
    val editorColors = CcEditorColors(
        tile = tileColor.toArgb(), activeTile = lerp(tileColor, scheme.primary, .14f).toArgb(),
        foreground = scheme.onSurface.toArgb(), media = scheme.onSurfaceVariantSummary.toArgb(),
        guide = scheme.onSurface.copy(alpha = .12f).toArgb(), drop = scheme.primary.copy(alpha = .55f).toArgb(),
        handle = scheme.onSurface.toArgb(), badge = scheme.onSurface.toArgb(), badgeText = scheme.surface.toArgb(),
    )
    LaunchedEffect(service) {
        service?.let(store::syncRemote)
        settings = store.settings
        preview = settings.tiles
    }
    AppList(padding, scroll, 28) {
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
                ArrowPreference(title = tr("cc_layout_reset", "还原初始布局"), onClick = { confirmReset = true })
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), cornerRadius = 22.5.dp, insideMargin = PaddingValues(0.dp)) {
                val rows = CcTileLayouts.rows(preview)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val cell = (maxWidth - 24.dp - 36.dp) / 4
                    AndroidView(
                        factory = { CcLayoutEditorView(it) },
                        modifier = Modifier.fillMaxWidth().height(24.dp + cell * rows + 12.dp * (rows - 1)),
                        update = { editor ->
                            editor.setColors(editorColors)
                            editor.onPreview = { tiles -> preview = tiles }
                            editor.onCommit = { tiles ->
                                settings = settings.copy(tiles = tiles)
                                store.update(service, settings)
                            }
                            editor.setTiles(settings.tiles)
                        },
                        onRelease = { it.release() },
                    )
                }
            }
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
                    settings = settings.copy(tiles = CcTileLayouts.initial)
                    preview = settings.tiles
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
    val guide: Int, val drop: Int, val handle: Int, val badge: Int, val badgeText: Int,
)

/** Offline native version of edit-control-center.html; no WebView, network or system editing. */
internal class CcLayoutEditorView(context: Context) : View(context) {
    var onPreview: (List<CcTile>) -> Unit = {}
    var onCommit: (List<CcTile>) -> Unit = {}
    private var tiles = CcTileLayouts.initial
    private var base = tiles
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val uiHandler = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val pad get() = 12f * density
    // Concentric corners: 22.5dp MIUIX card minus its 12dp grid inset.
    private val tileRadius get() = 10.5f * density
    private var colors: CcEditorColors? = null
    private val gap get() = 12f * density
    private val cell get() = (width - 2 * pad - 3 * gap) / 4f
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
    private val longPress = Runnable {
        if (active != null) {
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            report()
            invalidate()
        }
    }
    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    fun setTiles(value: List<CcTile>) {
        if (active == null && tiles != value) { tiles = value; rects.clear(); invalidate() }
    }
    fun setColors(value: CcEditorColors) {
        if (colors != value) { colors = value; invalidate() }
    }
    fun release() { uiHandler.removeCallbacks(longPress); animator?.cancel() }
    override fun onDetachedFromWindow() { cancelGesture(); release(); super.onDetachedFromWindow() }
    private fun rect(t: CcTile) = RectF(pad + t.col * step, pad + t.row * step,
        pad + t.col * step + t.w * cell + (t.w - 1) * gap, pad + t.row * step + t.h * cell + (t.h - 1) * gap)

    private fun round(canvas: Canvas, r: RectF, radius: Float, color: Int) {
        paint.style = Paint.Style.FILL; paint.color = color
        canvas.drawRoundRect(r, radius, radius, paint)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cell <= 0f) return
        val palette = colors ?: return
        if (dragging || resizing) {
            paint.style = Paint.Style.STROKE; paint.color = palette.guide; paint.strokeWidth = density
            paint.pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
            for (r in 0 until CcTileLayouts.rows(tiles)) for (c in 0..3) canvas.drawRoundRect(rect(CcTile("", c, r, 1, 1)), tileRadius, tileRadius, paint)
            tiles.firstOrNull { it.id == active?.id }?.let {
                paint.color = palette.drop; paint.strokeWidth = 2f * density
                canvas.drawRoundRect(rect(it), tileRadius, tileRadius, paint)
            }
            paint.pathEffect = null
        }
        tiles.filter { it.id != active?.id }.forEach { drawTile(canvas, it, rects[it.id] ?: rect(it)) }
        tiles.firstOrNull { it.id == active?.id }?.let { tile ->
            val r = rect(active!!)
            if (dragging) r.offset(dx, dy)
            if (resizing) {
                r.right = r.left + (r.width() + dx).coerceIn(cell * .75f, 4 * cell + 3 * gap)
                r.bottom = r.top + (r.height() + dy).coerceIn(cell * .75f, 3 * cell + 2 * gap)
            }
            drawTile(canvas, tile, r)
        }
    }
    private fun drawTile(c: Canvas, t: CcTile, r: RectF) {
        val palette = colors ?: return
        val radius = minOf(tileRadius, minOf(r.width(), r.height()) / 2)
        round(c, r, radius, if (active?.id == t.id && (dragging || resizing)) palette.activeTile else palette.tile)
        c.save(); c.clipPath(Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) })
        val scale = minOf(1f, cell / (88f * density))
        val u = density * scale
        if (t.slider) {
            icon(c, t.id, r.centerX() - 16 * u, r.bottom - 48 * u, 32 * u)
        } else if (t.id == "media") {
            val gray = palette.media
            fun block(x: Float, y: Float, w: Float, h: Float, rad: Float) = round(c, RectF(x, y, x + maxOf(0f, w), y + h), rad, gray)
            when {
                t.w == 1 && t.h == 1 -> { val s = minOf(44 * u, r.width() * .6f); block(r.centerX() - s / 2, r.centerY() - s / 2, s, s, 14 * u) }
                t.h == 1 || t.w >= 3 -> {
                    val s = minOf(r.height() - 32 * u, r.width() * .43f)
                    val x = r.left + 18 * u; val y = r.centerY() - s / 2
                    block(x, y, s, s, 18 * u)
                    val lineX = x + s + 16 * u; val lineW = r.right - lineX - 18 * u
                    block(lineX, r.centerY() - 18 * u, lineW, 16 * u, 8 * u)
                    block(lineX, r.centerY() + 8 * u, lineW * .6f, 14 * u, 7 * u)
                }
                else -> {
                    val x = r.left + 20 * u; val y = r.top + 20 * u
                    val s = minOf(68 * u, r.width() - 40 * u)
                    block(x, y, s, s, 18 * u)
                    block(x, y + s + 16 * u, (r.width() - 40 * u) * .74f, 18 * u, 9 * u)
                    block(x, y + s + 44 * u, (r.width() - 40 * u) * .42f, 16 * u, 8 * u)
                }
            }
        } else {
            val vertical = t.w == 1
            val iconSize = 32 * u
            val x = if (vertical) r.centerX() - iconSize / 2 else r.left + 20 * u
            val y = if (vertical) r.centerY() - 30 * u else r.centerY() - iconSize / 2
            icon(c, t.id, x, y, iconSize)
            paint.color = palette.foreground; paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = (if (vertical) 11 else 21) * u
            paint.style = Paint.Style.FILL
            val available = if (vertical) r.width() - 12 * u else r.right - (x + iconSize + 12 * u) - 12 * u
            val title = android.text.TextUtils.ellipsize(ccTileTitle(t.id), android.text.TextPaint(paint), available.coerceAtLeast(0f), android.text.TextUtils.TruncateAt.END).toString()
            paint.textAlign = if (vertical) Paint.Align.CENTER else Paint.Align.LEFT
            c.drawText(title, if (vertical) r.centerX() else x + iconSize + 12 * u,
                if (vertical) y + iconSize + 17 * u else r.centerY() - (paint.ascent() + paint.descent()) / 2, paint)
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
        c.save(); c.translate(x, y); c.scale(s / 36f, s / 36f)
        paint.color = colors?.foreground ?: return; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        fun path(block: Path.() -> Unit) { c.drawPath(Path().apply(block), paint) }
        when (id) {
            "wlan" -> {
                path { moveTo(3f, 12f); quadTo(18f, -2f, 33f, 12f) }
                path { moveTo(8.5f, 18f); quadTo(18f, 9f, 27.5f, 18f) }
                paint.style = Paint.Style.FILL; c.drawCircle(18f, 25f, 2.7f, paint)
            }
            "data" -> {
                path { moveTo(11.5f, 25f); lineTo(11.5f, 7f); moveTo(5.5f, 13f); lineTo(11.5f, 7f); lineTo(17.5f, 13f) }
                path { moveTo(24.5f, 11f); lineTo(24.5f, 29f); moveTo(18.5f, 23f); lineTo(24.5f, 29f); lineTo(30.5f, 23f) }
            }
            "brightness" -> {
                c.drawCircle(18f,18f,6f,paint)
                repeat(8) { i -> val a = i * PI / 4; c.drawLine(18 + 11f * cos(a).toFloat(),18 + 11f * sin(a).toFloat(),18 + 15f * cos(a).toFloat(),18 + 15f * sin(a).toFloat(),paint) }
            }
            else -> {
                path { moveTo(7.5f,14.5f); lineTo(12.5f,14.5f); lineTo(19f,9f); lineTo(19f,27f); lineTo(12.5f,21.5f); lineTo(7.5f,21.5f); close() }
                path { moveTo(23.5f,13.5f); quadTo(27f,18f,23.5f,22.5f) }
                path { moveTo(27f,10f); quadTo(34f,18f,27f,26f) }
            }
        }
        c.restore()
    }
    private fun report() {
        onPreview(tiles)
    }
    private fun animateTo(next: List<CcTile>) {
        if (next == tiles) return
        val from = tiles.associate { it.id to RectF(rects[it.id] ?: rect(it)) }
        animator?.cancel(); tiles = next
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            addUpdateListener {
                val f = it.animatedValue as Float
                next.forEach { t -> val a = from.getValue(t.id); val b = rect(t)
                    rects[t.id] = RectF(a.left + (b.left-a.left)*f, a.top+(b.top-a.top)*f, a.right+(b.right-a.right)*f, a.bottom+(b.bottom-a.bottom)*f)
                }
                invalidate()
            }
            start()
        }
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = tiles.lastOrNull { rect(it).contains(e.x,e.y) } ?: return false
                base = tiles; active = hit; pointer = e.getPointerId(0); startX = e.x; startY = e.y; dx = 0f; dy = 0f
                val r = rect(hit)
                resizing = !hit.slider && e.x >= r.right - 44 * density && e.y >= r.bottom - 44 * density
                if (resizing) { parent?.requestDisallowInterceptTouchEvent(true); report() } else uiHandler.postDelayed(longPress, 220)
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = e.findPointerIndex(pointer)
                val t = active ?: return false
                if (index < 0) { cancelGesture(); return true }
                dx = e.getX(index) - startX; dy = e.getY(index) - startY
                if (!dragging && !resizing) {
                    if (hypot(dx,dy) > ViewConfiguration.get(context).scaledTouchSlop) cancelGesture()
                    return true
                }
                val w = if (resizing) ((t.w * step + dx) / step).roundToInt().coerceIn(1,4) else t.w
                val h = if (resizing) ((t.h * step + dy) / step).roundToInt().coerceIn(1, if(t.id == "media") 3 else 2) else t.h
                val col = if (resizing) minOf(t.col,4-w) else (t.col + dx / step).roundToInt()
                val row = if (resizing) t.row else (t.row + dy / step).roundToInt().coerceAtMost(CcTileLayouts.rows(base))
                animateTo(CcTileLayouts.solve(base,t.id,col,row,w,h)); report(); invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (active == null) return true
                val changed = dragging || resizing
                uiHandler.removeCallbacks(longPress); active = null; dragging = false; resizing = false; pointer = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                if (changed) { onCommit(tiles); onPreview(tiles) } else performClick()
                invalidate(); return true
            }
            MotionEvent.ACTION_CANCEL -> { cancelGesture(); return true }
            MotionEvent.ACTION_POINTER_DOWN -> { cancelGesture(); return true }
        }
        return true
    }
    private fun cancelGesture() {
        uiHandler.removeCallbacks(longPress)
        if (active != null) {
            animator?.cancel(); tiles = base; rects.clear(); active = null; dragging = false; resizing = false; pointer = -1
            parent?.requestDisallowInterceptTouchEvent(false)
            onPreview(tiles); invalidate()
        }
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
