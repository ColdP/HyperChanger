// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.util.Log
import android.view.View
import android.widget.TextView
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/** The plugin obtains this row from the SystemUI host's CombinedHeaderController. */
internal class ControlCenterCarrierHook(private val module: XposedModule) {
    private class CarrierState(view: View) {
        private val reference = WeakReference(view)
        val carrier: View get() = checkNotNull(reference.get())
        val text: TextView get() = field(carrier, "carrierTextView") as TextView
        val hd: TextView get() = field(carrier, "hdText") as TextView
        val plus: TextView get() = field(carrier, "plusText") as TextView
        var nativeText: CharSequence = text.text
        var nativeTextVisibility = text.visibility
        var nativeDescription = carrier.contentDescription
        var nativeHd = hd.visibility == View.VISIBLE
        val nativeHdText: CharSequence = hd.text
        var nativePlus = plus.visibility == View.VISIBLE
        var nativeVisibility = carrier.visibility
        var customApplied = false
        var hidden = false
        var hdSuppressed = false
        var networkTypeApplied = false
    }

    // Values must not strongly reference their weak keys.
    private val states = WeakHashMap<View, CarrierState>()
    private val layouts = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val installed = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        targetClasses.forEach { name ->
            runCatching { classLoader.loadClass(name) }.getOrNull()?.let { onClassLoaded(it, preferences) }
        }
    }

    @Synchronized
    fun onClassLoaded(clazz: Class<*>, preferences: SharedPreferences) {
        if (clazz.name !in targetClasses || !installed.add(clazz)) return
        runCatching {
            when (clazz.name) {
                CARRIER -> installCarrier(clazz, preferences)
                CALLBACK -> installCallback(clazz)
                LAYOUT -> installLayout(clazz, preferences)
                NETWORK_NAME_UPDATE -> installNetworkNameUpdates(clazz, preferences)
            }
        }.onFailure {
            installed.remove(clazz)
            module.log(Log.WARN, "HyperChanger", "Could not hook control-center carrier: ${clazz.name}", it)
        }
    }

    private fun installCarrier(clazz: Class<*>, preferences: SharedPreferences) {
        module.hook(clazz.getDeclaredMethod("shouldShow"))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("control-center:carrier-visibility")
            .intercept { chain ->
                val original = chain.proceed() as Boolean
                val carrier = chain.thisObject as View
                if (!inControlCenter(carrier)) original else !state(carrier).hidden && original
            }
        module.hook(clazz.getDeclaredMethod("updateHDText", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("control-center:carrier-hd")
            .intercept { chain ->
                val carrier = chain.thisObject as View
                if (!inControlCenter(carrier)) return@intercept chain.proceed()
                val state = state(carrier)
                val showHd = field(carrier, "showHdIcon") as Boolean
                state.nativeHd = chain.getArg(0) as Boolean && showHd
                state.nativePlus = state.nativeHd && chain.getArg(1) as Boolean
                val settings = readControlCenterCarrierSettings(preferences)
                val result = if (settings.hdMode != 0 || settings.customEnabled) {
                    state.hdSuppressed = true
                    chain.proceed(arrayOf(false, false))
                } else {
                    chain.proceed()
                }
                applyNetworkMarker(state, settings)
                result
            }
        module.hook(clazz.getDeclaredMethod("updateContentDesc", Int::class.javaPrimitiveType))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("control-center:carrier-description")
            .intercept { chain ->
                val result = chain.proceed()
                val carrier = chain.thisObject as View
                if (inControlCenter(carrier)) {
                    val settings = readControlCenterCarrierSettings(preferences)
                    updateMarkerDescription(state(carrier), settings)
                }
                result
            }
    }

    private fun installCallback(clazz: Class<*>) {
        module.hook(clazz.getDeclaredMethod("onCarrierTextChanged", Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("control-center:carrier-native-text")
            .intercept { chain ->
                val result = chain.proceed()
                val carrier = field(chain.thisObject, "this\$0") as View
                if (inControlCenter(carrier) && field(carrier, "innerCarrierSlotId") == chain.getArg(2)) {
                    val state = state(carrier)
                    // Keep the latest OEM text even while a replacement is displayed, so
                    // disabling customization restores SIM/network changes made meanwhile.
                    state.nativeText = state.text.text
                    state.nativeTextVisibility = state.text.visibility
                    state.nativeDescription = carrier.contentDescription
                    state.customApplied = false
                }
                result
            }
    }

    private fun installLayout(clazz: Class<*>, preferences: SharedPreferences) {
        module.hook(clazz.getDeclaredMethod("onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("control-center:carrier-row")
            .intercept { chain ->
                val layout = chain.thisObject as View
                if (!isControlCenterLayout(layout)) return@intercept chain.proceed()
                synchronized(layouts) { layouts.add(layout) }
                val settings = readControlCenterCarrierSettings(preferences)
                val dataSlot = dataSlot()
                listOf("leftCarrierTextView", "rightCarrierTextView").forEachIndexed { index, name ->
                    val carrier = field(layout, name) as View
                    val state = state(carrier)
                    // A custom line has no SIM ownership. Per-SIM filtering applies to the
                    // OEM line; "hide all" also hides a custom line.
                    val custom = settings.customEnabled && index == 0
                    val hide = if (settings.customEnabled) settings.hideMode == 5 || index == 1
                    else hideControlCenterCarrierSlot(settings.hideMode, slot(carrier, index), dataSlot)
                    applyCarrier(state, settings, custom, hide)
                }
                val result = chain.proceed()
                if (settings.hdMode != 0 || settings.customEnabled) {
                    listOf("leftCarrierTextView", "rightCarrierTextView").forEach { name ->
                        updateMarkerDescription(state(field(layout, name) as View), settings)
                    }
                }
                if (settings.customEnabled || settings.hideMode != 0) {
                    // Both implementations exist: a drawable divider in CC and a text
                    // divider in shared layouts. Keep neither when filtering the CC row.
                    (field(layout, "carrierSeparatorView") as View).visibility = View.GONE
                    (field(layout, "carrierSeparatorText") as View).visibility = View.GONE
                }
                result
            }
        if (listener == null) {
            val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key in preferenceKeys) {
                    val snapshot = synchronized(layouts) { layouts.map(::WeakReference) }
                    mainHandler.post { snapshot.forEach { it.get()?.requestLayout() } }
                }
            }
            listener = changeListener
            preferences.registerOnSharedPreferenceChangeListener(changeListener)
        }
    }

    private fun applyCarrier(state: CarrierState, settings: ControlCenterCarrierSettings, custom: Boolean, hide: Boolean) {
        val carrier = state.carrier
        if (custom) {
            if (!state.customApplied) {
                state.nativeText = state.text.text
                state.nativeTextVisibility = state.text.visibility
                state.nativeDescription = carrier.contentDescription
            }
            if (state.text.text.toString() != settings.customText) state.text.text = settings.customText
            state.text.visibility = View.VISIBLE
            carrier.contentDescription = settings.customText
            state.customApplied = true
        } else if (state.customApplied) {
            state.text.text = state.nativeText
            state.text.visibility = state.nativeTextVisibility
            carrier.contentDescription = state.nativeDescription
            state.customApplied = false
        }
        applyNetworkMarker(state, settings)
        if (hide) {
            if (!state.hidden) state.nativeVisibility = carrier.visibility
            carrier.visibility = View.GONE
        } else if (state.hidden) {
            carrier.visibility = state.nativeVisibility
        }
        state.hidden = hide
    }

    private fun applyNetworkMarker(state: CarrierState, settings: ControlCenterCarrierSettings) {
        val suppressHd = settings.hdMode != 0 || settings.customEnabled
        if (settings.hdMode != 2 || settings.customEnabled) {
            if (state.networkTypeApplied) state.hd.text = state.nativeHdText
            state.networkTypeApplied = false
        }
        if (suppressHd) {
            if (!state.hdSuppressed) {
                state.nativeHd = state.hd.visibility == View.VISIBLE
                state.nativePlus = state.plus.visibility == View.VISIBLE
            }
            if (settings.hdMode == 2 && !settings.customEnabled) {
                val networkType = realNetworkType(state.carrier)
                if (state.hd.text.toString() != networkType) state.hd.text = networkType
                state.hd.visibility = if (networkType.isBlank()) View.GONE else View.VISIBLE
                state.networkTypeApplied = true
            } else {
                state.hd.visibility = View.GONE
            }
            state.plus.visibility = View.GONE
        } else if (state.hdSuppressed) {
            state.hd.visibility = if (state.nativeHd) View.VISIBLE else View.GONE
            state.plus.visibility = if (state.nativePlus) View.VISIBLE else View.GONE
        }
        state.hdSuppressed = suppressHd
        updateMarkerDescription(state, settings)
    }

    private fun updateMarkerDescription(state: CarrierState, settings: ControlCenterCarrierSettings) {
        if (settings.customEnabled || settings.hdMode == 1) {
            state.carrier.contentDescription = state.text.text
        } else if (settings.hdMode == 2) {
            state.carrier.contentDescription = listOf(
                state.text.text.toString(),
                if (state.hd.visibility == View.VISIBLE) state.hd.text.toString() else "",
            ).filter { it.isNotBlank() }.joinToString(", ")
        }
    }

    private fun realNetworkType(carrier: View): String = runCatching {
        val slot = field(carrier, "innerCarrierSlotId") as Int
        val hdController = field(carrier, "hdController") ?: return@runCatching ""
        val interactor = field(hdController, "miuiMobileIconsInt") ?: return@runCatching ""
        val flow = field(interactor, "showNames") ?: return@runCatching ""
        // The OEM pipeline resolves 4G/5G/5GA from service state, NR connection,
        // modem-specific 5GA flags and operator configuration, indexed by phoneId.
        val names = flowValue(flow) as? Array<*> ?: return@runCatching ""
        names.getOrNull(slot) as? String ?: ""
    }.getOrDefault("")

    private fun installNetworkNameUpdates(clazz: Class<*>, preferences: SharedPreferences) {
        module.hook(clazz.getDeclaredMethod("invokeSuspend", Any::class.java))
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .setId("control-center:carrier-network-type-updates")
            .intercept { chain ->
                val flow = field(chain.thisObject, "\$showNameFlow")
                val before = flow?.let(::flowValue)
                val result = chain.proceed()
                if (flow != null && before !== flowValue(flow) &&
                    readControlCenterCarrierSettings(preferences).hdMode == 2
                ) {
                    // The OEM updater replaces its slot-indexed array when a type changes.
                    // Refresh the row even when its carrier name and HD status stay the same.
                    val snapshot = synchronized(layouts) { layouts.map(::WeakReference) }
                    mainHandler.post { snapshot.forEach { it.get()?.requestLayout() } }
                }
                result
            }
    }

    private fun state(carrier: View): CarrierState = synchronized(states) {
        states[carrier] ?: CarrierState(carrier).also { states[carrier] = it }
    }

    private fun slot(carrier: View, fallback: Int): Int =
        (field(carrier, "innerCarrierSlotId") as Int).takeIf { it in 0..1 } ?: fallback

    private fun dataSlot(): Int = runCatching {
        val subId = SubscriptionManager.getActiveDataSubscriptionId().takeIf(SubscriptionManager::isValidSubscriptionId)
            ?: SubscriptionManager.getDefaultDataSubscriptionId()
        if (SubscriptionManager.isValidSubscriptionId(subId)) SubscriptionManager.getSlotIndex(subId) else -1
    }.getOrDefault(-1)

    private fun inControlCenter(view: View): Boolean {
        var parent = view.parent as? View
        while (parent != null) {
            if (parent.javaClass.name == LAYOUT) return isControlCenterLayout(parent)
            parent = parent.parent as? View
        }
        return false
    }

    private fun isControlCenterLayout(view: View): Boolean = runCatching {
        view.resources.getResourceEntryName(view.id) == "normal_control_center_carrier_layout"
    }.getOrDefault(false)

    companion object {
        private const val CARRIER = "com.android.systemui.controlcenter.shade.ControlCenterCarrierText"
        private const val CALLBACK = "com.android.systemui.controlcenter.shade.ControlCenterCarrierText\$mCarrierTextCallback\$1"
        private const val LAYOUT = "com.android.systemui.controlcenter.shade.MiuiCarrierTextLayout"
        private const val NETWORK_NAME_UPDATE = "com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MiuiMobileIconInteractorImpl\$showName\$2"
        private val targetClasses = setOf(CARRIER, CALLBACK, LAYOUT, NETWORK_NAME_UPDATE)
        private val preferenceKeys = setOf(
            KEY_CONTROL_CENTER_CARRIER_HD_MODE, KEY_CONTROL_CENTER_CARRIER_CUSTOM_ENABLED,
            KEY_CONTROL_CENTER_CARRIER_CUSTOM_TEXT, KEY_CONTROL_CENTER_CARRIER_HIDE_MODE,
        )

        private fun field(instance: Any, name: String): Any? =
            instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)

        private fun flowValue(flow: Any): Any? =
            flow.javaClass.getMethod("getValue").apply { isAccessible = true }.invoke(flow)
    }
}
