// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.os.Build
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.function.Supplier
import java.io.File

/** Camera 6.8.002090.2 gates. Never reads Pe.b$b.a during installation:
 * starting that holder before Pe.b breaks their circular static initialization.
 * All classes are resolved without initialization; the camera creates its own provider.
 */
internal class Camera68Hooks(
    private val module: XposedModule,
    private val preferences: Supplier<SharedPreferences?>,
) {
    private val installed = mutableSetOf<Method>()
    private val providerLock = Any()
    private var portedProvider: Any? = null
    private fun pref(key: String, default: Boolean) = preferences.get()?.getBoolean(key, default) ?: default
    private fun enabled() = pref(CameraModuleConfig.KEY_MASTER_ENABLED, true)
    private fun featuresEnabled() = enabled() && pref(CameraModuleConfig.KEY_ALL_XIAOMI18_FEATURES, true)
    private fun modelBypass() = enabled() && pref(CameraModuleConfig.KEY_REMOVE_MODEL_LIMITATION, false)

    fun install(loader: ClassLoader) {
        if (!enabled()) return
        installCrashDiagnostics(loader)
        recoverInterruptedRedCarpet(loader)
        installWatermarkHooks(loader)
        watermarkCatalog.install(loader)
        shutterHooks.install(loader)
        livePhotoHooks.install(loader)
        val capability = Class.forName("l9.g", false, loader)
        val pe = Class.forName("Pe.b", false, loader)
        hook(pe.getDeclaredMethod("Y"), "lcc-ui") { result, _, _ ->
            if (enabled() && pref(CameraModuleConfig.KEY_LEICA_UI, true)) true else result
        }
        hook(pe.getDeclaredMethod("O"), "model-compatibility") { result, _, _ ->
            if (modelBypass()) false else result
        }
        optional("model-exit") {
            val type = Class.forName("com.android.camera.a\$c", false, loader)
            val target = type.getDeclaredMethod("handleMessage", android.os.Message::class.java)
            synchronized(installed) {
                if (target in installed) return@optional
                module.hook(target).setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("camera2090:model-exit")
                    .intercept { chain ->
                        if (modelBypass() && (chain.getArg(0) as? android.os.Message)?.what == 11) null
                        else chain.proceed()
                    }
                installed.add(target)
            }
        }
        booleanGate(pe, "C0", "ai-help-capability")
        installAiHelpHooks(loader)
        for ((name, id) in listOf(
            "ai.AiModuleEntry" to "ai-help-entry",
            "legendary.LegendaryEnter" to "legendary-entry",
            "masterlive.MasterLiveModuleEntry" to "master-live-entry",
        )) optional(id) {
            booleanGate(Class.forName("com.android.camera.features.mode.$name", false, loader), "support", id)
        }
        val util = Class.forName("l9.h", false, loader)
        for ((name, id) in listOf("E2" to "color-style", "A2" to "picture-style-vibrance")) {
            optional(id) {
                hook(util.getDeclaredMethod(name, capability), id) { result, _, args ->
                    if (featuresEnabled() && args[0] != null) true else result
                }
            }
        }
        // Only color-style bit 3; all other hardware bits keep their native result.
        hook(capability.getDeclaredMethod("J0", Int::class.javaPrimitiveType), "color-style-bit") { result, _, args ->
            if (featuresEnabled() && args[0] == 3) true else result
        }
        // Version 2 enables the camera's own mode/intent checks and complete
        // picture-style list. Do not replace r2/v2 component switches or lists.
        hook(pe.getDeclaredMethod("m"), "picture-style-version") { result, _, _ ->
            if (featuresEnabled()) maxOf((result as Number).toInt(), 2) else result
        }

        // Resolve the declared provider type without reading a static holder.
        val providerType = pe.getDeclaredField("e").type
        installProviderType(providerType)
        // The factory result supplies the actual subclass AFTER original creation.
        // No invoke(), newInstance(), singleton field read or Class.forName(true).
        val factory = Class.forName("Eu.f2", false, loader).getDeclaredMethod("l")
        check(factory.returnType == providerType) { "Unexpected Camera 2090 provider schema" }
        hook(factory, "native-provider") { result, _, _ ->
            val selected = selectProvider(loader, factory.declaringClass, result)
            selected?.let { provider -> optional("native-provider-type") {
                if (provider.javaClass !in reportedProviders) {
                    reportedProviders.add(provider.javaClass)
                    log("Provider=${provider.javaClass.name}, device=${Build.DEVICE}, master=${enabled()}, leica=${pref(CameraModuleConfig.KEY_LEICA_UI, true)}")
                }
                installProviderType(provider.javaClass)
            } }
            selected
        }
        installFocalLengths(loader)
        installPictureStylePipeline(loader)
        log("2090 gates installed; physical device=${Build.DEVICE}; provider selected only after camera factory creation")
    }

    // The OEM toast maps every cloud business error to "poor network".
    // Observe the real result without forcing traditional AI scene settings,
    // HAL tag availability or the unrelated AppFunctions scene-query result.
    private fun installAiHelpHooks(loader: ClassLoader) {
        optional("ai-help-error") {
            val errorType = Class.forName("wn.d\$a", false, loader)
            val codeField = errorType.getDeclaredField("a").apply { isAccessible = true }
            val messageField = errorType.getDeclaredField("b").apply { isAccessible = true }
            val codeMethod = Class.forName("wn.m", false, loader).getDeclaredMethod("a")
            hook(Class.forName("com.android.camera.features.mode.ai.AiModule", false, loader)
                .getDeclaredMethod("getErrorTipRes", errorType), "ai-help-error") { result, _, args ->
                if (featuresEnabled()) {
                    val error = args[0]
                    log("AI help error: code=${codeMethod.invoke(codeField.get(error))}, message=${aiDiagnosticText(messageField.get(error))}")
                }
                result
            }
        }
        optional("ai-help-requester") {
            val requester = Class.forName("wn.a", false, loader)
            hook(requester.getDeclaredMethod("a", Class::class.java, String::class.java),
                "ai-help-endpoint") { result, _, args ->
                if (featuresEnabled() && (args[1] as? String)?.contains("avatar-ai.sec.miui.com") == true) {
                    log("AI help endpoint: ${args[1]}")
                }
                result
            }
            hook(requester.getDeclaredMethod("e", Throwable::class.java),
                "ai-help-exception") { result, _, args ->
                if (featuresEnabled()) {
                    val error = args[0] as? Throwable
                    log("AI cloud exception: type=${error?.javaClass?.name}, message=${aiDiagnosticText(error?.message)}")
                }
                result
            }
        }
        // Both cloud paths carry phone_model inside sceneContext. Adapt the
        // completed request DTO, without changing shared ASD state, Build,
        // global JSONObject.put or the camera's original request call count.
        for ((name, method, parameters) in listOf(
            Triple("rn.b", "Q", arrayOf(Any::class.java, Any::class.java)),
            Triple("un.a", "a", arrayOf(Any::class.java)),
        )) optional("ai-help-context:$name") {
            val request = Class.forName("wn.e", false, loader)
            val fields = listOf("a", "b", "c", "d").map { field ->
                request.getDeclaredField(field).apply { isAccessible = true }
            }
            val constructor = request.getDeclaredConstructor(
                String::class.java, String::class.java, String::class.java, Map::class.java,
            )
            hook(Class.forName(name, false, loader).getDeclaredMethod(method, *parameters),
                "ai-help-context:$name") { result, _, _ ->
                if (!featuresEnabled() || result == null) return@hook result
                val values = fields[2].get(result) as? Map<*, *> ?: return@hook result
                val text = values["sceneContext"] as? String ?: return@hook result
                val scene = org.json.JSONObject(text)
                val originalModel = scene.optString("phone_model")
                log("AI help context: pipeline=$name, model=${aiDiagnosticText(originalModel)}")
                if (!modelBypass() || !Build.DEVICE.equals("pandora", true) || originalModel == "madrid") {
                    return@hook result
                }
                scene.put("phone_model", "madrid")
                val updated = LinkedHashMap(values)
                updated["sceneContext"] = scene.toString()
                val replacement = constructor.newInstance(
                    fields[0].get(result), fields[1].get(result), fields[3].get(result), updated,
                )
                log("AI help request model adapted to madrid: pipeline=$name")
                replacement
            }
        }
    }

    // Exclude request URLs, long identifiers and multiline bodies from the
    // bounded private log. Never record images, OAID, auth headers or trace IDs.
    private fun aiDiagnosticText(value: Any?): String = value?.toString().orEmpty()
        .replace(Regex("https?://\\S+"), "<url>")
        .replace(Regex("[A-Za-z0-9_+/=-]{24,}"), "<id>")
        .replace('\n', ' ').replace('\r', ' ').take(180)

    /**
     * Correct the device identity at the final model-view boundary.
     * Property spoofing alone is too early: the camera
     * caches the Xiaomi 18 profile before the watermark is rendered. Keep the
     * original pipeline and replace only the text that is written to the
     * model/version views.
     *
     * This is also called from Gallery/MediaEditor, where the same obfuscated
     * watermark classes may be loaded from a feature dex.
     */
    fun installWatermarkHooks(loader: ClassLoader) {
        for (name in listOf("bv.a", "eo0.b")) optional("watermark-identity:$name") {
            val source = Class.forName(name, false, loader).getDeclaredMethod("a")
            val pair = source.returnType.getDeclaredConstructor(Any::class.java, Any::class.java)
            hook(source, "watermark-identity") { result, _, _ ->
                val model = customWatermarkModel()
                if (!enabled() || model.isBlank()) result
                else pair.newInstance(watermarkBrand(model), nativeWatermarkSeries())
            }
        }
        // These methods update BOTH the logo image selector and model text.
        // Keep the native resource paths/colors; only pass the selected identity.
        for ((name, method) in listOf("com.xiaomi.cam.watermark.a" to "J0", "mm0.a" to "F")) {
            optional("watermark-template:$name") {
                val target = Class.forName(name, false, loader).getDeclaredMethod(
                    method, String::class.java, String::class.java, Boolean::class.javaPrimitiveType,
                )
                synchronized(installed) {
                    if (target !in installed) {
                        module.hook(target).setPriority(XposedInterface.PRIORITY_HIGHEST)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("camera2090:watermark-template:$name")
                            .intercept { chain ->
                                val args = runCatching {
                                    val model = customWatermarkModel()
                                    if (!enabled() || model.isBlank()) null else chain.args.toTypedArray().also {
                                        it[0] = watermarkBrand(model)
                                        it[1] = nativeWatermarkSeries()
                                    }
                                }.getOrNull()
                                if (args == null) chain.proceed() else chain.proceed(args)
                            }
                        installed.add(target)
                        log("Installed watermark-template: $target")
                    }
                }
            }
        }
        optional("watermark-device-model") {
            val device = Class.forName("Pe.b", false, loader)
            hook(device.getDeclaredMethod("z"), "watermark-brand") { result, _, _ ->
                val custom = customWatermarkModel()
                if (!enabled() || custom.isBlank()) result else watermarkBrand(custom)
            }
            hook(device.getDeclaredMethod("A"), "watermark-model") { result, _, _ ->
                val custom = customWatermarkModel()
                if (!enabled() || custom.isBlank()) result else nativeWatermarkSeries().ifBlank { result }
            }
        }
        optional("watermark-model-view") {
            // h() is the template's actual ID; j() only returns WmModelView.
            installWatermarkModelView(loader, "vs.m", "vs.h", "Gg.V", "b", "h", "watermark-model-view")
        }
        // MediaEditor 2.10.x uses the same renderer contract under sm0.l.
        // The class is absent from the camera process, so keep this optional
        // and install it only when the editor feature dex is present.
        optional("editor-watermark-model-view") {
            installWatermarkModelView(loader, "sm0.l", "sm0.h", "kl0.p0", "e", "getId", "editor-watermark-model-view")
        }
    }

    private val watermarkSuffixSlots = java.util.WeakHashMap<Any, Boolean>()
    private val shutterHooks = CameraShutterHooks(module, preferences, ::log)
    private val livePhotoHooks = CameraLivePhotoHooks(module, {
        featuresEnabled() && modelBypass() && Build.DEVICE.equals("pandora", true)
    }, ::log)
    private val watermarkLeicaSlots = java.util.WeakHashMap<Any, Boolean>()
    private val watermarkSuffixMargins = java.util.WeakHashMap<Any, Float>()
    private val watermarkCatalog = CameraWatermarkCatalogHooks(module, { enabled() }, {
        pref(CAMERA_ALL_WATERMARKS, false)
    }, { pref(CAMERA_FRONT_WATERMARKS, false) }, ::log)

    private fun installWatermarkModelView(
        loader: ClassLoader, viewName: String, layoutName: String, translatorName: String,
        parseMethod: String, idMethod: String, hookId: String,
    ) {
        val view = Class.forName(viewName, false, loader)
        val update = view.getDeclaredMethod(
            "o", String::class.java, String::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        val getId = view.getMethod(idMethod)
        val setText = view.getMethod("n", String::class.java)
        // B retains the original template format after @model=; A is text_upper.
        val format = view.getDeclaredField("B").apply { isAccessible = true }
        val uppercase = view.getDeclaredField("A").apply { isAccessible = true }
        val margin = view.getField("e")
        fun render(obj: Any) {
            if (!enabled()) return
            val selected = customWatermarkModel()
            if (selected.isBlank()) return
            val pattern = format.get(obj) as? String ?: return
            val brand = watermarkBrand(selected)
            val text = splitCameraWatermarkModel(
                watermarkSeries(selected), cameraWatermarkSuffixMask(preferences.get(), brand),
            )
            val separateSuffix = synchronized(watermarkSuffixSlots) { watermarkSuffixSlots[obj] == true }
            val rendered = renderCameraWatermarkModel(pattern, brand, text, uppercase.getBoolean(obj), separateSuffix, synchronized(watermarkLeicaSlots) { watermarkLeicaSlots[obj] == true })
            if (pattern.contains("@{versionName}") && !pattern.contains("@{series}")) {
                synchronized(watermarkSuffixMargins) {
                    val original = watermarkSuffixMargins.getOrPut(obj) { margin.getFloat(obj) }
                    margin.setFloat(obj, if (text.suffix.isNotEmpty() && separateSuffix) maxOf(original, 8f) else original)
                }
            }
            // An empty suffix produces no native text/outline. Leave enable flags,
            // font references, rect_params and layout spacing owned by the template.
            setText.invoke(obj, rendered.ifBlank { "" })
            val id = getId.invoke(obj) as? String
            reportWatermark("$hookId:$id:$rendered", "Watermark text rendered: view=$hookId, id=$id, text=$rendered")
        }
        hook(update, hookId) { result, obj, _ ->
            if (obj != null) render(obj)
            result
        }
        // Resolve siblings only after the native parser has created the whole row.
        // Some editor templates contain versionName in BOTH model and suffix views.
        // Keep the original B format intact so live preference changes stay reversible.
        val layout = Class.forName(layoutName, false, loader)
        val children = layout.getDeclaredField("k").apply { isAccessible = true }
        val parse = layout.getDeclaredMethod(
            parseMethod, org.json.JSONObject::class.java, Class.forName(translatorName, false, loader),
        )
        synchronized(installed) {
            if (parse !in installed) {
                val translatorPath = Class.forName(translatorName, false, loader).declaredFields
                    .first { it.type == String::class.java }.apply { isAccessible = true }
                module.hook(parse).setId("camera2090:$hookId-layout")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                        var added = false
                        val modified = runCatching {
                            if (!enabled()) return@runCatching null
                            val selected = selectedWatermarkIdentity()
                            val split = splitCameraWatermarkModel(selected.model, cameraWatermarkSuffixMask(preferences.get(), selected.brand))
                            if (!split.hasLeica) return@runCatching null
                            val directory = File(translatorPath.get(chain.getArg(1)) as String).parentFile?.canonicalFile ?: return@runCatching null
                            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? android.app.Application
                                ?: return@runCatching null
                            val allowed = listOfNotNull(File(app.applicationInfo.dataDir), app.getExternalFilesDir(null))
                                .any { directory.path.startsWith(it.canonicalPath + File.separator) }
                            if (!allowed) return@runCatching null
                            val config = org.json.JSONObject(File(directory, "config.json").readText())
                            val row = chain.getArg(0) as org.json.JSONObject
                            val rows = row.optJSONArray("childViews") ?: return@runCatching null
                            val main = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                                .firstOrNull { it.optString("text").contains("@{series}") } ?: return@runCatching null
                            val font = config.optJSONObject("wmRes")?.optJSONObject("fonts")?.optJSONObject(main.optString("font_ref"))
                                ?: return@runCatching null
                            val size = font.optDouble("size", 30.0).toFloat().takeIf { it.isFinite() && it > 0f } ?: return@runCatching null
                            val color = font.optString("color", "@foreground=0")
                            val index = color.takeIf { it.startsWith("@foreground=") }?.substringAfter('=')?.toIntOrNull()
                            val backgrounds = config.optJSONObject("wmRes")?.optJSONArray("background")
                            if (index != null && (backgrounds == null || (0 until backgrounds.length()).any {
                                    index !in 0 until (backgrounds.optJSONObject(it)?.optJSONArray("foreground")?.length() ?: 0)
                                })) return@runCatching null
                            val changed = addCameraLeicaSuffix(row, split.byLeica, size, color) ?: return@runCatching null
                            if (split.byLeica) CameraWatermarkGlyph.materialize(directory)
                            chain.args.toTypedArray().also { it[0] = changed; added = true }
                        }.onFailure { log("$hookId by Leica layout: $it") }.getOrNull()
                        val result = if (modified == null) chain.proceed() else chain.proceed(modified)
                        runCatching {
                            val models = (children.get(chain.thisObject) as? List<*>)?.filterNotNull()?.filter(view::isInstance).orEmpty()
                            val hasSuffix = models.any {
                                val pattern = format.get(it) as? String ?: ""
                                pattern.contains("@{versionName}") && !pattern.contains("@{series}")
                            }
                            synchronized(watermarkSuffixSlots) { models.forEach { watermarkSuffixSlots[it] = hasSuffix } }
                            synchronized(watermarkLeicaSlots) { models.forEach { watermarkLeicaSlots[it] = added } }
                            models.forEach(::render)
                        }.onFailure { log("$hookId layout rendering: $it") }
                        result
                    }
                installed.add(parse)
            }
        }
    }

    private val reportedWatermarks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun reportWatermark(key: String, message: String) {
        if (reportedWatermarks.add(key)) log(message)
    }

    private fun selectedWatermarkIdentity() = resolveCameraWatermarkIdentity(
        preferences.get()?.getString(CAMERA_WATERMARK_BRAND, "").orEmpty(),
        preferences.get()?.getString(CameraModuleConfig.KEY_CUSTOM_WATERMARK_MODEL, "").orEmpty(),
        cameraSystemMarketName(), Build.BRAND,
    )
    // The stock image selector treats one exact Ultra identity as a full composite
    // logo. Our separate nodes own model + suffixes, so never select that composite.
    private fun nativeWatermarkSeries() = selectedWatermarkIdentity().model
        .replace(Regex("\\s+by\\s+Leica$", RegexOption.IGNORE_CASE), "")
    private fun customWatermarkModel() = selectedWatermarkIdentity().fullName
    private fun watermarkBrand(model: String) = selectedWatermarkIdentity().brand.uppercase(java.util.Locale.ROOT)
    private fun watermarkSeries(model: String) = selectedWatermarkIdentity().model

    private fun selectProvider(loader: ClassLoader, factory: Class<*>, original: Any?): Any? {
        if (!modelBypass() || !Build.DEVICE.equals("pandora", true) || original == null) return original
        // This APK ships Madrid, but no Pandora implementation. The factory
        // silently returns Common on Pandora: bypassing O() alone leaves nearly
        // every feature and the zoom tables at their Common defaults.
        val commonName = Class.forName("Pe.b", false, loader).getDeclaredField("e").type.name
        if (original.javaClass.name != commonName && original !== portedProvider) return original
        return synchronized(providerLock) {
            val selected = portedProvider ?: run {
                val resolver = Class.forName("Yf.c", false, loader).getDeclaredMethod("a", String::class.java)
                val type = resolver.invoke(null, "com.mi.device.Madrid") as Class<*>
                check(Class.forName(commonName, false, loader).isAssignableFrom(type))
                // Eu.f2.l() has already run. Do not touch Pe.b$b or create Pe.b.
                type.getDeclaredConstructor().newInstance().also {
                    installProviderType(type)
                    portedProvider = it
                    log("Routed missing Pandora provider Common -> Madrid; Build identity unchanged")
                }
            }
            factory.getDeclaredField("b").apply { isAccessible = true }.set(null, selected)
            factory.getDeclaredField("a").apply { isAccessible = true }.set(null, false)
            selected
        }
    }

    private fun parseFocalList(raw: String?): FloatArray? = raw?.split(',', '，', ';', ' ', '\t', '\n')
        ?.mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toFloatOrNull() }
        ?.filter { it.isFinite() && it in 0.1f..100f }
        ?.distinct()
        ?.take(12)
        ?.takeIf { it.size >= 2 }
        ?.toFloatArray()

    private fun focalPreset(name: String): FloatArray? = when (name.lowercase()) {
        "dada" -> floatArrayOf(.6f, 1f, 2f, 2.6f, 5f)
        "haotian" -> floatArrayOf(.6f, 1f, 2f, 5f, 10f)
        "xuanyuan" -> floatArrayOf(.6f, 1f, 2f, 3f, 4.3f)
        "aurora" -> floatArrayOf(.6f, 1f, 2f, 3.2f, 5f)
        "degas" -> floatArrayOf(.6f, 1f, 2f, 2.6f, 5f)
        "rothko" -> floatArrayOf(.6f, 1f, 2f, 3.2f, 5f)
        "dijun" -> floatArrayOf(.6f, 1f, 2f, 3.2f, 5f)
        "goya" -> floatArrayOf(.6f, 1f, 2f, 2.6f, 5f)
        "klimt" -> floatArrayOf(.6f, 1f, 2f, 3.2f, 5f)
        ("pud" + "ding") -> floatArrayOf(.7f, 1f, 2f, 2.6f, 5f)
        "shennong", "shennong_t" -> floatArrayOf(.7f, 1f, 2f, 3.2f, 5f)
        "byron" -> floatArrayOf(.7f, 1f, 2f, 5f, 10f)
        "pandora", "popsicle" -> floatArrayOf(.7f, 1f, 2f, 5f, 10f)
        "chagall", "warhol" -> floatArrayOf(.7f, 1f, 2f, 5f, 10f)
        "nezha" -> floatArrayOf(.6f, 1f, 2f, 3.2f, 4.3f, 8.6f)
        else -> null
    }

    private fun selectedFocalTable(): FloatArray? {
        if (!enabled() || !pref(CameraModuleConfig.KEY_PRESERVE_NATIVE_FOCAL_LENGTHS, true)) return null
        val source = prefInt(CameraModuleConfig.KEY_FOCAL_LENGTH_SOURCE, 0)
        if (source == 2) return parseFocalList(preferences.get()?.getString(CameraModuleConfig.KEY_CUSTOM_FOCAL_LENGTHS, null))
        if (source == 1) return focalPreset(preferences.get()?.getString(CameraModuleConfig.KEY_FOCAL_LENGTH_PRESET, "auto") ?: "auto")
        return focalPreset(Build.DEVICE)
    }

    // The donor package exposes the Xiaomi 18 Pro Max zoom array through every
    // ordinary mode. The selector itself is mode-aware, so replace only its
    // focal values while leaving the top-bar component catalog untouched.
    private fun focalTable(mode: Int): FloatArray? = selectedFocalTable()

    private fun prefInt(key: String, default: Int): Int = preferences.get()?.getInt(key, default) ?: default

    private fun installFocalLengths(loader: ClassLoader) {
        val int = Int::class.javaPrimitiveType!!
        val bool = Boolean::class.javaPrimitiveType!!
        val data = Class.forName("com.android.camera.data.data.i", false, loader)
        val state = Class.forName("g2.a", false, loader).getDeclaredMethod("g")
        fun rearCamera(): Boolean = runCatching {
            val item = state.invoke(null)
            !(item.javaClass.getMethod("N").invoke(item) as Boolean)
        }.getOrDefault(false)
        for (name in listOf("T", "V")) optional("focal-$name") {
            hook(data.getDeclaredMethod(name, int, bool), "focal-$name") { result, _, args ->
                if (rearCamera()) focalTable(args[0] as Int) ?: result else result
            }
        }
        optional("focal-view") {
            val view = Class.forName("com.android.camera.ui.zoom.ZoomRatioToggleView", false, loader)
            hook(view.getDeclaredMethod("u", FloatArray::class.java, bool, bool, bool), "focal-view") { result, obj, _ ->
                if (rearCamera()) focalTable(view.getDeclaredField("q").apply { isAccessible = true }.getInt(obj)) ?: result else result
            }
            hook(view.getDeclaredMethod("m", int, int, bool, bool), "focal-tap") { result, obj, args ->
                if (!rearCamera() || focalTable(args[0] as Int) == null) result else {
                    val displayed = view.getDeclaredField("R").apply { isAccessible = true }.get(obj) as? FloatArray
                    val index = args[1] as Int
                    val actualIndex = if (args[2] == true && displayed != null) displayed.lastIndex - index else index
                    displayed?.getOrNull(actualIndex) ?: result
                }
            }
        }
    }

    /** Reapply the Pro style values through the 2090 EffectController after the OEM
     * method has refreshed its workspace. This keeps the camera's own component
     * values and avoids importing a renderer or LUT implementation from another build. */
    private fun installPictureStylePipeline(loader: ClassLoader) = optional("picture-style-pipeline") {
        val module = Class.forName("com.android.camera.module.Camera2Module", false, loader)
        val effect = Class.forName("com.xiaomi.camera.effect.EffectController", false, loader)
        val componentTypes = listOf(
            "r2.g1" to "p0", "r2.e1" to "n0", "r2.c1" to "m0",
            "r2.q0" to "l0", "r2.s0" to "o0",
        )
        val getValue = module.getDeclaredMethod("getComponentManuallyStyleValue", Class.forName("r2.X0", false, loader))
        val repository = Class.forName("g2.a", false, loader).getDeclaredMethod("a")
        val resolveComponent = repository.returnType.getMethod("x", Class::class.java)
        val index = generateSequence(module as Class<*>?) { it.superclass }
            .mapNotNull { runCatching { it.getDeclaredField("mModuleIndex") }.getOrNull() }
            .firstOrNull()?.apply { isAccessible = true }
            ?: error("Camera2Module module index field unavailable")
        for (name in listOf("updateStyleVibrance", "updateStyleTone", "updateStyleTexture", "updateStyleTemperature", "updateStyleTune", "updateStyleLoadAndUse")) {
            optional("style-$name") {
                hook(module.getDeclaredMethod(name), "style-$name") { result, obj, _ ->
                    val current = runCatching { index.getInt(obj) }.getOrDefault(-1)
                    if (featuresEnabled() && current == 167) {
                        val controller = effect.getDeclaredMethod("u").invoke(null)
                        for ((typeName, setterName) in componentTypes) runCatching {
                            val component = resolveComponent.invoke(repository.invoke(null), Class.forName(typeName, false, loader))
                            if (component == null) return@runCatching
                            val value = getValue.invoke(obj, component) as Number
                            val setter = effect.getDeclaredMethod(setterName, Int::class.javaPrimitiveType)
                            setter.invoke(controller, value.toInt())
                        }
                    }
                    result
                }
            }
        }
    }

    private fun installProviderType(type: Class<*>) {
        optional("master-live-focal-profiles") {
            val profiles = type.getMethod("c0")
            hook(profiles, "master-live-focal-profiles") { result, _, _ ->
                if (!featuresEnabled() || result !is Map<*, *>) result
                else adaptMasterLiveProfiles(result, selectedFocalTable())
            }
        }
        // getMethods() finds inherited methods as well as native subclass overrides.
        // Each Method is registered once, even when the factory returns a cached provider.
        for ((name, id) in listOf(
            "o2" to "ai-help-provider", "c3" to "legendary-provider",
            "b3" to "legendary-m10r", "l3" to "master-live-provider",
        )) optional(id) { booleanGate(type, name, id, inherited = true) }
        optional("motion-capture-provider") {
            hook(type.getMethod("V0"), "motion-capture-provider") { result, _, _ ->
                // r2.H.isSupportMode still enforces photo modes/normal intent;
                // r2.H.isSwitchOn still reads the camera's actual ON/OFF value.
                if (featuresEnabled()) maxOf((result as Number).toInt(), 1) else result
            }
        }
        optional("rear-preview-provider") {
            hook(type.getMethod("z3"), "rear-preview-provider") { result, _, _ ->
                // Pandora is the connected Xiaomi 17 Pro with a real rear display.
                // Do not invent presentation support on arbitrary devices.
                if (featuresEnabled() && Build.DEVICE.equals("pandora", true)) true else result
            }
        }
    }

    private fun recoverInterruptedRedCarpet(loader: ClassLoader) = optional("master-live-red-carpet-fallback") {
        val marker = File("/data/user/0/com.android.camera/files/hyperchanger-masterlive-redcarpet.pending")
        if (!marker.exists() || System.currentTimeMillis() - marker.lastModified() > 30 * 60 * 1000L) {
            if (marker.exists()) marker.delete()
            return@optional
        }
        val store = Class.forName("g2.a", false, loader).getDeclaredMethod("g").invoke(null)
        val editor = store.javaClass.getMethod("g").invoke(store)
        editor.javaClass.getMethod("r", String::class.java, String::class.java)
            .invoke(editor, "pref_master_live_key", "0")
        editor.javaClass.getMethod("c").invoke(editor)
        marker.delete()
        log("Recovered interrupted red-carpet MasterLive session; selected ultra-live (0)")
    }

    /** Align the MasterLive profile DTO (lens keys, ranges and animated defaults)
     * with the physical camera's focal selector. The port's provider is a donor
     * profile, so changing only data.i leaves the animated path at 3.2 -> 7. */
    private fun adaptMasterLiveProfiles(source: Map<*, *>, focal: FloatArray?): Map<*, *> {
        if (focal == null || focal.size < 2) return source
        return runCatching {
            val result = LinkedHashMap<Any?, Any?>(source.size)
            val cls = Class.forName("Re.a", false, source.values.firstOrNull()?.javaClass?.classLoader)
            val fields = listOf("a", "b", "c", "d", "e", "f", "g").associateWith {
                cls.getDeclaredField(it).apply { isAccessible = true }
            }
            source.forEach { (key, value) ->
                if (value == null || value.javaClass != cls) { result[key] = value; return@forEach }
                val clone = cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                fields["a"]?.set(clone, fields["a"]?.get(value))
                fields["f"]?.set(clone, fields["f"]?.get(value))
                fields["g"]?.set(clone, fields["g"]?.get(value))
                val originalLabels = (fields["b"]?.get(value) as? List<*>)?.filterIsInstance<String>().orEmpty()
                if (originalLabels.isEmpty()) { result[key] = value; return@forEach }
                // Keep the provider's semantic lens keys (wide/tele/etc.).
                // A0 stores these strings; replacing them with display numbers
                // makes the native lens component reject the selection.
                fields["b"]?.set(clone, originalLabels)
                val ranges = focal.take(originalLabels.size).flatMapIndexed { index, _ ->
                    val start = focal[index]
                    val end = focal.getOrNull(index + 1) ?: start
                    listOf(start, maxOf(start, end))
                }
                fields["c"]?.set(clone, ranges)
                val originalFlags = fields["d"]?.get(value) as? List<*>
                fields["d"]?.set(clone, List(originalLabels.size) { index -> originalFlags?.getOrNull(index) ?: false })
                fields["e"]?.set(clone, originalLabels.indices.map { index ->
                    val start = focal[index]
                    val end = focal.getOrNull(index + 1) ?: start
                    "$start:$end"
                })
                result[key] = clone
            }
            result
        }.getOrDefault(source)
    }

    private fun booleanGate(type: Class<*>, name: String, id: String, inherited: Boolean = false) {
        val target = if (inherited) type.getMethod(name) else type.getDeclaredMethod(name)
        check(target.returnType == Boolean::class.javaPrimitiveType)
        hook(target, id) { result, _, _ -> if (featuresEnabled()) true else result }
    }

    private fun hook(method: Method, id: String, transform: (Any?, Any?, List<Any?>) -> Any?) {
        synchronized(installed) {
            if (method in installed) return
            check(!Modifier.isAbstract(method.modifiers)) { "Abstract camera gate: $method" }
            method.isAccessible = true
            module.hook(method).setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("camera2090:$id:${method.declaringClass.name}")
                .intercept { chain ->
                    val result = chain.proceed()
                    if (!enabled()) return@intercept result
                    // Installation/transform failure must not rerun the original.
                    runCatching { transform(result, chain.thisObject, chain.args) }
                        .onFailure { log("$id failed: ${it.javaClass.simpleName}") }.getOrDefault(result)
                }
            installed.add(method)
            log("Installed $id: ${method.toGenericString()}")
        }
    }

    private inline fun optional(id: String, block: () -> Unit) {
        runCatching(block).onFailure { log("$id unavailable: $it") }
    }
    private val reportedProviders = java.util.concurrent.ConcurrentHashMap.newKeySet<Class<*>>()
    private fun installCrashDiagnostics(loader: ClassLoader) = optional("crash-diagnostics") {
        val method = Class.forName("xcrash.h", false, loader)
            .getDeclaredMethod("uncaughtException", Thread::class.java, Throwable::class.java)
        synchronized(installed) {
            if (method in installed) return@optional
            module.hook(method).setId("camera2090:crash-diagnostics")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    if (enabled()) runCatching { log("Uncaught camera exception:\n${(chain.getArg(1) as? Throwable)?.stackTraceToString()}") }
                    chain.proceed()
                }
            installed.add(method)
        }
    }

    private fun log(message: String) {
        HookModuleLogger.log(module, ModuleLog.INFO, "Camera2090", message)
        // Device logcat is disabled on this ROM. Keep a bounded, app-private
        // diagnostic log; preserve the original camera crash handler unchanged.
        runCatching {
            val file = File("/data/user/0/com.android.camera/files/hyperchanger-camera2090.log")
            synchronized(installed) {
                if (file.length() > 262144) file.writeText("")
                file.appendText("${System.currentTimeMillis()} ${android.os.Process.myPid()} $message\n")
            }
        }
    }

    companion object {
        @JvmStatic
        fun supports(loader: ClassLoader): Boolean = runCatching {
            val pe = Class.forName("Pe.b", false, loader)
            val factory = Class.forName("Eu.f2", false, loader).getDeclaredMethod("l")
            factory.returnType == pe.getDeclaredField("e").type &&
                pe.getDeclaredMethod("Y").returnType == Boolean::class.javaPrimitiveType &&
                Class.forName("l9.g", false, loader).getDeclaredMethod("J0", Int::class.javaPrimitiveType).returnType == Boolean::class.javaPrimitiveType
        }.getOrDefault(false)
    }

}
