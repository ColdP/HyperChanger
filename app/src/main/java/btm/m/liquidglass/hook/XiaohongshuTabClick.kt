package btm.m.liquidglass.hook

import android.view.View
import android.view.ViewGroup
import java.util.Collections
import java.util.IdentityHashMap

/** XHS exposes Rx click listeners on its real TabView/RelativeLayout cells. */
internal object XiaohongshuTabClick {
    fun perform(bar: ViewGroup, kind: XiaohongshuTabFeatures.Kind, cell: View, detectedTarget: View): Boolean {
        if (!bar.isAttachedToWindow || !bar.isShown || !isWithin(cell, bar)) return false
        val tried = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())
        // Match LiquidTab's native accessor -> performClick route. View.performClick
        // can invoke an override or a listener even when touch input is disabled.
        fun click(view: View, native: Boolean = false): Boolean = tried.add(view) && view.isShown &&
            (native || view.isEnabled) && view.performClick()
        nativeCell(bar, kind)?.let { nativeCell ->
            // LiquidTab also walks up from the accessor result, since versions
            // can put the business listener on its enclosing tab container.
            var target: View? = nativeCell
            repeat(6) {
                val view = target ?: return@repeat
                if (click(view, native = true)) return true
                target = if (view === bar) null else view.parent as? View
            }
            if (descendants(nativeCell).any { click(it, native = true) }) return true
        }
        if (click(cell)) return true
        val chain = generateSequence(detectedTarget as View?) { it.parent as? View }.toList()
        if (cell in chain) {
            for (view in chain.takeWhile { it !== cell }) if (click(view)) return true
        }
        return descendants(cell).any { click(it) }
    }

    private fun nativeCell(bar: ViewGroup, kind: XiaohongshuTabFeatures.Kind): View? {
        val getter = when (kind) {
            XiaohongshuTabFeatures.Kind.HOME -> "getIndexHome"
            XiaohongshuTabFeatures.Kind.MARKET -> "getIndexStore"
            XiaohongshuTabFeatures.Kind.PUBLISH -> "getIndexPost"
            XiaohongshuTabFeatures.Kind.MESSAGES -> "getIndexMessage"
            XiaohongshuTabFeatures.Kind.PROFILE -> "getIndexMe"
            else -> return null
        }
        // Semantic getters survive the reference APK's obfuscated IDs. No numeric
        // slot mapping or host-private field access is needed; feature fallback
        // below still supports versions that remove or rename these accessors.
        for (type in generateSequence(bar.javaClass as Class<*>?) { it.superclass }) {
            val method = type.declaredMethods.firstOrNull {
                it.name == getter && it.parameterCount == 0 && View::class.java.isAssignableFrom(it.returnType)
            } ?: continue
            return runCatching {
                method.isAccessible = true
                (method.invoke(bar) as? View)?.takeIf { it !== bar && isWithin(it, bar) }
            }.getOrNull()
        }
        return null
    }

    private fun isWithin(view: View, bar: ViewGroup): Boolean =
        generateSequence(view as View?) { it.parent as? View }.any { it === bar }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
