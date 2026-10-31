// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** App UID cannot query HMC directly; only our SystemUI receiver answers this read-only query. */
@Composable
internal fun rememberMusicLockscreenConflicts(): MusicLockscreenPolicy {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    var state by remember { mutableStateOf(MusicLockscreenPolicy()) }
    LaunchedEffect(context, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                queryMusicLockscreenConflicts(context)?.let { state = it }
                delay(2000L)
            }
        }
    }
    return state
}

private suspend fun queryMusicLockscreenConflicts(context: Context): MusicLockscreenPolicy? =
    suspendCancellableCoroutine { continuation ->
        val handler = Handler(Looper.getMainLooper())
        var done = false
        val timeout = Runnable {
            if (!done) {
                done = true
                if (continuation.isActive) continuation.resume(null)
            }
        }
        handler.postDelayed(timeout, 1800L)
        continuation.invokeOnCancellation { done = true; handler.removeCallbacks(timeout) }
        try {
            context.sendOrderedBroadcast(Intent(HmcConflictMonitor.STATUS_ACTION)
                .setPackage("com.android.systemui"), null, HmcConflictMonitor.identityOptions(),
                object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (done) return
                        done = true
                        handler.removeCallbacks(timeout)
                        val b = getResultExtras(false)
                        val state = if (b?.getBoolean("checked") == true) MusicLockscreenPolicy(
                            true, b.getBoolean("coverConflict"), b.getBoolean("lyricsConflict"),
                            b.getBoolean("capsuleConflict")) else null
                        if (continuation.isActive) continuation.resume(state)
                    }
                }, handler, 0, null, null)
        } catch (_: Exception) {
            done = true
            handler.removeCallbacks(timeout)
            if (continuation.isActive) continuation.resume(null)
        }
    }
