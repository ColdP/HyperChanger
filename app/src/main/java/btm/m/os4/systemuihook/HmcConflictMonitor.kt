// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** Runs outside the media gates so detection also works when every local feature is off. */
object HmcConflictMonitor {
    internal const val STATUS_ACTION = "btm.m.os4.systemuihook.MUSIC_OWNERSHIP"
    @Volatile internal var policy = MusicLockscreenPolicy()
        private set
    private var context: Context? = null
    private var systemUi = false
    private var installed = false
    private var querying = false
    private val handler = Handler(Looper.getMainLooper())
    private var changed: (() -> Unit)? = null
    private val poll = object : Runnable {
        override fun run() {
            context?.let(::query)
            handler.postDelayed(this, 2500L)
        }
    }

    internal fun startNow(app: Context, onChanged: () -> Unit) {
        if (installed) return
        installed = true
        systemUi = app.packageName == "com.android.systemui"
        changed = onChanged
        start(app)
    }

    @Synchronized private fun start(app: Context) {
        if (context != null) return
        context = app.applicationContext
        if (systemUi) {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val uid = sentFromUid
                    val appUid = runCatching { c.packageManager.getPackageUid(BuildConfig.APPLICATION_ID, 0) }.getOrDefault(-2)
                    val wallpaperUid = runCatching { c.packageManager.getPackageUid("com.miui.miwallpaper", 0) }.getOrDefault(-2)
                    if (uid != android.os.Process.myUid() && uid != appUid && uid != wallpaperUid) return
                    if (isOrderedBroadcast) setResultExtras(statusBundle())
                }
            }, IntentFilter(STATUS_ACTION), Context.RECEIVER_EXPORTED)
        }
        handler.post(poll)
    }

    private fun query(ctx: Context) {
        if (querying) return
        querying = true
        var done = false
        val timeout = Runnable {
            if (!done) {
                done = true
                querying = false
            }
        }
        handler.postDelayed(timeout, 1800L)
        val intent = Intent(if (systemUi) "com.os4.musiccover.PROBE" else STATUS_ACTION)
            .setPackage("com.android.systemui").addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra("op", "query")
        try {
            ctx.sendOrderedBroadcast(intent, null, identityOptions(), object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (done) return
                    done = true
                    querying = false
                    handler.removeCallbacks(timeout)
                    val b = getResultExtras(false)
                    if (systemUi) {
                        if (b?.getBoolean("alive") == true) {
                            val mini = runCatching { JSONObject(b.getString("minicfg", "{}")) }.getOrNull()
                            publish(MusicLockscreenPolicy(true,
                                b.getBoolean("coverfeature") || b.getBoolean("cover"),
                                b.getBoolean("lyrics", false), mini?.optBoolean("enabled", false) == true))
                        } else if (!policy.checked) {
                            publish(MusicLockscreenPolicy(checked = true))
                        }
                    } else if (b?.getBoolean("checked") == true) {
                        publish(MusicLockscreenPolicy(true, b.getBoolean("coverConflict"),
                            b.getBoolean("lyricsConflict"), b.getBoolean("capsuleConflict")))
                    }
                    // A missing reply never clears an already verified conflict.
                }
            }, handler, 0, null, null)
        } catch (error: Exception) {
            handler.removeCallbacks(timeout)
            querying = false
            ModuleLog.w("MusicConflict", "Ownership query failed", error)
        }
    }

    private fun publish(next: MusicLockscreenPolicy) {
        if (next == policy) return
        HyperMusicCoverRuntimeBridge.beforePolicyChange(policy, next)
        policy = next
        changed?.invoke()
    }

    internal fun statusBundle() = Bundle().apply {
        val p = policy
        putBoolean("checked", p.checked)
        putBoolean("coverConflict", p.coverConflict)
        putBoolean("lyricsConflict", p.lyricsConflict)
        putBoolean("capsuleConflict", p.capsuleConflict)
    }

    internal fun identityOptions(): Bundle = BroadcastOptions.makeBasic().apply {
        setShareIdentityEnabled(true)
    }.toBundle()
}
