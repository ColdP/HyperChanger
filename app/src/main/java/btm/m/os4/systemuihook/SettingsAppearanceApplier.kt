// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

object SettingsAppearanceApplier {
    private val layers = Collections.synchronizedMap(WeakHashMap<Activity, LayerSession>())
    private val deviceLayers = Collections.synchronizedMap(WeakHashMap<Any, DeviceLayerSession>())
    private val originalTextColors = Collections.synchronizedMap(WeakHashMap<TextView, Int>())
    private val textModes = Collections.synchronizedMap(WeakHashMap<TextView, Int>())
    private val fallbackTextModes = Collections.synchronizedMap(WeakHashMap<TextView, Int>())
    private val logoSessions = Collections.synchronizedMap(WeakHashMap<Any, LogoSession>())
    private val cardSessions = Collections.synchronizedMap(WeakHashMap<Activity, CardAlphaSession>())
    private val tutorialCards = Collections.synchronizedMap(WeakHashMap<Any, TutorialCardSession>())
    private val deviceInfoCards = Collections.synchronizedMap(WeakHashMap<Any, DeviceInfoCardsSession>())
    private val harmonyCards = Collections.synchronizedMap(WeakHashMap<Any, HarmonyCardSession>())
    private val harmonyInfoCards = Collections.synchronizedMap(WeakHashMap<Any, HarmonyInfoCardsSession>())
    private val cardLikeViews = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val cardColorResources = Collections.synchronizedMap(WeakHashMap<Resources, MutableMap<Int, Boolean>>())
    private val internalTextColor = ThreadLocal<Boolean>()
    private val internalLogo = ThreadLocal<Boolean>()

    fun applyHome(activity: Activity) = applyActivity(activity, APPEARANCE_SLOT_HOME)
    fun shouldSuppressDeviceShader(fragment: Any): Boolean = runCatching {
        val context = fragment.javaClass.getMethod("getContext").invoke(fragment) as? android.content.Context ?: return false
        SettingsAppearanceSources.query(context, APPEARANCE_SLOT_DEVICE).exists
    }.getOrDefault(false)

    fun applyDevice(fragment: Any) {
        runCatching {
            val context = fragment.javaClass.getMethod("getContext").invoke(fragment) as? android.content.Context ?: return
            val source = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_DEVICE)
            fragmentActivity(fragment)?.let { applyCardOpacity(it, source.lightCardOpacity) }
            val old = deviceLayers[fragment]
            Log.i(TAG, "device apply class=${fragment.javaClass.name} exists=${source.exists} enabled=${source.enabled} mime=${source.mime} size=${source.size}")
            if (!source.exists) {
                old?.remove()
                deviceLayers.remove(fragment)
                // The custom My Device card is independent of the background
                // switch. Keep applying it when a custom device interface is
                // selected, even if the stock device background is disabled.
                if (old != null || source.deviceInterfaceStyle != DEVICE_INTERFACE_STYLE_SYSTEM || source.tutorialCardEnabled) {
                    fragmentActivity(fragment)?.let { applyFontMode(it, source.fontMode) }
                    applyTutorialCard(fragment)
                }
                return
            }
            if (old != null && old.view.sourceKey() == source.cacheKey() && old.view.parent === old.parent) {
                stopOriginalDeviceShader(fragment, old.systemBackground)
                old.systemBackground.visibility = View.INVISIBLE
                old.view.onHostResume()
                old.refresh(context)
                fragmentActivity(fragment)?.let { applyFontMode(it, source.fontMode) }
                applyTutorialCard(fragment)
                return
            }
            val background = findDeviceBackground(fragment, context) ?: run {
                Log.w(TAG, "device fragment has no mBgEffectView class=${fragment.javaClass.name}")
                applyTutorialCard(fragment)
                return
            }
            val parent = background.parent as? ViewGroup ?: return
            Log.i(TAG, "device target=${background.javaClass.name} parent=${parent.javaClass.name} index=${parent.indexOfChild(background)} visibility=${background.visibility}")
            old?.remove()
            stopOriginalDeviceShader(fragment, background)
            val media = SettingsBackgroundView(context, source)
            val index = parent.indexOfChild(background).coerceAtLeast(0)
            parent.addView(media, index + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val session = DeviceLayerSession(parent, background, background.visibility, media)
            background.visibility = View.INVISIBLE
            deviceLayers[fragment] = session
            Log.i(TAG, "device attached media=${media.javaClass.name} parent=${media.parent?.javaClass?.name}")
            fragmentActivity(fragment)?.let { applyFontMode(it, source.fontMode) }
            applyTutorialCard(fragment)
        }.onFailure { error -> Log.e(TAG, "device apply failed", error) }
    }

    fun applyDevice(activity: Activity) {
        runCatching {
            val source = SettingsAppearanceSources.query(activity, APPEARANCE_SLOT_DEVICE)
            applyCardOpacity(activity, source.lightCardOpacity)
            val old = deviceLayers[activity]
            if (!source.exists) {
                old?.remove()
                deviceLayers.remove(activity)
                applyFontMode(activity, source.fontMode)
                return
            }
            val id = activity.resources.getIdentifier("bgEffectView", "id", activity.packageName)
            val background = activity.findViewById<View>(id) ?: return
            val parent = background.parent as? ViewGroup ?: return
            Log.i(TAG, "device background hit activity=${activity.javaClass.name} id=$id source=${source.cacheKey()}")
            if (old != null && old.view.sourceKey() == source.cacheKey() && old.view.parent === old.parent) {
                old.view.onHostResume()
                old.refresh(activity)
                applyFontMode(activity, source.fontMode)
                return
            }
            old?.remove()
            background.setRenderEffect(null)
            val media = SettingsBackgroundView(activity, source)
            val index = parent.indexOfChild(background).coerceAtLeast(0)
            parent.addView(media, index + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val session = DeviceLayerSession(parent, background, background.visibility, media)
            background.visibility = View.INVISIBLE
            deviceLayers[activity] = session
            applyFontMode(activity, source.fontMode)
        }
    }

    fun stopDevice(fragment: Any?) {
        fragment?.let { deviceLayers[it]?.view?.onHostStop() }
    }
    fun destroyDevice(fragment: Any?) {
        if (fragment == null) return
        deviceLayers.remove(fragment)?.remove()
        tutorialCards.remove(fragment)?.remove()
        deviceInfoCards.remove(fragment)?.remove()
        harmonyCards.remove(fragment)?.remove()
        harmonyInfoCards.remove(fragment)?.remove()
    }
    fun stop(activity: Activity?) { activity?.let { layers[it]?.view?.onHostStop() } }
    fun destroy(activity: Activity?) {
        if (activity == null) return
        layers.remove(activity)?.remove()
        cardSessions.remove(activity)?.remove()
        restoreTextColors(activity.window?.decorView)
    }

    fun destroyLogo(fragment: Any?) {
        if (fragment == null) return
        logoSessions.remove(fragment)?.restore()
    }

    fun applyTutorialCard(fragment: Any) {
        runCatching {
            val context = fragment.javaClass.getMethod("getContext").invoke(fragment) as? android.content.Context ?: return
            val root = fragment.javaClass.getMethod("getView").invoke(fragment) as? View ?: return
            val targetId = context.resources.getIdentifier("miui_version_card_view", "id", context.packageName)
            // MiuiVersionCard is the scroll-aware host. The tutorial replaces its
            // layout content, rather than creating a root-level sibling, so its
            // translation remains coupled to the My Device scroll position.
            val target = root.findViewById<View>(targetId) as? FrameLayout ?: return
            val spacerId = context.resources.getIdentifier("version_card_click_view", "id", context.packageName)
            val spacer = root.findViewById<View>(spacerId)
            val animationLayoutId = context.resources.getIdentifier("version_layout", "id", context.packageName)
            // MiuiVersionCard animates this view on every scroll. Capture it
            // before adding our independent replacement card.
            val animationSource = target.findViewById<View>(animationLayoutId)
            val style = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_DEVICE_IMAGE).deviceInterfaceStyle
            when (style) {
                DEVICE_INTERFACE_STYLE_ONE -> {
                    harmonyCards.remove(fragment)?.remove()
                    harmonyInfoCards.remove(fragment)?.remove()
                    val source = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_DEVICE_IMAGE)
                    val logo = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_CUSTOM_DEVICE_LOGO)
                    applyDeviceInfoCards(fragment, context, root, source.copy(tutorialCardInfoCardsEnabled = true))
                    val old = tutorialCards[fragment]
                    val background = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_STYLE1_UPDATE_BACKGROUND)
                    val key = source.cacheKey() + logo.cacheKey() + background.cacheKey()
                    if (old != null && old.matches(target, spacer) && old.key == key) {
                        old.enforceTutorialLayout(context)
                        old.view.refresh(context, source.tutorialCardImageScale, source.tutorialCardAuthor, source.tutorialCardLogoScale, source.tutorialCardLogoVerticalOffset, source.tutorialCardImageLogoSpacing, source.tutorialCardTextSpacing, source.tutorialCardBackgroundBlur, source.tutorialCardBackgroundHorizontalOffset, source.tutorialCardBackgroundVerticalOffset, source.tutorialCardBackgroundScale)
                    } else {
                        old?.remove()
                        val card = TutorialDeviceCardView(
                            context,
                            source,
                            logo,
                            background,
                            root.findViewById<View>(context.resources.getIdentifier("miui_version_text", "id", context.packageName)),
                            spacer ?: target,
                        )
                        target.addView(card, tutorialCardLayoutParams(context))
                        val session = TutorialCardSession(target, spacer, spacer?.layoutParams, animationSource, card, key)
                        session.enforceTutorialLayout(context)
                        tutorialCards[fragment] = session
                        card.refresh(context, source.tutorialCardImageScale, source.tutorialCardAuthor, source.tutorialCardLogoScale, source.tutorialCardLogoVerticalOffset, source.tutorialCardImageLogoSpacing, source.tutorialCardTextSpacing, source.tutorialCardBackgroundBlur, source.tutorialCardBackgroundHorizontalOffset, source.tutorialCardBackgroundVerticalOffset, source.tutorialCardBackgroundScale)
                    }
                }
                DEVICE_INTERFACE_STYLE_TWO -> {
                    tutorialCards.remove(fragment)?.remove()
                    deviceInfoCards.remove(fragment)?.remove()
                    val image = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_STYLE2_DEVICE_IMAGE)
                    val logo = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_STYLE2_CUSTOM_DEVICE_LOGO)
                    val background = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_STYLE2_UPDATE_BACKGROUND)
                    val old = harmonyCards[fragment]
                    val key = image.cacheKey() + logo.cacheKey() + background.cacheKey()
                    val updateSource = root.findViewById<View>(context.resources.getIdentifier("miui_version_text", "id", context.packageName))
                    if (old != null && old.matches(target, spacer) && old.key == key) {
                        old.enforce(context)
                        old.view.refresh(image)
                    } else {
                        old?.remove()
                        val card = HarmonyUpdateCardView(context, logo, updateSource, background)
                        target.addView(card, harmonyCardLayoutParams(context))
                        val session = HarmonyCardSession(target, spacer, spacer?.layoutParams, animationSource, card, key)
                        session.enforce(context)
                        harmonyCards[fragment] = session
                        card.refresh(image)
                    }
                    applyHarmonyInfoCards(fragment, context, root, image)
                }
                else -> {
                    tutorialCards.remove(fragment)?.remove()
                    deviceInfoCards.remove(fragment)?.remove()
                    harmonyCards.remove(fragment)?.remove()
                    harmonyInfoCards.remove(fragment)?.remove()
                }
            }
        }.onFailure { error -> Log.e(TAG, "tutorial card apply failed", error) }
    }

    private fun applyHarmonyInfoCards(fragment: Any, context: android.content.Context, root: View, image: SettingsAppearanceSource) {
        val old = harmonyInfoCards[fragment]
        val nameId = context.resources.getIdentifier("device_name_card_view", "id", context.packageName)
        val storageId = context.resources.getIdentifier("device_memory_card_view", "id", context.packageName)
        val name = root.findViewById<View>(nameId) ?: return
        val storage = root.findViewById<View>(storageId) ?: return
        val parent = name.parent as? LinearLayout ?: return
        if (parent !== storage.parent) return
        if (old != null && old.matches(parent, name, storage, image.cacheKey())) {
            old.enforce()
            old.view.refresh(image.style2ImageScale)
            return
        }
        old?.remove()
        val row = HarmonyInfoCardsView(context, name, storage, image)
        val index = parent.indexOfChild(name).coerceAtLeast(0)
        // The stock parameter card adds a 12dp top margin. Reduce this
        // replacement row's contribution from 10dp to 5dp so the combined
        // gap is approximately 75% of the previous spacing.
        parent.addView(row, index, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tutorialDp(context, 182)).apply { bottomMargin = tutorialDp(context, 4.88f) })
        val session = HarmonyInfoCardsSession(parent, name, storage, row, image.cacheKey())
        session.enforce()
        harmonyInfoCards[fragment] = session
    }

    private fun applyDeviceInfoCards(
        fragment: Any,
        context: android.content.Context,
        root: View,
        source: SettingsAppearanceSource,
    ) {
        val old = deviceInfoCards[fragment]
        if (!source.tutorialCardInfoCardsEnabled) {
            old?.remove()
            deviceInfoCards.remove(fragment)
            return
        }
        val nameId = context.resources.getIdentifier("device_name_card_view", "id", context.packageName)
        val storageId = context.resources.getIdentifier("device_memory_card_view", "id", context.packageName)
        val name = root.findViewById<View>(nameId) ?: return
        val storage = root.findViewById<View>(storageId) ?: return
        val parent = name.parent as? LinearLayout ?: return
        if (parent !== storage.parent) return
        if (old != null && old.matches(parent, name, storage)) {
            old.enforce()
            return
        }
        old?.remove()
        val row = DeviceInfoCardsView(context, name, storage)
        val index = parent.indexOfChild(name).coerceAtLeast(0)
        parent.addView(row, index, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            tutorialDp(context, 148),
        ).apply {
            bottomMargin = tutorialDp(context, 6)
        })
        val session = DeviceInfoCardsSession(parent, name, storage, row)
        session.enforce()
        deviceInfoCards[fragment] = session
    }

    private fun tutorialCardLayoutParams(context: android.content.Context) = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        tutorialDp(context, 180),
        Gravity.TOP,
    ).apply {
        leftMargin = tutorialDp(context, 12)
        rightMargin = tutorialDp(context, 12)
        topMargin = tutorialDp(context, 18)
    }

    private fun compactVersionCardSpacer(params: ViewGroup.LayoutParams, height: Int): ViewGroup.LayoutParams = when (params) {
        is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(params).apply { this.height = height }
        is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(params).apply { this.height = height }
        is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(params).apply { this.height = height }
        else -> ViewGroup.LayoutParams(params).apply { this.height = height }
    }

    private fun tutorialDp(context: android.content.Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun tutorialDp(context: android.content.Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun fragmentActivity(fragment: Any): Activity? = runCatching {
        fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
    }.getOrNull()

    private fun findDeviceBackground(fragment: Any, context: android.content.Context): View? {
        runCatching {
            getObjectField(fragment, "mBgEffectView") as? View
        }.getOrNull()?.let { return it }
        val root = fragment.javaClass.getMethod("getView").invoke(fragment) as? View ?: return null
        val id = context.resources.getIdentifier("bgEffectView", "id", context.packageName)
        return root.findViewById(id)
    }

    fun applyLogo(fragment: Any) {
        runCatching {
            val context = fragment.javaClass.getMethod("getContext").invoke(fragment) as? android.content.Context ?: return
            if (isCustomDeviceCardEnabled(context)) {
                logoSessions.remove(fragment)?.restore()
                return
            }
            val source = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_LOGO)
            val root = fragment.javaClass.getMethod("getView").invoke(fragment) as? View ?: return
            val existing = logoSessions[fragment]
            val target = existing?.view ?: findLogoView(context, root) ?: return
            if (!source.exists || source.logoMode == LOGO_MODE_SYSTEM) {
                existing?.restore()
                logoSessions.remove(fragment)
                return
            }
            val session = existing ?: LogoSession(target).also { logoSessions[fragment] = it }
            val drawable = LogoDrawableLoader.load(context, source) ?: run {
                Log.e(TAG, "logo drawable load returned null mime=${source.mime} size=${source.size}")
                return
            }
            applyLogoDrawable(target, drawable)
            session.applyMaterialPolicy(source.logoMode == LOGO_MODE_NO_ADVANCED_MATERIAL)
        }.onFailure { error -> Log.e(TAG, "logo apply failed", error) }
    }

    fun applyLogo(activity: Activity) {
        runCatching {
            if (isCustomDeviceCardEnabled(activity)) {
                logoSessions.remove(activity)?.restore()
                return
            }
            val source = SettingsAppearanceSources.query(activity, APPEARANCE_SLOT_LOGO)
            val root = activity.window?.decorView ?: return
            val existing = logoSessions[activity]
            val target = existing?.view ?: findLogoView(activity, root) ?: return
            Log.i(TAG, "logo hit activity=${activity.javaClass.name} id=${target.id} source=${source.cacheKey()}")
            if (!source.exists || source.logoMode == LOGO_MODE_SYSTEM) {
                existing?.restore()
                logoSessions.remove(activity)
                return
            }
            val session = existing ?: LogoSession(target).also { logoSessions[activity] = it }
            val drawable = LogoDrawableLoader.load(activity, source) ?: return
            applyLogoDrawable(target, drawable)
            session.applyMaterialPolicy(source.logoMode == LOGO_MODE_NO_ADVANCED_MATERIAL)
        }
    }

    private fun sourceScale(context: android.content.Context): Float {
        return SettingsAppearanceSources.query(context, APPEARANCE_SLOT_LOGO).scale / 100f
    }

    fun logoReplacement(view: ImageView): Drawable? {
        if (internalLogo.get() == true || view.context.packageName != "com.android.settings") return null
        if (isCustomDeviceCardEnabled(view.context)) return null
        val idName = runCatching { view.resources.getResourceEntryName(view.id).lowercase() }.getOrDefault("")
        if (idName != "miui_logo_view" && !idName.contains("logo")) return null
        val source = SettingsAppearanceSources.query(view.context, APPEARANCE_SLOT_LOGO)
        if (!source.exists || source.logoMode == LOGO_MODE_SYSTEM) return null
        return LogoDrawableLoader.load(view.context, source)
    }

    fun logoResourceReplacement(context: android.content.Context, resources: Resources, resourceId: Int): Drawable? {
        return logoResourceReplacementInternal(context, resources, resourceId)
    }

    fun logoResourceReplacement(resources: Resources, resourceId: Int): Drawable? {
        val context = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        }.getOrNull() ?: return null
        return logoResourceReplacementInternal(context, resources, resourceId)
    }

    private fun logoResourceReplacementInternal(
        context: android.content.Context,
        resources: Resources,
        resourceId: Int,
    ): Drawable? {
        if (internalLogo.get() == true || context.packageName != "com.android.settings" || isCustomDeviceCardEnabled(context)) return null
        val packageName = runCatching { resources.getResourcePackageName(resourceId) }.getOrNull()
        if (packageName != null && packageName != "com.android.settings") return null
        val name = runCatching { resources.getResourceEntryName(resourceId).lowercase() }.getOrNull() ?: return null
        val source = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_LOGO)
        if (!source.exists || source.logoMode == LOGO_MODE_SYSTEM || !isLogoResource(name, source.logoMode)) return null
        val drawable = LogoDrawableLoader.load(context, source) ?: run {
            Log.e(TAG, "logo resource replacement load failed name=$name mime=${source.mime} size=${source.size}")
            return null
        }
        return LogoDrawableLoader.forBackground(drawable, source.scale / 100f)
    }

    private fun isLogoResource(name: String, mode: Int): Boolean {
        val xiaomi = name == "xiaomi_os_logo" || name == "xiaomi_os_logo_new" || name == "xiaomi_os_logo_new_lite"
        val provision = name == "provision_os_logo" || name == "provision_os_logo_big" ||
            name == "provision_os_logo_lite" || name == "provision_os_logo_small"
        return when (mode) {
            LOGO_MODE_NO_ADVANCED_MATERIAL -> xiaomi
            LOGO_MODE_KEEP_ADVANCED_MATERIAL -> provision || xiaomi
            else -> false
        }
    }

    private fun isCustomDeviceCardEnabled(context: android.content.Context): Boolean =
        SettingsAppearanceSources.query(context, APPEARANCE_SLOT_DEVICE_IMAGE).deviceInterfaceStyle != DEVICE_INTERFACE_STYLE_SYSTEM

    fun cardColorResourceReplacement(
        context: android.content.Context,
        resources: Resources,
        resourceId: Int,
        resolvedColor: Int,
    ): Int? {
        if (context.packageName != "com.android.settings" || !isLightMode(resources)) return null
        val opacity = SettingsAppearanceSources.query(context, APPEARANCE_SLOT_HOME)
            .lightCardOpacity.coerceIn(0, 100)
        if (opacity >= 100) return null
        val matches = cardColorResources[resources]?.get(resourceId) ?: run {
            val packageName = runCatching { resources.getResourcePackageName(resourceId) }.getOrNull()
            val name = runCatching { resources.getResourceEntryName(resourceId).lowercase() }.getOrNull()
            val result = packageName == null || packageName == "com.android.settings" && name in LIGHT_CARD_COLOR_RESOURCES
            cardColorResources.getOrPut(resources) { HashMap() }[resourceId] = result
            result
        }
        if (!matches) return null
        val alpha = opacity * 255 / 100
        val replacement = (alpha shl 24) or 0x00FFFFFF
        return replacement
    }

    fun cardColorStateListResourceReplacement(
        context: android.content.Context,
        resources: Resources,
        resourceId: Int,
        original: ColorStateList,
    ): ColorStateList? = cardColorResourceReplacement(
        context,
        resources,
        resourceId,
        original.defaultColor,
    )?.let(ColorStateList::valueOf)

    fun cardFinalColorReplacement(view: View, original: Int): Int? {
        if (view.context.packageName != "com.android.settings" || !isLightMode(view)) return null
        if (!isCardLike(view)) return null
        val opacity = lightCardOpacity(view.context)
        if (opacity >= 100) return null
        return cardColor(opacity)
    }

    fun cardFinalStateListReplacement(view: View, original: ColorStateList): ColorStateList? {
        return cardFinalColorReplacement(view, original.defaultColor)?.let(ColorStateList::valueOf)
    }

    fun cardFinalDrawableReplacement(view: View, drawable: Drawable) {
        if (view.context.packageName != "com.android.settings" || !isLightMode(view)) return
        if (!isCardLike(view)) return
        val opacity = lightCardOpacity(view.context)
        if (opacity >= 100) return

        // HyperCardView may recreate its drawable after the resource lookup. For
        // plain color drawables, replace the actual color; for vendor drawables,
        // retain their shape and apply the requested alpha at the final setter.
        runCatching {
            val colorDrawable = drawable as? ColorDrawable
            if (colorDrawable != null) colorDrawable.color = cardColor(opacity)
            else drawable.mutate().alpha = opacity * 255 / 100
        }
    }

    private fun lightCardOpacity(context: android.content.Context): Int =
        SettingsAppearanceSources.query(context, APPEARANCE_SLOT_HOME).lightCardOpacity.coerceIn(0, 100)

    private fun cardColor(opacity: Int): Int = (opacity * 255 / 100 shl 24) or 0x00FFFFFF

    private fun isLightMode(resources: Resources): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES

    private val LIGHT_CARD_COLOR_RESOURCES = setOf(
        "card_background_stroke_color",
        "card_style_summary_normal_color",
        "card_view_background_color",
        "cardview_light_background",
        "default_home_preference_item_background",
        "device_card_background",
        "list_card_background",
        "locale_cardview_background_color",
        "miuix_default_card_drawable_color_light",
        "miuix_default_color_container_list_light",
        "my_card_bg",
        "miuix_preference_card_group_background_light",
        "miuix_preference_card_group_background_color_light",
        "miuix_recyclerview_card_group_background_light",
        "wifi_cardview_background_color",
    )

    private fun harmonyCardLayoutParams(context: android.content.Context) = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        tutorialDp(context, 243),
        Gravity.TOP,
    ).apply {
        leftMargin = tutorialDp(context, 12)
        rightMargin = tutorialDp(context, 12)
        topMargin = tutorialDp(context, 18)
    }

    fun applyLogoDrawable(view: ImageView, drawable: Drawable) {
        internalLogo.set(true)
        try {
            view.visibility = View.VISIBLE
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.scaleX = 1f
            view.scaleY = 1f
            view.setImageDrawable(drawable)
            view.scaleX = SettingsAppearanceSources.query(view.context, APPEARANCE_SLOT_LOGO).scale / 100f
            view.scaleY = view.scaleX
        } finally {
            internalLogo.remove()
        }
    }

    fun cardBlurAlpha(view: View): Float? {
        if (view.context.packageName != "com.android.settings") return null
        val source = SettingsAppearanceSources.query(view.context, APPEARANCE_SLOT_DEVICE)
        if (!isLightMode(view) || source.lightCardOpacity >= 100 || !isCardLike(view)) return null
        return source.lightCardOpacity.coerceIn(0, 100) / 100f
    }

    private fun applyActivity(activity: Activity, slot: String) {
        runCatching {
            val source = SettingsAppearanceSources.query(activity, slot)
            applyCardOpacity(activity, source.lightCardOpacity)
            val old = layers[activity]
            if (!source.exists) {
                old?.remove()
                layers.remove(activity)
                applyFontMode(activity, source.fontMode)
                return
            }
            val content = activity.findViewById<View>(android.R.id.content) as? ViewGroup ?: return
            if (old != null && old.view.sourceKey() == source.cacheKey() && old.view.parent === content) {
                old.view.onHostResume()
                applyFontMode(activity, source.fontMode)
                return
            }
            old?.remove()
            val media = SettingsBackgroundView(activity, source)
            val session = LayerSession(content, media)
            session.clear(content)
            content.getChildAt(0)?.let { session.clear(it) }
            clearNamedSurfaces(activity, session)
            content.addView(media, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            session.attach(activity)
            layers[activity] = session
            applyFontMode(activity, source.fontMode)
        }
    }

    private fun applyFontMode(activity: Activity, mode: Int) {
        applyTextColor(activity.window?.decorView, mode)
    }

    /** Mirrors HyperBackground: remember each TextView's original color and reapply the selected mode. */
    private fun applyTextColor(view: View?, mode: Int) {
        if (view is TextView) {
            if (mode == 0) {
                textModes.remove(view)
                fallbackTextModes.remove(view)
                originalTextColors.remove(view)?.let { color ->
                    internalTextColor.set(true)
                    try { view.setTextColor(color) } finally { internalTextColor.remove() }
                }
            } else {
                if (!originalTextColors.containsKey(view)) originalTextColors[view] = view.currentTextColor
                textModes[view] = mode
                fallbackTextModes[view] = mode
                internalTextColor.set(true)
                try { view.setTextColor(forcedTextColor(mode)) } finally { internalTextColor.remove() }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) applyTextColor(view.getChildAt(i), mode)
    }

    private fun forcedTextColor(mode: Int): Int = if (mode == 1) Color.WHITE else Color.rgb(24, 24, 26)

    /** Called by the TextView hooks so MIUIX rebinding cannot undo a selected font mode. */
    fun overrideTextColor(view: TextView, argument: Any?, colorStateList: Boolean): Any? {
        if (internalTextColor.get() == true) return argument
        val mode = textModes[view] ?: fallbackTextModes[view] ?: fallbackFontMode(view).also {
            fallbackTextModes[view] = it
        }
        if (mode == 0) return argument
        val color = forcedTextColor(mode)
        return if (colorStateList) ColorStateList.valueOf(color) else color
    }

    private fun fallbackFontMode(view: TextView): Int = runCatching {
        val context = view.context
        val deviceId = context.resources.getIdentifier("bgEffectView", "id", context.packageName)
        val isDevicePage = deviceId != 0 && view.rootView.findViewById<View>(deviceId) != null
        SettingsAppearanceSources.query(context, if (isDevicePage) APPEARANCE_SLOT_DEVICE else APPEARANCE_SLOT_HOME).fontMode
    }.getOrDefault(0)

    private fun restoreTextColors(view: View?) {
        if (view is TextView) {
            textModes.remove(view)
            fallbackTextModes.remove(view)
            originalTextColors.remove(view)?.let { color ->
                internalTextColor.set(true)
                try { view.setTextColor(color) } finally { internalTextColor.remove() }
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) restoreTextColors(view.getChildAt(i))
    }

    private fun applyCardOpacity(activity: Activity, opacity: Int) {
        val session = cardSessions[activity] ?: CardAlphaSession(activity).also { cardSessions[activity] = it }
        session.apply(opacity.coerceIn(0, 100))
    }

    private fun clearNamedSurfaces(activity: Activity, session: LayerSession) {
        listOf(
            "nestedheaderlayout", "scroll_headers", "main_content", "prefs_container",
            "preference_recyclerview", "recycler_view", "content", "content_view",
            "content_wrapper", "action_bar_activity_content", "area_content", "auto_content",
        ).forEach { name ->
            val id = activity.resources.getIdentifier(name, "id", activity.packageName)
            if (id != 0) activity.findViewById<View>(id)?.let(session::clear)
        }
    }

    private fun stopOriginalDeviceShader(fragment: Any, background: View) {
        runCatching {
            val controller = getObjectField(fragment, "mBgEffectController")
            controller?.javaClass?.methods?.firstOrNull { it.name == "stop" && it.parameterCount == 0 }?.invoke(controller)
        }
        runCatching { background.setRenderEffect(null) }
    }

    private fun getObjectField(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null && type != Any::class.java) {
            runCatching {
                return type!!.getDeclaredField(name).apply { isAccessible = true }.get(instance)
            }
            type = type.superclass
        }
        return null
    }

    private fun findLogoView(context: android.content.Context, root: View): ImageView? {
        val exactId = context.resources.getIdentifier("miui_logo_view", "id", context.packageName)
        if (exactId != 0) root.findViewById<ImageView>(exactId)?.let { return it }
        var fallback: ImageView? = null
        fun visit(view: View) {
            if (view is ImageView) {
                val id = runCatching { context.resources.getResourceEntryName(view.id).lowercase() }.getOrDefault("")
                val score = when {
                    id == "miui_logo_view" -> 100
                    id.contains("logo") -> 80
                    id.contains("device") && id.contains("image") -> 50
                    else -> 0
                }
                if (score > 0 && (fallback == null || score > logoScore(context, fallback!!))) fallback = view
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i))
        }
        visit(root)
        return fallback
    }

    private fun logoScore(context: android.content.Context, view: ImageView): Int {
        val id = runCatching { context.resources.getResourceEntryName(view.id).lowercase() }.getOrDefault("")
        return when {
            id == "miui_logo_view" -> 100
            id.contains("logo") -> 80
            id.contains("device") && id.contains("image") -> 50
            else -> 0
        }
    }

    private class LogoSession(val view: ImageView) {
        private val originalDrawable = view.drawable
        private val originalScaleX = view.scaleX
        private val originalScaleY = view.scaleY
        private val materialViews = ArrayList<Pair<View, Drawable?>>()
        private var materialCleared = false

        fun applyMaterialPolicy(removeMaterial: Boolean) {
            if (!removeMaterial) {
                restoreMaterial()
                return
            }
            if (materialCleared) return
            var current: View? = view
            repeat(4) {
                val target = current ?: return@repeat
                if (target !== view) {
                    materialViews += target to target.background
                    target.background = null
                }
                current = (target.parent as? View)
            }
            materialCleared = true
        }

        private fun restoreMaterial() {
            materialViews.asReversed().forEach { (target, background) -> target.background = background }
            materialViews.clear()
            materialCleared = false
        }

        fun restore() {
            view.setImageDrawable(originalDrawable)
            view.scaleX = originalScaleX
            view.scaleY = originalScaleY
            restoreMaterial()
        }
    }

    private class TutorialCardSession(
        private val host: FrameLayout,
        private val spacer: View?,
        private val spacerLayoutParams: ViewGroup.LayoutParams?,
        private val animationSource: View?,
        val view: TutorialDeviceCardView,
        val key: String,
    ) {
        private val originalHostBackground = host.background
        private val originalChildVisibility = List(host.childCount) { index ->
            host.getChildAt(index)
        }.filter { it !== view }.map { it to it.visibility }
        private val animationSync = ViewTreeObserver.OnPreDrawListener {
            syncCardAnimation()
            true
        }
        private var animationListenerAttached = false

        fun matches(host: FrameLayout, spacer: View?): Boolean =
            this.host === host && this.spacer === spacer && view.parent === host

        fun enforceTutorialLayout(context: android.content.Context) {
            // Keep MiuiVersionCard itself alive: Settings moves it while scrolling.
            // Only its stock children are hidden, so the imported logo and card are
            // the sole visible content while all presenter references stay valid.
            host.background = null
            view.visibility = View.VISIBLE
            originalChildVisibility.forEach { (child, _) -> child.visibility = View.INVISIBLE }
            attachAnimationSync()
            syncCardAnimation()
            val original = spacerLayoutParams ?: return
            spacer?.layoutParams = SettingsAppearanceApplier.compactVersionCardSpacer(
                original,
                SettingsAppearanceApplier.tutorialDp(context, 211),
            )
        }

        fun remove() {
            if (animationListenerAttached) {
                runCatching { host.viewTreeObserver.removeOnPreDrawListener(animationSync) }
                animationListenerAttached = false
            }
            host.removeView(view)
            host.background = originalHostBackground
            originalChildVisibility.forEach { (child, visibility) -> child.visibility = visibility }
            spacer?.let { view -> spacerLayoutParams?.let { view.layoutParams = it } }
        }

        private fun attachAnimationSync() {
            if (animationListenerAttached) return
            host.viewTreeObserver.addOnPreDrawListener(animationSync)
            animationListenerAttached = true
        }

        private fun syncCardAnimation() {
            val source = animationSource ?: return
            view.translationX = source.translationX
            view.translationY = source.translationY
            view.scaleX = source.scaleX
            view.scaleY = source.scaleY
            view.alpha = source.alpha
        }
    }

    private class HarmonyCardSession(
        private val host: FrameLayout,
        private val spacer: View?,
        private val spacerLayoutParams: ViewGroup.LayoutParams?,
        private val animationSource: View?,
        val view: HarmonyUpdateCardView,
        val key: String,
    ) {
        private val originalHostBackground = host.background
        private val originalChildVisibility = List(host.childCount) { index -> host.getChildAt(index) }
            .filter { it !== view }
            .map { it to it.visibility }
        private val animationSync = ViewTreeObserver.OnPreDrawListener { sync(); true }
        private var attached = false

        fun matches(host: FrameLayout, spacer: View?): Boolean = this.host === host && this.spacer === spacer && view.parent === host

        fun enforce(context: android.content.Context) {
            host.background = null
            view.visibility = View.VISIBLE
            view.attach()
            originalChildVisibility.forEach { (child, _) -> child.visibility = View.INVISIBLE }
            if (!attached) {
                host.viewTreeObserver.addOnPreDrawListener(animationSync)
                attached = true
            }
            sync()
            val original = spacerLayoutParams ?: return
            spacer?.layoutParams = SettingsAppearanceApplier.compactVersionCardSpacer(original, SettingsAppearanceApplier.tutorialDp(context, 274))
        }

        fun remove() {
            view.dispose()
            if (attached) runCatching { host.viewTreeObserver.removeOnPreDrawListener(animationSync) }
            attached = false
            host.removeView(view)
            host.background = originalHostBackground
            originalChildVisibility.forEach { (child, visibility) -> child.visibility = visibility }
            spacer?.let { spacerLayoutParams?.let { params -> it.layoutParams = params } }
        }

        private fun sync() {
            val source = animationSource ?: return
            view.translationX = source.translationX
            view.translationY = source.translationY
            view.scaleX = source.scaleX
            view.scaleY = source.scaleY
            view.alpha = source.alpha
        }
    }

    private class DeviceInfoCardsSession(
        private val parent: LinearLayout,
        private val name: View,
        private val storage: View,
        private val view: DeviceInfoCardsView,
    ) {
        private val originalBackground = parent.background
        private val originalChildVisibility = List(parent.childCount) { index ->
            parent.getChildAt(index)
        }.filter { child -> child !== view }.map { child -> child to child.visibility }

        fun matches(parent: LinearLayout, name: View, storage: View): Boolean =
            this.parent === parent && this.name === name && this.storage === storage && view.parent === parent

        fun enforce() {
            // The stock container also owns the OS/guarantee rows and a shared
            // background. Hide all of it while retaining the name/storage views
            // as live data sources for the replacement cards.
            parent.background = null
            originalChildVisibility.forEach { (child, _) -> child.visibility = View.GONE }
            view.attach()
        }

        fun remove() {
            view.dispose()
            (view.parent as? ViewGroup)?.removeView(view)
            parent.background = originalBackground
            originalChildVisibility.forEach { (child, visibility) -> child.visibility = visibility }
        }
    }

    private class HarmonyInfoCardsSession(
        private val parent: LinearLayout,
        private val name: View,
        private val storage: View,
        val view: HarmonyInfoCardsView,
        private val key: String,
    ) {
        private val scrollbarStates = HashMap<View, Pair<Boolean, Boolean>>()
        private val originalBackground = parent.background
        private val originalChildVisibility = List(parent.childCount) { index -> parent.getChildAt(index) }
            .filter { it !== view }
            .map { it to it.visibility }

        init {
            // The replacement row lives inside the page's NestedScrollView.
            // Hide scrollbars on that ancestor as well as on the original
            // card subtree; otherwise the first layout can flash the stock
            // scrollbar when the row is inserted.
            captureAndHideScrollbars(parent)
            var ancestor = parent.parent
            while (ancestor is View) {
                captureAndHideScrollbars(ancestor)
                ancestor = ancestor.parent
            }
        }

        fun matches(parent: LinearLayout, name: View, storage: View, key: String): Boolean =
            this.parent === parent && this.name === name && this.storage === storage && this.key == key && view.parent === parent

        fun enforce() {
            parent.background = null
            originalChildVisibility.forEach { (child, _) -> child.visibility = View.GONE }
            hideScrollbars(parent)
            view.attach()
        }

        fun remove() {
            view.dispose()
            (view.parent as? ViewGroup)?.removeView(view)
            parent.background = originalBackground
            originalChildVisibility.forEach { (child, visibility) -> child.visibility = visibility }
            scrollbarStates.forEach { (child, state) ->
                child.isVerticalScrollBarEnabled = state.first
                child.isHorizontalScrollBarEnabled = state.second
            }
        }

        private fun captureAndHideScrollbars(target: View) {
            scrollbarStates.putIfAbsent(target, target.isVerticalScrollBarEnabled to target.isHorizontalScrollBarEnabled)
            hideScrollbars(target)
            if (target is ViewGroup) {
                for (index in 0 until target.childCount) captureAndHideScrollbars(target.getChildAt(index))
            }
        }

        private fun hideScrollbars(target: View) {
            target.isVerticalScrollBarEnabled = false
            target.isHorizontalScrollBarEnabled = false
            if (target is ViewGroup) {
                for (index in 0 until target.childCount) hideScrollbars(target.getChildAt(index))
            }
        }
    }

    private class LayerSession(val parent: ViewGroup, val view: SettingsBackgroundView) {
        private val backgrounds = ArrayList<Pair<View, Drawable?>>()
        private var observer: ViewTreeObserver.OnGlobalLayoutListener? = null
        private var lastWidth = -1
        private var lastHeight = -1
        private var lastChildCount = -1
        fun clear(target: View) {
            if (target === view || backgrounds.any { it.first === target }) return
            backgrounds += target to target.background
            target.background = null
        }
        fun remove() {
            observer?.let { listener ->
                runCatching { parent.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
            }
            observer = null
            (view.parent as? ViewGroup)?.removeView(view)
            view.dispose()
            backgrounds.asReversed().forEach { (target, background) -> target.background = background }
            backgrounds.clear()
        }

        fun attach(activity: Activity) {
            refresh(activity)
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                val root = parent.getChildAt(0)
                if (root == null || root.width != lastWidth || root.height != lastHeight || parent.childCount != lastChildCount) {
                    refresh(activity)
                }
            }
            observer = listener
            runCatching { parent.viewTreeObserver.addOnGlobalLayoutListener(listener) }
        }

        fun refresh(activity: Activity) {
            clearNamedSurfaces(activity, this)
            val root = parent.getChildAt(0) ?: return
            lastWidth = root.width
            lastHeight = root.height
            lastChildCount = parent.childCount
            clearPageSurfaces(activity, root, root)
        }

        private fun clearPageSurfaces(activity: Activity, view: View, root: View) {
            if (view === this.view || view.visibility != View.VISIBLE) return
            if (isPageSurface(activity, view, root)) clear(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    clearPageSurfaces(activity, view.getChildAt(index), root)
                }
            }
        }

        private fun isPageSurface(activity: Activity, view: View, root: View): Boolean {
            if (view === root) return true
            if (view.background == null) return false
            val metrics = activity.resources.displayMetrics
            val rootWidth = maxOf(root.width, metrics.widthPixels)
            val rootHeight = maxOf(root.height, metrics.heightPixels)
            val large = view.width >= rootWidth * 0.72f && view.height >= rootHeight * 0.32f
            if (!large) return false
            val idName = runCatching {
                if (view.id == View.NO_ID || view.id == 0) ""
                else activity.resources.getResourceEntryName(view.id).lowercase()
            }.getOrDefault("")
            val cls = view.javaClass.name.lowercase()
            if (containsAny(idName, "card", "button", "switch", "checkbox", "icon", "avatar", "image", "banner", "header_card")) return false
            if (containsAny(cls, "cardview", "button", "switch", "checkbox", "imageview")) return false
            if (containsAny(idName, "content", "container", "recycler", "list", "prefs", "preference", "nestedheader", "scroll", "fragment", "root", "main", "area", "panel")) return true
            if (containsAny(cls, "recyclerview", "nestedscrollview", "scrollview", "listview", "coordinatorlayout", "fragmentcontainerview", "viewpager")) return true
            return view is ViewGroup && view.width >= rootWidth * 0.90f && view.height >= rootHeight * 0.62f
        }

        private fun containsAny(value: String, vararg needles: String): Boolean {
            return needles.any(value::contains)
        }
    }

    private class CardAlphaSession(private val activity: Activity) {
        private data class Entry(val view: View, val drawable: Drawable, val alpha: Int)
        private val entries = ArrayList<Entry>()
        private val root: View = activity.window?.decorView ?: returnRoot(activity)
        private val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (root.width != lastWidth || root.height != lastHeight || childCount(root) != lastChildCount) {
                refresh(currentOpacity)
            }
        }
        private var currentOpacity = 100
        private var lastWidth = -1
        private var lastHeight = -1
        private var lastChildCount = -1
        private var initialized = false

        init { runCatching { root.viewTreeObserver.addOnGlobalLayoutListener(listener) } }

        fun apply(opacity: Int) {
            currentOpacity = opacity
            if (initialized && (!isLightMode() || opacity >= 100) && entries.isEmpty()) return
            if (initialized && opacity == currentOpacity && isLightMode() && opacity < 100) return
            if (!isLightMode() || opacity >= 100) {
                restore()
                initialized = true
                return
            }
            refresh(opacity)
            initialized = true
        }

        private fun refresh(opacity: Int) {
            if (!isLightMode() || opacity >= 100) { restore(); return }
            lastWidth = root.width
            lastHeight = root.height
            lastChildCount = childCount(root)
            visit(root, opacity)
        }

        private fun childCount(view: View): Int {
            if (view !is ViewGroup) return 1
            var count = 1
            for (index in 0 until view.childCount) count += childCount(view.getChildAt(index))
            return count
        }

        private fun visit(view: View, opacity: Int) {
            if (isCard(view)) {
                val drawable = view.background
                if (drawable != null && entries.none { it.view === view && it.drawable === drawable }) {
                    entries += Entry(view, drawable, drawable.alpha)
                }
                drawable?.mutate()?.alpha = opacity * 255 / 100
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), opacity)
        }

        private fun isCard(view: View): Boolean {
            if (view.width <= 0 || view.height <= 0) return false
            val name = runCatching {
                if (view.id == View.NO_ID || view.id == 0) ""
                else activity.resources.getResourceEntryName(view.id).lowercase()
            }.getOrDefault("")
            val cls = view.javaClass.name.lowercase()
            return (name.contains("card") || cls.contains("card")) &&
                !name.contains("icon") && !name.contains("button") &&
                view.width >= activity.resources.displayMetrics.widthPixels * 0.55f
        }

        private fun isLightMode(): Boolean =
            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES

        private fun restore() {
            entries.forEach { entry -> runCatching { entry.drawable.alpha = entry.alpha } }
            entries.clear()
        }

        fun remove() {
            runCatching { root.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
            restore()
        }

        private fun returnRoot(activity: Activity): View = activity.window?.decorView ?: View(activity)
    }

    private fun isLightMode(view: View): Boolean =
        view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES

    private fun isCardLike(view: View): Boolean {
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return false
        if (cardLikeViews[view] == true) return true
        val name = runCatching {
            if (view.id == View.NO_ID || view.id == 0) "" else view.resources.getResourceEntryName(view.id).lowercase()
        }.getOrDefault("")
        val cls = view.javaClass.name.lowercase()
        val result = (name.contains("card") || cls.contains("card")) &&
            !name.contains("icon") && !name.contains("button")
        if (result) cardLikeViews[view] = true
        return result
    }

    private class DeviceLayerSession(
        val parent: ViewGroup,
        val systemBackground: View,
        private val originalVisibility: Int,
        val view: SettingsBackgroundView,
    ) {
        fun refresh(context: android.content.Context) {
            if (view.parent === parent) view.onHostResume()
        }
        fun remove() {
            (view.parent as? ViewGroup)?.removeView(view)
            view.dispose()
            systemBackground.visibility = originalVisibility
        }
    }

    private const val TAG = "HyperChangerSettingsAppearance"
}
