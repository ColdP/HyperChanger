// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.island
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * `op numstate`: the keyguard stack's "N notifications" state (KeyguardNotificationState.NUMBER)
 * driven from outside, and what the rows and the count look like while it is - the first step of
 * folding ordinary notifications ColorOS's way, where the rows never leave the stack and the
 * capsule is only a layout of it (see the coloros-lockscreen-notification-capsule note).
 *
 *   --es do read                       (default) the state, the targets, the count view, every row
 *   --es do number|stack|list [--ez anim false]
 *                                      scroll the stack to that state the way SystemUI itself does
 *                                      it for the full-screen AOD (NotificationContainerViewBinder
 *                                      lambda 16, read 2026-09-30)
 *   --es do layer [--ef dy 0] [--ef s 1] [--ef a 1] [--es key part]
 *                                      one layer of our own in each row's folmeValueMap
 *   --es do unlayer                    take it out again
 *   --es do normals [--ez on false]    ordinary notifications left in the stack (on) or given
 *                                      back to the stack island (off); not saved
 *
 * Nothing here is used by the islands yet; it only reads, or does what it is told once.
 */
object NumStateProbe {

    /** Our layer's type in ViewState.mInjector.folmeValueMap. SystemUI's are 11018-11038. */
    private const val LAYER_TYPE = 11090

    @JvmStatic fun run(ctx: Context, i: Intent): String {
        val sb = StringBuilder("at=${SystemClock.uptimeMillis()}")
        val stack = MiniPlayerRuntime.stackForProbe() ?: return "$sb no stack (no mini player up?)"
        val model = runCatching { modelOf(stack) }.getOrElse { return "$sb no model: $it" }
        when (val what = i.getStringExtra("do") ?: "read") {
            "read" -> {}
            "number", "stack", "list" ->
                sb.append(" || go ").append(what).append(": ")
                    .append(runCatching { goTo(stack, model, what.uppercase(), i.getBooleanExtra("anim", true)) }
                        .getOrElse { "failed: " + it.stackTraceToString().take(600) })
            "layer" -> sb.append(" || layer: ").append(runCatching {
                layer(stack, i.getStringExtra("key"), i.getFloatExtra("dy", 0f),
                    i.getFloatExtra("s", 1f), i.getFloatExtra("a", 1f))
            }.getOrElse { "failed: $it" })
            "unlayer" -> sb.append(" || unlayer: ").append(runCatching { unlayer(stack) }.getOrElse { "failed: $it" })
            "normals" -> {
                val on = i.getBooleanExtra("on", true)
                LockIslands.setNormalsInStack(on)
                sb.append(" || ordinary notifications ").append(if (on) "in the stack" else "back in the island")
                    .append(" (the list rebuilds on the next frame; read again)")
            }
            else -> sb.append(" || unknown do=").append(what)
        }
        sb.append(" || ").append(NumState.describe())
        sb.append(" || ").append(describe(ctx, stack, model))
        return sb.toString()
    }

    private fun modelOf(stack: ViewGroup): Any {
        val controller = Xp.getObjectField(stack, "mController")
        val injector = Xp.getObjectField(controller, "mNsslControllerInjector")
        val helper = Xp.getObjectField(injector, "numStateTouchHelper")
        return Xp.callMethod(Xp.getObjectField(helper, "notifContainerViewModel"), "get")
    }

    private fun value(flowOwner: Any, getter: String): Any? =
        Xp.callMethod(Xp.callMethod(flowOwner, getter), "getValue")

    /** KeyguardNotificationState.<name>, from the class of the state the model holds now. */
    @Suppress("UNCHECKED_CAST")
    private fun stateConst(model: Any, name: String): Any {
        val now = value(model, "getCurrentsStackState") as Enum<*>
        return java.lang.Enum.valueOf(now.javaClass as Class<out Enum<*>>, name)
    }

    /** Where the stack's own scroll puts [state]: the scrollY SystemUI would scroll to. */
    private fun scrollFor(model: Any, state: String): Int {
        val info = value(model, "getOnUpdateChildSampleStackInfo")!!
        val event = Xp.callMethod(model, "notifStackStateToEvent", stateConst(model, state))
        val status = Xp.callMethod(model, "getKeyguardStackStatusInfo", info, event)
        val top = (Xp.callMethod(model, "switchStackStatus", status) as Number).toFloat()
        val focus = (Xp.getObjectField(info, "focusNotifsHeight") as Number).toFloat()
        val bound = (Xp.callMethod(model, "positionWithoutMinScrollRange", focus) as Number).toFloat()
        return (bound - top).toInt()
    }

    private fun goTo(stack: ViewGroup, model: Any, state: String, anim: Boolean): String {
        val y = scrollFor(model, state)
        val refactor = Xp.callMethod(Xp.getObjectField(model, "notifContainerRefactor"), "get")
        val scroller = Xp.callMethod(refactor, "getOverScroller")
        val cur = (Xp.callMethod(refactor, "getOwnScrollY") as Number).toInt()
        val injector = Xp.getObjectField(Xp.getObjectField(stack, "mController"), "mNsslControllerInjector")
        if (!anim) {
            Xp.callMethod(scroller, "abortAnimation")
            Xp.callMethod(stack, "setOwnScrollY", y)
            return "scrollY $cur -> $y (set)"
        }
        val animator = Xp.callMethod(Xp.getObjectField(model, "notificationScreenOnOffAnimator"), "get")
        val ease = animator.javaClass.getField("screenOnScrollYEaseStyle").get(null)
        Xp.callMethod(scroller, "startFolmeScroll", 0, cur, y - cur, 0, ease)
        Xp.callMethod(stack, "animateScroll")
        val type = topChangeType(injector, "NOTIFS_CHANGED")
        Xp.callMethod(injector, "onStartScroll", y.toFloat(), type)
        return "scrollY $cur -> $y (folme)"
    }

    /** NotificationTopChangeType.<name>, from onStartScroll's own second parameter. */
    @Suppress("UNCHECKED_CAST")
    private fun topChangeType(injector: Any, name: String): Any {
        val m = injector.javaClass.methods.first { it.name == "onStartScroll" && it.parameterTypes.size == 2 }
        return java.lang.Enum.valueOf(m.parameterTypes[1] as Class<out Enum<*>>, name)
    }

    // ---------------------------------------------------------------- our layer on the rows

    private fun rows(stack: ViewGroup): List<View> =
        (0 until stack.childCount).map(stack::getChildAt)
            .filter { it.javaClass.name.endsWith("ExpandableNotificationRow") }

    private fun keyOf(row: View): String? = runCatching {
        (Xp.getObjectField(Xp.callMethod(row, "getEntry"), "mSbn") as? android.service.notification.StatusBarNotification)?.key
    }.getOrNull()

    private fun layers(row: View): Any = Xp.getObjectField(Xp.callMethod(row, "getViewState"), "mInjector")

    private fun layer(stack: ViewGroup, key: String?, dy: Float, s: Float, a: Float): String {
        var n = 0
        for (row in rows(stack)) {
            if (key != null && keyOf(row)?.contains(key) != true) continue
            val entry = Xp.callMethod(layers(row), "ensureValueEntry", LAYER_TYPE)
            Xp.setObjectField(entry, "translationY", dy)
            Xp.setObjectField(entry, "scaleX", s)
            Xp.setObjectField(entry, "scaleY", s)
            Xp.setObjectField(entry, "alpha", a)
            // The injector's is a default method of an interface, which Xp.callMethod does not
            // look in; the row's own (PropertiesFrameLayout) is what that one calls anyway.
            Xp.callMethod(row, "scheduleUpdateProperties")
            n++
        }
        return "$n rows dy=$dy s=$s a=$a"
    }

    private fun unlayer(stack: ViewGroup): String {
        var n = 0
        for (row in rows(stack)) {
            val map = Xp.getObjectField(layers(row), "folmeValueMap") as SparseArray<*>
            if (map.indexOfKey(LAYER_TYPE) < 0) continue
            map.remove(LAYER_TYPE)
            Xp.callMethod(row, "scheduleUpdateProperties")
            n++
        }
        return "$n rows"
    }

    // ---------------------------------------------------------------- reading

    private fun describe(ctx: Context, stack: ViewGroup, model: Any): String {
        val sb = StringBuilder()
        fun part(name: String, f: () -> Any?) {
            sb.append(name).append('=').append(runCatching(f).getOrElse { "?($it)" }).append(' ')
        }
        part("state") { value(model, "getCurrentsStackState") }
        part("num") { value(model, "isInNumState") }
        part("numAnim") { value(model, "isNumStateAnimating") }
        part("setting") { Settings.System.getInt(ctx.contentResolver, "default_keyguard_notif_state", -1) }
        part("default") { Xp.callMethod(Xp.getObjectField(Xp.getObjectField(model, "settingsRepository"), "defaultState"), "getValue") }
        part("scrollY") { Xp.callMethod(Xp.callMethod(Xp.getObjectField(model, "notifContainerRefactor"), "get"), "getOwnScrollY") }
        for (s in listOf("NUMBER", "STACK", "LIST")) part("to$s") { scrollFor(model, s) }
        part("info") { value(model, "getOnUpdateChildSampleStackInfo") }
        sb.append("|| count: ")
        find(stack.rootView) { it.javaClass.name.endsWith("NotificationNumStateView") }.forEach { v ->
            sb.append(box(v)).append(" text=").append(texts(v)).append("; ")
        }
        sb.append("|| pill: ")
        find(stack.rootView) { it is MiniPlayerView && it.isShown }.forEach { sb.append(box(it)).append("; ") }
        sb.append("|| rows: ")
        for (row in rows(stack)) {
            sb.append(keyOf(row)?.takeLast(28) ?: row.javaClass.simpleName).append(' ').append(box(row))
            fun f(name: String, g: () -> Any?) {
                runCatching(g).getOrNull()?.let { sb.append(' ').append(name).append('=').append(fmt(it)) }
            }
            f("h") { Xp.callMethod(row, "getActualHeight") }
            f("ty") { row.translationY }
            f("sty") { Xp.callMethod(row, "getSuperTranslationY") }
            f("ssy") { Xp.callMethod(row, "getSuperScaleY") }
            f("ext") { Xp.callMethod(row, "getExtAlpha") }
            f("nsa") { Xp.getObjectField(Xp.callMethod(row, "getInjector"), "numStateAlpha") }
            f("layers") {
                val map = Xp.getObjectField(layers(row), "folmeValueMap") as SparseArray<*>
                (0 until map.size()).joinToString(",") { k ->
                    map.keyAt(k).toString() + if (Xp.getObjectField(map.valueAt(k), "isAnimating") == true) "*" else ""
                }
            }
            sb.append("; ")
        }
        return sb.toString()
    }

    private fun fmt(v: Any): String = if (v is Float) "%.2f".format(v) else v.toString()

    private fun box(v: View): String {
        val xy = IntArray(2).also(v::getLocationOnScreen)
        return (if (v.isShown) "" else "hidden:") + "v${v.visibility} a=${fmt(v.alpha)} " +
            "${xy[0]},${xy[1]}+${v.width}x${v.height}"
    }

    private fun texts(v: View): String {
        val out = ArrayList<String>()
        fun walk(x: View) {
            if (x is TextView && x.text.isNotEmpty()) out.add(x.text.toString())
            if (x is ViewGroup) for (k in 0 until x.childCount) walk(x.getChildAt(k))
        }
        walk(v)
        return out.joinToString("/")
    }

    private fun find(root: View, match: (View) -> Boolean): List<View> {
        val out = ArrayList<View>()
        val queue = ArrayDeque<View>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            if (match(v)) out.add(v)
            if (v is ViewGroup) for (k in 0 until v.childCount) queue.add(v.getChildAt(k))
        }
        return out
    }
}
