// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.xiaoaihook

import android.content.SharedPreferences
import android.util.Log
import android.view.View
import btm.m.os4.systemuihook.*
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

/**
 * Soft glass for the floating input box of Super XiaoAI (`com.miui.voiceassist`).
 *
 * Scope: only the power-button floating input box. The floating result window keeps its own native
 * MGL glass untouched - it already owns a correct layer with its own radius, so no window tuning and
 * no extra material layer is installed for it.
 *
 * Verified chain for 8.3.30.4420:
 *
 * - `nm1.f` (GlassLayerController) owns ONE full-screen `GlassCardMglTextureView` inside
 *   `R.id.glass_container`, driven with screen-sized geometry and the rotation adapter `nm1.f$b`.
 * - `GlassCardMglTextureView.v()` passes the TextureView's own `getWidth()/getHeight()` into
 *   `s81.h0.attachPassBlur`, and `s81.f0.attach()` keys the shared channel on
 *   `ViewRootImpl.getSurfaceControl()`, so a second full-screen TextureView inside the same window
 *   reuses the channel. [XiaoAiInputGlass] mounts exactly that.
 * - `u81.f.draw(u81.g, u81.h, float, boolean, u81.o4)` receives the frame's recipe; the host for the
 *   current frame is reachable as `GlassCardScene.f` (tracker) -> `glasscard.b.j` (host view).
 *   Only hosts registered as input backgrounds are retuned here, so the inspiration capsule and the
 *   result window keep their animation.
 */
object SuperXiaoAiWindowHook {
    private const val SCENE_CLASS = "com.xiaomi.voiceassist.shaderanimation.mgl.glasscard.a"
    private const val RENDERER_CLASS = "u81.f"
    private const val RECIPE_CLASS = "u81.g"

    private val drawingHost = ThreadLocal<View?>()
    private val inputCandidates = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
    private val inputLayers = Collections.synchronizedMap(WeakHashMap<View, XiaoAiInputGlass>())
    @Volatile private var currentSettings = SuperXiaoAiInputGlassSettings()
    private val refreshing = ThreadLocal.withInitial { false }
    private var installed = false

    /** Fast path for the global `View.setBackground` intercept before any input view was seen. */
    @Volatile private var hasInputCandidates = false

    /** Remote preferences hold listeners weakly; retain this for the process lifetime. */
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * Install-stage results recorded before any `Context` exists. They are flushed into the durable
     * diagnostics file as soon as a view shows up, which is the only channel that survived on the
     * target build (its logcat ring buffer is empty).
     */
    private val pendingNotes = Collections.synchronizedList(mutableListOf<String>())

    private fun note(message: String) {
        pendingNotes.add(message)
    }

    /** Flushed into the durable diagnostics file as soon as any view provides a context. */
    private fun flushNotes(context: android.content.Context) {
        val pending = synchronized(pendingNotes) {
            if (pendingNotes.isEmpty()) return
            val copy = pendingNotes.toList()
            pendingNotes.clear()
            copy
        }
        pending.forEach { GlassDiagnostics.log(context, it) }
    }

    private fun report(module: XposedInterface, message: String, error: Throwable? = null) {
        if (error == null) {
            module.log(Log.INFO, "SuperXiaoAiInputGlass", message)
            note(message)
        } else {
            module.log(Log.WARN, "SuperXiaoAiInputGlass", message, error)
            note("WARN " + message + " :: " + error)
        }
    }

    fun install(module: XposedInterface, loader: ClassLoader, preferences: SharedPreferences) {
        if (installed) return
        installed = true
        currentSettings = readSuperXiaoAiInputGlassSettings(preferences)
        installShaderTuning(module, loader)
        installInput(module, loader, preferences)
    }

    // ------------------------------------------------------------------ glass recipe tuning

    private fun installShaderTuning(module: XposedInterface, loader: ClassLoader) {
        val scene = runCatching { loader.loadClass(SCENE_CLASS) }.getOrElse {
            report(module, "GlassCardScene unavailable", it)
            return
        }
        runCatching {
            // Scene.f = geometry tracker (glasscard.b). tracker.j = bound host view.
            val frame = scene.getDeclaredMethod("onDrawFrame")
            module.hook(frame).intercept { chain ->
                val tracker = HookTools.getObjectField(chain.thisObject, "f")
                val previous = drawingHost.get()
                drawingHost.set(tracker?.let { HookTools.getObjectField(it, "j") as? View })
                try {
                    chain.proceed()
                } finally {
                    drawingHost.set(previous)
                }
            }
            // The renderer invocation must not stay inlined inside a precompiled scene callback.
            runCatching { module.deoptimize(frame) }
            report(module, "Installed GlassCardScene.onDrawFrame host tracking")
        }.onFailure {
            report(module, "GlassCardScene.onDrawFrame hook unavailable", it)
        }

        runCatching {
            val renderer = loader.loadClass(RENDERER_CLASS)
            val draw = renderer.declaredMethods.firstOrNull {
                it.name == "draw" && it.parameterTypes.map { type -> type.name } ==
                    listOf(RECIPE_CLASS, "u81.h", "float", "boolean", "u81.o4")
            } ?: error("u81.f.draw(u81.g,u81.h,float,boolean,u81.o4) not found")
            val recipe = loader.loadClass(RECIPE_CLASS)
            val fields = recipe.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
                .associateBy { it.name }.onEach { (_, field) -> field.isAccessible = true }
            val constructor = recipe.getDeclaredConstructor(Float::class.javaPrimitiveType)
            module.hook(draw).intercept { chain ->
                val original = chain.getArg(0)
                val context = drawingHost.get()?.context
                val host = drawingHost.get()
                val settings = currentSettings
                // The renderer is shared with the result window and the inspiration capsule. Only the
                // backgrounds this module registered as floating input hosts may be retuned.
                val parameters = if (host != null && inputCandidates.containsKey(host) && settings.enabled) {
                    settings.parameters
                } else {
                    null
                }
                if (parameters == null) {
                    if (rendererTuned.remove(chain.thisObject) != null) {
                        if (context != null) {
                            GlassDiagnostics.once(
                                context, "shader-restore",
                                "Input glass tuning released, cached background blur rebuilt once",
                            )
                        }
                        // Rebuild the cached background blur once so the original look returns.
                        return@intercept chain.proceedWith(
                            chain.thisObject,
                            arrayOf(original, chain.getArg(1), chain.getArg(2), true, chain.getArg(4)),
                        )
                    }
                    return@intercept chain.proceed()
                }
                val tuned = runCatching {
                    tuneShader(original, constructor.newInstance(fields.getValue("a").getFloat(original)), fields, parameters)
                }.getOrElse {
                    if (context != null) GlassDiagnostics.warn(context, "shader-tune", "Cannot tune glass recipe", it)
                    return@intercept chain.proceed()
                }
                // A slider change must also drop the cached background blur, but only for that frame.
                val changed = rendererTuned.put(chain.thisObject, parameters) != parameters
                if (context != null) {
                    GlassDiagnostics.once(
                        context, "input-shader-hit",
                        "Floating input MGL frame matched host=" + host?.javaClass?.simpleName,
                    )
                }
                chain.proceedWith(
                    chain.thisObject,
                    arrayOf(
                        // The third argument is the native layer alpha that drives the appear / exit
                        // animation. u81.f.draw() returns false as soon as it gets near zero, which
                        // dropped the whole glass layer, so it is passed through untouched and the
                        // glass opacity stays fixed at 100%.
                        tuned, chain.getArg(1), chain.getArg(2),
                        changed || chain.getArg(3) as Boolean, chain.getArg(4),
                    ),
                )
            }
            report(module, "Installed floating input glass recipe tuning")
        }.onFailure {
            report(module, "Floating input glass recipe tuning unavailable", it)
        }
    }

    private val rendererTuned = Collections.synchronizedMap(WeakHashMap<Any, XiaoAiGlassParameters>())

    private fun tuneShader(original: Any, copy: Any, fields: Map<String, Field>, p: XiaoAiGlassParameters): Any {
        fields.values.filter { !Modifier.isFinal(it.modifiers) }.forEach { field ->
            field.set(copy, field.get(original))
        }
        // Final arrays hold colour and luminance uniforms; copy their contents into the new recipe.
        fields.values.filter { it.type == FloatArray::class.java }.forEach { field ->
            (field.get(original) as FloatArray).copyInto(field.get(copy) as FloatArray)
        }
        fun multiply(name: String, ratio: Int) {
            fields.getValue(name).let { it.setFloat(copy, it.getFloat(copy) * ratio / 100f) }
        }
        // u81.f binds these exact fields to blur sigma, diffuse light, saturation and brightness.
        multiply("h", p.blur); multiply("i", p.blur)
        multiply("r", p.softLight); multiply("s", p.softLight); multiply("u", p.softLight)
        multiply("v", p.saturation)
        multiply("w", p.brightness); multiply("x", p.brightness)
        fields.getValue("l").let {
            it.setFloat(copy, 1f + (it.getFloat(copy) - 1f) * p.refraction / 100f)
        }
        return copy
    }

    // ------------------------------------------------------------------ floating input glass

    private fun installInput(module: XposedInterface, loader: ClassLoader, preferences: SharedPreferences) {
        fun refreshOwner(root: View) {
            inputChildren(root).forEach { child -> child.post { refreshInput(child) } }
        }
        runCatching {
            val method = View::class.java.getMethod("setBackground", android.graphics.drawable.Drawable::class.java)
            module.hook(method).intercept { chain ->
                val result = chain.proceed()
                val view = chain.thisObject as? View
                if (view != null && hasInputCandidates && refreshing.get() != true && inputCandidates.containsKey(view)) {
                    // Vendor setters replace the host drawable after setBackground returns; re-hide at
                    // the frame boundary once the glass already owns the background.
                    view.post { refreshInput(view) }
                }
                result
            }
        }.onFailure { report(module, "setBackground hook unavailable", it) }

        listOf(INPUT_MODULE_V3, INPUT_MODULE_V4).forEach { name ->
            runCatching {
                val cls = loader.loadClass(name)
                cls.declaredMethods.filter { it.name == "onAttachedToWindow" }.forEach { method ->
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        (chain.thisObject as? View)?.let { root -> root.post { refreshOwner(root) } }
                        result
                    }
                }
                // Verified full-screen state setters: entering full screen restores the native look.
                val stateSetter = if (name == INPUT_MODULE_V4) "t1" else "J1"
                cls.declaredMethods.filter { it.name == stateSetter }.forEach { method ->
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        (chain.thisObject as? View)?.let { root -> root.post { refreshOwner(root) } }
                        result
                    }
                }
                note("Installed input module hooks for " + name.substringAfterLast('.'))
            }.onFailure {
                report(module, "Input module hooks unavailable for $name", it)
            }
        }
        // Native HyperMaterial wrappers refresh asynchronously on theme and state changes.
        runCatching {
            loader.loadClass("com.xiaomi.voiceassistant.utils.m2").declaredMethods.filter {
                it.name in setOf("applyMaterialToElement", "applyMaterialToView", "clearMaterialOfElement", "applyBlurToContainer")
            }.forEach { method ->
                val targetIndex = method.parameterTypes.indexOf(View::class.java)
                if (targetIndex < 0) return@forEach
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    (chain.getArg(targetIndex) as? View)?.let { view ->
                        if (hasInputCandidates && refreshing.get() != true) view.post { refreshInput(view) }
                    }
                    result
                }
            }
        }.onFailure { report(module, "Native material refresh hooks unavailable", it) }
        runCatching {
            loader.loadClass("com.xiaomi.voiceassistant.utils.o2").declaredMethods.filter {
                it.name in setOf("applyMaterial", "clearMaterialAndRestoreBgAlpha")
            }.forEach { method ->
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    if (hasInputCandidates && refreshing.get() != true) {
                        val target = (HookTools.getObjectField(chain.thisObject, "f") as? java.lang.ref.WeakReference<*>)?.get() as? View
                        if (target != null) target.post { refreshInput(target) }
                    }
                    result
                }
            }
        }.onFailure { report(module, "Input material lifecycle hooks unavailable", it) }

        preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.startsWith("super_xiaoai_")) {
                currentSettings = readSuperXiaoAiInputGlassSettings(preferences)
                val candidates = synchronized(inputCandidates) { inputCandidates.keys.toList() }
                candidates.forEach { view -> view.post { refreshInput(view) } }
            }
        }.also { preferences.registerOnSharedPreferenceChangeListener(it) }
    }

    private fun inputChildren(root: View): List<View> {
        val children = listOf("main_section", "multimodal_bg")
            .mapNotNull { id ->
                val childId = root.resources.getIdentifier(id, "id", root.context.packageName)
                if (childId == 0) null else root.findViewById<View>(childId)
            }
        if (children.isEmpty()) return emptyList()
        hasInputCandidates = true
        flushNotes(root.context)
        return children.filter { child ->
            if (inputCandidates.put(child, true) == null) {
                child.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.post { refreshInput(v) } }
                child.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) { v.post { refreshInput(v) } }
                    override fun onViewDetachedFromWindow(v: View) {
                        // Released synchronously: the entry holds a strong reference to the host view,
                        // so a deferred release would keep the detached view alive.
                        inputLayers.remove(v)?.release()
                    }
                })
            }
            true
        }
    }

    /** Returns true when the glass layer is active for the view. */
    private fun refreshInput(view: View): Boolean {
        if (refreshing.get() == true) return false
        refreshing.set(true)
        try {
            val owner = inputOwner(view)
            if (owner == null || !view.isAttachedToWindow) return false
            val settings = currentSettings
            val wanted = settings.enabled && floatingInput(owner) && view.visibility == View.VISIBLE &&
                !(isMainSection(view) && multimodalVisible(owner))
            if (!wanted) {
                inputLayers.remove(view)?.release()
                return false
            }
            if (!XiaoAiInputGlass.isSupported(view)) {
                inputLayers.remove(view)?.release()
                GlassDiagnostics.once(
                    view.context, "input-unsupported",
                    "Floating input glass skipped: window has no MGL glass container",
                )
                return false
            }
            val layer = inputLayers.getOrPut(view) { XiaoAiInputGlass(view) }
            if (!layer.mount()) {
                inputLayers.remove(view)?.release()
                return false
            }
            layer.syncRadius()
            // The app rewrites the host drawable on every input state change; re-hide it once the
            // glass already owns the background.
            layer.syncHostBackground()
            GlassDiagnostics.once(
                view.context, "input-active",
                "Floating input glass active on " + view.resources.getResourceEntryName(view.id),
            )
            return true
        } catch (error: Throwable) {
            inputLayers.remove(view)?.release()
            GlassDiagnostics.warn(view.context, "input-refresh", "Floating input glass refresh failed", error)
            return false
        } finally {
            refreshing.set(false)
        }
    }

    private fun isMainSection(view: View): Boolean =
        runCatching { view.resources.getResourceEntryName(view.id) == "main_section" }.getOrDefault(false)

    private fun multimodalVisible(owner: View): Boolean {
        val id = owner.resources.getIdentifier("multimodal_bg", "id", owner.context.packageName)
        if (id == 0) return false
        val multimodal = owner.findViewById<View>(id) ?: return false
        return multimodal.visibility == View.VISIBLE
    }

    private fun inputOwner(view: View): View? {
        if (!isMainSection(view) && !runCatching {
                view.resources.getResourceEntryName(view.id) == "multimodal_bg"
            }.getOrDefault(false)
        ) {
            return null
        }
        return generateSequence(view.parent as? View) { it.parent as? View }.firstOrNull {
            it.javaClass.name == INPUT_MODULE_V3 || it.javaClass.name == INPUT_MODULE_V4
        }
    }

    private fun floatingInput(owner: View): Boolean {
        val field = if (owner.javaClass.name == INPUT_MODULE_V4) "H1" else "x1"
        // Verified V4.H1 / V3.x1 are mIsFullScreenState. Unknown versions stay untouched.
        return HookTools.getObjectField(owner, field) == false
    }

    private const val INPUT_MODULE_V3 = "com.xiaomi.voiceassistant.mainui.inputmodulev3.InputModuleViewV3"
    private const val INPUT_MODULE_V4 = "com.xiaomi.voiceassistant.mainui.inputmodulev4.InputModuleViewV4"
}
