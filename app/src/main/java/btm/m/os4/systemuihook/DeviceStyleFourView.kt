// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Landscape banner and native-data text rows; the Settings page retains its scroll/navigation. */
internal class DeviceStyleFourSession(
    private val host: FrameLayout, private val spacer: View?, private val animation: View?,
    private val info: LinearLayout, private val detail: View, private val recycler: View,
    private val name: View, private val storage: View, private val update: View?,
    private val backgroundSource: SettingsAppearanceSource, private val logoSource: SettingsAppearanceSource,
    private val prepareCards: (Any) -> Unit, val key: String,
) {
    private val s = backgroundSource.deviceStyleCustomization.styleFour
    private val banner = StyleFourBannerView(host.context, backgroundSource, logoSource, update)
    private val scrollTransition = DeviceCardScrollTransition(banner, host)
    private val body = StyleFourParametersView(host.context, s, name, storage, recycler, prepareCards)
    private val originalHostBackground = host.background
    private val originalClipChildren = host.clipChildren
    private val originalClipToPadding = host.clipToPadding
    private val originalHostHeight = host.layoutParams.height
    private val originalSpacerHeight = spacer?.layoutParams?.height
    private val originalInfoBackground = info.background
    private val originalInfoPadding = intArrayOf(info.paddingLeft, info.paddingTop, info.paddingRight, info.paddingBottom)
    private val infoMargins = (info.layoutParams as? ViewGroup.MarginLayoutParams)?.let { intArrayOf(it.leftMargin, it.topMargin, it.rightMargin, it.bottomMargin) }
    private val originalDetailVisibility = detail.visibility
    private val hostChildren = List(host.childCount) { host.getChildAt(it) }.map { it to it.visibility }
    private val infoChildren = List(info.childCount) { info.getChildAt(it) }.map { it to it.visibility }
    private var failed = false
    private val listener = ViewTreeObserver.OnPreDrawListener { enforce(); true }

    init {
        host.addView(banner, FrameLayout.LayoutParams(-1, -1).apply {
            val density = host.resources.displayMetrics.density
            val outer = host.layoutParams as? ViewGroup.MarginLayoutParams
            leftMargin = styleFourContentInset(12f * density + (outer?.leftMargin ?: 0), s.contentMargin * density, 2f * density).roundToInt() - (outer?.leftMargin ?: 0)
            rightMargin = styleFourContentInset(12f * density + (outer?.rightMargin ?: 0), s.contentMargin * density, 2f * density).roundToInt() - (outer?.rightMargin ?: 0)
            topMargin = (styleFourSpacing(18f) * density + 2f * density).roundToInt()
        })
        info.addView(body, 0, LinearLayout.LayoutParams(-1, -2))
        host.viewTreeObserver.addOnPreDrawListener(listener)
        enforce()
    }

    fun matches(candidate: FrameLayout, candidateInfo: LinearLayout, candidateRecycler: View, candidateKey: String) =
        host === candidate && info === candidateInfo && recycler === candidateRecycler && key == candidateKey && banner.parent === host && body.parent === info

    fun enforce() {
        host.background = null
        host.clipChildren = false
        host.clipToPadding = false
        hostChildren.forEach { (view, _) -> view.visibility = View.INVISIBLE }
        info.background = null
        info.setPadding(0, 0, 0, 0)
        infoChildren.forEach { (view, _) -> view.visibility = View.GONE }
        detail.visibility = View.GONE
        (info.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            val density = host.resources.displayMetrics.density
            val left = styleFourContentInset((infoMargins?.get(0) ?: 0).toFloat(), s.contentMargin * density).roundToInt()
            val right = styleFourContentInset((infoMargins?.get(2) ?: 0).toFloat(), s.contentMargin * density).roundToInt()
            if (it.leftMargin != left || it.rightMargin != right || it.topMargin != 0 || it.bottomMargin != 0) {
                info.layoutParams = it.apply { leftMargin = left; rightMargin = right; topMargin = 0; bottomMargin = 0 }
            }
        }
        val width = host.width.takeIf { it > 0 } ?: host.resources.displayMetrics.widthPixels
        val margins = banner.layoutParams as FrameLayout.LayoutParams
        val cardWidth = (width - margins.leftMargin - margins.rightMargin).coerceAtLeast(1)
        val cardHeight = (cardWidth * (528f / 798f) * deviceStyleScale(s.bannerHeightScale)).roundToInt().coerceAtLeast(1)
        if (margins.height != cardHeight) banner.layoutParams = margins.apply { height = cardHeight }
        val height = cardHeight + margins.topMargin
        host.layoutParams.takeIf { it.height != height }?.let { host.layoutParams = it.apply { this.height = height } }
        spacer?.layoutParams?.takeIf { it.height != height }?.let { spacer.layoutParams = it.apply { this.height = height } }
        scrollTransition.apply(animation)
        banner.refreshVersion()
        runCatching { body.refresh() }.onFailure {
            if (!failed) { failed = true; ModuleLog.e("HyperChangerDeviceStyle4", "Could not refresh native parameters", it) }
        }
    }

    fun remove() {
        scrollTransition.clear()
        if (host.viewTreeObserver.isAlive) host.viewTreeObserver.removeOnPreDrawListener(listener)
        host.removeView(banner); info.removeView(body)
        host.background = originalHostBackground
        host.clipChildren = originalClipChildren
        host.clipToPadding = originalClipToPadding
        host.layoutParams = host.layoutParams.apply { height = originalHostHeight }
        originalSpacerHeight?.let { h -> spacer?.layoutParams?.let { spacer.layoutParams = it.apply { height = h } } }
        info.background = originalInfoBackground
        info.setPadding(originalInfoPadding[0], originalInfoPadding[1], originalInfoPadding[2], originalInfoPadding[3])
        infoMargins?.let { original -> (info.layoutParams as? ViewGroup.MarginLayoutParams)?.let { info.layoutParams = it.apply { leftMargin = original[0]; topMargin = original[1]; rightMargin = original[2]; bottomMargin = original[3] } } }
        detail.visibility = originalDetailVisibility
        hostChildren.forEach { (view, visible) -> view.visibility = visible }
        infoChildren.forEach { (view, visible) -> view.visibility = visible }
    }
}

private class StyleFourBannerView(
    context: Context, backgroundSource: SettingsAppearanceSource, logoSource: SettingsAppearanceSource,
    private val versionSource: View?,
) : FrameLayout(context) {
    private val s = backgroundSource.deviceStyleCustomization.styleFour
    private val version = TextView(context)
    init {
        val dark = styleFourNight()
        background = GradientDrawable().apply {
            cornerRadius = dp(s.cornerRadius).toFloat()
            setColor(if (dark) 0xFF292A32.toInt() else Color.WHITE)
        }
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        elevation = dp(s.shadow).toFloat()
        isClickable = true // The reference banner has no inherited invisible update click target.
        val artwork = backgroundSource.takeIf { it.exists }?.let { LogoDrawableLoader.load(context, it) }
        if (artwork != null) addView(ImageView(context).apply {
            setImageDrawable(artwork); scaleType = ImageView.ScaleType.CENTER_CROP
            scaleX = deviceStyleScale(s.backgroundScale); scaleY = scaleX
            translationX = dp(s.backgroundX).toFloat(); translationY = -dp(s.backgroundY).toFloat()
            if (s.backgroundBlur > 0) setRenderEffect(RenderEffect.createBlurEffect(dp(s.backgroundBlur).toFloat(), dp(s.backgroundBlur).toFloat(), Shader.TileMode.CLAMP))
        }, LayoutParams(-1, -1))
        val defaultInk = if (dark || artwork != null) Color.WHITE else 0xFF17181C.toInt()
        val ink = styleFourInk(s.logoColorMode, defaultInk)
        val custom = logoSource.takeIf { it.exists }?.let { LogoDrawableLoader.load(context, it) }
        val native = if (custom == null) listOf("xiaomi_os_logo", "xiaomi_os_logo_new", "provision_os_logo").asSequence().mapNotNull {
            resources.getIdentifier(it, "drawable", context.packageName).takeIf { id -> id != 0 }?.let { id -> runCatching { context.getDrawable(id) }.getOrNull() }
        }.firstOrNull() else null
        val logo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(custom ?: native?.let { SplitXiaomiOsLogoDrawable(it, ink) })
            if (custom != null && !logoSource.deviceStyleCustomization.logoKeepsColors(logoSource.slot)) setColorFilter(ink)
            scaleX = deviceStyleScale(s.logoScale); scaleY = scaleX
        }
        val group = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = styleFourGravity(s.logoAlignment) or Gravity.CENTER_VERTICAL
            setPadding(dp(styleFourSpacing(24f)), 0, dp(styleFourSpacing(24f)), 0)
            translationX = dp(s.logoX).toFloat(); translationY = -dp(s.logoY).toFloat()
            addView(logo, LinearLayout.LayoutParams(dp(240), dp(76)))
            version.includeFontPadding = false
            version.gravity = styleFourGravity(s.logoAlignment)
            version.textSize = 14f * deviceStyleScale(s.versionScale)
            version.setTextColor(styleFourInk(s.versionColorMode, defaultInk))
            version.visibility = if (s.versionVisible) VISIBLE else GONE
            addView(version, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(styleFourSpacing(s.versionSpacing.toFloat())) })
        }
        addView(group, LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        refreshVersion()
    }
    fun refreshVersion() {
        val value = s.versionText.ifBlank { (versionSource as? TextView)?.text?.toString().orEmpty() }
        if (version.text.toString() != value) version.text = value
    }
}

private class StyleFourParametersView(
    context: Context, private val s: DeviceStyleFourSettings, private val name: View, private val storage: View,
    private val recycler: View, private val prepareCards: (Any) -> Unit,
) : LinearLayout(context) {
    private var signature = ""
    init { orientation = VERTICAL; isClickable = true; clipChildren = false }

    fun refresh() {
        val adapter = recycler.javaClass.getMethod("getAdapter").invoke(recycler)
        var cards = adapter?.let { NativeDeviceCards.read(it) }.orEmpty()
        val nativeName = nativeText(name, "summary")
        val nativeStorage = nativeText(storage, "summary")
        fun snapshot() = cards.joinToString("|") { "${System.identityHashCode(it.item)}:${it.index}:${it.title}:${it.value}:${it.first}:${it.second}" } + ":$nativeName:$nativeStorage:${resources.configuration.uiMode}"
        if (signature == snapshot()) return
        if (adapter != null) { prepareCards(adapter); cards = NativeDeviceCards.read(adapter) }
        signature = snapshot()
        removeAllViews()
        val model = s.modelText.ifBlank { cards.firstOrNull { it.index == 6 }?.value.orEmpty().ifBlank { android.os.Build.MODEL } }
        addView(label(model, 32f * deviceStyleScale(s.modelScale), true, styleFourInk(s.modelColorMode, primary())).apply {
            gravity = styleFourGravity(s.modelAlignment)
        }, LayoutParams(-1, -2).apply { topMargin = dp(styleFourSpacing(s.modelTopGap.toFloat())); bottomMargin = dp(styleFourSpacing(s.modelBottomGap.toFloat())) })
        if (s.dividersVisible) addDivider()
        val values = listOf(nativeName, value(cards, 0), nativeStorage, value(cards, 5), value(cards, 1), value(cards, 3), value(cards, 4), value(cards, 2))
        val fallbackLabels = listOf("设备名称", "处理器", "存储空间", "运行内存", "电池容量", "屏幕尺寸", "分辨率", "影像参数")
        val nativeLabels = listOf(nativeText(name, "title"), title(cards, 0), nativeText(storage, "title"), title(cards, 5), title(cards, 1), title(cards, 3), title(cards, 4), title(cards, 2))
        val keys = listOf("设备名称", "处理器", "存储空间", "运行内存", "电池容量", "屏幕尺寸", "分辨率", "device4_camera")
        val rows = (0..7).filter { s.visibleRows[it] }.associateWith { i ->
            val text = s.rowValues[i].ifBlank { values[i] }
            val title = nativeLabels[i].ifBlank { UnlockIslandRuntime.tr(context, keys[i], fallbackLabels[i]) }
            parameter(i, text, title)
        }
        var index = 0
        while (index < 8) {
            if (index == 2 && s.pairMemoryStorage && rows[2] != null && rows[3] != null) {
                val pair = PairParameterRow(context, rows.getValue(3), rows.getValue(2))
                addView(pair, LayoutParams(-1, -2).apply { topMargin = dp(styleFourSpacing(s.rowGap.toFloat())) })
                index = 4
            } else {
                rows[index]?.let { addView(it, LayoutParams(-1, -2).apply { topMargin = dp(styleFourSpacing(s.rowGap.toFloat())) }) }
                index++
            }
        }
    }

    private fun parameter(index: Int, value: String, title: String): View = StyleFourParameterRow(
        context,
        label(value, 18f * deviceStyleScale(s.valueScale), true, styleFourInk(s.parameterColorMode, primary())),
        label(title, 13f * deviceStyleScale(s.labelScale), false, styleFourInk(s.parameterColorMode, secondary())),
        if (index == 0) StyleFourArrowView(context, styleFourInk(s.parameterColorMode, secondary()))
        else if (s.markersVisible) View(context).apply { setBackgroundColor(s.markerColor) } else null,
        s.alignment, dp(styleFourSpacing(s.textSpacing.toFloat())), dp(styleFourSpacing(s.markerSpacing.toFloat())),
        dp(if (index == 0) 8 else s.markerWidth), dp(if (index == 0) 14 else s.markerHeight),
        index != 0 && s.markerPosition == 1,
    ).apply {
        minimumHeight = dp(56f * deviceStyleScale(s.rowHeightScale))
        setPadding(0, dp(styleFourSpacing(9f)), 0, dp(styleFourSpacing(9f)))
        when (index) {
            0 -> setOnClickListener { name.performClick() }
            2 -> setOnClickListener { storage.performClick() }
            3 -> setOnClickListener { openDeviceMemoryExtension(context) }
            else -> isClickable = true
        }
    }

    private fun addDivider(top: Int = 0) = addView(DeviceStyleDividerView(context), LayoutParams(-1, dp(1).coerceAtLeast(1)).apply { topMargin = top })
    private fun label(value: String, size: Float, bold: Boolean, color: Int) = TextView(context).apply {
        text = value; textSize = size; includeFontPadding = false; setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun nativeText(view: View, id: String) = (view.findViewById<View>(resources.getIdentifier(id, "id", context.packageName)) as? TextView)?.text?.toString().orEmpty()
    private fun value(cards: List<NativeDeviceCardSnapshot>, index: Int) = cards.firstOrNull { it.index == index }?.let { if (it.first.isNotBlank() && it.second.isNotBlank()) "${it.first} ${it.second}" else it.value }.orEmpty()
    private fun title(cards: List<NativeDeviceCardSnapshot>, index: Int) = cards.firstOrNull { it.index == index }?.title.orEmpty()
    private fun primary() = if (styleFourNight()) Color.WHITE else 0xFF111214.toInt()
    private fun secondary() = if (styleFourNight()) 0xB3FFFFFF.toInt() else 0xFF929295.toInt()
}

/** Align the entire value/label/marker unit and wrap long native values within its row. */
private class StyleFourParameterRow(
    context: Context, private val value: TextView, private val label: TextView, private val marker: View?,
    private val alignment: Int, private val textGap: Int, private val markerGap: Int,
    private val markerWidth: Int, private val markerHeight: Int, private val markerBefore: Boolean,
) : ViewGroup(context) {
    init {
        value.gravity = styleFourGravity(alignment)
        label.gravity = styleFourGravity(alignment)
        addView(value); addView(label); marker?.let(::addView)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val natural = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val markerSpace = if (marker == null) 0 else markerWidth + markerGap
        val contentWidth = (available - markerSpace - textGap).coerceAtLeast(0)
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        label.measure(if (natural) free else MeasureSpec.makeMeasureSpec(contentWidth / 2, MeasureSpec.AT_MOST), free)
        value.measure(if (natural) free else MeasureSpec.makeMeasureSpec((contentWidth - label.measuredWidth).coerceAtLeast(0), MeasureSpec.AT_MOST), free)
        marker?.measure(MeasureSpec.makeMeasureSpec(markerWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(markerHeight, MeasureSpec.EXACTLY))
        val width = value.measuredWidth + textGap + label.measuredWidth + markerSpace + paddingLeft + paddingRight
        val height = maxOf(value.measuredHeight, label.measuredHeight, marker?.measuredHeight ?: 0) + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(maxOf(minimumHeight, height), heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val unit = value.measuredWidth + textGap + label.measuredWidth + if (marker == null) 0 else markerGap + markerWidth
        val free = (width - paddingLeft - paddingRight - unit).coerceAtLeast(0)
        var x = paddingLeft + when (alignment) { 0 -> 0; 1 -> free / 2; else -> free }
        fun place(view: View) {
            val y = paddingTop + (height - paddingTop - paddingBottom - view.measuredHeight) / 2
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
            x += view.measuredWidth
        }
        if (markerBefore) marker?.let { place(it); x += markerGap }
        place(value); x += textGap; place(label)
        if (!markerBefore) marker?.let { x += markerGap; place(it) }
    }
}

/** Native-style navigation affordance; independent of rectangular marker visibility/color/position. */
private class StyleFourArrowView(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = paint.strokeWidth / 2f
        path.reset()
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            path.moveTo(width - inset, inset); path.lineTo(inset, height / 2f); path.lineTo(width - inset, height - inset)
        } else {
            path.moveTo(inset, inset); path.lineTo(width - inset, height / 2f); path.lineTo(inset, height - inset)
        }
        canvas.drawPath(path, paint)
    }
}

/** Keep RAM/storage together when they fit; stack at large fonts or narrow widths. */
private class PairParameterRow(context: Context, private val left: View, private val right: View) : LinearLayout(context) {
    init { addView(left); addView(right) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        left.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightMeasureSpec)
        right.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightMeasureSpec)
        val gap = dp(styleFourSpacing(12f))
        val horizontal = left.measuredWidth + right.measuredWidth + gap <= width
        val leftWidth = left.measuredWidth
        val rightWidth = right.measuredWidth
        val extra = (width - leftWidth - rightWidth - gap).coerceAtLeast(0)
        orientation = if (horizontal) HORIZONTAL else VERTICAL
        left.layoutParams = LayoutParams(if (horizontal) leftWidth + extra / 2 else -1, -2)
        right.layoutParams = LayoutParams(if (horizontal) rightWidth + extra - extra / 2 else -1, -2).apply { marginStart = if (horizontal) gap else 0 }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

private fun View.dp(value: Int) = dp(value.toFloat())
private fun View.dp(value: Float) = (value * resources.displayMetrics.density).roundToInt()
private fun View.styleFourNight() = resources.configuration.uiMode and 0x30 == 0x20
private fun styleFourGravity(mode: Int) = when (mode) { 0 -> Gravity.START; 1 -> Gravity.CENTER_HORIZONTAL; else -> Gravity.END }
private fun styleFourInk(mode: Int, default: Int) = when (mode) { 1 -> 0xFF17181C.toInt(); 2 -> 0xFFF7F8FC.toInt(); else -> default }
