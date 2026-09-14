// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.Collections
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt

internal fun readRoProductMarketName(): String {
    val value = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java, String::class.java)
            .invoke(null, "ro.product.marketname", "") as? String
    }.getOrNull().orEmpty().trim()
    return value.ifBlank { android.os.Build.MODEL.orEmpty().ifBlank { "Android" } }
}

private const val MODULE_PACKAGE = "btm.m.os4.systemuihook"
private const val CLOCK_DATA_UTILS_CLASS = "com.miui.clock.utils.DataUtils"
private const val WIDGET_WIDTH_DP = 360f
private const val WIDGET_HEIGHT_DP = 64f
private const val WIDGET_COMBINATION_TWO_HEIGHT_DP = 64f
private const val WIDGET_VERTICAL_OFFSET_DP = 80f
private const val WIDGET_TEXT_SIZE_SP = 15.3f
private const val WIDGET_COMBINATION_METRIC_TEXT_SIZE_SP = 12.24f
private const val WIDGET_ROW_HEIGHT_DP = 20
private const val WIDGET_WEATHER_ROW_HEIGHT_DP = 22
private const val WIDGET_CITY_ROW_TOP_DP = 22
private const val WIDGET_THIRD_ROW_TOP_DP = 42
private const val DETAILED_WIDGET_WIDTH_DP = 151
private const val DETAILED_WIDGET_PAIR_GAP_DP = 27
private const val NOTIFICATION_SAFE_GAP_DP = 16f
private const val WEATHER_ICON_SIZE_DP = 20
private const val MOBILE_ICON_WIDTH_DP = 19
private const val MOBILE_ICON_HEIGHT_DP = 21
private const val WIDGET_DARK_FOREGROUND = 0xFF1A1A1A.toInt()
private const val STEPS_GOAL = 10000
private const val SYSTEM_NEXT_ALARM_FORMATTED = "next_alarm_formatted"

/**
 * The editor and full-screen charging animation reuse the keyguard window. Track their explicit
 * SystemUI states instead of treating every keyguard-attached layout as an ordinary lockscreen.
 */
internal object LockscreenWidgetSceneState {
    @Volatile private var editorActive = false
    @Volatile private var chargingActive = false
    @Volatile private var controlCenterActive = false
    @Volatile var aodActive = false
        private set
    private val controllers = Collections.newSetFromMap(
        WeakHashMap<LockscreenWidgetController, Boolean>(),
    )

    val hasBlockingOverlay: Boolean
        get() = editorActive || chargingActive || controlCenterActive

    fun register(controller: LockscreenWidgetController) {
        synchronized(controllers) { controllers.add(controller) }
    }

    fun unregister(controller: LockscreenWidgetController) {
        synchronized(controllers) { controllers.remove(controller) }
    }

    fun setEditorActive(active: Boolean) {
        if (editorActive == active) return
        editorActive = active
        notifyControllers()
    }

    fun setChargingActive(active: Boolean) {
        if (chargingActive == active) return
        chargingActive = active
        notifyControllers()
    }

    fun setControlCenterActive(active: Boolean) {
        if (controlCenterActive == active) return
        controlCenterActive = active
        notifyControllers()
    }

    fun setAodActive(active: Boolean) {
        if (aodActive == active) return
        aodActive = active
        notifyControllers()
    }

    private fun notifyControllers() {
        val snapshot = synchronized(controllers) { controllers.toList() }
        snapshot.forEach(LockscreenWidgetController::onSceneVisibilityChanged)
    }
}

private fun wallpaperForegroundColor(context: Context): Int = runCatching {
    val wallpaperManager = WallpaperManager.getInstance(context)
    val drawable = runCatching {
        wallpaperManager.getDrawable(WallpaperManager.FLAG_LOCK)
    }.getOrNull() ?: wallpaperManager.drawable
        ?: throw IllegalStateException("No wallpaper drawable available")
    val sample = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    val pixelCount = sample.width * sample.height
    Canvas(sample).apply {
        drawable.setBounds(0, 0, width, height)
        drawable.draw(this)
    }
    var totalLuminance = 0.0
    for (y in 0 until sample.height) {
        for (x in 0 until sample.width) {
            val color = sample.getPixel(x, y)
            totalLuminance +=
                0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)
        }
    }
    sample.recycle()
    if (totalLuminance / pixelCount >= 142.0) {
        WIDGET_DARK_FOREGROUND
    } else {
        Color.WHITE
    }
}.getOrDefault(Color.WHITE)

/**
 * The widget is created inside com.android.systemui, so its Context cannot resolve the
 * module's own R.drawable IDs. Resolve the resource through a module package Context and
 * gracefully fall back to no icon if the module package is unavailable.
 */
private fun loadMobile3Icon(systemUiContext: Context): Drawable? = runCatching {
    val moduleContext = systemUiContext.createPackageContext(
        MODULE_PACKAGE,
        Context.CONTEXT_IGNORE_SECURITY,
    )
    val id = moduleContext.resources.getIdentifier(
        "ic_mobile_3_bold",
        "drawable",
        MODULE_PACKAGE,
    )
    if (id != 0) moduleContext.getDrawable(id) else null
}.getOrNull()

private fun loadModuleDrawable(systemUiContext: Context, name: String): Drawable? = runCatching {
    val moduleContext = systemUiContext.createPackageContext(
        MODULE_PACKAGE,
        Context.CONTEXT_IGNORE_SECURITY,
    )
    moduleContext.resources.getIdentifier(name, "drawable", MODULE_PACKAGE)
        .takeIf { it != 0 }
        ?.let { moduleContext.getDrawable(it)?.constantState?.newDrawable()?.mutate() }
}.getOrNull()

private fun loadLockscreenWidgetSignature(
    systemUiContext: Context,
    type: Int,
): Drawable? = runCatching {
    if (type == LOCKSCREEN_WIDGET_SIGNATURE_NONE) return@runCatching null
    // SystemUI cannot read the module's private files. The exported provider is the same
    // cross-process resource path used by the module's appearance assets.
    val uri = Uri.Builder()
        .scheme("content")
        .authority(SETTINGS_APPEARANCE_AUTHORITY)
        .appendPath(LOCKSCREEN_WIDGET_SIGNATURE_SLOT)
        .build()
    systemUiContext.contentResolver.openInputStream(uri)?.use { input ->
        LogoDrawableLoader.loadBytes(systemUiContext, input.readBytes())?.mutate()
    }
}.getOrNull()

private fun loadSystemDrawable(systemUiContext: Context, name: String): Drawable? = runCatching {
    systemUiContext.resources.getIdentifier(name, "drawable", systemUiContext.packageName)
        .takeIf { it != 0 }
        ?.let { systemUiContext.getDrawable(it)?.constantState?.newDrawable()?.mutate() }
}.getOrNull()

private fun parseTimeOfDay(value: String?): Int? {
    val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(value?.trim().orEmpty()) ?: return null
    val hour = match.groupValues[1].toIntOrNull() ?: return null
    val minute = match.groupValues[2].toIntOrNull() ?: return null
    return (hour * 60 + minute).takeIf { hour in 0..23 && minute in 0..59 }
}

private data class LockscreenWidgetData(
    val temperature: String,
    val condition: String,
    val city: String,
    val highLow: String,
    val battery: Int,
    val deviceName: String,
    val weatherIcon: Drawable?,
    val sunrise: Int,
    val sunriseTomorrow: Int,
    val sunset: Int,
    val steps: Int?,
    val humidity: String,
    val aqi: String,
    val feelsLike: String,
    val feelsLikeIcon: Drawable?,
    val wind: String,
    val windIcon: Drawable?,
    val standCount: Int?,
    val nextAlarm: String?,
    val nextSchedule: ScheduleEntry?,
)

private data class ScheduleEntry(
    val title: String,
    val timeLabel: String,
)

private data class SystemWeatherSnapshot(
    val temperature: String,
    val condition: String,
    val city: String,
    val highLow: String,
    val icon: Drawable?,
    val sunrise: Int,
    val sunriseTomorrow: Int,
    val sunset: Int,
    val humidity: String,
    val aqi: String,
    val feelsLike: String,
    val feelsLikeIcon: Drawable?,
    val wind: String,
    val windIcon: Drawable?,
)

/** A time-layer root and its vendor container that exposes visual clock bounds. */
private data class LockscreenClockTarget(
    val clock: View,
    val container: View,
)

/** Draws the long step widget's circular goal indicator and the platform shoe glyph. */
private class StepsProgressView(context: Context) : View(context) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var steps = 0
    private var foregroundColor = Color.WHITE
    private var icon: Drawable? = null

    fun bind(value: Int?, color: Int, drawable: Drawable?) {
        steps = value?.coerceAtLeast(0) ?: 0
        foregroundColor = color
        icon = drawable?.mutate()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = (width.coerceAtMost(height) * .095f).coerceAtLeast(dp(4f))
        val inset = stroke / 2f + dp(2f)
        bounds.set(inset, inset, width - inset, height - inset)
        ringPaint.strokeWidth = stroke
        ringPaint.strokeCap = Paint.Cap.ROUND
        ringPaint.color = Color.argb(72, Color.red(foregroundColor), Color.green(foregroundColor), Color.blue(foregroundColor))
        canvas.drawArc(bounds, -50f, 280f, false, ringPaint)
        ringPaint.color = foregroundColor
        val progress = (steps.toFloat() / STEPS_GOAL).coerceIn(0f, 1f)
        canvas.drawArc(bounds, -50f, 280f * progress, false, ringPaint)
        icon?.let { drawable ->
            val iconSize = (width.coerceAtMost(height) * .36f).roundToInt()
            val left = (width - iconSize) / 2
            val top = (height - iconSize) / 2
            drawable.setBounds(left, top, left + iconSize, top + iconSize)
            drawable.setTint(foregroundColor)
            drawable.draw(canvas)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

/** A lightweight native view for the five independently selectable lock-screen widgets. */
private class LockscreenWidgetView(
    context: Context,
    private val itemMask: Int,
    private val itemOrder: List<Int>,
    private val applyBatteryTrackMaterial: (View) -> Unit,
    private val applyShortcutSurfaceMaterial: (View) -> Unit,
    private val foregroundColorProvider: () -> Int,
    private val signatureType: Int,
    private val signatureColor: Int,
    private val signatureScale: Int,
    private val signatureBackground: Boolean,
) : FrameLayout(context) {
    private val weatherIcon = ImageView(context)
    private val temperature = TextView(context)
    private val condition = TextView(context)
    private val city = TextView(context)
    private val highLow = TextView(context)
    private val batteryIcon = ImageView(context)
    private val batteryPercent = TextView(context)
    private val deviceName = TextView(context)
    private val batteryTrack = ImageView(context)
    private val batteryFill = View(context)
    private val combinationWeatherIcon = ImageView(context)
    private val combinationCity = TextView(context)
    private val combinationCondition = TextView(context)
    private val sunIcon = ImageView(context)
    private val sunTime = TextView(context)
    private val stepIcon = ImageView(context)
    private val stepCount = TextView(context)
    private val stepsWideTitle = TextView(context)
    private val stepsWideValue = TextView(context)
    private val stepsWideUnit = TextView(context)
    private val stepsWideProgress = StepsProgressView(context)
    private val humidityLabel = TextView(context)
    private val humidityValue = TextView(context)
    private val aqiLabel = TextView(context)
    private val aqiValue = TextView(context)
    private val feelsLikeIcon = ImageView(context)
    private val feelsLikeLabel = TextView(context)
    private val feelsLikeValue = TextView(context)
    private val windIcon = ImageView(context)
    private val windValue = TextView(context)
    private val standIcon = ImageView(context)
    private val standLabel = TextView(context)
    private val standCount = TextView(context)
    private val alarmIcon = ImageView(context)
    private val alarmTime = TextView(context)
    private var alarmValueLayoutParams: LinearLayout.LayoutParams? = null
    private var alarmValueDefaultTopMarginPx = 0
    private var alarmValueDefaultHeightPx = 0
    private var alarmValueMultilineHeightPx = 0
    private val scheduleTitle = TextView(context)
    private val scheduleTime = TextView(context)
    private val signatureImage = ImageView(context)
    private val shortcutSurfaceLayers = ArrayList<ImageView>(5)
    private lateinit var batteryBar: FrameLayout
    private var batteryValue = 0
    private val density = resources.displayMetrics.density

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        if (usesDetailedLayout) {
            buildCombinationOne(context)
        } else {
            buildFlexibleLayout(context)
        }
    }

    private val usesDetailedLayout: Boolean
        get() = itemMask == LOCKSCREEN_WIDGET_DEFAULT_ITEMS && itemOrder == listOf(
            LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER,
            LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY,
        )

    private fun buildCombinationOne(context: Context) {
        setPadding(dp(16), 0, dp(16), 0)
        val columns = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        addView(columns, LayoutParams(-1, -1))

        // Use fixed bottom-anchored slots for the low/high temperature and battery
        // bar.  This keeps their geometric bottom edges aligned even when font
        // metrics differ between SystemUI and the module process.
        val left = FrameLayout(context)
        val weatherTop = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        weatherIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        weatherTop.addView(weatherIcon, LinearLayout.LayoutParams(dp(WEATHER_ICON_SIZE_DP), dp(WEATHER_ICON_SIZE_DP)))
        style(temperature, WIDGET_TEXT_SIZE_SP)
        style(condition, WIDGET_TEXT_SIZE_SP)
        weatherTop.addView(temperature, LinearLayout.LayoutParams(-2, dp(WIDGET_WEATHER_ROW_HEIGHT_DP)).apply { leftMargin = dp(4) })
        weatherTop.addView(condition, LinearLayout.LayoutParams(-2, dp(WIDGET_WEATHER_ROW_HEIGHT_DP)).apply { leftMargin = dp(4) })
        left.addView(weatherTop, FrameLayout.LayoutParams(-1, dp(WIDGET_WEATHER_ROW_HEIGHT_DP)))
        style(city, WIDGET_TEXT_SIZE_SP)
        left.addView(city, FrameLayout.LayoutParams(-1, dp(WIDGET_ROW_HEIGHT_DP)).apply {
            topMargin = dp(WIDGET_CITY_ROW_TOP_DP)
        })
        style(highLow, WIDGET_TEXT_SIZE_SP)
        left.addView(highLow, FrameLayout.LayoutParams(-1, dp(WIDGET_ROW_HEIGHT_DP)).apply {
            gravity = Gravity.TOP
            topMargin = dp(WIDGET_THIRD_ROW_TOP_DP)
        })

        val right = FrameLayout(context)
        val batteryTop = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        batteryIcon.setImageDrawable(loadMobile3Icon(context))
        batteryIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        batteryTop.addView(batteryIcon, LinearLayout.LayoutParams(dp(MOBILE_ICON_WIDTH_DP), dp(MOBILE_ICON_HEIGHT_DP)))
        style(batteryPercent, WIDGET_TEXT_SIZE_SP)
        batteryTop.addView(batteryPercent, LinearLayout.LayoutParams(-2, dp(WIDGET_WEATHER_ROW_HEIGHT_DP)).apply { leftMargin = dp(5) })
        right.addView(batteryTop, FrameLayout.LayoutParams(-1, dp(WIDGET_WEATHER_ROW_HEIGHT_DP)))
        style(deviceName, WIDGET_TEXT_SIZE_SP)
        deviceName.maxLines = 1
        deviceName.ellipsize = android.text.TextUtils.TruncateAt.END
        right.addView(deviceName, FrameLayout.LayoutParams(-1, dp(WIDGET_ROW_HEIGHT_DP)).apply {
            topMargin = dp(WIDGET_CITY_ROW_TOP_DP)
        })

        val bar = FrameLayout(context)
        batteryBar = bar
        batteryTrack.background = rounded(Color.argb(76, 255, 255, 255), dp(9).toFloat())
        batteryFill.background = rounded(Color.WHITE, dp(9).toFloat())
        batteryTrack.clipToOutline = true
        batteryTrack.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val radius = dp(9).toFloat().coerceAtMost(minOf(view.width, view.height) / 2f)
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        bar.addView(batteryTrack, FrameLayout.LayoutParams(-1, dp(10)))
        bar.addView(batteryFill, FrameLayout.LayoutParams(0, dp(10)))
        right.addView(bar, FrameLayout.LayoutParams(-1, dp(10)).apply {
            gravity = Gravity.TOP
            // Center the 10dp track within the 20dp third row.
            topMargin = dp(WIDGET_THIRD_ROW_TOP_DP + 5)
        })

        columns.addView(left, LinearLayout.LayoutParams(0, -1, 1f))
        columns.addView(right, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(27) })
    }

    private fun buildFlexibleLayout(context: Context) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        addView(row, LayoutParams(-1, -1))

        // The editor limits compositions to two long cards, one long card plus two circles,
        // or four circles. Keep that same geometry in SystemUI.
        val selectedCount = Integer.bitCount(itemMask)
        val longCount = listOf(
            LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER,
            LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY,
            LOCKSCREEN_WIDGET_ITEM_COMPACT_WEATHER,
            LOCKSCREEN_WIDGET_ITEM_SIGNATURE,
            LOCKSCREEN_WIDGET_ITEM_STEPS_WIDE,
            LOCKSCREEN_WIDGET_ITEM_SCHEDULE,
        ).count { itemMask and it != 0 }
        val surfaceHeight = if (selectedCount >= 4 && longCount == 0) 56 else 64
        val metricWidth = surfaceHeight
        val hasDetailedPair = selectedCount == 2 &&
            itemMask and LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER != 0 &&
            itemMask and LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY != 0
        val surfaceGap = when {
            hasDetailedPair -> DETAILED_WIDGET_PAIR_GAP_DP
            selectedCount >= 4 -> 8
            else -> 12
        }
        val compactWeatherWidth = if (selectedCount >= 4) 122 else 142
        val detailedWidth = DETAILED_WIDGET_WIDTH_DP
        fun addSurface(surface: FrameLayout, width: Int) {
            row.addView(surface, LinearLayout.LayoutParams(dp(width), dp(surfaceHeight)).apply {
                if (row.childCount > 0) leftMargin = dp(surfaceGap)
            })
        }
        val surfaces = LinkedHashMap<Int, Pair<FrameLayout, Int>>()
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER != 0) {
            // Detailed weather and battery are content-only widgets. Their default pair has
            // never used a shortcut-style capsule; retain that appearance for every order and
            // mixed composition instead of letting the generic layout add one.
            val weatherSurface = createContentSlot()
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            weatherIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            top.addView(weatherIcon, LinearLayout.LayoutParams(dp(WEATHER_ICON_SIZE_DP), dp(WEATHER_ICON_SIZE_DP)))
            style(temperature, WIDGET_TEXT_SIZE_SP)
            style(condition, WIDGET_TEXT_SIZE_SP)
            top.addView(temperature, LinearLayout.LayoutParams(-2, dp(WIDGET_WEATHER_ROW_HEIGHT_DP)).apply { leftMargin = dp(4) })
            top.addView(condition, LinearLayout.LayoutParams(-2, dp(WIDGET_WEATHER_ROW_HEIGHT_DP)).apply { leftMargin = dp(4) })
            weatherSurface.addView(top, FrameLayout.LayoutParams(-1, dp(WIDGET_WEATHER_ROW_HEIGHT_DP)))
            style(city, WIDGET_TEXT_SIZE_SP)
            weatherSurface.addView(city, FrameLayout.LayoutParams(-1, dp(WIDGET_ROW_HEIGHT_DP)).apply {
                topMargin = dp(WIDGET_CITY_ROW_TOP_DP)
            })
            style(highLow, WIDGET_TEXT_SIZE_SP)
            weatherSurface.addView(highLow, FrameLayout.LayoutParams(-1, dp(WIDGET_ROW_HEIGHT_DP)).apply {
                gravity = Gravity.TOP
                topMargin = dp(WIDGET_THIRD_ROW_TOP_DP)
            })
            surfaces[LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER] = weatherSurface to detailedWidth
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY != 0) {
            val batterySurface = createContentSlot()
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            batteryIcon.setImageDrawable(loadMobile3Icon(context))
            batteryIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            top.addView(batteryIcon, LinearLayout.LayoutParams(dp(MOBILE_ICON_WIDTH_DP), dp(MOBILE_ICON_HEIGHT_DP)))
            style(batteryPercent, WIDGET_TEXT_SIZE_SP)
            top.addView(batteryPercent, LinearLayout.LayoutParams(-2, dp(WIDGET_WEATHER_ROW_HEIGHT_DP)).apply { leftMargin = dp(5) })
            batterySurface.addView(top, FrameLayout.LayoutParams(-1, dp(WIDGET_WEATHER_ROW_HEIGHT_DP)))
            style(deviceName, WIDGET_TEXT_SIZE_SP)
            deviceName.maxLines = 1
            deviceName.ellipsize = android.text.TextUtils.TruncateAt.END
            batterySurface.addView(deviceName, FrameLayout.LayoutParams(-1, dp(WIDGET_ROW_HEIGHT_DP)).apply {
                topMargin = dp(WIDGET_CITY_ROW_TOP_DP)
            })
            batteryBar = FrameLayout(context)
            batteryTrack.background = rounded(Color.argb(76, 255, 255, 255), dp(9).toFloat())
            batteryFill.background = rounded(Color.WHITE, dp(9).toFloat())
            batteryBar.addView(batteryTrack, FrameLayout.LayoutParams(-1, dp(10)))
            batteryBar.addView(batteryFill, FrameLayout.LayoutParams(0, dp(10)))
            batterySurface.addView(batteryBar, FrameLayout.LayoutParams(-1, dp(10)).apply {
                gravity = Gravity.TOP
                topMargin = dp(WIDGET_THIRD_ROW_TOP_DP + 5)
            })
            surfaces[LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY] = batterySurface to detailedWidth
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_COMPACT_WEATHER != 0) {
            val weatherSurface = createSurface(widthDp = compactWeatherWidth, heightDp = surfaceHeight, circular = false)
            val weatherContent = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), 0, dp(10), 0)
            }
            combinationWeatherIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            weatherContent.addView(combinationWeatherIcon, LinearLayout.LayoutParams(dp(42), dp(42)))
            val weatherText = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            style(combinationCity, 15.3f)
            style(combinationCondition, 13.6f)
            weatherText.addView(combinationCity, LinearLayout.LayoutParams(-1, dp(22)))
            weatherText.addView(combinationCondition, LinearLayout.LayoutParams(-1, dp(19)))
            weatherContent.addView(weatherText, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(7) })
            weatherSurface.addView(weatherContent, LayoutParams(-1, -1))
            surfaces[LOCKSCREEN_WIDGET_ITEM_COMPACT_WEATHER] = weatherSurface to compactWeatherWidth
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_SIGNATURE != 0) {
            val signatureSurface = if (signatureBackground) {
                createSurface(widthDp = compactWeatherWidth, heightDp = surfaceHeight, circular = false)
            } else {
                createContentSlot()
            }
            signatureSurface.clipChildren = true
            signatureSurface.clipToPadding = true
            signatureImage.scaleType = ImageView.ScaleType.FIT_CENTER
            signatureImage.setImageDrawable(loadLockscreenWidgetSignature(context, signatureType)?.apply {
                if (signatureType == LOCKSCREEN_WIDGET_SIGNATURE_VECTOR) {
                    setColorFilter(signatureColor, PorterDuff.Mode.SRC_IN)
                }
            })
            val scale = signatureScale.coerceIn(25, 200) / 100f
            signatureImage.scaleX = scale
            signatureImage.scaleY = scale
            signatureSurface.addView(signatureImage, LayoutParams(-1, -1))
            surfaces[LOCKSCREEN_WIDGET_ITEM_SIGNATURE] = signatureSurface to compactWeatherWidth
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_STEPS_WIDE != 0) {
            val stepsSurface = createSurface(widthDp = compactWeatherWidth, heightDp = surfaceHeight, circular = false)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
            }
            val labels = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            style(stepsWideTitle, 12f)
            stepsWideTitle.text = "今日步数"
            stepsWideTitle.alpha = .78f
            labels.addView(stepsWideTitle, LinearLayout.LayoutParams(-1, dp(18)))
            val valueRow = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
            style(stepsWideValue, 17.6f)
            stepsWideValue.setTypeface(stepsWideValue.typeface, android.graphics.Typeface.BOLD)
            valueRow.addView(stepsWideValue, LinearLayout.LayoutParams(-2, dp(29)))
            style(stepsWideUnit, 8.8f)
            stepsWideUnit.text = "步"
            stepsWideUnit.setTypeface(stepsWideUnit.typeface, android.graphics.Typeface.BOLD)
            valueRow.addView(stepsWideUnit, LinearLayout.LayoutParams(-2, dp(22)).apply { leftMargin = dp(2) })
            labels.addView(valueRow, LinearLayout.LayoutParams(-1, dp(30)))
            content.addView(labels, LinearLayout.LayoutParams(0, -1, 1f))
            content.addView(stepsWideProgress, LinearLayout.LayoutParams(dp(58), dp(58)))
            stepsSurface.addView(content, LayoutParams(-1, -1))
            surfaces[LOCKSCREEN_WIDGET_ITEM_STEPS_WIDE] = stepsSurface to compactWeatherWidth
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_SCHEDULE != 0) {
            val scheduleSurface = createSurface(widthDp = compactWeatherWidth, heightDp = surfaceHeight, circular = false)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
            }
            style(scheduleTitle, 12f)
            scheduleTitle.alpha = .78f
            scheduleTitle.maxLines = 1
            scheduleTitle.ellipsize = android.text.TextUtils.TruncateAt.END
            content.addView(scheduleTitle, LinearLayout.LayoutParams(-1, dp(22)))
            style(scheduleTime, 15f)
            scheduleTime.maxLines = 1
            scheduleTime.ellipsize = android.text.TextUtils.TruncateAt.END
            content.addView(scheduleTime, LinearLayout.LayoutParams(-1, dp(24)))
            scheduleSurface.addView(content, LayoutParams(-1, -1))
            surfaces[LOCKSCREEN_WIDGET_ITEM_SCHEDULE] = scheduleSurface to compactWeatherWidth
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_SUN != 0) {
            val sunSurface = createSurface(widthDp = metricWidth, heightDp = surfaceHeight, circular = true)
            val sunContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            sunIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            sunContent.addView(sunIcon, LinearLayout.LayoutParams(dp(32), dp(28)))
            style(sunTime, WIDGET_COMBINATION_METRIC_TEXT_SIZE_SP)
            sunTime.gravity = Gravity.CENTER
            sunContent.addView(sunTime, LinearLayout.LayoutParams(-1, dp(22)).apply { topMargin = dp(1) })
            sunSurface.addView(sunContent, LayoutParams(-1, -1))
            surfaces[LOCKSCREEN_WIDGET_ITEM_SUN] = sunSurface to metricWidth
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_STEPS != 0) {
            val stepsSurface = createSurface(widthDp = metricWidth, heightDp = surfaceHeight, circular = true)
            val stepsContent = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            stepIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            stepsContent.addView(stepIcon, LinearLayout.LayoutParams(dp(28), dp(28)))
            style(stepCount, WIDGET_COMBINATION_METRIC_TEXT_SIZE_SP)
            stepCount.gravity = Gravity.CENTER
            stepsContent.addView(stepCount, LinearLayout.LayoutParams(-1, dp(22)).apply { topMargin = dp(1) })
            stepsSurface.addView(stepsContent, LayoutParams(-1, -1))
            surfaces[LOCKSCREEN_WIDGET_ITEM_STEPS] = stepsSurface to metricWidth
        }
        fun addMetricSurface(
            flag: Int,
            icon: View,
            value: TextView,
            iconWidth: Int = 28,
            iconHeight: Int = 28,
            valueSize: Float = WIDGET_COMBINATION_METRIC_TEXT_SIZE_SP,
            valueHeight: Int = 22,
        ) {
            val surface = createSurface(widthDp = metricWidth, heightDp = surfaceHeight, circular = true)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            if (icon is ImageView) icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            content.addView(icon, LinearLayout.LayoutParams(dp(iconWidth), dp(iconHeight)))
            style(value, valueSize)
            value.gravity = Gravity.CENTER
            val valueLayoutParams = LinearLayout.LayoutParams(-1, dp(valueHeight)).apply {
                topMargin = dp(1)
            }
            content.addView(value, valueLayoutParams)
            if (value === alarmTime) {
                alarmValueLayoutParams = valueLayoutParams
                alarmValueDefaultTopMarginPx = valueLayoutParams.topMargin
                alarmValueDefaultHeightPx = valueLayoutParams.height
            }
            surface.addView(content, LayoutParams(-1, -1))
            surfaces[flag] = surface to metricWidth
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_HUMIDITY != 0) {
            style(humidityLabel, 10f)
            humidityLabel.text = "湿度"
            humidityLabel.gravity = Gravity.CENTER
            addMetricSurface(LOCKSCREEN_WIDGET_ITEM_HUMIDITY, humidityLabel, humidityValue, 32, 25)
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_AQI != 0) {
            style(aqiLabel, 10f)
            aqiLabel.text = "AQI"
            aqiLabel.gravity = Gravity.CENTER
            addMetricSurface(LOCKSCREEN_WIDGET_ITEM_AQI, aqiLabel, aqiValue, 32, 25)
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_FEELS_LIKE != 0) {
            style(feelsLikeLabel, 10f)
            feelsLikeLabel.text = "体感"
            feelsLikeLabel.gravity = Gravity.CENTER
            addMetricSurface(LOCKSCREEN_WIDGET_ITEM_FEELS_LIKE, feelsLikeLabel, feelsLikeValue, 32, 25)
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_WIND != 0) {
            addMetricSurface(
                LOCKSCREEN_WIDGET_ITEM_WIND,
                windIcon,
                windValue,
                valueSize = 10f,
            )
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_STAND != 0) {
            style(standLabel, 10f)
            standLabel.text = "站立"
            standLabel.gravity = Gravity.CENTER
            addMetricSurface(LOCKSCREEN_WIDGET_ITEM_STAND, standLabel, standCount, 32, 25)
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_ALARM != 0) {
            alarmIcon.setImageDrawable(
                loadModuleDrawable(context, "ic_widget_alarm")
                    ?: loadSystemDrawable(context, "ic_alarm"),
            )
            // Reserve a second line for values with a non-weekday date prefix. Four-circle
            // layouts are only 56dp high, so use a smaller icon there to keep two lines inside.
            val alarmIconHeight = if (surfaceHeight >= 64) 30 else 25
            // Keep the single-line slot identical to the other metric widgets. The taller slot
            // is applied only when the bound alarm text actually contains a date line.
            val alarmValueHeight = 22
            alarmValueMultilineHeightPx = dp(if (surfaceHeight >= 64) 32 else 30)
            addMetricSurface(
                LOCKSCREEN_WIDGET_ITEM_ALARM,
                alarmIcon,
                alarmTime,
                30,
                alarmIconHeight,
                11f,
                alarmValueHeight,
            )
            // style() sets the single-line defaults for other metric values; override them
            // after the shared builder so a date prefix can occupy its own line.
            alarmTime.maxLines = 2
            alarmTime.ellipsize = android.text.TextUtils.TruncateAt.END
            alarmTime.setLineSpacing(0f, .9f)
        }
        itemOrder.forEach { flag ->
            surfaces.remove(flag)?.let { (surface, width) -> addSurface(surface, width) }
        }
        // Keep the view resilient to a future preference migration with an incomplete order.
        surfaces.values.forEach { (surface, width) -> addSurface(surface, width) }
    }

    private fun createSurface(widthDp: Int, heightDp: Int, circular: Boolean): FrameLayout {
        val surface = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        val layer = ImageView(context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (circular) {
                        outline.setOval(0, 0, view.width, view.height)
                    } else {
                        outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                    }
                }
            }
        }
        surface.addView(layer, LayoutParams(dp(widthDp), dp(heightDp), Gravity.CENTER))
        shortcutSurfaceLayers += layer
        return surface
    }

    private fun createContentSlot(): FrameLayout = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
    }

    fun bind(data: LockscreenWidgetData) {
        val color = foregroundColorProvider()
        if (!usesDetailedLayout) {
            bindFlexibleLayout(data, color)
            return
        }
        applyForegroundColor(color)
        temperature.text = data.temperature
        condition.text = data.condition
        city.text = data.city
        highLow.text = data.highLow
        batteryPercent.text = "${data.battery}%"
        batteryValue = data.battery.coerceIn(0, 100)
        deviceName.text = data.deviceName
        data.weatherIcon?.let { weatherIcon.setImageDrawable(it) }
        applyTrackMaterialAfterLayout()
        updateBatteryFill()
    }

    private fun bindFlexibleLayout(data: LockscreenWidgetData, color: Int) {
        combinationCity.text = data.city
        combinationCondition.text = listOf(data.temperature, data.condition)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_COMPACT_WEATHER != 0) {
            combinationWeatherIcon.setImageDrawable(data.weatherIcon)
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_DETAIL_WEATHER != 0) {
            temperature.text = data.temperature
            condition.text = data.condition
            city.text = data.city
            highLow.text = data.highLow
            weatherIcon.setImageDrawable(data.weatherIcon)
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_DETAIL_BATTERY != 0) {
            batteryPercent.text = "${data.battery}%"
            batteryValue = data.battery.coerceIn(0, 100)
            deviceName.text = data.deviceName
            applyTrackMaterialAfterLayout()
            updateBatteryFill()
        }

        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val showSunset = data.sunset in 1 until 24 * 60 && now >= data.sunrise && now < data.sunset
        val targetMinute = when {
            showSunset -> data.sunset
            now >= data.sunset && data.sunriseTomorrow in 1 until 24 * 60 -> data.sunriseTomorrow
            else -> data.sunrise
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_SUN != 0) {
            sunIcon.setImageDrawable(
                loadModuleDrawable(context, if (showSunset) "ic_widget_sunset" else "ic_widget_sunrise")
                    ?: loadSystemDrawable(context, if (showSunset) "weather_icon_sun_down" else "weather_icon_sun_up"),
            )
            sunTime.text = formatMinute(targetMinute)
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_STEPS != 0) {
            stepIcon.setImageDrawable(loadSystemDrawable(context, "health_icon_step_count_25"))
            stepCount.text = data.steps?.takeIf { it >= 0 }?.toString() ?: "--"
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_STEPS_WIDE != 0) {
            stepsWideValue.text = data.steps?.takeIf { it >= 0 }?.toString() ?: "--"
            stepsWideProgress.bind(data.steps, color, loadSystemDrawable(context, "health_icon_step_count_25"))
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_HUMIDITY != 0) {
            humidityValue.text = data.humidity
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_AQI != 0) {
            aqiValue.text = data.aqi
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_FEELS_LIKE != 0) {
            feelsLikeValue.text = data.feelsLike
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_WIND != 0) {
            windIcon.setImageDrawable(data.windIcon)
            windValue.text = data.wind
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_STAND != 0) {
            standCount.text = data.standCount?.takeIf { it >= 0 }?.toString() ?: "--"
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_ALARM != 0) {
            alarmTime.text = formatAlarmText(data.nextAlarm ?: "--:--")
            updateAlarmTextSpacing()
        }
        if (itemMask and LOCKSCREEN_WIDGET_ITEM_SCHEDULE != 0) {
            scheduleTitle.text = data.nextSchedule?.title ?: "无日程"
            scheduleTime.text = data.nextSchedule?.timeLabel ?: ""
        }
        listOf(
            temperature, condition, city, highLow, batteryPercent, deviceName,
            combinationCity, combinationCondition, sunTime, stepCount, humidityLabel, humidityValue,
            aqiLabel, aqiValue, feelsLikeLabel, feelsLikeValue, windValue, standLabel, standCount,
            stepsWideTitle, stepsWideValue, stepsWideUnit, alarmTime, scheduleTitle, scheduleTime,
        ).forEach { it.setTextColor(color) }
        listOf(
            weatherIcon, combinationWeatherIcon, sunIcon, stepIcon, batteryIcon, windIcon, alarmIcon,
        ).forEach {
            it.imageTintList = ColorStateList.valueOf(color)
        }
        stepsWideProgress.bind(
            data.steps,
            color,
            loadSystemDrawable(context, "health_icon_step_count_25"),
        )
        batteryFill.background = rounded(color, dp(9).toFloat())
        applySurfaceMaterialsAfterLayout()
    }

    private fun applyForegroundColor(color: Int) {
        listOf(temperature, condition, city, highLow, batteryPercent, deviceName).forEach {
            it.setTextColor(color)
        }
        batteryIcon.imageTintList = ColorStateList.valueOf(color)
        weatherIcon.imageTintList = ColorStateList.valueOf(color)
        batteryFill.background = rounded(color, dp(9).toFloat())
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (!usesDetailedLayout) {
            applySurfaceMaterialsAfterLayout()
            if (::batteryBar.isInitialized) updateBatteryFill()
        } else {
            applyTrackMaterialAfterLayout()
            updateBatteryFill()
        }
    }

    private fun applyTrackMaterialAfterLayout() {
        runCatching { applyBatteryTrackMaterial(batteryTrack) }
        batteryTrack.post {
            runCatching { applyBatteryTrackMaterial(batteryTrack) }
            batteryTrack.invalidateOutline()
        }
    }

    private fun applySurfaceMaterialsAfterLayout() {
        shortcutSurfaceLayers.forEach { layer ->
            runCatching { applyShortcutSurfaceMaterial(layer) }
            layer.post {
                runCatching { applyShortcutSurfaceMaterial(layer) }
                layer.invalidateOutline()
            }
        }
    }

    private fun updateBatteryFill() {
        if (!::batteryBar.isInitialized || batteryBar.width <= 0) return
        val fillWidth = (batteryBar.width * batteryValue / 100f).roundToInt()
        batteryFill.layoutParams = (batteryFill.layoutParams as FrameLayout.LayoutParams).apply {
            width = fillWidth.coerceAtLeast(if (batteryValue > 0) dp(5) else 0)
        }
        batteryFill.requestLayout()
    }

    private fun style(view: TextView, size: Float) {
        view.setTextColor(Color.WHITE)
        view.textSize = size
        view.includeFontPadding = false
        view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        view.gravity = Gravity.CENTER_VERTICAL
        view.maxLines = 1
    }

    private fun rounded(color: Int, radius: Float) = android.graphics.drawable.GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun dp(value: Int): Int = (value * density + .5f).toInt()

    private fun formatMinute(value: Int): String = if (value in 1 until 24 * 60) {
        String.format(Locale.US, "%02d:%02d", value / 60, value % 60)
    } else {
        "--:--"
    }

    private fun formatAlarmText(value: String): String {
        val text = value.trim()
        val match = Regex(
            "^(.*?)(\\d{1,2}\\s*[:：]\\s*\\d{2}(?:\\s*(?:[AaPp][Mm]|上午|下午))?)$",
        ).matchEntire(text) ?: return text
        val prefix = match.groupValues[1].trim()
        val time = match.groupValues[2].trim()
        // The system's formatted alarm often includes a weekday (for example, "周二 7:00").
        // Weekday-only prefixes add no useful information in this compact lock-screen slot.
        val weekdayOnly = Regex("^(?:周|星期)[一二三四五六日天]$").matches(prefix)
        return if (prefix.isEmpty() || weekdayOnly) time else "$prefix\n$time"
    }

    /** Resize the alarm text slot only when a date line is present. */
    private fun updateAlarmTextSpacing() {
        val params = alarmValueLayoutParams ?: return
        val isMultiline = alarmTime.text?.contains('\n') == true
        val targetMargin = if (isMultiline) {
            (alarmValueDefaultTopMarginPx * .35f).roundToInt()
        } else {
            alarmValueDefaultTopMarginPx
        }
        val targetHeight = if (isMultiline && alarmValueMultilineHeightPx > 0) {
            alarmValueMultilineHeightPx
        } else {
            alarmValueDefaultHeightPx
        }
        if (params.topMargin == targetMargin && params.height == targetHeight) return
        params.topMargin = targetMargin
        params.height = targetHeight
        alarmTime.layoutParams = params
        alarmTime.requestLayout()
    }
}

/** Keeps the widget attached to SystemUI's lock-screen shortcut host and follows layout rebuilds. */
internal class LockscreenWidgetController(
    private val host: ViewGroup,
    private val leftShortcut: View,
    private val rightShortcut: View,
    private val preferences: android.content.SharedPreferences,
    private val classLoader: ClassLoader,
    private val applyBatteryTrackMaterial: (View) -> Unit,
    private val applyShortcutSurfaceMaterial: (View) -> Unit,
) {
    private val context = host.context
    private val handler = Handler(Looper.getMainLooper())
    private var view: LockscreenWidgetView? = null
    private var displayedItemMask = -1
    private var displayedItemOrder: List<Int> = emptyList()
    private var displayedSignatureType = -1
    private var displayedSignatureVersion = Long.MIN_VALUE
    private var displayedSignatureColor = 0
    private var displayedSignatureScale = -1
    private var displayedSignatureBackground = true
    private var positionPosted = false
    private var followerPosted = false
    private var targetVisible = false
    private var appearanceAnimating = false
    private var hideAnimationRunning = false
    private var lastLockscreenVisible: Boolean? = null
    private var notificationStack: WeakReference<View>? = null
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_LOCKSCREEN_WIDGET_ENABLED || key == KEY_LOCKSCREEN_WIDGET_HIDE_ON_AOD) {
            host.post(::refresh)
        }
    }
    // Depth clocks can split the visible hour and minute into independent roots. Track our
    // contribution per root so their own AOD and gesture translations remain untouched.
    private val appliedClockAvoidanceOffsets = WeakHashMap<View, Float>()
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 30_000L)
        }
    }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        val lockscreenVisible = isLockscreenVisible()
        if (lockscreenVisible != lastLockscreenVisible) {
            lastLockscreenVisible = lockscreenVisible
            refresh()
        }
        schedulePosition()
    }
    private val animationFollower = object : Runnable {
        override fun run() {
            val widget = view
            if (targetVisible && widget?.visibility == View.VISIBLE) {
                position()
                host.postOnAnimation(this)
            } else {
                followerPosted = false
            }
        }
    }

    init {
        host.clipChildren = false
        host.clipToPadding = false
        LockscreenWidgetSceneState.register(this)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        host.rootView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        leftShortcut.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePosition() }
        rightShortcut.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePosition() }
        handler.post(refreshRunnable)
    }

    fun destroy() {
        LockscreenWidgetSceneState.unregister(this)
        runCatching { preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
        runCatching { host.rootView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener) }
        restoreClockPosition()
        runCatching { view?.let(host::removeView) }
        handler.removeCallbacksAndMessages(null)
        host.removeCallbacks(animationFollower)
        followerPosted = false
        view = null
        displayedItemMask = -1
        displayedItemOrder = emptyList()
        displayedSignatureType = -1
        displayedSignatureVersion = Long.MIN_VALUE
        displayedSignatureColor = 0
        displayedSignatureScale = -1
        displayedSignatureBackground = true
    }

    internal fun onSceneVisibilityChanged() {
        val updateVisibility = update@{
            val widget = view ?: return@update
            if (LockscreenWidgetSceneState.hasBlockingOverlay) {
                hideWidget(widget, immediately = true)
            }
            refresh()
        }
        // Editor and charge callbacks normally run on SystemUI's main thread. Hide in that
        // callback before either overlay is attached, so the widget cannot leak into its first frame.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateVisibility()
        } else {
            host.post(updateVisibility)
        }
    }

    private fun refresh() {
        val rawItemMask = preferences.readLockscreenWidgetItems()
        val requestedItemOrder = preferences.readLockscreenWidgetOrder(rawItemMask)
        val requestedItemMask = requestedItemOrder.fold(0) { mask, flag -> mask or flag }
        val requestedSignatureType = preferences.getInt(
            KEY_LOCKSCREEN_WIDGET_SIGNATURE_TYPE,
            LOCKSCREEN_WIDGET_SIGNATURE_NONE,
        ).coerceIn(LOCKSCREEN_WIDGET_SIGNATURE_NONE, LOCKSCREEN_WIDGET_SIGNATURE_VECTOR)
        val requestedSignatureVersion = preferences.getLong(KEY_LOCKSCREEN_WIDGET_SIGNATURE_VERSION, 0L)
        val requestedSignatureColor = preferences.getInt(KEY_LOCKSCREEN_WIDGET_SIGNATURE_COLOR, Color.WHITE)
        val requestedSignatureScale = preferences.getInt(KEY_LOCKSCREEN_WIDGET_SIGNATURE_SCALE, 100).coerceIn(25, 200)
        val requestedSignatureBackground = preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_SIGNATURE_BACKGROUND, true)
        if (view != null && (
                displayedItemMask != requestedItemMask ||
                    displayedItemOrder != requestedItemOrder ||
                    displayedSignatureType != requestedSignatureType ||
                    displayedSignatureVersion != requestedSignatureVersion ||
                    displayedSignatureColor != requestedSignatureColor ||
                    displayedSignatureScale != requestedSignatureScale ||
                    displayedSignatureBackground != requestedSignatureBackground
            )
        ) {
            restoreClockPosition()
            view?.animate()?.cancel()
            view?.let { old -> runCatching { (old.parent as? ViewGroup)?.removeView(old) } }
            view = null
            displayedItemMask = -1
            displayedItemOrder = emptyList()
        }
        val widget = view ?: LockscreenWidgetView(
            context = context,
            itemMask = requestedItemMask,
            itemOrder = requestedItemOrder,
            applyBatteryTrackMaterial = applyBatteryTrackMaterial,
            applyShortcutSurfaceMaterial = applyShortcutSurfaceMaterial,
            foregroundColorProvider = ::resolveForegroundColor,
            signatureType = requestedSignatureType,
            signatureColor = requestedSignatureColor,
            signatureScale = requestedSignatureScale,
            signatureBackground = requestedSignatureBackground,
        ).also {
            view = it
            displayedItemMask = requestedItemMask
            displayedItemOrder = requestedItemOrder
            displayedSignatureType = requestedSignatureType
            displayedSignatureVersion = requestedSignatureVersion
            displayedSignatureColor = requestedSignatureColor
            displayedSignatureScale = requestedSignatureScale
            displayedSignatureBackground = requestedSignatureBackground
            host.addView(it, ViewGroup.LayoutParams(dp(WIDGET_WIDTH_DP), dp(WIDGET_HEIGHT_DP)))
        }
        // Recover if a vendor layout rebuild detached the widget from its stable root.
        if (widget.parent !== host) {
            (widget.parent as? ViewGroup)?.removeView(widget)
            host.addView(widget, ViewGroup.LayoutParams(dp(WIDGET_WIDTH_DP), dp(WIDGET_HEIGHT_DP)))
        }
        val shouldShow = preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_ENABLED, false) &&
            isLockscreenVisible() &&
            !(preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_HIDE_ON_AOD, false) &&
                LockscreenWidgetSceneState.aodActive)
        if (!shouldShow) {
            hideWidget(
                widget,
                immediately = LockscreenWidgetSceneState.hasBlockingOverlay ||
                    (LockscreenWidgetSceneState.aodActive &&
                        preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_HIDE_ON_AOD, false)),
            )
            return
        }
        showWidget(widget)
        widget.bind(readData())
        schedulePosition()
    }

    private fun resolveForegroundColor(): Int = when (
        preferences.getInt(
            KEY_LOCKSCREEN_WIDGET_COLOR_MODE,
            LOCKSCREEN_WIDGET_COLOR_AUTO,
        ).coerceIn(LOCKSCREEN_WIDGET_COLOR_LIGHT, LOCKSCREEN_WIDGET_COLOR_AUTO)
    ) {
        LOCKSCREEN_WIDGET_COLOR_LIGHT -> Color.WHITE
        LOCKSCREEN_WIDGET_COLOR_DARK -> WIDGET_DARK_FOREGROUND
        else -> wallpaperForegroundColor(context)
    }

    private fun showWidget(widget: LockscreenWidgetView) {
        val wasTargetVisible = targetVisible
        targetVisible = true
        hideAnimationRunning = false
        if (widget.visibility != View.VISIBLE) {
            widget.animate().cancel()
            widget.visibility = View.VISIBLE
            widget.alpha = 0f
            widget.scaleX = 0.92f
            widget.scaleY = 0.92f
            appearanceAnimating = true
            widget.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(260L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (targetVisible) appearanceAnimating = false
                    }
                }).start()
        } else if (!wasTargetVisible) {
            // Reverse an in-progress hide (for example when the bouncer closes)
            // from the current visual state instead of leaving the widget faded.
            widget.animate().cancel()
            appearanceAnimating = true
            widget.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (targetVisible) appearanceAnimating = false
                    }
                }).start()
        } else if (!appearanceAnimating) {
            // Keep the widget visually coupled to the shortcut surfaces during
            // AOD/gesture transitions; position() updates this every frame.
            syncShortcutAppearance(widget)
        }
        startFollower()
    }

    private fun hideWidget(widget: LockscreenWidgetView, immediately: Boolean = false) {
        targetVisible = false
        if (immediately) {
            widget.animate().cancel()
            widget.visibility = View.GONE
            widget.alpha = 1f
            widget.scaleX = 1f
            widget.scaleY = 1f
            appearanceAnimating = false
            hideAnimationRunning = false
            restoreClockPosition()
            stopFollower()
            return
        }
        if (hideAnimationRunning && widget.visibility == View.VISIBLE) return
        if (widget.visibility != View.VISIBLE) {
            widget.visibility = View.GONE
            restoreClockPosition()
            stopFollower()
            return
        }
        widget.animate().cancel()
        appearanceAnimating = true
        hideAnimationRunning = true
        widget.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f)
            .setDuration(220L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!targetVisible) {
                        widget.visibility = View.GONE
                        restoreClockPosition()
                        appearanceAnimating = false
                        hideAnimationRunning = false
                        stopFollower()
                    }
                }
            }).start()
    }

    private fun startFollower() {
        if (followerPosted) return
        followerPosted = true
        host.postOnAnimation(animationFollower)
    }

    private fun stopFollower() {
        host.removeCallbacks(animationFollower)
        followerPosted = false
    }

    private fun readData(): LockscreenWidgetData {
        val root = host.rootView
        val systemWeather = readSystemWeather()
        val weatherRoot = findViewByIdName(root, setOf("weather_smartspace_view", "weather_smartspace_view_large"))
        val texts = ArrayList<String>()
        weatherRoot?.let { collectTexts(it, texts) }
        val joined = texts.joinToString(" ")
        val fallbackTemperature = Regex("[+-]?\\d{1,3}°?").find(joined)?.value?.let {
            if (it.endsWith("°")) it else "$it°"
        } ?: "--°"
        val fallbackCondition = texts.firstOrNull { it.matches(Regex(".*[\\p{IsHan}].*")) && it.length <= 8 }
            ?.replace(Regex("[0-9°+\\- ]"), "")?.takeIf { it.isNotBlank() } ?: "晴"
        val fallbackCity = texts.firstOrNull { it.length in 2..8 && it.matches(Regex(".*[\\p{IsHan}].*")) && it != fallbackCondition }
            ?: "天气"
        val high = Regex("(?i)(?:H|最高)\\s*[:：]?\\s*([+-]?\\d{1,3})").find(joined)?.groupValues?.getOrNull(1)
        val low = Regex("(?i)(?:L|最低)\\s*[:：]?\\s*([+-]?\\d{1,3})").find(joined)?.groupValues?.getOrNull(1)
        val fallbackHighLow = if (high != null || low != null) "H: ${high ?: "--"}°  L: ${low ?: "--"}°" else "H: --°  L: --°"
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val battery = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } ?: 0
        val deviceName = preferences.getString(KEY_LOCKSCREEN_WIDGET_DEVICE_NAME, "")
            .orEmpty().trim().ifBlank { readRoProductMarketName() }
        val icon = systemWeather?.icon ?: findWeatherIcon(weatherRoot)
            ?: context.resources.getIdentifier("weather_icon_0", "drawable", context.packageName)
                .takeIf { it != 0 }?.let { context.getDrawable(it) }
        return LockscreenWidgetData(
            systemWeather?.temperature ?: fallbackTemperature,
            systemWeather?.condition ?: fallbackCondition,
            systemWeather?.city ?: fallbackCity,
            systemWeather?.highLow ?: fallbackHighLow,
            battery,
            deviceName,
            icon,
            systemWeather?.sunrise ?: 0,
            systemWeather?.sunriseTomorrow ?: 0,
            systemWeather?.sunset ?: 0,
            // Both step layouts use the same HealthBean value. The wide layout has its own
            // flag, so include it here or it would always render the no-data placeholder.
            if (displayedItemMask and (LOCKSCREEN_WIDGET_ITEM_STEPS or LOCKSCREEN_WIDGET_ITEM_STEPS_WIDE) != 0) {
                readSystemSteps()
            } else {
                null
            },
            systemWeather?.humidity ?: "--",
            systemWeather?.aqi ?: "--",
            systemWeather?.feelsLike ?: "--°",
            systemWeather?.feelsLikeIcon,
            systemWeather?.wind ?: "--",
            systemWeather?.windIcon,
            if (displayedItemMask and LOCKSCREEN_WIDGET_ITEM_STAND != 0) readSystemStandCount() else null,
            if (displayedItemMask and LOCKSCREEN_WIDGET_ITEM_ALARM != 0) readNextAlarm() else null,
            if (displayedItemMask and LOCKSCREEN_WIDGET_ITEM_SCHEDULE != 0) readNextSchedule() else null,
        )
    }

    /** Mirrors the value consumed by HyperOS lockscreen clock templates as `next_alarm_time`. */
    private fun readNextAlarm(): String? = runCatching {
        Settings.System.getString(context.contentResolver, SYSTEM_NEXT_ALARM_FORMATTED)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun readNextSchedule(): ScheduleEntry? = runCatching {
        val uri = SettingsAppearanceSources.lockscreenScheduleUri()
        val projection = arrayOf(
            SettingsAppearanceProvider.COLUMN_SCHEDULE_TITLE,
            SettingsAppearanceProvider.COLUMN_SCHEDULE_BEGIN,
            SettingsAppearanceProvider.COLUMN_SCHEDULE_ALL_DAY,
        )
        context.contentResolver.query(uri, projection, null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@runCatching null
                val title = cursor.getString(0).orEmpty().trim().ifBlank { "日程" }
                val begin = cursor.getLong(1)
                val allDay = cursor.getInt(2) != 0
                val format = SimpleDateFormat(if (allDay) "MM/dd 全天" else "MM/dd HH:mm", Locale.getDefault())
                ScheduleEntry(title, format.format(java.util.Date(begin)))
            }
    }.getOrNull()

    private fun readSystemWeather(): SystemWeatherSnapshot? = runCatching {
        val dataUtils = Class.forName(CLOCK_DATA_UTILS_CLASS, false, classLoader)
        val getter = dataUtils.getMethod("getWeatherBean", String::class.java, WeakReference::class.java)
        val bean = listOf("2", "1").asSequence()
            .mapNotNull { type -> runCatching { getter.invoke(null, type, WeakReference(context)) }.getOrNull() }
            .firstOrNull() ?: return@runCatching null
        fun call(name: String, vararg args: Any?): Any? = runCatching {
            val method = bean.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
                ?: return@runCatching null
            method.invoke(bean, *args)
        }.getOrNull()
        val valid = call("getTemperatureValid") as? Boolean ?: true
        val temperature = if (valid) "${(call("getTemperature") as? Number)?.toInt() ?: 0}°" else "--°"
        val condition = (call("getDescription") as? String).orEmpty().ifBlank { "晴" }
        val city = (call("getCityName") as? String).orEmpty().ifBlank { "天气" }
        val high = (call("getHighestTemperature") as? Number)?.toInt()
        val low = (call("getLowestTemperature") as? Number)?.toInt()
        val highLow = "H: ${high ?: "--"}°  L: ${low ?: "--"}°"
        val now = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val sunrise = (call("getSunriseMinuteTime") as? Number)?.toInt() ?: 0
        // SystemUI's display path trusts the formatted tomorrow value. Use it first because
        // several HyperOS revisions expose a stale implementation of the minute getter.
        val sunriseTomorrow = parseTimeOfDay(call("getSunriseTomorrowTimeString") as? String)
            ?: (call("getSunriseTomorrowMinuteTime") as? Number)?.toInt()
            ?: 0
        val sunset = (call("getSunsetMinuteTime") as? Number)?.toInt() ?: 0
        val night = sunrise > 0 && sunset > 0 && (now < sunrise || now > sunset)
        val iconId = call("getIconResId", night, false) as? Number
        val icon = iconId?.toInt()?.takeIf { it != 0 }?.let { context.getDrawable(it) }
        val humidity = (call("getHumidity") as? Number)?.toInt()
            ?.takeIf { it in 0..100 }?.let { "$it%" } ?: "--"
        val aqi = if (call("isAQIDateValid") as? Boolean == true) {
            (call("getAQILevel") as? String).orEmpty().ifBlank { "--" }
        } else {
            "--"
        }
        val feelsLikeValid = call("getFeelTemperatureValid") as? Boolean == true
        val feelsLike = if (feelsLikeValid) {
            "${(call("getSomatosensoryTemperature") as? Number)?.toInt() ?: 0}°"
        } else {
            "--°"
        }
        val feelsLikeIcon = (call("getSomatosensoryResId", 0) as? Number)?.toInt()
            ?.takeIf { feelsLikeValid && it != 0 }?.let { context.getDrawable(it) }
        val windIcon = (call("getWindIconResId") as? Number)?.toInt()
            ?.takeIf { it != 0 }?.let { context.getDrawable(it) }
        val windDirection = (call("getWindDescResIdFull") as? Number)?.toInt()
            ?.takeIf { it != 0 }?.let { id -> runCatching { context.getString(id) }.getOrNull() }
            .orEmpty()
        val windStrength = (call("getWindStrength") as? String).orEmpty()
        val wind = if (windDirection.isNotBlank() && windStrength.isNotBlank()) {
            "$windDirection ${windStrength}级"
        } else {
            "--"
        }
        SystemWeatherSnapshot(
            temperature, condition, city, highLow, icon, sunrise, sunriseTomorrow, sunset,
            humidity, aqi, feelsLike, feelsLikeIcon, wind, windIcon,
        )
    }.getOrNull()

    /**
     * SystemUI itself queries Xiaomi Health through DataUtils using health type 500. Calling the
     * same API keeps the widget on the ROM's permission path and avoids declaring a provider
     * dependency in the module app. Every reflection failure is a no-data state, never a crash.
     */
    private fun readSystemSteps(): Int? = runCatching {
        readSystemHealthCount(500, "getStepCountNow")
    }.getOrNull()

    /** Health type 504 is the SystemUI path for the standing-count widget. */
    private fun readSystemStandCount(): Int? = runCatching {
        readSystemHealthCount(504, "getStandCountNow")
    }.getOrNull()

    private fun readSystemHealthCount(type: Int, resultGetter: String): Int? {
        val dataUtils = Class.forName(CLOCK_DATA_UTILS_CLASS, false, classLoader)
        val healthBean = Class.forName("com.miui.clock.module.HealthBean", false, classLoader)
        val getter = dataUtils.methods.firstOrNull { method ->
            method.name == "getHealthBean" && method.parameterCount == 3 &&
                method.parameterTypes[0] == WeakReference::class.java &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                method.parameterTypes[2].isAssignableFrom(healthBean)
        } ?: return null
        val bean = getter.invoke(null, WeakReference(context), type, null) ?: return null
        val steps = bean.javaClass.methods.firstOrNull { method ->
            method.name == resultGetter && method.parameterCount == 0
        }?.invoke(bean) as? Number ?: return null
        return steps.toInt().takeIf { it >= 0 }
    }

    private fun isLockscreenVisible(): Boolean = runCatching {
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return@runCatching false
        val locked = keyguard.isKeyguardLocked || keyguard.isDeviceLocked
        locked && !LockscreenWidgetSceneState.hasBlockingOverlay && !hasChargingAnimationOverlay() &&
            !isBouncerShowing()
    }.getOrDefault(false)

    /** Covers a charging overlay already present when the widget controller is created. */
    private fun hasChargingAnimationOverlay(): Boolean {
        fun visit(candidate: View): Boolean {
            val name = candidate.javaClass.name
            if (name.startsWith("com.miui.charge.") ||
                name.startsWith("com.android.systemui.charging.")
            ) return true
            return candidate is ViewGroup && (0 until candidate.childCount).any { index ->
                visit(candidate.getChildAt(index))
            }
        }
        return visit(host.rootView)
    }

    /** The password/PIN bouncer is still technically keyguard-locked, but the widget must hide. */
    private fun isBouncerShowing(): Boolean {
        var found = false
        fun visit(view: View) {
            if (found || view.visibility != View.VISIBLE || view.alpha <= 0.01f ||
                view.width <= 0 || view.height <= 0
            ) return
            val name = view.javaClass.name.lowercase()
            if (name.contains("bouncer") || name.contains("keyguardpassword") ||
                name.contains("keyguardpin") || name.contains("pinbasedinput") ||
                name.contains("securitycontainer") || name.contains("passwordview")
            ) {
                found = true
                return
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(host.rootView)
        return found
    }

    private fun findWeatherIcon(root: View?): Drawable? {
        if (root == null) return null
        if (root is ImageView && viewIdName(root) == "weather_icon") {
            return root.drawable?.constantState?.newDrawable()?.mutate()
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) findWeatherIcon(root.getChildAt(i))?.let { return it }
        return null
    }

    private fun collectTexts(root: View, out: MutableList<String>) {
        if (root is TextView) root.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let(out::add)
        if (root is ViewGroup) for (i in 0 until root.childCount) collectTexts(root.getChildAt(i), out)
    }

    private fun findViewByIdName(root: View, names: Set<String>): View? {
        if (viewIdName(root) in names) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) findViewByIdName(root.getChildAt(i), names)?.let { return it }
        return null
    }

    private fun viewIdName(view: View): String = runCatching {
        view.resources.getResourceEntryName(view.id)
    }.getOrDefault("")

    private fun schedulePosition() {
        if (positionPosted) return
        positionPosted = true
        host.postOnAnimation {
            positionPosted = false
            position()
        }
    }

    private fun position() {
        val widget = view ?: return
        if (widget.visibility != View.VISIBLE || host.width <= 0 || host.height <= 0) return
        val hostLocation = IntArray(2).also(host::getLocationOnScreen)
        val leftLocation = IntArray(2).also(leftShortcut::getLocationOnScreen)
        val rightLocation = IntArray(2).also(rightShortcut::getLocationOnScreen)
        val centerX = ((leftLocation[0] + leftShortcut.width / 2f) +
            (rightLocation[0] + rightShortcut.width / 2f)) / 2f - hostLocation[0]
        val centerY = ((leftLocation[1] + leftShortcut.height / 2f) +
            (rightLocation[1] + rightShortcut.height / 2f)) / 2f - hostLocation[1]
        val width = min(dp(WIDGET_WIDTH_DP), (host.width - dp(32f)).coerceAtLeast(dp(280f)))
        val height = dp(WIDGET_HEIGHT_DP)
        if (widget.layoutParams.width != width || widget.layoutParams.height != height) {
            widget.layoutParams = widget.layoutParams.apply { this.width = width; this.height = height }
            schedulePosition()
            return
        }
        if (widget.width <= 0 || widget.height <= 0) return
        widget.translationX = centerX - widget.width / 2f - widget.left
        val normalTargetY = centerY - widget.height / 2f - dp(WIDGET_VERTICAL_OFFSET_DP) - widget.top
        val notificationTargetY = if (preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_NOTIFICATION_AVOID, true)) {
            firstVisibleNotificationTop()?.let { notificationTop ->
                notificationTop - dp(NOTIFICATION_SAFE_GAP_DP) - widget.height - widget.top
            }
        } else {
            null
        }
        widget.translationY = notificationTargetY?.let { minOf(normalTargetY, it) } ?: normalTargetY
        adjustClockForWidget(widget)
        if (!appearanceAnimating) syncShortcutAppearance(widget)
    }

    /**
     * MIUI's depth clock may split the visible hour and minute into a primary clock and a
     * foreground-clock copy. Keep every active time layer clear of the widget, while preserving
     * each layer's own gesture and AOD translation.
     */
    private fun adjustClockForWidget(widget: LockscreenWidgetView) {
        val targets = findLockscreenClockTargets(host.rootView)
            .filter { target ->
                target.clock.isAttachedToWindow && isVisibleForNotificationAvoidance(target.clock)
            }
        if (targets.isEmpty()) {
            restoreClockPosition()
            return
        }
        restoreStaleClockPositions(targets.mapTo(LinkedHashSet()) { it.clock })
        val widgetLocation = IntArray(2).also(widget::getLocationOnScreen)
        val candidateBottoms = buildList {
            targets.forEach { target ->
                val containerLocation = IntArray(2).also(target.container::getLocationOnScreen)
                target.container.getClockBottomForNotificationAvoidance()?.let { bottom ->
                    add(containerLocation[1] + bottom)
                }
                // All-in-one clocks animate their contents inside a full-height root. Its
                // measured height is unusable, while mClockViewRect tracks the time glyph.
                val clockLocation = IntArray(2).also(target.clock::getLocationOnScreen)
                val previousOffset = appliedClockAvoidanceOffsets[target.clock] ?: 0f
                target.clock.getRenderedClockContentBottom()?.let { bottom ->
                    add(clockLocation[1] + bottom - previousOffset)
                }
            }
        }
        val clockBottomOnScreen = candidateBottoms.maxOrNull() ?: run {
            restoreClockPosition()
            return
        }
        val requiredOffset = minOf(
            0f,
            widgetLocation[1] - dp(NOTIFICATION_SAFE_GAP_DP) - clockBottomOnScreen,
        )
        targets.forEach { target ->
            val clock = target.clock
            val previousOffset = appliedClockAvoidanceOffsets[clock] ?: 0f
            val systemOffset = clock.translationY - previousOffset
            clock.translationY = systemOffset + requiredOffset
            appliedClockAvoidanceOffsets[clock] = requiredOffset
        }
    }

    private fun restoreClockPosition() {
        appliedClockAvoidanceOffsets.entries.toList().forEach { (clock, offset) ->
            if (clock.isAttachedToWindow) {
                clock.translationY -= offset
            }
        }
        appliedClockAvoidanceOffsets.clear()
    }

    private fun restoreStaleClockPositions(activeClocks: Set<View>) {
        appliedClockAvoidanceOffsets.entries.toList()
            .filter { (clock, _) -> clock !in activeClocks }
            .forEach { (clock, offset) ->
                if (clock.isAttachedToWindow) {
                    clock.translationY -= offset
                }
                appliedClockAvoidanceOffsets.remove(clock)
            }
    }

    /** Returns the top of the first real, visible notification relative to the widget host. */
    private fun firstVisibleNotificationTop(): Float? {
        val stack = notificationStack?.get()?.takeIf { it.isAttachedToWindow }
            ?: findViewByIdName(host.rootView, setOf("notification_stack_scroller"))?.also {
                notificationStack = WeakReference(it)
            }

        var top: Int? = null
        fun recordTop(candidate: View) {
            val location = IntArray(2).also(candidate::getLocationOnScreen)
            top = top?.let { minOf(it, location[1]) } ?: location[1]
        }
        fun visitNotificationRow(candidate: View) {
            if (!isVisibleForNotificationAvoidance(candidate)) return
            if (candidate.javaClass.name.contains("ExpandableNotificationRow")) {
                recordTop(candidate)
                return
            }
            if (candidate is ViewGroup) {
                for (index in 0 until candidate.childCount) visitNotificationRow(candidate.getChildAt(index))
            }
        }
        fun visitMediaHeader(candidate: View) {
            if (!isVisibleForNotificationAvoidance(candidate)) return
            if (candidate.isMediaHeaderForNotificationAvoidance()) {
                recordTop(candidate)
                return
            }
            if (candidate is ViewGroup) {
                for (index in 0 until candidate.childCount) visitMediaHeader(candidate.getChildAt(index))
            }
        }

        // Standard notifications are children of the stack. MIUI's media header is not an
        // ExpandableNotificationRow and may instead be hosted directly below the lockscreen root.
        stack?.takeIf(::isVisibleForNotificationAvoidance)?.let(::visitNotificationRow)
        host.rootView?.let(::visitMediaHeader)
        val notificationScreenTop = top ?: return null
        val hostLocation = IntArray(2).also(host::getLocationOnScreen)
        return notificationScreenTop - hostLocation[1].toFloat()
    }

    private fun View.isMediaHeaderForNotificationAvoidance(): Boolean {
        var type: Class<*>? = javaClass
        while (type != null) {
            if (type.name == "com.android.systemui.statusbar.notification.mediacontrol.MiuiMediaHeaderView") {
                return true
            }
            type = type.superclass
        }
        return false
    }

    private fun isVisibleForNotificationAvoidance(candidate: View): Boolean =
        candidate.visibility == View.VISIBLE && candidate.alpha > 0.01f &&
            candidate.width > 0 && candidate.height > dp(24f)

    private fun syncShortcutAppearance(widget: LockscreenWidgetView) {
        // The lock-screen clock and shortcut surfaces use the same swipe/AOD
        // transform.  Follow the most contracted/faded participant so the
        // widget never appears to lag behind either visual element.
        val clock = findClockView(host.rootView)
        val animatedViews = listOfNotNull(leftShortcut, rightShortcut, clock)
        val alpha = animatedViews.minOf { it.alpha }.coerceIn(0f, 1f)
        val scaleX = animatedViews.minOf { it.scaleX }.coerceIn(0.75f, 1.2f)
        val scaleY = animatedViews.minOf { it.scaleY }.coerceIn(0.75f, 1.2f)
        widget.alpha = alpha
        widget.scaleX = scaleX
        widget.scaleY = scaleY
    }

    private fun findClockView(root: View?): View? {
        if (root == null) return null
        val clockIds = setOf(
            "keyguard_clock",
            "miui_keyguard_clock",
            "miui_keyguard_clock_container",
            "lockscreen_clock_view_large",
            "lockscreen_clock_view_small",
            "clock_container",
        )
        return findViewByIdName(root, clockIds)
    }

    /** Locates the large lock-screen template, excluding the status-bar's small clock. */
    private fun findPrimaryLockscreenClock(root: View?): LockscreenClockTarget? {
        if (root == null) return null
        val container = findViewByClassName(root, "com.android.keyguard.clock.KeyguardClockContainer")
        return container?.let { containerView ->
            readMiuiClockView(containerView)?.let { LockscreenClockTarget(it, containerView) }
        }
    }

    /**
     * In depth mode, MIUI renders the portion in front of the subject in the normal clock
     * container and the portion behind it in a separate foreground-clock container. Both roots
     * can carry a text_area, so translating both also keeps the date and lunar date aligned with
     * the visible time when the entire clock is behind the subject.
     */
    private fun findLockscreenClockTargets(root: View?): List<LockscreenClockTarget> {
        if (root == null) return emptyList()
        val targets = LinkedHashMap<View, LockscreenClockTarget>()
        findPrimaryLockscreenClock(root)?.let { target -> targets[target.clock] = target }
        findViewByIdName(
            root,
            setOf("miui_keyguard_foreground_clock_container", "keyguard_foreground_clock_container"),
        )?.let { container ->
            findVendorClockRoot(container)?.let { clock ->
                targets[clock] = LockscreenClockTarget(clock, container)
            }
        }
        return targets.values.toList()
    }

    private fun findVendorClockRoot(root: View): View? {
        if (root.javaClass.name.startsWith("com.miui.clock.")) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findVendorClockRoot(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun findViewByClassName(root: View, className: String): View? {
        if (root.javaClass.name == className) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findViewByClassName(root.getChildAt(index), className)?.let { return it }
            }
        }
        return null
    }

    private fun readMiuiClockView(container: View): View? = runCatching {
        val controllerField = container.javaClass.getDeclaredField("mMiuiClockController")
        controllerField.isAccessible = true
        val controller = controllerField.get(container) ?: return@runCatching null
        val clockField = controller.javaClass.getDeclaredField("mClockView")
        clockField.isAccessible = true
        clockField.get(controller) as? View
    }.getOrNull()

    /**
     * MIUI calculates this per clock template for its own notification layout. It reflects the
     * bottom of visible clock content, unlike mClockView.height which can span the whole screen.
     */
    private fun View.getClockBottomForNotificationAvoidance(): Float? = runCatching {
        (javaClass.getMethod("getClockBottom").invoke(this) as? Number)
            ?.toFloat()
            ?.takeIf { it > 0f }
    }.getOrNull()

    private fun View.getRenderedClockContentBottom(): Float? = runCatching {
        (javaClass.getMethod("getMClockViewRect").invoke(this) as? Rect)
            ?.takeIf { !it.isEmpty }
            ?.bottom
            ?.toFloat()
    }.getOrNull()

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density + .5f).toInt()
}
