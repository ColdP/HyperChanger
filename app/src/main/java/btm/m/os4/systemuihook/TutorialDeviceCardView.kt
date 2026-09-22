// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.res.Configuration
import android.graphics.ImageDecoder
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Runtime replacement for the tutorial style 1 device card. */
class TutorialDeviceCardView(
    context: Context,
    private val imageSource: SettingsAppearanceSource,
    private val logoSource: SettingsAppearanceSource,
    private val backgroundSource: SettingsAppearanceSource,
    private val updateSource: View?,
    private val clickSource: View?,
) : FrameLayout(context) {
    private val surface = View(context)
    private val bottomSurface = View(context)
    private val backgroundImage = ImageView(context)
    private val phone = ImageView(context)
    private val topLogo = ImageView(context)
    private val bottomLogo = ImageView(context)
    private val author = TextView(context)
    private val separator = TextView(context)
    private val version = TextView(context)
    private var backgroundHiddenByLongPress = false

    init {
        isClickable = true
        clipChildren = true
        clipToPadding = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        // Keep a real opaque card surface when no custom top image is imported.
        // The host card background is cleared by TutorialCardSession, so this
        // child must own the fallback fill itself.
        addView(surface, LayoutParams(-1, -1))
        backgroundImage.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(backgroundImage, LayoutParams(-1, -1))
        addView(bottomSurface.apply {
            background = GradientDrawable().apply {
                setColor(0x33000000)
                val radius = dp(20f).toFloat()
                setCornerRadii(floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius))
            }
        }, LayoutParams(-1, dp(40f)).apply { topMargin = dp(140f) })
        phone.scaleType = ImageView.ScaleType.FIT_END
        phone.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> view.pivotY = view.height.toFloat() }
        addView(phone, LayoutParams(dp(105f), dp(140f)).apply { gravity = Gravity.START or Gravity.BOTTOM; leftMargin = dp(40f); bottomMargin = dp(40f) })
        topLogo.scaleType = ImageView.ScaleType.FIT_CENTER
        addView(topLogo, LayoutParams(dp(150f), dp(140f)).apply { gravity = Gravity.END or Gravity.TOP; rightMargin = dp(40f) })

        val bottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(15f), 0, dp(15f), 0) }
        bottomLogo.scaleType = ImageView.ScaleType.FIT_CENTER
        bottom.addView(bottomLogo, LinearLayout.LayoutParams(dp(115f), dp(38f)))
        bottom.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        configureText(author)
        configureText(version)
        bottom.addView(author, LinearLayout.LayoutParams(-2, -2))
        separator.text = " | "
        configureText(separator)
        bottom.addView(separator, LinearLayout.LayoutParams(-2, -2))
        bottom.addView(version, LinearLayout.LayoutParams(-2, -2))
        addView(bottom, LayoutParams(-1, dp(40f)).apply { gravity = Gravity.BOTTOM })
        setOnLongClickListener {
            if (backgroundImage.drawable == null) return@setOnLongClickListener false
            backgroundHiddenByLongPress = !backgroundHiddenByLongPress
            backgroundImage.visibility = if (backgroundHiddenByLongPress) GONE else VISIBLE
            true
        }
        // The replacement card sits above MiuiVersionCard's original click
        // target. Forward taps so the stock system-update navigation remains
        // available while keeping the custom card's visuals independent.
        setOnClickListener { clickSource?.performClick() }
    }

    fun refresh(context: Context, imageScale: Int, cardAuthor: String, logoScale: Int, logoVerticalOffset: Int, imageLogoSpacing: Int, logoTextSpacing: Int, backgroundBlur: Float, backgroundHorizontalOffset: Int, backgroundVerticalOffset: Int, backgroundScale: Int) {
        val night = isNight()
        val surfaceColor = if (night) 0x5E313131.toInt() else 0x47545454.toInt()
        surface.background = rounded(surfaceColor)
        background = rounded(surfaceColor)
        bottomSurface.background = GradientDrawable().apply {
            setColor(if (night) 0x33000000 else 0xB0FAFAFA.toInt())
            val radius = dp(20f).toFloat()
            setCornerRadii(floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius))
        }
        val bg = decode(context, backgroundSource)
        backgroundImage.setImageDrawable(bg)
        if (bg == null) backgroundHiddenByLongPress = false
        backgroundImage.visibility = if (bg == null || backgroundHiddenByLongPress) GONE else VISIBLE
        backgroundImage.scaleX = backgroundScale.coerceIn(40, 200) / 100f
        backgroundImage.scaleY = backgroundImage.scaleX
        backgroundImage.translationX = dp(92f) * backgroundHorizontalOffset.coerceIn(-120, 120) / 60f
        backgroundImage.translationY = -dp(92f) * backgroundVerticalOffset.coerceIn(-120, 120) / 60f
        backgroundImage.setRenderEffect(if (backgroundBlur > 0f) RenderEffect.createBlurEffect(dp(backgroundBlur).toFloat(), dp(backgroundBlur).toFloat(), Shader.TileMode.CLAMP) else null)
        phone.setImageDrawable(decode(context, imageSource))
        phone.visibility = if (phone.drawable == null) GONE else VISIBLE
        val scale = imageScale.coerceIn(40, 200) / 100f
        phone.scaleX = scale
        phone.scaleY = scale
        topLogo.setImageDrawable(decode(context, logoSource))
        topLogo.visibility = if (topLogo.drawable == null) GONE else VISIBLE
        val logoScaleFactor = logoScale.coerceIn(40, 200) / 100f
        topLogo.scaleX = logoScaleFactor
        topLogo.scaleY = logoScaleFactor
        val spacingShift = dp(25f) * imageLogoSpacing.coerceIn(-120, 120) / 60f
        phone.translationX = -spacingShift
        topLogo.translationX = spacingShift
        topLogo.translationY = -dp(92f) * logoVerticalOffset.coerceIn(-120, 120) / 60f
        bottomLogo.setImageDrawable(loadBuiltInLogo())
        bottomLogo.translationX = -dp(36f) * logoTextSpacing.coerceIn(-120, 120) / 120f
        bottomLogo.visibility = if (bottomLogo.drawable == null) INVISIBLE else VISIBLE
        author.text = cardAuthor
        version.text = sourceText(updateSource, "miui_version_text")
        configureText(author)
        configureText(separator)
        configureText(version)
        visibility = VISIBLE
    }

    private fun configureText(view: TextView) { view.textSize = 14.5f; view.setTextColor(textColor()); view.maxLines = 1; view.isSingleLine = true; view.includeFontPadding = false }
    private fun textColor(): Int = if (isNight()) 0xFFFFFFFF.toInt() else 0xFF1B1B1B.toInt()
    private fun sourceText(source: View?, idName: String): String { val id = resources.getIdentifier(idName, "id", context.packageName); val target = source?.findViewById<View>(id) ?: source; return (target as? TextView)?.text?.toString().orEmpty() }
    private fun loadBuiltInLogo(): Drawable? = listOf("xiaomi_os_logo", "xiaomi_os_logo_new", "provision_os_logo", "provision_os_logo_big").asSequence().mapNotNull { name -> resources.getIdentifier(name, "drawable", context.packageName).takeIf { it != 0 }?.let { runCatching { context.getDrawable(it) }.getOrNull() } }.firstOrNull()
    private fun decode(context: Context, source: SettingsAppearanceSource): Drawable? = runCatching { if (!source.exists) return@runCatching null; ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, source.uri)) }.getOrNull()
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(20f).toFloat() }
    private fun isNight() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}
