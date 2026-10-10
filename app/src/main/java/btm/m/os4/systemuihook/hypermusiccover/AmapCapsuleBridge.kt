// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.hypermusiccover

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import btm.m.os4.systemuihook.LockscreenCapsuleImmersiveHost

/** Bridges 高德's navigation lifecycle to the SystemUI capsule immersive host. */
object AmapCapsuleBridge {
    private const val TAG = "[HyperChanger-Amap] "
    private const val PACKAGE = "com.autonavi.minimap"
    private const val MODULE = "com.autonavi.minimap.immersenavi.module.NativesModuleImmerseNavi"
    private const val SYSUI = "com.android.systemui"
    private var context: Context? = null
    private var registered = false

    @JvmStatic
    fun handle(loader: ClassLoader) {
        runCatching {
            val instrumentation = Xp.findClass("android.app.Instrumentation", loader)
            Xp.hookAll(instrumentation, "callApplicationOnCreate") { chain ->
                val result = chain.proceed()
                val app = chain.args.firstOrNull() as? Application
                if (app != null && Application.getProcessName() == PACKAGE) register(app)
                result
            }
        }.onFailure { Xp.log(TAG + "application hook unavailable: $it") }
        runCatching {
            val module = Xp.findClass(MODULE, loader)
            Xp.hookAll(module, "init") { chain ->
                val result = chain.proceed()
                tell(true)
                result
            }
            Xp.hookAll(module, "destroy") { chain ->
                tell(false)
                chain.proceed()
            }
        }.onFailure { Xp.log(TAG + "navigation module hook unavailable: $it") }
    }

    private fun register(application: Application) {
        if (registered) return
        registered = true
        context = application.applicationContext
        val filter = IntentFilter(LockscreenCapsuleImmersiveHost.ACTION)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra(LockscreenCapsuleImmersiveHost.EXTRA_STATE) != LockscreenCapsuleImmersiveHost.STATE_READY) return
                // The overview flag is consumed by AMap's own page; keeping the broadcast in the
                // app process lets newer AMap builds opt into the same protocol without a hard
                // dependency on their private Messenger class.
            }
        }
        if (Build.VERSION.SDK_INT >= 33) application.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") application.registerReceiver(receiver, filter)
        tell(true)
    }

    private fun tell(ready: Boolean) {
        val ctx = context ?: return
        runCatching {
            ctx.sendBroadcast(
                Intent(LockscreenCapsuleImmersiveHost.ACTION).setPackage(SYSUI)
                    .putExtra(LockscreenCapsuleImmersiveHost.EXTRA_STATE,
                        if (ready) LockscreenCapsuleImmersiveHost.STATE_READY else LockscreenCapsuleImmersiveHost.STATE_STOP),
            )
        }
    }
}
