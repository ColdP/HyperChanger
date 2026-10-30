// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.transit
import btm.m.os4.systemuihook.hypermusiccover.next.Gen
import btm.m.os4.systemuihook.hypermusiccover.next.Xp
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.os.Bundle
import org.json.JSONObject
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * What keeps 高德's live channel (bizType 113) talking on HyperOS.
 *
 * 高德's script only sends on a channel that has a connected device: on this phone nothing is
 * connected to 113, and a navigation then sends no plan (`type 24`) and no live data (`type 25`)
 * at all - measured on 2026-10-06 with this bridge taken out: `bizBegin(113)` and `bizBegin(103)`,
 * 103's cards, and not one 113 message. With it in, every 113 message the 10-05 ride and the
 * walking tests carried came through. So the OPPO intelligent card (bizType 10200,
 * thid_sdk_template_oppo_intelligent, class il3 in 17.00.0.2005) is made into that device:
 *
 *  - its device config is put into every channel's device list (`xn0.a`), and `bizBegin(10200)`
 *    is called beside every channel the script opens, with the begin data an OPPO's script sends;
 *  - il3's provider and intent are set before its own isSupport asks (`arm`);
 *  - the provider it then reaches for, IntelligentIntent (SceneService's on ColorOS), is stood in
 *    for: an acquire that would return null gets a client for Settings' provider instead, and the
 *    calls on that client are answered here, the way SceneService answers them, never reaching
 *    Settings. A phone with a real IntelligentIntent provider keeps its own.
 *
 * What the card then forwards (`shareIntent`) is 高德's raw 103/113 payloads re-wrapped, not an
 * intent entity; it is answered and dropped - AmapTransitShare reads the same payloads off the
 * channels directly.
 */
internal object AmapOppoBridge {

    private const val TAG = "MCAmap: bridge: "
    private const val AUTHORITY = "IntelligentIntent"
    /** Every app may reach it, and nothing below ever does. */
    private const val STAND_IN = "settings"
    private const val INTENT_NAME = "Navigation.NotifyPublicTransportStatus"
    private const val CODE_OK = 0
    private const val CODE_UNSUPPORTED = 1003
    private const val WEARABLE = "com.amap.bundle.wearable.ajx.NativesModuleWearable"
    private const val WEARABLE_SERVICE = "com.amap.bundle.wearable.WearableService"
    private const val OPPO_CARD = "il3"
    private const val BIZ_TABLE = "xn0"
    private const val OPPO_BIZ = 10200
    private const val OPPO_DEVICE = "thid_sdk_template_oppo_intelligent"
    private const val PROVIDER = "IntelligentIntent"
    private const val INTENT = "Navigation.NotifyPublicTransportStatus"
    private const val OPPO_BEGIN_DATA =
        "{\"authority\":\"$PROVIDER\",\"intentName\":\"$INTENT\"}"

    /** The clients handed out for the authority; weak, so a released one is forgotten. */
    private val ours: MutableSet<ContentProviderClient> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val bridging = AtomicBoolean(false)
    private val bridged = LinkedHashMap<String, String>()
    @Volatile private var oppoCard: Class<*>? = null
    @Volatile private var oppoConfig: Any? = null
    private val acquires = AtomicInteger()
    private val shares = AtomicInteger()

    /** The clients still out, for the next generation (HotReload.saveFeatures). */
    fun save(): Array<Any?> = synchronized(ours) { ours.toTypedArray<Any?>() }

    /** The previous generation's clients, taken as ours before the hooks go in. */
    fun adopt(saved: Array<Any?>?) {
        val clients = saved?.filterIsInstance<ContentProviderClient>() ?: return
        ours.addAll(clients)
        if (clients.isNotEmpty()) Xp.log(TAG + "adopted ${clients.size} stand-in client(s)")
    }

    fun handle(cl: ClassLoader) {
        try {
            for (name in arrayOf("acquireUnstableContentProviderClient", "acquireContentProviderClient")) {
                Xp.hookAll("amap.bridge.acquire." + Xp.slug(name), ContentResolver::class.java, name) { chain ->
                    val out = chain.proceed()
                    val asked = chain.args.getOrNull(0)
                    if (out != null || asked !is String || asked != AUTHORITY) return@hookAll out
                    acquires.incrementAndGet()
                    // Re-enters this hook with the stand-in's authority, which passes straight on.
                    val client = (chain.thisObject as ContentResolver)
                        .acquireUnstableContentProviderClient(STAND_IN)
                    if (client == null) {
                        Xp.log(TAG + "no stand-in client for $AUTHORITY")
                        return@hookAll null
                    }
                    ours.add(client)
                    Xp.log(TAG + "$AUTHORITY asked for (#${acquires.get()}), answered here")
                    client
                }
            }
            Xp.hookAll("amap.bridge.provider_call", ContentProviderClient::class.java, "call") { chain ->
                if (chain.thisObject !in ours) return@hookAll chain.proceed()
                val a = chain.args
                // call(method, arg, extras), or call(authority, method, arg, extras).
                val off = if (a.size >= 4) 1 else 0
                answer(a.getOrNull(off) as String?, a.getOrNull(off + 1) as String?,
                    a.getOrNull(off + 2) as Bundle?)
            }
            Xp.log(TAG + "standing in for $AUTHORITY")
        } catch (t: Throwable) {
            Xp.w(TAG + "hooks failed: " + t)
        }
        try {
            val service = Xp.findClass(WEARABLE_SERVICE, cl)
            bridgeOppo(service, cl)
            // The card's channel goes when the script's does.
            Xp.hookAll("amap.bridge.biz_end", service, "bizEnd") { chain ->
                if (bridging.get()) return@hookAll chain.proceed()
                val biz = chain.args.firstOrNull { it is Int } as Int?
                val ours = synchronized(bridged) {
                    biz != null && biz != OPPO_BIZ && bridged.containsKey("bizBegin($biz)")
                }
                val svc = chain.thisObject
                val out = chain.proceed()
                if (ours && svc != null) close(svc, biz!!)
                out
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "wearable service not bridged: $t")
        }
    }

    private fun answer(method: String?, arg: String?, extras: Bundle?): Bundle {
        val result = try {
            when (method) {
                "queryFeature" -> query(arg, extras)
                "shareIntent" -> share(extras?.getString("intentData"))
                "deleteIntent", "deleteEntity" -> result(CODE_OK, null)
                "getSid" -> result(CODE_OK, null)
                else -> {
                    Xp.log(TAG + "unknown call $method")
                    result(CODE_UNSUPPORTED, null)
                }
            }
        } catch (t: Throwable) {
            Xp.w(TAG + "$method failed: $t")
            result(CODE_UNSUPPORTED, null)
        }
        return Bundle().apply { putString("result", result.toString()) }
    }

    /** SceneService's QueryFeatureUtil.f, for the one intent this stands in for. */
    private fun query(feature: String?, extras: Bundle?): JSONObject {
        val data = JSONObject()
        when (feature) {
            "querySupportIntent" -> data.put(feature, extras?.getString("intentName") == INTENT_NAME)
            "querySupportIntentByPackage" -> data.put(INTENT_NAME, true)
            "enableIntelligentIntent" -> data.put(feature, true)
            else -> {
                Xp.log(TAG + "unknown feature $feature")
                return result(CODE_UNSUPPORTED, null)
            }
        }
        Xp.log(TAG + "queryFeature $feature ${extras?.getString("intentName").orEmpty()} -> $data")
        return result(CODE_OK, data.toString())
    }

    /** The card's forwarded payload: counted, answered, dropped (see the class's note). */
    private fun share(intentData: String?): JSONObject {
        if (intentData.isNullOrEmpty()) return result(CODE_UNSUPPORTED, null)
        shares.incrementAndGet()
        return result(CODE_OK, null)
    }

    private fun result(code: Int, data: String?): JSONObject = JSONObject()
        .put("code", code)
        .put("message", if (code == CODE_OK) "success" else "unsupported")
        .apply { if (data != null) put("data", data) }

    /**
     * The channel 高德's script never opens here. 高德's Java keeps the OPPO intelligent card
     * (bizType 10200, thid_sdk_template_oppo_intelligent, il3) unconditional - `xn0`'s table maps
     * it and `jl3.getConfig` hands it back for any phone - so a begin for it needs no machine
     * check at all; only the script's decision is missing.
     *
     * Two halves, because the card on its own is a dead end:
     *  - `bizBegin(10200)` is called beside every channel the script does begin, with the begin
     *    data that sets the card's provider and intent (il3.onReceiveBizBeginData) and the intent
     *    itself pre-armed on the instance (isSupport reads both before it will call at all). The
     *    provider it then asks for is this module's own stand-in, so the card is supported here.
     *  - the card's device config is put into the channel's own device list as well (`xn0.a`), so
     *    every payload the script sends for the ride reaches the card too, in 高德's own words.
     * What comes out is whatever 高德's script sends for a bus or subway ride, passed through the
     * same path SceneService uses on an OPPO; the probe reports both halves.
     */
    private fun bridgeOppo(service: Class<*>, cl: ClassLoader) {
        try {
            oppoCard = Xp.findClass(OPPO_CARD, cl)
            hookOppoCard()
        } catch (t: Throwable) {
            Xp.w(TAG + "OPPO card hooks failed: $t")
        }
        try {
            val table = Xp.findClass(BIZ_TABLE, cl)
            for (m in table.declaredMethods) {
                if (m.name != "a" || m.parameterTypes.size != 2 ||
                    m.parameterTypes[0] != Integer.TYPE
                ) continue
                m.isAccessible = true
                Xp.hook("amap.bridge.biz_table", m) { chain ->
                    val out = chain.proceed()
                    // Only a channel the bridge opened, and never 10200's own list.
                    val biz = chain.args.firstOrNull { it is Int } as Int?
                    if (biz != null && biz != OPPO_BIZ && out is MutableList<*>) inject(biz, out)
                    out
                }
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "device table not hooked: $t")
        }
        // The script's own door: the AJX module's begin, on whichever object carries it - here the
        // script's bizBegin never reaches WearableService itself, so the service is called directly.
        try {
            val module = Xp.findClass(WEARABLE, cl)
            val begins = module.declaredMethods.filter {
                (it.name == "bizBegin" || it.name == "bizBeginWithData") &&
                    it.parameterTypes.firstOrNull() == Integer.TYPE
            }
            for (begin in begins) {
                begin.isAccessible = true
                val iface = ifaceOf(begin)
                val data = begin.parameterTypes.getOrNull(1) == String::class.java
                Xp.hook("amap.bridge." + Xp.slug(begin.name), begin) { chain ->
                    val a = chain.args
                    val biz = a.firstOrNull { it is Int } as Int?
                    val svc = a.firstOrNull { iface.isInstance(it) }
                    if (biz != null && biz != OPPO_BIZ && svc != null) {
                        open(svc, begin, biz, a.getOrNull(1) as? String, data)
                    }
                    chain.proceed()
                }
            }
            Xp.log(TAG + "OPPO channel bridge armed on " + begins.size + " module begin(s)")
        } catch (t: Throwable) {
            Xp.w(TAG + "OPPO channel bridge failed: $t")
        }
    }

    /** The wearable service type the module's begin carries, whatever it is called. */
    private fun ifaceOf(begin: java.lang.reflect.Method): Class<*> =
        begin.parameterTypes.firstOrNull { it.name.contains("earable") && it.isInterface }
            ?: begin.parameterTypes[1]

    /**
     * The card's instance, whichever begin built it: its provider and intent are set before its
     * own isSupport looks at them, so it counts as supported here even though the script never
     * handed it the data an OPPO's script would.
     */
    private fun hookOppoCard() {
        val card = oppoCard ?: return
        try {
            Xp.hookAll("amap.bridge.card_support", card, "isSupport") { chain ->
                arm(chain.thisObject)
                chain.proceed()
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "card isSupport not hooked: $t")
        }
        try {
            Xp.hookAll("amap.bridge.card_connect", card, "connect") { chain ->
                setBridged("card", "connected")
                Xp.log(TAG + "$OPPO_DEVICE connected, provider=$PROVIDER intent=$INTENT")
                chain.proceed()
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "card connect not hooked: $t")
        }
    }

    /** il3's authority (h) and intent (g), the two isSupport refuses without. */
    private fun arm(device: Any?) {
        if (device == null) return
        try {
            Xp.setObjectField(device, "h", PROVIDER)
            Xp.setObjectField(device, "g", INTENT)
        } catch (t: Throwable) {
            Xp.log(TAG + "card not armed: $t")
        }
    }

    /** Puts 10200's own device config into [list], once per channel. */
    private fun inject(biz: Int, list: MutableList<*>) {
        val key = "bizBegin($biz)"
        synchronized(bridged) { if (!bridged.containsKey(key)) return }
        try {
            val cfg = oppoConfig ?: config().also { oppoConfig = it }
            if (cfg == null) {
                setBridged(key, "no 10200 config")
                return
            }
            if (list.contains(cfg)) return
            @Suppress("UNCHECKED_CAST")
            (list as MutableList<Any?>).add(cfg)
            setBridged(key, "in channel, " + list.size + " devices")
            Xp.log(TAG + "$OPPO_DEVICE rides in bizType $biz (" + list.size + " devices)")
        } catch (t: Throwable) {
            setBridged(key, "inject failed " + t)
        }
    }

    /** One fresh wn0 for the OPPO card, from 高德's own table, never the script's list. */
    private fun config(): Any? {
        val table = try {
            Xp.findClass(BIZ_TABLE, oppoCard?.classLoader)
        } catch (t: Throwable) {
            return null
        }
        for (m in table.declaredMethods) {
            if (m.name != "a" || m.parameterTypes.size != 2 ||
                m.parameterTypes[0] != Integer.TYPE
            ) continue
            try {
                m.isAccessible = true
                val out = m.invoke(null, OPPO_BIZ, null) as? List<*> ?: continue
                if (out.isNotEmpty()) return out[0]
            } catch (t: Throwable) {
                Xp.log(TAG + "10200 config not read: $t")
            }
        }
        return null
    }

    /** Opens 10200 beside the channel the script just began, once per bizType. */
    private fun open(svc: Any, begin: java.lang.reflect.Method, biz: Int, data: String?,
                     withData: Boolean) {
        val key = "bizBegin($biz)"
        Xp.log(TAG + "opening $OPPO_DEVICE for bizType $biz (withData=" + withData + ")")
        synchronized(bridged) {
            if (bridged.containsKey(key)) return
            bridged[key] = "opening"
            while (bridged.size > 8) bridged.remove(bridged.keys.first())
        }
        try {
            if (!bridging.compareAndSet(false, true)) return
            try {
                val method = on(svc, "bizBeginWithData")
                val plain = on(svc, "bizBegin")
                val cbType = (method ?: plain)?.parameterTypes?.get(1) ?: return
                // The service drops a begin whose callback is null, so a stand-in of the script's
                // own kind is made; only the card's connection state ever goes to it.
                // The service keeps it for the ride, past a retirement; a retired generation's
                // copy answers nothing. Its identity is answered either way - null there was an
                // NPE for whoever put it in a map.
                val cb = java.lang.reflect.Proxy.newProxyInstance(
                    cbType.classLoader, arrayOf(cbType)
                ) { self, m, args ->
                    when (m.name) {
                        "hashCode" -> System.identityHashCode(self)
                        "equals" -> self === args?.firstOrNull()
                        "toString" -> "MusicCover OPPO card callback"
                        "callback" -> {
                            if (Gen.live()) setBridged(key, "callback " + args?.firstOrNull())
                            null
                        }
                        else -> null
                    }
                }
                if (method != null) {
                    // The card's provider and intent come from the begin data (il3.onReceiveBizBeginData),
                    // so 高德's own for an OPPO is used; the script's is not that shape.
                    method.invoke(svc, OPPO_BIZ, OPPO_BEGIN_DATA, cb, null)
                } else if (plain != null) {
                    plain.invoke(svc, OPPO_BIZ, cb, null)
                    beginOppoWithData(svc, cb)
                } else {
                    setBridged(key, "no begin on the service")
                    return
                }
            } finally {
                bridging.set(false)
            }
            if (bridged[key] == "opening") setBridged(key, "asked")
            Xp.log(TAG + "bridged $OPPO_DEVICE for bizType $biz -> bizBegin($OPPO_BIZ)")
        } catch (t: Throwable) {
            setBridged(key, "failed " + t)
            Xp.w(TAG + "bridge for $biz failed: $t")
        }
    }

    /** One of the service's own methods, by name, whatever its object is. */
    private fun on(svc: Any, name: String): java.lang.reflect.Method? =
        svc.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.firstOrNull() == Integer.TYPE
        }

    /** il3.onReceiveBizBeginData with the card's two fields, for a data-less begin. */
    private fun beginOppoWithData(svc: Any, cb: Any) {
        val method = on(svc, "bizBeginWithData") ?: return
        method.invoke(svc, OPPO_BIZ, OPPO_BEGIN_DATA, cb, null)
    }

    /** The card's channel goes when the script's does, so it never outlives the ride. */
    private fun close(svc: Any, biz: Int) {
        try {
            val method = on(svc, "bizEnd") ?: return
            if (!bridging.compareAndSet(false, true)) return
            try {
                method.invoke(svc, OPPO_BIZ)
            } finally {
                bridging.set(false)
            }
            synchronized(bridged) { bridged.remove("bizBegin($biz)") }
            Xp.log(TAG + "closed $OPPO_DEVICE with bizType $biz")
        } catch (t: Throwable) {
            Xp.w(TAG + "close for $biz failed: $t")
        }
    }

    private fun setBridged(key: String, what: String) {
        synchronized(bridged) { bridged[key] = what }
    }

    fun describe(): String = "bridge: acquires=" + acquires.get() + " shares=" + shares.get() + " " +
        synchronized(bridged) { if (bridged.isEmpty()) "nothing yet" else bridged.entries.joinToString(" ") { it.key + "=" + it.value } }
}
