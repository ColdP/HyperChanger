// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import org.json.JSONArray
import org.json.JSONObject

/**
 * ColorOS's per-brand page rules, run on the page the way ColorOS runs them.
 *
 * ColorOS reads a mini program's order page by CSS: its cloud hands each brand's appId a
 * `pickupCodeH5Query` - for each of the brand's order pages, selectors for the code, the order's
 * state and the wait - and its observer runs them over the page's HTML with Jsoup (Gleaner's
 * `ld.a` HtmlParser, `sd.d` PickupCodeHelper; 取餐码-OPPO逆向/05 §3). The rules this module ships
 * are those answers, fetched once (取餐码-OPPO逆向/云端规则-pickupCodeH5Query, `export.py`) and
 * kept verbatim in `assets/coloros/pickup_h5_rules.json`.
 *
 * The HTML is not ColorOS's to begin with: it runs JS inside 微信 to get it, which is the line this
 * module does not cross. But the assist structure carries the same DOM: XWeb hands every node's tag
 * and attributes over in its `HtmlInfo` - `<wx-text class="od-pb-c_item_c_t … od-pb-c_item_c_t_a">`
 * with its text in a child (measured 2026-10-13 on 肯德基's order page, 1124 of 1218 nodes). So
 * [El] is that DOM rebuilt, and the selectors run on it as written.
 *
 * Why it matters: a page's words are not its state. 肯德基's step bar shows 准备中, 请取餐 and 已完成
 * all at once, the step it is on told only by its `_a` class; 「制作完成」 is ready at 古茗 and
 * 「制作完成后」 is still being made at 奈雪; 茶百道 says it is ready with 「感谢对茶百道的支持」. Read
 * by the brand's own selector, none of that can be confused.
 *
 * Only what the rules use is implemented: selectors are a tag and classes (`wx-view.a.b`), or a
 * comma list of those - no descendants, no pseudo-classes; queries are all `query_type` 2 but for
 * two list containers found by id.
 */
internal object PickupCss {

    // ------------------------------------------------------------------ the page

    /**
     * A DOM node: an element ([tag], its `class` and `id`), or a text node ([tag] empty, [own] its
     * text). Built by [PickupAssist.dom] off the assist structure, or by a test off a fixture.
     */
    class El(val tag: String, val classes: Set<String> = emptySet(), val id: String = "", val own: String? = null) {
        var parent: El? = null
            private set
        val children = ArrayList<El>()

        val isText: Boolean get() = tag.isEmpty()

        fun add(child: El): El {
            child.parent = this
            children += child
            return child
        }

        /** Jsoup's `text()`: every text below, in order. */
        fun text(): String = StringBuilder().also { collect(it) }.toString().trim()

        private fun collect(out: StringBuilder) {
            own?.let { out.append(it.trim()) }
            for (c in children) c.collect(out)
        }

        /** Jsoup's `ownText()`: this element's own text nodes only. */
        fun ownText(): String = children.filter { it.isText }.joinToString("") { it.own.orEmpty().trim() }.trim()

        /** The element after this one under the same parent, text nodes skipped. */
        fun next(): El? = sibling(+1)

        fun previous(): El? = sibling(-1)

        private fun sibling(step: Int): El? {
            val list = parent?.children ?: return null
            var i = list.indexOf(this) + step
            while (i in list.indices) {
                if (!list[i].isText) return list[i]
                i += step
            }
            return null
        }

        /** Jsoup's `select()`: this element and everything below it that matches, in document order. */
        fun select(selector: String): List<El> {
            val parts = compounds(selector)
            val out = ArrayList<El>()
            fun walk(e: El) {
                if (!e.isText && parts.any { it.matches(e) }) out += e
                for (c in e.children) walk(c)
            }
            walk(this)
            return out
        }

        fun byId(id: String): El? {
            if (this.id == id) return this
            for (c in children) c.byId(id)?.let { return it }
            return null
        }

        /** Whether anything below is an element: an empty document is a WebView with no page in it. */
        fun hasElements(): Boolean = children.any { !it.isText }
    }

    private class Compound(val tag: String, val classes: List<String>) {
        fun matches(e: El): Boolean =
            (tag.isEmpty() || tag == "*" || tag.equals(e.tag, ignoreCase = true)) && e.classes.containsAll(classes)
    }

    private val parsed = java.util.concurrent.ConcurrentHashMap<String, List<Compound>>()

    private fun compounds(selector: String): List<Compound> = parsed.getOrPut(selector) {
        selector.split(',').mapNotNull { s ->
            val bits = s.trim().split('.')
            if (bits.all { it.isEmpty() }) null else Compound(bits[0], bits.drop(1).filter { it.isNotEmpty() })
        }
    }

    // ------------------------------------------------------------------ the rules

    /** One `PickupCodeCssQuery`, its JSON keys as they come. */
    class Query(
        val type: Int,
        val result: Int,
        val key: String,
        val label: String,
        val tag: String,
        val attr: String,
        val method: String?,
        val param: String?,
        val linkTo: String?,
    )

    /** One `PickupCodeOderQuery`: an order page of the brand's - its list, or one order's detail. */
    class Page(
        val description: String,
        val url: String,
        val list: Query?,
        val items: List<Query>,
        val code: List<Query>,
        val status: List<Query>,
        val wait: List<Query>,
        /**
         * Not ColorOS's: `orderTimeQuery`, which only this module's own rules ([LOCAL]) carry. A
         * brand with no state for collected (蜜雪冰城) needs its order's age to tell an order looked
         * up later from one just made.
         */
        val orderTime: List<Query> = emptyList(),
    )

    class Rule(val appId: String, val name: String, val updated: String, val pages: List<Page>)

    const val ASSET = "coloros/pickup_h5_rules.json"

    /**
     * This module's own rules, in the same format, for brands ColorOS's cloud has none for (its
     * answer for 蜜雪冰城 is -1401, 「没有匹配的规则」). ColorOS's rule wins where both have one.
     */
    const val LOCAL = "coloros/pickup_h5_rules_local.json"

    @Volatile private var loaded: Map<String, Rule>? = null

    /** The shipped rules by appId, read once; empty when the asset cannot be read. */
    fun rules(): Map<String, Rule> {
        loaded?.let { return it }
        fun load(name: String) = runCatching {
            PickupOem.asset(name).use { parse(it.readBytes().toString(Charsets.UTF_8)) }
        }.onFailure { Xp.log(PickupCodeIsland.TAG + "page rules not read ($name): $it") }.getOrDefault(emptyMap())
        val read = combine(load(ASSET), load(LOCAL))
        loaded = read
        return read
    }

    /** ColorOS's rules, and this module's for the brands they leave out. */
    fun combine(oem: Map<String, Rule>, local: Map<String, Rule>): Map<String, Rule> =
        oem + local.filterKeys { it !in oem }

    fun ruleFor(appId: String?): Rule? = if (appId.isNullOrEmpty()) null else rules()[appId]

    fun parse(json: String): Map<String, Rule> {
        val out = LinkedHashMap<String, Rule>()
        val array = JSONObject(json).optJSONArray("rules") ?: return out
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val appId = o.optString("appId")
            if (appId.isEmpty()) continue
            val pages = o.optJSONArray("pages") ?: continue
            out[appId] = Rule(appId, o.optString("appName"), o.optString("updateTime"),
                (0 until pages.length()).mapNotNull { pages.optJSONObject(it)?.let(::page) })
        }
        return out
    }

    private fun page(o: JSONObject) = Page(
        description = o.optString("description"),
        url = o.optString("url"),
        list = o.optJSONObject("listNodeQuery")?.let(::query),
        items = queries(o.optJSONArray("itemNodeQuery")),
        code = queries(o.optJSONArray("codeQuery")),
        status = queries(o.optJSONArray("statusQuery")),
        wait = queries(o.optJSONArray("waitTimeQuery")),
        orderTime = queries(o.optJSONArray("orderTimeQuery")),
    )

    private fun queries(a: JSONArray?): List<Query> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::query) }

    /** A JSON string or null - org.json's optString turns a missing key into "" and a null into "null". */
    private fun JSONObject.str(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key)

    private fun query(o: JSONObject) = Query(
        type = o.optInt("query_type", 0),
        result = o.optInt("result_type", 0),
        key = o.str("key").orEmpty(),
        label = o.str("label").orEmpty(),
        tag = o.str("tag").orEmpty(),
        attr = o.str("attr").orEmpty(),
        method = o.str("method"),
        param = o.str("param"),
        linkTo = o.str("link_to"),
    )

    // ------------------------------------------------------------------ reading a page

    /**
     * What a brand's rules read off a page: the [code] (and the [label] its query names it by),
     * the order's state in this module's words ([PickupParse.STATUSES]), and the [wait]. [page] is
     * the rule page that answered, for the probe. Any of the three may be missing.
     */
    data class Read(
        val code: String?,
        val label: String?,
        val status: String?,
        val wait: String?,
        val page: String,
        /** When the order was placed, as [PickupPolicy] reads it (`2026-08-22T19:33`). */
        val orderTime: String? = null,
    )

    /**
     * The one state this module reads differently from ColorOS. 麦当劳's page says
     * 「订单已准备完毕」 once the meal is waiting at the counter; ColorOS calls that over and takes
     * the card down, and the user chose (2026-10-13) to keep the card and turn its button to
     * 「我已取餐」 instead - the same as every other brand's ready state.
     */
    private val OVERRIDES = mapOf("订单已准备完毕" to "待取餐")

    /**
     * [rule] run over [doc], ColorOS's way: each of the brand's pages is tried (ColorOS knows which
     * page it is on by the page's route, which the assist structure does not carry, so the page
     * whose selectors find the most is the one this is), and on it the first base node that yields
     * anything - the whole page, or each order of a list ([Page.list], [Page.items]). null when no
     * selector of the brand's finds anything: the page is not one its rules know.
     */
    fun read(rule: Rule, doc: El): Read? {
        var best: Read? = null
        var bestScore = 0
        for (p in rule.pages) {
            val r = readPage(p, doc) ?: continue
            val score = (if (r.code != null) 4 else 0) + (if (r.status != null) 2 else 0) + (if (r.wait != null) 1 else 0)
            if (score > bestScore) {
                best = r
                bestScore = score
            }
        }
        return best
    }

    private fun readPage(p: Page, doc: El): Read? {
        for (base in bases(p, doc)) {
            val code = field(p.code, base)
            val status = field(p.status, base)
            val wait = field(p.wait, base)
            if (code == null && status == null && wait == null) continue
            return Read(
                code = code?.second?.let(::codeOf),
                label = code?.first?.tag?.takeIf { it.isNotEmpty() },
                status = status?.let { (q, s) -> stateOf(OVERRIDES[q.param] ?: s) },
                wait = wait?.second?.let(::waitOf),
                page = p.description,
                orderTime = field(p.orderTime, base)?.second?.let(::timeOf),
            )
        }
        return null
    }

    /** HtmlParser's base nodes: the list container's children, the item nodes, or else the page. */
    private fun bases(p: Page, doc: El): List<El> {
        val out = ArrayList<El>()
        p.items.forEach { q -> out += find(q, doc) }
        p.list?.let { q -> find(q, doc).firstOrNull()?.let { list -> out += list.children.filter { !it.isText } } }
        if (out.isEmpty()) out += doc
        return out
    }

    /** `query_type`: 2 a selector, 1 an id, 0 a class name. */
    private fun find(q: Query, base: El): List<El> = when {
        q.key.isEmpty() -> emptyList()
        q.type == 1 -> listOfNotNull(base.byId(q.key))
        q.type == 0 -> base.select(".${q.key}")
        else -> base.select(q.key)
    }

    /**
     * PickupCodeHelper's `f`: the first query whose value passes its `method`, after its `link_to`.
     * A query that finds nothing is passed over rather than ending the search - ColorOS's would stop
     * at a method-less one.
     */
    private fun field(qs: List<Query>, base: El): Pair<Query, String>? {
        for (q in qs) {
            val raw = value(q, base)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            if (!passes(q, raw)) continue
            val out = link(q, raw)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            return q to out
        }
        return null
    }

    /** HtmlParser's `f`, by `result_type`. */
    private fun value(q: Query, base: El): String? {
        val found = find(q, base)
        if (found.isEmpty()) return null
        return when (q.result) {
            0 -> found.joinToString(",") { it.text() }
            1 -> textOf(found.first(), q, own = false)
            2 -> textOf(found.last(), q, own = false)
            3 -> beside(found, q, next = true)
            4 -> beside(found, q, next = false)
            5 -> textOf(found.last(), q, own = true)
            else -> found.first().text()
        }
    }

    /** HtmlParser's `g`: an attribute, the text after a [Query.tag]-bearing label, or the text. */
    private fun textOf(e: El, q: Query, own: Boolean): String? {
        if (q.attr.isNotEmpty()) return if (q.attr == "class") e.classes.joinToString(" ") else if (q.attr == "id") e.id else null
        if (q.label.isEmpty() || q.tag.isEmpty()) return if (own) e.ownText() else e.text()
        val labelled = e.select(q.label).firstOrNull { it.text().contains(q.tag) } ?: return null
        val after = labelled.next() ?: return null
        return if (own) after.ownText() else after.text()
    }

    /** HtmlParser's `d`: the first found element bearing [Query.tag] (or its [Query.label] that does), and the element beside it. */
    private fun beside(found: List<El>, q: Query, next: Boolean): String? {
        for (e in found) {
            val holder = if (q.label.isEmpty()) {
                if (q.tag.isNotEmpty() && !e.text().contains(q.tag)) null else e
            } else {
                e.select(q.label).firstOrNull { q.tag.isEmpty() || it.text().contains(q.tag) }
            } ?: continue
            val other = (if (next) holder.next() else holder.previous()) ?: continue
            return other.text()
        }
        return null
    }

    /** PickupCodeHelper's `d`. */
    private fun passes(q: Query, s: String): Boolean = when (q.method) {
        null, "default" -> true
        "equals" -> s == q.param
        "not_equals" -> s != q.param
        "contains" -> q.param != null && s.contains(q.param)
        "text_start_with" -> q.param != null && s.startsWith(q.param)
        "notnull" -> s.isNotEmpty()
        else -> true
    }

    /** PickupCodeHelper's `j`: `link_to` is a regex to cut the value with, a constant to replace it with, or nothing. */
    private fun link(q: Query, s: String): String? {
        val to = q.linkTo ?: return s
        if (!to.startsWith("pattern=")) return to
        return runCatching { Regex(to.removePrefix("pattern=")).find(s)?.value }.getOrNull()
    }

    /** A code as the rules cut it: no spaces, no `#`, and something that can be a code at all. */
    private fun codeOf(s: String): String? {
        val c = s.filterNot { it.isWhitespace() }.removePrefix("#")
        return c.takeIf { it.length in 1..12 && it.any(Char::isDigit) }
    }

    /**
     * ColorOS's state names (已下单 / 制作中 / 待取餐 / 已完成 / 已取消) are all in this module's
     * vocabulary already; a rule that hands back the page's own words instead (`notnull` with no
     * `link_to`) is read through the vocabulary, and nothing when it says none of it.
     */
    private fun stateOf(s: String): String? =
        if (s in PickupParse.STATUSES) s else PickupParse.STATUSES.firstOrNull { s.contains(it) }

    /** 「12分钟」 → 「约12分钟」; a sentence → whatever [PickupParse.waitTime] finds in it, or itself if short. */
    private fun waitOf(s: String): String? {
        Regex("^(\\d{1,3}(?:-\\d{1,3})?)\\s*分钟$").find(s)?.let { return "约" + it.groupValues[1] + "分钟" }
        return PickupParse.waitTime(listOf(s)) ?: s.takeIf { it.length <= 10 }
    }

    /** 「2026-08-22 19:33:52」 → 「2026-08-22T19:33」, the shape the recognizer hands [PickupPolicy]. */
    private fun timeOf(s: String): String? {
        val m = Regex("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})[ T]+(\\d{1,2}):(\\d{2})").find(s) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        return "%s-%02d-%02dT%02d:%s".format(y, mo.toInt(), d.toInt(), h.toInt(), mi)
    }

    // ------------------------------------------------------------------ the verdict

    /**
     * This file's reading [own] (and ColorOS's recognizer's, folded into it) with the brand's rules'
     * [css] over it. The rules' code and state win where they read one. Where they read a code but
     * no state, the page's words are still used for one - but never to say the order is ready or
     * over: on a brand whose state the rules know how to find, a ready word found elsewhere on the
     * page is exactly the confusion the rules are there to avoid, and it would turn the card's button
     * to 「我已取餐」 on a guess.
     *
     * A page none of the rules' selectors find anything on ([css] null) is left to [own] as before:
     * the brand redesigned its page, or this is a page its rules do not cover.
     */
    fun merge(own: PickupParse.Result?, css: Read?): PickupParse.Result? {
        if (css == null) return own
        val code = css.code
        val base = when {
            code == null -> own ?: return null
            own == null -> PickupParse.Result(code, css.label ?: "取餐码", null, null, strong = true)
            own.code == code -> own.copy(strong = true)
            else -> own.copy(code = code, label = css.label ?: "取餐码", strong = true)
        }
        return base.copy(
            status = css.status ?: own?.status?.takeUnless { PickupParse.isReady(it) },
            waitTime = css.wait ?: base.waitTime,
            orderTime = base.orderTime ?: css.orderTime,
            ruled = css.status != null,
        )
    }

    /** The front page out of the documents [PickupAssist.dom] found: the last one with a page in it. */
    fun front(docs: List<El>): El? = docs.lastOrNull { it.hasElements() }
}
