// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.app.assist.AssistStructure
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel

/**
 * A mini program's page, as SystemUI reads it: the assist structure of a task, asked for by task
 * (IActivityTaskManager.requestAssistDataForTask - SystemUI holds GET_TOP_ACTIVITY_INFO), its text
 * nodes, and the mini program the page's own launch intent names.
 *
 * Nothing here keeps any state; [PickupCodeIsland] decides what is read and when.
 */
internal object PickupAssist {

    private const val TAG = PickupCodeIsland.TAG
    private const val SYSUI = PickupCodeIsland.SYSUI

    /**
     * The mini program a page belongs to, out of the page's own launch intent: the appId, and the
     * name it was searched for when it was reached from a search. Null when the page carries
     * neither - a page in one of the same containers that is not a mini program, or one whose
     * intent could not be read.
     */
    class Program(val appId: String, val name: String, val scheme: String = "")

    /**
     * 支付宝 puts the appId in a nested `startParams` bundle, beside the query and the
     * `alipays://platformapi/startapp?appId=…` scheme it was opened with (measured 2026-10-09:
     * `appId = 2021002163601771`, `globalSearchQuery = 蜜雪冰城`).
     *
     * Read as a field and never as a regex over the string extras, which is the fallback ColorOS
     * has (`"appId"\s*:\s*"(\d{8,20})"`): the renderer's own `__config__ta_render_anr_cfg` in this
     * very bundle carries `"appId":"2021001178689171,…"`, a list of *other* mini programs, and a
     * regex finds that first. (ColorOS's own readers take `mExtras.appInfo.appId`; 支付宝's pages
     * have not shown that shape, so it is looked for second rather than relied on.)
     */
    fun programOf(extras: Bundle?): Program? {
        if (extras == null) return null
        val start = runCatching { extras.getBundle("startParams") }.getOrNull()
        val info = runCatching { extras.getBundle("mExtras")?.getBundle("appInfo") }.getOrNull()
        val appId = text(start, "appId").ifEmpty { text(info, "appId") }
        if (appId.matches(NUMERIC_ID)) {
            // `ap_framework_scheme` is the `alipays://platformapi/startapp?appId=…` the system was
            // handed when this page was opened, and it is what a tap on the island uses to come
            // back to it (see [PickupIsland]'s openNow).
            return Program(appId, text(start, "globalSearchQuery"),
                text(start, "ap_framework_scheme"))
        }
        // 微信 has no such bundle, and ColorOS's own reader for it (`Gleaner`'s `yc/k`) does not
        // look for one: it walks the extras for a string shaped like 微信's appId. Reading every
        // string is safe here in a way it was not for 支付宝's numeric ids, where the renderer's
        // own config carries a list of *other* mini programs' ids in the same bundle - a `wx`
        // followed by sixteen hex digits is a shape nothing else in an intent has.
        wechatId(extras)?.let { return Program(it, "") }
        return null
    }

    /** 微信's appId in a page's extras, one level of nested bundles deep. */
    private fun wechatId(extras: Bundle, depth: Int = 0): String? {
        val keys = runCatching { extras.keySet() }.getOrNull() ?: return null
        for (key in keys) {
            val id = text(extras, key).lowercase()
            if (id.matches(WX_ID)) return id
        }
        if (depth >= 1) return null
        for (key in keys) {
            val nested = runCatching { extras.getBundle(key) }.getOrNull() ?: continue
            wechatId(nested, depth + 1)?.let { return it }
        }
        return null
    }

    private val NUMERIC_ID = Regex("\\d{8,20}")
    private val WX_ID = Regex("wx[a-f0-9]{16}")

    private fun text(from: Bundle?, key: String): String =
        runCatching { from?.getString(key) }.getOrNull().orEmpty()

    /**
     * IActivityTaskManager.requestAssistDataForTask(receiver, taskId, callingPackage,
     * attributionTag, fetchStructure) - Android 17's five; the appop it notes is checked against
     * SystemUI's own package. The receiver is a bare Binder: IAssistDataReceiver's transaction 1
     * is onHandleAssistData(Bundle), 2 the screenshot, both oneway.
     */
    fun request(task: Int, done: (AssistStructure?, Bundle?) -> Unit): Boolean {
        val receiver = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == 1) {
                    val b = runCatching {
                        data.enforceInterface("android.app.IAssistDataReceiver")
                        if (data.readInt() != 0) Bundle.CREATOR.createFromParcel(data) else null
                    }.getOrNull()
                    b?.classLoader = AssistStructure::class.java.classLoader
                    @Suppress("DEPRECATION")
                    val st = runCatching { b?.getParcelable<AssistStructure>("structure") }.getOrNull()
                    // The page's own launch intent rides along in `content`, and it is read apart
                    // from the structure and after it. 支付宝 puts a Parcelable of its own class in
                    // this same bundle (…fulllinktracker…SyncData, seen 2026-10-09) which does not
                    // unmarshal in SystemUI at all; one try around both would throw a page's text
                    // away for the sake of its intent, and the text is what the island is for.
                    val extras = runCatching {
                        @Suppress("DEPRECATION")
                        b?.getParcelable<android.app.assist.AssistContent>("content")?.intent?.extras
                    }.getOrNull()
                    done(st, extras)
                    return true
                }
                if (code == 2) return true
                return super.onTransact(code, data, reply, flags)
            }
        }
        receiver.attachInterface(null, "android.app.IAssistDataReceiver")
        return runCatching {
            val atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
            val recv = Class.forName("android.app.IAssistDataReceiver")
            val proxy = Class.forName("android.app.IAssistDataReceiver\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, receiver)
            val m = atm.javaClass.methods.firstOrNull { it.name == "requestAssistDataForTask" }
                ?: error("no requestAssistDataForTask")
            val ok = when (m.parameterTypes.size) {
                5 -> m.invoke(atm, proxy, task, SYSUI, null, true)
                4 -> m.invoke(atm, proxy, task, SYSUI, null)
                else -> error("requestAssistDataForTask${m.parameterTypes.toList()}")
            }
            ok as? Boolean ?: true
        }.onFailure {
            Xp.log(TAG + "assist request failed: $it")
        }.getOrDefault(false)
    }

    /** The structure's texts in tree order, each with the WebView it is in. */
    fun nodes(st: AssistStructure): List<PickupParse.Node> {
        val out = ArrayList<PickupParse.Node>()
        var pages = -1
        fun walk(n: AssistStructure.ViewNode, page: Int) {
            var p = page
            if (n.className?.endsWith("WebView") == true) p = ++pages
            n.text?.let { if (it.isNotBlank()) out.add(PickupParse.Node(it.toString(), p)) }
            for (i in 0 until n.childCount) walk(n.getChildAt(i), p)
        }
        for (i in 0 until st.windowNodeCount) walk(st.getWindowNodeAt(i).rootViewNode, -1)
        return out
    }

    /**
     * The structure's web pages as DOM trees, one per `#document`, in tree order - for the brands'
     * CSS rules ([PickupCss]). XWeb puts each DOM node's tag and attributes in its `HtmlInfo`, and
     * a text node is a child whose tag is empty; views that are not part of a page (no `HtmlInfo`)
     * are walked through. Only `class` and `id` are kept: the rules ask for nothing else, and the
     * other attributes include whole images as data URLs.
     */
    fun dom(st: AssistStructure): List<PickupCss.El> {
        val docs = ArrayList<PickupCss.El>()
        fun text(into: PickupCss.El, t: CharSequence?) {
            if (t != null && t.isNotBlank()) into.add(PickupCss.El("", own = t.toString()))
        }
        fun walk(n: AssistStructure.ViewNode, into: PickupCss.El?) {
            var here = into
            val html = n.htmlInfo
            val tag = html?.tag.orEmpty()
            when {
                tag == "#document" -> here = PickupCss.El(tag).also { docs += it }
                into == null -> {}
                html == null || tag.isEmpty() -> text(into, n.text)
                else -> {
                    var cls = ""
                    var id = ""
                    html.attributes?.forEach { a ->
                        when (a.first) {
                            "class" -> cls = a.second.orEmpty()
                            "id" -> id = a.second.orEmpty()
                        }
                    }
                    val el = PickupCss.El(tag.lowercase(), cls.split(' ', '\t', '\n').filter { it.isNotEmpty() }.toSet(), id)
                    into.add(el)
                    text(el, n.text)
                    here = el
                }
            }
            for (i in 0 until n.childCount) walk(n.getChildAt(i), here)
        }
        for (i in 0 until st.windowNodeCount) walk(st.getWindowNodeAt(i).rootViewNode, null)
        return docs
    }
}
