// SPDX-License-Identifier: AGPL-3.0-or-later
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
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
private const val WIDGET_PRESENTATION_POSITION_DURATION_MS = 360L
private const val WIDGET_COMBINATION_TWO_HEIGHT_DP = 64f
private const val WIDGET_VERTICAL_OFFSET_DP = 80f
private const val WIDGET_CLOCK_BOTTOM_GAP_DP = 14f
private const val WIDGET_TEXT_SIZE_SP = 15.3f
private const val WIDGET_COMBINATION_METRIC_TEXT_SIZE_SP = 12.24f
private const val WIDGET_ROW_HEIGHT_DP = 20
private const val WIDGET_WEATHER_ROW_HEIGHT_DP = 22
private const val WIDGET_CITY_ROW_TOP_DP = 22
private const val WIDGET_THIRD_ROW_TOP_DP = 42
private const val DETAILED_WIDGET_WIDTH_DP = 151
private const val DETAILED_WIDGET_PAIR_GAP_DP = 27
private const val DETAILED_WIDGET_BACKGROUND_CONTENT_SCALE = .88f
private const val NOTIFICATION_SAFE_GAP_DP = 16f
private const val MIN_CLOCK_AVOIDANCE_SCALE = 0.2f
private const val WEATHER_ICON_SIZE_DP = 20
private const val MOBILE_ICON_WIDTH_DP = 19
private const val MOBILE_ICON_HEIGHT_DP = 21
private const val WIDGET_DARK_FOREGROUND = 0xFF1A1A1A.toInt()
private const val STEPS_GOAL = 10000
private const val STAND_GOAL = 12
private const val SYSTEM_NEXT_ALARM_FORMATTED = "next_alarm_formatted"
private const val WALLPAPER_COLOR_CACHE_TTL_MS = 15_000L

private object WallpaperForegroundColorCache {
    private var cachedWallpaperId = Int.MIN_VALUE
    private var cachedColor = Color.WHITE
    private var cachedTimestampMs = 0L

    @Synchronized
    fun resolve(context: Context): Int = runCatching {
        val wallpaperManager = WallpaperManager.getInstance(context)
        val wallpaperId = runCatching {
            wallpaperManager.getWallpaperId(WallpaperManager.FLAG_LOCK)
        }.getOrDefault(-1)
        val now = SystemClock.elapsedRealtime()
        if (wallpaperId > 0 && wallpaperId == cachedWallpaperId) {
            return@runCatching cachedColor
        }
        if (wallpaperId <= 0 && cachedTimestampMs > 0L && now - cachedTimestampMs < WALLPAPER_COLOR_CACHE_TTL_MS) {
            return@runCatching cachedColor
        }
        val computed = sample(wallpaperManager)
        cachedWallpaperId = wallpaperId
        cachedColor = computed
        cachedTimestampMs = now
        computed
    }.getOrDefault(Color.WHITE)

    private fun sample(wallpaperManager: WallpaperManager): Int {
        val drawable = runCatching {
            wallpaperManager.getDrawable(WallpaperManager.FLAG_LOCK)
        }.getOrNull() ?: wallpaperManager.drawable
            ?: return Color.WHITE
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
        return if (totalLuminance / pixelCount >= 142.0) WIDGET_DARK_FOREGROUND else Color.WHITE
    }
}

/**
 * The editor and full-screen charging animation reuse the keyguard window. Track their explicit
 * SystemUI states instead of treating every keyguard-attached layout as an ordinary lockscreen.
 */
internal object LockscreenWidgetSceneState {
    @Volatile private var editorActive = false
    @Volatile private var chargingActive = false
    @Volatile private var controlCenterActive = false
    @Volatile var keyguardGoingAway = false
        private set
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
        val keyguardExitReset = active && keyguardGoingAway
        if (keyguardExitReset) keyguardGoingAway = false
        if (aodActive == active && !keyguardExitReset) return
        aodActive = active
        notifyControllers()
    }

    fun setKeyguardGoingAway(goingAway: Boolean) {
        if (keyguardGoingAway == goingAway) return
        keyguardGoingAway = goingAway
        notifyControllers()
    }

    private fun notifyControllers() {
        val snapshot = synchronized(controllers) { controllers.toList() }
        snapshot.forEach(LockscreenWidgetController::onSceneVisibilityChanged)
    }
}

private fun wallpaperForegroundColor(context: Context): Int =
    WallpaperForegroundColorCache.resolve(context)

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
    val batteryCharging: Boolean,
    val batteryTempTenthsC: Int?,
    val deviceName: String,
    val weatherIcon: Drawable?,
    val sunrise: Int,
    val sunriseTomorrow: Int,
    val sunset: Int,
    val steps: Int?,
    val stepsTarget: Int,
    val humidity: String,
    val aqi: String,
    val feelsLike: String,
    val feelsLikeIcon: Drawable?,
    val wind: String,
    val windIcon: Drawable?,
    val standCount: Int?,
    val standTarget: Int,
    val nextAlarm: String?,
    val nextSchedule: ScheduleEntry?,
    val uvIndex: Int?,
    val rainProbability: Int?,
    val pressureHpa: Int?,
    val bluetoothBattery: BluetoothBatterySnapshot,
    val worldClock: WorldClockSnapshot,
    val calendarWeekday: String,
    val calendarDay: String,
    val countdown: CountdownSnapshot,
    val memoTitle: String,
    val memoContent: String,
    val sleepMinutes: Int?,
    val soundMode: Int,
    val flashlightOn: Boolean,
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
    val uvIndex: Int?,
    val rainProbability: Int?,
    val pressureHpa: Int?,
)

/** A time-layer root and its vendor container that exposes visual clock bounds. */
private data class LockscreenClockTarget(
    val clock: View,
    val container: View,
    val timeContent: View?,
    val dateContent: View?,
    val movementRoot: View,
)

/** The transform contributed by widget avoidance to a time-only clock layer. */
private data class ClockScaleTransform(
    val previousScaleX: Float,
    val previousScaleY: Float,
    val previousPivotY: Float,
    val previousTranslationY: Float,
    val appliedScaleX: Float,
    val appliedScaleY: Float,
    val appliedPivotY: Float,
    val appliedTranslationY: Float,
)

private data class ClockTranslationTransform(
    val previousTranslationY: Float,
    val appliedTranslationY: Float,
)

/** Draws the long step widget's circular goal indicator and the platform shoe glyph. */
private class StepsProgressView(context: Context) : View(context) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var steps = 0
    private var goal = STEPS_GOAL
    private var foregroundColor = Color.WHITE
    private var icon: Drawable? = null

    fun bind(value: Int?, color: Int, drawable: Drawable?, target: Int = STEPS_GOAL) {
        steps = value?.coerceAtLeast(0) ?: 0
        goal = target.takeIf { it > 0 } ?: STEPS_GOAL
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
        val progress = (steps.toFloat() / goal).coerceIn(0f, 1f)
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

/** A lightweight native view for all selectable lock-screen widgets (legacy + iOS/OneUI extended). */
private class LockscreenWidgetView(
    context: Context,
    private val widgetIds: List<String>,
    private val interactiveEnabled: Boolean,
    private val onSoundModeClick: () -> Unit,
    private val onFlashlightClick: () -> Unit,
    private val applyBatteryTrackMaterial: (View) -> Unit,
    private val applyShortcutSurfaceMaterial: (View) -> Unit,
    private val foregroundColorProvider: () -> Int,
    private val signatureType: Int,
    private val signatureColor: Int,
    private val signatureScale: Int,
    private val signatureBackground: Boolean,
) : FrameLayout(context) {
    private val widgetIdSet: Set<String> = widgetIds.toSet()
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
    // Extended iOS / OneUI widgets
    private val uvGauge = CircularGaugeRingView(context)
    private val rainIcon = ImageView(context)
    private val rainValue = TextView(context)
    private val pressureGauge = CircularGaugeRingView(context)
    private val batteryRingGauge = CircularGaugeRingView(context)
    private val batteryTempIcon = ImageView(context)
    private val batteryTempValue = TextView(context)
    private val btIcon = ImageView(context)
    private val btPercent = TextView(context)
    private val btDeviceName = TextView(context)
    private val btTrack = ImageView(context)
    private val btFill = View(context)
    private var btBatteryBar: FrameLayout? = null
    private var btBatteryValue = 0
    private val worldClockLabel = TextView(context)
    private val worldClockTime = TextView(context)
    private val worldClockOffset = TextView(context)
    private val calendarWeekday = TextView(context)
    private val calendarDay = TextView(context)
    private val countdownIcon = ImageView(context)
    private val countdownTitle = TextView(context)
    private val countdownDays = TextView(context)
    private val countdownDate = TextView(context)
    private val memoIcon = ImageView(context)
    private val memoTitle = TextView(context)
    private val memoContent = TextView(context)
    private val sleepIcon = ImageView(context)
    private val sleepValue = TextView(context)
    private val activityRings = ActivityRingsView(context)
    private val activityStepsLine = TextView(context)
    private val activityStandLine = TextView(context)
    private val soundModeIcon = ImageView(context)
    private val soundModeLabel = TextView(context)
    private val flashlightIcon = ImageView(context)
    private val flashlightLabel = TextView(context)
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

    private fun hasWidget(id: String): Boolean = id in widgetIdSet

    private val usesDetailedLayout: Boolean
        get() = widgetIds == listOf(
            LockscreenWidgetIds.DETAIL_WEATHER,
            LockscreenWidgetIds.DETAIL_BATTERY,
        )

    private val detailedWeatherId: String?
        get() = when {
            hasWidget(LockscreenWidgetIds.DETAIL_WEATHER_BG) -> LockscreenWidgetIds.DETAIL_WEATHER_BG
            hasWidget(LockscreenWidgetIds.DETAIL_WEATHER) -> LockscreenWidgetIds.DETAIL_WEATHER
            else -> null
        }

    private val detailedBatteryId: String?
        get() = when {
            hasWidget(LockscreenWidgetIds.DETAIL_BATTERY_BG) -> LockscreenWidgetIds.DETAIL_BATTERY_BG
            hasWidget(LockscreenWidgetIds.DETAIL_BATTERY) -> LockscreenWidgetIds.DETAIL_BATTERY
            else -> null
        }

    private val hasDetailedWeather: Boolean
        get() = detailedWeatherId != null

    private val hasDetailedBattery: Boolean
        get() = detailedBatteryId != null

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
        val selectedCount = widgetIds.size
        val longCount = widgetIds.count(LockscreenWidgetIds::isWide)
        val surfaceHeight = if (selectedCount >= 4 && longCount == 0) 56 else 64
        val metricWidth = surfaceHeight
        val hasDetailedPair = selectedCount == 2 &&
            hasDetailedWeather && hasDetailedBattery
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
        val surfaces = LinkedHashMap<String, Pair<FrameLayout, Int>>()
        detailedWeatherId?.let { weatherId ->
            val hasBackground = weatherId == LockscreenWidgetIds.DETAIL_WEATHER_BG
            val contentInset = if (hasBackground) dp(12) else 0
            val weatherSurface = if (hasBackground) {
                createSurface(widthDp = detailedWidth, heightDp = surfaceHeight, circular = false)
            } else {
                createContentSlot()
            }
            val weatherContent = FrameLayout(context).apply {
                if (hasBackground) {
                    scaleX = DETAILED_WIDGET_BACKGROUND_CONTENT_SCALE
                    scaleY = DETAILED_WIDGET_BACKGROUND_CONTENT_SCALE
                }
            }
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
            weatherContent.addView(top, FrameLayout.LayoutParams(-1, dp(WIDGET_WEATHER_ROW_HEIGHT_DP)).apply {
                leftMargin = contentInset
                rightMargin = contentInset
            })
            style(city, WIDGET_TEXT_SIZE_SP)
            weatherContent.addView(city, FrameLayout.LayoutParams(-1, dp(WIDGET_ROW_HEIGHT_DP)).apply {
                topMargin = dp(WIDGET_CITY_ROW_TOP_DP)
                leftMargin = contentInset
                rightMargin = contentInset
            })
            style(highLow, WIDGET_TEXT_SIZE_SP)
            weatherContent.addView(highLow, FrameLayout.LayoutParams(-1, dp(WIDGET_ROW_HEIGHT_DP)).apply {
                gravity = Gravity.TOP
                topMargin = dp(WIDGET_THIRD_ROW_TOP_DP)
                leftMargin = contentInset
                rightMargin = contentInset
            })
            weatherSurface.addView(weatherContent, LayoutParams(-1, -1))
            surfaces[weatherId] = weatherSurface to detailedWidth
        }
        detailedBatteryId?.let { batteryId ->
            val hasBackground = batteryId == LockscreenWidgetIds.DETAIL_BATTERY_BG
            val contentInset = if (hasBackground) dp(12) else 0
            val batterySurface = if (hasBackground) {
                createSurface(widthDp = detailedWidth, heightDp = surfaceHeight, circular = false)
            } else {
                createContentSlot()
            }
            val batteryContent = FrameLayout(context).apply {
                if (hasBackground) {
                    scaleX = DETAILED_WIDGET_BACKGROUND_CONTENT_SCALE
                    scaleY = DETAILED_WIDGET_BACKGROUND_CONTENT_SCALE
                }
            }
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            batteryIcon.setImageDrawable(loadMobile3Icon(context))
            batteryIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            top.addView(batteryIcon, LinearLayout.LayoutParams(dp(MOBILE_ICON_WIDTH_DP), dp(MOBILE_ICON_HEIGHT_DP)))
            style(batteryPercent, WIDGET_TEXT_SIZE_SP)
            top.addView(batteryPercent, LinearLayout.LayoutParams(-2, dp(WIDGET_WEATHER_ROW_HEIGHT_DP)).apply { leftMargin = dp(5) })
            batteryContent.addView(top, FrameLayout.LayoutParams(-1, dp(WIDGET_WEATHER_ROW_HEIGHT_DP)).apply {
                leftMargin = contentInset
                rightMargin = contentInset
            })
            style(deviceName, WIDGET_TEXT_SIZE_SP)
            deviceName.maxLines = 1
            deviceName.ellipsize = android.text.TextUtils.TruncateAt.END
            batteryContent.addView(deviceName, FrameLayout.LayoutParams(-1, dp(WIDGET_ROW_HEIGHT_DP)).apply {
                topMargin = dp(WIDGET_CITY_ROW_TOP_DP)
                leftMargin = contentInset
                rightMargin = contentInset
            })
            batteryBar = FrameLayout(context)
            batteryTrack.background = rounded(Color.argb(76, 255, 255, 255), dp(9).toFloat())
            batteryFill.background = rounded(Color.WHITE, dp(9).toFloat())
            batteryBar.addView(batteryTrack, FrameLayout.LayoutParams(-1, dp(10)))
            batteryBar.addView(batteryFill, FrameLayout.LayoutParams(0, dp(10)))
            batteryContent.addView(batteryBar, FrameLayout.LayoutParams(-1, dp(10)).apply {
                gravity = Gravity.TOP
                topMargin = dp(WIDGET_THIRD_ROW_TOP_DP + 5)
                leftMargin = contentInset
                rightMargin = contentInset
            })
            batterySurface.addView(batteryContent, LayoutParams(-1, -1))
            surfaces[batteryId] = batterySurface to detailedWidth
        }
        if (hasWidget(LockscreenWidgetIds.COMPACT_WEATHER)) {
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
            surfaces[LockscreenWidgetIds.COMPACT_WEATHER] = weatherSurface to compactWeatherWidth
        }
        if (hasWidget(LockscreenWidgetIds.SIGNATURE)) {
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
            surfaces[LockscreenWidgetIds.SIGNATURE] = signatureSurface to compactWeatherWidth
        }
        if (hasWidget(LockscreenWidgetIds.STEPS_WIDE)) {
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
            stepsWideTitle.text = widgetLocalized("今日步数", "Steps", "歩数")
            stepsWideTitle.alpha = .78f
            labels.addView(stepsWideTitle, LinearLayout.LayoutParams(-1, dp(18)))
            val valueRow = LinearLayout(context).apply { gravity = Gravity.BOTTOM }
            style(stepsWideValue, 17.6f)
            stepsWideValue.setTypeface(stepsWideValue.typeface, android.graphics.Typeface.BOLD)
            valueRow.addView(stepsWideValue, LinearLayout.LayoutParams(-2, dp(29)))
            style(stepsWideUnit, 8.8f)
            stepsWideUnit.text = widgetLocalized("步", "", "歩")
            stepsWideUnit.setTypeface(stepsWideUnit.typeface, android.graphics.Typeface.BOLD)
            valueRow.addView(stepsWideUnit, LinearLayout.LayoutParams(-2, dp(22)).apply { leftMargin = dp(2) })
            labels.addView(valueRow, LinearLayout.LayoutParams(-1, dp(30)))
            content.addView(labels, LinearLayout.LayoutParams(0, -1, 1f))
            content.addView(stepsWideProgress, LinearLayout.LayoutParams(dp(58), dp(58)))
            stepsSurface.addView(content, LayoutParams(-1, -1))
            surfaces[LockscreenWidgetIds.STEPS_WIDE] = stepsSurface to compactWeatherWidth
        }
        if (hasWidget(LockscreenWidgetIds.SCHEDULE)) {
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
            surfaces[LockscreenWidgetIds.SCHEDULE] = scheduleSurface to compactWeatherWidth
        }
        if (hasWidget(LockscreenWidgetIds.BLUETOOTH_BATTERY)) {
            val btSurface = createSurface(widthDp = compactWeatherWidth, heightDp = surfaceHeight, circular = false)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            btIcon.setImageDrawable(loadModuleDrawable(context, "ic_widget_bluetooth"))
            btIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            top.addView(btIcon, LinearLayout.LayoutParams(dp(16), dp(16)))
            style(btPercent, 13.5f)
            top.addView(btPercent, LinearLayout.LayoutParams(-2, dp(18)).apply { leftMargin = dp(5) })
            content.addView(top, LinearLayout.LayoutParams(-1, dp(18)))
            style(btDeviceName, 11.2f)
            btDeviceName.alpha = .82f
            btDeviceName.ellipsize = android.text.TextUtils.TruncateAt.END
            content.addView(btDeviceName, LinearLayout.LayoutParams(-1, dp(16)).apply { topMargin = dp(2) })
            val bar = FrameLayout(context)
            btBatteryBar = bar
            btTrack.background = rounded(Color.argb(76, 255, 255, 255), dp(6).toFloat())
            btFill.background = rounded(Color.WHITE, dp(6).toFloat())
            bar.addView(btTrack, FrameLayout.LayoutParams(-1, dp(6)))
            bar.addView(btFill, FrameLayout.LayoutParams(0, dp(6)))
            content.addView(bar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(5) })
            btSurface.addView(content, LayoutParams(-1, -1))
            surfaces[LockscreenWidgetIds.BLUETOOTH_BATTERY] = btSurface to compactWeatherWidth
        }
        if (hasWidget(LockscreenWidgetIds.COUNTDOWN)) {
            val cdSurface = createSurface(widthDp = compactWeatherWidth, heightDp = surfaceHeight, circular = false)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), 0, dp(12), 0)
            }
            countdownIcon.setImageDrawable(loadModuleDrawable(context, "ic_widget_countdown"))
            countdownIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            content.addView(countdownIcon, LinearLayout.LayoutParams(dp(22), dp(22)))
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            style(countdownTitle, 11.2f)
            countdownTitle.alpha = .78f
            countdownTitle.ellipsize = android.text.TextUtils.TruncateAt.END
            headerRow.addView(countdownTitle, LinearLayout.LayoutParams(0, dp(17), 1f))
            style(countdownDate, 10f)
            countdownDate.alpha = .68f
            headerRow.addView(countdownDate, LinearLayout.LayoutParams(-2, dp(15)).apply { leftMargin = dp(4) })
            texts.addView(headerRow, LinearLayout.LayoutParams(-1, dp(17)))
            style(countdownDays, 15f)
            countdownDays.ellipsize = android.text.TextUtils.TruncateAt.END
            texts.addView(countdownDays, LinearLayout.LayoutParams(-1, dp(23)).apply { topMargin = dp(2) })
            content.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            cdSurface.addView(content, LayoutParams(-1, -1))
            surfaces[LockscreenWidgetIds.COUNTDOWN] = cdSurface to compactWeatherWidth
        }
        if (hasWidget(LockscreenWidgetIds.CUSTOM_MEMO)) {
            val memoSurface = createSurface(widthDp = compactWeatherWidth, heightDp = surfaceHeight, circular = false)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), 0, dp(12), 0)
            }
            memoIcon.setImageDrawable(loadModuleDrawable(context, "ic_widget_memo"))
            memoIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            content.addView(memoIcon, LinearLayout.LayoutParams(dp(20), dp(20)))
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            style(memoTitle, 11.2f)
            memoTitle.alpha = .76f
            memoTitle.ellipsize = android.text.TextUtils.TruncateAt.END
            texts.addView(memoTitle, LinearLayout.LayoutParams(-1, dp(17)))
            style(memoContent, 13.5f)
            memoContent.maxLines = 1
            memoContent.ellipsize = android.text.TextUtils.TruncateAt.END
            texts.addView(memoContent, LinearLayout.LayoutParams(-1, dp(22)).apply { topMargin = dp(2) })
            content.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            memoSurface.addView(content, LayoutParams(-1, -1))
            surfaces[LockscreenWidgetIds.CUSTOM_MEMO] = memoSurface to compactWeatherWidth
        }
        if (hasWidget(LockscreenWidgetIds.ACTIVITY_RINGS)) {
            val actSurface = createSurface(widthDp = compactWeatherWidth, heightDp = surfaceHeight, circular = false)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(10), 0)
            }
            content.addView(activityRings, LinearLayout.LayoutParams(dp(44), dp(44)))
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            style(activityStepsLine, 13.6f)
            activityStepsLine.ellipsize = android.text.TextUtils.TruncateAt.END
            texts.addView(activityStepsLine, LinearLayout.LayoutParams(-1, dp(20)))
            style(activityStandLine, 11.4f)
            activityStandLine.alpha = .82f
            activityStandLine.ellipsize = android.text.TextUtils.TruncateAt.END
            texts.addView(activityStandLine, LinearLayout.LayoutParams(-1, dp(18)).apply { topMargin = dp(2) })
            content.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            actSurface.addView(content, LayoutParams(-1, -1))
            surfaces[LockscreenWidgetIds.ACTIVITY_RINGS] = actSurface to compactWeatherWidth
        }
        if (hasWidget(LockscreenWidgetIds.SUN)) {
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
            surfaces[LockscreenWidgetIds.SUN] = sunSurface to metricWidth
        }
        if (hasWidget(LockscreenWidgetIds.STEPS)) {
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
            surfaces[LockscreenWidgetIds.STEPS] = stepsSurface to metricWidth
        }
        fun addMetricSurface(
            id: String,
            icon: View,
            value: TextView,
            iconWidth: Int = 28,
            iconHeight: Int = 28,
            valueSize: Float = WIDGET_COMBINATION_METRIC_TEXT_SIZE_SP,
            valueHeight: Int = 22,
            onClick: (() -> Unit)? = null,
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
            if (onClick != null && interactiveEnabled) {
                surface.isClickable = true
                surface.isFocusable = false
                surface.setOnClickListener { onClick() }
            }
            surface.addView(content, LayoutParams(-1, -1))
            surfaces[id] = surface to metricWidth
        }
        if (hasWidget(LockscreenWidgetIds.HUMIDITY)) {
            style(humidityLabel, 10f)
            humidityLabel.text = widgetLocalized("湿度", "HUM", "湿度")
            humidityLabel.gravity = Gravity.CENTER
            addMetricSurface(LockscreenWidgetIds.HUMIDITY, humidityLabel, humidityValue, 32, 25)
        }
        if (hasWidget(LockscreenWidgetIds.AQI)) {
            style(aqiLabel, 10f)
            aqiLabel.text = "AQI"
            aqiLabel.gravity = Gravity.CENTER
            addMetricSurface(LockscreenWidgetIds.AQI, aqiLabel, aqiValue, 32, 25)
        }
        if (hasWidget(LockscreenWidgetIds.FEELS_LIKE)) {
            style(feelsLikeLabel, 10f)
            feelsLikeLabel.text = widgetLocalized("体感", "FEELS", "体感")
            feelsLikeLabel.gravity = Gravity.CENTER
            addMetricSurface(LockscreenWidgetIds.FEELS_LIKE, feelsLikeLabel, feelsLikeValue, 32, 25)
        }
        if (hasWidget(LockscreenWidgetIds.WIND)) {
            addMetricSurface(
                LockscreenWidgetIds.WIND,
                windIcon,
                windValue,
                valueSize = 10f,
            )
        }
        if (hasWidget(LockscreenWidgetIds.STAND)) {
            style(standLabel, 10f)
            standLabel.text = widgetLocalized("站立", "STAND", "スタンド")
            standLabel.gravity = Gravity.CENTER
            addMetricSurface(LockscreenWidgetIds.STAND, standLabel, standCount, 32, 25)
        }
        if (hasWidget(LockscreenWidgetIds.ALARM)) {
            alarmIcon.setImageDrawable(
                loadModuleDrawable(context, "ic_widget_alarm")
                    ?: loadSystemDrawable(context, "ic_alarm"),
            )
            val alarmIconHeight = if (surfaceHeight >= 64) 30 else 25
            val alarmValueHeight = 22
            alarmValueMultilineHeightPx = dp(if (surfaceHeight >= 64) 32 else 30)
            addMetricSurface(
                LockscreenWidgetIds.ALARM,
                alarmIcon,
                alarmTime,
                30,
                alarmIconHeight,
                11f,
                alarmValueHeight,
            )
            alarmTime.maxLines = 2
            alarmTime.ellipsize = android.text.TextUtils.TruncateAt.END
            alarmTime.setLineSpacing(0f, .9f)
        }
        if (hasWidget(LockscreenWidgetIds.UV_INDEX)) {
            val uvSurface = createSurface(widthDp = metricWidth, heightDp = surfaceHeight, circular = true)
            uvSurface.addView(uvGauge, LayoutParams(-1, -1))
            surfaces[LockscreenWidgetIds.UV_INDEX] = uvSurface to metricWidth
        }
        if (hasWidget(LockscreenWidgetIds.RAIN_PROB)) {
            rainIcon.setImageDrawable(loadModuleDrawable(context, "ic_widget_rain"))
            addMetricSurface(
                LockscreenWidgetIds.RAIN_PROB,
                rainIcon,
                rainValue,
                iconWidth = 22,
                iconHeight = 22,
                valueSize = 11.8f,
                valueHeight = 20,
            )
        }
        if (hasWidget(LockscreenWidgetIds.PRESSURE)) {
            val pressureSurface = createSurface(widthDp = metricWidth, heightDp = surfaceHeight, circular = true)
            pressureSurface.addView(pressureGauge, LayoutParams(-1, -1))
            surfaces[LockscreenWidgetIds.PRESSURE] = pressureSurface to metricWidth
        }
        if (hasWidget(LockscreenWidgetIds.BATTERY_RING)) {
            val ringSurface = createSurface(widthDp = metricWidth, heightDp = surfaceHeight, circular = true)
            ringSurface.addView(batteryRingGauge, LayoutParams(-1, -1))
            surfaces[LockscreenWidgetIds.BATTERY_RING] = ringSurface to metricWidth
        }
        if (hasWidget(LockscreenWidgetIds.BATTERY_TEMP)) {
            batteryTempIcon.setImageDrawable(loadModuleDrawable(context, "ic_widget_thermometer"))
            addMetricSurface(
                LockscreenWidgetIds.BATTERY_TEMP,
                batteryTempIcon,
                batteryTempValue,
                iconWidth = 22,
                iconHeight = 22,
                valueSize = 11.4f,
                valueHeight = 20,
            )
        }
        if (hasWidget(LockscreenWidgetIds.WORLD_CLOCK)) {
            val wcSurface = createSurface(widthDp = metricWidth, heightDp = surfaceHeight, circular = true)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            style(worldClockLabel, if (surfaceHeight >= 64) 9.8f else 9f)
            worldClockLabel.alpha = .78f
            worldClockLabel.gravity = Gravity.CENTER
            content.addView(worldClockLabel, LinearLayout.LayoutParams(-1, dp(14)))
            style(worldClockTime, if (surfaceHeight >= 64) 13.8f else 12.2f)
            worldClockTime.gravity = Gravity.CENTER
            content.addView(worldClockTime, LinearLayout.LayoutParams(-1, dp(19)))
            style(worldClockOffset, 8.8f)
            worldClockOffset.alpha = .72f
            worldClockOffset.gravity = Gravity.CENTER
            content.addView(worldClockOffset, LinearLayout.LayoutParams(-1, dp(13)))
            wcSurface.addView(content, LayoutParams(-1, -1))
            surfaces[LockscreenWidgetIds.WORLD_CLOCK] = wcSurface to metricWidth
        }
        if (hasWidget(LockscreenWidgetIds.CALENDAR_DATE)) {
            val calSurface = createSurface(widthDp = metricWidth, heightDp = surfaceHeight, circular = true)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            style(calendarWeekday, if (surfaceHeight >= 64) 10.5f else 9.5f)
            calendarWeekday.alpha = .82f
            calendarWeekday.gravity = Gravity.CENTER
            content.addView(calendarWeekday, LinearLayout.LayoutParams(-1, dp(15)))
            style(calendarDay, if (surfaceHeight >= 64) 21.5f else 18.5f)
            calendarDay.gravity = Gravity.CENTER
            content.addView(calendarDay, LinearLayout.LayoutParams(-1, dp(26)))
            calSurface.addView(content, LayoutParams(-1, -1))
            surfaces[LockscreenWidgetIds.CALENDAR_DATE] = calSurface to metricWidth
        }
        if (hasWidget(LockscreenWidgetIds.SLEEP)) {
            sleepIcon.setImageDrawable(loadModuleDrawable(context, "ic_widget_sleep"))
            addMetricSurface(
                LockscreenWidgetIds.SLEEP,
                sleepIcon,
                sleepValue,
                iconWidth = 22,
                iconHeight = 22,
                valueSize = 10.8f,
                valueHeight = 20,
            )
        }
        if (hasWidget(LockscreenWidgetIds.SOUND_MODE)) {
            addMetricSurface(
                LockscreenWidgetIds.SOUND_MODE,
                soundModeIcon,
                soundModeLabel,
                iconWidth = 22,
                iconHeight = 22,
                valueSize = 10.4f,
                valueHeight = 19,
                onClick = onSoundModeClick,
            )
        }
        if (hasWidget(LockscreenWidgetIds.FLASHLIGHT)) {
            flashlightIcon.setImageDrawable(loadModuleDrawable(context, "ic_widget_flashlight"))
            addMetricSurface(
                LockscreenWidgetIds.FLASHLIGHT,
                flashlightIcon,
                flashlightLabel,
                iconWidth = 22,
                iconHeight = 22,
                valueSize = 10.4f,
                valueHeight = 19,
                onClick = onFlashlightClick,
            )
        }
        widgetIds.forEach { id ->
            surfaces.remove(id)?.let { (surface, width) -> addSurface(surface, width) }
        }
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
        if (hasWidget(LockscreenWidgetIds.COMPACT_WEATHER)) {
            combinationWeatherIcon.setImageDrawable(data.weatherIcon)
        }
        if (hasDetailedWeather) {
            temperature.text = data.temperature
            condition.text = data.condition
            city.text = data.city
            highLow.text = data.highLow
            weatherIcon.setImageDrawable(data.weatherIcon)
        }
        if (hasDetailedBattery) {
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
        if (hasWidget(LockscreenWidgetIds.SUN)) {
            sunIcon.setImageDrawable(
                loadModuleDrawable(context, if (showSunset) "ic_widget_sunset" else "ic_widget_sunrise")
                    ?: loadSystemDrawable(context, if (showSunset) "weather_icon_sun_down" else "weather_icon_sun_up"),
            )
            sunTime.text = formatMinute(targetMinute)
        }
        if (hasWidget(LockscreenWidgetIds.STEPS)) {
            stepIcon.setImageDrawable(
                loadSystemDrawable(context, "health_icon_step_count_25")
                    ?: loadModuleDrawable(context, "ic_widget_steps"),
            )
            stepCount.text = data.steps?.takeIf { it >= 0 }?.toString() ?: "--"
        }
        if (hasWidget(LockscreenWidgetIds.STEPS_WIDE)) {
            stepsWideValue.text = data.steps?.takeIf { it >= 0 }?.toString() ?: "--"
            stepsWideProgress.bind(
                data.steps,
                color,
                loadSystemDrawable(context, "health_icon_step_count_25")
                    ?: loadModuleDrawable(context, "ic_widget_steps"),
                data.stepsTarget,
            )
        }
        if (hasWidget(LockscreenWidgetIds.HUMIDITY)) {
            humidityValue.text = data.humidity
        }
        if (hasWidget(LockscreenWidgetIds.AQI)) {
            aqiValue.text = data.aqi
        }
        if (hasWidget(LockscreenWidgetIds.FEELS_LIKE)) {
            feelsLikeValue.text = data.feelsLike
        }
        if (hasWidget(LockscreenWidgetIds.WIND)) {
            windIcon.setImageDrawable(data.windIcon)
            windValue.text = data.wind
        }
        if (hasWidget(LockscreenWidgetIds.STAND)) {
            standCount.text = data.standCount?.takeIf { it >= 0 }?.toString() ?: "--"
        }
        if (hasWidget(LockscreenWidgetIds.ALARM)) {
            alarmTime.text = formatAlarmText(data.nextAlarm ?: "--:--")
            updateAlarmTextSpacing()
        }
        if (hasWidget(LockscreenWidgetIds.SCHEDULE)) {
            scheduleTitle.text = data.nextSchedule?.title ?: widgetLocalized("无日程", "No events", "予定なし")
            scheduleTime.text = data.nextSchedule?.timeLabel ?: ""
        }
        // Bind extended iOS / OneUI widgets
        if (hasWidget(LockscreenWidgetIds.UV_INDEX)) {
            val uv = data.uvIndex
            val progress = if (uv != null) (uv.coerceIn(0, 11) / 11f) else 0f
            uvGauge.bindGauge(
                progress = progress,
                valueText = uv?.toString() ?: "--",
                bottomLabel = "UV",
                color = color,
            )
        }
        if (hasWidget(LockscreenWidgetIds.RAIN_PROB)) {
            rainValue.text = data.rainProbability?.let { "$it%" } ?: "--%"
        }
        if (hasWidget(LockscreenWidgetIds.PRESSURE)) {
            val hpa = data.pressureHpa
            val progress = if (hpa != null) ((hpa - 970f) / 80f).coerceIn(0f, 1f) else 0f
            pressureGauge.bindGauge(
                progress = progress,
                valueText = hpa?.toString() ?: "--",
                bottomLabel = "hPa",
                color = color,
            )
        }
        if (hasWidget(LockscreenWidgetIds.BATTERY_RING)) {
            val icon = if (data.batteryCharging) {
                loadModuleDrawable(context, "ic_widget_battery_bolt")
            } else {
                loadMobile3Icon(context)
            }
            batteryRingGauge.bindGauge(
                progress = data.battery.coerceIn(0, 100) / 100f,
                valueText = "${data.battery}%",
                bottomLabel = null,
                color = color,
                iconDrawable = icon,
                fullCircle = true,
            )
        }
        if (hasWidget(LockscreenWidgetIds.BATTERY_TEMP)) {
            batteryTempValue.text = formatBatteryTempText(data.batteryTempTenthsC)
        }
        if (hasWidget(LockscreenWidgetIds.BLUETOOTH_BATTERY)) {
            val bt = data.bluetoothBattery
            if (bt.connected) {
                btPercent.text = bt.batteryPercent?.let { "$it%" }
                    ?: widgetLocalized("已连接", "Linked", "接続済")
                btDeviceName.text = bt.deviceName
                btBatteryValue = bt.batteryPercent?.coerceIn(0, 100) ?: 100
            } else {
                btPercent.text = "--"
                btDeviceName.text = widgetLocalized("未连接蓝牙", "No Bluetooth", "未接続")
                btBatteryValue = 0
            }
            btFill.background = rounded(color, dp(6).toFloat())
            updateBluetoothBatteryFill()
        }
        if (hasWidget(LockscreenWidgetIds.WORLD_CLOCK)) {
            worldClockLabel.text = data.worldClock.label
            worldClockTime.text = data.worldClock.timeText
            worldClockOffset.text = data.worldClock.offsetLabel
        }
        if (hasWidget(LockscreenWidgetIds.CALENDAR_DATE)) {
            calendarWeekday.text = data.calendarWeekday
            calendarDay.text = data.calendarDay
        }
        if (hasWidget(LockscreenWidgetIds.COUNTDOWN)) {
            countdownTitle.text = data.countdown.title
            countdownDays.text = data.countdown.daysLabel
            countdownDate.text = data.countdown.dateLabel
        }
        if (hasWidget(LockscreenWidgetIds.CUSTOM_MEMO)) {
            val hasTitle = data.memoTitle.isNotBlank()
            memoTitle.visibility = if (hasTitle) View.VISIBLE else View.GONE
            memoTitle.text = data.memoTitle
            memoContent.maxLines = if (hasTitle) 1 else 2
            memoContent.text = data.memoContent.ifBlank {
                widgetLocalized("保持热爱，奔赴山海", "Stay curious", "毎日を大切に")
            }
        }
        if (hasWidget(LockscreenWidgetIds.SLEEP)) {
            sleepValue.text = formatSleepDuration(data.sleepMinutes)
        }
        if (hasWidget(LockscreenWidgetIds.ACTIVITY_RINGS)) {
            val stepsVal = data.steps?.takeIf { it >= 0 }
            val standVal = data.standCount?.takeIf { it >= 0 }
            val stepsProg = if (stepsVal != null && data.stepsTarget > 0) stepsVal.toFloat() / data.stepsTarget else 0f
            val standProg = if (standVal != null && data.standTarget > 0) standVal.toFloat() / data.standTarget else 0f
            activityRings.bind(stepsProg, standProg, color)
            activityStepsLine.text = if (stepsVal != null) {
                widgetLocalized("$stepsVal 步", "$stepsVal steps", "$stepsVal 歩")
            } else {
                widgetLocalized("-- 步", "-- steps", "-- 歩")
            }
            activityStandLine.text = if (standVal != null) {
                widgetLocalized("站立 $standVal/${data.standTarget}", "Stand $standVal/${data.standTarget}", "スタンド $standVal/${data.standTarget}")
            } else {
                widgetLocalized("站立 --/${data.standTarget}", "Stand --/${data.standTarget}", "スタンド --/${data.standTarget}")
            }
        }
        if (hasWidget(LockscreenWidgetIds.SOUND_MODE)) {
            soundModeIcon.setImageDrawable(loadModuleDrawable(context, soundModeDrawableName(data.soundMode)))
            soundModeLabel.text = soundModeLabel(data.soundMode)
        }
        if (hasWidget(LockscreenWidgetIds.FLASHLIGHT)) {
            flashlightLabel.text = if (data.flashlightOn) {
                widgetLocalized("已开启", "ON", "オン")
            } else {
                widgetLocalized("手电筒", "Torch", "ライト")
            }
            flashlightIcon.alpha = if (data.flashlightOn) 1f else 0.78f
        }
        listOf(
            temperature, condition, city, highLow, batteryPercent, deviceName,
            combinationCity, combinationCondition, sunTime, stepCount, humidityLabel, humidityValue,
            aqiLabel, aqiValue, feelsLikeLabel, feelsLikeValue, windValue, standLabel, standCount,
            stepsWideTitle, stepsWideValue, stepsWideUnit, alarmTime, scheduleTitle, scheduleTime,
            rainValue, batteryTempValue, btPercent, btDeviceName,
            worldClockLabel, worldClockTime, worldClockOffset,
            calendarWeekday, calendarDay, countdownTitle, countdownDays, countdownDate,
            memoTitle, memoContent, sleepValue, activityStepsLine, activityStandLine,
            soundModeLabel, flashlightLabel,
        ).forEach { it.setTextColor(color) }
        listOf(
            weatherIcon, combinationWeatherIcon, sunIcon, stepIcon, batteryIcon, windIcon, alarmIcon,
            rainIcon, batteryTempIcon, btIcon, countdownIcon, memoIcon, sleepIcon,
            soundModeIcon, flashlightIcon,
        ).forEach {
            it.imageTintList = ColorStateList.valueOf(color)
        }
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
            updateBluetoothBatteryFill()
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

    private fun updateBluetoothBatteryFill() {
        val bar = btBatteryBar ?: return
        if (bar.width <= 0) {
            bar.post { updateBluetoothBatteryFill() }
            return
        }
        val fillWidth = (bar.width * btBatteryValue / 100f).roundToInt()
        btFill.layoutParams = (btFill.layoutParams as FrameLayout.LayoutParams).apply {
            width = fillWidth.coerceAtLeast(if (btBatteryValue > 0) dp(4) else 0)
        }
        btFill.requestLayout()
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
) : LockscreenMediaPresentationListener {
    private val context = host.context
    private val handler = Handler(Looper.getMainLooper())
    private var view: LockscreenWidgetView? = null
    private var displayedWidgetIds: List<String> = emptyList()
    private var displayedInteractiveEnabled = true
    private var currentVerticalOffsetDp = 0
    private var currentScaleFactor = 1f
    private var currentFollowClockBottom = false
    private var lastKnownClockBottomInHost = Float.NaN
    private var torchOn = false
    private var torchCameraId: String? = null
    private var torchCallback: CameraManager.TorchCallback? = null
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
    private var presentationPositionAnimating = false
    private var positionAnimationTargetX = Float.NaN
    private var positionAnimationTargetY = Float.NaN
    private val finishPresentationPositionAnimation = Runnable {
        presentationPositionAnimating = false
        positionAnimationTargetX = Float.NaN
        positionAnimationTargetY = Float.NaN
        view?.let { widget ->
            if (widget.isAttachedToWindow && widget.visibility == View.VISIBLE) position()
        }
    }
    private var lastLockscreenVisible: Boolean? = null
    private var notificationStack: WeakReference<View>? = null
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key.startsWith("lockscreen_widget_")) {
            host.post(::refresh)
        }
    }
    // Depth clocks can split the visible hour and minute into independent roots. Track our
    // contribution per root so their own AOD and gesture translations remain untouched.
    private val appliedClockAvoidanceOffsets = WeakHashMap<View, ClockTranslationTransform>()
    private val appliedClockAvoidanceScales = WeakHashMap<View, ClockScaleTransform>()
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
    private val clockAvoidancePreDrawListener = ViewTreeObserver.OnPreDrawListener {
        val widget = view
        if (targetVisible && widget?.isAttachedToWindow == true && widget.visibility == View.VISIBLE) {
            adjustClockForWidget(widget)
        }
        true
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
        LockscreenMediaPresentationBridge.register(this)
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        host.rootView.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        host.rootView.viewTreeObserver.addOnPreDrawListener(clockAvoidancePreDrawListener)
        leftShortcut.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePosition() }
        rightShortcut.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> schedulePosition() }
        handler.post(refreshRunnable)
    }

    fun destroy() {
        unregisterTorchCallback()
        LockscreenWidgetSceneState.unregister(this)
        LockscreenMediaPresentationBridge.unregister(this)
        runCatching { preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener) }
        runCatching { host.rootView.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener) }
        runCatching { host.rootView.viewTreeObserver.removeOnPreDrawListener(clockAvoidancePreDrawListener) }
        restoreClockPosition()
        runCatching { view?.let(host::removeView) }
        handler.removeCallbacksAndMessages(null)
        host.removeCallbacks(animationFollower)
        host.removeCallbacks(finishPresentationPositionAnimation)
        followerPosted = false
        presentationPositionAnimating = false
        positionAnimationTargetX = Float.NaN
        positionAnimationTargetY = Float.NaN
        lastKnownClockBottomInHost = Float.NaN
        view = null
        displayedWidgetIds = emptyList()
        displayedInteractiveEnabled = true
        displayedSignatureType = -1
        displayedSignatureVersion = Long.MIN_VALUE
        displayedSignatureColor = 0
        displayedSignatureScale = -1
        displayedSignatureBackground = true
    }

    private fun updateTorchCallbackRegistration(needed: Boolean) {
        if (!needed) {
            unregisterTorchCallback()
            return
        }
        if (torchCallback != null) return
        runCatching {
            val cameraManager = context.getSystemService(CameraManager::class.java) ?: return@runCatching
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return@runCatching
            torchCameraId = cameraId
            val cb = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(camId: String, enabled: Boolean) {
                    if (camId == torchCameraId && torchOn != enabled) {
                        torchOn = enabled
                        host.post(::refresh)
                    }
                }
            }
            cameraManager.registerTorchCallback(cb, handler)
            torchCallback = cb
        }
    }

    private fun unregisterTorchCallback() {
        val cb = torchCallback ?: return
        torchCallback = null
        runCatching {
            context.getSystemService(CameraManager::class.java)?.unregisterTorchCallback(cb)
        }
    }

    private fun toggleFlashlight() {
        runCatching {
            val cameraManager = context.getSystemService(CameraManager::class.java) ?: return@runCatching
            val id = torchCameraId ?: cameraManager.cameraIdList.firstOrNull { camId ->
                cameraManager.getCameraCharacteristics(camId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }?.also { torchCameraId = it } ?: return@runCatching
            val next = !torchOn
            cameraManager.setTorchMode(id, next)
            torchOn = next
            host.post(::refresh)
        }
    }

    private fun cycleSoundMode() {
        runCatching {
            val audio = context.getSystemService(AudioManager::class.java) ?: return@runCatching
            val next = when (audio.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            audio.ringerMode = next
            host.post(::refresh)
        }
    }

    internal fun onSceneVisibilityChanged() {
        val updateVisibility = update@{
            val widget = view ?: return@update
            if (LockscreenWidgetSceneState.hasBlockingOverlay ||
                LockscreenWidgetSceneState.keyguardGoingAway
            ) {
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

    override fun onMediaPresentationChanged(presentation: LockscreenMediaPresentation) {
        // The media header is inserted/removed from the notification stack asynchronously.
        // Keep the widget's current position as the origin and retarget it as the stack settles,
        // otherwise notification avoidance moves it above the island in one traversal.
        presentationPositionAnimating = true
        host.removeCallbacks(finishPresentationPositionAnimation)
        host.postDelayed(
            finishPresentationPositionAnimation,
            WIDGET_PRESENTATION_POSITION_DURATION_MS + 80L,
        )
        host.post(::refresh)
    }

    private fun refresh() {
        val extSettings = readLockscreenWidgetExtendedSettings(preferences)
        val requestedWidgetIds = readEffectiveLockscreenWidgetIds(preferences)
        val requestedInteractive = extSettings.interactiveEnabled
        currentVerticalOffsetDp = extSettings.verticalOffsetDp
        currentScaleFactor = extSettings.scalePercent / 100f
        if (currentFollowClockBottom != extSettings.followClockBottom) {
            currentFollowClockBottom = extSettings.followClockBottom
            lastKnownClockBottomInHost = Float.NaN
            if (currentFollowClockBottom) restoreClockPosition()
        }
        val requestedSignatureType = preferences.getInt(
            KEY_LOCKSCREEN_WIDGET_SIGNATURE_TYPE,
            LOCKSCREEN_WIDGET_SIGNATURE_NONE,
        ).coerceIn(LOCKSCREEN_WIDGET_SIGNATURE_NONE, LOCKSCREEN_WIDGET_SIGNATURE_VECTOR)
        val requestedSignatureVersion = preferences.getLong(KEY_LOCKSCREEN_WIDGET_SIGNATURE_VERSION, 0L)
        val requestedSignatureColor = preferences.getInt(KEY_LOCKSCREEN_WIDGET_SIGNATURE_COLOR, Color.WHITE)
        val requestedSignatureScale = preferences.getInt(KEY_LOCKSCREEN_WIDGET_SIGNATURE_SCALE, 100).coerceIn(25, 200)
        val requestedSignatureBackground = preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_SIGNATURE_BACKGROUND, true)
        if (view != null && (
                displayedWidgetIds != requestedWidgetIds ||
                    displayedInteractiveEnabled != requestedInteractive ||
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
            displayedWidgetIds = emptyList()
        }
        val widget = view ?: LockscreenWidgetView(
            context = context,
            widgetIds = requestedWidgetIds,
            interactiveEnabled = requestedInteractive,
            onSoundModeClick = ::cycleSoundMode,
            onFlashlightClick = ::toggleFlashlight,
            applyBatteryTrackMaterial = applyBatteryTrackMaterial,
            applyShortcutSurfaceMaterial = applyShortcutSurfaceMaterial,
            foregroundColorProvider = ::resolveForegroundColor,
            signatureType = requestedSignatureType,
            signatureColor = requestedSignatureColor,
            signatureScale = requestedSignatureScale,
            signatureBackground = requestedSignatureBackground,
        ).also {
            view = it
            displayedWidgetIds = requestedWidgetIds
            displayedInteractiveEnabled = requestedInteractive
            displayedSignatureType = requestedSignatureType
            displayedSignatureVersion = requestedSignatureVersion
            displayedSignatureColor = requestedSignatureColor
            displayedSignatureScale = requestedSignatureScale
            displayedSignatureBackground = requestedSignatureBackground
            host.addView(it, ViewGroup.LayoutParams(dp(WIDGET_WIDTH_DP), dp(WIDGET_HEIGHT_DP)))
        }
        updateTorchCallbackRegistration(LockscreenWidgetIds.FLASHLIGHT in displayedWidgetIds)
        // Recover if a vendor layout rebuild detached the widget from its stable root.
        if (widget.parent !== host) {
            (widget.parent as? ViewGroup)?.removeView(widget)
            host.addView(widget, ViewGroup.LayoutParams(dp(WIDGET_WIDTH_DP), dp(WIDGET_HEIGHT_DP)))
        }
        val shouldShow = preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_ENABLED, false) &&
            isLockscreenVisible() &&
            LockscreenMediaPresentationBridge.presentation != LockscreenMediaPresentation.MUSIC_LOCKSCREEN &&
            LockscreenMediaPresentationBridge.presentation != LockscreenMediaPresentation.LYRICS_LOCKSCREEN &&
            LockscreenMediaPresentationBridge.presentation != LockscreenMediaPresentation.MUSIC_LOCKSCREEN_STYLE2 &&
            !(preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_HIDE_ON_AOD, false) &&
                LockscreenWidgetSceneState.aodActive)
        if (!shouldShow) {
            hideWidget(
                widget,
                immediately = LockscreenWidgetSceneState.hasBlockingOverlay ||
                    LockscreenWidgetSceneState.keyguardGoingAway ||
                    (LockscreenWidgetSceneState.aodActive &&
                        preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_HIDE_ON_AOD, false)),
            )
            return
        }
        showWidget(widget)
        widget.bind(readData(extSettings))
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
        val targetScale = currentScaleFactor
        if (widget.visibility != View.VISIBLE) {
            widget.animate().cancel()
            widget.visibility = View.VISIBLE
            widget.alpha = 0f
            widget.scaleX = 0.92f * targetScale
            widget.scaleY = 0.92f * targetScale
            appearanceAnimating = true
            widget.animate().alpha(1f).scaleX(targetScale).scaleY(targetScale)
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
            widget.animate().alpha(1f).scaleX(targetScale).scaleY(targetScale)
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
        val targetScale = currentScaleFactor
        if (immediately) {
            widget.animate().cancel()
            widget.visibility = View.GONE
            widget.alpha = 1f
            widget.scaleX = targetScale
            widget.scaleY = targetScale
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
        widget.animate().alpha(0f).scaleX(0.92f * targetScale).scaleY(0.92f * targetScale)
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

    private fun readData(extSettings: LockscreenWidgetExtendedSettings): LockscreenWidgetData {
        val active = displayedWidgetIds.toSet()
        val needsWeather = active.any {
            it == LockscreenWidgetIds.DETAIL_WEATHER ||
                it == LockscreenWidgetIds.DETAIL_WEATHER_BG ||
                it == LockscreenWidgetIds.COMPACT_WEATHER ||
                it == LockscreenWidgetIds.SUN ||
                it == LockscreenWidgetIds.HUMIDITY ||
                it == LockscreenWidgetIds.AQI ||
                it == LockscreenWidgetIds.FEELS_LIKE ||
                it == LockscreenWidgetIds.WIND ||
                it == LockscreenWidgetIds.UV_INDEX ||
                it == LockscreenWidgetIds.RAIN_PROB ||
                it == LockscreenWidgetIds.PRESSURE
        }
        val systemWeather = if (needsWeather) readSystemWeather() else null
        val weatherRoot = if (needsWeather && systemWeather == null) {
            findViewByIdName(host.rootView, setOf("weather_smartspace_view", "weather_smartspace_view_large"))
        } else {
            null
        }
        val texts = ArrayList<String>()
        weatherRoot?.let { collectTexts(it, texts) }
        val joined = texts.joinToString(" ")
        val fallbackTemperature = Regex("[+-]?\\d{1,3}°?").find(joined)?.value?.let {
            if (it.endsWith("°")) it else "$it°"
        } ?: "--°"
        val fallbackCondition = texts.firstOrNull { it.matches(Regex(".*[\\p{IsHan}].*")) && it.length <= 8 }
            ?.replace(Regex("[0-9°+\\- ]"), "")?.takeIf { it.isNotBlank() }
            ?: widgetLocalized("晴", "Clear", "晴れ")
        val fallbackCity = texts.firstOrNull { it.length in 2..8 && it.matches(Regex(".*[\\p{IsHan}].*")) && it != fallbackCondition }
            ?: widgetLocalized("天气", "Weather", "天気")
        val high = Regex("(?i)(?:H|最高)\\s*[:：]?\\s*([+-]?\\d{1,3})").find(joined)?.groupValues?.getOrNull(1)
        val low = Regex("(?i)(?:L|最低)\\s*[:：]?\\s*([+-]?\\d{1,3})").find(joined)?.groupValues?.getOrNull(1)
        val fallbackHighLow = if (high != null || low != null) "H: ${high ?: "--"}°  L: ${low ?: "--"}°" else "H: --°  L: --°"

        val needsBattery = active.any {
            it == LockscreenWidgetIds.DETAIL_BATTERY ||
                it == LockscreenWidgetIds.DETAIL_BATTERY_BG ||
                it == LockscreenWidgetIds.BATTERY_RING ||
                it == LockscreenWidgetIds.BATTERY_TEMP
        }
        val batterySnapshot = if (needsBattery) {
            readBatteryStatusSnapshot(context)
        } else {
            BatteryStatusSnapshot(0, false, null)
        }
        val deviceName = preferences.getString(KEY_LOCKSCREEN_WIDGET_DEVICE_NAME, "")
            .orEmpty().trim().ifBlank { readRoProductMarketName() }
        val icon = if (needsWeather) {
            systemWeather?.icon ?: findWeatherIcon(weatherRoot)
                ?: context.resources.getIdentifier("weather_icon_0", "drawable", context.packageName)
                    .takeIf { it != 0 }?.let { context.getDrawable(it) }
        } else {
            null
        }
        val needsSteps = LockscreenWidgetIds.STEPS in active ||
            LockscreenWidgetIds.STEPS_WIDE in active ||
            LockscreenWidgetIds.ACTIVITY_RINGS in active
        val stepsPair = if (needsSteps) readSystemStepsWithTarget() else null
        val needsStand = LockscreenWidgetIds.STAND in active ||
            LockscreenWidgetIds.ACTIVITY_RINGS in active
        val standPair = if (needsStand) readSystemStandWithTarget() else null
        val sleepMinutes = if (LockscreenWidgetIds.SLEEP in active) readSystemSleepMinutes() else null
        val bluetoothBattery = if (LockscreenWidgetIds.BLUETOOTH_BATTERY in active) {
            readBluetoothBatterySnapshot(context)
        } else {
            BluetoothBatterySnapshot(false, "", null)
        }
        val worldClock = if (LockscreenWidgetIds.WORLD_CLOCK in active) {
            buildWorldClockSnapshot(extSettings.worldClockZoneId, extSettings.worldClockLabel)
        } else {
            WorldClockSnapshot("", "", "")
        }
        val nowCal = Calendar.getInstance()
        val calendarWeekday = if (LockscreenWidgetIds.CALENDAR_DATE in active) {
            SimpleDateFormat("EEE", Locale.getDefault()).format(nowCal.time).uppercase(Locale.getDefault())
        } else {
            ""
        }
        val calendarDay = if (LockscreenWidgetIds.CALENDAR_DATE in active) {
            nowCal.get(Calendar.DAY_OF_MONTH).toString()
        } else {
            ""
        }
        val countdown = if (LockscreenWidgetIds.COUNTDOWN in active) {
            buildCountdownSnapshot(extSettings.countdownTitle, extSettings.countdownDate)
        } else {
            CountdownSnapshot("", "", "")
        }
        val soundMode = if (LockscreenWidgetIds.SOUND_MODE in active) {
            readSoundMode(context)
        } else {
            AudioManager.RINGER_MODE_NORMAL
        }
        return LockscreenWidgetData(
            temperature = systemWeather?.temperature ?: fallbackTemperature,
            condition = systemWeather?.condition ?: fallbackCondition,
            city = systemWeather?.city ?: fallbackCity,
            highLow = systemWeather?.highLow ?: fallbackHighLow,
            battery = batterySnapshot.level,
            batteryCharging = batterySnapshot.charging,
            batteryTempTenthsC = batterySnapshot.temperatureTenthsC,
            deviceName = deviceName,
            weatherIcon = icon,
            sunrise = systemWeather?.sunrise ?: 0,
            sunriseTomorrow = systemWeather?.sunriseTomorrow ?: 0,
            sunset = systemWeather?.sunset ?: 0,
            steps = stepsPair?.first,
            stepsTarget = stepsPair?.second?.takeIf { it > 0 } ?: STEPS_GOAL,
            humidity = systemWeather?.humidity ?: "--",
            aqi = systemWeather?.aqi ?: "--",
            feelsLike = systemWeather?.feelsLike ?: "--°",
            feelsLikeIcon = systemWeather?.feelsLikeIcon,
            wind = systemWeather?.wind ?: "--",
            windIcon = systemWeather?.windIcon,
            standCount = standPair?.first,
            standTarget = standPair?.second?.takeIf { it > 0 } ?: STAND_GOAL,
            nextAlarm = if (LockscreenWidgetIds.ALARM in active) readNextAlarm() else null,
            nextSchedule = if (LockscreenWidgetIds.SCHEDULE in active) readNextSchedule() else null,
            uvIndex = systemWeather?.uvIndex,
            rainProbability = systemWeather?.rainProbability,
            pressureHpa = systemWeather?.pressureHpa,
            bluetoothBattery = bluetoothBattery,
            worldClock = worldClock,
            calendarWeekday = calendarWeekday,
            calendarDay = calendarDay,
            countdown = countdown,
            memoTitle = extSettings.memoTitle,
            memoContent = extSettings.memoContent,
            sleepMinutes = sleepMinutes,
            soundMode = soundMode,
            flashlightOn = torchOn,
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
                val title = cursor.getString(0).orEmpty().trim()
                    .ifBlank { widgetLocalized("日程", "Event", "予定") }
                val begin = cursor.getLong(1)
                val allDay = cursor.getInt(2) != 0
                val allDayPattern = widgetLocalized("MM/dd 全天", "MM/dd All day", "MM/dd 終日")
                val format = SimpleDateFormat(if (allDay) allDayPattern else "MM/dd HH:mm", Locale.getDefault())
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
        val condition = (call("getDescription") as? String).orEmpty()
            .ifBlank { widgetLocalized("晴", "Clear", "晴れ") }
        val city = (call("getCityName") as? String).orEmpty()
            .ifBlank { widgetLocalized("天气", "Weather", "天気") }
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
            val unit = widgetLocalized("级", " Lv", "級")
            "$windDirection $windStrength$unit"
        } else {
            "--"
        }
        val uvIndex = (call("getUVILevel") as? String)?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
        val rawRain = (call("getRainProbability") as? String)?.trim().orEmpty()
        val rainProbability = if (rawRain.isNotEmpty() && rawRain != "--") {
            rawRain.removeSuffix("%").trim().toIntOrNull()?.takeIf { it in 0..100 }
                ?: (call("getRainProbabilityInt") as? Number)?.toInt()?.takeIf { it in 0..100 }
        } else {
            null
        }
        val pressureHpa = (call("getPressure") as? Number)?.toInt()?.takeIf { it in 800..1150 }
        SystemWeatherSnapshot(
            temperature, condition, city, highLow, icon, sunrise, sunriseTomorrow, sunset,
            humidity, aqi, feelsLike, feelsLikeIcon, wind, windIcon,
            uvIndex, rainProbability, pressureHpa,
        )
    }.getOrNull()

    /**
     * SystemUI itself queries Xiaomi Health through DataUtils using health type 500. Calling the
     * same API keeps the widget on the ROM's permission path and avoids declaring a provider
     * dependency in the module app. Every reflection failure is a no-data state, never a crash.
     */
    private fun readSystemStepsWithTarget(): Pair<Int, Int>? = runCatching {
        readSystemHealthPair(500, "getStepCountNow", "getStepCountTarget", STEPS_GOAL)
    }.getOrNull()

    /** Health type 504 is the SystemUI path for the standing-count widget. */
    private fun readSystemStandWithTarget(): Pair<Int, Int>? = runCatching {
        readSystemHealthPair(504, "getStandCountNow", "getStandCountTarget", STAND_GOAL)
    }.getOrNull()

    /** Health type 506 is the SystemUI path for sleep report duration in minutes. */
    private fun readSystemSleepMinutes(): Int? = runCatching {
        val bean = invokeSystemHealthBean(506) ?: return@runCatching null
        val minutes = bean.javaClass.methods.firstOrNull { method ->
            method.name == "getSleepDurationMinute" && method.parameterCount == 0
        }?.invoke(bean) as? Number ?: return@runCatching null
        minutes.toInt().takeIf { it > 0 }
    }.getOrNull()

    private fun invokeSystemHealthBean(type: Int): Any? {
        val dataUtils = Class.forName(CLOCK_DATA_UTILS_CLASS, false, classLoader)
        val healthBean = Class.forName("com.miui.clock.module.HealthBean", false, classLoader)
        val getter = dataUtils.methods.firstOrNull { method ->
            method.name == "getHealthBean" && method.parameterCount == 3 &&
                method.parameterTypes[0] == WeakReference::class.java &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType &&
                method.parameterTypes[2].isAssignableFrom(healthBean)
        } ?: return null
        return getter.invoke(null, WeakReference(context), type, null)
    }

    private fun readSystemHealthPair(
        type: Int,
        nowGetter: String,
        targetGetter: String,
        defaultTarget: Int,
    ): Pair<Int, Int>? {
        val bean = invokeSystemHealthBean(type) ?: return null
        val now = bean.javaClass.methods.firstOrNull { method ->
            method.name == nowGetter && method.parameterCount == 0
        }?.invoke(bean) as? Number ?: return null
        val nowInt = now.toInt().takeIf { it >= 0 } ?: return null
        val target = (bean.javaClass.methods.firstOrNull { method ->
            method.name == targetGetter && method.parameterCount == 0
        }?.invoke(bean) as? Number)?.toInt()?.takeIf { it > 0 } ?: defaultTarget
        return nowInt to target
    }

    private fun isLockscreenVisible(): Boolean = runCatching {
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return@runCatching false
        val locked = keyguard.isKeyguardLocked || keyguard.isDeviceLocked
        locked && !LockscreenWidgetSceneState.keyguardGoingAway &&
            !LockscreenWidgetSceneState.hasBlockingOverlay && !hasChargingAnimationOverlay() &&
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
        val targetX = centerX - widget.width / 2f - widget.left
        val sunkenTargetY = centerY - widget.height / 2f - dp(WIDGET_VERTICAL_OFFSET_DP) +
            dp(currentVerticalOffsetDp.toFloat()) - widget.top
        val targetY = if (currentFollowClockBottom) {
            val clockBottomInHost = resolveClockBottomInHost(hostLocation[1])
            if (clockBottomInHost != null) {
                lastKnownClockBottomInHost = clockBottomInHost
            }
            val effectiveClockBottom = clockBottomInHost
                ?: lastKnownClockBottomInHost.takeUnless(Float::isNaN)
            if (effectiveClockBottom != null) {
                val desiredY = effectiveClockBottom + dp(WIDGET_CLOCK_BOTTOM_GAP_DP) +
                    dp(currentVerticalOffsetDp.toFloat()) - widget.top
                val minY = dp(8f) - widget.top.toFloat()
                val maxY = (centerY - widget.height / 2f - dp(16f) - widget.top).coerceAtLeast(minY)
                desiredY.coerceIn(minY, maxY)
            } else {
                sunkenTargetY
            }
        } else {
            val notificationTargetY = if (preferences.getBoolean(KEY_LOCKSCREEN_WIDGET_NOTIFICATION_AVOID, true)) {
                firstVisibleNotificationTop()?.let { notificationTop ->
                    notificationTop - dp(NOTIFICATION_SAFE_GAP_DP) - widget.height - widget.top
                }
            } else {
                null
            }
            notificationTargetY?.let { minOf(sunkenTargetY, it) } ?: sunkenTargetY
        }
        if (presentationPositionAnimating) {
            val targetChanged = positionAnimationTargetX.isNaN() ||
                kotlin.math.abs(positionAnimationTargetX - targetX) > 1f ||
                kotlin.math.abs(positionAnimationTargetY - targetY) > 1f
            if (targetChanged) {
                positionAnimationTargetX = targetX
                positionAnimationTargetY = targetY
                if (!appearanceAnimating) widget.animate().cancel()
                widget.animate()
                    .translationX(targetX)
                    .translationY(targetY)
                    .setDuration(WIDGET_PRESENTATION_POSITION_DURATION_MS)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.35f))
                    .start()
            }
        } else {
            widget.translationX = targetX
            widget.translationY = targetY
        }
        if (!appearanceAnimating) syncShortcutAppearance(widget)
    }

    private fun resolveClockBottomInHost(hostTopOnScreen: Int): Float? {
        val targets = findLockscreenClockTargets(host.rootView)
            .filter { target ->
                target.clock.isAttachedToWindow && isVisibleForNotificationAvoidance(target.clock)
            }
        val maxScreenBottom = targets.mapNotNull { target ->
            target.clockBottomOnScreen() ?: target.timeContent
                ?.takeIf { it.isAttachedToWindow && it.height > 0 }
                ?.let { time ->
                    val loc = IntArray(2).also(time::getLocationOnScreen)
                    loc[1] + time.height * kotlin.math.abs(time.scaleY)
                }
        }.maxOrNull() ?: return null
        return maxScreenBottom - hostTopOnScreen.toFloat()
    }

    /**
     * MIUI's depth clock may split the visible hour and minute into a primary clock and a
     * foreground-clock copy. Keep every active time layer clear of the widget, while preserving
     * each layer's own gesture and AOD translation.
     */
    private fun adjustClockForWidget(widget: LockscreenWidgetView) {
        // Read every frame from the OEM's unmodified geometry. This also restores clock layers
        // that disappeared during a template/depth transition before resolving the new ones.
        // Keep doing this during full-AOD transitions: SystemUI changes the underlying glyph
        // geometry every frame, and mapping that live geometry avoids fighting or stepping over
        // its own lockscreen/AOD interpolation.
        restoreClockPosition()
        if (currentFollowClockBottom) return
        val targets = findLockscreenClockTargets(host.rootView)
            .filter { target ->
                target.clock.isAttachedToWindow && isVisibleForNotificationAvoidance(target.clock)
            }
        if (targets.isEmpty()) return
        val widgetLocation = IntArray(2).also(widget::getLocationOnScreen)
        val desiredBottom = widgetLocation[1] - dp(NOTIFICATION_SAFE_GAP_DP)
        var remainingTranslation = 0f
        val dateScaleOffsets = LinkedHashMap<View, Float>()
        targets.forEach { target ->
            val clockBottom = target.clockBottomOnScreen() ?: return@forEach
            val overlap = (clockBottom - desiredBottom).coerceAtLeast(0f)
            if (overlap <= 0f) return@forEach

            var compressedBy = 0f
            target.timeContent
                ?.takeIf { it !== target.clock && it.isAttachedToWindow && it.height > 0 }
                ?.let { timeContent ->
                    val timeLocation = IntArray(2).also(timeContent::getLocationOnScreen)
                    // Only the part of this layer above the reported visible clock bottom can
                    // contribute to avoidance. mClockViewRect is especially important here for
                    // all-in-one clocks whose root itself spans the full keyguard.
                    val compressibleHeight = (clockBottom - timeLocation[1])
                        .coerceIn(0f, timeContent.height * kotlin.math.abs(timeContent.scaleY))
                    if (compressibleHeight > 0f) {
                        val requestedFactor = 1f - overlap / compressibleHeight
                        val factor = requestedFactor.coerceIn(MIN_CLOCK_AVOIDANCE_SCALE, 1f)
                        if (factor < 1f) {
                            val clockLocation = IntArray(2).also(target.clock::getLocationOnScreen)
                            val glyphTop = target.clock.getRenderedClockContentRect()
                                ?.let { clockLocation[1].toFloat() + it.top.toFloat() }
                            val glyphTopOffset = glyphTop
                                ?.let { (it - timeLocation[1].toFloat()).coerceAtLeast(0f) }
                                ?: 0f
                            applyClockScale(timeContent, factor)
                            compressedBy = compressibleHeight * (1f - factor)
                            target.dateContent
                                ?.takeUnless { it === timeContent }
                                ?.let { date ->
                                    // Scaling around the time-group top also moves glyph ink
                                    // above that top. Follow that real top-edge displacement,
                                    // not the much larger amount removed from the bottom.
                                    val offset = glyphTopOffset * (factor - 1f)
                                    dateScaleOffsets[date] = minOf(dateScaleOffsets[date] ?: 0f, offset)
                                }
                        }
                    }
                }
            remainingTranslation = minOf(remainingTranslation, -(overlap - compressedBy).coerceAtLeast(0f))
        }
        if (remainingTranslation != 0f || dateScaleOffsets.values.any { it != 0f }) {
            // clock_animation_container is also driven by the OEM's AOD/unlock animator. Apply
            // our remaining offset to its children instead: the date follows the time without
            // moving the entire large-clock root or competing for the animator's property.
            targets.forEach { target ->
                val timeContent = target.timeContent
                if (timeContent != null) {
                    val scaleTransform = appliedClockAvoidanceScales[timeContent]
                    if (scaleTransform != null) {
                        timeContent.translationY += remainingTranslation
                        appliedClockAvoidanceScales[timeContent] = scaleTransform.copy(
                            appliedTranslationY = timeContent.translationY,
                        )
                    } else {
                        applyClockTranslation(timeContent, remainingTranslation)
                    }
                }
                target.dateContent
                    ?.takeUnless { it === timeContent }
                    ?.let { date ->
                        val offset = (dateScaleOffsets[date] ?: 0f) + remainingTranslation
                        if (offset != 0f) applyClockTranslation(date, offset)
                    }
                if (timeContent == null && target.dateContent == null) {
                    applyClockTranslation(target.movementRoot, remainingTranslation)
                }
            }
        }
    }

    private fun applyClockTranslation(view: View, offset: Float) {
        if (view in appliedClockAvoidanceOffsets) return
        val previous = view.translationY
        val applied = previous + offset
        view.translationY = applied
        appliedClockAvoidanceOffsets[view] = ClockTranslationTransform(
            previousTranslationY = previous,
            appliedTranslationY = applied,
        )
    }

    /** Uniformly contracts the time layer, keeping its visual top fixed and the date unscaled. */
    private fun applyClockScale(timeContent: View, factor: Float) {
        if (timeContent in appliedClockAvoidanceScales) return
        val previousScaleY = timeContent.scaleY
        val previousScaleX = timeContent.scaleX
        val previousPivotY = timeContent.pivotY
        val previousTranslationY = timeContent.translationY
        // Changing the pivot to the top would normally move a layer already scaled by the OEM.
        // Compensate that existing transform so only the lower edge moves during avoidance.
        val translationOffset = previousPivotY * (1f - previousScaleY)
        timeContent.pivotY = 0f
        timeContent.translationY = previousTranslationY + translationOffset
        timeContent.scaleX = previousScaleX * factor
        timeContent.scaleY = previousScaleY * factor
        appliedClockAvoidanceScales[timeContent] = ClockScaleTransform(
            previousScaleX = previousScaleX,
            previousScaleY = previousScaleY,
            previousPivotY = previousPivotY,
            previousTranslationY = previousTranslationY,
            appliedScaleX = timeContent.scaleX,
            appliedScaleY = timeContent.scaleY,
            appliedPivotY = timeContent.pivotY,
            appliedTranslationY = timeContent.translationY,
        )
    }

    private fun restoreClockPosition() {
        appliedClockAvoidanceScales.entries.toList().forEach { (timeContent, transform) ->
            // SystemUI writes absolute AOD/gesture values to these properties. Restore only the
            // values that are still ours; if OEM code has taken ownership, leave them untouched.
            if (timeContent.scaleX.approximatelyEquals(transform.appliedScaleX)) {
                timeContent.scaleX = transform.previousScaleX
            }
            if (timeContent.scaleY.approximatelyEquals(transform.appliedScaleY)) {
                timeContent.scaleY = transform.previousScaleY
            }
            if (timeContent.translationY.approximatelyEquals(transform.appliedTranslationY)) {
                timeContent.translationY = transform.previousTranslationY
            }
            if (timeContent.pivotY.approximatelyEquals(transform.appliedPivotY)) {
                timeContent.pivotY = transform.previousPivotY
            }
        }
        appliedClockAvoidanceScales.clear()
        appliedClockAvoidanceOffsets.entries.toList().forEach { (root, transform) ->
            if (root.translationY.approximatelyEquals(transform.appliedTranslationY)) {
                root.translationY = transform.previousTranslationY
            }
        }
        appliedClockAvoidanceOffsets.clear()
    }

    private fun Float.approximatelyEquals(other: Float): Boolean =
        kotlin.math.abs(this - other) <= 0.01f

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
        val scaleX = animatedViews.minOf { it.scaleX }.coerceIn(0.75f, 1.2f) * currentScaleFactor
        val scaleY = animatedViews.minOf { it.scaleY }.coerceIn(0.75f, 1.2f) * currentScaleFactor
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
            readMiuiClockView(containerView)?.let { clock ->
                val timeContent = findClockTimeContent(clock)
                val dateContent = findClockDateContent(clock, containerView)
                LockscreenClockTarget(
                    clock = clock,
                    container = containerView,
                    timeContent = timeContent,
                    dateContent = dateContent,
                    movementRoot = findClockMovementRoot(clock, timeContent, dateContent),
                )
            }
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
                val timeContent = findClockTimeContent(clock)
                val dateContent = findClockDateContent(clock, container)
                targets[clock] = LockscreenClockTarget(
                    clock = clock,
                    container = container,
                    timeContent = timeContent,
                    dateContent = dateContent,
                    movementRoot = findClockMovementRoot(clock, timeContent, dateContent),
                )
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

    /**
     * Finds a layer that contains the time glyphs but not the date above them. HyperOS' current
     * all-in-one clocks expose mTimeGroup; classic and oversized templates expose mTimeView.
     * Resource-name fallbacks keep this working when those private fields are renamed.
     */
    private fun findClockTimeContent(clock: View): View? {
        val fieldNames = arrayOf("mTimeGroup", "mTimeView", "mTimeContainer")
        fieldNames.forEach { fieldName ->
            var type: Class<*>? = clock.javaClass
            while (type != null) {
                val value = runCatching {
                    type.getDeclaredField(fieldName).apply { isAccessible = true }.get(clock) as? View
                }.getOrNull()
                if (value != null && value !== clock) return value
                type = type.superclass
            }
        }
        return findViewByIdName(
            clock,
            setOf("time_group", "time_view", "clock_time", "time_container"),
        )?.takeUnless { it === clock }
    }

    /** Finds the unscaled date line, including templates that place it beside mClockView. */
    private fun findClockDateContent(clock: View, container: View): View? {
        findViewField(clock, arrayOf("mTextArea", "mDateView", "mDateContainer"))?.let {
            if (it.visibility == View.VISIBLE) return it
        }
        val ids = setOf(
            "text_area",
            "current_date",
            "notification_date",
            "tv_data",
            "date_and_time",
        )
        return sequenceOf(clock, container)
            .mapNotNull { root -> findVisibleViewByIdName(root, ids) }
            .firstOrNull()
    }

    /** OEM root whose descendants include both the date line and the scalable time group. */
    private fun findClockMovementRoot(clock: View, timeContent: View?, dateContent: View?): View {
        findViewField(clock, arrayOf("mAnimationContainer"))?.let { return it }
        findViewByIdName(clock, setOf("clock_animation_container"))?.let { return it }
        if (timeContent != null && dateContent != null) {
            lowestCommonViewAncestor(timeContent, dateContent)?.let { ancestor ->
                if (ancestor.isDescendantOf(clock) || ancestor === clock) return ancestor
            }
        }
        return clock
    }

    private fun findViewField(root: View, fieldNames: Array<String>): View? {
        fieldNames.forEach { fieldName ->
            var type: Class<*>? = root.javaClass
            while (type != null) {
                val value = runCatching {
                    type.getDeclaredField(fieldName).apply { isAccessible = true }.get(root) as? View
                }.getOrNull()
                if (value != null) return value
                type = type.superclass
            }
        }
        return null
    }

    private fun lowestCommonViewAncestor(first: View, second: View): View? {
        val firstAncestors = LinkedHashSet<View>()
        var current: View? = first
        while (current != null) {
            firstAncestors.add(current)
            current = current.parent as? View
        }
        current = second
        while (current != null) {
            if (current in firstAncestors) return current
            current = current.parent as? View
        }
        return null
    }

    private fun findVisibleViewByIdName(root: View, names: Set<String>): View? {
        val entryName = if (root.id != View.NO_ID) {
            runCatching { root.resources.getResourceEntryName(root.id) }.getOrNull()
        } else {
            null
        }
        if (entryName in names && root.visibility == View.VISIBLE && root.height > 0) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findVisibleViewByIdName(root.getChildAt(index), names)?.let { return it }
            }
        }
        return null
    }

    private fun View.isDescendantOf(ancestor: View): Boolean {
        var current: android.view.ViewParent? = parent
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
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
        getRenderedClockContentRect()
            ?.takeIf { !it.isEmpty }
            ?.bottom
            ?.toFloat()
    }.getOrNull()

    private fun View.getRenderedClockContentRect(): Rect? = runCatching {
        (javaClass.getMethod("getMClockViewRect").invoke(this) as? Rect)
            ?.takeIf { !it.isEmpty }
    }.getOrNull()

    private fun LockscreenClockTarget.clockBottomOnScreen(): Float? {
        val candidateBottoms = buildList {
            val containerLocation = IntArray(2).also(container::getLocationOnScreen)
            container.getClockBottomForNotificationAvoidance()?.let { bottom ->
                add(containerLocation[1] + bottom)
            }
            // All-in-one roots can be full-screen; mClockViewRect follows the rendered glyphs.
            val clockLocation = IntArray(2).also(clock::getLocationOnScreen)
            clock.getRenderedClockContentBottom()?.let { bottom ->
                add(clockLocation[1] + bottom)
            }
        }
        return candidateBottoms.maxOrNull()
    }

    private fun dp(value: Float): Int = (value * context.resources.displayMetrics.density + .5f).toInt()
}
