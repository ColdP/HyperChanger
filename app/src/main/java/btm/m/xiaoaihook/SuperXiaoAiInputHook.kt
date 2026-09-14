// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.xiaoaihook

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.BaseBundle
import android.os.Bundle
import android.os.Handler
import android.os.PersistableBundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** Consolidated Super XiaoAi input-method integration. */
object SuperXiaoAiInputHook {
    fun install(module: io.github.libxposed.api.XposedModule, classLoader: ClassLoader, phraseProcess: Boolean) {
        HookConfig.initRemote(module)
        runCatching { ClipboardArchive.install(module, classLoader) }
        if (phraseProcess) return
        runCatching { PlatformGate.install(module, classLoader) }
        runCatching { AiGuard.install(module, classLoader) }
        runCatching { SpeechGuard.install(module, classLoader) }
        runCatching { DictionaryGate.install(module, classLoader) }
        runCatching { ClipboardPrivacy.install(module, classLoader) }
        runCatching { SearchPageAppearance.install(module, classLoader) }
        runCatching { KeyboardSurface.install(module, classLoader) }
    }
}

object HookConfig {
    private const val PREFS_NAME = "hyper_system_ui_hook"
    private const val KEY_AI_SAFETY = "super_xiaoai_ai_safety_unblocked"
    private const val KEY_VOICE_MODERATION = "super_xiaoai_voice_safety_unblocked"
    private const val KEY_CLOUD_BLACKLIST = "super_xiaoai_blacklist_unblocked"
    private const val KEY_CLIPBOARD_UNBLOCKED = "super_xiaoai_clipboard_unblocked"
    private const val KEY_GLOBAL_SEARCH_APPEARANCE = "super_xiaoai_global_search_appearance"
    private const val KEY_STYLE_ENABLED = "super_xiaoai_keyboard_style_enabled"
    private const val KEY_COLOR_MODE = "super_xiaoai_keyboard_color_mode"
    private const val KEY_CORNER_RADIUS = "super_xiaoai_keyboard_corner_radius"
    private const val KEY_OPACITY = "super_xiaoai_keyboard_opacity"
    private const val KEY_BLUR_RADIUS = "super_xiaoai_keyboard_blur"
    private const val KEY_HIGHLIGHT = "super_xiaoai_keyboard_highlight"
    private const val KEY_SHADOW = "super_xiaoai_keyboard_shadow"
    private const val KEY_STROKE_WIDTH = "super_xiaoai_keyboard_stroke_width"
    private const val KEY_BOTTOM_HIGHLIGHT = "super_xiaoai_keyboard_bottom_highlight"

    private var remotePrefs: SharedPreferences? = null

    fun initRemote(module: XposedInterface) {
        try {
            remotePrefs = module.getRemotePreferences(PREFS_NAME)
        } catch (_: Throwable) {
            remotePrefs = null
        }
    }

    fun syncFromProvider(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    fun isAiSafetyEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_AI_SAFETY, false) ?: false
    }

    fun isVoiceModerationEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_VOICE_MODERATION, false) ?: false
    }

    fun isCloudBlacklistEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_CLOUD_BLACKLIST, false) ?: false
    }

    fun isClipboardSensitiveEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_CLIPBOARD_UNBLOCKED, false) ?: false
    }

    fun isClipboardPermanentEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_CLIPBOARD_UNBLOCKED, false) ?: false
    }

    fun isGlobalSearchAppearanceEnabled(): Boolean {
        return remotePrefs?.getBoolean(KEY_GLOBAL_SEARCH_APPEARANCE, false) ?: false
    }

    fun isOsVersionUnblockEnabled(): Boolean {
        return false
    }

    fun isStyleEnabled(): Boolean {
        return (remotePrefs?.getBoolean(KEY_STYLE_ENABLED, false) ?: false) ||
            (remotePrefs?.getInt(KEY_COLOR_MODE, 0) ?: 0) != 0
    }

    private fun getFloat(key: String, default: Float): Float {
        val prefs = remotePrefs ?: return default
        return runCatching { prefs.getFloat(key, default) }.getOrElse {
            runCatching { prefs.getInt(key, default.toInt()).toFloat() }.getOrDefault(default)
        }
    }

    fun getCornerRadius(): Float {
        return getFloat(KEY_CORNER_RADIUS, 24f).coerceIn(0f, 48f)
    }

    /**
     * The settings writer stores the complete settings object, so preference
     * presence cannot distinguish the default from an explicit choice. Treat
     * both the current 24dp default and the previous 30dp default as follow-
     * native values; every other value is an intentional override.
     */
    fun hasCornerRadiusOverride(): Boolean {
        val value = getFloat(KEY_CORNER_RADIUS, 24f)
        return value !in 23.999f..24.001f && value !in 29.999f..30.001f
    }

    fun getOpacity(): Int {
        return remotePrefs?.getInt(KEY_OPACITY, 56)?.coerceIn(0, 100) ?: 56
    }

    fun getBlurRadius(): Float {
        return getFloat(KEY_BLUR_RADIUS, 15f).coerceIn(0f, 45f)
    }

    /** Top edge highlight alpha, expressed as a percentage of the shader's full alpha. */
    fun getHighlight(): Int {
        return remotePrefs?.getInt(KEY_HIGHLIGHT, 100)?.coerceIn(0, 100) ?: 100
    }

    /** Native shadow alpha multiplier. 100% preserves the platform value. */
    fun getShadow(): Int {
        return remotePrefs?.getInt(KEY_SHADOW, 10)?.coerceIn(0, 100) ?: 10
    }

    /** Shader outline width in dp. */
    fun getStrokeWidth(): Float {
        return getFloat(KEY_STROKE_WIDTH, 2f).coerceIn(0f, 4f)
    }

    /** Bottom edge highlight alpha, expressed as a percentage of full alpha. */
    fun getBottomHighlight(): Int {
        return remotePrefs?.getInt(KEY_BOTTOM_HIGHLIGHT, 100)?.coerceIn(0, 100) ?: 100
    }

    /** 0 follows the system; 1 and 2 explicitly select light and dark material. */
    fun getKeyboardColorMode(): Int {
        return remotePrefs?.getInt(KEY_COLOR_MODE, 0)?.coerceIn(0, 2) ?: 0
    }

    fun isKeyboardSurfaceDark(systemDark: Boolean): Boolean = when (getKeyboardColorMode()) {
        1 -> false
        2 -> true
        else -> systemDark
    }

    /** Keyboard color mode controls a native HyperMaterial token, not a solid background. */
    fun getBgType(): Int = 0

    fun getBgColor(): String {
        return if (getKeyboardColorMode() == 2) "#1D1D1F" else "#F7F7F7"
    }

    fun getTextColor(): String {
        return ""
    }

    fun getFunctionKeycapColor(): String {
        return ""
    }

    fun getMenuCardColor(): String {
        return ""
    }

    fun getLetterKeycapColor(): String {
        return ""
    }

    fun getBgImageVersion(): Long {
        return 0L
    }

    fun isVerboseLogEnabled(): Boolean {
        return false
    }
}

object HookTools {
    private const val TAG = "XiaoAiTypeUnblock"

    fun log(module: XposedInterface, msg: String) {
        module.log(Log.INFO, TAG, msg)
        Log.i(TAG, msg)
    }

    fun logWarn(module: XposedInterface, msg: String) {
        module.log(Log.WARN, TAG, msg)
        Log.w(TAG, msg)
    }

    fun logError(module: XposedInterface, msg: String, tr: Throwable? = null) {
        if (tr != null) {
            module.log(Log.ERROR, TAG, msg, tr)
            Log.e(TAG, msg, tr)
        } else {
            module.log(Log.ERROR, TAG, msg)
            Log.e(TAG, msg)
        }
    }

    fun findClass(className: String, classLoader: ClassLoader): Class<*>? {
        return try {
            Class.forName(className, false, classLoader)
        } catch (t: Throwable) {
            null
        }
    }

    fun findMethodExact(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method? {
        return try {
            clazz.getDeclaredMethod(methodName, *parameterTypes).apply {
                isAccessible = true
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun findFirstMethodByParamTypes(clazz: Class<*>, returnType: Class<*>?, vararg parameterTypes: Class<*>): Method? {
        for (m in clazz.declaredMethods) {
            if (returnType != null && m.returnType != returnType) continue
            val types = m.parameterTypes
            if (types.size == parameterTypes.size) {
                var match = true
                for (i in types.indices) {
                    if (types[i] != parameterTypes[i]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    m.isAccessible = true
                    return m
                }
            }
        }
        return null
    }

    fun getObjectField(obj: Any, fieldName: String): Any? {
        var currentClass: Class<*>? = obj.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            try {
                val field = currentClass.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(obj)
            } catch (_: NoSuchFieldException) {
                currentClass = currentClass.superclass
            } catch (t: Throwable) {
                return null
            }
        }
        return null
    }

    fun setObjectField(obj: Any, fieldName: String, value: Any?) {
        var currentClass: Class<*>? = obj.javaClass
        while (currentClass != null && currentClass != Any::class.java) {
            try {
                val field = currentClass.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(obj, value)
                return
            } catch (_: NoSuchFieldException) {
                currentClass = currentClass.superclass
            } catch (_: Throwable) {
                return
            }
        }
    }
}

/**
 * Reuses the HyperMaterial profile that Xiaomi's IME reserves for the global
 * search page.  The IME resets that profile in three lifecycle callbacks, so
 * each of them must be covered instead of changing only onStartInputView.
 */
object SearchPageAppearance {
    private const val IME_SERVICE_CLASS = "com.mi.ime.MiInputMethodService"
    // 0.2.701 moved the HyperMaterial implementation from bb.t to bb.u.
    // bb.t is still the OutlineProvider in the newer build, so keep this
    // lookup separate from the outline hook below.
    private val HYPER_MATERIAL_HELPER_CLASSES = arrayOf("bb.u", "bb.t")
    private const val BLUR_CAPABILITY_CLASS = "xe.b"
    private const val QUICK_SEARCH_PACKAGE = "com.android.quicksearchbox"
    private const val HYPER_MATERIAL_HELPER_GETTER =
        "getHyperMaterialHelper\$app_iflytekFullRelease"
    private const val REFRESH_METHOD = "k"
    private const val SUPPORTED_MATERIAL_VERSION = 2
    private val installed = AtomicBoolean(false)

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return

        val serviceClass = HookTools.findClass(IME_SERVICE_CLASS, classLoader)
        if (serviceClass == null) {
            installed.set(false)
            HookTools.logWarn(module, "[Search Appearance] MiInputMethodService is unavailable")
            return
        }

        var hookCount = installMaterialEligibilityHooks(module, classLoader)
        val inputStarted = HookTools.findMethodExact(
            serviceClass,
            "onStartInput",
            EditorInfo::class.java,
            Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE,
        )
        val inputViewStarted = HookTools.findMethodExact(
            serviceClass,
            "onStartInputView",
            EditorInfo::class.java,
            Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE,
        )
        val windowShown = HookTools.findMethodExact(serviceClass, "onWindowShown")

        listOf(inputStarted, inputViewStarted).filterNotNull().forEach { method ->
            module.hook(method).intercept { chain ->
                if (!HookConfig.isGlobalSearchAppearanceEnabled()) return@intercept chain.proceed()
                val editorInfo = chain.getArg(0) as? EditorInfo
                if (editorInfo == null) return@intercept chain.proceed()

                val originalPackage = editorInfo.packageName
                editorInfo.packageName = QUICK_SEARCH_PACKAGE
                try {
                    // onStartInputView() calls the helper's k() before returning. Prepare the
                    // real helper before that call instead of relying on a late refresh.
                    prepareServiceMaterial(chain.thisObject)
                    chain.proceed()
                } finally {
                    editorInfo.packageName = originalPackage
                    applySearchMaterial(module, chain.thisObject)
                }
            }
            hookCount++
        }
        if (windowShown != null) {
            module.hook(windowShown).intercept { chain ->
                val result = chain.proceed()
                if (HookConfig.isGlobalSearchAppearanceEnabled()) {
                    applySearchMaterial(module, chain.thisObject)
                }
                result
            }
            hookCount++
        }

        if (hookCount == 0) {
            installed.set(false)
            HookTools.logWarn(module, "[Search Appearance] No compatible input lifecycle method was found")
        } else {
            HookTools.log(module, "[Search Appearance] Hooked $hookCount IME lifecycle methods")
        }
    }

    private fun applySearchMaterial(module: XposedInterface, service: Any?) {
        if (service == null || !HookConfig.isGlobalSearchAppearanceEnabled()) return
        runCatching {
            val getter = service.javaClass.methods.firstOrNull {
                it.name == HYPER_MATERIAL_HELPER_GETTER && it.parameterCount == 0
            } ?: return
            val helper = getter.invoke(service) ?: return
            prepareMaterialEligibility(helper)
            helper.javaClass.getDeclaredMethod(REFRESH_METHOD).apply {
                isAccessible = true
            }.invoke(helper)
        }.onFailure { error ->
            HookTools.logError(module, "[Search Appearance] Could not refresh HyperMaterial state", error)
        }
    }

    private fun prepareServiceMaterial(service: Any?) {
        if (service == null) return
        runCatching {
            val getter = service.javaClass.methods.firstOrNull {
                it.name == HYPER_MATERIAL_HELPER_GETTER && it.parameterCount == 0
            } ?: return
            prepareMaterialEligibility(getter.invoke(service))
        }
    }

    /**
     * Current IME builds reset the capability flag and re-evaluate the cloud
     * package list from several lifecycle paths. Intercepting the common
     * evaluator ensures the material view is created during that original
     * lifecycle pass instead of only marking it enabled after the fact.
     */
    private fun installMaterialEligibilityHooks(module: XposedInterface, classLoader: ClassLoader): Int {
        var hookCount = 0

        val helperClass = findMaterialHelperClass(classLoader)
        val refresh = helperClass?.let { HookTools.findMethodExact(it, REFRESH_METHOD) }
        if (refresh != null) {
            module.hook(refresh).intercept { chain ->
                if (HookConfig.isGlobalSearchAppearanceEnabled()) {
                    prepareMaterialEligibility(chain.thisObject)
                }
                chain.proceed()
            }
            hookCount++
        } else {
            HookTools.logWarn(module, "[Search Appearance] HyperMaterial helper k() is unavailable")
        }

        val blurClass = HookTools.findClass(BLUR_CAPABILITY_CLASS, classLoader)
        val isBlurSupported = blurClass?.let { HookTools.findMethodExact(it, "c") }
        if (isBlurSupported != null) {
            module.hook(isBlurSupported).intercept { chain ->
                if (HookConfig.isGlobalSearchAppearanceEnabled()) true else chain.proceed()
            }
            hookCount++
        } else {
            HookTools.logWarn(module, "[Search Appearance] xe.b.c() is unavailable")
        }

        val isBlurEnabled = blurClass?.let {
            HookTools.findMethodExact(it, "b", Context::class.java)
        }
        if (isBlurEnabled != null) {
            module.hook(isBlurEnabled).intercept { chain ->
                if (HookConfig.isGlobalSearchAppearanceEnabled()) true else chain.proceed()
            }
            hookCount++
        } else {
            HookTools.logWarn(module, "[Search Appearance] xe.b.b(Context) is unavailable")
        }

        return hookCount
    }

    private fun prepareMaterialEligibility(helper: Any?) {
        if (helper == null) return

        setHelperFieldCompat(helper, true, "f3551u", "f3472t", "u", "t")
        setHelperFieldCompat(helper, QUICK_SEARCH_PACKAGE, "f3552v", "f3473u", "v", "u")

        val currentVersions = getHelperFieldCompat(helper, "f3553w", "f3474v", "w", "v") as? Map<*, *>
        val updatedVersions = LinkedHashMap<Any?, Any?>()
        currentVersions?.forEach { (packageName, version) ->
            updatedVersions[packageName] = version
        }
        updatedVersions[QUICK_SEARCH_PACKAGE] = SUPPORTED_MATERIAL_VERSION
        setHelperFieldCompat(helper, updatedVersions, "f3553w", "f3474v", "w", "v")

        // HyperMaterial k() treats these as explicit theme overrides. Add the search
        // package to the set matching the current IME configuration so the
        // global appearance follows the device theme instead of falling back
        // to the stock whitelist decision.
        val context = getHelperFieldCompat(helper, "f3536a", "f3458a", "a") as? Context
        val systemDark = context?.resources?.configuration?.uiMode?.let {
            (it and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        } ?: false
        val dark = HookConfig.isKeyboardSurfaceDark(systemDark)
        val darkPackages = LinkedHashSet<Any?>()
        (getHelperFieldCompat(helper, "f3554x", "f3475w", "x", "w") as? Set<*>)?.forEach {
            darkPackages.add(it)
        }
        val lightPackages = LinkedHashSet<Any?>()
        (getHelperFieldCompat(helper, "f3555y", "f3476x", "y", "x") as? Set<*>)?.forEach {
            lightPackages.add(it)
        }
        darkPackages.remove(QUICK_SEARCH_PACKAGE)
        lightPackages.remove(QUICK_SEARCH_PACKAGE)
        if (dark) darkPackages.add(QUICK_SEARCH_PACKAGE) else lightPackages.add(QUICK_SEARCH_PACKAGE)
        setHelperFieldCompat(helper, darkPackages, "f3554x", "f3475w", "x", "w")
        setHelperFieldCompat(helper, lightPackages, "f3555y", "f3476x", "y", "x")
    }

    private fun findMaterialHelperClass(classLoader: ClassLoader): Class<*>? =
        HYPER_MATERIAL_HELPER_CLASSES
            .mapNotNull { HookTools.findClass(it, classLoader) }
            .firstOrNull { clazz ->
                // The old APK also contains bb.u, but that class is only a
                // synthetic ThreadFactory. The helper always owns k().
                clazz.declaredMethods.any { it.name == REFRESH_METHOD && it.parameterCount == 0 }
            }

    private fun getHelperFieldCompat(helper: Any, vararg names: String): Any? =
        names.firstNotNullOfOrNull { HookTools.getObjectField(helper, it) }

    private fun setHelperFieldCompat(helper: Any, value: Any?, vararg names: String): Boolean {
        for (name in names) {
            var current: Class<*>? = helper.javaClass
            while (current != null && current != Any::class.java) {
                try {
                    current.getDeclaredField(name).apply {
                        isAccessible = true
                        set(helper, value)
                    }
                    return true
                } catch (_: NoSuchFieldException) {
                    current = current.superclass
                } catch (_: Throwable) {
                    break
                }
            }
        }
        return false
    }
}

object AiGuard {
    private val SAFETY_BLOCKED_PATTERN = Pattern.compile("\"safety_blocked\"\\s*:\\s*true", Pattern.CASE_INSENSITIVE)

    fun sanitizeJson(input: String?): String? {
        if (!HookConfig.isAiSafetyEnabled()) return input
        if (input == null || !input.contains("safety_blocked")) return input
        val matcher = SAFETY_BLOCKED_PATTERN.matcher(input)
        if (matcher.find()) {
            return matcher.replaceAll("\"safety_blocked\": false")
        }
        return input
    }

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        val fbSClass = HookTools.findClass("fb.s", classLoader)
        if (fbSClass != null) {
            // Hook h(String) -> static
            val methodH = HookTools.findMethodExact(fbSClass, "h", String::class.java)
            if (methodH != null) {
                try {
                    module.hook(methodH).intercept { chain ->
                        if (!HookConfig.isAiSafetyEnabled()) return@intercept chain.proceed()
                        val originalJson = chain.getArg(0) as? String
                        val sanitized = sanitizeJson(originalJson)
                        if (sanitized != originalJson) {
                            if (HookConfig.isVerboseLogEnabled()) {
                                HookTools.log(module, "[AI Safety] Sanitized safety_blocked in fb.s.h()")
                            }
                            chain.proceed(arrayOf(sanitized))
                        } else {
                            chain.proceed()
                        }
                    }
                    HookTools.log(module, "[AI Safety] Hooked fb.s.h(String)")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook fb.s.h", t)
                }
            }

            // Hook e(String) -> instance
            val methodE = HookTools.findMethodExact(fbSClass, "e", String::class.java)
            if (methodE != null) {
                try {
                    module.hook(methodE).intercept { chain ->
                        if (!HookConfig.isAiSafetyEnabled()) return@intercept chain.proceed()
                        val originalJson = chain.getArg(0) as? String
                        val sanitized = sanitizeJson(originalJson)
                        if (sanitized != originalJson) {
                            if (HookConfig.isVerboseLogEnabled()) {
                                HookTools.log(module, "[AI Safety] Sanitized safety_blocked in fb.s.e()")
                            }
                            chain.proceed(arrayOf(sanitized))
                        } else {
                            chain.proceed()
                        }
                    }
                    HookTools.log(module, "[AI Safety] Hooked fb.s.e(String)")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook fb.s.e", t)
                }
            }

            // Hook f(String) -> instance (streaming parser)
            val methodF = HookTools.findMethodExact(fbSClass, "f", String::class.java)
            if (methodF != null) {
                try {
                    module.hook(methodF).intercept { chain ->
                        if (!HookConfig.isAiSafetyEnabled()) return@intercept chain.proceed()
                        val originalJson = chain.getArg(0) as? String
                        val sanitized = sanitizeJson(originalJson)
                        if (sanitized != originalJson) {
                            if (HookConfig.isVerboseLogEnabled()) {
                                HookTools.log(module, "[AI Safety] Sanitized safety_blocked in fb.s.f()")
                            }
                            chain.proceed(arrayOf(sanitized))
                        } else {
                            chain.proceed()
                        }
                    }
                    HookTools.log(module, "[AI Safety] Hooked fb.s.f(String)")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook fb.s.f", t)
                }
            }
        } else {
            HookTools.logWarn(module, "[AI Safety] Class fb.s not found, skipping specific fb.s hooks")
        }

        // Translation safety refusal hook (aa.b6)
        val b6Class = HookTools.findClass("aa.b6", classLoader)
        if (b6Class != null) {
            val methodM = HookTools.findFirstMethodByParamTypes(b6Class, null, Any::class.java, HookTools.findClass("sc.c", classLoader) ?: Any::class.java)
            if (methodM != null) {
                HookTools.log(module, "[AI Safety] Translation flow handler registered")
            }
        }
    }
}

/**
 * Removes MIUIFrequentPhrase's expiry, item-count and text-length limits.
 *
 * com.miui.phrase persists the canonical list, while com.xiaomi.type loads the
 * same APK classes for its clipboard panel. This hook is therefore installed in
 * both processes (when the classes are present).
 */
object ClipboardArchive {
    private const val MANAGER_CLASS = "com.miui.inputmethod.MiuiClipboardManager"
    private const val STORAGE_CLASS = "N1.c"
    private const val PROVIDER_CLASS = "com.miui.provider.InputProvider"
    private const val CLIPBOARD_URI = "content://com.miui.phrase.input.provider/getClipboardList"
    private const val CLIPBOARD_PROVIDER_URI = "content://com.miui.phrase.input.provider"
    private const val SAVE_CLIPBOARD_METHOD = "saveClipboardCipherText"
    private const val PERMANENT_REQUEST_KEY = "xatype_permanent"
    private const val PHRASE_PACKAGE = "com.miui.phrase"
    private const val IME_SERVICE_CLASS = "com.mi.ime.MiInputMethodService"
    private const val POPUP_CLASS = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"
    private const val POPUP_INIT_TASK_CLASS = "com.miui.inputmethod.InputMethodClipboardPhrasePopupView\$2"
    private const val HEADER_ADAPTER_CLASS = "com.miui.inputmethod.InputMethodClipboardHeaderAdapter"
    private const val UNLIMITED_TIP = "剪贴板内容永久保存，不限制条数与文字长度"
    private val installedManagerClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val classLoaderWatcherInstalled = AtomicBoolean(false)
    private val phraseContextLoaderHookInstalled = AtomicBoolean(false)
    private val readHookHitLogged = AtomicBoolean(false)
    private val mergeHookHitLogged = AtomicBoolean(false)
    private val writeHookHitLogged = AtomicBoolean(false)
    private val providerCallHookHitLogged = AtomicBoolean(false)
    private val disabledCallbackLogged = AtomicBoolean(false)
    private val popupInitHookHitLogged = AtomicBoolean(false)
    private val frameworkBridgeInstalled = AtomicBoolean(false)

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        installFrameworkBridge(module)
        val managerClass = HookTools.findClass(MANAGER_CLASS, classLoader)
        if (managerClass == null) {
            installPhraseContextLoaderHook(module, classLoader)
            installClassLoaderWatcher(module)
            HookTools.logWarn(
                module,
                "[Permanent Clipboard] $MANAGER_CLASS is not loaded yet; waiting for dynamic class loader"
            )
            return
        }

        installForManagerClass(module, managerClass)
    }

    private fun installFrameworkBridge(module: XposedInterface) {
        if (!frameworkBridgeInstalled.compareAndSet(false, true)) return

        ContentResolver::class.java.declaredMethods
            .filter {
                it.name == "call" && it.parameterTypes.contentEquals(
                    arrayOf(
                        Uri::class.java,
                        String::class.java,
                        String::class.java,
                        Bundle::class.java
                    )
                )
            }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val uri = chain.getArg(0) as? Uri
                    val callMethod = chain.getArg(1) as? String
                    if (
                        HookConfig.isClipboardPermanentEnabled() &&
                        uri?.authority == "com.miui.phrase.input.provider" &&
                        callMethod == SAVE_CLIPBOARD_METHOD
                    ) {
                        val args = chain.args.toTypedArray()
                        args[3] = Bundle(chain.getArg(3) as? Bundle ?: Bundle()).apply {
                            putBoolean(PERMANENT_REQUEST_KEY, true)
                        }
                        return@intercept chain.proceed(args)
                    }
                    chain.proceed()
                }
            }

        Handler::class.java.declaredMethods
            .filter {
                it.name == "post" && it.parameterTypes.contentEquals(
                    arrayOf(Runnable::class.java)
                )
            }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val task = chain.getArg(0) as? Runnable ?: return@intercept chain.proceed()
                    if (
                        HookConfig.isClipboardPermanentEnabled() &&
                        task.javaClass.name == POPUP_INIT_TASK_CLASS
                    ) {
                        val args = chain.args.toTypedArray()
                        args[0] = Runnable {
                            task.run()
                            restorePopupHistory(module, task)
                        }
                        return@intercept chain.proceed(args)
                    }
                    chain.proceed()
                }
            }
    }

    private fun restorePopupHistory(module: XposedInterface, task: Runnable) {
        try {
            val popup = HookTools.getObjectField(task, "this\$0") ?: return
            val context = HookTools.getObjectField(popup, "mContext") as? Context ?: return
            val loader = task.javaClass.classLoader ?: return
            val managerClass = Class.forName(MANAGER_CLASS, false, loader)
            val complete = queryModels(context, managerClass)
            val display = managerClass.getDeclaredMethod(
                "buildRecyclerViewDisplayList",
                List::class.java
            ).apply { isAccessible = true }.invoke(null, complete) as? List<*> ?: complete
            HookTools.setObjectField(popup, "mAllClipboardList", complete)
            HookTools.setObjectField(popup, "mCurrentImeClipboardList", display)
            managerClass.getDeclaredMethod(
                "setClipboardModelList",
                Context::class.java,
                List::class.java
            ).apply { isAccessible = true }.invoke(null, context, display)
            if (popupInitHookHitLogged.compareAndSet(false, true)) {
                HookTools.log(
                    module,
                    "[Permanent Clipboard] Framework popup bridge active; preservedCount=${display.size}"
                )
            }
        } catch (t: Throwable) {
            HookTools.logError(module, "[Permanent Clipboard] Framework popup bridge failed", t)
        }
    }

    private fun installForManagerClass(module: XposedInterface, managerClass: Class<*>) {
        val classLoader = managerClass.classLoader ?: return
        if (!installedManagerClasses.add(managerClass)) return

        installTextLengthHooks(module, managerClass)
        installCleanupHooks(module, managerClass)
        installPublicReadHook(module, managerClass)
        installReadHooks(module, managerClass)
        installMergeHooks(module, managerClass)
        installOutboundSaveHook(module, managerClass)
        installDisplayListWriteHook(module, managerClass)
        installProviderCallHook(module, classLoader, managerClass)
        installProviderEntryHook(module, classLoader, managerClass)
        installProviderWriteHook(module, classLoader, managerClass)
        installPopupHooks(module, classLoader)
        installPopupInitTaskHook(module, classLoader, managerClass)
        installHeaderHook(module, classLoader)
        deoptimizeCallers(module, managerClass, classLoader)
        HookTools.log(
            module,
            "[Permanent Clipboard] Unlimited clipboard hooks installed; enabled=${HookConfig.isClipboardPermanentEnabled()}"
        )
    }

    private fun installPhraseContextLoaderHook(module: XposedInterface, classLoader: ClassLoader) {
        if (!phraseContextLoaderHookInstalled.compareAndSet(false, true)) return
        val serviceClass = HookTools.findClass(IME_SERVICE_CLASS, classLoader) ?: return
        serviceClass.declaredMethods
            .filter { it.name == "onCreate" && it.parameterTypes.isEmpty() }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val context = chain.thisObject as? Context
                    if (context != null) {
                        try {
                            refreshConfig(context)
                            val phraseContext = context.createPackageContext(
                                PHRASE_PACKAGE,
                                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                            )
                            install(module, phraseContext.classLoader)
                        } catch (t: Throwable) {
                            HookTools.logError(
                                module,
                                "[Permanent Clipboard] Failed to obtain phrase package class loader",
                                t
                            )
                        }
                    }
                    result
                }
            }
    }

    private fun installClassLoaderWatcher(module: XposedInterface) {
        if (!classLoaderWatcherInstalled.compareAndSet(false, true)) return

        ClassLoader::class.java.declaredMethods
            .filter { method ->
                method.name == "loadClass" && method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == String::class.java
            }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val loadedClass = chain.proceed()
                    if (chain.getArg(0) == MANAGER_CLASS && loadedClass is Class<*>) {
                        try {
                            installForManagerClass(module, loadedClass)
                        } catch (t: Throwable) {
                            HookTools.logError(
                                module,
                                "[Permanent Clipboard] Failed to install hooks for dynamic class loader",
                                t
                            )
                        }
                    }
                    loadedClass
                }
            }
    }

    private fun installTextLengthHooks(module: XposedInterface, managerClass: Class<*>) {
        setUnlimitedTextLength(managerClass)
        managerClass.declaredMethods
            .filter { it.name == "init" && it.parameterTypes.isEmpty() }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    refreshConfig(HookTools.getObjectField(chain.thisObject, "mContext") as? Context)
                    val result = chain.proceed()
                    if (HookConfig.isClipboardPermanentEnabled()) {
                        setUnlimitedTextLength(managerClass)
                    }
                    result
                }
            }
    }

    private fun setUnlimitedTextLength(managerClass: Class<*>) {
        if (!HookConfig.isClipboardPermanentEnabled()) return
        try {
            managerClass.getDeclaredField("MAX_CLIP_CONTENT_SIZE").apply {
                isAccessible = true
                setInt(null, Int.MAX_VALUE)
            }
        } catch (_: Throwable) {
        }
    }

    private fun installCleanupHooks(module: XposedInterface, managerClass: Class<*>) {
        managerClass.declaredMethods
            .filter {
                it.name == "clearOldClipboardNew" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Context::class.java
            }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    refreshConfig(chain.getArg(0) as? Context)
                    if (HookConfig.isClipboardPermanentEnabled()) null else chain.proceed()
                }
            }
    }

    private fun installReadHooks(
        module: XposedInterface,
        managerClass: Class<*>
    ) {
        val method = managerClass.declaredMethods.firstOrNull {
            it.name == "getNoExpiredClipboardData" && it.parameterTypes.size == 3
        } ?: return
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            refreshConfig(chain.getArg(0) as? Context)
            if (!HookConfig.isClipboardPermanentEnabled()) return@intercept chain.proceed()
            try {
                val completeHistory = parseModels(managerClass, chain.getArg(1) as? String ?: "")
                if (readHookHitLogged.compareAndSet(false, true)) {
                    HookTools.log(
                        module,
                        "[Permanent Clipboard] Read hook active; preservedCount=${completeHistory.size}"
                    )
                }
                completeHistory
            } catch (t: Throwable) {
                HookTools.logError(module, "[Permanent Clipboard] Failed to read complete history", t)
                chain.proceed()
            }
        }
    }

    private fun installPublicReadHook(
        module: XposedInterface,
        managerClass: Class<*>
    ) {
        val method = managerClass.declaredMethods.firstOrNull {
            it.name == "getClipboardData" && it.parameterTypes.contentEquals(
                arrayOf(Context::class.java)
            )
        } ?: return
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val context = chain.getArg(0) as? Context ?: return@intercept chain.proceed()
            refreshConfig(context)
            if (!HookConfig.isClipboardPermanentEnabled()) {
                logDisabledCallback(module, "public read")
                return@intercept chain.proceed()
            }

            try {
                val completeHistory = queryModels(context, managerClass)
                if (readHookHitLogged.compareAndSet(false, true)) {
                    HookTools.log(
                        module,
                        "[Permanent Clipboard] Public read hook active; preservedCount=${completeHistory.size}"
                    )
                }
                completeHistory
            } catch (t: Throwable) {
                HookTools.logError(module, "[Permanent Clipboard] Failed to query complete history", t)
                chain.proceed()
            }
        }
    }

    private fun installOutboundSaveHook(
        module: XposedInterface,
        managerClass: Class<*>
    ) {
        val method = managerClass.declaredMethods.firstOrNull {
            it.name == "addClipDataToPhrase" && it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Context::class.java &&
                it.parameterTypes[1] == Boolean::class.javaPrimitiveType
        } ?: return
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val context = chain.getArg(0) as? Context ?: return@intercept chain.proceed()
            refreshConfig(context)
            if (!HookConfig.isClipboardPermanentEnabled()) return@intercept chain.proceed()

            try {
                val newModel = chain.getArg(2) ?: return@intercept chain.proceed()
                if (chain.getArg(1) == true) {
                    val callback = managerClass.getDeclaredField("mClipBoardDataChangeInterface").apply {
                        isAccessible = true
                    }.get(null)
                    callback?.javaClass?.methods?.firstOrNull {
                        it.name == "updateClipBoardData" && it.parameterTypes.size == 1
                    }?.invoke(callback, newModel)
                }
                val merged = LinkedHashSet<Any>().apply {
                    add(newModel)
                    addAll(queryModels(context, managerClass))
                }.sortedByDescending(::modelTime)
                val extras = Bundle().apply {
                    putString("jsonArray", serializeModels(merged))
                    putBoolean(PERMANENT_REQUEST_KEY, true)
                }
                context.contentResolver.call(
                    Uri.parse(CLIPBOARD_PROVIDER_URI),
                    SAVE_CLIPBOARD_METHOD,
                    null,
                    extras
                )
                null
            } catch (t: Throwable) {
                HookTools.logError(module, "[Permanent Clipboard] Failed to send complete history", t)
                chain.proceed()
            }
        }
    }

    private fun installDisplayListWriteHook(
        module: XposedInterface,
        managerClass: Class<*>
    ) {
        val method = managerClass.declaredMethods.firstOrNull {
            it.name == "setClipboardModelList" && it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Context::class.java &&
                List::class.java.isAssignableFrom(it.parameterTypes[1])
        } ?: return
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val context = chain.getArg(0) as? Context ?: return@intercept chain.proceed()
            refreshConfig(context)
            if (!HookConfig.isClipboardPermanentEnabled()) {
                logDisabledCallback(module, "display-list write")
                return@intercept chain.proceed()
            }

            try {
                val displayed = (chain.getArg(1) as? Collection<*>)?.filterNotNull().orEmpty()
                val encodedHistory = context.getSharedPreferences("sp_name_clip_board", 0)
                    .getString("clipboard_cipher_list", "").orEmpty()
                val historyJson = if (encodedHistory.isEmpty()) {
                    ""
                } else {
                    String(Base64.decode(encodedHistory, Base64.DEFAULT), Charsets.UTF_8)
                }
                val merged = LinkedHashSet<Any>().apply {
                    addAll(displayed)
                    addAll(parseModels(managerClass, historyJson))
                }.sortedByDescending(::modelTime)
                val args = chain.args.toTypedArray()
                args[1] = merged
                chain.proceed(args)
            } catch (t: Throwable) {
                HookTools.logError(
                    module,
                    "[Permanent Clipboard] Failed to preserve history during panel refresh",
                    t
                )
                chain.proceed()
            }
        }
    }

    private fun installMergeHooks(
        module: XposedInterface,
        managerClass: Class<*>
    ) {
        val method = managerClass.declaredMethods.firstOrNull {
            it.name == "addContentListToJsonArray" && it.parameterTypes.size == 4
        } ?: return
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            if (!HookConfig.isClipboardPermanentEnabled()) return@intercept chain.proceed()
            try {
                val deviceId = chain.getArg(0) as? String
                val includeTemporary = chain.getArg(1) as? Boolean ?: false
                val incoming = (chain.getArg(2) as? Collection<*>)?.filterNotNull().orEmpty()
                val existing = parseModels(managerClass, chain.getArg(3) as? String ?: "")
                val merged = LinkedHashSet<Any>().apply {
                    addAll(incoming)
                    addAll(existing)
                }.sortedByDescending(::modelTime).filter { model ->
                    includeTemporary || !modelBoolean(model, "isTemp") ||
                        deviceId.isNullOrEmpty() || deviceId != modelString(model, "getDeviceId")
                }
                if (mergeHookHitLogged.compareAndSet(false, true)) {
                    HookTools.log(
                        module,
                        "[Permanent Clipboard] Merge hook active; preservedCount=${merged.size}"
                    )
                }
                serializeModels(merged)
            } catch (t: Throwable) {
                HookTools.logError(module, "[Permanent Clipboard] Failed to merge complete history", t)
                chain.proceed()
            }
        }
    }

    private fun installProviderWriteHook(
        module: XposedInterface,
        classLoader: ClassLoader,
        managerClass: Class<*>
    ) {
        val storageClass = HookTools.findClass(STORAGE_CLASS, classLoader) ?: return
        val method = storageClass.declaredMethods.firstOrNull {
            it.name == "f" && it.returnType == String::class.java &&
                it.parameterTypes.map(Class<*>::getName) == listOf(
                    "android.content.Context",
                    "android.database.sqlite.SQLiteDatabase",
                    "com.miui.inputmethod.ClipboardContentModel",
                    "java.lang.String"
                )
        } ?: return
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            refreshConfig(chain.getArg(0) as? Context)
            if (!HookConfig.isClipboardPermanentEnabled()) return@intercept chain.proceed()
            try {
                val newModel = chain.getArg(2) ?: return@intercept chain.proceed()
                val existing = parseModels(managerClass, chain.getArg(3) as? String ?: "")
                val merged = LinkedHashSet<Any>().apply {
                    add(newModel)
                    addAll(existing)
                }.sortedByDescending(::modelTime)
                if (writeHookHitLogged.compareAndSet(false, true)) {
                    HookTools.log(
                        module,
                        "[Permanent Clipboard] Write hook active; preservedCount=${merged.size}"
                    )
                }
                serializeModels(merged)
            } catch (t: Throwable) {
                HookTools.logError(module, "[Permanent Clipboard] Failed to persist complete history", t)
                chain.proceed()
            }
        }
    }

    private fun installPopupHooks(module: XposedInterface, classLoader: ClassLoader) {
        val popupClass = HookTools.findClass(POPUP_CLASS, classLoader) ?: return
        popupClass.declaredMethods
            .filter {
                (it.name == "lambda\$updateClipboardData\$8" && it.parameterTypes.size == 1) ||
                    (it.name == "lambda\$setRemoteDataToView\$5" && it.parameterTypes.isEmpty())
            }
            .forEach { method -> installListPreservationHook(module, method) }
    }

    private fun installListPreservationHook(module: XposedInterface, method: Method) {
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            if (!HookConfig.isClipboardPermanentEnabled()) return@intercept chain.proceed()
            val popup = chain.thisObject
            val before = adapterList(popup)?.toList().orEmpty()
            val result = chain.proceed()
            val current = adapterList(popup)
            if (current != null) {
                before.forEach { item ->
                    if (!current.contains(item)) current.add(item)
                }
                HookTools.setObjectField(popup, "mCurrentImeClipboardList", current)
            }
            result
        }
    }

    private fun installHeaderHook(module: XposedInterface, classLoader: ClassLoader) {
        val adapterClass = HookTools.findClass(HEADER_ADAPTER_CLASS, classLoader) ?: return
        adapterClass.declaredMethods
            .filter { it.name == "onBindViewHolder" && it.parameterTypes.size == 2 }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    if (HookConfig.isClipboardPermanentEnabled()) {
                        val holder = chain.getArg(0)
                        (holder?.let { HookTools.getObjectField(it, "tipTextView") } as? TextView)
                            ?.text = UNLIMITED_TIP
                    }
                    result
                }
            }
    }

    private fun parseModels(managerClass: Class<*>, json: String): ArrayList<Any> {
        val method = managerClass.getDeclaredMethod("jsonToBeanList", String::class.java).apply {
            isAccessible = true
        }
        val parsed = method.invoke(null, json) as? Collection<*> ?: return arrayListOf()
        return ArrayList(parsed.filterNotNull())
    }

    private fun serializeModels(models: Collection<Any>): String {
        val array = JSONArray()
        models.forEach { model ->
            val method = model.javaClass.getMethod("toJSONObject")
            array.put(method.invoke(model))
        }
        return array.toString()
    }

    private fun modelTime(model: Any): Long =
        (model.javaClass.getMethod("getTime").invoke(model) as? Number)?.toLong() ?: Long.MIN_VALUE

    private fun modelBoolean(model: Any, methodName: String): Boolean =
        model.javaClass.getMethod(methodName).invoke(model) as? Boolean ?: false

    private fun modelString(model: Any, methodName: String): String? =
        model.javaClass.getMethod(methodName).invoke(model) as? String

    @Suppress("UNCHECKED_CAST")
    private fun adapterList(popup: Any): MutableList<Any>? {
        val adapter = HookTools.getObjectField(popup, "mInputMethodClipboardAdapter") ?: return null
        val method = adapter.javaClass.methods.firstOrNull {
            it.name == "getAdapterList" && it.parameterTypes.isEmpty()
        } ?: return null
        return method.invoke(adapter) as? MutableList<Any>
    }

    private fun installPopupInitTaskHook(
        module: XposedInterface,
        classLoader: ClassLoader,
        managerClass: Class<*>
    ) {
        val taskClass = HookTools.findClass(POPUP_INIT_TASK_CLASS, classLoader) ?: return
        taskClass.declaredMethods
            .filter { it.name == "run" && it.parameterTypes.isEmpty() }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val popup = HookTools.getObjectField(chain.thisObject, "this\$0")
                        ?: return@intercept result
                    val context = HookTools.getObjectField(popup, "mContext") as? Context
                        ?: return@intercept result
                    refreshConfig(context)
                    if (!HookConfig.isClipboardPermanentEnabled()) return@intercept result

                    try {
                        val complete = queryModels(context, managerClass)
                        val buildDisplay = managerClass.getDeclaredMethod(
                            "buildRecyclerViewDisplayList",
                            List::class.java
                        ).apply { isAccessible = true }
                        @Suppress("UNCHECKED_CAST")
                        val display = buildDisplay.invoke(null, complete) as? List<Any> ?: complete
                        HookTools.setObjectField(popup, "mAllClipboardList", complete)
                        HookTools.setObjectField(popup, "mCurrentImeClipboardList", display)

                        managerClass.getDeclaredMethod(
                            "setClipboardModelList",
                            Context::class.java,
                            List::class.java
                        ).apply { isAccessible = true }.invoke(null, context, display)
                        if (popupInitHookHitLogged.compareAndSet(false, true)) {
                            HookTools.log(
                                module,
                                "[Permanent Clipboard] Popup initialization hook active; preservedCount=${display.size}"
                            )
                        }
                    } catch (t: Throwable) {
                        HookTools.logError(
                            module,
                            "[Permanent Clipboard] Failed to restore complete popup history",
                            t
                        )
                    }
                    result
                }
            }
    }

    private fun installProviderEntryHook(
        module: XposedInterface,
        classLoader: ClassLoader,
        managerClass: Class<*>
    ) {
        val storageClass = HookTools.findClass(STORAGE_CLASS, classLoader) ?: return
        val method = storageClass.declaredMethods.firstOrNull {
            it.name == "v" && it.returnType == Bundle::class.java &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == Context::class.java &&
                it.parameterTypes[2] == Bundle::class.java
        } ?: return
        val modelClass = HookTools.findClass(
            "com.miui.inputmethod.ClipboardContentModel",
            classLoader
        ) ?: return
        val fromJson = modelClass.getMethod("fromJSONObject", JSONObject::class.java)
        method.isAccessible = true
        module.hook(method).intercept { chain ->
            val context = chain.getArg(0) as? Context ?: return@intercept chain.proceed()
            refreshConfig(context)
            if (!HookConfig.isClipboardPermanentEnabled()) return@intercept chain.proceed()

            try {
                val extras = chain.getArg(2) as? Bundle ?: return@intercept chain.proceed()
                val rewritten = rewriteClipboardWrite(context, extras, managerClass, fromJson)
                    ?: return@intercept chain.proceed()
                val args = chain.args.toTypedArray()
                args[2] = rewritten.first
                if (writeHookHitLogged.compareAndSet(false, true)) {
                    HookTools.log(
                        module,
                        "[Permanent Clipboard] Provider entry hook active; preservedCount=${rewritten.second}"
                    )
                }
                chain.proceed(args)
            } catch (t: Throwable) {
                HookTools.logError(
                    module,
                    "[Permanent Clipboard] Failed to rewrite provider clipboard write",
                    t
                )
                chain.proceed()
            }
        }
    }

    private fun installProviderCallHook(
        module: XposedInterface,
        classLoader: ClassLoader,
        managerClass: Class<*>
    ) {
        val providerClass = HookTools.findClass(PROVIDER_CLASS, classLoader) ?: return
        val modelClass = HookTools.findClass(
            "com.miui.inputmethod.ClipboardContentModel",
            classLoader
        ) ?: return
        val fromJson = modelClass.getMethod("fromJSONObject", JSONObject::class.java)
        providerClass.declaredMethods
            .filter {
                it.name == "call" && it.parameterTypes.contentEquals(
                    arrayOf(String::class.java, String::class.java, Bundle::class.java)
                )
            }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    if (chain.getArg(0) != SAVE_CLIPBOARD_METHOD) return@intercept chain.proceed()
                    val context = (chain.thisObject as? ContentProvider)?.context
                        ?: return@intercept chain.proceed()
                    val extras = chain.getArg(2) as? Bundle ?: return@intercept chain.proceed()
                    refreshConfig(context)
                    val permanentRequested = extras.getBoolean(PERMANENT_REQUEST_KEY, false)
                    if (!permanentRequested && !HookConfig.isClipboardPermanentEnabled()) {
                        logDisabledCallback(module, "provider call")
                        return@intercept chain.proceed()
                    }

                    try {
                        val rewritten = rewriteClipboardWrite(context, extras, managerClass, fromJson)
                        if (rewritten == null) {
                            if (permanentRequested && providerCallHookHitLogged.compareAndSet(false, true)) {
                                HookTools.log(
                                    module,
                                    "[Permanent Clipboard] Provider accepted complete-history request"
                                )
                            }
                            return@intercept chain.proceed()
                        }
                        val args = chain.args.toTypedArray()
                        args[2] = rewritten.first
                        if (providerCallHookHitLogged.compareAndSet(false, true)) {
                            HookTools.log(
                                module,
                                "[Permanent Clipboard] Provider call hook active; preservedCount=${rewritten.second}"
                            )
                        }
                        chain.proceed(args)
                    } catch (t: Throwable) {
                        HookTools.logError(
                            module,
                            "[Permanent Clipboard] Failed to intercept provider clipboard write",
                            t
                        )
                        chain.proceed()
                    }
                }
            }
    }

    private fun rewriteClipboardWrite(
        context: Context,
        extras: Bundle,
        managerClass: Class<*>,
        fromJson: Method
    ): Pair<Bundle, Int>? {
        val singleJson = extras.getString("singleJson")
        if (singleJson.isNullOrEmpty()) return null

        val encodedHistory = context.getSharedPreferences("sp_name_clip_board", 0)
            .getString("clipboard_cipher_list", "").orEmpty()
        val historyJson = if (encodedHistory.isEmpty()) {
            ""
        } else {
            String(Base64.decode(encodedHistory, Base64.DEFAULT), Charsets.UTF_8)
        }
        val newModel = fromJson.invoke(null, JSONObject(singleJson)) ?: return null
        val merged = LinkedHashSet<Any>().apply {
            add(newModel)
            addAll(parseModels(managerClass, historyJson))
        }.sortedByDescending(::modelTime)

        val rewrittenExtras = Bundle(extras).apply {
            remove("singleJson")
            putString("jsonArray", serializeModels(merged))
        }
        return rewrittenExtras to merged.size
    }

    private fun queryModels(context: Context, managerClass: Class<*>): ArrayList<Any> {
        val json = JSONArray()
        context.contentResolver.query(
            Uri.parse(CLIPBOARD_URI),
            null,
            null,
            null,
            null
        )?.use { cursor ->
            val contentColumn = cursor.getColumnIndex("phrase_content")
            if (contentColumn >= 0 && cursor.moveToFirst()) {
                do {
                    json.put(JSONObject(cursor.getString(contentColumn)))
                } while (cursor.moveToNext())
            }
        }
        return parseModels(managerClass, json.toString())
    }

    private fun refreshConfig(context: Context?) {
        if (context != null) HookConfig.syncFromProvider(context)
    }

    private fun logDisabledCallback(module: XposedInterface, source: String) {
        if (disabledCallbackLogged.compareAndSet(false, true)) {
            HookTools.logWarn(
                module,
                "[Permanent Clipboard] $source reached, but the synced feature flag is disabled"
            )
        }
    }

    private fun deoptimizeCallers(
        module: XposedInterface,
        managerClass: Class<*>,
        classLoader: ClassLoader
    ) {
        managerClass.declaredMethods
            .forEach { method ->
                try {
                    module.deoptimize(method)
                } catch (_: Throwable) {
                }
            }

        HookTools.findClass(STORAGE_CLASS, classLoader)?.declaredMethods
            ?.filter { it.name == "v" }
            ?.forEach { method ->
                try {
                    module.deoptimize(method)
                } catch (_: Throwable) {
                }
            }

        HookTools.findClass(PROVIDER_CLASS, classLoader)?.declaredMethods
            ?.filter { it.name == "call" }
            ?.forEach { method ->
                try {
                    module.deoptimize(method)
                } catch (_: Throwable) {
                }
            }

        listOf(
            POPUP_INIT_TASK_CLASS,
            "com.miui.inputmethod.b",
            "A0.d"
        ).forEach { className ->
            HookTools.findClass(className, classLoader)?.declaredMethods
                ?.filter { it.name == "run" }
                ?.forEach { method ->
                    try {
                        module.deoptimize(method)
                    } catch (_: Throwable) {
                    }
                }
        }
    }
}

object ClipboardPrivacy {

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        // Hook PersistableBundle.getBoolean(String, boolean)
        val methodGetBoolean = HookTools.findMethodExact(
            PersistableBundle::class.java,
            "getBoolean",
            String::class.java,
            Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE
        )
        if (methodGetBoolean != null) {
            try {
                module.hook(methodGetBoolean).intercept { chain ->
                    if (!HookConfig.isClipboardSensitiveEnabled()) return@intercept chain.proceed()
                    val key = chain.getArg(0) as? String
                    if ("android.content.extra.IS_SENSITIVE" == key) {
                        if (HookConfig.isVerboseLogEnabled()) {
                            HookTools.log(module, "[Clipboard] Bypassed android.content.extra.IS_SENSITIVE check in PersistableBundle")
                        }
                        false // Never mark as sensitive
                    } else {
                        chain.proceed()
                    }
                }
                HookTools.log(module, "[Clipboard] Hooked PersistableBundle.getBoolean(String, boolean)")
            } catch (t: Throwable) {
                HookTools.logError(module, "Failed to hook PersistableBundle.getBoolean", t)
            }
        }

        // Also hook BaseBundle.getBoolean(String, boolean)
        val methodBaseGetBoolean = HookTools.findMethodExact(
            BaseBundle::class.java,
            "getBoolean",
            String::class.java,
            Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE
        )
        if (methodBaseGetBoolean != null) {
            try {
                module.hook(methodBaseGetBoolean).intercept { chain ->
                    if (!HookConfig.isClipboardSensitiveEnabled()) return@intercept chain.proceed()
                    val key = chain.getArg(0) as? String
                    if ("android.content.extra.IS_SENSITIVE" == key) {
                        if (HookConfig.isVerboseLogEnabled()) {
                            HookTools.log(module, "[Clipboard] Bypassed android.content.extra.IS_SENSITIVE check in BaseBundle")
                        }
                        false
                    } else {
                        chain.proceed()
                    }
                }
                HookTools.log(module, "[Clipboard] Hooked BaseBundle.getBoolean(String, boolean)")
            } catch (t: Throwable) {
                HookTools.logError(module, "Failed to hook BaseBundle.getBoolean", t)
            }
        }
    }
}

object DictionaryGate {

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        // 1. Hook com.iflytek.inputmethod.smartengine.c1.onPyCloudAttachUpdate
        val c1Class = HookTools.findClass("com.iflytek.inputmethod.smartengine.c1", classLoader)
        if (c1Class != null) {
            val methodAttach = HookTools.findMethodExact(
                c1Class,
                "onPyCloudAttachUpdate",
                Int::class.javaPrimitiveType ?: Integer.TYPE,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java
            )
            if (methodAttach != null) {
                try {
                    module.hook(methodAttach).intercept { chain ->
                        if (!HookConfig.isCloudBlacklistEnabled()) return@intercept chain.proceed()
                        val blacklistStr = chain.getArg(1) as? String
                        if (!blacklistStr.isNullOrEmpty()) {
                            if (HookConfig.isVerboseLogEnabled()) {
                                HookTools.log(module, "[Cloud Blacklist] Intercepted and cleared cloud blacklist (len=${blacklistStr.length}) in c1.onPyCloudAttachUpdate")
                            }
                        }
                        // Replace blacklist string and version with empty strings
                        chain.proceed(arrayOf(chain.getArg(0), "", "", chain.getArg(3), chain.getArg(4)))
                    }
                    HookTools.log(module, "[Cloud Blacklist] Hooked c1.onPyCloudAttachUpdate()")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook c1.onPyCloudAttachUpdate", t)
                }
            }
        }

        // 2. Hook com.iflytek.inputmethod.smart.api.entity.PinyinCloudAttachResult
        val attachResultClass = HookTools.findClass("com.iflytek.inputmethod.smart.api.entity.PinyinCloudAttachResult", classLoader)
        if (attachResultClass != null) {
            // Hook getBlackListStr() -> return ""
            val methodGet = HookTools.findMethodExact(attachResultClass, "getBlackListStr")
            if (methodGet != null) {
                try {
                    module.hook(methodGet).intercept { chain ->
                        if (!HookConfig.isCloudBlacklistEnabled()) {
                            chain.proceed()
                        } else {
                            "" // Always return empty blacklist
                        }
                    }
                    HookTools.log(module, "[Cloud Blacklist] Hooked PinyinCloudAttachResult.getBlackListStr() -> return empty")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook PinyinCloudAttachResult.getBlackListStr", t)
                }
            }

            // Hook setBlackListStr(String) -> set ""
            val methodSet = HookTools.findMethodExact(attachResultClass, "setBlackListStr", String::class.java)
            if (methodSet != null) {
                try {
                    module.hook(methodSet).intercept { chain ->
                        if (!HookConfig.isCloudBlacklistEnabled()) {
                            chain.proceed()
                        } else {
                            chain.proceed(arrayOf(""))
                        }
                    }
                    HookTools.log(module, "[Cloud Blacklist] Hooked PinyinCloudAttachResult.setBlackListStr(String)")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook PinyinCloudAttachResult.setBlackListStr", t)
                }
            }

            // Hook fromBundle(Bundle)
            val methodFromBundle = HookTools.findMethodExact(attachResultClass, "fromBundle", Bundle::class.java)
            if (methodFromBundle != null) {
                try {
                    module.hook(methodFromBundle).intercept { chain ->
                        val result = chain.proceed()
                        if (HookConfig.isCloudBlacklistEnabled()) {
                            try {
                                val fieldB = attachResultClass.getDeclaredField("b").apply { isAccessible = true }
                                fieldB.set(chain.getThisObject(), "")
                            } catch (_: Throwable) {}
                        }
                        result
                    }
                    HookTools.log(module, "[Cloud Blacklist] Hooked PinyinCloudAttachResult.fromBundle(Bundle)")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook PinyinCloudAttachResult.fromBundle", t)
                }
            }
        }
    }
}

object PlatformGate {

    private var hasLoggedSysProp = false

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        hookSystemProperties(module)
        patchS0Field(module, classLoader)
        hookAiVersion(module, classLoader)
        hookMetadataHelper(module, classLoader)
        hookDialogHostActivity(module, classLoader)
    }

    /**
     * 1. Hook android.os.SystemProperties to report HyperOS 4.0+ environment.
     */
    private fun hookSystemProperties(module: XposedInterface) {
        try {
            val sysPropClass = Class.forName("android.os.SystemProperties")

            // get(String, String)
            val methodGet2 = HookTools.findMethodExact(sysPropClass, "get", String::class.java, String::class.java)
            if (methodGet2 != null) {
                module.hook(methodGet2).intercept { chain ->
                    if (!HookConfig.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                    val key = chain.getArg(0) as? String

                    when (key) {
                        "ro.mi.os.version.code" -> {
                            logPropertyMock(module, key, "4")
                            "4"
                        }
                        "ro.mi.os.version.name" -> {
                            logPropertyMock(module, key, "OS4.0")
                            "OS4.0"
                        }
                        "ro.mi.os.version.incremental" -> {
                            logPropertyMock(module, key, "OS4.0.1.0")
                            "OS4.0.1.0"
                        }
                        "ro.miui.ui.version.code" -> {
                            val orig = chain.proceed() as? String
                            if (orig.isNullOrEmpty() || orig == "0") "15" else orig
                        }
                        "ro.miui.ui.version.name" -> {
                            val orig = chain.proceed() as? String
                            if (orig.isNullOrEmpty() || orig == "unknown") "V15" else orig
                        }
                        else -> chain.proceed()
                    }
                }
                HookTools.log(module, "[HyperOS Unblock] Hooked SystemProperties.get(String, String)")
            }

            // get(String)
            val methodGet1 = HookTools.findMethodExact(sysPropClass, "get", String::class.java)
            if (methodGet1 != null) {
                module.hook(methodGet1).intercept { chain ->
                    if (!HookConfig.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                    val key = chain.getArg(0) as? String

                    when (key) {
                        "ro.mi.os.version.code" -> {
                            logPropertyMock(module, key, "4")
                            "4"
                        }
                        "ro.mi.os.version.name" -> {
                            logPropertyMock(module, key, "OS4.0")
                            "OS4.0"
                        }
                        "ro.mi.os.version.incremental" -> {
                            logPropertyMock(module, key, "OS4.0.1.0")
                            "OS4.0.1.0"
                        }
                        else -> chain.proceed()
                    }
                }
                HookTools.log(module, "[HyperOS Unblock] Hooked SystemProperties.get(String)")
            }

            // getInt(String, int)
            val methodGetInt = HookTools.findMethodExact(
                sysPropClass,
                "getInt",
                String::class.java,
                Int::class.javaPrimitiveType ?: Integer.TYPE
            )
            if (methodGetInt != null) {
                module.hook(methodGetInt).intercept { chain ->
                    if (!HookConfig.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                    val key = chain.getArg(0) as? String
                    val def = (chain.getArg(1) as? Number)?.toInt() ?: 0

                    when (key) {
                        "ro.mi.os.version.code" -> {
                            logPropertyMock(module, key, "4")
                            4
                        }
                        "ro.miui.ui.version.code" -> {
                            val orig = (chain.proceed() as? Number)?.toInt() ?: def
                            if (orig <= 0) 15 else orig
                        }
                        else -> chain.proceed()
                    }
                }
                HookTools.log(module, "[HyperOS Unblock] Hooked SystemProperties.getInt(String, int)")
            }

            // getLong(String, long)
            val methodGetLong = HookTools.findMethodExact(
                sysPropClass,
                "getLong",
                String::class.java,
                Long::class.javaPrimitiveType ?: java.lang.Long.TYPE
            )
            if (methodGetLong != null) {
                module.hook(methodGetLong).intercept { chain ->
                    if (!HookConfig.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                    val key = chain.getArg(0) as? String
                    if ("ro.mi.os.version.code" == key) {
                        4L
                    } else {
                        chain.proceed()
                    }
                }
            }
        } catch (t: Throwable) {
            HookTools.logError(module, "Failed to hook SystemProperties", t)
        }
    }

    private fun logPropertyMock(module: XposedInterface, key: String, mockVal: String) {
        if (!hasLoggedSysProp) {
            hasLoggedSysProp = true
            if (HookConfig.isVerboseLogEnabled()) {
                HookTools.log(module, "[HyperOS Unblock] SystemProperties.$key -> mock '$mockVal'")
            }
        }
    }

    /**
     * 2. Reflectively patch z7.s0 static boolean flag (isNotOS4) to false in memory.
     */
    private fun patchS0Field(module: XposedInterface, classLoader: ClassLoader) {
        try {
            val s0Class = HookTools.findClass("z7.s0", classLoader)
            if (s0Class != null) {
                for (field in s0Class.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) && (field.type == Boolean::class.javaPrimitiveType || field.type == java.lang.Boolean.TYPE)) {
                        field.isAccessible = true
                        val oldVal = field.getBoolean(null)
                        field.setBoolean(null, false)
                        if (HookConfig.isVerboseLogEnabled()) {
                            HookTools.log(module, "[HyperOS Unblock] Patched z7.s0.${field.name} from $oldVal to false")
                        }
                    }
                }
            } else {
                HookTools.logWarn(module, "[HyperOS Unblock] Class z7.s0 not found")
            }
        } catch (t: Throwable) {
            HookTools.logError(module, "Failed to patch z7.s0", t)
        }
    }

    /**
     * 3. Hook com.xiaomi.taiyi.sdk.common.AIVersion to ensure AI service compatibility.
     */
    private fun hookAiVersion(module: XposedInterface, classLoader: ClassLoader) {
        val aiVersionClass = HookTools.findClass("com.xiaomi.taiyi.sdk.common.AIVersion", classLoader)
        if (aiVersionClass != null) {
            // isOS4Service(Context) -> return true
            val methodIsOS4 = HookTools.findMethodExact(aiVersionClass, "isOS4Service", Context::class.java)
            if (methodIsOS4 != null) {
                try {
                    module.hook(methodIsOS4).intercept { chain ->
                        if (!HookConfig.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                        if (HookConfig.isVerboseLogEnabled()) {
                            HookTools.log(module, "[HyperOS Unblock] AIVersion.isOS4Service() -> true")
                        }
                        true
                    }
                    HookTools.log(module, "[HyperOS Unblock] Hooked AIVersion.isOS4Service(Context)")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook AIVersion.isOS4Service", t)
                }
            }

            // isServiceSupport(Context) -> return true
            val methodIsSupport = HookTools.findMethodExact(aiVersionClass, "isServiceSupport", Context::class.java)
            if (methodIsSupport != null) {
                try {
                    module.hook(methodIsSupport).intercept { chain ->
                        if (!HookConfig.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                        if (HookConfig.isVerboseLogEnabled()) {
                            HookTools.log(module, "[HyperOS Unblock] AIVersion.isServiceSupport() -> true")
                        }
                        true
                    }
                    HookTools.log(module, "[HyperOS Unblock] Hooked AIVersion.isServiceSupport(Context)")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook AIVersion.isServiceSupport", t)
                }
            }

            // SERVICE_SDK_INT(Context) -> return 200
            val methodSdkInt = HookTools.findMethodExact(aiVersionClass, "SERVICE_SDK_INT", Context::class.java)
            if (methodSdkInt != null) {
                try {
                    module.hook(methodSdkInt).intercept { chain ->
                        if (!HookConfig.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                        val orig = (chain.proceed() as? Number)?.toInt() ?: -1
                        if (orig < 200) 200 else orig
                    }
                    HookTools.log(module, "[HyperOS Unblock] Hooked AIVersion.SERVICE_SDK_INT(Context)")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook AIVersion.SERVICE_SDK_INT", t)
                }
            }
        }
    }

    /**
     * 4. Hook nc.a.s(Context, String, String) to provide virtual ai_sdk metadata if missing.
     */
    private fun hookMetadataHelper(module: XposedInterface, classLoader: ClassLoader) {
        val ncClass = HookTools.findClass("nc.a", classLoader)
        if (ncClass != null) {
            val methodS = HookTools.findMethodExact(
                ncClass,
                "s",
                Context::class.java,
                String::class.java,
                String::class.java
            )
            if (methodS != null) {
                try {
                    module.hook(methodS).intercept { chain ->
                        if (!HookConfig.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                        val pkgName = chain.getArg(1) as? String
                        val metaKey = chain.getArg(2) as? String

                        if ("com.xiaomi.aiservice" == pkgName) {
                            when (metaKey) {
                                "ai_sdk_code" -> "200"
                                "ai_sdk_name" -> "2.0.0-b2a69a6-260410-SNAPSHOT01"
                                else -> chain.proceed()
                            }
                        } else {
                            chain.proceed()
                        }
                    }
                    HookTools.log(module, "[HyperOS Unblock] Hooked nc.a.s(Context, String, String)")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook nc.a.s", t)
                }
            }
        }
    }

    /**
     * 5. Hook DialogHostActivity to block OS_VERSION_UNSUPPORTED dialog if triggered.
     */
    private fun hookDialogHostActivity(module: XposedInterface, classLoader: ClassLoader) {
        val dialogHostClass = HookTools.findClass("com.mi.ime.dialog.DialogHostActivity", classLoader)
        if (dialogHostClass != null) {
            val methodOnCreate = HookTools.findMethodExact(dialogHostClass, "onCreate", Bundle::class.java)
            if (methodOnCreate != null) {
                try {
                    module.hook(methodOnCreate).intercept { chain ->
                        if (!HookConfig.isOsVersionUnblockEnabled()) return@intercept chain.proceed()
                        val activity = chain.getThisObject() as? Activity
                        val dialogType = activity?.intent?.getStringExtra("dialog_type")
                        if ("OS_VERSION_UNSUPPORTED" == dialogType) {
                            if (HookConfig.isVerboseLogEnabled()) {
                                HookTools.log(module, "[HyperOS Unblock] Suppressed OS_VERSION_UNSUPPORTED DialogHostActivity")
                            }
                            activity.finish()
                            return@intercept null
                        }
                        chain.proceed()
                    }
                    HookTools.log(module, "[HyperOS Unblock] Hooked DialogHostActivity.onCreate(Bundle)")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook DialogHostActivity.onCreate", t)
                }
            }
        }
    }
}

object KeyboardSurface {

    private var cachedBitmap: Bitmap? = null
    private var cachedImageVersion: Long = -1L
    @Volatile private var activeBottomBarColor: Int = Color.TRANSPARENT
    // na.d is a data-style class whose hashCode includes these mutable fields,
    // so identity keys are required to keep restoration reliable after patching.
    private val originalAppsPanelColors = IdentityHashMap<Any, Map<String, Long>>()
    private val originalGlassTokenColors = WeakHashMap<Any, MutableMap<String, IntArray>>()
    private val materialRefreshGenerations = WeakHashMap<View, Int>()
    private val clipboardAdapterHooks = ConcurrentHashMap.newKeySet<Class<*>>()
    private val clipboardAppliedBackgrounds = WeakHashMap<View, AppliedClipboardBackground>()

    private fun setObjectFieldCompat(instance: Any, value: Any?, vararg names: String): Boolean {
        for (name in names) {
            var current: Class<*>? = instance.javaClass
            while (current != null && current != Any::class.java) {
                try {
                    current.getDeclaredField(name).apply {
                        isAccessible = true
                        set(instance, value)
                    }
                    return true
                } catch (_: NoSuchFieldException) {
                    current = current.superclass
                } catch (_: Throwable) {
                    break
                }
            }
        }
        return false
    }

    private fun materialView(helper: Any): View? =
        // 0.2.701 (bb.u) keeps the material surface in `i`; older builds use
        // the R8 names below. `h` in bb.u is a boolean state flag.
        helperField(helper, "f3542i", "f3463h", "i", "h") as? View

    private fun rimView(helper: Any): View? =
        helperField(helper, "j", "f3464i", "i") as? View

    private fun helperService(helper: Any): android.inputmethodservice.InputMethodService? =
        helperField(helper, "f3536a", "f3458a", "a") as? android.inputmethodservice.InputMethodService

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val imeServiceClass = HookTools.findClass("com.mi.ime.MiInputMethodService", classLoader)
        if (imeServiceClass == null) {
            HookTools.logError(module, "MiInputMethodService class not found for KeyboardSurface", null)
            return
        }

        installClipboardPopupHook(module)

        // 1. Hook onCreateInputView()
        val onCreateInputViewMethod = HookTools.findMethodExact(imeServiceClass, "onCreateInputView")
        if (onCreateInputViewMethod != null) {
            module.hook(onCreateInputViewMethod).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                if (service != null) {
                    HookConfig.syncFromProvider(service)
                }
                val resultView = chain.proceed() as? View
                if (resultView != null && HookConfig.isStyleEnabled() && service != null) {
                    applyStyle(module, service, resultView)
                }
                resultView
            }
            HookTools.log(module, "KeyboardSurface: Hooked onCreateInputView")
        }

        // 2. Hook onStartInputView(EditorInfo, boolean)
        val onStartInputViewMethod = HookTools.findMethodExact(
            imeServiceClass,
            "onStartInputView",
            EditorInfo::class.java,
            Boolean::class.javaPrimitiveType ?: java.lang.Boolean.TYPE
        )
        if (onStartInputViewMethod != null) {
            module.hook(onStartInputViewMethod).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                if (service != null) {
                    HookConfig.syncFromProvider(service)
                }
                val result = chain.proceed()
                if (HookConfig.isStyleEnabled() && service != null) {
                    val currentImeRootView = HookTools.getObjectField(service, "currentImeRootView") as? View
                    if (currentImeRootView != null) {
                        applyStyle(module, service, currentImeRootView)
                    }
                }
                result
            }
            HookTools.log(module, "KeyboardSurface: Hooked onStartInputView")
        }

        // 3. Hook onWindowShown()
        val onWindowShownMethod = HookTools.findMethodExact(imeServiceClass, "onWindowShown")
        if (onWindowShownMethod != null) {
            module.hook(onWindowShownMethod).intercept { chain ->
                val service = chain.thisObject as? android.inputmethodservice.InputMethodService
                if (service != null) {
                    HookConfig.syncFromProvider(service)
                }
                val result = chain.proceed()
                if (HookConfig.isStyleEnabled() && service != null) {
                    val currentImeRootView = HookTools.getObjectField(service, "currentImeRootView") as? View
                    if (currentImeRootView != null) {
                        applyStyle(module, service, currentImeRootView)
                    }
                }
                result
            }
            HookTools.log(module, "KeyboardSurface: Hooked onWindowShown")
        }

        // 4. Hook Compose keyboard container corner radius: na.m.F0(s0.p)
        try {
            val naMClass = HookTools.findClass("na.m", classLoader)
            if (naMClass != null) {
                val f0Method = naMClass.declaredMethods.find { it.name == "F0" }
                if (f0Method != null) {
                    module.hook(f0Method).intercept { chain ->
                        if (HookConfig.isStyleEnabled() && HookConfig.hasCornerRadiusOverride()) {
                            HookConfig.getCornerRadius().toFloat()
                        } else {
                            // With no explicit value, keep Compose's own radius so
                            // firmware resource changes continue to be respected.
                            chain.proceed()
                        }
                    }
                    HookTools.log(module, "KeyboardSurface: Hooked na.m.F0 (Compose corner radius)")
                }

                // Keep Compose foreground tokens readable after replacing the
                // keyboard surface. A custom solid color may have the opposite
                // luminance from the active system theme.
                val colorsMethod = naMClass.declaredMethods.find {
                    it.name == "w" && it.parameterTypes.size == 1 && it.returnType.name == "na.d"
                }
                if (colorsMethod != null) {
                    module.hook(colorsMethod).intercept { chain ->
                        val colors = chain.proceed()
                        if (colors != null) {
                            updateKeyboardContrast(
                                colors,
                                HookConfig.isStyleEnabled(),
                                HookConfig.getTextColor(),
                                HookConfig.getFunctionKeycapColor(),
                                HookConfig.getMenuCardColor(),
                                HookConfig.getLetterKeycapColor()
                            )
                        }
                        colors
                    }
                    HookTools.log(module, "KeyboardSurface: Hooked na.m.w (Keyboard contrast)")
                }
            }

            // In 0.2.599.905736fd the main QWERTY key renderer (aa.s6.a)
            // obtains the normal letter/number keycap color through na.d.d().
            // Hooking this accessor is deliberately narrower than mutating the
            // backing `c` field, which is also reused by cards and voice panels.
            val colorsClass = HookTools.findClass("na.d", classLoader)
            val normalKeycapMethod = colorsClass?.declaredMethods?.find {
                it.name == "d" &&
                    it.parameterTypes.isEmpty() &&
                    it.returnType == Long::class.javaPrimitiveType
            }
            if (normalKeycapMethod != null) {
                module.hook(normalKeycapMethod).intercept { chain ->
                    val customColor = parseOptionalColor(HookConfig.getLetterKeycapColor())
                    if (HookConfig.isStyleEnabled() && customColor != null) {
                        composeColor(customColor)
                    } else {
                        chain.proceed()
                    }
                }
                HookTools.log(module, "KeyboardSurface: Hooked na.d.d (Letter keycap color)")
            } else {
                HookTools.logError(module, "na.d.d() not found for letter keycap color", null)
            }
        } catch (t: Throwable) {
            HookTools.logError(module, "Error hooking Compose style tokens", t)
        }

        // 6. Hook the HyperMaterial helper. In 0.2.701 it is bb.u; older
        // 0.2.520 builds keep the implementation in bb.t.
        val materialHelperClass = findHyperMaterialHelperClass(classLoader)
        if (materialHelperClass != null) {
            val helperName = materialHelperClass.name
            // Hook h(): Unblock HyperMaterial support check
            val hMethod = materialHelperClass.declaredMethods.find { it.name == "h" }
            if (hMethod != null) {
                module.hook(hMethod).intercept { chain ->
                    if (HookConfig.isStyleEnabled() && HookConfig.getBgType() == 0) {
                        true
                    } else {
                        chain.proceed()
                    }
                }
                HookTools.log(module, "KeyboardSurface: Hooked $helperName.h (Global HyperMaterial unblock)")
            }

            // Hook k(): Package whitelist update hook
            val kMethod = materialHelperClass.declaredMethods.find { it.name == "k" }
            if (kMethod != null) {
                module.hook(kMethod).intercept { chain ->
                    // bb.t.k() reruns whenever the editor package changes. The
                    // material views are also the host for solid/image drawables,
                    // so keep them alive for every enabled custom background,
                    // not only for dynamic glass.
                    if (HookConfig.isStyleEnabled()) {
                        forceCurrentPackageIntoMaterialWhitelist(chain.thisObject)
                    }
                    val res = chain.proceed()
                    if (HookConfig.isStyleEnabled()) {
                        val helper = chain.thisObject
                        val f3497d = helperField(helper, "f3539e", "f3460d", "e", "d")
                        if (f3497d != null) {
                            try {
                                val setValueMethod = f3497d.javaClass.methods.find { it.name == "setValue" }
                                setValueMethod?.invoke(f3497d, true)
                            } catch (_: Throwable) {}
                        }
                    }
                    res
                }
                HookTools.log(module, "KeyboardSurface: Hooked $helperName.k (Whitelist bypass)")
            }

            // Hook e(boolean): Custom dynamic glass blur radius & parameters
            val eMethod = materialHelperClass.declaredMethods.find { it.name == "e" && it.parameterTypes.size == 1 }
            if (eMethod != null) {
                module.hook(eMethod).intercept { chain ->
                    val result = chain.proceed()
                    if (result != null && HookConfig.isStyleEnabled() && HookConfig.getBgType() == 0) {
                        try {
                            setObjectFieldCompat(
                                result,
                                HookConfig.getBlurRadius().coerceIn(0f, 45f).roundToInt(),
                                "f18726p",
                                "f18517p",
                                "p",
                            )
                        } catch (_: Throwable) {}
                    }
                    result
                }
                HookTools.log(module, "KeyboardSurface: Hooked $helperName.e (Dynamic glass blur tuning)")
            }

            // a() calls c(h) again when the keyboard interaction state
            // changes. Let the native method finish its required view setup,
            // then restore our custom drawable after its delayed background
            // cleanup has also run.
            val cMethod = materialHelperClass.declaredMethods.find {
                it.name == "c" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == View::class.java &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            if (cMethod != null) {
                module.hook(cMethod).intercept { chain ->
                    val result = chain.proceed()
                    if (HookConfig.isStyleEnabled() && HookConfig.getBgType() != 0) {
                        val helper = chain.thisObject
                        val materialView = chain.getArg(0) as? View
                        if (materialView != null) {
                            materialView.post {
                                restoreCustomBackground(helper, materialView)
                            }
                            materialView.postDelayed({
                                restoreCustomBackground(helper, materialView)
                            }, 48L)
                        }
                    }
                    result
                }
                HookTools.log(module, "KeyboardSurface: Hooked $helperName.c (Restore custom background)")
            }

            // Hook b(View): the native method rewrites every shader uniform on
            // each layout pass. Apply our controls after that write so they are
            // effective on both the initial frame and subsequent relayouts.
            val bMethod = materialHelperClass.declaredMethods.find {
                it.name == "b" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == View::class.java &&
                    it.returnType == Void.TYPE
            }?.apply { isAccessible = true }
            if (bMethod != null) {
                module.hook(bMethod).intercept { chain ->
                    val res = chain.proceed()
                    if (HookConfig.isStyleEnabled()) {
                        val helper = chain.thisObject
                        val view = chain.getArg(0) as? View
                        if (view != null && applyRuntimeShaderControls(helper, view)) {
                            // A few firmware builds perform one more RenderEffect
                            // update from a posted layout callback. Re-apply once
                            // after that callback without creating a polling loop.
                            view.post {
                                if (HookConfig.isStyleEnabled()) {
                                    applyRuntimeShaderControls(helper, view)
                                }
                            }
                        }
                    }
                    res
                }
                HookTools.log(module, "KeyboardSurface: Hooked $helperName.b (RuntimeShader uRadii sync)")
            }

            // Hook g(boolean, FrameLayout, int): Update views on attach
            val gMethod = materialHelperClass.declaredMethods.find { it.name == "g" }
            if (gMethod != null) {
                module.hook(gMethod).intercept { chain ->
                    val res = chain.proceed()
                    if (HookConfig.isStyleEnabled()) {
                        val helper = chain.thisObject
                        val service = helperService(helper)
                        if (service != null) {
                            HookConfig.syncFromProvider(service)
                            scheduleHyperMaterialRefresh(module, service, helper)
                        }
                    }
                    res
                }
                HookTools.log(module, "KeyboardSurface: Hooked $helperName.g")
            }

            // In non-floating mode g() creates the material view with height=0.
            // o()/p() later posts the real height after InputMethodService computes
            // contentTopInsets. Applying material before that layout produces the
            // opaque white cold-start frame seen after force-stopping the IME.
            val oMethod = materialHelperClass.declaredMethods.find {
                (it.name == "o" || it.name == "p") &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes.all { type -> type == Int::class.javaPrimitiveType }
            }
            if (oMethod != null) {
                module.hook(oMethod).intercept { chain ->
                    val res = chain.proceed()
                    if (HookConfig.isStyleEnabled()) {
                        val helper = chain.thisObject
                        val service = helperService(helper)
                        if (service != null) {
                            // o()/p() posts the final material height before this
                            // callback. Queue the complete style pass behind it so
                            // contentTopInsets and target screen coordinates are
                            // recomputed together, including the toolbar area.
                            val rootView = HookTools.getObjectField(service, "currentImeRootView") as? View
                            if (rootView != null) {
                                applyStyle(module, service, rootView)
                            } else {
                                scheduleHyperMaterialRefresh(module, service, helper)
                            }
                        }
                    }
                    res
                }
                HookTools.log(module, "KeyboardSurface: Hooked $helperName.${oMethod.name} (post-layout material refresh)")
            }
        }

        // 6.2 Hook xe.b: Unblock system background blur capability checks
        val xeBClass = HookTools.findClass("xe.b", classLoader)
        if (xeBClass != null) {
            val cMethod = xeBClass.declaredMethods.find { it.name == "c" }
            if (cMethod != null) {
                module.hook(cMethod).intercept { chain ->
                    if (HookConfig.isStyleEnabled() && HookConfig.getBgType() == 0) true else chain.proceed()
                }
                HookTools.log(module, "KeyboardSurface: Hooked xe.b.c")
            }
            val bMethod = xeBClass.declaredMethods.find { it.name == "b" && it.parameterTypes.size == 1 }
            if (bMethod != null) {
                module.hook(bMethod).intercept { chain ->
                    if (HookConfig.isStyleEnabled() && HookConfig.getBgType() == 0) true else chain.proceed()
                }
                HookTools.log(module, "KeyboardSurface: Hooked xe.b.b")
            }
        }

        // 7. Hook HyperMaterial OutlineProvider: bb.t.getOutline(View, Outline)
        try {
            val bbTClass = HookTools.findClass("bb.t", classLoader)
            if (bbTClass != null) {
                val getOutlineMethod = HookTools.findMethodExact(bbTClass, "getOutline", View::class.java, Outline::class.java)
                if (getOutlineMethod != null) {
                    module.hook(getOutlineMethod).intercept { chain ->
                        if (HookConfig.isStyleEnabled() && HookConfig.hasCornerRadiusOverride()) {
                            val view = chain.getArg(0) as? View
                            val outline = chain.getArg(1) as? Outline
                            if (view != null && outline != null && view.width > 0 && view.height > 0) {
                                val radiusDp = HookConfig.getCornerRadius()
                                val radiusPx = radiusDp * view.resources.displayMetrics.density
                                setKeyboardOutline(outline, view, radiusPx, isFloatingOutlineOwner(chain.thisObject))
                                return@intercept null
                            }
                        }
                        chain.proceed()
                    }
                    HookTools.log(module, "KeyboardSurface: Hooked bb.t.getOutline")
                }
            }
        } catch (t: Throwable) {
            HookTools.logError(module, "Error hooking bb.t.getOutline", t)
        }

        // 8. Hook bb.g1.S (sets navigation bar color) & bb.g1.s (customizeBottomViewColor)
        try {
            val bbG1Class = HookTools.findClass("bb.g1", classLoader)
            if (bbG1Class != null) {
                val sMethod = bbG1Class.declaredMethods.find { it.name == "S" }
                if (sMethod != null) {
                    module.hook(sMethod).intercept { chain ->
                        val res = chain.proceed()
                        if (HookConfig.isStyleEnabled()) {
                            val g1Obj = chain.thisObject
                            val bField = HookTools.getObjectField(g1Obj, "b")
                            if (bField != null) {
                                val bInner = HookTools.getObjectField(bField, "b") as? android.content.Context
                                if (bInner is android.inputmethodservice.InputMethodService) {
                                    val window = bInner.window?.window
                                    window?.setNavigationBarColor(activeBottomBarColor)
                                    window?.setNavigationBarContrastEnforced(false)
                                }
                            }
                        }
                        res
                    }
                    HookTools.log(module, "KeyboardSurface: Hooked bb.g1.S")
                }

                // Hook bb.g1.s(int i5, int i10, int i11, boolean z2): keep
                // HyperOS' separate bottom view continuous with the glass card.
                val staticSMethod = bbG1Class.declaredMethods.find { it.name == "s" && it.parameterTypes.size == 4 }
                if (staticSMethod != null) {
                    module.hook(staticSMethod).intercept { chain ->
                        if (HookConfig.isStyleEnabled()) {
                            val iconColor = chain.getArg(1) as? Int ?: 0
                            val rippleColor = chain.getArg(2) as? Int ?: 0
                            try {
                                val injectorClass = Class.forName("android.inputmethodservice.InputMethodServiceInjector")
                                val customizeMethod = injectorClass.declaredMethods.find { it.name == "customizeBottomViewColor" }
                                if (customizeMethod != null) {
                                    customizeMethod.isAccessible = true
                                customizeMethod.invoke(null, true, activeBottomBarColor, iconColor, rippleColor)
                                }
                            } catch (_: Throwable) {}
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                    HookTools.log(module, "KeyboardSurface: Hooked bb.g1.s (BottomView glass background)")
                }
            }
        } catch (t: Throwable) {
            HookTools.logError(module, "Error hooking bb.g1", t)
        }
    }

    /**
     * HyperMaterial k() removes both material views when the current editor package is
     * absent from its downloaded whitelist. Add only the active package before
     * that check so the native method keeps the blur views alive.
     */
    private fun forceCurrentPackageIntoMaterialWhitelist(helper: Any) {
        val packageName = helperField(helper, "f3552v", "f3473u", "v", "u") as? String ?: return

        val versions = LinkedHashMap<Any?, Any?>()
        (helperField(helper, "f3553w", "f3474v", "w", "v") as? Map<*, *>)?.forEach { (key, value) ->
            versions[key] = value
        }
        versions[packageName] = 2
        setHelperField(helper, versions, "f3553w", "f3474v", "w", "v")

        val darkPackages = LinkedHashSet<Any?>()
        (helperField(helper, "f3554x", "f3475w", "x", "w") as? Set<*>)?.forEach {
            darkPackages.add(it)
        }
        val lightPackages = LinkedHashSet<Any?>()
        (helperField(helper, "f3555y", "f3476x", "y", "x") as? Set<*>)?.forEach {
            lightPackages.add(it)
        }
        darkPackages.remove(packageName)
        lightPackages.remove(packageName)

        val context = helperField(helper, "f3536a", "f3458a", "a") as? Context
        val systemDark = context?.resources?.configuration?.uiMode?.let {
            (it and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        } ?: false
        if (HookConfig.isKeyboardSurfaceDark(systemDark)) {
            darkPackages.add(packageName)
        } else {
            lightPackages.add(packageName)
        }
        setHelperField(helper, darkPackages, "f3554x", "f3475w", "x", "w")
        setHelperField(helper, lightPackages, "f3555y", "f3476x", "y", "x")
    }

    private fun isFloatingMaterial(helper: Any): Boolean {
        val mode = helperField(helper, "f3544m", "f3465l", "m", "l") ?: return false
        return runCatching {
            val ordinal = mode.javaClass.methods.firstOrNull {
                it.name == "ordinal" && it.parameterCount == 0
            }?.invoke(mode) as? Number
            ordinal?.toInt() == 2 || mode.toString().equals("FLOATING", ignoreCase = true)
        }.getOrDefault(false)
    }

    /** bb.t is the newer ViewOutlineProvider and keeps its helper in f3528a. */
    private fun isFloatingOutlineOwner(owner: Any?): Boolean {
        val helper = owner?.let { helperField(it, "f3528a", "a") } ?: return false
        return isFloatingMaterial(helper)
    }

    private fun nativeCornerRadiusPx(helper: Any, view: View): Float? {
        val service = helperService(helper)
        val context = service ?: view.context
        val resourceName = if (isFloatingMaterial(helper)) {
            "floating_corner_radius"
        } else {
            "keyboard_container_corner_radius"
        }
        val resources = context.resources
        val packageName = context.packageName
        var resourceId = runCatching {
            resources.getIdentifier(resourceName, "dimen", packageName)
        }.getOrDefault(0)
        if (resourceId == 0) {
            resourceId = runCatching {
                val rClass = Class.forName("z9.b", false, context.classLoader)
                rClass.getDeclaredField(resourceName).apply { isAccessible = true }.getInt(null)
            }.getOrDefault(0)
        }
        return resourceId.takeIf { it != 0 }?.let {
            runCatching { resources.getDimension(it) }.getOrNull()
        }
    }

    private fun resolveCornerRadiusPx(helper: Any, view: View): Float {
        val density = view.resources.displayMetrics.density
        if (HookConfig.hasCornerRadiusOverride()) {
            return HookConfig.getCornerRadius().coerceAtLeast(0f) * density
        }
        return nativeCornerRadiusPx(helper, view)
            ?: (24f * density)
    }

    private fun setKeyboardOutline(
        outline: Outline,
        view: View,
        radiusPx: Float,
        floating: Boolean,
    ) {
        if (radiusPx <= 0f) {
            outline.setRect(0, 0, view.width, view.height)
            return
        }
        val radii = if (floating) {
            floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx, radiusPx)
        } else {
            floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f)
        }
        val path = Path().apply {
            addRoundRect(
                RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                radii,
                Path.Direction.CW,
            )
        }
        outline.setPath(path)
    }

    private fun helperField(helper: Any, vararg names: String): Any? =
        names.firstNotNullOfOrNull { name -> HookTools.getObjectField(helper, name) }

    private fun setHelperField(helper: Any, value: Any?, vararg names: String) {
        names.firstOrNull { name ->
            var current: Class<*>? = helper.javaClass
            while (current != null && current != Any::class.java) {
                try {
                    current.getDeclaredField(name)
                    return@firstOrNull true
                } catch (_: NoSuchFieldException) {
                    current = current.superclass
                } catch (_: Throwable) {
                    return@firstOrNull false
                }
            }
            false
        }?.let { HookTools.setObjectField(helper, it, value) }
    }

    private fun findHyperMaterialHelperClass(classLoader: ClassLoader): Class<*>? =
        arrayOf("bb.u", "bb.t")
            .mapNotNull { HookTools.findClass(it, classLoader) }
            .firstOrNull { clazz ->
                clazz.declaredMethods.any { it.name == "k" && it.parameterCount == 0 }
            }

    /** Compose stores sRGB colors as an unsigned ARGB value in the high 32 bits. */
    private fun composeColor(argb: Int): Long =
        (argb.toLong() and 0xffffffffL) shl 32

    // R8 retains some one-letter fields but gives collision-prone fields a stable
    // renamed form. Keep logical names here so color replacements stay readable.
    private val composeColorFieldAliases = mapOf(
        "a" to arrayOf("f13310a", "f13191a", "a"),
        "d" to arrayOf("f13318d", "f13199d", "d"),
        "e" to arrayOf("f13321e", "f13202e", "e"),
        "h" to arrayOf("f13329h", "f13210h", "h"),
        "i" to arrayOf("f13332i", "f13213i", "i"),
        "l" to arrayOf("f13339l", "f13220l", "l"),
        "m" to arrayOf("f13342m", "f13222m", "m"),
        "u" to arrayOf("f13360u", "f13237u", "u"),
        "v" to arrayOf("f13362v", "f13239v", "v"),
        "w" to arrayOf("f13364w", "f13241w", "w"),
        "x" to arrayOf("f13366x", "f13243x", "x"),
        "y" to arrayOf("f13368y", "f13245y", "y"),
        "z" to arrayOf("f13370z", "f13247z", "z"),
        "o1" to arrayOf("f13350o1", "o1")
    )

    private fun findField(instance: Any, logicalName: String): Field? {
        val fieldNames = composeColorFieldAliases[logicalName] ?: arrayOf(logicalName)
        fieldNames.forEach { name ->
            var current: Class<*>? = instance.javaClass
            while (current != null && current != Any::class.java) {
                try {
                    return current.getDeclaredField(name).apply { isAccessible = true }
                } catch (_: NoSuchFieldException) {
                    current = current.superclass
                } catch (_: Throwable) {
                    break
                }
            }
        }
        return null
    }

    private fun readLongField(instance: Any, name: String): Long? = try {
        findField(instance, name)?.getLong(instance)
    } catch (_: Throwable) {
        null
    }

    private fun writeLongField(instance: Any, name: String, value: Long) {
        try {
            findField(instance, name)?.setLong(instance, value)
        } catch (_: Throwable) {
        }
    }

    private fun readBooleanField(instance: Any, vararg names: String): Boolean? {
        names.forEach { name ->
            try {
                val field = findField(instance, name) ?: return@forEach
                if (field.type == Boolean::class.javaPrimitiveType) {
                    return field.getBoolean(instance)
                }
                (field.get(instance) as? Boolean)?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    /**
     * na.d core fields used here are h/i/k/l/m (key and mode text/icons),
     * w/x (toolbar icons), z (bottom-bar icon), and A/B (dividers).
     * B0..L0 are the APPS panel colors.
     */
    private fun updateKeyboardContrast(
        colors: Any,
        enabled: Boolean,
        textColor: String,
        functionKeycapColor: String,
        menuCardColor: String,
        letterKeycapColor: String
    ) {
        val coreFields = arrayOf(
            "a", "d", "e", "h", "i", "k", "l", "m",
            "u", "v", "w", "x", "y", "z", "A", "B"
        )
        val appsPanelFields = arrayOf("B0", "C0", "D0", "E0", "F0", "G0", "H0", "I0", "J0", "K0", "L0")
        val fieldNames = coreFields + appsPanelFields
        synchronized(originalAppsPanelColors) {
            val originals = originalAppsPanelColors.getOrPut(colors) {
                fieldNames.mapNotNull { name -> readLongField(colors, name)?.let { name to it } }.toMap()
            }

            // The same na.d instance can survive a background-type change.
            // Restore it before applying the colors required by the current mode.
            originals.forEach { (name, value) -> writeLongField(colors, name, value) }
            if (!enabled) {
                return
            }

            // In 0.2.701 na.d.n1 is a Long color token; the actual theme
            // switch moved to the adjacent o1 Boolean field.
            val systemDark = readBooleanField(colors, "f13219k1", "o1", "n1") ?: false
            val surfaceDark = HookConfig.isKeyboardSurfaceDark(systemDark)
            val customText = textColor.takeIf { it.isNotBlank() }?.let {
                try {
                    Color.parseColor(it)
                } catch (_: Throwable) {
                    null
                }
            }
            val customFunctionKeycap = parseOptionalColor(functionKeycapColor)
            val customMenuCard = parseOptionalColor(menuCardColor)
            val customLetterKeycap = parseOptionalColor(letterKeycapColor)
            val keySurfaceDark = (customLetterKeycap ?: customFunctionKeycap)?.let {
                (299 * Color.red(it) + 587 * Color.green(it) + 114 * Color.blue(it)) / 1000 < 150
            } ?: surfaceDark
            val keyPrimary = customText
                ?: if (keySurfaceDark) Color.argb(242, 255, 255, 255) else Color.argb(230, 0, 0, 0)
            val keySecondary = customText?.let {
                Color.argb(
                    (Color.alpha(it) * 0.82f).toInt(),
                    Color.red(it),
                    Color.green(it),
                    Color.blue(it)
                )
            } ?: if (keySurfaceDark) Color.argb(217, 255, 255, 255) else Color.argb(178, 0, 0, 0)
            val menuSurfaceDark = customMenuCard?.let {
                (299 * Color.red(it) + 587 * Color.green(it) + 114 * Color.blue(it)) / 1000 < 150
            } ?: surfaceDark
            val menuPrimary = customText
                ?: if (menuSurfaceDark) Color.argb(242, 255, 255, 255) else Color.argb(230, 0, 0, 0)
            val menuSecondary = customText?.let {
                Color.argb(
                    (Color.alpha(it) * 0.82f).toInt(),
                    Color.red(it),
                    Color.green(it),
                    Color.blue(it)
                )
            } ?: if (menuSurfaceDark) Color.argb(217, 255, 255, 255) else Color.argb(178, 0, 0, 0)
            val divider = if (surfaceDark) Color.argb(54, 255, 255, 255) else Color.argb(42, 0, 0, 0)
            // These Compose surfaces are drawn above bb.u's HyperMaterial view.
            // Keep them transparent/translucent so the configured glass remains
            // visible while retaining a small tint for panel readability.
            val opacityStrength = HookConfig.getOpacity().coerceIn(0, 100) / 100f
            val toolbarSurface = Color.TRANSPARENT
            val panelSurface = if (surfaceDark) {
                Color.argb((74 * opacityStrength).roundToInt(), 255, 255, 255)
            } else {
                Color.argb((92 * opacityStrength).roundToInt(), 255, 255, 255)
            }

            val replacements = mutableMapOf(
                // The top toolbar background is na.d.u. A transparent token
                // lets the HyperMaterial surface remain visible.
                "u" to toolbarSurface,
                "B" to toolbarSurface,
                // Toolbar/toolbox foreground icons.
                "y" to menuPrimary,
                "x" to keyPrimary,
                "z" to keyPrimary,
                // Apps/toolbox panel surfaces and foreground colors. In the
                // 0.2.701 panel D0/G0 are backgrounds, E0/F0/H0 are content.
                "D0" to panelSurface,
                "G0" to panelSurface,
                "E0" to menuPrimary,
                "F0" to menuSecondary,
                "H0" to menuPrimary,
            )
            if (customFunctionKeycap != null) {
                replacements["e"] = customFunctionKeycap
                replacements["d"] = resolvePressedKeycapColor(customFunctionKeycap, keySurfaceDark)
            }
            replacements.putAll(
                mapOf(
                    "h" to keyPrimary,
                    "i" to keySecondary,
                    "k" to keySecondary,
                    "l" to keyPrimary,
                    "m" to keyPrimary,
                    "w" to keyPrimary,
                    "x" to keyPrimary,
                    "z" to keyPrimary,
                    "A" to divider
                )
            )
            replacements.forEach { (name, value) -> writeLongField(colors, name, composeColor(value)) }
        }
    }

    /**
     * MIUIFrequentPhrase supplies this PopupWindow class, but the Xiaomi IME
     * loads and displays it in its own process. Hooking the framework show call
     * avoids racing the APK's dynamic class loader and runs after all stock
     * panel backgrounds have been assigned.
     */
    private fun installClipboardPopupHook(module: XposedModule) {
        PopupWindow::class.java.declaredMethods
            .filter { it.name == "showAtLocation" && it.parameterTypes.size == 4 }
            .forEach { method ->
                method.isAccessible = true
                module.hook(method).intercept { chain ->
                    val result = chain.proceed()
                    val popup = chain.thisObject as? PopupWindow
                    if (popup?.javaClass?.name == CLIPBOARD_POPUP_CLASS) {
                        applyClipboardPopupStyle(module, popup)
                    }
                    result
                }
            }
        HookTools.log(module, "KeyboardSurface: Hooked clipboard PopupWindow glass styling")
    }

    private fun applyClipboardPopupStyle(module: XposedModule, popup: PopupWindow) {
        val service = HookTools.getObjectField(popup, "mInputMethodService") as?
            android.inputmethodservice.InputMethodService ?: return
        HookConfig.syncFromProvider(service)
        if (!HookConfig.isStyleEnabled()) return

        val root = popup.contentView ?: return
        val inside = findViewByResourceName(root, "inside_view") ?: root
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        root.background = null
        findViewByResourceName(root, "outside_view")?.background = null

        popup.javaClass.classLoader?.let { loader ->
            installClipboardAdapterHooks(module, loader)
        }
        // The popup is already attached when showAtLocation returns, but its
        // first traversal has not drawn yet. Replace every inflated stock card
        // now; the posted block below is reserved for native material setup.
        styleClipboardViewTree(root)
        inside.post {
            try {
                val density = inside.resources.displayMetrics.density
                val radiusPx = HookConfig.getCornerRadius().coerceAtLeast(0f) * density
                when (HookConfig.getBgType()) {
                    0 -> {
                        inside.setBackgroundColor(Color.TRANSPARENT)
                        val helper = HookTools.getObjectField(service, "hyperMaterialHelper")
                        if (helper != null) {
                            updateCachedGlassTokens(
                                helper,
                                HookConfig.getBlurRadius(),
                                HookConfig.getOpacity()
                            )
                            try {
                                invokeHelperMethod(helper, "c", inside)
                            } catch (t: Throwable) {
                                HookTools.logError(
                                    module,
                                    "KeyboardSurface: clipboard native HyperMaterial apply failed",
                                    t
                                )
                            }
                        }
                        val isDark = isDarkSurface(service)
                        applySoftGlassForeground(
                            inside,
                            isDark,
                            HookConfig.getOpacity(),
                            radiusPx
                        )
                    }
                    1 -> {
                        inside.foreground = null
                        inside.background = ColorDrawable(
                            resolveSolidColor(
                                HookConfig.getBgColor(),
                                HookConfig.getOpacity()
                            )
                        )
                    }
                    2 -> {
                        inside.foreground = null
                        getOrLoadBitmap(service)?.takeIf { !it.isRecycled }?.let { bitmap ->
                            inside.background = BitmapDrawable(service.resources, bitmap).apply {
                                alpha = HookConfig.getOpacity().coerceIn(0, 100) * 255 / 100
                            }
                        }
                    }
                }

                applyTopCornerOutline(inside, radiusPx)
                styleClipboardViewTree(root)
                if (HookConfig.isVerboseLogEnabled()) {
                    HookTools.log(
                        module,
                        "KeyboardSurface: clipboard panel synchronized with keyboard background"
                    )
                }
            } catch (t: Throwable) {
                HookTools.logError(module, "KeyboardSurface: clipboard panel styling failed", t)
            }
        }
    }

    private fun installClipboardAdapterHooks(module: XposedModule, classLoader: ClassLoader) {
        CLIPBOARD_ADAPTER_CLASSES.forEach { className ->
            val adapterClass = HookTools.findClass(className, classLoader) ?: return@forEach
            if (!clipboardAdapterHooks.add(adapterClass)) return@forEach

            adapterClass.declaredMethods
                .filter {
                    !it.isBridge && !it.isSynthetic &&
                        ((it.name == "onBindViewHolder" && it.parameterTypes.size >= 2) ||
                            (it.name == "onCreateViewHolder" && it.parameterTypes.size >= 2))
                }
                .forEach { method ->
                    method.isAccessible = true
                    module.hook(method).intercept { chain ->
                        val result = chain.proceed()
                        if (HookConfig.isStyleEnabled()) {
                            val holder = if (method.name == "onCreateViewHolder") {
                                result
                            } else {
                                chain.getArg(0)
                            }
                            val itemView = holder?.let {
                                HookTools.getObjectField(it, "itemView") as? View
                            }
                            // RecyclerView may draw the holder immediately after
                            // this callback returns. Style it synchronously so a
                            // stock opaque selector can never reach the first frame.
                            itemView?.let(::styleClipboardViewTree)
                        }
                        result
                    }
                }
        }
    }

    private fun styleClipboardViewTree(view: View) {
        styleClipboardViewTree(view, clipboardPalette(view))
    }

    private fun styleClipboardViewTree(view: View, palette: ClipboardPalette) {
        val name = resourceEntryName(view)
        val density = view.resources.displayMetrics.density
        val itemRadius = 18f * density
        val smallRadius = 12f * density

        when (name) {
            "outside_view", "clipboard_title_bar", "list_view_layout", "recycler_view" -> {
                clearClipboardBackground(view)
            }
            "clipboard_item_layout", "phrase_item_layout" -> {
                applyClipboardBackground(
                    view,
                    clipboardBackgroundSignature(
                        STYLE_ITEM_CARD,
                        palette.card,
                        palette.cardPressed,
                        itemRadius
                    )
                ) {
                    statefulRoundedBackground(
                        palette.card,
                        palette.cardPressed,
                        itemRadius
                    )
                }
                view.elevation = 0f
            }
            "clipboard_loading" -> {
                applyClipboardBackground(
                    view,
                    clipboardBackgroundSignature(STYLE_LOADING_CARD, palette.card, itemRadius)
                ) {
                    roundedBackground(palette.card, itemRadius)
                }
            }
            "clipboard_text", "phrase_text" -> {
                applyClipboardBackground(
                    view,
                    clipboardBackgroundSignature(
                        STYLE_TAB,
                        palette.tabSelected,
                        Color.TRANSPARENT,
                        smallRadius
                    )
                ) {
                    selectedRoundedBackground(
                        palette.tabSelected,
                        Color.TRANSPARENT,
                        smallRadius
                    )
                }
                if (view is TextView) {
                    view.setTextColor(
                        ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                            intArrayOf(palette.primaryText, palette.secondaryText)
                        )
                    )
                }
            }
            "clipboard_text_item_top", "clipboard_text_item_bottom", "image_end_show",
            "phrase_text_item", "text_view" -> (view as? TextView)?.setTextColor(palette.primaryText)
            "clipboard_no_items", "loading_text", "clipboard_across_devices_tip_text" -> {
                (view as? TextView)?.setTextColor(palette.secondaryText)
            }
            "pack_up_view", "delete_and_add_action_button" -> {
                (view as? ImageView)?.setColorFilter(palette.primaryText)
            }
        }

        if (view is ViewGroup) {
            if (containsNamedChildren(view, "clipboard_text", "phrase_text")) {
                applyClipboardBackground(
                    view,
                    clipboardBackgroundSignature(STYLE_TAB_TRACK, palette.tabTrack, smallRadius)
                ) {
                    roundedBackground(palette.tabTrack, smallRadius)
                }
            }
            if (name == "clipboard_tip_view" && view.childCount > 0) {
                val tipCard = view.getChildAt(0)
                applyClipboardBackground(
                    tipCard,
                    clipboardBackgroundSignature(STYLE_TIP_CARD, palette.card, itemRadius)
                ) {
                    roundedBackground(palette.card, itemRadius)
                }
            }
            for (index in 0 until view.childCount) {
                styleClipboardViewTree(view.getChildAt(index), palette)
            }
        }
    }

    private fun clipboardPalette(view: View): ClipboardPalette {
        val opacityStrength = HookConfig.getOpacity().coerceIn(0, 100) / 100f
        val customCard = parseOptionalColor(HookConfig.getMenuCardColor())
        val dark = when (HookConfig.getBgType()) {
            1 -> isDarkColor(parseOptionalColor(HookConfig.getBgColor()) ?: Color.WHITE)
            else -> isDarkSurface(view.context)
        }
        val customText = parseOptionalColor(HookConfig.getTextColor())
        val primary = customText ?: if (dark) Color.rgb(245, 247, 252) else Color.rgb(20, 22, 27)
        val secondary = withAlpha(primary, if (dark) 178 else 150)
        val card = customCard ?: if (dark) {
            Color.argb((92 * opacityStrength).toInt(), 255, 255, 255)
        } else {
            Color.argb((145 * opacityStrength).toInt(), 255, 255, 255)
        }
        val pressed = if (dark) {
            Color.argb((135 * opacityStrength).toInt(), 255, 255, 255)
        } else {
            Color.argb((190 * opacityStrength).toInt(), 255, 255, 255)
        }
        val tabTrack = if (dark) {
            Color.argb((62 * opacityStrength).toInt(), 255, 255, 255)
        } else {
            Color.argb((105 * opacityStrength).toInt(), 255, 255, 255)
        }
        val tabSelected = customCard ?: if (dark) {
            Color.argb((118 * opacityStrength).toInt(), 255, 255, 255)
        } else {
            Color.argb((205 * opacityStrength).toInt(), 255, 255, 255)
        }
        return ClipboardPalette(card, pressed, tabTrack, tabSelected, primary, secondary)
    }

    private fun applyTopCornerOutline(view: View, radiusPx: Float) {
        if (radiusPx <= 0f) {
            view.clipToOutline = false
            return
        }
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                if (target.width > 0 && target.height > 0) {
                    outline.setRoundRect(
                        0,
                        0,
                        target.width,
                        target.height,
                        radiusPx
                    )
                }
            }
        }
        view.invalidateOutline()
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
    }

    private fun statefulRoundedBackground(normal: Int, pressed: Int, radius: Float) =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedBackground(pressed, radius)
            )
            addState(intArrayOf(), roundedBackground(normal, radius))
        }

    private fun selectedRoundedBackground(selected: Int, normal: Int, radius: Float) =
        StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_selected),
                roundedBackground(selected, radius)
            )
            addState(intArrayOf(), roundedBackground(normal, radius))
        }

    private fun applyClipboardBackground(
        view: View,
        signature: Int,
        create: () -> Drawable
    ) {
        val cached = clipboardAppliedBackgrounds[view]
        if (cached?.signature == signature && view.background === cached.drawable) return

        val drawable = create()
        view.background = drawable
        clipboardAppliedBackgrounds[view] = AppliedClipboardBackground(signature, drawable)
    }

    private fun clearClipboardBackground(view: View) {
        clipboardAppliedBackgrounds.remove(view)
        if (view.background != null) view.background = null
    }

    private fun clipboardBackgroundSignature(kind: Int, vararg values: Any): Int {
        var result = kind
        values.forEach { value -> result = 31 * result + value.hashCode() }
        return result
    }

    private fun containsNamedChildren(group: ViewGroup, vararg names: String): Boolean {
        val found = group.childrenResourceNames().toSet()
        return names.all(found::contains)
    }

    private fun ViewGroup.childrenResourceNames(): List<String> =
        (0 until childCount).mapNotNull { resourceEntryName(getChildAt(it)) }

    private fun findViewByResourceName(view: View, name: String): View? {
        if (resourceEntryName(view) == name) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findViewByResourceName(view.getChildAt(index), name)?.let { return it }
            }
        }
        return null
    }

    private fun resourceEntryName(view: View): String? {
        if (view.id == View.NO_ID) return null
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isDarkSurface(context: Context): Boolean {
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return HookConfig.isKeyboardSurfaceDark(systemDark)
    }

    private fun isDarkColor(color: Int): Boolean =
        (299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color)) / 1000 < 150

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private data class ClipboardPalette(
        val card: Int,
        val cardPressed: Int,
        val tabTrack: Int,
        val tabSelected: Int,
        val primaryText: Int,
        val secondaryText: Int
    )

    private data class AppliedClipboardBackground(
        val signature: Int,
        val drawable: Drawable
    )

    private const val STYLE_ITEM_CARD = 1
    private const val STYLE_LOADING_CARD = 2
    private const val STYLE_TAB = 3
    private const val STYLE_TAB_TRACK = 4
    private const val STYLE_TIP_CARD = 5

    private const val CLIPBOARD_POPUP_CLASS =
        "com.miui.inputmethod.InputMethodClipboardPhrasePopupView"
    private val CLIPBOARD_ADAPTER_CLASSES = arrayOf(
        "com.miui.inputmethod.InputMethodClipboardAdapter",
        "com.miui.inputmethod.InputMethodClipboardHeaderAdapter",
        "com.miui.inputmethod.InputMethodPhraseAdapter"
    )

    private fun parseOptionalColor(value: String): Int? = value.takeIf { it.isNotBlank() }?.let {
        try {
            Color.parseColor(it)
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolvePressedKeycapColor(color: Int, dark: Boolean): Int {
        val target = if (dark) 255 else 0
        val amount = if (dark) 0.18f else 0.14f
        fun blend(channel: Int): Int = (channel + (target - channel) * amount).toInt().coerceIn(0, 255)
        return Color.argb(
            Color.alpha(color),
            blend(Color.red(color)),
            blend(Color.green(color)),
            blend(Color.blue(color))
        )
    }

    private fun getOrLoadBitmap(service: android.content.Context): Bitmap? {
        return null
    }

    /**
     * HyperMaterial caches the light and dark material tokens in Kotlin lazy fields.
     * Updating only the e(boolean) factory cannot change a token that has
     * already been created, so update both cached tokens before reapplying it.
     */
    private fun updateCachedGlassTokens(helper: Any, blurRadiusDp: Float, opacity: Int): Boolean {
        var updated = false
        // The native material token stores blur as an integer dp value. Keep the
        // setting itself at 0.01 dp precision, then round only at this boundary.
        val clampedBlur = blurRadiusDp.coerceIn(0f, 45f).roundToInt()
        val clampedOpacity = opacity.coerceIn(0, 100)

        val lazyFields = when {
            helperField(helper, "f3548q") != null || helperField(helper, "f3549r") != null ->
                arrayOf("f3548q", "f3549r")
            helperField(helper, "f3469p") != null || helperField(helper, "f3470q") != null ->
                arrayOf("f3469p", "f3470q")
            else -> arrayOf("p", "q")
        }
        for ((index, lazyFieldName) in lazyFields.withIndex()) {
            try {
                val lazyValue = helperField(helper, lazyFieldName) ?: continue
                val getValue = lazyValue.javaClass.methods.firstOrNull {
                    it.name == "getValue" && it.parameterTypes.isEmpty()
                } ?: continue
                val token = getValue.invoke(lazyValue) ?: continue
                updated = setObjectFieldCompat(
                    token,
                    clampedBlur,
                    "f18726p",
                    "f18517p",
                    "p",
                ) || updated
                updated = updateGlassTokenColors(token, clampedOpacity) || updated
            } catch (_: Throwable) {
            }
        }

        return updated
    }

    /**
     * The keyboard token stores its material colors as ARGB arrays. Keep one
     * pristine copy per token and scale only alpha so repeated refreshes do
     * not compound the configured opacity.
     */
    private fun updateGlassTokenColors(token: Any, opacity: Int): Boolean {
        var updated = false
        val fields = arrayOf(
            arrayOf("f18711e", "f18502e", "e", "primary"),
            arrayOf("f18718i", "f18509i", "i", "secondary"),
        )
        synchronized(originalGlassTokenColors) {
            val originals = originalGlassTokenColors.getOrPut(token) { LinkedHashMap() }
            for ((fieldName, oldFieldName, legacyName, key) in fields) {
                val current = helperField(token, fieldName, oldFieldName, legacyName) as? IntArray ?: continue
                if (current.isEmpty()) continue
                val original = originals[key]?.takeIf { it.size == current.size }
                    ?: current.clone().also { originals[key] = it }
                val adjusted = original.map { color ->
                    val alpha = (Color.alpha(color) * (opacity / 100.0f)).toInt().coerceIn(0, 255)
                    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
                }.toIntArray()
                if (!current.contentEquals(adjusted)) {
                    updated = setObjectFieldCompat(token, adjusted, fieldName, oldFieldName, legacyName) || updated
                }
            }
        }
        return updated
    }

    /**
     * Re-apply all user-facing controls to the shader after HyperMaterial has
     * written its native uniforms. This is intentionally self-contained so it
     * can be called from b(View), delayed layout callbacks, and our refresh pass.
     */
    private fun applyRuntimeShaderControls(helper: Any, view: View): Boolean {
        if (!HookConfig.isStyleEnabled()) return false
        // bb.u stores the compiled shader in t; s is only the lazy shader
        // source string (Loc.j). Keep the old aliases for 0.2.520.
        val runtimeShader = helperField(helper, "f3550t", "t", "s") as? android.graphics.RuntimeShader
            ?: return false
        val parent = view.parent as? View
        val frameParams = view.layoutParams as? android.widget.FrameLayout.LayoutParams
        val horizontalMargin = (frameParams?.leftMargin ?: 0) * 2
        val verticalMargin = (frameParams?.topMargin ?: 0) * 2
        val width = ((parent?.width ?: view.width) - horizontalMargin).coerceAtLeast(0)
        val height = ((frameParams?.height?.takeIf { it > 0 }
            ?: ((parent?.height ?: view.height) - verticalMargin))).coerceAtLeast(0)
        if (width <= 0 || height <= 0) return false

        val density = view.resources.displayMetrics.density
        val radiusPx = resolveCornerRadiusPx(helper, view)
        val floating = isFloatingMaterial(helper)
        return runCatching {
            runtimeShader.setFloatUniform("uResolution", width.toFloat(), height.toFloat())
            if (floating) {
                runtimeShader.setFloatUniform(
                    "uRadii",
                    radiusPx,
                    radiusPx,
                    radiusPx,
                    radiusPx,
                )
            } else {
                runtimeShader.setFloatUniform("uRadii", radiusPx, 0.0f, 0.0f, radiusPx)
            }
            applyRuntimeShaderTuning(runtimeShader, helper, density)
            view.setRenderEffect(
                android.graphics.RenderEffect.createRuntimeShaderEffect(
                    runtimeShader,
                    "uInputContent",
                )
            )
            view.visibility = View.VISIBLE
            true
        }.getOrDefault(false)
    }

    /** Apply the user-facing edge and shadow controls to HyperMaterial's shader. */
    private fun applyRuntimeShaderTuning(
        runtimeShader: android.graphics.RuntimeShader,
        helper: Any,
        density: Float,
    ) {
        runCatching {
            runtimeShader.setFloatUniform(
                "uStrokeWidth",
                HookConfig.getStrokeWidth().coerceIn(0f, 4f) * density,
            )
            // bb.u stores the resolved dark state in l; k is a separate
            // boolean lifecycle flag. Older bb.t builds use k.
            val dark = readBooleanField(helper, "f3543l", "l", "k") ?: false
            // Keep the platform's calibrated light/dark alpha at 100%, then
            // scale it by the user percentage. This avoids turning the normal
            // 0.596/0.122 native highlight into an overbright solid white line.
            val nativeTopAlpha = if (dark) 0.12156863f else 0.59607846f
            runtimeShader.setFloatUniform(
                "uStrokeAlphaTop",
                nativeTopAlpha * (HookConfig.getHighlight().coerceIn(0, 100) / 100.0f),
            )
            runtimeShader.setFloatUniform(
                "uStrokeAlphaBottom",
                0.050980393f * (HookConfig.getBottomHighlight().coerceIn(0, 100) / 100.0f),
            )
            runtimeShader.setFloatUniform(
                "uShadowAlpha",
                // Native bb.u constants are C=0x08000000 (light) and
                // D=0x14484848 (dark); their alpha channels are the shadow
                // strengths. The 0.596/0.122 values above are stroke alpha.
                (if (dark) 0.078431375f else 0.03137255f) *
                    (HookConfig.getShadow().coerceIn(0, 100) / 100.0f),
            )
        }
    }

    /**
     * Keep the native blur view's background transparent. The target APK clears
     * that background 20 ms after applying material, so the outline belongs in
     * the foreground where it does not replace the blur surface.
     */
    private fun applySoftGlassForeground(
        view: View,
        @Suppress("UNUSED_PARAMETER") isDark: Boolean,
        @Suppress("UNUSED_PARAMETER") opacity: Int,
        @Suppress("UNUSED_PARAMETER") radiusPx: Float
    ) {
        // bb.t.c()/bb.t.b() already provide the calibrated blur, shadow and
        // outline. A second gradient foreground washes out key labels, so keep
        // the native material surface unobstructed.
        view.foreground = null
    }

    private fun getImeContentTopInset(
        service: android.inputmethodservice.InputMethodService
    ): Int? = try {
        val field = android.inputmethodservice.InputMethodService::class.java
            .getDeclaredField("mTmpInsets")
        field.isAccessible = true
        val insets = field.get(service)
        val topField = insets.javaClass.getField("contentTopInsets")
        topField.getInt(insets).takeIf { it > 0 }
    } catch (_: Throwable) {
        null
    }

    private fun resolveKeyboardContentTop(
        service: android.inputmethodservice.InputMethodService,
        targetView: View
    ): Int? {
        // mTmpInsets.contentTopInsets is still 0 during the first cold-start
        // frame. Xiaomi's material view has already been laid out at the real
        // keyboard top by bb.u.o, so its screen position is the reliable source.
        val helper = HookTools.getObjectField(service, "hyperMaterialHelper")
        val materialView = helper?.let(::materialView)
        if (materialView != null && materialView.isAttachedToWindow && materialView.height > 0) {
            val materialLocation = IntArray(2)
            val targetLocation = IntArray(2)
            materialView.getLocationOnScreen(materialLocation)
            targetView.getLocationOnScreen(targetLocation)
            val materialTop = materialLocation[1] - targetLocation[1]
            if (materialTop >= 0 && materialTop < targetView.height) {
                return materialTop
            }
        }

        return getImeContentTopInset(service)?.let { windowTop ->
            val decor = service.window?.window?.decorView
            if (decor != null) {
                val decorLocation = IntArray(2)
                val targetLocation = IntArray(2)
                decor.getLocationOnScreen(decorLocation)
                targetView.getLocationOnScreen(targetLocation)
                windowTop + decorLocation[1] - targetLocation[1]
            } else {
                windowTop
            }
        }
    }

    private fun invokeHelperMethod(helper: Any, name: String, vararg args: Any?): Any? {
        val method = helper.javaClass.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size
        } ?: return null
        method.isAccessible = true
        return method.invoke(helper, *args)
    }

    /** Resolve the configured solid color once so the keyboard card and bottom
     * system strip use exactly the same ARGB value. */
    private fun resolveSolidColor(colorString: String, opacity: Int): Int {
        val parsedColor = try {
            Color.parseColor(colorString)
        } catch (_: Throwable) {
            Color.parseColor("#1E1E2E")
        }
        val alpha = (Color.alpha(parsedColor) * (opacity.coerceIn(0, 100) / 100.0f))
            .toInt()
            .coerceIn(0, 255)
        return Color.argb(
            alpha,
            Color.red(parsedColor),
            Color.green(parsedColor),
            Color.blue(parsedColor)
        )
    }

    /**
     * HyperOS draws the navigation/accessory strip on a separate system surface.
     * Passing the translucent keyboard color to that surface makes it blend over
     * the system's dark backing (and sometimes through two bottom-bar layers), so
     * it appears much darker than the keyboard card. Flatten it over the light IME
     * backing first; an opaque result also avoids repeated alpha composition.
     */
    private fun resolveSolidBottomBarColor(colorString: String, opacity: Int): Int {
        val color = resolveSolidColor(colorString, opacity)
        val alpha = Color.alpha(color)
        fun compositeOverWhite(channel: Int): Int =
            ((channel * alpha + 255 * (255 - alpha)) / 255).coerceIn(0, 255)
        return Color.rgb(
            compositeOverWhite(Color.red(color)),
            compositeOverWhite(Color.green(color)),
            compositeOverWhite(Color.blue(color))
        )
    }

    private fun restoreCustomBackground(helper: Any, materialView: View) {
        if (!HookConfig.isStyleEnabled()) return

        val bgType = HookConfig.getBgType()
        if (bgType == 0) return

        try {
            // Disable pass-window blur and the rim render effect without
            // skipping bb.u.c(), which is also responsible for view setup.
            invokeHelperMethod(helper, "m")
        } catch (_: Throwable) {
        }

        materialView.alpha = 1.0f
        when (bgType) {
            1 -> {
                materialView.background = ColorDrawable(
                    resolveSolidColor(HookConfig.getBgColor(), HookConfig.getOpacity())
                )
            }
            2 -> {
                val service = helperService(helper)
                val bitmap = service?.let { getOrLoadBitmap(it) }
                if (service != null && bitmap != null && !bitmap.isRecycled) {
                    materialView.background = BitmapDrawable(service.resources, bitmap).apply {
                        alpha = (HookConfig.getOpacity().coerceIn(0, 100) * 255 / 100)
                    }
                }
            }
        }
        materialView.foreground = null
        materialView.elevation = 0f
        materialView.translationZ = 0f
        materialView.visibility = View.VISIBLE
        rimView(helper)?.visibility = View.GONE
        materialView.invalidate()
    }

    /**
     * Wait until Xiaomi's material view has a real surface size before applying
     * HyperMaterial. Calls from onCreateInputView/onStartInputView/bb.u.g can all
     * arrive while the non-floating glass view is still 0px tall on a cold start.
     * A generation per view coalesces those calls, then two bounded settle passes
     * cover the render-thread hand-off without leaving permanent polling behind.
     */
    private fun scheduleHyperMaterialRefresh(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        helper: Any?
    ) {
        if (helper == null || !HookConfig.isStyleEnabled()) return
        val materialView = materialView(helper) ?: return
        val rimView = rimView(helper)
        val dynamicGlass = HookConfig.getBgType() == 0

        val generation = synchronized(materialRefreshGenerations) {
            val next = (materialRefreshGenerations[materialView] ?: 0) + 1
            materialRefreshGenerations[materialView] = next
            next
        }

        // Hide only Xiaomi's background material views while their height is 0;
        // the keyboard content stays visible over the stable fallback tint.
        if (dynamicGlass && (!materialView.isAttachedToWindow || materialView.width <= 0 || materialView.height <= 0)) {
            materialView.visibility = View.INVISIBLE
            rimView?.visibility = View.INVISIBLE
        }

        val refresh = object : Runnable {
            private var layoutWaitFrames = 0
            private var settlePass = 0

            override fun run() {
                val isCurrent = synchronized(materialRefreshGenerations) {
                    materialRefreshGenerations[materialView] == generation
                }
                if (!isCurrent || !HookConfig.isStyleEnabled()) return

                if (!materialView.isAttachedToWindow || materialView.width <= 0 || materialView.height <= 0) {
                    if (dynamicGlass) {
                        materialView.visibility = View.INVISIBLE
                        rimView?.visibility = View.INVISIBLE
                    }
                    // bb.u.o normally resolves this on the next layout. Keep the
                    // fallback visible for at most two seconds on unusually slow starts.
                    if (layoutWaitFrames++ < 120) {
                        materialView.postOnAnimation(this)
                    } else {
                        synchronized(materialRefreshGenerations) {
                            if (materialRefreshGenerations[materialView] == generation) {
                                materialRefreshGenerations.remove(materialView)
                            }
                        }
                        HookTools.log(module, "KeyboardSurface: skipped zero-size material view after cold-start wait")
                    }
                    return
                }

                updateHyperMaterialViews(module, service, helper)

                if (dynamicGlass && settlePass < 2) {
                    val delayMs = if (settlePass++ == 0) 64L else 240L
                    materialView.postDelayed(this, delayMs)
                } else {
                    synchronized(materialRefreshGenerations) {
                        if (materialRefreshGenerations[materialView] == generation) {
                            materialRefreshGenerations.remove(materialView)
                        }
                    }
                }
            }
        }
        materialView.post(refresh)
    }

    private fun updateHyperMaterialViews(
        module: XposedModule,
        service: android.inputmethodservice.InputMethodService,
        helper: Any?
    ) {
        if (helper == null) return
        val f3500h = materialView(helper) ?: return
        val f3501i = rimView(helper)

        if (!HookConfig.isStyleEnabled()) return

        val bgType = HookConfig.getBgType() // 0: DYNAMIC_GLASS, 1: COLOR, 2: IMAGE
        val opacity = HookConfig.getOpacity()
        val blurRadiusDp = HookConfig.getBlurRadius()

        val density = f3500h.resources.displayMetrics.density
        val radiusPx = resolveCornerRadiusPx(helper, f3500h)
        val isDark = isDarkSurface(service)

        f3500h.post {
            try {
                // 1. Background Customization on f3500h (the actual keyboard card at bottom)
                when (bgType) {
                    0 -> { // HyperOS Dynamic Liquid Glass (系统通知中心同款动态毛玻璃)
                        val glassStrength = (opacity.coerceIn(0, 100) / 100.0f).coerceIn(0f, 1f)
                        // bb.u inserts this view at index 0, behind the keyboard.
                        // Keep full material strength; Z elevation is normalized
                        // below so rounded corners cannot lift it above key content.
                        f3500h.alpha = 1.0f

                        // This APK already ships a complete native material pipeline
                        // in bb.u.c(View). Tune its cached token and let that code
                        // configure pass-window blur, radius, blend colors and bloom.
                        f3500h.setBackgroundColor(Color.TRANSPARENT)
                        val tokenUpdated = updateCachedGlassTokens(helper, blurRadiusDp, opacity)
                        val materialApplied = try {
                            invokeHelperMethod(helper, "c", f3500h) as? Boolean ?: false
                        } catch (t: Throwable) {
                            HookTools.logError(module, "KeyboardSurface: native HyperMaterial apply failed", t)
                            false
                        }

                        applySoftGlassForeground(f3500h, isDark, opacity, radiusPx)
                        f3500h.visibility = View.VISIBLE

                        if (HookConfig.isVerboseLogEnabled()) {
                            HookTools.log(
                                module,
                                "KeyboardSurface: glass tokenUpdated=$tokenUpdated, materialApplied=$materialApplied, blur=${blurRadiusDp}dp"
                            )
                        }

                        // Update f3501i (RuntimeShader Rim Light & Shadow)
                        if (f3501i != null) {
                            try {
                                f3501i.alpha = 0.55f + 0.25f * glassStrength
                                // b(View) writes the native uniforms first;
                                // immediately follow it with our complete set.
                                invokeHelperMethod(helper, "b", f3501i)
                                applyRuntimeShaderControls(helper, f3501i)
                                f3501i.visibility = View.VISIBLE
                            } catch (_: Throwable) {
                                f3501i.visibility = View.GONE
                            }
                        }
                    }
                    1, 2 -> restoreCustomBackground(helper, f3500h)
                }

                // bb.u adds this surface at index 0 as the keyboard background.
                // Any positive Z elevation makes the solid/image rectangle draw
                // above the Compose key layer and obscures the key labels. Always
                // normalize it, including when the configured radius is zero.
                f3500h.elevation = 0f
                f3500h.translationZ = 0f

                // 2. Rounded Corners & Clipping on f3500h (Top corners only)
                if (radiusPx > 0f) {
                    f3500h.clipToOutline = true
                    f3500h.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, outline: Outline) {
                            val w = v.width
                            val h = v.height
                            if (w <= 0 || h <= 0) return
                            setKeyboardOutline(
                                outline,
                                v,
                                radiusPx,
                                isFloatingMaterial(helper),
                            )
                        }
                    }
                    f3500h.invalidateOutline()
                } else {
                    f3500h.clipToOutline = false
                    f3500h.outlineProvider = ViewOutlineProvider.BACKGROUND
                }

            } catch (t: Throwable) {
                HookTools.logError(module, "KeyboardSurface: failed to update material views", t)
            }
        }
    }

    private fun resolveBottomBarColor(
        service: android.inputmethodservice.InputMethodService,
        bgType: Int,
        opacity: Int,
        bgColor: String
    ): Int {
        if (bgType == 1) return resolveSolidBottomBarColor(bgColor, opacity)
        if (bgType != 0) return Color.TRANSPARENT
        // HyperOS draws this accessory/navigation strip on a separate surface.
        // Keep it fully transparent in dynamic-glass mode: the keyboard's own
        // material view supplies the glass, while the Compose search action key
        // retains its native blue functional-key color.
        return Color.TRANSPARENT
    }

    private fun updateBottomBarAppearance(
        service: android.inputmethodservice.InputMethodService,
        backgroundColor: Int
    ) {
        try {
            val isDark = isDarkSurface(service)
            val iconColor = if (isDark) Color.parseColor("#9E9E9E") else Color.parseColor("#757575")
            val rippleColor = if (isDark) Color.parseColor("#33FFFFFF") else Color.parseColor("#1F000000")

            // 1. HyperOS renders this accessory/navigation strip separately.
            val injectorClass = Class.forName("android.inputmethodservice.InputMethodServiceInjector")
            val customizeMethod = injectorClass.declaredMethods.find { it.name == "customizeBottomViewColor" }
            if (customizeMethod != null) {
                customizeMethod.isAccessible = true
                if (customizeMethod.parameterTypes.size == 4) {
                    customizeMethod.invoke(null, true, backgroundColor, iconColor, rippleColor)
                } else if (customizeMethod.parameterTypes.size == 2) {
                    customizeMethod.invoke(null, true, backgroundColor)
                }
            }
        } catch (_: Throwable) {}

        try {
            // 2. Match any explicit bottom container in DecorView as well.
            val window = service.window?.window
            val decor = window?.decorView as? ViewGroup
            if (decor != null) {
                for (i in 0 until decor.childCount) {
                    val child = decor.getChildAt(i)
                    val className = child.javaClass.name
                    if (className.contains("Bottom", ignoreCase = true) || className.contains("NavigationBar", ignoreCase = true)) {
                        child.setBackgroundColor(backgroundColor)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    fun applyStyle(module: XposedModule, service: android.inputmethodservice.InputMethodService, rootView: View) {
        HookConfig.syncFromProvider(service)
        if (!HookConfig.isStyleEnabled()) return

        val opacity = HookConfig.getOpacity()
        val bgType = HookConfig.getBgType()
        val bottomBarColor = resolveBottomBarColor(
            service,
            bgType,
            opacity,
            HookConfig.getBgColor()
        )
        activeBottomBarColor = bottomBarColor

        rootView.post {
            try {
                // 1. Transparent window & navigation bar (Do NOT add FLAG_BLUR_BEHIND to window as it blurs the entire screen!)
                val window = service.window?.window
                if (window != null) {
                    window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    window.setNavigationBarColor(bottomBarColor)
                    window.setNavigationBarContrastEnforced(false)
                    window.setDimAmount(0f)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    val decor = window.decorView as? ViewGroup
                    decor?.setBackgroundColor(Color.TRANSPARENT)
                }

                // 2. Blend the separately rendered bottom strip into the glass.
                updateBottomBarAppearance(service, bottomBarColor)

                // 3. Clear background on full-screen container views so nothing bleeds to top
                rootView.background = null

                val targetView: View = if (rootView is ViewGroup && rootView.childCount > 0) {
                    rootView.getChildAt(0)
                } else {
                    rootView
                }
                // The actual keyboard card is the helper's material view. Applying a second
                // gradient to the full Compose root made the key area opaque
                // and produced a visible seam above the navigation strip.
                targetView.background = null

                // For keyboard foreground keys and text, keep them solid and crisp!
                targetView.alpha = 1.0f

                // 4. Update HyperMaterialHelper's f3500h view which is the true keyboard bottom card
                val helper = HookTools.getObjectField(service, "hyperMaterialHelper")
                scheduleHyperMaterialRefresh(module, service, helper)
            } catch (t: Throwable) {
                HookTools.logError(module, "Error applying custom style to keyboard view", t)
            }
        }
    }
}

object SpeechGuard {

    fun install(module: XposedInterface, classLoader: ClassLoader) {
        // 1. Hook a8.n.f (MiclawErrorHelper.f) - Blocks CONTENT_MODERATION Toast
        val miclawErrorHelperClass = HookTools.findClass("a8.n", classLoader)
        if (miclawErrorHelperClass != null) {
            val methodF = HookTools.findMethodExact(
                miclawErrorHelperClass,
                "f",
                Context::class.java,
                String::class.java,
                String::class.java
            )
            if (methodF != null) {
                try {
                    module.hook(methodF).intercept { chain ->
                        if (!HookConfig.isVoiceModerationEnabled()) return@intercept chain.proceed()
                        val code = chain.getArg(2) as? String
                        if ("CONTENT_MODERATION" == code) {
                            if (HookConfig.isVerboseLogEnabled()) {
                                HookTools.log(module, "[Voice Moderation] Suppressed Miclaw CONTENT_MODERATION toast/error")
                            }
                            null
                        } else {
                            chain.proceed()
                        }
                    }
                    HookTools.log(module, "[Voice Moderation] Hooked a8.n.f(Context, String, String)")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook a8.n.f", t)
                }
            }
        } else {
            HookTools.logWarn(module, "[Voice Moderation] Class a8.n not found")
        }

        // 2. Hook s8.f.m - Error code 30002 mapper
        val s8FClass = HookTools.findClass("s8.f", classLoader)
        if (s8FClass != null) {
            val methodM = HookTools.findMethodExact(s8FClass, "m", Int::class.javaPrimitiveType ?: Integer.TYPE, String::class.java)
            if (methodM != null) {
                try {
                    module.hook(methodM).intercept { chain ->
                        if (!HookConfig.isVoiceModerationEnabled()) return@intercept chain.proceed()
                        val errorCode = (chain.getArg(0) as? Number)?.toInt() ?: 0
                        if (errorCode == 30002) {
                            if (HookConfig.isVerboseLogEnabled()) {
                                HookTools.log(module, "[Voice Moderation] Intercepted error 30002 in s8.f.m()")
                            }
                            chain.proceed(arrayOf(-1, chain.getArg(1)))
                        } else {
                            chain.proceed()
                        }
                    }
                    HookTools.log(module, "[Voice Moderation] Hooked s8.f.m(int, String)")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook s8.f.m", t)
                }
            }
        }

        // 3. Hook s8.d.e(Bundle) - ASR Callback error receiver
        val s8DClass = HookTools.findClass("s8.d", classLoader)
        if (s8DClass != null) {
            val methodE = HookTools.findMethodExact(s8DClass, "e", Bundle::class.java)
            if (methodE != null) {
                try {
                    module.hook(methodE).intercept { chain ->
                        if (!HookConfig.isVoiceModerationEnabled()) return@intercept chain.proceed()
                        val bundle = chain.getArg(0) as? Bundle
                        if (bundle != null) {
                            val code = bundle.getInt("code", -1)
                            if (code == 30002) {
                                if (HookConfig.isVerboseLogEnabled()) {
                                    HookTools.log(module, "[Voice Moderation] Suppressed ASR error 30002 callback in s8.d.e()")
                                }
                                return@intercept null
                            }
                        }
                        chain.proceed()
                    }
                    HookTools.log(module, "[Voice Moderation] Hooked s8.d.e(Bundle)")
                } catch (t: Throwable) {
                    HookTools.logError(module, "Failed to hook s8.d.e", t)
                }
            }
        }
    }
}
