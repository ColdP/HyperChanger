// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Parcelable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/** Geometry and the optional bottom button only; shared sidebar glass keeps its owner. */
internal class SidebarShapeHooks(
    private val module: XposedModule,
    private val hookDeoptimized: (Executable) -> HookBuilder,
    private val logTag: String,
) {
    private var settings = SidebarShapeSettings()
    private val cards = Collections.synchronizedMap(WeakHashMap<View, CardState>())
    private val capsules = Collections.synchronizedMap(WeakHashMap<ImageView, Boolean>())
    private class CardState(var nativeRadius: Float) {
        var background: Drawable? = null
        var radius = Float.NaN
        var provider: ViewOutlineProvider? = null
    }

    fun install(loader: ClassLoader, preferences: SharedPreferences) {
        settings = readSidebarShapeSettings(preferences)
        if (!settings.radiusEnabled && !settings.capsuleButton) return
        attempt("native card outlines") {
            hook(loader.loadClass("com.miui.common.utils.n").getDeclaredMethod("a", View::class.java,
                Float::class.javaPrimitiveType), "native-radius") { chain ->
                val view = chain.getArg(0) as View
                val result = chain.proceed()
                if (isCard(view)) {
                    register(view).nativeRadius = chain.getArg(1) as Float
                    applyRadius(view)
                }
                result
            }
        }
        if (settings.radiusEnabled) {
            installToolCard(loader, "gb.p", "t", "b")
            installToolCard(loader, "s8.i", "E", "d")
            attempt("all-apps card outline") {
                val material = loader.loadClass("com.miui.dock.allapps.f0")
                listOf(material.getDeclaredMethod("c", ViewGroup::class.java, Context::class.java),
                    material.getDeclaredMethod("e", View::class.java, android.content.res.Resources::class.java)).forEach { method ->
                    hook(method, "all-apps:${method.name}") { chain ->
                        val view = chain.getArg(0) as View
                        if (isCard(view)) register(view)
                        val result = chain.proceed()
                        if (isCard(view)) applyRadius(view)
                        result
                    }
                }
            }
            attempt("brightness card outline") {
                hook(loader.loadClass(NEWBOX + "w1").getDeclaredMethod("e"), "brightness") { chain ->
                    val result = chain.proceed()
                    val view = chain.thisObject as View
                    register(view)
                    applyRadius(view)
                    result
                }
            }
            attempt("final scaled card outlines") {
                val turbo = loader.loadClass(NEWBOX + "TurboLayout")
                hook(turbo.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType),
                    "measured-cards") { chain ->
                    val result = chain.proceed()
                    // The existing toolbox owner has now resolved the real transform scale.
                    synchronized(cards) { cards.keys.toList() }.forEach(::applyRadius)
                    result
                }
                val getDock = turbo.getDeclaredMethod("getDockLayout")
                hook(turbo.getDeclaredMethod("S"), "dock-material-refresh") { chain ->
                    val result = chain.proceed()
                    (getDock.invoke(chain.thisObject) as? View)?.let { register(it); applyRadius(it) }
                    result
                }
            }
        }
        if (settings.capsuleButton) installCapsule(loader)
    }

    private fun isCard(view: View) = view.javaClass.name in CARD_TYPES || cards.containsKey(view)

    private fun register(view: View): CardState = synchronized(cards) {
        cards.getOrPut(view) {
            val resource = view.resources.getIdentifier("global_dock_bg_radius", "dimen", "com.miui.securitycenter")
            CardState(if (resource == 0) 24f * view.resources.displayMetrics.density else view.resources.getDimension(resource))
        }
    }

    private fun installToolCard(loader: ClassLoader, typeName: String, methodName: String, fieldName: String) =
        attempt("tool card outline: $typeName") {
            val type = loader.loadClass(typeName)
            val field = type.getDeclaredField(fieldName).apply { isAccessible = true }
            hook(type.getDeclaredMethod(methodName), "tool:$typeName") { chain ->
                (field.get(chain.thisObject) as? View)?.let(::register)
                val result = chain.proceed()
                (field.get(chain.thisObject) as? View)?.let { register(it); applyRadius(it) }
                result
            }
        }

    private fun effectiveRadius(view: View, width: Int, height: Int): Float {
        var scale = 1f
        var parent = view.parent
        while (parent is View) {
            if (parent is SidebarToolboxScaleLayout) {
                scale = parent.fit.scale.takeIf { it > 0f } ?: 1f
                break
            }
            parent = parent.parent
        }
        val baseline = register(view).nativeRadius
        val requested = sidebarAdjustedRadius(baseline, settings.radius, view.resources.displayMetrics.density)
        return sidebarCardRadius(requested / scale, width, height)
    }

    private fun applyRadius(view: View) {
        if (!settings.radiusEnabled || settings.radius == 0f) return
        val state = register(view)
        val provider = state.provider ?: object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, effectiveRadius(view, view.width, view.height))
            }
        }.also { state.provider = it }
        if (view.outlineProvider !== provider) view.outlineProvider = provider
        view.clipToOutline = true
        val radius = effectiveRadius(view, view.measuredWidth, view.measuredHeight)
        if (state.background !== view.background || state.radius != radius) {
            val background = view.background?.mutate()
            if (view.background !== background) view.background = background
            roundBackground(background, radius)
            state.background = background
            state.radius = radius
            view.invalidateOutline()
        }
    }

    private fun roundBackground(drawable: Drawable?, radius: Float) {
        when (drawable) {
            is GradientDrawable -> drawable.cornerRadius = radius
            is LayerDrawable -> for (index in 0 until drawable.numberOfLayers) roundBackground(drawable.getDrawable(index), radius)
            is StateListDrawable -> for (index in 0 until drawable.stateCount) roundBackground(drawable.getStateDrawable(index), radius)
        }
    }

    private fun installCapsule(loader: ClassLoader) = attempt("capsule button") {
        val dock = loader.loadClass(NEWBOX + "p0")
        val bottom = dock.getDeclaredField("l").apply { isAccessible = true }
        val recycler = dock.getDeclaredField("i").apply { isAccessible = true }
        val left = dock.getDeclaredField("b").apply { isAccessible = true }
        fun style(owner: Any, refresh: Boolean) {
            val button = bottom.get(owner) as? ImageView ?: return
            val first = !capsules.containsKey(button)
            if (button.drawable !is SidebarCapsuleDrawable) {
                val text = button.resources.getIdentifier("all_app_name", "color", "com.miui.securitycenter")
                val dark = button.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                val color = if (text == 0) if (dark) Color.WHITE else Color.BLACK else button.context.getColor(text)
                button.setImageDrawable(SidebarCapsuleDrawable(color, button.resources.displayMetrics.density))
                button.imageTintList = null
                button.clearColorFilter()
                button.scaleType = ImageView.ScaleType.FIT_XY
                button.setPadding(0, 0, 0, 0)
                button.background = ColorDrawable(Color.TRANSPARENT)
            }
            (button.drawable as SidebarCapsuleDrawable).arrowLeft = left.getBoolean(owner)
            button.outlineProvider = CAPSULE_OUTLINE
            button.clipToOutline = true
            if (first || refresh) {
                capsules[button] = true
                attempt("native capsule material and bloom") { applyCapsuleMaterial(button, loader) }
            }
        }
        listOf("s", "M", "L").forEach { name ->
            hook(dock.getDeclaredMethod(name), "capsule:$name") { chain ->
                val result = chain.proceed()
                attempt("capsule style refresh") { style(chain.thisObject!!, true) }
                result
            }
        }
        // p0 inherits this implementation. Limit the framework hook to this exact host class.
        hook(FrameLayout::class.java.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType), "capsule-measure") { chain ->
            if (chain.thisObject?.javaClass != dock) return@hook chain.proceed()
            val owner = chain.thisObject as FrameLayout
            attempt("capsule measured bounds") {
                style(owner, false)
                val button = bottom.get(owner) as? ImageView ?: return@attempt
                val list = recycler.get(owner) as? View ?: return@attempt
                val widthSpec = chain.getArg(0) as Int
                val heightSpec = chain.getArg(1) as Int
                val width = View.MeasureSpec.getSize(widthSpec).takeIf { it > 0 } ?: owner.measuredWidth
                val height = if (View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.EXACTLY)
                    View.MeasureSpec.getSize(heightSpec) else owner.layoutParams?.height?.takeIf { it > 0 }
                        ?: owner.measuredHeight.takeIf { it > 0 } ?: View.MeasureSpec.getSize(heightSpec)
                val baseline = register(owner).nativeRadius
                val radius = if (settings.radiusEnabled) sidebarAdjustedRadius(baseline, settings.radius, owner.resources.displayMetrics.density)
                    else baseline
                val bounds = sidebarCapsuleBounds(width, height, radius, owner.resources.displayMetrics.density)
                (button.layoutParams as? FrameLayout.LayoutParams)?.apply {
                    this.width = bounds.width
                    this.height = bounds.height
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    leftMargin = 0
                    rightMargin = 0
                    bottomMargin = bounds.bottomInset
                }
                (list.layoutParams as? FrameLayout.LayoutParams)?.bottomMargin = bounds.listBottomSpace
            }
            chain.proceed()
        }
    }

    private fun applyCapsuleMaterial(view: View, loader: ClassLoader) {
        loader.loadClass("com.miui.common.utils.n").getDeclaredMethod("k", View::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, view, true)
        View::class.java.getMethod("clearMiBackgroundBlendColor").invoke(view)
        val bloom = loader.loadClass("miuix.theme.token.b").getField("c").get(null) as FloatArray
        if (settings.advancedMaterial) applyCapsuleAdvancedMaterial(view, loader, bloom)
        else applyCapsuleGlass(view, loader, bloom)
    }

    private fun applyCapsuleAdvancedMaterial(view: View, loader: ClassLoader, bloom: FloatArray) {
        // Native FloatingEffectHelper uses the O/P advanced tokens with blur and bloom.
        // p0.L uses this exact stronger bloom token. This path never enables MiGlass.
        val dark = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val colorType = loader.loadClass("miuix.theme.token.c")
        val colors = colorType.getField(if (dark) "P" else "O").get(null)
        val builderType = loader.loadClass("miuix.theme.token.MaterialToken\$b")
        val builder = builderType.getConstructor(Int::class.javaPrimitiveType).newInstance(30)
        builderType.getDeclaredMethod("f", colorType).invoke(builder, colors)
        builderType.getDeclaredMethod("j", Int::class.javaPrimitiveType).invoke(builder, 8)
        builderType.getDeclaredMethod("b", FloatArray::class.java).invoke(builder, bloom.copyOf())
        val material = builderType.getDeclaredMethod("a").invoke(builder) ?: return
        val adapterType = loader.loadClass("vq.j")
        val adapter = adapterType.getDeclaredMethod("a", Parcelable::class.java).invoke(null, material) ?: return
        val blur = checkNotNull(adapterType.getDeclaredMethod("c").invoke(adapter))
        val blend = checkNotNull(adapterType.getDeclaredMethod("d").invoke(adapter))
        val stroke = checkNotNull(adapterType.getDeclaredMethod("b").invoke(adapter))
        val blendType = blend.javaClass
        val originalColors = blendType.getField("a").get(blend) as IntArray
        val modes = blendType.getField("b").get(blend) as IntArray
        val extra = blendType.getField("c").get(blend) as? FloatArray
        val tuned = blendType.getConstructor(IntArray::class.java, IntArray::class.java, FloatArray::class.java)
            .newInstance(sidebarCapsuleBlendColors(originalColors), modes.copyOf(), extra?.copyOf())
        val apply = loader.loadClass("vq.g")
        apply.getDeclaredMethod("b", View::class.java, blur.javaClass).invoke(null, view, blur)
        apply.getDeclaredMethod("e", View::class.java, blendType, stroke.javaClass).invoke(null, view, tuned, stroke)
    }

    private fun applyCapsuleGlass(view: View, loader: ClassLoader, bloom: FloatArray) {
        val token = checkNotNull(loader.loadClass("com.miui.gamebooster.utils.q").getField("a").get(null))
        val builderType = loader.loadClass("miuix.theme.token.MaterialToken\$b")
        val builder = builderType.getConstructor(Int::class.javaPrimitiveType).newInstance(40)
        builderType.getDeclaredMethod("i", token.javaClass).invoke(builder, token)
        val material = checkNotNull(builderType.getDeclaredMethod("a").invoke(builder))
        val adapterType = loader.loadClass("vq.j")
        val adapter = checkNotNull(adapterType.getDeclaredMethod("a", Parcelable::class.java).invoke(null, material))
        val original = checkNotNull(adapterType.getDeclaredMethod("e").invoke(adapter))
        val type = original.javaClass
        val recipe = type.getField("a").get(original) as FloatArray
        check(hasSoftGlassRecipe(recipe)) { "Host glass recipe unavailable" }
        val smallBlur = sidebarCapsuleBlur(type.getField("b").getInt(original))
        val bigBlur = sidebarCapsuleBlur(type.getField("c").getInt(original))
        val tuned = type.getConstructor(FloatArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(sidebarCapsuleGlassRecipe(recipe), smallBlur, bigBlur)
        loader.loadClass("vq.g").getDeclaredMethod("c", View::class.java, type).invoke(null, view, tuned)
        View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType).invoke(view, 8)
        View::class.java.getMethod("setMiGlassBlurRadius", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(view, smallBlur, bigBlur)
        check(loader.loadClass("vq.f").getDeclaredMethod("e", View::class.java, FloatArray::class.java)
            .invoke(null, view, bloom.copyOf()) == true) { "Native bloom stroke unavailable" }
    }

    private fun hook(method: Method, id: String, interceptor: Hooker) {
        hookDeoptimized(method).setExceptionMode(ExceptionMode.PROTECTIVE).setId("sidebar:shape:$id").intercept(interceptor)
    }

    private inline fun attempt(label: String, block: () -> Unit) {
        runCatching(block).onFailure { HookModuleLogger.log(module, ModuleLog.WARN, logTag, "Could not apply $label", it) }
    }

    private companion object {
        const val NEWBOX = "com.miui.gamebooster.windowmanager.newbox."
        val CARD_TYPES = setOf(NEWBOX + "p0", NEWBOX + "z1", NEWBOX + "w1", "com.miui.dock.allapps.w")
        val CAPSULE_OUTLINE = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, minOf(view.width, view.height) / 2f)
            }
        }
    }
}
