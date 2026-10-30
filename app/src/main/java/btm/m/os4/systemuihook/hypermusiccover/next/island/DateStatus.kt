// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.island
import btm.m.os4.systemuihook.hypermusiccover.next.Main
import btm.m.os4.systemuihook.hypermusiccover.next.PageSpring
import btm.m.os4.systemuihook.hypermusiccover.next.Gen
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.ceil

/**
 * The lock screen's lasting bottom lines - 勿扰, charging, a trusted device - drawn after the
 * date instead, where the island pill cannot cover them (2026-10-07).
 *
 * Drawn inside the date's own view (com.miui.clock.classic.ClassicTextAreaView) rather than
 * beside it: under the glass clock the date's colour is the blend layers' weights
 * (date-colour-is-blend-weights), so only what the date itself draws is the same glass, and the
 * OEM's squeeze, the cover's collapse and the doze move it all as one. The view is measured wider
 * by what is added; where that moves the date (a centred date moves left by half of it), the
 * drawing is shifted back and let go on a spring, so the date slides aside and the status rises
 * in after it.
 *
 * At the bottom they are taken out where the OEM keeps them: the rotating indication is not
 * handed the moved types (KeyguardIndicationRotateTextViewController.updateIndication, as
 * HyperTweak's LockscreenBottomTextHooker does), and the 勿扰 half of the "勿扰 | N个通知" line
 * (NotificationNumStateView) is measured to nothing. Passing moments - a fingerprint miss, 上滑
 * 解锁, NFC - stay at the bottom, near where the hand is.
 *
 * Only on clocks whose date is that view: anywhere else nothing is moved.
 */
internal object DateStatus {
    private const val TAG = "MCDate: "

    // KeyguardIndicationRotateTextViewController's types that move up. 3 is reverse charging as
    // well as charging, which is why it moves only while plugged in.
    private const val OWNER_INFO = 0
    private const val BATTERY = 3
    private const val TRUST = 6
    private val MOVED = intArrayOf(OWNER_INFO, BATTERY, TRUST)

    private const val ICON_NONE = 0
    private const val ICON_MOON = 1
    private const val ICON_BOLT = 2

    /** The segments in the order they stand after the date. */
    private val ORDER = listOf("zen", "charge", "trust", "owner")

    private val main = Gen.handler()


    /** Whether the islands are on: the placement is fixed (MiniPlayerRuntime.statusAtDate). */
    @Volatile private var switchOn = false
    private var switchRead = false

    private var ctx: Context? = null
    private var zen = false
    private var plugged = false
    private var level = -1

    /** The moved types' latest indications as the controller was handed them, null for gone. */
    private val indications = HashMap<Int, Any>()
    /** Moved types the controller is not holding because they are drawn at the date. */
    private val withheld = HashSet<Int>()
    private var controllerRef: WeakReference<Any>? = null
    private var updateMethod: Method? = null
    private var passing = false

    private val hosts = WeakHashMap<View, Host>()
    private var lastAlive = false
    private var lastActive = false
    private var numStateRef: WeakReference<View>? = null

    private val shown = ArrayList<Seg>()
    private val scratch = TextPaint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The last few layout decisions, for `op datestatus`. A hand-back is one frame wide and over
     * in half a second, so a probe that only shows the current state cannot tell a compensated
     * slide from a jump after the fact.
     */
    private val trail = java.util.ArrayDeque<String>()

    private fun note(line: String) {
        trail.addLast(line)
        while (trail.size > 6) trail.removeFirst()
    }

    private fun brief(l: DateStatusLayout?): String {
        if (l == null) return "none"
        return "l" + l.left + " w" + l.width + "/x" + l.extra + " t" + l.top + " h" + l.height +
                " p" + l.parentWidth + (if (l.aod) "/aod" else "")
    }

    private class Host(val view: TextView) {
        /** The OEM's own width, and what was added to it, as last measured. */
        var base = 0
        var extra = 0
        /** extra as of the last layout: a change between the two is ours to slide. */
        var laidExtra = 0
        var layout: DateStatusLayout? = null
        /** The room there was for the segments, for drawing them as they were measured. */
        var room = Float.MAX_VALUE
        /**
         * The shift that keeps the date where it was drawn, on the view's animation matrix and
         * not the canvas: drawn shifted inside a view already laid out at its new place, the date
         * ran past the view's bounds and its parent cut it ("0月7日", filmed 2026-10-07).
         */
        val offset = Tween {
            view.animationMatrix = if (value == 0f) null else android.graphics.Matrix().apply { setTranslate(value, 0f) }
        }
    }

    private class Seg(val key: String) {
        var icon = ICON_NONE
        var text = ""
        var leaving = false
        val alpha = Tween { invalidateHosts() }
        /** Where it was against where the layout now puts it: a neighbour came or went. */
        val shift = Tween { invalidateHosts() }
    }

    /** One value on PageSpring's curve, picked up from wherever it is when retargeted. */
    private class Tween(private val frame: Tween.() -> Unit) {
        var value = 0f
            private set
        private var anim: ValueAnimator? = null

        fun snap(v: Float) {
            anim?.cancel()
            anim = null
            value = v
            frame()
        }

        /** Retirement: whatever it was moving towards does not arrive. */
        fun cancel() {
            anim?.cancel()
            anim = null
        }

        fun to(target: Float, response: Float, end: (() -> Unit)? = null) {
            anim?.cancel()
            anim = null
            val from = value
            if (from == target) {
                frame()
                end?.invoke()
                return
            }
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = PageSpring.durationMs(response, 0f)
            a.interpolator = PageSpring.interpolator(response, 0f)
            a.addUpdateListener {
                value = from + (target - from) * (it.animatedValue as Float)
                frame()
            }
            a.addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    value = target
                    if (anim === a) anim = null
                    frame()
                    end?.invoke()
                }
            })
            anim = a
            a.start()
        }
    }

    // ---------------------------------------------------------------- hooks

    fun install(classLoader: ClassLoader) {
        hookDate(classLoader)
        hookIndications(classLoader)
        hookNumState(classLoader)
    }

    private fun hookDate(cl: ClassLoader) {
        val cls = runCatching {
            Xp.findClass(listOf("com", "miui", "clock", "classic", "ClassicTextAreaView").joinToString("."), cl)
        }.getOrElse {
            Xp.log(TAG + "no date view, nothing moves: $it")
            return
        }
        runCatching {
            Xp.hookAll("sysui.datestatus.classic_measure", cls, "onMeasure") { chain ->
                val result = chain.proceed()
                runCatching { measured(chain.thisObject as TextView, (chain.args[0] as Number).toInt()) }
                    .onFailure { Xp.log(TAG + "measure: $it") }
                result
            }
            Xp.hookAll("sysui.datestatus.classic_draw", cls, "onDraw") { chain ->
                val v = chain.thisObject as TextView
                val host = hosts[v]
                val canvas = chain.args[0] as Canvas
                if (host == null || shown.isEmpty()) return@hookAll chain.proceed()
                try {
                    chain.proceed()
                } finally {
                    runCatching { draw(host, canvas) }.onFailure { Xp.log(TAG + "draw: $it") }
                }
            }
        }.onFailure { Xp.log(TAG + "date view unhookable: $it") }
    }

    private fun hookIndications(cl: ClassLoader) {
        val cls = runCatching {
            Xp.findClass(listOf("com", "android", "systemui", "keyguard",
                "KeyguardIndicationRotateTextViewController").joinToString("."), cl)
        }.getOrElse {
            Xp.log(TAG + "no indication controller, the bottom line stays: $it")
            return
        }
        runCatching {
            Xp.hookAll("sysui.datestatus.indication_update", cls, "updateIndication") { chain ->
                val type = (chain.args[0] as? Number)?.toInt()
                if (passing || type == null || type !in MOVED || chain.args.size != 3) {
                    return@hookAll chain.proceed()
                }
                controllerRef = WeakReference(chain.thisObject)
                updateMethod = chain.executable as Method
                val indication = chain.args[1]
                if (indication != null && !TextUtils.isEmpty(message(indication))) {
                    indications[type] = indication
                } else {
                    indications.remove(type)
                }
                val result = if (indication != null && withholds(type)) {
                    withheld.add(type)
                    // Shown now, it would stay up until the queue turned: let it go at once.
                    val args = chain.args.toTypedArray()
                    args[1] = null
                    args[2] = true
                    chain.proceed(args)
                } else {
                    withheld.remove(type)
                    chain.proceed()
                }
                later()
                result
            }
            // Withheld, the controller has nothing under the type and returns early; this is how
            // we hear that it went.
            Xp.hookAll("sysui.datestatus.indication_hide", cls, "hideIndication") { chain ->
                val type = (chain.args.getOrNull(0) as? Number)?.toInt()
                if (!passing && type != null && type in MOVED) {
                    controllerRef = WeakReference(chain.thisObject)
                    if (indications.remove(type) != null) later()
                    withheld.remove(type)
                }
                chain.proceed()
            }
        }.onFailure { Xp.log(TAG + "indications unhookable: $it") }
    }

    private fun hookNumState(cl: ClassLoader) {
        val cls = runCatching {
            Xp.findClass(listOf("com", "miui", "systemui", "notification", "view",
                "NotificationNumStateView").joinToString("."), cl)
        }.getOrElse {
            Xp.log(TAG + "no num state line, 勿扰 stays at the bottom: $it")
            return
        }
        runCatching {
            Xp.hookAll("sysui.datestatus.numstate_measure", cls, "onMeasure") { chain ->
                val result = chain.proceed()
                val v = chain.thisObject as View
                numStateRef = WeakReference(v)
                if (active()) runCatching { dropZen(v, (chain.args[1] as Number).toInt()) }
                    .onFailure { Xp.log(TAG + "num state: $it") }
                result
            }
        }.onFailure { Xp.log(TAG + "num state unhookable: $it") }
    }

    /**
     * The 勿扰 mark and the divider after it measured to nothing, and the line narrowed by what
     * they took: NotificationNumStateView lays its three children end to end by measured width,
     * so the count closes up and stays centred.
     */
    private fun dropZen(v: View, hSpec: Int) {
        val zenView = Xp.getObjectField(v, "zenView") as? View ?: return
        val divider = Xp.getObjectField(v, "dividingLine") as? View ?: return
        val gone = zenView.measuredWidth + divider.measuredWidth
        if (gone <= 0) return
        val zero = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.EXACTLY)
        zenView.measure(zero, hSpec)
        divider.measure(zero, hSpec)
        Xp.callMethod(v, "setMeasuredDimension", maxOf(0, v.measuredWidth - gone), v.measuredHeight)
    }

    private fun message(indication: Any): CharSequence? =
        runCatching { Xp.getObjectField(indication, "mMessage") as? CharSequence }.getOrNull()

    // ---------------------------------------------------------------- state

    /** Moved, and drawn at the date, now. */
    private fun active(): Boolean = enabled() && lastAlive

    private fun enabled(): Boolean {
        if (!switchRead) {
            val c = ctx ?: Main.appContext() ?: return false
            switchOn = MiniPlayerRuntime.statusAtDate(c)
            switchRead = true
        }
        return switchOn
    }

    private fun withholds(type: Int): Boolean =
        active() && (type != BATTERY || plugged)

    /** The switch moved (MiniPlayerRuntime.applyConfig). */
    @JvmStatic fun configChanged() {
        switchRead = false
        later()
    }

    /** A doze can stop animation frames before their final matrix cleanup. */
    @JvmStatic fun onAodChanged() {
        val reset = {
            for (host in hosts.values) {
                host.layout = null
                host.offset.snap(0f)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) reset() else Gen.post { reset() }
    }

    /**
     * Retirement: the indications the date was holding go back to the OEM's controller, the
     * segments stop moving, and every host gets its own matrix and width back. The hosts map is
     * cleared so the next generation's first layout builds fresh ones - the map is what keeps a
     * host from registering its layout listener a second time, and the sweep takes the listener.
     */
    @JvmStatic
    fun retire() {
        // Not alive any more: sync() then hands every withheld indication back.
        lastAlive = false
        lastActive = false
        runCatching { sync() }
        for (seg in shown) {
            seg.alpha.cancel()
            seg.shift.cancel()
        }
        shown.clear()
        for (h in hosts.values) {
            h.layout = null
            h.base = 0
            h.extra = 0
            h.laidExtra = 0
            h.offset.cancel()
            h.offset.snap(0f)
        }
        hosts.clear()
        indications.clear()
        withheld.clear()
        controllerRef = null
        updateMethod = null
        numStateRef = null
        passing = false
        ctx = null
        switchRead = false
    }

    private fun later() {
        Gen.post(::update)
    }

    private fun start(c: Context) {
        if (ctx != null) return
        val app = c.applicationContext ?: c
        ctx = app
        val cr = app.contentResolver
        val zenUri = Settings.Global.getUriFor("zen_mode")
        fun readZen() {
            zen = runCatching { Settings.Global.getInt(cr, "zen_mode", 0) != 0 }.getOrDefault(false)
        }
        readZen()
        runCatching {
            Gen.observer(cr, zenUri, false, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    readZen()
                    update()
                }
            })
        }.onFailure { Xp.log(TAG + "zen not watched: $it") }
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, i: Intent) { battery(i) }
            }
            Gen.receiver(app, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, null)
                ?.let(::battery)
        }.onFailure { Xp.log(TAG + "battery not watched: $it") }
        Xp.log(TAG + "watching zen=$zen plugged=$plugged level=$level")
        Gen.post(::update)
    }

    private fun battery(i: Intent) {
        val p = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).let { if (it < 0) -1 else it * 100 / scale }
        if (p == plugged && l == level) return
        plugged = p
        level = l
        update()
    }

    private fun hostAlive(): Boolean = hosts.keys.any { it.isAttachedToWindow && it.isShown }

    /** What should stand after the date now, in order. */
    private fun wanted(): List<Triple<String, Int, String>> {
        if (!active()) return emptyList()
        val out = ArrayList<Triple<String, Int, String>>()
        if (zen) out.add(Triple("zen", ICON_MOON, ""))
        if (plugged && level >= 0) out.add(Triple("charge", ICON_BOLT, "$level%"))
        indications[TRUST]?.let(::message)?.let { out.add(Triple("trust", ICON_NONE, it.toString())) }
        indications[OWNER_INFO]?.let(::message)?.let { out.add(Triple("owner", ICON_NONE, it.toString())) }
        return out
    }

    private fun update() {
        val alive = hostAlive()
        if (alive != lastAlive) {
            lastAlive = alive
            numStateRef?.get()?.requestLayout()
        }
        // The switch takes the line back to the bottom without the date view changing, and the
        // num state view stays measured as it was unless asked (MiniPlayerConfig.STATUS_AT_DATE).
        val activeNow = active()
        if (activeNow != lastActive) {
            lastActive = activeNow
            numStateRef?.get()?.requestLayout()
        }
        val want = wanted()
        val before = positions()
        var changed = false
        for ((key, icon, text) in want) {
            var seg = shown.firstOrNull { it.key == key }
            if (seg == null) {
                seg = Seg(key)
                val rank = ORDER.indexOf(key)
                val at = shown.indexOfFirst { ORDER.indexOf(it.key) > rank }.let { if (it < 0) shown.size else it }
                shown.add(at, seg)
                changed = true
            }
            if (seg.icon != icon || seg.text != text) {
                seg.icon = icon
                seg.text = text
                changed = true
            }
            if (seg.leaving || seg.alpha.value < 1f) {
                seg.leaving = false
                seg.alpha.to(1f, 0.35f)
            }
        }
        // A copy: a segment still at alpha 0 (added, then unwanted before its first frame) ends its
        // fade at once, and remove() takes it out of [shown] while this loop is still in it - the
        // ConcurrentModificationException that crashed SystemUI on every boot (#80).
        for (seg in shown.toList()) {
            if (seg.leaving || want.any { it.first == seg.key }) continue
            seg.leaving = true
            seg.alpha.to(0f, 0.25f) { remove(seg) }
        }
        if (changed) relayout(before)
        sync()
    }

    private fun remove(seg: Seg) {
        if (!seg.leaving) return
        val before = positions()
        shown.remove(seg)
        relayout(before)
    }

    /** Widths changed: the segments that stay slide from where they were, the date on layout. */
    private fun relayout(before: Map<String, Float>) {
        val after = positions()
        for (seg in shown) {
            val was = before[seg.key] ?: continue
            val now = after[seg.key] ?: continue
            if (was != now) {
                seg.shift.snap(seg.shift.value + was - now)
                // The width was held while it slid (measured); now it may close up.
                seg.shift.to(0f, 0.42f) { if (!settling()) for (v in hosts.keys) v.requestLayout() }
            }
        }
        for (v in hosts.keys) v.requestLayout()
        invalidateHosts()
    }

    /** Each segment's x after the date, as the visible date lays them out. */
    private fun positions(): Map<String, Float> {
        val host = hosts.values.firstOrNull { it.view.isShown } ?: return emptyMap()
        return place(host.view, host.room).associate { it.seg.key to it.x }
    }

    /** The bottom's indications follow what is drawn at the date. */
    private fun sync() {
        val controller = controllerRef?.get() ?: return
        val method = updateMethod ?: return
        for (type in MOVED) {
            val indication = indications[type]
            val want = indication != null && withholds(type)
            if (want == (type in withheld)) continue
            passing = true
            try {
                if (want) {
                    method.invoke(controller, type, null, true)
                    withheld.add(type)
                } else {
                    withheld.remove(type)
                    if (indication != null) method.invoke(controller, type, indication, false)
                }
            } catch (t: Throwable) {
                Xp.log(TAG + "sync $type: $t")
            } finally {
                passing = false
            }
        }
    }

    // ---------------------------------------------------------------- measuring and drawing

    private class Placed(val seg: Seg, val x: Float, val sep: Float, val text: String, val textX: Float)

    private fun place(v: TextView, room: Float): List<Placed> {
        if (shown.isEmpty()) return emptyList()
        val paint = scratch.apply { set(v.paint) }
        val em = paint.textSize
        val sepW = paint.measureText(SEP)
        val gap = em * 0.32f
        val iconGap = em * 0.12f
        fun iconW(icon: Int) = when (icon) {
            ICON_MOON -> em * 0.8f
            ICON_BOLT -> em * 0.5f
            else -> 0f
        }
        val texts = shown.map { it.text }.toMutableList()
        fun total(): Float {
            var x = gap
            shown.forEachIndexed { i, s ->
                if (i > 0) x += sepW
                x += iconW(s.icon)
                if (s.icon != ICON_NONE && texts[i].isNotEmpty()) x += iconGap
                x += paint.measureText(texts[i])
            }
            return x
        }
        // Too wide: the last texts give way first, down to an ellipsis.
        var over = total() - room
        for (i in shown.indices.reversed()) {
            if (over <= 0f) break
            val t = texts[i]
            if (t.isEmpty()) continue
            val w = paint.measureText(t)
            texts[i] = TextUtils.ellipsize(t, paint, maxOf(0f, w - over), TextUtils.TruncateAt.END).toString()
            over = total() - room
        }
        val out = ArrayList<Placed>(shown.size)
        var x = gap
        shown.forEachIndexed { i, s ->
            var sep = Float.NaN
            if (i > 0) {
                sep = x
                x += sepW
            }
            val start = x
            x += iconW(s.icon)
            if (s.icon != ICON_NONE && texts[i].isNotEmpty()) x += iconGap
            out.add(Placed(s, start, sep, texts[i], x))
            x += paint.measureText(texts[i])
        }
        return out
    }

    private fun width(placed: List<Placed>, v: TextView): Float {
        val last = placed.lastOrNull() ?: return 0f
        return last.textX + scratch.apply { set(v.paint) }.measureText(last.text)
    }

    private fun measured(v: TextView, wSpec: Int) {
        val host = hosts.getOrPut(v) {
            start(v.context)
            Host(v).also { h ->
                val layoutListener = View.OnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                    val previous = h.layout
                    val current = DateStatusLayout(l, t, r - l, b - t,
                        (v.parent as? View)?.width ?: 0, h.extra, MiniPlayerScene.aodActive)
                    h.layout = current
                    h.laidExtra = h.extra
                    val offset = DateStatusLayoutPolicy.offset(previous, current, h.offset.value)
                    note("layout off=" + h.offset.value + " -> " + (offset ?: "null")
                            + " prev[" + brief(previous) + "] cur[" + brief(current) + "]")
                    if (offset == null) h.offset.snap(0f)
                    else if (previous?.extra != current.extra) {
                        h.offset.snap(offset)
                        h.offset.to(0f, 0.42f)
                    }
                }
                Gen.layoutChange(v, layoutListener)
                Gen.attachState(v, object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        h.layout = null
                        h.offset.snap(0f)
                        later()
                    }

                    override fun onViewDetachedFromWindow(view: View) {
                        h.layout = null
                        h.offset.snap(0f)
                        later()
                        // The keyguard is rebuilt and this view goes with it. Left registered,
                        // these listeners and the host would keep the whole old tree reachable
                        // and the ledger one entry longer per rebuild; a view that comes back
                        // is measured again and gets a new host in the same cleared state.
                        Gen.unlayoutChange(view, layoutListener)
                        Gen.unattachState(view, this)
                        hosts.remove(view)
                    }
                })
            }
        }
        if (hostAlive() != lastAlive) Gen.post(::update)
        host.base = v.measuredWidth
        val mode = View.MeasureSpec.getMode(wSpec)
        if (shown.isEmpty()) {
            // The last segment has gone and the width we added is leaving the layout: the pass
            // that follows is the one the date is handed back on, and it is ours to animate. It
            // needs the record of the width we were laid out with and the offset that cancels it
            // (DateStatusLayoutPolicy takes `previous` and the live offset), so neither is cleared
            // here - only the room to draw in, which is empty anyway. Clearing them made every
            // unplug step the date straight to its narrower place (measured 2026-10-10: extra
            // 201->0 on one frame, off stayed 0.0 the whole way through).
            host.extra = 0
            host.room = 0f
            return
        }
        if (mode == View.MeasureSpec.EXACTLY || v.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            // The OEM is sizing the view itself here and our width is not in this layout, so a
            // remembered one would be compensation for something that never happened.
            host.extra = 0
            host.room = 0f
            host.layout = null
            host.offset.snap(0f)
            return
        }
        val limit = if (mode == View.MeasureSpec.AT_MOST) View.MeasureSpec.getSize(wSpec)
                    else v.resources.displayMetrics.widthPixels
        host.room = (limit - host.base - 48f * v.resources.displayMetrics.density).coerceAtLeast(0f)
        // Narrower, but a segment still sliding in from the right: the width waits for it, or
        // the view's bounds cut it on its way.
        val natural = ceil(width(place(v, host.room), v)).toInt()
        host.extra = if (natural < host.laidExtra && settling()) host.laidExtra else natural
        if (host.extra > 0) {
            Xp.callMethod(v, "setMeasuredDimension", host.base + host.extra, v.measuredHeight)
        }
    }

    private fun draw(host: Host, canvas: Canvas) {
        val v = host.view
        val placed = place(v, host.room)
        if (placed.isEmpty()) return
        val paint = scratch.apply { set(v.paint) }
        val color = v.currentTextColor
        paint.color = color
        val h = v.measuredHeight.toFloat()
        val baseline = h / 2f - (paint.ascent() + paint.descent()) / 2f
        val em = paint.textSize
        val rise = 8f * v.resources.displayMetrics.density
        var prevAlpha = 1f
        for (p in placed) {
            val a = p.seg.alpha.value.coerceIn(0f, 1f)
            val dx = host.base + p.seg.shift.value + rise * (1f - a)
            if (!p.sep.isNaN()) {
                // The separator belongs to both neighbours: it goes with whichever leaves.
                paint.alpha = (255 * a * prevAlpha).toInt()
                canvas.drawText(SEP, dx + p.sep, baseline, paint)
            }
            prevAlpha = a
            if (a <= 0.003f) continue
            paint.alpha = (255 * a).toInt()
            when (p.seg.icon) {
                ICON_MOON -> icon(canvas, moon(em * 0.8f), dx + p.x, (h - em * 0.8f) / 2f, paint)
                ICON_BOLT -> icon(canvas, bolt(em * 0.5f, em * 0.8f), dx + p.x, (h - em * 0.8f) / 2f, paint)
            }
            if (p.text.isNotEmpty()) canvas.drawText(p.text, dx + p.textX, baseline, paint)
        }
    }

    private fun icon(canvas: Canvas, path: Path, x: Float, y: Float, paint: Paint) {
        canvas.save()
        canvas.translate(x, y)
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private var moonPath: Path? = null
    private var moonSize = 0f
    private var boltPath: Path? = null
    private var boltSize = 0f

    /** A crescent in an s by s box. */
    private fun moon(s: Float): Path {
        moonPath?.takeIf { moonSize == s }?.let { return it }
        val outer = Path().apply { addCircle(s * 0.5f, s * 0.5f, s * 0.42f, Path.Direction.CW) }
        val bite = Path().apply { addCircle(s * 0.74f, s * 0.3f, s * 0.36f, Path.Direction.CW) }
        outer.op(bite, Path.Op.DIFFERENCE)
        moonPath = outer
        moonSize = s
        return outer
    }

    /** A lightning bolt in a w by h box. */
    private fun bolt(w: Float, h: Float): Path {
        boltPath?.takeIf { boltSize == h }?.let { return it }
        val pts = floatArrayOf(0.66f, 0.02f, 0.04f, 0.58f, 0.46f, 0.58f, 0.34f, 0.98f,
            0.96f, 0.40f, 0.54f, 0.40f)
        val p = Path()
        for (i in pts.indices step 2) {
            val x = pts[i] * w
            val y = pts[i + 1] * h
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        boltPath = p
        boltSize = h
        return p
    }

    private fun settling(): Boolean = shown.any { it.shift.value != 0f }

    private fun invalidateHosts() {
        for (v in hosts.keys) v.invalidate()
    }

    private const val SEP = " · "

    /** For `op datestatus`. */
    fun describe(): String = "datestatus switch=${enabled()} alive=$lastAlive zen=$zen plugged=$plugged " +
        "level=$level indications=${indications.mapValues { message(it.value) }} withheld=$withheld " +
        "shown=${shown.joinToString { "${it.key}:${it.text}@${"%.2f".format(it.alpha.value)}" + if (it.leaving) "-" else "" }} " +
        "hosts=${hosts.values.joinToString { "${it.view.isShown}/${it.base}+${it.extra}/off=${it.offset.value}" }} " +
        "controller=${controllerRef?.get() != null} numState=${numStateRef?.get() != null} " +
        "trail=" + trail.joinToString(" | ")
}
