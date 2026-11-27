// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.text.TextUtils
import android.util.SparseArray
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class ControlCenterSignalSim(
    val slot: Int,
    val level: Int,
    val inService: Boolean,
    val carrier: String,
    val networkType: String,
    val dataConnected: Boolean,
)

internal data class ControlCenterSignalSnapshot(
    val airplaneMode: Boolean,
    val wifiConnected: Boolean,
    val wifiIsDefault: Boolean,
    val wifiLevel: Int,
    val sims: List<ControlCenterSignalSim>,
    val dataSlot: Int,
    val noSimLabel: String,
) {
    val isDualSim: Boolean get() = !airplaneMode && sims.size >= 2
}

/**
 * Renders the iOS-style Control Center header signal block for both single-SIM and dual-SIM:
 * - Single SIM: `[signal] Carrier [Wi-Fi / 5G]`
 * - Dual SIM:
 *     `[signal] [1/custom] Carrier1 [Wi-Fi / 5G]`
 *     `[signal] [2/custom] Carrier2 [5G]`
 * - Airplane mode: `[airplane] [Wi-Fi?]`
 * - No SIM: `[dim signal] No SIM [Wi-Fi?]`
 */
internal class ControlCenterTopSignalView(context: Context) : LinearLayout(context) {
    private val density = context.resources.displayMetrics.density
    private val row1 = SimRow(context)
    private val row2 = SimRow(context)
    private var lastSnapshot: ControlCenterSignalSnapshot? = null
    private var lastIconStyle: Int = -1
    private var lastSim1Label: String? = null
    private var lastSim2Label: String? = null
    private var lastColor: Int = Color.WHITE
    private var lastTypeface: Typeface? = null
    private var lastMaxRow1Width: Int = -1
    private var lastMaxRow2Width: Int = -1

    init {
        tag = "hyperchanger.control-center.ios-signal"
        id = View.generateViewId()
        orientation = VERTICAL
        gravity = Gravity.START
        clipChildren = false
        clipToPadding = false
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        addView(row1, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(
            row2,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3f)
            },
        )
        row2.visibility = View.GONE
    }

    fun invalidateIcons() {
        StatusBarSignalDrawableLoader.clearCache()
        lastSnapshot = null
    }

    fun applyStyle(
        color: Int,
        typeface: Typeface?,
        direction: Int,
        maxRow1WidthPx: Int,
        maxRow2WidthPx: Int = maxRow1WidthPx,
    ) {
        if (layoutDirection != direction) layoutDirection = direction
        val clampedRow1 = maxRow1WidthPx.coerceAtLeast(dp(48f))
        val clampedRow2 = maxRow2WidthPx.coerceAtLeast(dp(48f))
        if (lastColor == color &&
            lastTypeface === typeface &&
            lastMaxRow1Width == clampedRow1 &&
            lastMaxRow2Width == clampedRow2
        ) {
            return
        }
        val colorChanged = lastColor != color
        val typefaceChanged = lastTypeface !== typeface
        lastColor = color
        lastTypeface = typeface
        lastMaxRow1Width = clampedRow1
        lastMaxRow2Width = clampedRow2
        row1.applyStyle(color, typeface, clampedRow1)
        row2.applyStyle(color, typeface, clampedRow2)
        if (colorChanged || typefaceChanged) {
            // Re-resolve status bar tint & cap-height adaptive icon sizing.
            lastSnapshot = null
        }
    }

    fun bind(snapshot: ControlCenterSignalSnapshot, options: ControlCenterTopContentSettings) {
        val iconStyle = options.signalIconStyle
        val sim1Custom = options.sim1Label
        val sim2Custom = options.sim2Label
        if (lastSnapshot == snapshot &&
            lastIconStyle == iconStyle &&
            lastSim1Label == sim1Custom &&
            lastSim2Label == sim2Custom
        ) {
            return
        }
        lastSnapshot = snapshot
        lastIconStyle = iconStyle
        lastSim1Label = sim1Custom
        lastSim2Label = sim2Custom

        val showWifi = snapshot.wifiConnected && (snapshot.wifiIsDefault || snapshot.sims.none { it.dataConnected })

        if (snapshot.airplaneMode) {
            row1.bindAirplane(
                iconStyle = iconStyle,
                showWifi = snapshot.wifiConnected,
                wifiLevel = snapshot.wifiLevel,
            )
            row1.visibility = View.VISIBLE
            row2.visibility = View.GONE
            return
        }

        if (snapshot.sims.isEmpty()) {
            row1.bindNoSim(
                iconStyle = iconStyle,
                label = snapshot.noSimLabel,
                showWifi = snapshot.wifiConnected,
                wifiLevel = snapshot.wifiLevel,
            )
            row1.visibility = View.VISIBLE
            row2.visibility = View.GONE
            return
        }

        if (snapshot.sims.size == 1) {
            val sim = snapshot.sims[0]
            val netType = if (!showWifi && sim.dataConnected) sim.networkType else ""
            row1.bindSim(
                iconStyle = iconStyle,
                sim = sim,
                badgeLabel = null,
                networkType = netType,
                showWifi = showWifi,
                wifiLevel = snapshot.wifiLevel,
            )
            row1.visibility = View.VISIBLE
            row2.visibility = View.GONE
            return
        }

        val first = snapshot.sims[0]
        val second = snapshot.sims[1]
        val firstNet = if (!showWifi && first.dataConnected) first.networkType else ""
        val secondNet = if (!showWifi && second.dataConnected) second.networkType else ""
        val firstBadge = resolveDualSimBadge(first.slot, sim1Custom, sim2Custom)
        val secondBadge = resolveDualSimBadge(second.slot, sim1Custom, sim2Custom)
        // In iOS dual-SIM mode, show Wi-Fi on the top row (or data row if Wi-Fi is off).
        row1.bindSim(
            iconStyle = iconStyle,
            sim = first,
            badgeLabel = firstBadge,
            networkType = firstNet,
            showWifi = showWifi,
            wifiLevel = snapshot.wifiLevel,
        )
        row2.bindSim(
            iconStyle = iconStyle,
            sim = second,
            badgeLabel = secondBadge,
            networkType = secondNet,
            showWifi = false,
            wifiLevel = snapshot.wifiLevel,
        )
        row1.visibility = View.VISIBLE
        row2.visibility = View.VISIBLE
    }

    fun firstRowCenterOffsetPx(): Float {
        val h1 = row1.measuredHeight
        return if (h1 > 0) h1 / 2f else measuredHeight / 2f
    }

    fun secondRowCenterOffsetPx(): Float {
        if (row2.visibility != View.VISIBLE || row2.measuredHeight <= 0) return firstRowCenterOffsetPx()
        val row2TopMargin = (row2.layoutParams as? MarginLayoutParams)?.topMargin ?: 0
        return row1.measuredHeight + row2TopMargin + row2.measuredHeight / 2f
    }

    private fun resolveDualSimBadge(slot: Int, sim1Custom: String, sim2Custom: String): String = when (slot) {
        0 -> sim1Custom.ifBlank { "1" }
        1 -> sim2Custom.ifBlank { "2" }
        else -> (slot + 1).toString()
    }

    private fun dp(value: Float): Int = (value * density).roundToInt()

    private inner class SimRow(context: Context) : LinearLayout(context) {
        private val barsDrawable = IosSignalBarsDrawable()
        private val dotsDrawable = IosDotsSignalDrawable()
        private val airplaneDrawable = IosAirplaneDrawable()
        private val badgeDrawable = IosSimBadgeDrawable()
        private val wifiDrawable = IosWifiDrawable()
        private val capRect = Rect()
        private var currentColor = Color.WHITE
        private var maxRowWidthPx = dp(180f)
        private var lastSignalWidthPx = dp(17f)
        private var lastBadgeWidthPx = 0

        private val signalIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(barsDrawable)
        }
        private val badgeIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageDrawable(badgeDrawable)
            visibility = View.GONE
        }
        private val carrierText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setTextColor(Color.WHITE)
        }
        private val networkTypeText = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setSingleLine(true)
            includeFontPadding = false
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }
        private val wifiIcon = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(wifiDrawable)
            visibility = View.GONE
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false

            addView(
                signalIcon,
                LayoutParams(dp(17f), dp(11f)).apply {
                    marginEnd = dp(4.5f)
                },
            )
            addView(
                badgeIcon,
                LayoutParams(dp(13.5f), dp(13.5f)).apply {
                    marginEnd = dp(4.5f)
                },
            )
            addView(
                carrierText,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
            addView(
                networkTypeText,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(4.5f)
                },
            )
            addView(
                wifiIcon,
                LayoutParams(dp(15f), dp(11f)).apply {
                    marginStart = dp(5f)
                },
            )
        }

        fun applyStyle(color: Int, typeface: Typeface?, maxRowWidthPx: Int) {
            currentColor = color
            this.maxRowWidthPx = maxRowWidthPx
            barsDrawable.setTintColor(color)
            dotsDrawable.setTintColor(color)
            airplaneDrawable.setTintColor(color)
            badgeDrawable.setTintColor(color)
            wifiDrawable.setTintColor(color)
            carrierText.setTextColor(color)
            networkTypeText.setTextColor(color)
            val resolvedTypeface = typeface ?: Typeface.DEFAULT_BOLD
            carrierText.typeface = resolvedTypeface
            networkTypeText.typeface = resolvedTypeface
            updateCarrierMaxWidth()
        }

        fun bindSim(
            iconStyle: Int,
            sim: ControlCenterSignalSim,
            badgeLabel: String?,
            networkType: String,
            showWifi: Boolean,
            wifiLevel: Int,
        ) {
            applySignalIcon(iconStyle = iconStyle, level = sim.level, inService = sim.inService)
            signalIcon.visibility = View.VISIBLE

            if (badgeLabel != null) {
                badgeDrawable.setLabel(badgeLabel)
                val badgeH = (measureCarrierCapHeightPx() * 1.32f).roundToInt().coerceIn(dp(12f), dp(17f))
                val badgeW = badgeDrawable.measureWidthPx(badgeH, density)
                setViewSize(badgeIcon, badgeW, badgeH)
                lastBadgeWidthPx = badgeW + dp(4.5f)
                badgeIcon.visibility = View.VISIBLE
            } else {
                lastBadgeWidthPx = 0
                badgeIcon.visibility = View.GONE
            }

            carrierText.text = sim.carrier
            carrierText.visibility = if (sim.carrier.isNotBlank()) View.VISIBLE else View.GONE

            if (showWifi) {
                networkTypeText.visibility = View.GONE
                applyWifiIcon(iconStyle = iconStyle, wifiLevel = wifiLevel)
                wifiIcon.visibility = View.VISIBLE
            } else if (networkType.isNotBlank()) {
                wifiIcon.visibility = View.GONE
                networkTypeText.text = networkType
                networkTypeText.visibility = View.VISIBLE
            } else {
                wifiIcon.visibility = View.GONE
                networkTypeText.visibility = View.GONE
            }
            updateCarrierMaxWidth()
        }

        fun bindAirplane(iconStyle: Int, showWifi: Boolean, wifiLevel: Int) {
            val size = (measureCarrierCapHeightPx() * 1.35f).roundToInt().coerceIn(dp(12f), dp(17f))
            setViewSize(signalIcon, size, size)
            signalIcon.scaleType = ImageView.ScaleType.FIT_CENTER
            if (signalIcon.drawable !== airplaneDrawable) signalIcon.setImageDrawable(airplaneDrawable)
            lastSignalWidthPx = size
            lastBadgeWidthPx = 0
            signalIcon.visibility = View.VISIBLE
            badgeIcon.visibility = View.GONE
            carrierText.visibility = View.GONE
            networkTypeText.visibility = View.GONE
            if (showWifi) {
                applyWifiIcon(iconStyle = iconStyle, wifiLevel = wifiLevel)
                wifiIcon.visibility = View.VISIBLE
            } else {
                wifiIcon.visibility = View.GONE
            }
        }

        fun bindNoSim(iconStyle: Int, label: String, showWifi: Boolean, wifiLevel: Int) {
            applySignalIcon(iconStyle = iconStyle, level = 0, inService = false)
            signalIcon.visibility = View.VISIBLE
            lastBadgeWidthPx = 0
            badgeIcon.visibility = View.GONE
            carrierText.text = label
            carrierText.visibility = if (label.isNotBlank()) View.VISIBLE else View.GONE
            networkTypeText.visibility = View.GONE
            if (showWifi) {
                applyWifiIcon(iconStyle = iconStyle, wifiLevel = wifiLevel)
                wifiIcon.visibility = View.VISIBLE
            } else {
                wifiIcon.visibility = View.GONE
            }
            updateCarrierMaxWidth()
        }

        /** Adaptive visual height anchored to the live carrier text cap-height across font scales & DPIs. */
        private fun measureCarrierCapHeightPx(): Float {
            carrierText.paint.getTextBounds("H", 0, 1, capRect)
            val measured = capRect.height().toFloat()
            return if (measured > 2f) measured else carrierText.textSize * 0.74f
        }

        private fun targetSignalBarHeightPx(): Int =
            (measureCarrierCapHeightPx() * 1.12f).roundToInt().coerceIn(dp(9.5f), dp(15.5f))

        private fun applySignalIcon(iconStyle: Int, level: Int, inService: Boolean) {
            val targetH = targetSignalBarHeightPx()
            when (iconStyle) {
                CONTROL_CENTER_SIGNAL_ICON_FOLLOW_STATUS_BAR -> {
                    val loaded = StatusBarSignalDrawableLoader.loadMobile(
                        context = context,
                        level = level,
                        inService = inService,
                        tintColor = currentColor,
                    )
                    if (loaded != null) {
                        val (w, h) = computeAdaptiveGlyphSize(
                            visualAspect = loaded.visualAspect,
                            targetHeightPx = targetH,
                            maxWideWidthPx = (targetH * 3.1f).roundToInt().coerceIn(dp(28f), dp(40f)),
                        )
                        setViewSize(signalIcon, w, h)
                        signalIcon.scaleType = ImageView.ScaleType.FIT_XY
                        signalIcon.setImageDrawable(loaded.drawable)
                        lastSignalWidthPx = w
                        return
                    }
                    applyIosBars(level, inService, targetH)
                }
                CONTROL_CENTER_SIGNAL_ICON_DOTS -> {
                    val targetW = (targetH * 3.05f).roundToInt().coerceIn(dp(28f), dp(42f))
                    setViewSize(signalIcon, targetW, targetH)
                    signalIcon.scaleType = ImageView.ScaleType.FIT_XY
                    if (signalIcon.drawable !== dotsDrawable) signalIcon.setImageDrawable(dotsDrawable)
                    dotsDrawable.update(level = level, inService = inService)
                    lastSignalWidthPx = targetW
                }
                else -> applyIosBars(level, inService, targetH)
            }
        }

        private fun applyIosBars(level: Int, inService: Boolean, targetH: Int) {
            val targetW = (targetH * 1.54f).roundToInt().coerceIn(dp(14f), dp(24f))
            setViewSize(signalIcon, targetW, targetH)
            signalIcon.scaleType = ImageView.ScaleType.FIT_XY
            if (signalIcon.drawable !== barsDrawable) signalIcon.setImageDrawable(barsDrawable)
            barsDrawable.update(level = level, inService = inService)
            lastSignalWidthPx = targetW
        }

        private fun applyWifiIcon(iconStyle: Int, wifiLevel: Int) {
            val targetH = targetSignalBarHeightPx()
            if (iconStyle == CONTROL_CENTER_SIGNAL_ICON_FOLLOW_STATUS_BAR) {
                val loaded = StatusBarSignalDrawableLoader.loadWifi(
                    context = context,
                    level = wifiLevel,
                    tintColor = currentColor,
                )
                if (loaded != null) {
                    val (w, h) = computeAdaptiveGlyphSize(
                        visualAspect = loaded.visualAspect,
                        targetHeightPx = targetH,
                        maxWideWidthPx = (targetH * 2.1f).roundToInt().coerceIn(dp(18f), dp(28f)),
                    )
                    setViewSize(wifiIcon, w, h)
                    wifiIcon.scaleType = ImageView.ScaleType.FIT_XY
                    wifiIcon.setImageDrawable(loaded.drawable)
                    return
                }
            }
            val targetW = (targetH * 1.36f).roundToInt().coerceIn(dp(12f), dp(21f))
            setViewSize(wifiIcon, targetW, targetH)
            wifiIcon.scaleType = ImageView.ScaleType.FIT_XY
            if (wifiIcon.drawable !== wifiDrawable) wifiIcon.setImageDrawable(wifiDrawable)
            wifiDrawable.setSignalLevel(wifiLevel)
        }

        /**
         * Normalizes any cropped status bar glyph (OEM, theme, or imported PNG/vector) to match
         * the carrier text visual height while preserving its real aspect ratio—handling both
         * standard bar icons and wide dot-row packs without becoming oversized or undersized.
         */
        private fun computeAdaptiveGlyphSize(
            visualAspect: Float,
            targetHeightPx: Int,
            maxWideWidthPx: Int,
        ): Pair<Int, Int> {
            val aspect = visualAspect.coerceIn(0.65f, 6.0f)
            return if (aspect <= 2.2f) {
                val w = (targetHeightPx * aspect).roundToInt().coerceIn(dp(9f), maxWideWidthPx)
                w to targetHeightPx
            } else {
                // Wide flat glyphs (e.g. imported iOS 10 5-dot icons): cap width so dot diameter stays natural.
                val w = (targetHeightPx * aspect).roundToInt().coerceAtMost(maxWideWidthPx)
                val h = (w / aspect).roundToInt().coerceIn(dp(5f), targetHeightPx)
                w to h
            }
        }

        private fun setViewSize(view: View, width: Int, height: Int) {
            val lp = view.layoutParams as LayoutParams
            if (lp.width != width || lp.height != height) {
                lp.width = width
                lp.height = height
                view.layoutParams = lp
            }
        }

        private fun updateCarrierMaxWidth() {
            val trailingReserve = dp(34f)
            val reserved = lastSignalWidthPx + dp(4.5f) + lastBadgeWidthPx + trailingReserve
            carrierText.maxWidth = (maxRowWidthPx - reserved).coerceAtLeast(dp(40f))
        }
    }
}

private data class NormalizedBounds(
    val leftFrac: Float,
    val topFrac: Float,
    val rightFrac: Float,
    val bottomFrac: Float,
    val visualAspect: Float,
)

private data class LoadedStatusGlyph(
    val drawable: Drawable,
    val visualAspect: Float,
)

/**
 * Draws only the tight non-transparent visual region of [source] stretched cleanly onto the
 * destination bounds, so arbitrary transparent padding in OEM/theme/imported status bar assets
 * never causes oversized or tiny icons in the Control Center header.
 */
private class TightBoundsDrawable(
    private val source: Drawable,
    private val crop: NormalizedBounds,
) : Drawable() {
    override fun draw(canvas: Canvas) {
        val dstW = bounds.width().toFloat()
        val dstH = bounds.height().toFloat()
        if (dstW <= 0f || dstH <= 0f) return

        val visWFrac = (crop.rightFrac - crop.leftFrac).coerceAtLeast(0.05f)
        val visHFrac = (crop.bottomFrac - crop.topFrac).coerceAtLeast(0.05f)
        val srcW = dstW / visWFrac
        val srcH = dstH / visHFrac
        val srcL = bounds.left - crop.leftFrac * srcW
        val srcT = bounds.top - crop.topFrac * srcH

        val save = canvas.save()
        canvas.clipRect(bounds)
        source.setBounds(
            srcL.roundToInt(),
            srcT.roundToInt(),
            (srcL + srcW).roundToInt(),
            (srcT + srcH).roundToInt(),
        )
        source.draw(canvas)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        source.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        source.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Loads SystemUI's live status bar mobile & Wi-Fi drawables and measures their tight visual bounds
 * so "Follow status bar" picks up OEM icons, MIUI themes, and HyperChanger's imported icon packs
 * at a consistent, adaptive visual size.
 */
private object StatusBarSignalDrawableLoader {
    private val boundsCache = LinkedHashMap<String, NormalizedBounds>(32, 0.75f, true)
    private val transformMethod by lazy {
        runCatching {
            val clazz = Class.forName("com.android.systemui.statusbar.MiuiStatusBarIconViewHelper")
            clazz.getDeclaredMethod(
                "transformResId",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    @Synchronized
    fun clearCache() {
        boundsCache.clear()
    }

    fun loadMobile(context: Context, level: Int, inService: Boolean, tintColor: Int): LoadedStatusGlyph? {
        val clamped = level.coerceIn(0, 4)
        val primaryName = if (inService) "stat_sys_signal_$clamped" else "stat_sys_signal_null"
        return loadSystemUiGlyph(context, primaryName, tintColor)
            ?: if (!inService) loadSystemUiGlyph(context, "stat_sys_signal_0", tintColor) else null
    }

    fun loadWifi(context: Context, level: Int, tintColor: Int): LoadedStatusGlyph? {
        // HyperOS stat_sys_wifi_signal_* uses 4 levels: 0..3.
        val mapped = (level - 1).coerceIn(0, 3)
        return loadSystemUiGlyph(context, "stat_sys_wifi_signal_$mapped", tintColor)
    }

    private fun loadSystemUiGlyph(context: Context, entryName: String, tintColor: Int): LoadedStatusGlyph? = runCatching {
        val resources = context.resources
        val baseId = resources.getIdentifier(entryName, "drawable", "com.android.systemui")
        if (baseId == 0) return@runCatching null
        val isLight = isLightColor(tintColor)
        val resolvedId = (transformMethod?.invoke(null, baseId, false, isLight) as? Int)
            ?.takeIf { it != 0 }
            ?: baseId
        val drawable = resources.getDrawable(resolvedId, context.theme)?.mutate() ?: return@runCatching null
        if (resolvedId == baseId && !isLight) {
            drawable.setTint(tintColor)
        }
        val cacheKey = "$resolvedId:${drawable.intrinsicWidth}x${drawable.intrinsicHeight}:${drawable.javaClass.name}:$isLight"
        val crop = synchronized(this) { boundsCache[cacheKey] } ?: measureTightBounds(drawable).also { measured ->
            synchronized(this) {
                if (boundsCache.size >= 48) {
                    val oldest = boundsCache.keys.firstOrNull()
                    if (oldest != null) boundsCache.remove(oldest)
                }
                boundsCache[cacheKey] = measured
            }
        }
        LoadedStatusGlyph(
            drawable = TightBoundsDrawable(drawable, crop),
            visualAspect = crop.visualAspect,
        )
    }.getOrNull()

    private fun measureTightBounds(drawable: Drawable): NormalizedBounds {
        val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: 64
        val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: 64
        val maxDim = 72f
        val scale = (maxDim / maxOf(iw, ih)).coerceAtMost(1f)
        val sampleW = (iw * scale).roundToInt().coerceIn(16, 72)
        val sampleH = (ih * scale).roundToInt().coerceIn(16, 72)

        val bitmap = Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, sampleW, sampleH)
            drawable.draw(canvas)
            val pixels = IntArray(sampleW * sampleH)
            bitmap.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH)
            var minX = sampleW
            var minY = sampleH
            var maxX = -1
            var maxY = -1
            for (y in 0 until sampleH) {
                val rowOffset = y * sampleW
                for (x in 0 until sampleW) {
                    val alpha = (pixels[rowOffset + x] ushr 24) and 0xFF
                    if (alpha > 18) {
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < minX || maxY < minY) {
                NormalizedBounds(0f, 0f, 1f, 1f, iw.toFloat() / ih.toFloat())
            } else {
                val left = minX / sampleW.toFloat()
                val top = minY / sampleH.toFloat()
                val right = (maxX + 1) / sampleW.toFloat()
                val bottom = (maxY + 1) / sampleH.toFloat()
                val visW = (maxX - minX + 1).toFloat()
                val visH = (maxY - minY + 1).toFloat()
                NormalizedBounds(left, top, right, bottom, visW / visH)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun isLightColor(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val luminance = (0.299 * r + 0.587 * g + 0.114 * b)
        return luminance >= 128.0
    }
}

/** 4-bar cellular signal glyph styled after Apple iOS (wide bars, tight spacing, subtle rounded corners). */
private class IosSignalBarsDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private var tintColor = Color.WHITE
    private var drawableAlpha = 255
    private var level = 0
    private var inService = true

    fun setTintColor(color: Int) {
        if (tintColor != color) {
            tintColor = color
            invalidateSelf()
        }
    }

    fun update(level: Int, inService: Boolean) {
        val clamped = level.coerceIn(0, 4)
        if (this.level != clamped || this.inService != inService) {
            this.level = clamped
            this.inService = inService
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val barCount = 4
        val gapRatio = 0.45f
        val barW = w / (barCount + (barCount - 1) * gapRatio)
        val gap = barW * gapRatio
        val radius = barW * 0.28f
        val activeAlpha = ((Color.alpha(tintColor) * drawableAlpha) / 255f).roundToInt().coerceIn(0, 255)
        val inactiveAlpha = (activeAlpha * 0.35f).roundToInt().coerceIn(0, 255)
        val baseColor = tintColor and 0x00FFFFFF

        for (i in 0 until barCount) {
            val barH = (h * BAR_HEIGHT_RATIOS[i]).coerceAtLeast(barW * 1.15f)
            val left = bounds.left + i * (barW + gap)
            val top = bounds.bottom - barH
            rect.set(left, top, left + barW, bounds.bottom.toFloat())
            val alpha = if (inService && i < level) activeAlpha else inactiveAlpha
            paint.color = baseColor or (alpha shl 24)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private val BAR_HEIGHT_RATIOS = floatArrayOf(0.38f, 0.57f, 0.78f, 1.0f)
    }
}

/** Classic iOS 10 five-dot signal indicator: filled circles for active bars, crisp hollow rings for inactive. */
private class IosDotsSignalDrawable : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var tintColor = Color.WHITE
    private var drawableAlpha = 255
    private var level = 0
    private var inService = true

    fun setTintColor(color: Int) {
        if (tintColor != color) {
            tintColor = color
            invalidateSelf()
        }
    }

    fun update(level: Int, inService: Boolean) {
        val clamped = level.coerceIn(0, 4)
        if (this.level != clamped || this.inService != inService) {
            this.level = clamped
            this.inService = inService
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val dotCount = 5
        val gapRatio = 0.34f
        val diameter = minOf(h * 0.54f, w / (dotCount + (dotCount - 1) * gapRatio))
        val gap = diameter * gapRatio
        val totalWidth = dotCount * diameter + (dotCount - 1) * gap
        val startX = bounds.left + (w - totalWidth) / 2f
        val cy = bounds.exactCenterY()
        val radius = diameter / 2f
        val stroke = (diameter * 0.16f).coerceAtLeast(1f)

        val activeAlpha = ((Color.alpha(tintColor) * drawableAlpha) / 255f).roundToInt().coerceIn(0, 255)
        val ringAlpha = (activeAlpha * 0.85f).roundToInt().coerceIn(0, 255)
        val baseColor = tintColor and 0x00FFFFFF

        fillPaint.color = baseColor or (activeAlpha shl 24)
        ringPaint.color = baseColor or (ringAlpha shl 24)
        ringPaint.strokeWidth = stroke

        val filledDots = if (!inService) {
            0
        } else {
            when (level) {
                0 -> 0
                1 -> 1
                2 -> 3
                3 -> 4
                else -> 5
            }
        }

        for (i in 0 until dotCount) {
            val cx = startX + radius + i * (diameter + gap)
            if (i < filledDots) {
                canvas.drawCircle(cx, cy, radius, fillPaint)
            } else {
                canvas.drawCircle(cx, cy, (radius - stroke / 2f).coerceAtLeast(0.5f), ringPaint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        ringPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Filled rounded pill/square badge with transparent knockout SIM label (`1` / `2` or custom text). */
private class IosSimBadgeDrawable : Drawable() {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        color = Color.BLACK
    }
    private val rect = RectF()
    private var tintColor = Color.WHITE
    private var drawableAlpha = 255
    private var label = "1"

    fun setTintColor(color: Int) {
        if (tintColor != color) {
            tintColor = color
            invalidateSelf()
        }
    }

    fun setLabel(text: String) {
        if (label != text) {
            label = text
            invalidateSelf()
        }
    }

    fun measureWidthPx(heightPx: Int, density: Float): Int {
        if (label.length <= 1) return heightPx
        cutPaint.textSize = heightPx * 0.68f
        val textWidth = cutPaint.measureText(label)
        val horizontalPadding = 5.5f * density
        return maxOf(heightPx, ceil((textWidth + horizontalPadding).toDouble()).toInt())
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val finalAlpha = ((Color.alpha(tintColor) * drawableAlpha * 0.88f) / 255f).roundToInt().coerceIn(0, 255)
        bgPaint.color = (tintColor and 0x00FFFFFF) or (finalAlpha shl 24)
        rect.set(bounds)
        val radius = minOf(w, h) * 0.24f

        val save = canvas.saveLayer(rect, null)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        cutPaint.textSize = h * (if (label.length <= 1) 0.72f else 0.66f)
        val fm = cutPaint.fontMetrics
        val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(label, rect.centerX(), baseline, cutPaint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 3-arc + dot Wi-Fi glyph styled after iOS Control Center. */
private class IosWifiDrawable : Drawable() {
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val oval = RectF()
    private var tintColor = Color.WHITE
    private var drawableAlpha = 255
    private var level = 4

    fun setTintColor(color: Int) {
        if (tintColor != color) {
            tintColor = color
            invalidateSelf()
        }
    }

    fun setSignalLevel(level: Int) {
        val clamped = level.coerceIn(0, 4)
        if (this.level != clamped) {
            this.level = clamped
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val activeAlpha = ((Color.alpha(tintColor) * drawableAlpha) / 255f).roundToInt().coerceIn(0, 255)
        val inactiveAlpha = (activeAlpha * 0.32f).roundToInt().coerceIn(0, 255)
        val baseColor = tintColor and 0x00FFFFFF

        val cx = bounds.exactCenterX()
        val stroke = (h * 0.145f).coerceAtLeast(1.2f)
        val dotRadius = stroke * 0.72f
        val cy = bounds.bottom - dotRadius

        arcPaint.strokeWidth = stroke

        dotPaint.color = baseColor or ((if (level >= 1) activeAlpha else inactiveAlpha) shl 24)
        canvas.drawCircle(cx, cy, dotRadius, dotPaint)

        val maxRadius = (h - dotRadius - stroke * 0.6f).coerceAtLeast(stroke * 2f)
        val radii = floatArrayOf(maxRadius * 0.36f, maxRadius * 0.67f, maxRadius)
        val startAngle = -133f
        val sweepAngle = 86f
        for (i in 0 until 3) {
            val r = radii[i]
            val lit = level >= (i + 2)
            arcPaint.color = baseColor or ((if (lit) activeAlpha else inactiveAlpha) shl 24)
            oval.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(oval, startAngle, sweepAngle, false, arcPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        arcPaint.colorFilter = colorFilter
        dotPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Clean airplane glyph for Airplane Mode in iOS Control Center header. */
private class IosAirplaneDrawable : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private var tintColor = Color.WHITE
    private var drawableAlpha = 255

    fun setTintColor(color: Int) {
        if (tintColor != color) {
            tintColor = color
            invalidateSelf()
        }
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val finalAlpha = ((Color.alpha(tintColor) * drawableAlpha) / 255f).roundToInt().coerceIn(0, 255)
        paint.color = (tintColor and 0x00FFFFFF) or (finalAlpha shl 24)

        val sx = w / 24f
        val sy = h / 24f
        path.reset()
        path.moveTo(bounds.left + 21f * sx, bounds.top + 12f * sy)
        path.lineTo(bounds.left + 15.5f * sx, bounds.top + 10.5f * sy)
        path.lineTo(bounds.left + 10.5f * sx, bounds.top + 2.5f * sy)
        path.lineTo(bounds.left + 8.5f * sx, bounds.top + 2.5f * sy)
        path.lineTo(bounds.left + 11f * sx, bounds.top + 10.5f * sy)
        path.lineTo(bounds.left + 5.5f * sx, bounds.top + 10.5f * sy)
        path.lineTo(bounds.left + 3.8f * sx, bounds.top + 8f * sy)
        path.lineTo(bounds.left + 2.3f * sx, bounds.top + 8f * sy)
        path.lineTo(bounds.left + 3.4f * sx, bounds.top + 12f * sy)
        path.lineTo(bounds.left + 2.3f * sx, bounds.top + 16f * sy)
        path.lineTo(bounds.left + 3.8f * sx, bounds.top + 16f * sy)
        path.lineTo(bounds.left + 5.5f * sx, bounds.top + 13.5f * sy)
        path.lineTo(bounds.left + 11f * sx, bounds.top + 13.5f * sy)
        path.lineTo(bounds.left + 8.5f * sx, bounds.top + 21.5f * sy)
        path.lineTo(bounds.left + 10.5f * sx, bounds.top + 21.5f * sy)
        path.lineTo(bounds.left + 15.5f * sx, bounds.top + 13.5f * sy)
        path.lineTo(bounds.left + 21f * sx, bounds.top + 12f * sy)
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Reads live cellular, carrier, network-type, Wi-Fi, and airplane state from SystemUI's
 * CombinedHeaderController hierarchy with resilient framework fallbacks.
 */
internal object ControlCenterSignalStateReader {
    fun read(
        context: Context,
        carrierLayout: View?,
    ): ControlCenterSignalSnapshot {
        val leftCarrier = carrierLayout?.let { readField(it, "leftCarrierTextView") }
        val carrierController = leftCarrier?.let { readField(it, "carrierTextController") }
        val hdController = leftCarrier?.let { readField(it, "hdController") }
        val miuiMobileIconsInt = hdController?.let { readField(it, "miuiMobileIconsInt") }
        val networkController = carrierController?.let { readField(it, "mNetworkController") }

        val airplaneMode = (carrierController?.let { readField(it, "mAirplane") as? Boolean })
            ?: (networkController?.let { readField(it, "mAirplaneMode") as? Boolean })
            ?: (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0)

        val currentCarriers = carrierController?.let { readField(it, "mCurrentCarrier") as? Array<*> }
        val rawCarriers = carrierController?.let { readField(it, "mCarrier") as? Array<*> }
        val customCarriers = carrierController?.let { readField(it, "mCustomCarrier") as? Array<*> }
        val cardDisabled = carrierController?.let { readField(it, "mCardDisable") as? BooleanArray }
        val noSimLabel = (carrierController?.let { readField(it, "mCurrentFinalCarrier") as? String }?.takeIf { it.isNotBlank() })
            ?: (carrierController?.let { readField(it, "mNoServiceString") as? String }?.takeIf { it.isNotBlank() })
            ?: "No SIM"

        val showNames = miuiMobileIconsInt?.let { readField(it, "showNames") }?.let(::readFlowValue) as? Array<*>
        val dataConnectedArr = miuiMobileIconsInt?.let { readField(it, "dataConnected") }?.let(::readFlowValue) as? BooleanArray

        val defaultConn = miuiMobileIconsInt?.let { readField(it, "connectRepo") }
            ?.let { readField(it, "defaultConnections") }
            ?.let(::readFlowValue)
        val defaultWifi = defaultConn?.let { readField(it, "wifi") }?.let { readField(it, "isDefault") as? Boolean }
        val defaultMobile = defaultConn?.let { readField(it, "mobile") }?.let { readField(it, "isDefault") as? Boolean }

        val wifiController = networkController?.let { readField(it, "mWifiSignalController") }
        val wifiState = wifiController?.let { readInheritedField(it, "mCurrentState") }
        val wifiStateEnabled = wifiState?.let { readInheritedField(it, "enabled") as? Boolean } ?: false
        val wifiStateConnected = wifiState?.let { readInheritedField(it, "connected") as? Boolean } ?: false
        val wifiStateDefault = wifiState?.let { readInheritedField(it, "isDefault") as? Boolean } ?: false
        val wifiStateLevel = (wifiState?.let { readInheritedField(it, "level") as? Number })?.toInt() ?: -1

        val activeWifiCaps = readActiveWifiCapabilities(context)
        val wifiIsDefault = (defaultWifi == true) || wifiStateDefault || (activeWifiCaps != null)
        val wifiConnected = wifiIsDefault || (wifiStateEnabled && wifiStateConnected)
        val wifiLevel = resolveWifiLevel(context, wifiConnected, wifiStateLevel, activeWifiCaps)

        val activeDataSlot = resolveDataSlot()
        val simsBySlot = linkedMapOf<Int, ControlCenterSignalSim>()

        val mobileControllers = networkController?.let { readField(it, "mMobileSignalControllers") as? SparseArray<*> }
        if (mobileControllers != null) {
            for (index in 0 until mobileControllers.size()) {
                val controller = mobileControllers.valueAt(index) ?: continue
                val subInfo = readField(controller, "mSubscriptionInfo") as? SubscriptionInfo ?: continue
                val slot = subInfo.simSlotIndex
                if (slot < 0) continue
                if (cardDisabled != null && slot < cardDisabled.size && cardDisabled[slot]) continue

                val state = readInheritedField(controller, "mCurrentState")
                val level = (state?.let { readInheritedField(it, "level") as? Number })?.toInt()?.coerceIn(0, 4) ?: 0
                val serviceState = state?.let { readField(it, "serviceState") as? ServiceState }
                val stateConnected = state?.let { readInheritedField(it, "connected") as? Boolean } ?: false
                val inService = if (serviceState != null) {
                    serviceState.state == ServiceState.STATE_IN_SERVICE ||
                        readDataRegistrationState(serviceState) == ServiceState.STATE_IN_SERVICE
                } else {
                    stateConnected
                }
                val stateDataConnected = state?.let { readField(it, "dataConnected") as? Boolean } ?: false
                val flowDataConnected = dataConnectedArr?.getOrNull(slot) ?: false
                val netType = (showNames?.getOrNull(slot) as? String).orEmpty().trim()
                val isDataSlot = (activeDataSlot == slot) || (state?.let { readField(it, "dataSim") as? Boolean } == true)
                val dataConnected = flowDataConnected || stateDataConnected || (defaultMobile == true && isDataSlot && netType.isNotBlank())

                val carrier = firstNonBlank(
                    customCarriers?.getOrNull(slot) as? String,
                    currentCarriers?.getOrNull(slot) as? String,
                    rawCarriers?.getOrNull(slot) as? String,
                    state?.let { readField(it, "networkName") as? String },
                    subInfo.carrierName?.toString(),
                    subInfo.displayName?.toString(),
                )
                simsBySlot[slot] = ControlCenterSignalSim(
                    slot = slot,
                    level = if (inService) level else 0,
                    inService = inService,
                    carrier = carrier,
                    networkType = netType,
                    dataConnected = dataConnected,
                )
            }
        }

        if (simsBySlot.isEmpty() && currentCarriers != null) {
            for (slot in currentCarriers.indices) {
                if (cardDisabled != null && slot < cardDisabled.size && cardDisabled[slot]) continue
                val name = (currentCarriers[slot] as? String)?.trim().orEmpty()
                if (name.isBlank()) continue
                val netType = (showNames?.getOrNull(slot) as? String).orEmpty().trim()
                val flowData = dataConnectedArr?.getOrNull(slot) ?: false
                simsBySlot[slot] = ControlCenterSignalSim(
                    slot = slot,
                    level = 4,
                    inService = true,
                    carrier = name,
                    networkType = netType,
                    dataConnected = flowData || (defaultMobile == true && activeDataSlot == slot && netType.isNotBlank()),
                )
            }
        }

        val sortedSims = simsBySlot.values.sortedBy { it.slot }
        return ControlCenterSignalSnapshot(
            airplaneMode = airplaneMode,
            wifiConnected = wifiConnected,
            wifiIsDefault = wifiIsDefault,
            wifiLevel = wifiLevel,
            sims = sortedSims,
            dataSlot = activeDataSlot,
            noSimLabel = noSimLabel,
        )
    }

    private fun readActiveWifiCapabilities(context: Context): NetworkCapabilities? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching null
        val network = cm.activeNetwork ?: return@runCatching null
        val caps = cm.getNetworkCapabilities(network) ?: return@runCatching null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) caps else null
    }.getOrNull()

    private fun resolveWifiLevel(
        context: Context,
        connected: Boolean,
        stateLevel: Int,
        caps: NetworkCapabilities?,
    ): Int {
        if (!connected) return 0
        if (stateLevel in 1..4) return stateLevel
        val rssi = runCatching {
            val fromCaps = caps?.signalStrength
            if (fromCaps != null && fromCaps != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
                fromCaps
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(WifiManager::class.java)?.connectionInfo?.rssi
            }
        }.getOrNull()
        if (rssi != null && rssi > -127 && rssi < 0) {
            return when {
                rssi >= -55 -> 4
                rssi >= -67 -> 3
                rssi >= -78 -> 2
                rssi >= -88 -> 1
                else -> 1
            }
        }
        return if (stateLevel == 0) 4 else stateLevel.coerceIn(1, 4)
    }

    private fun resolveDataSlot(): Int = runCatching {
        val subId = SubscriptionManager.getActiveDataSubscriptionId().takeIf(SubscriptionManager::isValidSubscriptionId)
            ?: SubscriptionManager.getDefaultDataSubscriptionId()
        if (SubscriptionManager.isValidSubscriptionId(subId)) SubscriptionManager.getSlotIndex(subId) else -1
    }.getOrDefault(-1)

    private fun firstNonBlank(vararg values: String?): String =
        values.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()

    private fun readField(instance: Any, name: String): Any? = runCatching {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
    }.getOrNull()

    private fun readInheritedField(instance: Any, name: String): Any? = runCatching {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return@runCatching field.get(instance)
            }
            type = type.superclass
        }
        null
    }.getOrNull()

    private fun readDataRegistrationState(serviceState: ServiceState): Int = runCatching {
        (serviceState.javaClass.getMethod("getDataRegistrationState").invoke(serviceState) as? Number)?.toInt()
    }.getOrNull() ?: ServiceState.STATE_OUT_OF_SERVICE

    private fun readFlowValue(flow: Any): Any? = runCatching {
        flow.javaClass.getMethod("getValue").apply { isAccessible = true }.invoke(flow)
    }.getOrNull()
}
