// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Uses the same MIUI view-blur blender sequence as HyperCeiler's about-page logo. */
class MiuiBlendImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {
    private var drawableResource = 0
    private var themeDark: Boolean? = null

    fun setThemeDark(dark: Boolean) {
        if (themeDark == dark) return
        themeDark = dark
        applyBlend()
    }

    fun setBlendDrawable(resource: Int) {
        drawableResource = resource
        setBackgroundResource(resource)
        applyBlend()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyBlend()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyBlend()
    }

    private fun applyBlend() {
        if (drawableResource == 0) return
        val dark = themeDark ?: (
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            )
        val colors = if (dark) {
            intArrayOf(0xE6A1A1A1.toInt(), 0x4DE6E6E6, 0xFF1AF500.toInt())
        } else {
            intArrayOf(0xCC4A4A4A.toInt(), 0xFF4F4F4F.toInt(), 0xFF1AF200.toInt())
        }
        val modes = intArrayOf(if (dark) 18 else 19, 100, 106)
        val applied = runCatching {
            View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType).invoke(this, 3)
            runCatching {
                View::class.java.getMethod("clearMiBackgroundBlendColor").invoke(this)
            }
            val addColor = View::class.java.getMethod(
                "addMiBackgroundBlendColor",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            colors.indices.forEach { addColor.invoke(this, colors[it], modes[it]) }
        }.isSuccess
        if (!applied) {
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (dark) Color.argb(217, 185, 180, 200) else Color.argb(204, 74, 74, 74),
            )
        } else {
            backgroundTintList = null
        }
    }
}

class HyperCeilerBrandView(context: Context) : FrameLayout(context) {
    private val logo = MiuiBlendImageView(context)
    private val wordmark = MiuiBlendImageView(context)
    private val version = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = Typeface.create(typeface, Typeface.BOLD)
        includeFontPadding = false
    }
    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(logo)
        addView(wordmark)
        addView(version)
    }

    init {
        alpha = .85f
        clipChildren = false
        clipToPadding = false
        content.clipChildren = false
        content.clipToPadding = false
        addView(content, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        logo.setBlendDrawable(R.drawable.ic_hyperchanger_logo_sp)
        wordmark.setBlendDrawable(R.drawable.ic_hyperchanger_text_sp)
    }

    fun configure(
        logoSize: Int,
        textWidth: Int,
        versionText: String,
        textColor: Int,
        dark: Boolean,
        onLogoClick: () -> Unit,
    ) {
        enableRootBlur()
        content.translationY = -dp(40).toFloat()
        logo.setThemeDark(dark)
        wordmark.setThemeDark(dark)
        logo.setBlendDrawable(R.drawable.ic_hyperchanger_logo_sp)
        wordmark.setBlendDrawable(R.drawable.ic_hyperchanger_text_sp)
        logo.layoutParams = LinearLayout.LayoutParams(logoSize, logoSize)
        logo.setOnClickListener { onLogoClick() }
        val textHeight = (textWidth * 104f / 766f).toInt()
        wordmark.layoutParams = LinearLayout.LayoutParams(textWidth, textHeight).apply {
            topMargin = dp(14)
        }
        version.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(18) }
        version.text = versionText
        version.setTextColor(textColor)
        content.requestLayout()
    }

    private fun enableRootBlur() {
        runCatching {
            View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType)
                .invoke(this, 1)
            View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType)
                .invoke(this, dp(50))
            View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType)
                .invoke(this, 0)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
}
