// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RenderEffect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.ImageDecoder
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/** HarmonyOS-like replacement cards used by the independent style 2 mode. */
class HarmonyUpdateCardView(
    context: Context,
    private val logoSource: SettingsAppearanceSource,
    private val updateSource: View?,
    private val backgroundSource: SettingsAppearanceSource,
) : FrameLayout(context) {
    private val backgroundImage = ImageView(context)
    private val logo = ImageView(context)
    private val version = TextView(context)
    private val customText = TextView(context)
    private val content = LinearLayout(context)
    private val versionListener = android.view.ViewTreeObserver.OnPreDrawListener {
        val current = sourceText(updateSource, "miui_version_text")
        if (version.text?.toString() != current) version.text = current
        true
    }
    private var versionListenerAttached = false

    init {
        clipChildren = true
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(28).toFloat())
            }
        }
        backgroundImage.scaleType = ImageView.ScaleType.CENTER_CROP
        backgroundImage.visibility = View.GONE
        addView(backgroundImage, LayoutParams(-1, -1))
        content.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        content.addView(logo, LinearLayout.LayoutParams(dp(220), dp(118)))
        version.gravity = Gravity.CENTER
        version.includeFontPadding = false
        content.addView(version, LinearLayout.LayoutParams(dp(220), dp(30)))
        addView(content, LayoutParams(-1, -1).apply { gravity = Gravity.CENTER })
        customText.includeFontPadding = false
        customText.maxLines = 2
        addView(customText, LayoutParams(-1, dp(36), Gravity.CENTER))
        refresh(backgroundSource)
    }

    fun refresh(style: SettingsAppearanceSource) {
        val night = isNight()
        if (backgroundSource.exists) {
            background = ColorDrawable(Color.TRANSPARENT)
            backgroundImage.setImageDrawable(runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, backgroundSource.uri))
            }.getOrNull())
            backgroundImage.visibility = if (backgroundImage.drawable == null) View.GONE else View.VISIBLE
            backgroundImage.scaleX = style.style2BackgroundScale.coerceIn(40, 200) / 100f
            backgroundImage.scaleY = backgroundImage.scaleX
            backgroundImage.translationX = dp(92f) * style.style2BackgroundHorizontalOffset.coerceIn(-120, 120) / 60f
            backgroundImage.translationY = -dp(92f) * style.style2BackgroundVerticalOffset.coerceIn(-120, 120) / 60f
            backgroundImage.setRenderEffect(
                if (style.style2BackgroundBlur > 0f) {
                    RenderEffect.createBlurEffect(
                        dp(style.style2BackgroundBlur),
                        dp(style.style2BackgroundBlur),
                        Shader.TileMode.CLAMP,
                    )
                } else null,
            )
        } else {
            backgroundImage.setImageDrawable(null)
            backgroundImage.visibility = View.GONE
            backgroundImage.setRenderEffect(null)
            background = cardSurface(night)
        }
        val secondary = if (night) 0xB8E9ECF5.toInt() else 0x991B1D23.toInt()
        version.setTextColor(textColor(style.style2VersionColorMode, secondary))
        version.textSize = 14f
        version.text = sourceText(updateSource, "miui_version_text")
        val customLogo = logoSource.takeIf { it.exists }?.let { LogoDrawableLoader.load(context, it) }
        val builtInLogo = customLogo ?: loadBuiltInLogo()
        logo.setImageDrawable(
            customLogo ?: builtInLogo?.let {
                SplitXiaomiOsLogoDrawable(
                    it,
                    textColor(style.style2LogoColorMode, if (night) 0xFFF7F8FC.toInt() else 0xFF17181C.toInt())
                )
            }
        )
        // Imported logos are user artwork and may be recolored as a whole. The
        // built-in HyperOS logo is split so its blue "OS" remains untouched.
        logo.colorFilter = if (customLogo != null) {
            PorterDuffColorFilter(
                textColor(style.style2LogoColorMode, if (night) 0xFFF7F8FC.toInt() else 0xFF17181C.toInt()),
                PorterDuff.Mode.SRC_IN,
            )
        } else {
            null
        }
        logo.visibility = if (logo.drawable == null) View.INVISIBLE else View.VISIBLE
        val horizontalGravity = when (style.style2LogoAlignment.coerceIn(0, 2)) {
            0 -> Gravity.START
            2 -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        content.gravity = horizontalGravity or Gravity.CENTER_VERTICAL
        version.gravity = horizontalGravity or Gravity.CENTER_VERTICAL
        val groupOffsetX = dp(92f) * style.style2LogoHorizontalOffset.coerceIn(-120, 120) / 60f
        val groupOffsetY = -dp(92f) * style.style2LogoVerticalOffset.coerceIn(-120, 120) / 60f
        content.translationX = groupOffsetX
        content.translationY = groupOffsetY
        // Reduce the previous baseline gap to 75% (a 9dp upward correction)
        // while keeping the user's slider adjustment relative to that point.
        val spacing = -dp(9f) + dp(36f) * style.style2LogoVersionSpacing.coerceIn(-120, 120) / 60f
        version.translationY = spacing
        configureCustomText(style, secondary)
    }

    private fun configureCustomText(style: SettingsAppearanceSource, color: Int) {
        val enabled = style.style2TextEnabled && style.style2Text.isNotBlank()
        customText.text = style.style2Text
        customText.setTextColor(textColor(style.style2TextColorMode, color))
        customText.textSize = 14f * style.style2TextScale.coerceIn(40, 200) / 100f
        customText.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) return
        if (!style.style2TextIndependent) {
            if (customText.parent !== content) {
                (customText.parent as? ViewGroup)?.removeView(customText)
                content.addView(customText, LinearLayout.LayoutParams(dp(220), dp(30)))
            }
            content.removeView(customText)
            val index = if (style.style2TextPosition == 0) 0 else content.childCount
            content.addView(customText, index)
            val logoGravity = when (style.style2LogoAlignment.coerceIn(0, 2)) {
                0 -> Gravity.START
                2 -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
            customText.gravity = logoGravity or Gravity.CENTER_VERTICAL
            customText.translationX = 0f
            customText.translationY = 0f
            val spacing = if (style.style2TextPosition == 0) style.style2TextSpacingAbove else style.style2TextSpacingBelow
            val effectiveSpacing = (-80 + spacing).coerceIn(-200, 40)
            val margin = dp(18f) + dp(24f) * effectiveSpacing / 60f
            customText.layoutParams = LinearLayout.LayoutParams(dp(220), dp(30)).apply {
                if (style.style2TextPosition == 0) bottomMargin = margin.roundToInt() else topMargin = margin.roundToInt()
            }
        } else {
            if (customText.parent !== this) {
                (customText.parent as? ViewGroup)?.removeView(customText)
                addView(customText, LayoutParams(-1, dp(36), Gravity.CENTER))
            }
            val alignment = style.style2TextAlignment.coerceIn(0, 2)
            val gravity = when (alignment) {
                1 -> Gravity.START
                2 -> Gravity.END
                else -> Gravity.CENTER_HORIZONTAL
            }
            customText.gravity = gravity or Gravity.CENTER_VERTICAL
            customText.layoutParams = LayoutParams(-1, dp(36), gravity or Gravity.CENTER_VERTICAL)
            customText.translationX = dp(92f) * style.style2TextHorizontalOffsetForAlignment().coerceIn(-120, 120) / 60f
            customText.translationY = -dp(92f) * style.style2TextVerticalOffsetForAlignment().coerceIn(-120, 120) / 60f
        }
    }

    private fun textColor(mode: Int, systemColor: Int): Int = when (mode.coerceIn(0, 2)) {
        1 -> 0xFF17181C.toInt()
        2 -> 0xFFF7F8FC.toInt()
        else -> systemColor
    }

    fun attach() {
        if (versionListenerAttached) return
        viewTreeObserver.addOnPreDrawListener(versionListener)
        versionListenerAttached = true
    }

    fun dispose() {
        if (!versionListenerAttached) return
        runCatching { viewTreeObserver.removeOnPreDrawListener(versionListener) }
        versionListenerAttached = false
    }

    private fun sourceText(source: View?, idName: String): String {
        val id = resources.getIdentifier(idName, "id", context.packageName)
        val target = source?.findViewById<View>(id) ?: source
        return (target as? TextView)?.text?.toString().orEmpty()
    }

    private fun surfaceDrawable(night: Boolean, radius: Int) = GradientDrawable().apply {
        setColor(if (night) 0xFF242932.toInt() else 0xFFF1F4FA.toInt())
        cornerRadius = dp(radius).toFloat()
    }

    private fun cardSurface(night: Boolean) = GradientDrawable().apply {
        setColor(cardBackground())
        cornerRadius = dp(28).toFloat()
    }

    private fun cardBackground(): Int {
        val id = resources.getIdentifier("my_device_info_item_background_color", "color", context.packageName)
        return if (id != 0) runCatching { context.getColor(id) }.getOrDefault(fallbackCardBackground()) else fallbackCardBackground()
    }

    private fun fallbackCardBackground() = if (isNight()) 0x33FFFFFF else 0xFFFFFFFF.toInt()

    private fun loadBuiltInLogo(): Drawable? {
        val names = listOf("xiaomi_os_logo", "xiaomi_os_logo_new", "provision_os_logo", "provision_os_logo_big")
        return names.asSequence()
            .mapNotNull { name ->
                val id = resources.getIdentifier(name, "drawable", context.packageName)
                if (id == 0) null else runCatching { context.getDrawable(id) }.getOrNull()
            }
            .firstOrNull()
    }

    private fun isNight() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

/** Draws the system Xiaomi/OS vector as two independently colorable regions. */
private class SplitXiaomiOsLogoDrawable(
    private val source: Drawable?,
    private val xiaomiColor: Int?,
) : Drawable() {
    private val xiaomiFilter = xiaomiColor?.let {
        PorterDuffColorFilter(it, PorterDuff.Mode.SRC_IN)
    }

    override fun draw(canvas: Canvas) {
        val drawable = source ?: return
        val bounds = bounds
        if (bounds.isEmpty) return
        drawable.bounds = bounds

        // xiaomi_os_logo's first path occupies about 82% of the viewport; the
        // trailing region is the independently colored OS wordmark.
        val split = bounds.left + (bounds.width() * 0.825f).roundToInt()
        canvas.save()
        canvas.clipRect(bounds.left, bounds.top, split, bounds.bottom)
        drawable.colorFilter = xiaomiFilter
        drawable.draw(canvas)
        canvas.restore()

        canvas.save()
        canvas.clipRect(split, bounds.top, bounds.right, bounds.bottom)
        drawable.colorFilter = null
        drawable.draw(canvas)
        canvas.restore()
        drawable.colorFilter = null
    }

    override fun setAlpha(alpha: Int) { source?.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { source?.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android API")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = source?.intrinsicWidth ?: -1
    override fun getIntrinsicHeight(): Int = source?.intrinsicHeight ?: -1
}

class HarmonyInfoCardsView(
    context: Context,
    private val nameSource: View,
    private val storageSource: View,
    private val imageSource: SettingsAppearanceSource,
) : LinearLayout(context) {
    private val nameTitle = TextView(context)
    private val nameSummary = TextView(context)
    private val storageTitle = TextView(context)
    private val storageSummary = TextView(context)
    private val phone = ImageView(context)
    private val ring = StorageRingView(context)
    private var decodedImageKey = ""
    private var currentScale = 100
    private val updateListener = android.view.ViewTreeObserver.OnPreDrawListener {
        refresh(currentScale)
        true
    }
    private var listenerAttached = false

    init {
        orientation = HORIZONTAL
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        gravity = Gravity.TOP
        clipChildren = false
        clipToPadding = false
        addView(nameCard(), LayoutParams(0, dp(182), 1f).apply { rightMargin = dp(6) })
        addView(storageCard(), LayoutParams(0, dp(182), 1f).apply { leftMargin = dp(6) })
        refresh(currentScale)
    }

    fun refresh(imageScale: Int) {
        currentScale = imageScale
        val night = isNight()
        val primary = if (night) 0xFFF7F8FC.toInt() else 0xFF17181C.toInt()
        val secondary = if (night) 0xB8E9ECF5.toInt() else 0x991B1D23.toInt()
        background = null
        listOf(nameTitle, storageTitle).forEach { styleText(it, primary, 16f) }
        listOf(nameSummary, storageSummary).forEach { styleText(it, secondary, 14f) }
        nameTitle.text = sourceText(nameSource, "title").ifBlank { "设备名称" }
        nameSummary.text = sourceText(nameSource, "summary")
        storageTitle.text = sourceText(storageSource, "title").ifBlank { "存储空间" }
        storageSummary.text = sourceText(storageSource, "summary")
        ring.progress = storageFraction(storageSummary.text?.toString().orEmpty())
        val imageKey = imageSource.cacheKey()
        if (imageKey != decodedImageKey) {
            phone.setImageDrawable(decode(imageSource))
            decodedImageKey = imageKey
        }
        phone.visibility = if (phone.drawable == null) View.GONE else View.VISIBLE
        phone.scaleX = imageScale.coerceIn(40, 200) / 100f
        phone.scaleY = phone.scaleX
        (phone.parent as? View)?.background = GradientDrawable().apply {
            setColor(if (night) 0x223E7FEA else 0x154D83E8)
            shape = GradientDrawable.OVAL
        }
        (nameTitle.parent?.parent as? View)?.background = cardBackground()
        (storageTitle.parent?.parent as? View)?.background = cardBackground()
    }

    fun attach() {
        if (listenerAttached) return
        viewTreeObserver.addOnPreDrawListener(updateListener)
        listenerAttached = true
    }

    fun dispose() {
        if (!listenerAttached) return
        runCatching { viewTreeObserver.removeOnPreDrawListener(updateListener) }
        listenerAttached = false
    }

    private fun nameCard(): View {
        val card = column().apply { setOnClickListener { nameSource.performClick() } }
        card.addView(labelBlock(nameTitle, nameSummary), LinearLayout.LayoutParams(-1, dp(70)))
        val imageHost = FrameLayout(context).apply {
            background = GradientDrawable().apply { setColor(if (isNight()) 0x223E7FEA else 0x154D83E8); shape = GradientDrawable.OVAL }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            clipChildren = true
        }
        phone.scaleType = ImageView.ScaleType.FIT_CENTER
        imageHost.addView(phone, FrameLayout.LayoutParams(dp(78), dp(78), Gravity.CENTER))
        card.addView(imageHost, LinearLayout.LayoutParams(dp(78), dp(78)).apply { gravity = Gravity.START; topMargin = dp(6) })
        return card
    }

    private fun storageCard(): View {
        val card = column().apply { setOnClickListener { storageSource.performClick() } }
        card.addView(labelBlock(storageTitle, storageSummary), LinearLayout.LayoutParams(-1, dp(70)))
        // Center the enlarged ring in the same 78dp footprint as the device
        // circle so both controls share the same visual centerline.
        val ringHost = FrameLayout(context).apply {
            addView(ring, FrameLayout.LayoutParams(dp(69), dp(69), Gravity.CENTER))
        }
        card.addView(ringHost, LinearLayout.LayoutParams(dp(78), dp(78)).apply {
            gravity = Gravity.START
            topMargin = dp(6)
        })
        return card
    }

    private fun labelBlock(title: TextView, summary: TextView) = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.START
        addView(title, LinearLayout.LayoutParams(-1, dp(28)))
        addView(summary, LinearLayout.LayoutParams(-1, dp(36)))
    }

    private fun column() = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.TOP or Gravity.START
        setPadding(dp(16), dp(14), dp(16), dp(12))
        isClickable = true
        isFocusable = true
    }

    private fun styleText(view: TextView, color: Int, size: Float) {
        view.setTextColor(color)
        view.textSize = size
        view.includeFontPadding = false
        view.maxLines = 2
        view.gravity = Gravity.START
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

    private fun decode(source: SettingsAppearanceSource): Drawable? = runCatching {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, source.uri))
    }.getOrNull()

    private fun cardBackground() = GradientDrawable().apply {
        setColor(tutorialCardBackground())
        cornerRadius = dp(28).toFloat()
    }

    private fun tutorialCardBackground(): Int {
        val id = resources.getIdentifier("my_device_info_item_background_color", "color", context.packageName)
        return if (id != 0) runCatching { context.getColor(id) }.getOrDefault(fallbackCardBackground()) else fallbackCardBackground()
    }

    private fun fallbackCardBackground() = if (isNight()) 0x33FFFFFF else 0xFFFFFFFF.toInt()

    private fun isNight() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private class StorageRingView(context: Context) : View(context) {
        var progress: Int = 0
            set(value) { field = value.coerceIn(0, 1000); invalidate() }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.resources.displayMetrics.density * 10f; strokeCap = Paint.Cap.ROUND }
        override fun onDraw(canvas: Canvas) {
            val inset = stroke.strokeWidth / 2f
            val bounds = RectF(inset, inset, width - inset, height - inset)
            stroke.color = if (isNight()) 0x443F4C63 else 0x223C5B8F
            canvas.drawArc(bounds, -90f, 360f, false, stroke)
            stroke.color = if (isNight()) 0xFFB9D4FF.toInt() else 0xFF4D83E8.toInt()
            canvas.drawArc(bounds, -90f, 360f * progress / 1000f, false, stroke)
        }
        private fun isNight() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private companion object {
        val STORAGE_VALUE = Regex("(\\d+(?:[.,]\\d+)?)\\s*([KMGTkmgt])?[Bb]?")
    }
}
