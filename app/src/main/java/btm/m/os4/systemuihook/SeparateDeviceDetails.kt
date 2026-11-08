// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * A Style 3-only presentation of the Settings device cards. The native RecyclerView is kept
 * alive and continues receiving asynchronous CPU/camera updates; this view only mirrors its
 * DeviceCardInfo objects and forwards their click listeners.
 */
internal class SeparateDeviceDetailsSession(
    private val host: ViewGroup,
    private val recycler: View,
    private val view: SeparateDeviceDetailsView,
    private val key: String,
) {
    private val originalVisibility = recycler.visibility
    private val background = host.background
    private val padding = intArrayOf(host.paddingLeft, host.paddingTop, host.paddingRight, host.paddingBottom)
    private val originalIndex = host.indexOfChild(recycler).coerceAtLeast(0)
    private var reportedFailure = false
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        enforce()
        true
    }

    init {
        host.addView(view, originalIndex.coerceAtMost(host.childCount),
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        runCatching { host.viewTreeObserver.addOnPreDrawListener(preDraw) }
        enforce()
    }

    fun refresh() { view.invalidateData(); enforce() }

    private fun enforce() {
        val ready = runCatching { view.refreshFrom(recycler) }.onFailure { error ->
            if (!reportedFailure) {
                reportedFailure = true
                ModuleLog.e("HyperChangerDeviceDetails", "Could not render separated device cards; retaining native grid", error)
            }
        }.getOrDefault(false)
        recycler.visibility = if (ready) View.GONE else originalVisibility
        view.visibility = if (ready) View.VISIBLE else View.GONE
        if (ready) {
            host.background = null
            if (host.paddingLeft != 0 || host.paddingTop != 0 || host.paddingRight != 0 || host.paddingBottom != 0) host.setPadding(0, 0, 0, 0)
        } else {
            host.background = background
            host.setPadding(padding[0], padding[1], padding[2], padding[3])
        }
    }

    fun remove() {
        runCatching { host.viewTreeObserver.removeOnPreDrawListener(preDraw) }
        (view.parent as? ViewGroup)?.removeView(view)
        recycler.visibility = originalVisibility
        host.background = background
        host.setPadding(padding[0], padding[1], padding[2], padding[3])
    }

    fun matches(candidateHost: ViewGroup, candidateRecycler: View, candidateKey: String): Boolean =
        host === candidateHost && recycler === candidateRecycler && view.parent === host && key == candidateKey
}

internal class SeparateDeviceDetailsView(
    context: Context,
    private val iconStyle: Int,
    private val processorIconStyle: Int,
    private val prepareCards: (Any) -> Unit,
    private val customIconSlot: String,
    private val controls: DeviceStyleCustomization,
) : LinearLayout(context) {
    private var lastSignature = ""
    private var lastAdapter: Any? = null
    private val buildVersion = systemProperty("ro.mi.os.version.incremental")

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, 0, 0, 0)
    }

    fun invalidateData() { lastSignature = "" }

    fun refreshFrom(recycler: View): Boolean {
        // Target RecyclerView belongs to the Settings ClassLoader, not the module's AndroidX.
        val adapter = recycler.javaClass.getMethod("getAdapter").invoke(recycler) ?: return false
        var cards = orderedCards(adapter)
        fun signature() = cards.joinToString("|") { "${System.identityHashCode(it.item)}:${it.index}:${it.value}:${it.title}:${it.first}:${it.second}:${it.iconRes}" } + resources.configuration.uiMode
        if (lastAdapter === adapter && signature() == lastSignature) return cards.isNotEmpty()
        prepareCards(adapter)
        cards = orderedCards(adapter)
        lastAdapter = adapter
        lastSignature = signature()
        removeAllViews()
        cards.forEachIndexed { index, card ->
            addView(createCard(card), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = dp(DEVICE_STYLE_CARD_GAP_DP)
            })
        }
        return cards.isNotEmpty()
    }

    // Preserve native extra information (for example a security-chip card) when supplied.
    private fun orderedCards(adapter: Any) = readCards(adapter).sortedBy { card ->
        ORDER.indexOf(card.index).takeIf { it >= 0 } ?: ORDER.size
    }

    private fun createCard(card: CardSnapshot): View {
        val dark = resources.configuration.uiMode and 0x30 == 0x20
        val surface = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (dark) 0x24FFFFFF else Color.WHITE)
            }
            isClickable = card.listener != null
            card.listener?.let { listener ->
                setOnClickListener {
                    // Native listeners inspect BaseDeviceCardItem.getKey(); pass that native type.
                    runCatching {
                        val native = card.item.javaClass.classLoader!!.loadClass("com.android.settings.device.BaseDeviceCardItem")
                            .getConstructor(Context::class.java).newInstance(context) as View
                        native.javaClass.getMethod("setKey", String::class.java).invoke(native, card.key)
                        listener.onClick(native)
                    }
                }
            }
            minimumHeight = dp(96f * CARD_HEIGHT_SCALE)
            setPadding(dp(20), dp(18f * CARD_HEIGHT_SCALE), dp(20), dp(18f * CARD_HEIGHT_SCALE))
        }
        val text = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(35), 0)
        }
        val brandHeader = if (card.index == 2) cameraBrandHeader(dark) else null
        if (brandHeader != null) text.addView(brandHeader, LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        val primary = TextView(context).apply {
            this.text = if (card.first.isNotBlank() && card.second.isNotBlank()) "${card.first}\n${card.second}" else card.value
            textSize = (if (brandHeader != null) 15f else 20f) * CARD_TEXT_SCALE
            includeFontPadding = false
            setTextColor(if (brandHeader != null) { if (dark) 0xB3FFFFFF.toInt() else 0x99000000.toInt() }
                else if (dark) Color.WHITE else 0xFF202124.toInt())
        }
        text.addView(primary, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        if (card.index == MODEL_INDEX) {
            if (buildVersion.isNotBlank()) text.addView(subtitle(buildVersion, dark))
        } else {
            val label = card.title
            if (label.isNotBlank()) text.addView(subtitle(label, dark))
        }
        surface.addView(text, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        iconDrawable(card)?.let { drawable ->
            ImageView(context).apply {
                setImageDrawable(drawable)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }.also { image ->
                surface.addView(image, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END))
                surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    // The title column is vertically centered, so align the icon's actual top
                    // inset to it rather than pinning the icon to the smaller surface padding.
                    image.translationY = (text.top - image.top).toFloat()
                }
            }
        }
        return surface
    }

    private fun cameraBrandHeader(dark: Boolean): View? {
        if (controls.cameraBrand == 0) return null
        if (controls.cameraBrand == 9) return controls.cameraBrandText.takeIf { it.isNotBlank() }?.let { value ->
            TextView(context).apply {
                text = value
                textSize = 20f * CARD_TEXT_SCALE
                includeFontPadding = false
                setTextColor(if (dark) Color.WHITE else 0xFF202124.toInt())
            }
        }
        val drawable = runCatching {
            if (controls.cameraBrand == 10) {
                val source = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_STYLE3_CAMERA_BRAND)
                if (source.exists) LogoDrawableLoader.load(context, source) else null
            } else moduleContext()?.getDrawable(BRAND_LOGOS[controls.cameraBrand] ?: return null)
        }.getOrNull() ?: return null
        if (dark && controls.cameraBrand in listOf(3, 4, 5, 6, 8)) drawable.mutate().setTint(Color.WHITE)
        val ratio = drawable.intrinsicWidth.coerceAtLeast(1).toFloat() / drawable.intrinsicHeight.coerceAtLeast(1)
        val widthDp = minOf(150f, 28f * ratio)
        val heightDp = minOf(28f, widthDp / ratio)
        return ImageView(context).apply {
            setImageDrawable(drawable)
            scaleType = ImageView.ScaleType.FIT_START
            minimumWidth = dp(widthDp.toInt().coerceAtLeast(1))
            minimumHeight = dp(heightDp.toInt().coerceAtLeast(1))
            adjustViewBounds = true
            maxWidth = minimumWidth
            maxHeight = minimumHeight
        }
    }

    private fun subtitle(value: String, dark: Boolean) = TextView(context).apply {
        text = value
        textSize = 14f
        includeFontPadding = false
        setTextColor(if (dark) 0xB3FFFFFF.toInt() else 0x99000000.toInt())
        setPadding(0, dp(7), 0, 0)
    }

    private fun iconDrawable(card: CardSnapshot): android.graphics.drawable.Drawable? = runCatching {
        val replacement = card.index == 0 && processorIconStyle >= 2
        val drawable = if (replacement) processorDrawable(processorIconStyle) else null
        val result = drawable ?: defaultIcon(card) ?: return@runCatching null
        val dark = resources.configuration.uiMode and 0x30 == 0x20
        result.mutate().apply {
            if (drawable != null || iconStyle == 0) {
                setTint(if (dark) 0x8AFFFFFF.toInt() else if (iconStyle == 1) 0x8A000000.toInt() else 0x99000000.toInt())
            } else if (dark) {
                // SRC_IN recolors without multiplying the source path alpha (phone includes 20%).
                setTint(Color.WHITE)
            }
        }
    }.getOrNull()

    private fun defaultIcon(card: CardSnapshot): android.graphics.drawable.Drawable? = if (iconStyle == 1) {
        COLOROS_ICONS[card.index]?.let { moduleContext()?.getDrawable(it) }
            ?: card.iconRes.takeIf { it != 0 }?.let(context::getDrawable)
    } else {
        val id = resources.getIdentifier(NATIVE_ICONS[card.index].orEmpty(), "drawable", context.packageName)
        if (id != 0) context.getDrawable(id) else card.iconRes.takeIf { it != 0 }?.let(context::getDrawable)
    }

    private fun moduleContext(): Context? = runCatching {
        context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
    }.getOrNull()

    private fun processorDrawable(choice: Int): android.graphics.drawable.Drawable? = runCatching {
        if (choice == 2) {
            val id = resources.getIdentifier("device_description_snapdragon_cpu", "drawable", context.packageName)
            return@runCatching if (id != 0) context.getDrawable(id) else null
        }
        if (choice == 9) {
            val source = SettingsAppearanceSources.query(context, customIconSlot)
            return@runCatching if (source.exists) LogoDrawableLoader.load(context, source) else null
        }
        val id = when (choice) {
            3 -> R.drawable.ic_cpu_vendor_xring
            4 -> R.drawable.ic_cpu_vendor_mediatek
            5 -> R.drawable.ic_cpu_vendor_apple
            6 -> R.drawable.ic_cpu_vendor_intel
            7 -> R.drawable.ic_cpu_vendor_nvidia
            8 -> R.drawable.ic_cpu_vendor_amd
            else -> return@runCatching null
        }
        moduleContext()?.getDrawable(id)
    }.getOrNull()

    private fun readCards(adapter: Any): List<CardSnapshot> {
        val raw = field(adapter, "cardInfos") ?: field(adapter, "mCardInfos") ?: return emptyList()
        val iterable = when (raw) {
            is Iterable<*> -> raw.asSequence()
            is Array<*> -> raw.asSequence()
            else -> return emptyList()
        }
        return iterable.mapNotNull { item ->
            item ?: return@mapNotNull null
            runCatching {
                CardSnapshot(
                    item = item,
                    index = call(item, "getIndex") as? Int ?: -1,
                    title = (call(item, "getTitle2") as? String).orEmpty().ifBlank { (call(item, "getTitle") as? String).orEmpty() },
                    value = call(item, "getValue") as? String ?: "",
                    first = call(item, "getFirstValue") as? String ?: "",
                    second = call(item, "getSecondValue") as? String ?: "",
                    key = call(item, "getKey") as? String ?: "",
                    iconRes = call(item, "getIconResId") as? Int ?: 0,
                    listener = call(item, "getListener") as? View.OnClickListener,
                )
            }.getOrNull()
        }.toList()
    }

    private fun field(target: Any, name: String): Any? = runCatching {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                return@runCatching field.get(target)
            }
            type = type.superclass
        }
        null
    }.getOrNull()

    private fun call(target: Any, name: String): Any? = runCatching {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val method = type.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
            if (method != null) {
                method.isAccessible = true
                return@runCatching method.invoke(target)
            }
            type = type.superclass
        }
        null
    }.getOrNull()

    private fun systemProperty(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
            .invoke(null, name, "") as String
    }.getOrDefault("")

    private fun dp(value: Int): Int = dp(value.toFloat())
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private data class CardSnapshot(
        val item: Any,
        val index: Int,
        val title: String,
        val value: String,
        val first: String,
        val second: String,
        val key: String,
        val iconRes: Int,
        val listener: View.OnClickListener?,
    )

    private companion object {
        const val CARD_HEIGHT_SCALE = .92f
        const val CARD_TEXT_SCALE = .87f
        const val MODEL_INDEX = 6
        val ORDER = listOf(MODEL_INDEX, 0, 5, 2, 1, 3, 4)
        val NATIVE_ICONS = mapOf(
            6 to "device_description_model", 0 to "device_description_cpu", 5 to "device_description_ram",
            1 to "device_description_battery", 3 to "device_description_screen", 4 to "device_description_resolution", 2 to "device_description_camera",
        )
        val COLOROS_ICONS = mapOf(
            MODEL_INDEX to R.drawable.device3_coloros_phone,
            0 to R.drawable.device3_coloros_cpu,
            5 to R.drawable.device3_coloros_ram,
            1 to R.drawable.device3_coloros_battery,
            3 to R.drawable.device3_coloros_screen,
            4 to R.drawable.device3_coloros_resolution,
            2 to R.drawable.device3_coloros_camera,
        )
        val BRAND_LOGOS = mapOf(
            1 to R.drawable.device3_brand_leica, 2 to R.drawable.device3_brand_zeiss,
            3 to R.drawable.device3_brand_hasselblad, 4 to R.drawable.device3_brand_fujifilm,
            5 to R.drawable.device3_brand_sony, 6 to R.drawable.device3_brand_nikon,
            7 to R.drawable.device3_brand_canon, 8 to R.drawable.device3_brand_panasonic,
        )
    }
}
