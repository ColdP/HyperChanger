// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/** Runtime recreation of the tutorial's device_info_item_kashi and storage_info_item_kashi. */
class DeviceInfoCardsView(
    context: Context,
    private val nameSource: View,
    private val storageSource: View,
) : LinearLayout(context) {
    private val nameTitle = TextView(context)
    private val nameSummary = TextView(context)
    private val storageTitle = TextView(context)
    private val storageSummary = TextView(context)
    private val storageProgress = StorageProgressView(context)
    private val updateListener = ViewTreeObserver.OnPreDrawListener {
        refresh()
        true
    }
    private var listenerAttached = false

    init {
        orientation = HORIZONTAL
        clipChildren = false
        clipToPadding = false
        gravity = Gravity.TOP

        addView(deviceCard(context), LayoutParams(0, dp(148), 1f).apply { rightMargin = dp(4) })
        addView(storageCard(context), LayoutParams(0, dp(148), 1f).apply { leftMargin = dp(4) })
        isClickable = false
        refresh()
    }

    fun attach() {
        if (listenerAttached) return
        viewTreeObserver.addOnPreDrawListener(updateListener)
        listenerAttached = true
    }

    fun dispose() {
        if (listenerAttached) {
            runCatching { viewTreeObserver.removeOnPreDrawListener(updateListener) }
            listenerAttached = false
        }
    }

    private fun deviceCard(context: Context): View {
        val card = column(context).apply {
            setOnClickListener { nameSource.performClick() }
            isEnabled = nameSource.isEnabled
            alpha = nameSource.alpha
        }
        card.addView(DeviceSymbolView(context), LinearLayout.LayoutParams(dp(38), dp(38)).apply {
            topMargin = dp(16)
        })
        card.addView(nameTitle, textParams(top = 12))
        card.addView(nameSummary, textParams(top = 4, summary = true))
        return card
    }

    private fun storageCard(context: Context): View {
        val card = column(context).apply {
            setOnClickListener { storageSource.performClick() }
            isEnabled = storageSource.isEnabled
            alpha = storageSource.alpha
        }
        val progressHost = FrameLayout(context)
        progressHost.addView(storageProgress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8), Gravity.CENTER_VERTICAL))
        card.addView(progressHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)).apply {
            topMargin = dp(16)
        })
        card.addView(storageTitle, textParams(top = 12))
        card.addView(storageSummary, textParams(top = 4, summary = true))
        return card
    }

    private fun column(context: Context) = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.START
        setPadding(dp(16), 0, dp(16), dp(16))
        background = rippleBackground(context)
        isClickable = true
        isFocusable = true
    }

    private fun textParams(top: Int, summary: Boolean = false): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    }

    private fun refresh() {
        nameTitle.text = sourceText(nameSource, "title").ifBlank { "设备名称" }
        nameSummary.text = sourceText(nameSource, "summary")
        storageTitle.text = sourceText(storageSource, "title").ifBlank { "存储空间" }
        storageSummary.text = sourceText(storageSource, "summary")
        storageProgress.progress = storageFraction(storageSummary.text?.toString().orEmpty())

        val primary = themedColor(android.R.attr.textColorPrimary, if (isNight()) 0xFFFFFFFF.toInt() else 0xFF1B1B1B.toInt())
        val secondary = themedColor(android.R.attr.textColorSecondary, if (isNight()) 0xB3FFFFFF.toInt() else 0x991B1B1B.toInt())
        listOf(nameTitle, storageTitle).forEach { configureText(it, primary, 16f) }
        listOf(nameSummary, storageSummary).forEach { configureText(it, secondary, 14f) }
    }

    private fun configureText(view: TextView, color: Int, size: Float) {
        view.setTextColor(color)
        view.textSize = size
        view.includeFontPadding = false
        view.maxLines = if (size >= 16f) 1 else 2
        view.ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun sourceText(source: View, idName: String): String {
        val id = resources.getIdentifier(idName, "id", context.packageName)
        return (source.findViewById<View>(id) as? TextView)?.text?.toString().orEmpty()
    }

    private fun storageFraction(summary: String): Int {
        val values = STORAGE_VALUE.findAll(summary).take(2).mapNotNull { match ->
            val raw = match.groupValues[1].replace(',', '.').toFloatOrNull() ?: return@mapNotNull null
            val multiplier = when (match.groupValues[2].uppercase(Locale.ROOT).firstOrNull()) {
                'T' -> 1024f * 1024f
                'G' -> 1024f
                'M' -> 1f
                'K' -> 1f / 1024f
                else -> 1f
            }
            raw * multiplier
        }.toList()
        if (values.size < 2 || values[1] <= 0f) return 0
        return (values[0] / values[1] * 1000f).roundToInt().coerceIn(0, 1000)
    }

    private fun rippleBackground(context: Context): RippleDrawable {
        val card = GradientDrawable().apply {
            setColor(tutorialCardBackground())
            cornerRadius = dp(19).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(themedColor(android.R.attr.colorControlHighlight, 0x22000000)), card, null)
    }

    private fun tutorialCardBackground(): Int {
        val id = resources.getIdentifier("my_device_info_item_background_color", "color", context.packageName)
        return if (id != 0) runCatching { context.getColor(id) }.getOrDefault(fallbackCardBackground()) else fallbackCardBackground()
    }

    private fun fallbackCardBackground() = if (isNight()) 0x33FFFFFF else 0xFFFFFFFF.toInt()

    private fun accentColor(): Int {
        val id = resources.getIdentifier("system_accent1_200", "color", "android")
        return if (id != 0) runCatching { resources.getColor(id, context.theme) }.getOrDefault(0xFF8CB8FF.toInt()) else 0xFF8CB8FF.toInt()
    }

    private fun themedColor(attribute: Int, fallback: Int): Int = TypedValue().let { value ->
        if (context.theme.resolveAttribute(attribute, value, true)) {
            if (value.resourceId != 0) runCatching { context.getColor(value.resourceId) }.getOrDefault(value.data) else value.data
        } else fallback
    }

    private fun isNight() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private class DeviceSymbolView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8CB8FF.toInt() }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = context.resources.displayMetrics.density * 1.7f
        }
        private val speaker = Paint(stroke).apply { strokeWidth = context.resources.displayMetrics.density }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val circle = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(circle, width / 2f, height / 2f, fill)
            val phone = RectF(width * .31f, height * .20f, width * .69f, height * .80f)
            canvas.drawRoundRect(phone, width * .07f, width * .07f, stroke)
            canvas.drawLine(width * .42f, height * .69f, width * .58f, height * .69f, speaker)
        }
    }

    private class StorageProgressView(context: Context) : View(context) {
        var progress: Int = 0
            set(value) {
                field = value.coerceIn(0, 1000)
                invalidate()
            }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8CB8FF.toInt() }
        private val radius = context.resources.displayMetrics.density * 555f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(bounds, radius, radius, track)
            if (progress > 0) {
                val fillBounds = RectF(0f, 0f, width * progress / 1000f, height.toFloat())
                canvas.drawRoundRect(fillBounds, radius, radius, fill)
            }
        }
    }

    private companion object {
        val STORAGE_VALUE = Regex("(\\d+(?:[.,]\\d+)?)\\s*([KMGTkmgt])?[Bb]?")
    }
}
