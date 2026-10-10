// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.TimeZone
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Like the top buttons, inject a native view without replacing the OEM header. */
internal class ControlCenterClockHook(private val module: XposedModule) {
    private val installed = Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>())
    private val bindings = WeakHashMap<ViewGroup, WeakReference<ClockBinding>>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun install(classLoader: ClassLoader, preferences: SharedPreferences) {
        runCatching { classLoader.loadClass(COMBINED_HEADER) }.getOrNull()?.let { onClassLoaded(it, preferences) }
    }

    @Synchronized
    fun onClassLoaded(clazz: Class<*>, preferences: SharedPreferences) {
        if (clazz.name != COMBINED_HEADER || !installed.add(clazz)) return
        runCatching {
            listOf("start", "updateControlCenterHeaderLayout").forEach { name ->
                module.hook(clazz.getDeclaredMethod(name))
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("control-center:big-clock:$name")
                    .intercept { chain ->
                        val result = chain.proceed()
                        bind(chain.thisObject, preferences)
                        result
                    }
            }
            if (preferenceListener == null) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == null || key in preferenceKeys) {
                        val snapshot = synchronized(bindings) { bindings.values.toList() }
                        handler.post { snapshot.forEach { it.get()?.reload() } }
                    }
                }
                preferenceListener = listener
                preferences.registerOnSharedPreferenceChangeListener(listener)
            }
        }.onFailure {
            installed.remove(clazz)
            module.log(Log.WARN, "HyperChanger", "Could not hook control-center big clock", it)
        }
    }

    private fun bind(controller: Any, preferences: SharedPreferences) {
        val date = field(controller, "controlCenterDateView") as? TextView ?: return
        val root = field(controller, "headerView") as? FrameLayout ?: return
        synchronized(bindings) {
            if (bindings[root]?.get() == null) {
                val binding = ClockBinding(root, date, preferences)
                bindings[root] = WeakReference(binding)
                binding.install()
            }
        }
    }

    private inner class ClockBinding(
        private val root: FrameLayout,
        private val date: TextView,
        private val preferences: SharedPreferences,
    ) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
        private val clock = TextView(root.context).apply {
            tag = "hyperchanger.control-center.big-clock"
            id = View.generateViewId()
            setSingleLine(true)
            includeFontPadding = false
            isClickable = false
            isFocusable = false
            visibility = View.GONE
        }
        private var settings = readControlCenterClockSettings(preferences)
        private var formatter: SimpleDateFormat? = null
        private var formatterLocale: java.util.Locale? = null
        private var lastSecond = Long.MIN_VALUE
        private var scheduled = false
        private var observer: ViewTreeObserver? = null
        private val matrix = Matrix()
        private val anchor = FloatArray(4)
        private val powerManager = root.context.getSystemService(PowerManager::class.java)
        private val tick = Runnable {
            scheduled = false
            update()
        }

        fun install() {
            root.addOnAttachStateChangeListener(this)
            if (root.isAttachedToWindow) onViewAttachedToWindow(root)
            reload()
        }

        fun reload() {
            settings = readControlCenterClockSettings(preferences)
            formatter = null
            lastSecond = Long.MIN_VALUE
            if (settings.enabled) {
                if (clock.parent == null) root.addView(clock, FrameLayout.LayoutParams(-2, -2))
                clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.sizeSp)
            } else {
                (clock.parent as? ViewGroup)?.removeView(clock)
                cancelTick()
            }
            update()
        }

        override fun onViewAttachedToWindow(view: View) {
            observer = root.viewTreeObserver.also { it.addOnPreDrawListener(this) }
            update()
        }

        override fun onViewDetachedFromWindow(view: View) {
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
            observer = null
            cancelTick()
        }

        override fun onPreDraw(): Boolean {
            update()
            return true
        }

        private fun cancelTick() {
            handler.removeCallbacks(tick)
            scheduled = false
        }

        private fun update() {
            if (!settings.enabled) {
                clock.visibility = View.GONE
                cancelTick()
                return
            }
            // Only the control-center date controls visibility. Notification, editing,
            // brightness-mirror and collapsed states retain their OEM behavior.
            var alpha = 1f
            var ancestor: View? = date
            while (ancestor != null && ancestor !== root) {
                if (ancestor.visibility != View.VISIBLE) alpha = 0f
                alpha *= ancestor.alpha
                ancestor = ancestor.parent as? View
            }
            val interactive = powerManager?.isInteractive != false
            val visible = root.isAttachedToWindow && root.isShown &&
                root.windowVisibility == View.VISIBLE && interactive && ancestor === root &&
                alpha > 0f && date.width > 0 && date.height > 0
            if (!visible) {
                clock.visibility = View.GONE
                cancelTick()
                return
            }
            clock.visibility = View.VISIBLE
            clock.alpha = alpha.coerceIn(0f, 1f)
            if (clock.currentTextColor != date.currentTextColor) clock.setTextColor(date.currentTextColor)
            // Read locale and timezone again after system changes; never retain an old zone.
            val locale = date.resources.configuration.locales[0]
            if (formatter == null || formatterLocale != locale) {
                formatter = SimpleDateFormat(settings.format, locale)
                formatterLocale = locale
                lastSecond = Long.MIN_VALUE
            }
            val currentFormatter = checkNotNull(formatter)
            val zone = TimeZone.getDefault()
            if (currentFormatter.timeZone != zone) {
                currentFormatter.timeZone = zone
                lastSecond = Long.MIN_VALUE
            }
            val now = System.currentTimeMillis()
            if (lastSecond != now / 1000L) {
                val formatted = formatControlCenterClock(currentFormatter, now)
                val text = SpannableString(formatted.text)
                if (settings.redOne) formatted.hourOneIndices.forEach { index ->
                    text.setSpan(ForegroundColorSpan(Color.rgb(235, 0, 41)), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                clock.text = text
                clock.contentDescription = formatted.text
                lastSecond = now / 1000L
            }
            clock.typeface = date.typeface
            clock.layoutDirection = date.layoutDirection
            clock.measure(
                View.MeasureSpec.makeMeasureSpec(root.width.coerceAtLeast(1), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            // Map the date through all OEM translations/scales into its sibling's parent.
            // The sibling avoids altering ConstraintLayout's OEM constraint sets.
            matrix.reset()
            date.transformMatrixToGlobal(matrix)
            root.transformMatrixToLocal(matrix)
            anchor[0] = 0f
            anchor[1] = 0f
            anchor[2] = date.width.toFloat()
            anchor[3] = 0f
            matrix.mapPoints(anchor)
            val rtl = date.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val left = (if (rtl) anchor[2] - clock.measuredWidth else anchor[0]).roundToInt()
            val top = (anchor[1] - settings.gapDp * root.resources.displayMetrics.density - clock.measuredHeight).roundToInt()
            if (clock.left != left || clock.top != top || clock.width != clock.measuredWidth || clock.height != clock.measuredHeight) {
                clock.layout(left, top, left + clock.measuredWidth, top + clock.measuredHeight)
            }
            if (!scheduled) {
                scheduled = true
                handler.postDelayed(tick, 1000L - now % 1000L)
            }
        }
    }

    companion object {
        private const val COMBINED_HEADER = "com.android.systemui.controlcenter.shade.CombinedHeaderController"
        private val preferenceKeys = setOf(
            KEY_CONTROL_CENTER_CLOCK_ENABLED, KEY_CONTROL_CENTER_CLOCK_FORMAT, KEY_CONTROL_CENTER_CLOCK_SIZE,
            KEY_CONTROL_CENTER_CLOCK_GAP, KEY_CONTROL_CENTER_CLOCK_RED_ONE,
        )

        private fun field(instance: Any, name: String): Any? =
            instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
    }
}
