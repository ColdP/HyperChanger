// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * The list of mini programs ColorOS keeps in its cloud, and what this module makes of it.
 *
 * The rules XML packed here is the copy that ships with the APK - 47 brands - while the same
 * feature's brand list is served per release from `iwisdom.apps.coloros.com`: 93 微信 applets on
 * 2026-07-17, each with its appId, the `gh_…` original id the rules are keyed by, and a `disabled`
 * flag, which is the only way this module has of honouring a brand ColorOS has switched off.
 *
 * 支付宝 has no such list. The cloud call answers with two AIFluid scenes and this is the only
 * applet one; the other, `AIFluidCard`, names `com.tencent.mm.plugin.appbrand.ui` and nothing else.
 * So 支付宝's brands remain the three the rules XML carries, and that is OPPO's own state rather
 * than a gap here.
 *
 * Fetched from SystemUI's side (`op cloud --es do fetch`), not the app's: the two are different
 * uids, so nothing the app downloads is readable from in here. SystemUI holds INTERNET for its own
 * reasons (and for the pickup artwork this module used to download the same way).
 */
internal object PickupCloud {

    private const val URL = "https://iwisdom.apps.coloros.com/wisdom/getRuleManage"
    private const val SCENE = "AIFluidWxAppletList"
    private const val FILE = "hyperchanger_hmc_next_pickup_cloud.json"
    private const val MAX_ENTRIES = 500
    private const val MAX_BYTES = 2_000_000
    private const val CONNECT_MS = 8_000
    private const val READ_MS = 15_000

    /**
     * One mini program: [name] as ColorOS spells it, [appId] what 微信 opens it under, [originId]
     * the `gh_…` its rules entry is keyed by, and [disabled] whether ColorOS has turned it off.
     */
    class Entry(
        @JvmField val name: String,
        @JvmField val appId: String,
        @JvmField val originId: String,
        @JvmField val disabled: Boolean,
    )

    @Volatile private var entries: List<Entry> = emptyList()

    /** When the cloud's own copy was last updated, in ms, or 0 when it never has been. */
    @Volatile var updatedAt: Long = 0L
        private set

    /** When this module last asked, in ms, or 0. */
    @Volatile var fetchedAt: Long = 0L
        private set

    fun count(): Int = entries.size
    fun names(): Map<String, String> = entries.associate { it.originId to it.name }
    fun disabled(): Set<String> = entries.filter { it.disabled }.map { it.originId }.toSet()

    /** The `gh_…` a 微信 mini program's appId belongs to, or null when the list does not know it. */
    fun originOf(appId: String): String? {
        val wanted = appId.lowercase(Locale.ROOT)
        return entries.firstOrNull { it.appId == wanted }?.originId
    }

    /**
     * Read what an earlier fetch left, once, when the module starts. Nothing here is fatal: an
     * unreadable or absent file simply leaves the list empty, which is the state every install
     * begins in and which the rules XML alone already covers.
     */
    fun load(ctx: Context) {
        if (entries.isNotEmpty()) return
        runCatching {
            val file = file(ctx)
            if (!file.isFile) return@runCatching
            val json = JSONObject(file.readText(Charsets.UTF_8))
            fetchedAt = json.optLong("fetched_at", 0L)
            updatedAt = json.optLong("updated_at", 0L)
            entries = read(json.optJSONArray("entries"))
            Xp.log(TAG + "cloud rules: ${entries.size} kept, update ${stamp(updatedAt)}")
        }.onFailure { Xp.log(TAG + "cloud rules not read: $it") }
    }

    /**
     * What a fetch came to: whether the list was taken, and what to show the user. Two fields
     * rather than one sentence the settings page had to read back - it took "starts with 已更新"
     * for success, which a reworded line or another language would have broken (2026-10-14).
     */
    data class Fetched(val ok: Boolean, val said: String)

    /**
     * Ask the cloud and keep the answer. [Fetched.said] is the whole story because this is a
     * button on a settings page, and "sync failed" with no reason is worse than the reason.
     */
    fun fetch(ctx: Context): Fetched {
        val now = System.currentTimeMillis()
        val reply = runCatching { ask(ctx) }.getOrElse {
            return Fetched(false, "同步失败：${it.javaClass.simpleName} ${it.message.orEmpty()}".trim())
        }
        val incoming = reply.second
        if (incoming.isEmpty()) return Fetched(false, "同步失败：云端没有 ${reply.first} 条规则里的取餐名单")
        return runCatching {
            val stored = file(ctx)
            stored.parentFile?.mkdirs()
            val tmp = File(stored.parentFile, "$FILE.tmp")
            tmp.writeText(JSONObject().apply {
                put("fetched_at", now)
                put("updated_at", reply.third)
                put("entries", JSONArray().apply { incoming.forEach { e -> put(JSONObject().apply {
                    put("name", e.name); put("id", e.appId)
                    put("origId", e.originId); put("disabled", e.disabled)
                }) } })
            }.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(stored)) throw IllegalStateException("无法写入 ${stored.name}")
            entries = incoming
            fetchedAt = now
            updatedAt = reply.third
            PickupRules.invalidate()
            Xp.log(TAG + "cloud rules: ${incoming.size} applied, update ${stamp(reply.third)}")
            Fetched(true, "已更新：${incoming.size} 个小程序，云端版本 ${stamp(reply.third)}")
        }.getOrElse { Fetched(false, "已取得 ${incoming.size} 条，但保存失败：${it.javaClass.simpleName}") }
    }

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE)

    /** The cloud's list, its own update time, and how many scenes it carried. */
    private fun ask(ctx: Context): Triple<Int, List<Entry>, Long> {
        val connection = (URL(URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            instanceFollowRedirects = false
            requestMethod = "GET"
            // What ColorOS's own HeaderInterceptor adds, and nothing else: no device id, no account.
            setRequestProperty("appVersion", "null:${android.os.Build.VERSION.SDK_INT}")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", "okhttp/4.12.0")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val raw = connection.inputStream
            val stream = if (connection.contentEncoding?.contains("gzip", true) == true) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            val body = stream.use { read(it, MAX_BYTES) }
            val response = JSONObject(String(body, Charsets.UTF_8))
            if (response.optInt("code", -1) != 200) {
                throw IllegalStateException(PickupEvent.clean(response.optString("msg")))
            }
            val data = response.optJSONArray("data") ?: error("响应缺少 data")
            for (i in 0 until data.length()) {
                val rule = data.optJSONObject(i) ?: continue
                if (rule.optString("scene") != SCENE) continue
                val ext = rule.opt("ext")
                val array = if (ext is JSONArray) ext else JSONArray(ext.toString())
                return Triple(data.length(), read(array), rule.optLong("updateTime", 0L))
            }
            return Triple(data.length(), emptyList(), 0L)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The entries as OPPO's own reader keeps them: an appId shaped like 微信's and an original id
     * shaped like the rules' are the two things that have to hold, a name that is only whitespace
     * is dropped, and one appId appears once. Anything else in the list is something this module
     * would not know what to do with, so it is not kept.
     */
    private fun read(array: JSONArray): List<Entry> {
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (i in 0 until minOf(array.length(), MAX_ENTRIES)) {
            val item = array.optJSONObject(i) ?: continue
            val name = PickupEvent.truncate(PickupEvent.clean(item.optString("name")), 80)
            val appId = PickupEvent.clean(item.optString("id")).lowercase(Locale.ROOT)
            val originId = PickupEvent.clean(item.optString("origId")).lowercase(Locale.ROOT)
            if (name.isEmpty()) continue
            if (!appId.matches(WX_ID) || !originId.matches(GH_ID) || !seen.add(appId)) continue
            out.add(Entry(name, appId, originId, item.optBoolean("disabled", false)))
        }
        return out
    }

    private fun read(stream: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) error("响应过大")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private val WX_ID = Regex("wx[a-z0-9]{8,40}")
    private val GH_ID = Regex("gh_[a-z0-9]{6,40}")

    private const val TAG = "MCPickupCloud: "

    /** What the list is right now, for the probe. */
    fun describe(): String =
        "cloud=${entries.size} updated=${stamp(updatedAt).ifEmpty { "-" }}" +
            " fetched=${stamp(fetchedAt).ifEmpty { "-" }}"

    /** A date for the settings page, in the phone's own zone. */
    fun stamp(at: Long): String =
        if (at <= 0) "" else java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(java.util.Date(at))
}
