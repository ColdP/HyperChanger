// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.BatteryManager
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

internal fun widgetLocalized(zh: String, en: String, ja: String): String =
    when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
        "zh" -> zh
        "ja" -> ja
        else -> en
    }

internal data class BatteryStatusSnapshot(
    val level: Int,
    val charging: Boolean,
    val temperatureTenthsC: Int?,
)

internal data class BluetoothBatterySnapshot(
    val connected: Boolean,
    val deviceName: String,
    val batteryPercent: Int?,
)

internal data class WorldClockSnapshot(
    val label: String,
    val timeText: String,
    val offsetLabel: String,
)

internal data class CountdownSnapshot(
    val title: String,
    val daysLabel: String,
    val dateLabel: String,
)

internal fun readBatteryStatusSnapshot(context: Context): BatteryStatusSnapshot {
    val batteryManager = context.getSystemService(BatteryManager::class.java)
    val managerLevel = runCatching {
        batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrNull()?.takeIf { it in 0..100 }
    val sticky = runCatching {
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }.getOrNull()
    val intentLevel = sticky?.let { intent ->
        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (raw >= 0 && scale > 0) ((raw * 100f) / scale).roundToInt().coerceIn(0, 100) else null
    }
    val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL ||
        runCatching { batteryManager?.isCharging == true }.getOrDefault(false)
    val tempRaw = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        ?.takeIf { it in -300..1200 }
    return BatteryStatusSnapshot(
        level = managerLevel ?: intentLevel ?: 0,
        charging = charging,
        temperatureTenthsC = tempRaw,
    )
}

internal fun formatBatteryTempText(temperatureTenthsC: Int?): String =
    if (temperatureTenthsC != null) {
        String.format(Locale.US, "%.1f°", temperatureTenthsC / 10f)
    } else {
        "--°"
    }

internal fun readBluetoothBatterySnapshot(context: Context): BluetoothBatterySnapshot = runCatching {
    val manager = context.getSystemService(BluetoothManager::class.java)
    val adapter = manager?.adapter ?: return@runCatching BluetoothBatterySnapshot(false, "", null)
    if (!adapter.isEnabled) return@runCatching BluetoothBatterySnapshot(false, "", null)
    val bonded = adapter.bondedDevices.orEmpty()
    var firstConnectedName: String? = null
    for (device in bonded) {
        val connected = runCatching {
            device.javaClass.getMethod("isConnected").invoke(device) as? Boolean == true
        }.getOrDefault(false)
        if (!connected) continue
        val name = runCatching { device.name }.getOrNull().orEmpty().trim()
            .ifBlank { widgetLocalized("蓝牙设备", "Bluetooth", "Bluetooth") }
        if (firstConnectedName == null) firstConnectedName = name
        val battery = runCatching {
            device.javaClass.getMethod("getBatteryLevel").invoke(device) as? Int
        }.getOrNull()?.takeIf { it in 0..100 }
        if (battery != null) {
            return@runCatching BluetoothBatterySnapshot(true, name, battery)
        }
    }
    if (firstConnectedName != null) {
        BluetoothBatterySnapshot(true, firstConnectedName, null)
    } else {
        BluetoothBatterySnapshot(false, "", null)
    }
}.getOrElse {
    BluetoothBatterySnapshot(false, "", null)
}

internal fun buildWorldClockSnapshot(zoneId: String, customLabel: String): WorldClockSnapshot {
    val targetZone = TimeZone.getTimeZone(zoneId.ifBlank { LOCKSCREEN_WIDGET_DEFAULT_WORLD_CLOCK_ZONE })
    val nowMillis = System.currentTimeMillis()
    val formatter = SimpleDateFormat("HH:mm", Locale.US).apply {
        timeZone = targetZone
    }
    val localOffsetMs = TimeZone.getDefault().getOffset(nowMillis)
    val targetOffsetMs = targetZone.getOffset(nowMillis)
    val diffMinutes = (targetOffsetMs - localOffsetMs) / 60_000
    val offsetLabel = when {
        diffMinutes == 0 -> widgetLocalized("同区", "0h", "±0h")
        diffMinutes % 60 == 0 -> {
            val hours = diffMinutes / 60
            if (hours > 0) "+${hours}h" else "${hours}h"
        }
        else -> {
            val sign = if (diffMinutes > 0) "+" else "-"
            val absMin = abs(diffMinutes)
            "$sign${absMin / 60}:${String.format(Locale.US, "%02d", absMin % 60)}"
        }
    }
    return WorldClockSnapshot(
        label = resolveWorldClockShortLabel(targetZone.id, customLabel),
        timeText = formatter.format(Date(nowMillis)),
        offsetLabel = offsetLabel,
    )
}

internal fun buildCountdownSnapshot(title: String, dateText: String): CountdownSnapshot {
    val effectiveTitle = title.trim().ifBlank {
        widgetLocalized("新年倒计时", "New Year", "新年まで")
    }
    val effectiveDate = dateText.trim().ifBlank { defaultCountdownDateString() }
    val daysDelta = computeCountdownDaysDelta(effectiveDate)
    val daysLabel = when {
        daysDelta == null -> "--"
        daysDelta > 0 -> widgetLocalized("还有 $daysDelta 天", "In ${daysDelta}d", "あと${daysDelta}日")
        daysDelta == 0 -> widgetLocalized("就是今天", "Today", "今日")
        else -> widgetLocalized("已过 ${-daysDelta} 天", "${-daysDelta}d ago", "${-daysDelta}日前")
    }
    val shortDate = parseCountdownDateMillis(effectiveDate)?.let { millis ->
        SimpleDateFormat("MM/dd", Locale.US).format(Date(millis))
    } ?: ""
    return CountdownSnapshot(effectiveTitle, daysLabel, shortDate)
}

internal fun formatSleepDuration(minutes: Int?): String {
    if (minutes == null || minutes <= 0) return "--"
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h > 0 && m > 0 -> "${h}h${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

internal fun readSoundMode(context: Context): Int = runCatching {
    context.getSystemService(AudioManager::class.java)?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
}.getOrDefault(AudioManager.RINGER_MODE_NORMAL)

internal fun soundModeLabel(mode: Int): String = when (mode) {
    AudioManager.RINGER_MODE_VIBRATE -> widgetLocalized("振动", "Vibrate", "バイブ")
    AudioManager.RINGER_MODE_SILENT -> widgetLocalized("静音", "Silent", "サイレント")
    else -> widgetLocalized("响铃", "Ring", "着信音")
}

internal fun soundModeDrawableName(mode: Int): String = when (mode) {
    AudioManager.RINGER_MODE_VIBRATE -> "ic_widget_sound_vibrate"
    AudioManager.RINGER_MODE_SILENT -> "ic_widget_sound_silent"
    else -> "ic_widget_sound_ring"
}

/**
 * iOS-style circular gauge ring widget (used for Battery Ring, UV Index, and Barometric Pressure).
 * Draws a smooth rounded arc track + progress arc around the circular perimeter, plus either:
 *  - Mode A (bottomLabel != null): open bottom 280° gauge with big center value and bottom label (UV / hPa)
 *  - Mode B (icon != null): full 360° or 280° ring with crisp icon + percentage text (Battery Ring)
 */
internal class CircularGaugeRingView(context: Context) : View(context) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val arcBounds = RectF()
    private var progress = 0f
    private var foregroundColor = Color.WHITE
    private var centerText = "--"
    private var bottomLabel: String? = null
    private var icon: Drawable? = null
    private var fullCircle = false

    fun bindGauge(
        progress: Float,
        valueText: String,
        bottomLabel: String?,
        color: Int,
        iconDrawable: Drawable? = null,
        fullCircle: Boolean = false,
    ) {
        this.progress = progress.coerceIn(0f, 1f)
        this.centerText = valueText
        this.bottomLabel = bottomLabel
        this.foregroundColor = color
        this.icon = iconDrawable?.mutate()
        this.fullCircle = fullCircle
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val minDim = width.coerceAtMost(height).toFloat()
        if (minDim <= 0f) return
        val stroke = (minDim * 0.085f).coerceAtLeast(dp(3.4f))
        val inset = stroke / 2f + dp(3.5f)
        arcBounds.set(inset, inset, width - inset, height - inset)
        ringPaint.strokeWidth = stroke
        ringPaint.color = Color.argb(
            74,
            Color.red(foregroundColor),
            Color.green(foregroundColor),
            Color.blue(foregroundColor),
        )
        val startAngle = if (fullCircle) -90f else 130f
        val sweepTotal = if (fullCircle) 360f else 280f
        canvas.drawArc(arcBounds, startAngle, sweepTotal, false, ringPaint)

        ringPaint.color = foregroundColor
        val sweep = if (progress > 0f) (sweepTotal * progress).coerceAtLeast(4f) else 0f
        if (sweep > 0f) {
            canvas.drawArc(arcBounds, startAngle, sweep, false, ringPaint)
        }

        val cx = width / 2f
        val cy = height / 2f
        val drawable = icon
        if (drawable != null) {
            val iconSize = (minDim * 0.27f).roundToInt()
            val iconTop = (cy - iconSize * 0.92f).roundToInt()
            val iconLeft = (cx - iconSize / 2f).roundToInt()
            drawable.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
            drawable.setTint(foregroundColor)
            drawable.draw(canvas)

            valuePaint.color = foregroundColor
            valuePaint.textSize = minDim * 0.21f
            val fm = valuePaint.fontMetrics
            val textY = cy + iconSize * 0.45f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(centerText, cx, textY, valuePaint)
        } else {
            valuePaint.color = foregroundColor
            val valueSizeFactor = if (centerText.length >= 4) 0.225f else 0.30f
            valuePaint.textSize = minDim * valueSizeFactor
            val fm = valuePaint.fontMetrics
            val valueY = cy - dp(1f) - (fm.ascent + fm.descent) / 2f
            canvas.drawText(centerText, cx, valueY, valuePaint)

            bottomLabel?.takeIf { it.isNotEmpty() }?.let { label ->
                labelPaint.color = Color.argb(
                    210,
                    Color.red(foregroundColor),
                    Color.green(foregroundColor),
                    Color.blue(foregroundColor),
                )
                labelPaint.textSize = minDim * 0.165f
                val lfm = labelPaint.fontMetrics
                val labelY = height - inset - dp(1f) - (lfm.ascent + lfm.descent) / 2f
                canvas.drawText(label, cx, labelY, labelPaint)
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

/**
 * iOS Fitness / OneUI Health concentric dual progress rings for the Activity Summary wide widget.
 * Outer ring tracks daily steps goal; inner ring tracks daily stand hours goal.
 */
internal class ActivityRingsView(context: Context) : View(context) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val outerBounds = RectF()
    private val innerBounds = RectF()
    private var outerProgress = 0f
    private var innerProgress = 0f
    private var foregroundColor = Color.WHITE

    fun bind(stepsProgress: Float, standProgress: Float, color: Int) {
        outerProgress = stepsProgress.coerceIn(0f, 1f)
        innerProgress = standProgress.coerceIn(0f, 1f)
        foregroundColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val minDim = width.coerceAtMost(height).toFloat()
        if (minDim <= 0f) return
        val stroke = (minDim * 0.12f).coerceAtLeast(dp(4f))
        val outerInset = stroke / 2f + dp(2f)
        outerBounds.set(outerInset, outerInset, width - outerInset, height - outerInset)
        val innerInset = outerInset + stroke + dp(2.5f)
        innerBounds.set(innerInset, innerInset, width - innerInset, height - innerInset)

        ringPaint.strokeWidth = stroke
        val trackColor = Color.argb(
            68,
            Color.red(foregroundColor),
            Color.green(foregroundColor),
            Color.blue(foregroundColor),
        )
        // Outer ring (Steps)
        ringPaint.color = trackColor
        canvas.drawArc(outerBounds, -90f, 360f, false, ringPaint)
        ringPaint.color = foregroundColor
        if (outerProgress > 0f) {
            canvas.drawArc(outerBounds, -90f, (360f * outerProgress).coerceAtLeast(6f), false, ringPaint)
        }

        // Inner ring (Stand)
        ringPaint.color = trackColor
        canvas.drawArc(innerBounds, -90f, 360f, false, ringPaint)
        ringPaint.color = Color.argb(
            215,
            Color.red(foregroundColor),
            Color.green(foregroundColor),
            Color.blue(foregroundColor),
        )
        if (innerProgress > 0f) {
            canvas.drawArc(innerBounds, -90f, (360f * innerProgress).coerceAtLeast(6f), false, ringPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
