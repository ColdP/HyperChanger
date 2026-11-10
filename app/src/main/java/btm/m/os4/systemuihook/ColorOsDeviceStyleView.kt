// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.*
import android.widget.*
import kotlin.math.abs
import kotlin.math.roundToInt

/** Style 3 shares style 2's native host and lifecycle. */
internal class ColorOsUpdateCardView(
    context: Context,
    backgroundSource: SettingsAppearanceSource,
    logoSource: SettingsAppearanceSource,
    private val updateSource: View?,
    private val clickSource: View,
) : FrameLayout(context) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var scrollingTouch = false
    private val image = ImageView(context)
    private val logo = ImageView(context)
    private val version = TextView(context)
    private val content = LinearLayout(context)
    private val update = TextView(context)
    private val updateCard = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // A MATCH_PARENT surface participates in FrameLayout's first measurement,
            // expanding a WRAP_CONTENT button to the entire banner. Measure the label
            // first, then size the independent material surface from that label.
            measureChildWithMargins(update, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val surfaceWidth = (update.measuredWidth * deviceStyleScale(controls.buttonBackgroundLengthScale)).roundToInt()
            val surfaceHeight = (update.measuredHeight * deviceStyleScale(controls.buttonBackgroundWidthScale)).roundToInt()
            updateSurface.measure(MeasureSpec.makeMeasureSpec(surfaceWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(surfaceHeight, MeasureSpec.EXACTLY))
            setMeasuredDimension(
                resolveSize(maxOf(update.measuredWidth, surfaceWidth), widthMeasureSpec),
                resolveSize(maxOf(update.measuredHeight, surfaceHeight), heightMeasureSpec),
            )
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            pivotX = w / 2f
            pivotY = h.toFloat()
        }
    }
    private val updateSurface = View(context)
    private var controls = backgroundSource.deviceStyleCustomization
    private var backgroundPresent = backgroundSource.exists
    private var nextUpdateCheck = 0L
    private var backgroundKey = ""
    private var logoKey = ""
    private var materialKey = ""
    private val listener = ViewTreeObserver.OnPreDrawListener { refreshVersion(); true }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(26).toFloat())
            }
        }
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(image, LayoutParams(-1, -1))
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.clipChildren = false
        content.clipToPadding = false
        logo.scaleType = ImageView.ScaleType.FIT_CENTER
        content.addView(logo, LinearLayout.LayoutParams(dp(220), dp(64)))
        version.gravity = Gravity.CENTER
        version.includeFontPadding = false
        version.textSize = 15f
        content.addView(version, LinearLayout.LayoutParams(-1, dp(28)))
        addView(content, LayoutParams(-1, -2, Gravity.TOP).apply { leftMargin = dp(32); rightMargin = dp(32) })
        updateCard.clipChildren = false
        updateCard.clipToPadding = false
        updateSurface.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        updateSurface.clipToOutline = true
        updateSurface.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) { materialKey = ""; applyUpdateMaterial() }
        }
        updateCard.addView(updateSurface, LayoutParams(-2, -2, Gravity.CENTER))
        update.gravity = Gravity.CENTER
        update.includeFontPadding = false
        update.textSize = 13.5f
        update.setPadding(dp(20), dp(10), dp(20), dp(10))
        updateCard.addView(update, LayoutParams(-2, -2, Gravity.CENTER))
        addView(updateCard, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(30) })
        // Only the explicit update-status button navigates to the native update page.
        // The rest of the replacement card must consume touches without forwarding
        // them, especially after the scroll fade makes the card visually transparent;
        // forwarding the outer card click lets the native update target underneath win.
        updateCard.setOnClickListener { clickSource.performClick() }
        setOnClickListener { /* consume blank-card taps */ }
        refresh(backgroundSource, logoSource)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                scrollingTouch = false
                // Keep a tap on the replacement card from reaching the native update view.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - touchDownX)
                val dy = abs(event.y - touchDownY)
                if (!scrollingTouch && dy > touchSlop && dy > dx) {
                    scrollingTouch = true
                    // The card consumed the down event only to block click passthrough.
                    // Once this is a vertical gesture, return interception to the page scroll.
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (scrollingTouch) {
                    scrollingTouch = false
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(listener)
        materialKey = ""
        applyUpdateMaterial()
    }
    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(listener)
        HyperOsBackdropMaterial.clear(updateSurface)
        materialKey = ""
        super.onDetachedFromWindow()
    }
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        content.layoutParams = (content.layoutParams as LayoutParams).apply { topMargin = (height * .27f).roundToInt() }
        logo.layoutParams = (logo.layoutParams as LinearLayout.LayoutParams).apply { this.width = minOf(dp(220), (width - dp(64)).coerceAtLeast(0)) }
        content.pivotX = when (controls.logoAlignment) { 0 -> 0f; 2 -> (width - dp(64)).toFloat(); else -> (width - dp(64)) / 2f }
    }

    fun refresh(backgroundSource: SettingsAppearanceSource, logoSource: SettingsAppearanceSource) {
        val previousControls = controls
        controls = backgroundSource.deviceStyleCustomization
        if (previousControls.buttonBackgroundLengthScale != controls.buttonBackgroundLengthScale ||
            previousControls.buttonBackgroundWidthScale != controls.buttonBackgroundWidthScale) updateCard.requestLayout()
        backgroundPresent = backgroundSource.exists
        val night = resources.configuration.uiMode and 0x30 == 0x20
        background = GradientDrawable().apply { setColor(if (night) 0xFF292A32.toInt() else 0xFFEDEEF2.toInt()) }
        val imageKey = "${backgroundSource.uri}:${backgroundSource.modified}:${backgroundSource.size}"
        if (backgroundKey != imageKey) {
            image.setImageDrawable(backgroundSource.takeIf { it.exists }?.let { LogoDrawableLoader.load(context, it) })
            image.visibility = if (image.drawable == null) View.GONE else View.VISIBLE
            backgroundKey = imageKey
        }
        image.scaleX = deviceStyleScale(controls.backgroundScale)
        image.scaleY = image.scaleX
        image.translationX = dp(controls.backgroundX).toFloat()
        image.translationY = -dp(controls.backgroundY).toFloat()
        image.setRenderEffect(if (controls.backgroundBlur == 0) null else RenderEffect.createBlurEffect(dp(controls.backgroundBlur).toFloat(), dp(controls.backgroundBlur).toFloat(), Shader.TileMode.CLAMP))
        val newLogoKey = "${logoSource.uri}:${logoSource.modified}:${logoSource.size}:${controls.logoColorMode}:$night:$backgroundPresent"
        if (logoKey != newLogoKey) {
            val custom = logoSource.takeIf { it.exists }?.let { LogoDrawableLoader.load(context, it) }
            val ink = foreground(controls.logoColorMode)
            logo.setImageDrawable(custom ?: loadBuiltInLogo()?.let { SplitXiaomiOsLogoDrawable(it, ink) })
            logo.colorFilter = if (custom != null && controls.logoColorMode != 0 && !controls.logoKeepsColors(logoSource.slot)) PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN) else null
            logo.visibility = if (logo.drawable == null) View.INVISIBLE else View.VISIBLE
            logoKey = newLogoKey
        }
        val alignment = when (controls.logoAlignment) { 0 -> Gravity.START; 2 -> Gravity.END; else -> Gravity.CENTER_HORIZONTAL }
        content.gravity = alignment
        (logo.layoutParams as LinearLayout.LayoutParams).let { params ->
            if (params.gravity != alignment) logo.layoutParams = params.apply { gravity = alignment }
        }
        version.gravity = alignment or Gravity.CENTER_VERTICAL
        val pivot = when (controls.logoAlignment) { 0 -> 0f; 2 -> logo.layoutParams.width.toFloat(); else -> logo.layoutParams.width / 2f }
        logo.pivotX = pivot
        logo.scaleX = deviceStyleScale(controls.logoScale)
        logo.scaleY = logo.scaleX
        content.scaleX = deviceStyleScale(controls.contentScale)
        content.scaleY = content.scaleX
        content.translationX = dp(controls.logoX).toFloat()
        content.translationY = -dp(controls.logoY).toFloat()
        val versionParams = version.layoutParams as LinearLayout.LayoutParams
        val spacing = dp(controls.logoSpacing)
        if (versionParams.topMargin != spacing) version.layoutParams = versionParams.apply { topMargin = spacing }
        version.textSize = 15f * deviceStyleScale(controls.versionScale)
        version.setTextColor(foreground(controls.versionColorMode))
        update.setTextColor(foreground(controls.buttonTextColorMode))
        update.scaleX = deviceStyleScale(controls.buttonTextScale)
        update.scaleY = update.scaleX
        updateCard.scaleX = deviceStyleScale(controls.buttonOverallScale)
        updateCard.scaleY = updateCard.scaleX
        updateCard.translationX = dp(controls.buttonX).toFloat()
        updateCard.translationY = -dp(controls.buttonY).toFloat()
        updateCard.visibility = if (backgroundSource.style3UpdateStatusVisible) View.VISIBLE else View.GONE
        applyUpdateMaterial()
        refreshVersion()
    }

    /** Transparent plain View: no HyperCardView drawable can cover the HyperOS compositor. */
    private fun applyUpdateMaterial() {
        if (!isAttachedToWindow || updateCard.visibility != View.VISIBLE || updateSurface.width == 0 || updateSurface.height == 0) return
        val night = resources.configuration.uiMode and 0x30 == 0x20
        val key = "${controls.encode()}:$night:${updateSurface.width}:${updateSurface.height}"
        if (materialKey == key) return
        materialKey = key
        HyperOsBackdropMaterial.clear(updateSurface)
        val color = when (controls.buttonColorMode) {
            1 -> Color.WHITE
            2 -> 0xFF17181C.toInt()
            else -> if (night) 0xFF17181C.toInt() else Color.WHITE
        }
        updateSurface.background = GradientDrawable().apply {
            cornerRadius = dp(40).toFloat()
            val pure = if (controls.buttonColorMode == 0) controls.buttonPureColor else color
            setColor(if (controls.buttonMaterial == 0) Color.argb(controls.buttonPureOpacity * Color.alpha(pure) / 100, Color.red(pure), Color.green(pure), Color.blue(pure)) else Color.TRANSPARENT)
        }
        runCatching {
            when (controls.buttonMaterial) {
                1 -> HyperOsBackdropMaterial.apply(updateSurface, controls.buttonAdvancedOpacity, controls.buttonAdvancedBlur, color, controls.buttonHighlight)
                2 -> {
                    val glass = controls.glass()
                    HyperOsBackdropMaterial.apply(updateSurface, softGlassBlur(10, glass.backgroundOpacity), softGlassBlur(40, glass.backgroundBlur), color, false)
                    View::class.java.getMethod("setMiBackgroundBlurScaleRatio", Float::class.javaPrimitiveType).invoke(updateSurface, softGlassScale(1f, glass.backgroundScale))
                    PortalGlassMaterial.apply(updateSurface, glass, softGlassBlur(40, glass.backgroundBlur))
                }
            }
        }.onFailure {
            ModuleLog.w("SettingsAppearance", "HyperOS update material failed (mode=${controls.buttonMaterial})", it)
        }
    }

    private fun foreground(mode: Int): Int = when (mode) {
        1 -> 0xFF17181C.toInt()
        2 -> 0xFFF7F8FC.toInt()
        else -> if (backgroundPresent || resources.configuration.uiMode and 0x30 == 0x20) Color.WHITE else Color.DKGRAY
    }
    private fun refreshVersion() {
        val current = controls.versionText.takeIf { it.isNotBlank() } ?: (updateSource as? TextView)?.text ?: ""
        if (version.text.toString() != current.toString()) version.text = current
        val now = android.os.SystemClock.elapsedRealtime()
        if (updateCard.visibility == View.VISIBLE && now >= nextUpdateCheck) {
            nextUpdateCheck = now + 1500L
            val available = runCatching {
                val type = context.classLoader.loadClass("com.android.settings.device.MiuiAboutPhoneUtils")
                !(type.getMethod("getUpdateInfo", Context::class.java).invoke(null, context) as? String).isNullOrBlank()
            }.getOrNull()
            val text = when (available) {
                true -> tr("appearance_update_available", "发现新版本")
                false -> tr("appearance_up_to_date", "已是最新版本")
                null -> tr("appearance_check_updates", "检查更新")
            }
            if (update.text.toString() != text) update.text = text
        }
    }
    private fun tr(key: String, fallback: String) = UnlockIslandRuntime.tr(context, key, fallback)
    private fun loadBuiltInLogo(): Drawable? = listOf("xiaomi_os_logo", "xiaomi_os_logo_new", "provision_os_logo")
        .asSequence().mapNotNull { name -> resources.getIdentifier(name, "drawable", context.packageName).takeIf { it != 0 }?.let { runCatching { context.getDrawable(it) }.getOrNull() } }.firstOrNull()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

internal class ColorOsInfoCardsView(context: Context, private val nameSource: View, private val storageSource: View, backgroundSource: SettingsAppearanceSource) : LinearLayout(context) {
    private val name = TextView(context)
    private val nameValue = TextView(context)
    private val storage = TextView(context)
    private val storageValue = TextView(context)
    private val progress = MonetStorageProgressView(context)
    private val divider = DeviceStyleDividerView(context)
    private var nightMode: Boolean? = null
    private val listener = ViewTreeObserver.OnPreDrawListener { refresh(); true }

    init {
        orientation = VERTICAL
        setPadding(dp(18), dp(7), dp(18), dp(18))
        addView(row(name, nameValue, nameSource), LayoutParams(-1, dp(52)))
        addView(divider, LayoutParams(-1, dp(1).coerceAtLeast(1)))
        addView(row(storage, storageValue, storageSource), LayoutParams(-1, dp(44)))
        addView(progress, LayoutParams(-1, dp(9)))
        refresh(backgroundSource)
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(listener)
    }
    override fun onDetachedFromWindow() {
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(listener)
        super.onDetachedFromWindow()
    }

    private fun row(title: TextView, value: TextView, source: View) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        title.textSize = 16f
        title.includeFontPadding = false
        value.textSize = 14.5f
        value.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        value.includeFontPadding = false
        value.setSingleLine()
        value.ellipsize = TextUtils.TruncateAt.END
        addView(title, LayoutParams(-2, -2))
        addView(value, LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        addView(ChevronView(context), LayoutParams(dp(8), dp(14)).apply { marginStart = dp(8) })
        setOnClickListener { source.performClick() }
        isFocusable = true
    }

    fun refresh(backgroundSource: SettingsAppearanceSource? = null) {
        // Native hidden cards continue receiving asynchronous data. Only changed text requests layout.
        setText(name, sourceText(nameSource, "title"))
        setText(nameValue, sourceText(nameSource, "summary"))
        setText(storage, sourceText(storageSource, "title"))
        setText(storageValue, sourceText(storageSource, "summary"))
        if (backgroundSource != null) progress.setBackgroundSource(backgroundSource)
        progress.refreshTheme()
        val night = resources.configuration.uiMode and 0x30 == 0x20
        if (nightMode != night) {
            nightMode = night
            val surfaceId = resources.getIdentifier("my_device_info_item_background_color", "color", context.packageName)
            val surfaceColor = if (surfaceId != 0) runCatching { context.getColor(surfaceId) }.getOrNull() else null
            background = GradientDrawable().apply { setColor(surfaceColor ?: if (night) 0x33FFFFFF else Color.WHITE); cornerRadius = dp(22).toFloat() }
            listOf(name, storage).forEach { it.setTextColor(if (night) Color.WHITE else 0xFF17181C.toInt()) }
            listOf(nameValue, storageValue).forEach { it.setTextColor(if (night) 0xB8E9ECF5.toInt() else 0xFF999999.toInt()) }
            divider.invalidate()
            progress.invalidate()
        }
    }
    private fun setText(view: TextView, text: CharSequence) { if (view.text.toString() != text.toString()) view.text = text }
    private fun sourceText(view: View, name: String) = (view.findViewById<View>(resources.getIdentifier(name, "id", context.packageName)) as? TextView)?.text ?: ""
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private class ChevronView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFAAAAAA.toInt(); style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 1.3f }
        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(Path().apply { moveTo(width * .2f, height * .2f); lineTo(width * .8f, height * .5f); lineTo(width * .2f, height * .8f) }, paint)
        }
    }


}

/** Draw against the current configuration, including a theme change on an existing header. */
internal class DeviceStyleDividerView(context: Context) : View(context) {
    private val paint = Paint()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = if (resources.configuration.uiMode and 0x30 == 0x20) 0x24FFFFFF else 0x14000000
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        invalidate()
    }
}

internal class ColorOsCardSession(private val host: FrameLayout, private val spacer: View?, private val originalParams: ViewGroup.LayoutParams?, private val animationSource: View?, val view: ColorOsUpdateCardView, val key: String) {
    private val scrollTransition = DeviceCardScrollTransition(view, host)
    private val background = host.background
    private val originalHostHeight = host.layoutParams?.height
    private val originals = List(host.childCount) { host.getChildAt(it) }.filter { it !== view }.map { it to it.visibility }
    private var attached = false
    private val animationListener = ViewTreeObserver.OnPreDrawListener { resize(host.context); syncAnimation(); true }
    fun matches(host: FrameLayout, spacer: View?) = this.host === host && this.spacer === spacer && view.parent === host
    fun enforce(context: Context) {
        host.background = null
        originals.forEach { (child, _) -> child.visibility = View.INVISIBLE }
        view.visibility = View.VISIBLE
        if (!attached) {
            host.viewTreeObserver.addOnPreDrawListener(animationListener)
            attached = true
        }
        resize(context)
        syncAnimation()
    }
    private fun resize(context: Context) {
        val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
        val width = (host.width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels) - (margins?.leftMargin ?: 0) - (margins?.rightMargin ?: 0)
        val cardHeight = (width * (382f / 364f) * .92f).roundToInt()
        if (view.layoutParams.height != cardHeight) view.layoutParams = view.layoutParams.apply { height = cardHeight }
        val height = cardHeight + (30 * context.resources.displayMetrics.density).roundToInt()
        host.layoutParams?.takeIf { it.height != height }?.let { host.layoutParams = it.apply { this.height = height } }
        val params = spacer?.layoutParams
        if (params != null && params.height != height) spacer.layoutParams = when (params) {
            is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(params).apply { this.height = height }
            is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(params).apply { this.height = height }
            is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(params).apply { this.height = height }
            else -> ViewGroup.LayoutParams(params).apply { this.height = height }
        }
    }
    private fun syncAnimation() {
        scrollTransition.apply(animationSource)
    }
    fun remove() {
        scrollTransition.clear()
        if (attached && host.viewTreeObserver.isAlive) host.viewTreeObserver.removeOnPreDrawListener(animationListener)
        attached = false
        (view.parent as? ViewGroup)?.removeView(view)
        host.background = background
        originalHostHeight?.let { height -> host.layoutParams?.let { host.layoutParams = it.apply { this.height = height } } }
        originals.forEach { (child, visibility) -> child.visibility = visibility }
        originalParams?.let { spacer?.layoutParams = it }
    }
}

internal class ColorOsInfoCardsSession(private val parent: LinearLayout, private val name: View, private val storage: View, val view: ColorOsInfoCardsView) {
    private val background = parent.background
    private val originals = List(parent.childCount) { parent.getChildAt(it) }.filter { it !== view }.map { it to it.visibility }
    fun matches(parent: LinearLayout, name: View, storage: View) = this.parent === parent && this.name === name && this.storage === storage && view.parent === parent
    fun enforce() { parent.background = null; originals.forEach { (child, _) -> child.visibility = View.GONE } }
    fun remove() {
        (view.parent as? ViewGroup)?.removeView(view)
        parent.background = background
        originals.forEach { (child, visibility) -> child.visibility = visibility }
    }
}
