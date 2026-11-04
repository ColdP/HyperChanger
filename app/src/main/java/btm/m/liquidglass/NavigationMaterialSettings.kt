package btm.m.liquidglass

import android.content.SharedPreferences
import btm.m.os4.systemuihook.REMOTE_PREFERENCE_GROUP
import io.github.libxposed.service.XposedService
import org.json.JSONObject

/** Shared recipe multipliers; 100 preserves the renderer's original per-style defaults. */
data class NavigationMaterial(
    val blur: Int = 100,
    val opacity: Int = 100,
    val refraction: Int = 100,
    val thickness: Int = 100,
    val highlight: Int = 100,
    val dispersion: Boolean? = null,
    val selectionOpacity: Int = 100,
    val pressBoost: Int = 100,
) {
    fun normalized() = copy(blur = blur.coerceIn(0, 300), opacity = opacity.coerceIn(0, 250),
        refraction = refraction.coerceIn(0, 200), thickness = thickness.coerceIn(0, 200),
        highlight = highlight.coerceIn(0, 200), selectionOpacity = selectionOpacity.coerceIn(0, 300), pressBoost = pressBoost.coerceIn(0, 200))
    fun alpha(original: Float): Float = (original * opacity / 100f).coerceIn(0f, 1f)
    fun light(original: Float): Float = (original * highlight / 100f).coerceIn(0f, 1f)
}

object NavigationMaterialSettings {
    const val PREFIX = "navigation_material_"
    const val KEY_FOLLOW = "navigation_material_use_global"
    const val KEY_MODULE = "${PREFIX}module_enabled"
    const val PREVIEW_FILE = "navigation_material/preview.jpg"
    val styles = listOf(NavigationStyle.HYPER_OS_FLOATING, NavigationStyle.LIQUID_GLASS, NavigationStyle.HARMONY_OS_FLOATING, NavigationStyle.HYPER_OS)
    private val fields = listOf("blur", "opacity", "refraction", "thickness", "highlight", "dispersion", "selection_opacity", "press_boost")
    fun defaults(style: NavigationStyle): NavigationMaterial = when (style) {
        NavigationStyle.HYPER_OS_FLOATING -> NavigationMaterial(
            blur = 244, opacity = 42, refraction = 0, thickness = 0,
            highlight = 160, selectionOpacity = 169, dispersion = false,
        )
        NavigationStyle.HARMONY_OS_FLOATING -> NavigationMaterial(
            blur = 160, opacity = 110, refraction = 61, thickness = 140,
            highlight = 155, dispersion = true,
        )
        else -> NavigationMaterial()
    }
    fun key(style: NavigationStyle, field: String, packageName: String? = null): String {
        val name = "$PREFIX${style.preferenceValue}_$field"
        return packageName?.let { ScopedSettings.key(it, name) } ?: name
    }
    fun isRecipePreference(key: String, style: NavigationStyle, packageName: String? = null): Boolean =
        fields.any { key == key(style, it, packageName) }
    fun isPreference(key: String): Boolean {
        val name = if (key.startsWith("scope.")) key.substringAfterLast('.') else key
        return name == KEY_MODULE || name == KEY_FOLLOW || styles.any { style -> fields.any { name == key(style, it) } }
    }
    fun followsGlobal(p: SharedPreferences?, packageName: String): Boolean =
        p?.getBoolean(ScopedSettings.key(packageName, KEY_FOLLOW), true) ?: true
    fun effective(p: SharedPreferences?, style: NavigationStyle, packageName: String): NavigationMaterial =
        read(p, style, if (followsGlobal(p, packageName)) null else packageName)
    fun saveFollow(p: SharedPreferences, service: XposedService?, packageName: String, enabled: Boolean) {
        val key = ScopedSettings.key(packageName, KEY_FOLLOW)
        p.edit().putBoolean(key, enabled).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.putBoolean(key, enabled)?.apply()
    }
    fun moduleEnabled(p: SharedPreferences?): Boolean = p?.getBoolean(KEY_MODULE, false) ?: false
    fun read(p: SharedPreferences?, style: NavigationStyle, packageName: String? = null): NavigationMaterial {
        val default = defaults(style)
        if (style !in styles || p == null) return default
        return NavigationMaterial(
            blur = p.getInt(key(style, "blur", packageName), default.blur),
            opacity = p.getInt(key(style, "opacity", packageName), default.opacity),
            refraction = p.getInt(key(style, "refraction", packageName), default.refraction),
            thickness = p.getInt(key(style, "thickness", packageName), default.thickness),
            highlight = p.getInt(key(style, "highlight", packageName), default.highlight),
            dispersion = p.getInt(key(style, "dispersion", packageName), default.dispersion?.let { if (it) 1 else 0 } ?: -1)
                .let { if (it < 0) null else it != 0 },
            selectionOpacity = p.getInt(key(style, "selection_opacity", packageName), default.selectionOpacity),
            pressBoost = p.getInt(key(style, "press_boost", packageName), default.pressBoost),
        ).normalized()
    }
    fun save(p: SharedPreferences, service: XposedService?, style: NavigationStyle, material: NavigationMaterial, packageName: String? = null) {
        require(style in styles)
        val value = material.normalized()
        fun write(target: SharedPreferences) = target.edit().apply {
            putInt(key(style, "blur", packageName), value.blur); putInt(key(style, "opacity", packageName), value.opacity)
            putInt(key(style, "refraction", packageName), value.refraction); putInt(key(style, "thickness", packageName), value.thickness)
            putInt(key(style, "highlight", packageName), value.highlight)
            putInt(key(style, "dispersion", packageName), value.dispersion?.let { if (it) 1 else 0 } ?: -1)
            putInt(key(style, "selection_opacity", packageName), value.selectionOpacity)
            putInt(key(style, "press_boost", packageName), value.pressBoost)
        }.apply()
        write(p)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let(::write)
    }
    fun saveModule(p: SharedPreferences, service: XposedService?, enabled: Boolean) {
        p.edit().putBoolean(KEY_MODULE, enabled).apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.edit()?.putBoolean(KEY_MODULE, enabled)?.apply()
    }
    fun syncPreferences(local: SharedPreferences, remote: SharedPreferences) {
        val values = remote.all.filterKeys(::isPreference) + local.all.filterKeys(::isPreference)
        for (target in listOf(local, remote)) target.edit().apply {
            values.forEach { (key, value) -> when (value) {
                is Boolean -> putBoolean(key, value)
                is Int -> putInt(key, value)
            } }
        }.apply()
    }
    fun completePreset(values: JSONObject) {
        fun put(key: String, type: String, value: Any) {
            if (!values.has(key)) values.put(key, JSONObject().put("type", type).put("value", value))
        }
        put(KEY_MODULE, "boolean", false)
        for (packageName in listOf<String?>(null) + ScopedSettings.apps.map { it.packageName }) {
        if (packageName != null) put(ScopedSettings.key(packageName, KEY_FOLLOW), "boolean", true)
        for (style in styles) {
            val default = defaults(style)
            val fieldDefaults = mapOf(
                "blur" to default.blur, "opacity" to default.opacity,
                "refraction" to default.refraction, "thickness" to default.thickness,
                "highlight" to default.highlight, "selection_opacity" to default.selectionOpacity,
                "press_boost" to default.pressBoost,
                "dispersion" to (default.dispersion?.let { if (it) 1 else 0 } ?: -1),
            )
            for ((field, value) in fieldDefaults) put(key(style, field, packageName), "int", value)
        }
        }
    }
}
