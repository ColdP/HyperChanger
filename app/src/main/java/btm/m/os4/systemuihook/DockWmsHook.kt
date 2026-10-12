// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.PowerManager
import android.view.SurfaceControlViewHost
import android.view.SurfaceControl
import android.view.WindowManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Method
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import btm.m.os4.systemuihook.dockmotion.DockNativeMotion
import btm.m.os4.systemuihook.dockmotion.DockNativeMotionEndpoint
import btm.m.os4.systemuihook.dockmotion.DockRecentsMotion
import btm.m.os4.systemuihook.dockmotion.DockWallpaperEndpoint

/** Composites the Dock background below the launcher buffer in the WMS window tree. */
internal object DockWmsHook {
    private val launcherTitles = setOf(
        "com.miui.home/com.miui.home.launcher.Launcher",
        "com.miui.home/.launcher.Launcher",
        "com.miui.home.launcher.Launcher",
    )
    private data class Appearance(
        val settings: DockSettings,
        val margin: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val radius: Float,
        val density: Float,
        val dark: Boolean,
        val frameWidth: Int,
        val frameHeight: Int,
    )
    private data class Layer(
        val parent: SurfaceControl,
        val effect: SurfaceControl?,
        val tint: SurfaceControl,
        val highlight: SurfaceControl,
        var appearance: Appearance? = null,
        var glassKey: String? = null,
        var glassId: String? = null,
        var glassPackage: SurfaceControlViewHost.SurfacePackage? = null,
        var glassSurface: SurfaceControl? = null,
        var glassReady: Boolean = false,
        var pending: Boolean = false,
        var closed: Boolean = false,
        var lastFailure: Long = 0,
        var remoteContext: android.content.Context? = null,
        var revealAt: Long = 0L,
        var revealScheduled: Boolean = false,
        var uid: Int = -1,
        var pid: Int = -1,
        var session: Any? = null,
        var token: IBinder? = null,
        var overview: Boolean = false,
        val motion: DockNativeMotion = DockNativeMotion(),
        val fallback: DockRecentsMotion = DockRecentsMotion(),
        var nativeApplied: Boolean = false,
        var projection: Float = 1f,
        var pose: DockMotionPose? = null,
    )
    private val layers = IdentityHashMap<Any, Layer>()
    private var backgroundBlurAvailable = true
    private var missingSurfaceReported = false
    private val worker = Handler(HandlerThread("HyperChangerDockMaterial").apply { start() }.looper)
    private var nextGlassId = 0L
    private var lastUnlockAt = 0L
    private const val REVEAL_MS = 821L
    private val frameQueued = AtomicBoolean()
    private val nativePose = AtomicReference<DockNativePose?>()
    private val endpoint = DockNativeMotionEndpoint(
        { scheduleMotion() },
        { android.util.Log.i("HyperChangerDockMotion", it) },
    )

    fun install(module: HyperSystemUiModule, param: PackageLoadedParam) = install(module, param.defaultClassLoader)

    fun install(module: HyperSystemUiModule, loader: ClassLoader) {
        val windowClass = runCatching { loader.loadClass("com.android.server.wm.WindowState") }.getOrNull() ?: return
        val prepare = windowClass.declaredMethods.firstOrNull {
            it.name == "prepareSurfaces" && it.parameterCount == 0
        } ?: return
        module.installHook(prepare)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:desktop-dock-wms")
            .intercept { chain ->
                val result = chain.proceed()
                runCatching { update(chain.thisObject, module) }
                    .onFailure { android.util.Log.e("HyperChangerDock", "Dock update failed", it) }
                result
            }
        android.util.Log.i("HyperChangerDock", "WindowState.prepareSurfaces hook installed")
        windowClass.declaredMethods.firstOrNull {
            it.name == "removeImmediately" && it.parameterCount == 0
        }?.let { removeMethod ->
            module.installHook(removeMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:desktop-dock-remove")
                .intercept { chain ->
                    remove(chain.thisObject)
                    chain.proceed()
                }
        }
        installRevealHook(module, loader)
        installMotionEndpoint(module, loader)
        installWallpaperMotion(module, loader, windowClass)
    }

    private fun installMotionEndpoint(module: HyperSystemUiModule, loader: ClassLoader) {
        val stub = loader.loadClass("android.view.IWindowManager\$Stub")
        val method = stub.getDeclaredMethod("onTransact", Int::class.javaPrimitiveType,
            Parcel::class.java, Parcel::class.java, Int::class.javaPrimitiveType)
        module.installHook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("hyperchanger:desktop-dock-motion-binder")
            .intercept { chain ->
                val code = chain.getArg(0) as Int
                if (!DockNativeMotionEndpoint.handles(code) && code != DockNativeMotionEndpoint.STATE_TRANSACTION_CODE && code != DOCK_POSE_TRANSACTION) {
                    return@intercept chain.proceed()
                }
                val data = chain.getArg(1) as Parcel
                val reply = chain.getArg(2) as? Parcel ?: return@intercept false
                if (code == DOCK_POSE_TRANSACTION) {
                    data.enforceInterface("android.view.IWindowManager")
                    val uid = Binder.getCallingUid()
                    val pid = Binder.getCallingPid()
                    if ((chain.getArg(3) as Int) and IBinder.FLAG_ONEWAY == 0 && data.dataAvail() == 64) {
                        val wire = LongArray(8) { data.readLong() }
                        val previous = nativePose.get()
                        val pose = decodeDockNativePose(uid, pid, wire,
                            previous?.takeIf { it.uid == uid && it.pid == pid }?.sequence ?: 0L, System.nanoTime())
                        val authenticated = synchronized(layers) { layers.values.any { it.uid == uid && it.pid == pid && !it.closed } }
                        if (pose != null && authenticated) {
                            nativePose.updateAndGet { current ->
                                if (current != null && current.uid == uid && current.pid == pid && current.sequence >= pose.sequence) current else pose
                            }
                            scheduleMotion()
                        }
                    }
                    reply.writeInt(DockNativeMotionEndpoint.ACK)
                } else if (code == DockNativeMotionEndpoint.STATE_TRANSACTION_CODE) {
                    data.enforceInterface("android.view.IWindowManager")
                    val enabled = readDockSettings(module.getRemotePreferences(REMOTE_PREFERENCE_GROUP)).enabled
                    val callerUid = Binder.getCallingUid()
                    val callerPid = Binder.getCallingPid()
                    val ownLayer = synchronized(layers) {
                        layers.values.firstOrNull { it.uid == callerUid && it.pid == callerPid && !it.closed }
                    }
                    val context = ownLayer?.remoteContext
                    val interactive = context?.getSystemService(PowerManager::class.java)?.isInteractive != false
                    reply.writeInt(DockNativeMotionEndpoint.STATE_ACK)
                    reply.writeInt(if (enabled && interactive) 1 else 0)
                } else {
                    reply.writeInt(endpoint.receive(code, data, chain.getArg(3) as Int))
                }
                true
            }
        android.util.Log.i("HyperChangerDockMotion", "Authenticated native motion endpoint installed")
    }

    private fun installWallpaperMotion(module: HyperSystemUiModule, loader: ClassLoader, windowClass: Class<*>) {
        runCatching {
            val resolved = DockWallpaperEndpoint.resolve(loader.loadClass("com.android.server.wm.WallpaperController"),
                windowClass, loader.loadClass("com.android.server.wm.Session"), IBinder::class.java, Bundle::class.java)
            module.installHook(resolved.method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:desktop-dock-motion-scene")
                .intercept { chain ->
                    runCatching {
                        val command = chain.getArg(1) as? String
                        val extras = chain.getArg(5) as? Bundle
                        @Suppress("DEPRECATION")
                        val scale = (extras?.get("scale_to") as? Number)?.toDouble()
                        val target = scale?.let { DockRecentsMotion.overviewTarget(command, extras.getString("action"), it) }
                        if (target != null) {
                            synchronized(layers) {
                                layers.entries.firstOrNull { (window, layer) ->
                                    if (resolved.sessionScoped()) DockWallpaperEndpoint.ownsWindow(
                                        layer.session, layer.token, chain.thisObject, chain.getArg(0))
                                    else window === chain.getArg(0)
                                }?.value?.let { layer ->
                                    synchronized(layer) {
                                        layer.overview = target
                                        layer.fallback.setOverview(target, SystemClock.uptimeMillis())
                                        if (extras.getString("action") == "setTo") layer.fallback.finish()
                                        if (target) endpoint.requestHookRevalidation(layer.uid, layer.pid)
                                    }
                                    scheduleMotion()
                                }
                            }
                        }
                    }.onFailure { android.util.Log.w("HyperChangerDockMotion", "Scene observer failed", it) }
                    chain.proceed()
                }
        }.onFailure { android.util.Log.w("HyperChangerDockMotion", "Scene endpoint unavailable; native following remains enabled", it) }
    }

    private fun bindMotion(window: Any, layer: Layer) {
        val session = field(window, "mSession")?.get(window) ?: return
        val uid = field(session, "mUid")?.get(session) as? Int ?: return
        val pid = field(session, "mPid")?.get(session) as? Int ?: return
        if (uid < 10000 || pid <= 0) return
        synchronized(layer) {
            if (layer.uid != uid || layer.pid != pid) {
                layer.motion.reset()
                layer.nativeApplied = false
                layer.projection = 1f
                layer.pose = null
            }
            layer.session = session
            layer.token = (field(window, "mClient")?.get(window) as? android.os.IInterface)?.asBinder()
                ?: field(window, "mClient")?.get(window) as? IBinder
            layer.uid = uid
            layer.pid = pid
        }
        endpoint.bindIdentity(uid, pid)
    }

    /** Coalesce Binder samples. Apply every material as one parent transform. */
    private fun scheduleMotion() {
        if (!frameQueued.compareAndSet(false, true)) return
        worker.postDelayed({
            frameQueued.set(false)
            var running = false
            val snapshot = synchronized(layers) { layers.values.toList() }
            snapshot.forEach { layer ->
                if (!layer.closed) runCatching { if (drawMotion(layer)) running = true }
                    .onFailure { android.util.Log.w("HyperChangerDockMotion", "Motion frame failed", it) }
            }
            if (running) scheduleMotion()
        }, 8L)
    }

    private fun installRevealHook(module: HyperSystemUiModule, loader: ClassLoader) {
        for (name in listOf("com.android.server.wm.KeyguardController", "com.android.server.wm.ActivityTaskManagerService")) {
            val method = runCatching { loader.loadClass(name) }.getOrNull()?.declaredMethods?.firstOrNull {
                it.name == "keyguardGoingAway" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            } ?: continue
            module.installHook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:desktop-dock-reveal")
                .intercept { chain ->
                    runCatching { beginReveal(module) }
                        .onFailure { android.util.Log.w("HyperChangerDock", "Dock reveal failed", it) }
                    chain.proceed()
                }
            android.util.Log.i("HyperChangerDock", "Dock reveal trigger installed on $name")
            return
        }
        android.util.Log.w("HyperChangerDock", "Dock reveal trigger unavailable")
    }

    private fun beginReveal(module: HyperSystemUiModule) {
        val settings = readDockSettings(module.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
        if (!settings.enabled || !settings.entryAnimation) return
        val now = SystemClock.uptimeMillis()
        if (now - lastUnlockAt < REVEAL_MS) return
        lastUnlockAt = now
        synchronized(layers) {
            layers.values.forEach { layer ->
                if (layer.closed || layer.appearance == null) return@forEach
                layer.revealAt = now
                drawReveal(layer)
                scheduleReveal(layer)
            }
        }
    }

    private fun scheduleReveal(layer: Layer) {
        if (!layer.closed) scheduleMotion()
    }

    private fun drawReveal(layer: Layer) { drawMotion(layer) }

    private fun drawMotion(layer: Layer): Boolean {
        synchronized(layer) {
            val appearance = layer.appearance ?: return false
            val effect = layer.effect ?: return false
            if (!effect.isValid || layer.closed) return false
            val now = SystemClock.uptimeMillis()
            val sample = endpoint.latest(layer.uid, layer.pid)
            if (sample != null) {
                layer.motion.accept(sample, layer.overview)
                layer.nativeApplied = true
                if (sample.scene() == 1) layer.overview = true
                else if (sample.scene() == 2 || sample.scale() >= .999999) layer.overview = false
            } else if (layer.nativeApplied) {
                layer.fallback.resumeFrom(layer.motion.progress(), layer.overview, now)
                layer.nativeApplied = false
            }
            val projected = DockNativeMotion.autoAimScale(endpoint.latestAutoAim(layer.uid, layer.pid),
                layer.revealAt.takeIf { it > 0 } ?: lastUnlockAt, System.nanoTime())
            val observedPose = nativePose.get()?.takeIf {
                it.uid == layer.uid && it.pid == layer.pid && it.timestamp <= System.nanoTime()
                    && System.nanoTime() - it.timestamp <= DOCK_POSE_MAX_AGE_NS
            }
            layer.projection = observedPose?.scale ?: projected?.toFloat() ?: 1f
            val elapsed = (now - layer.revealAt - 10L).coerceAtLeast(0L)
            val t = if (layer.revealAt > 0L) (elapsed.toFloat() / REVEAL_MS).coerceIn(0f, 1f) else 1f
            val remaining = 1f - t
            val lift = remaining * remaining * (1f - 2.25f * t)
            val alphaT = (elapsed / 280f).coerceIn(0f, 1f)
            val alpha = if (layer.revealAt > 0L) alphaT * alphaT * (3f - 2f * alphaT) else 1f
            val scale = if (layer.nativeApplied) layer.motion.scale() else 1f - .05f * layer.fallback.progress(now)
            // The launcher's parent already carries the large recents translation.
            // Only compensate the residual icon-row shift inside that parent.
            val offset = if (layer.nativeApplied) layer.motion.relativeOffsetY(appearance.density)
                else DockNativeMotion.relativeOffsetY(appearance.density, layer.fallback.progress(now))
            val pose = dockMotionPose(appearance.margin.toFloat(), appearance.y.toFloat(),
                appearance.frameWidth / 2f, appearance.frameHeight / 2f, scale, layer.projection,
                (observedPose?.x ?: 0f) * appearance.density,
                offset + if (observedPose != null) observedPose.y * appearance.density else 96f * appearance.density * lift,
                observedPose?.alpha ?: alpha)
            if (t >= 1f) layer.revealAt = 0L
            val running = layer.revealAt > 0L || projected != null || observedPose != null || sample != null || layer.fallback.isRunning(now)
            if (layer.pose == pose) return running
            val tx = SurfaceControl.Transaction()
            try {
                invoke(tx, "setPosition", effect, pose.x, pose.y)
                invoke(tx, "setMatrix", effect, pose.scale, 0f, 0f, pose.scale)
                invoke(tx, "setAlpha", effect, pose.alpha)
                invoke(tx, "apply")
            } finally { tx.close() }
            layer.pose = pose
            return running
        }
    }

    private fun update(window: Any, module: HyperSystemUiModule) {
        val attrs = field(window, "mAttrs")?.get(window) as? WindowManager.LayoutParams ?: return
        if (!isLauncher(attrs) || (call(window, "getDisplayId") as? Int ?: 0) != 0) {
            remove(window)
            return
        }
        val settings = readDockSettings(module.getRemotePreferences(REMOTE_PREFERENCE_GROUP))
        if (!settings.enabled) {
            remove(window)
            return
        }
        val parent = call(window, "getSurfaceControl") as? SurfaceControl ?: run {
            if (!missingSurfaceReported) {
                missingSurfaceReported = true
                android.util.Log.w("HyperChangerDock", "Launcher WindowState has no accessible SurfaceControl")
            }
            return
        }
        if (!parent.isValid) {
            remove(window)
            return
        }
        val frame = call(window, "getFrame") as? Rect ?: return
        val config = call(window, "getConfiguration") as? Configuration
        val density = (config?.densityDpi ?: 160) / 160f
        if (frame.width() <= 0 || frame.height() <= 0 || density <= 0f) return
        val margin = (settings.margin * density).toInt().coerceAtMost(frame.width() / 2)
        val bottom = (settings.bottom * density).toInt().coerceAtMost(frame.height())
        val width = frame.width() - margin * 2
        val height = (settings.height * density).toInt().coerceAtMost(frame.height() - bottom)
        if (width <= 0 || height <= 0) {
            remove(window)
            return
        }
        val y = frame.height() - bottom - height
        val radius = (settings.radius * density).coerceAtMost(minOf(width, height) / 2f)
        val dark = (config?.uiMode ?: 0) and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val layer = layers[window]?.takeIf { it.parent == parent && it.tint.isValid } ?: create(window, parent)
        bindMotion(window, layer)
        val service = field(window, "mWmService")?.get(window)
        layer.remoteContext = service?.let { field(it, "mContext")?.get(it) as? android.content.Context }
        val appearance = Appearance(settings, margin, y, width, height, radius, density, dark, frame.width(), frame.height())
        if (!settings.entryAnimation) layer.revealAt = 0L
        else if (layer.appearance == null && SystemClock.uptimeMillis() - lastUnlockAt < REVEAL_MS) {
            layer.revealAt = lastUnlockAt
        }
        if (settings.material == 2) requestGlass(window, layer, appearance)
        else if (layer.glassId != null) retireGlass(layer)
        if (layer.appearance == appearance) { drawMotion(layer); scheduleMotion(); return }
        val color = when (settings.material) {
            0 -> settings.solidColor
            1 -> settings.advancedColor
            else -> settings.glassColor
        }
        val alpha = when (settings.material) {
            0 -> Color.alpha(color) / 255f
            1 -> settings.advancedOpacity / 100f
            else -> settings.glassOpacity / 100f * (0.12f + settings.glassSoftLight / 250f)
        }
        val blur = when (settings.material) {
            0 -> 0
            1 -> (settings.advancedBlur * density).toInt()
            else -> if (layer.glassReady) 0 else (settings.glassBackdropBlur * density).toInt()
        }
        val tx = SurfaceControl.Transaction()
        try {
            layer.effect?.let { effect ->
                invoke(tx, "setLayer", effect, -1)
                val elapsed = (SystemClock.uptimeMillis() - layer.revealAt - 10L).coerceAtLeast(0L)
                val t = if (layer.revealAt > 0L) (elapsed.toFloat() / REVEAL_MS).coerceIn(0f, 1f) else 1f
                val remaining = 1f - t
                val lift = remaining * remaining * (1f - 2.25f * t)
                invoke(tx, "setPosition", effect, margin.toFloat(), y + 96f * density * lift)
                val alphaT = (elapsed / 280f).coerceIn(0f, 1f)
                val revealAlpha = if (layer.revealAt > 0L) alphaT * alphaT * (3f - 2f * alphaT) else 1f
                invoke(tx, "setAlpha", effect, revealAlpha)
                invoke(tx, "setWindowCrop", effect, width, height)
                invoke(tx, "setCornerRadius", effect, radius)
                if (backgroundBlurAvailable) {
                    runCatching { invoke(tx, "setBackgroundBlurRadius", effect, blur) }
                        .onFailure {
                            backgroundBlurAvailable = false
                            android.util.Log.w("HyperChangerDock", "Background blur unavailable", it)
                        }
                }
                invoke(tx, "show", effect)
            }
            invoke(tx, "setLayer", layer.tint, if (layer.effect == null) -1 else 0)
            invoke(tx, "setPosition", layer.tint,
                if (layer.effect == null) margin.toFloat() else 0f,
                if (layer.effect == null) y.toFloat() else 0f)
            invoke(tx, "setWindowCrop", layer.tint, width, height)
            invoke(tx, "setCornerRadius", layer.tint, radius)
            invoke(tx, "setColor", layer.tint,
                floatArrayOf(Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f))
            invoke(tx, "setAlpha", layer.tint, if (layer.glassReady && settings.material == 2) 0f else alpha.coerceIn(0f, 1f))
            invoke(tx, "show", layer.tint)
            invoke(tx, "setLayer", layer.highlight, 1)
            invoke(tx, "setPosition", layer.highlight, 0f, 0f)
            invoke(tx, "setWindowCrop", layer.highlight, width, (2 * density).toInt().coerceAtLeast(1))
            invoke(tx, "setColor", layer.highlight, floatArrayOf(1f, 1f, 1f))
            invoke(tx, "setAlpha", layer.highlight,
                if (settings.material == 1 && settings.advancedHighlight) 0.26f else 0f)
            invoke(tx, "show", layer.highlight)
            invoke(tx, "apply")
            layer.appearance = appearance
            layer.pose = null
            drawMotion(layer)
            scheduleMotion()
        } finally {
            tx.close()
        }
    }

    private fun create(window: Any, parent: SurfaceControl): Layer {
        remove(window)
        val effect = runCatching {
            val builder = SurfaceControl.Builder()
            invoke(builder, "setName", "HyperChanger Dock blur")
            invoke(builder, "setParent", parent)
            invoke(builder, "setEffectLayer")
            invoke(builder, "build") as SurfaceControl
        }.getOrNull()
        val builder = SurfaceControl.Builder()
        invoke(builder, "setName", "HyperChanger Dock tint")
        invoke(builder, "setParent", effect ?: parent)
        invoke(builder, "setColorLayer")
        val tint = invoke(builder, "build") as SurfaceControl
        val highlightBuilder = SurfaceControl.Builder()
        invoke(highlightBuilder, "setName", "HyperChanger Dock highlight")
        invoke(highlightBuilder, "setParent", effect ?: parent)
        invoke(highlightBuilder, "setColorLayer")
        val highlight = invoke(highlightBuilder, "build") as SurfaceControl
        return Layer(parent, effect, tint, highlight).also {
            synchronized(layers) { layers[window] = it }
        }
    }

    private fun remove(window: Any) {
        val layer = synchronized(layers) { layers.remove(window) }
        layer?.let {
            layer.closed = true
            retireGlass(layer)
            runCatching {
                val tx = SurfaceControl.Transaction()
                try {
                    invoke(tx, "remove", layer.highlight)
                    invoke(tx, "remove", layer.tint)
                    layer.effect?.let { invoke(tx, "remove", it) }
                    invoke(tx, "apply")
                } finally {
                    tx.close()
                }
            }
            layer.highlight.release()
            layer.tint.release()
            layer.effect?.release()
        }
    }

    private fun requestGlass(window: Any, layer: Layer, appearance: Appearance) {
        val settings = appearance.settings
        val key = "${appearance.width}/${appearance.height}/${appearance.radius}/${settings.glassColor}/" +
            "${settings.glassOpacity}/${settings.glassBackdropBlur}/${settings.glassBlur}/${settings.glassSoftLight}"
        if (layer.glassKey != key) {
            retireGlass(layer)
            layer.glassKey = key
        }
        if (layer.glassSurface != null || layer.pending ||
            android.os.SystemClock.uptimeMillis() - layer.lastFailure < 5_000L) return
        val service = field(window, "mWmService")?.get(window) ?: return
        val context = field(service, "mContext")?.get(service) as? android.content.Context ?: return
        layer.remoteContext = context
        val id = "${System.identityHashCode(window)}-${++nextGlassId}"
        layer.glassId = id
        layer.pending = true
        val args = Bundle().apply {
            putInt("width", appearance.width)
            putInt("height", appearance.height)
            putFloat("radius", appearance.radius)
            putInt("color", settings.glassColor)
            putInt("opacity", settings.glassOpacity)
            putInt("backdropBlur", settings.glassBackdropBlur)
            putInt("glassBlur", settings.glassBlur)
            putInt("softLight", settings.glassSoftLight)
        }
        worker.post {
            val result = runCatching {
                context.contentResolver.call(Uri.parse("content://$DOCK_MATERIAL_AUTHORITY"), "create", id, args)
            }.getOrNull()
            val parcel = result?.getParcelable("surface", SurfaceControlViewHost.SurfacePackage::class.java)
            val surface = runCatching { parcel?.let { invoke(it, "getSurfaceControl") as? SurfaceControl } }.getOrNull()
            synchronized(layer) {
                if (layer.closed || layer.glassId != id || surface == null || !surface.isValid) {
                    parcel?.release()
                    if (layer.glassId == id) {
                        layer.pending = false
                        layer.lastFailure = android.os.SystemClock.uptimeMillis()
                        android.util.Log.w("HyperChangerDock", "Glass host unavailable: ${result?.getString("error")}")
                    }
                    releaseRemote(context, id)
                    return@post
                }
                runCatching {
                    val tx = SurfaceControl.Transaction()
                    try {
                        invoke(tx, "reparent", surface, layer.effect ?: layer.parent)
                        invoke(tx, "setLayer", surface, 2)
                        invoke(tx, "setPosition", surface, 0f, 0f)
                        invoke(tx, "setWindowCrop", surface, appearance.width, appearance.height)
                        invoke(tx, "setAlpha", surface, 1f / 255f)
                        invoke(tx, "show", surface)
                        invoke(tx, "apply")
                    } finally { tx.close() }
                    layer.glassPackage = parcel
                    layer.glassSurface = surface
                    android.util.Log.i("HyperChangerDock", "Dock glass host attached; waiting for background texture")
                    probeGlass(layer, context, id, 0)
                }.onFailure {
                    parcel?.release()
                    layer.lastFailure = android.os.SystemClock.uptimeMillis()
                    android.util.Log.w("HyperChangerDock", "Glass attach failed", it)
                    releaseRemote(context, id)
                }
                layer.pending = false
            }
        }
    }

    private fun retireGlass(layer: Layer) {
        val id = layer.glassId ?: return
        layer.glassId = null
        layer.pending = false
        layer.glassKey = null
        layer.glassSurface?.let { surface ->
            runCatching {
                val tx = SurfaceControl.Transaction()
                try { invoke(tx, "reparent", surface, null); invoke(tx, "apply") }
                finally { tx.close() }
            }
        }
        layer.glassSurface = null
        layer.glassReady = false
        layer.glassPackage?.release()
        layer.glassPackage = null
        // The remote host retains its own lease until this release reaches its process.
        layer.remoteContext?.let { context -> worker.post { releaseRemote(context, id) } }
    }

    private fun releaseRemote(context: android.content.Context, id: String) {
        runCatching {
            context.contentResolver.call(Uri.parse("content://$DOCK_MATERIAL_AUTHORITY"), "release", id, null)
        }
    }

    private fun probeGlass(layer: Layer, context: android.content.Context, id: String, attempt: Int) {
        worker.postDelayed({
            if (layer.closed || layer.glassId != id) return@postDelayed
            val ready = runCatching {
                context.contentResolver.call(Uri.parse("content://$DOCK_MATERIAL_AUTHORITY"), "probe", id, null)
                    ?.getBoolean("ready", false) == true
            }.getOrDefault(false)
            if (!ready) {
                if (attempt < 20) probeGlass(layer, context, id, attempt + 1)
                else android.util.Log.w("HyperChangerDock", "Glass background texture never became ready")
                return@postDelayed
            }
            synchronized(layer) {
                val surface = layer.glassSurface ?: return@synchronized
                if (layer.glassId != id || !surface.isValid) return@synchronized
                runCatching {
                    val tx = SurfaceControl.Transaction()
                    try {
                        invoke(tx, "setAlpha", surface, 1f)
                        invoke(tx, "setAlpha", layer.tint, 0f)
                        layer.effect?.let { invoke(tx, "setBackgroundBlurRadius", it, 0) }
                        invoke(tx, "apply")
                    } finally { tx.close() }
                    layer.glassReady = true
                    android.util.Log.i("HyperChangerDock", "Native Dock glass ready")
                }.onFailure { android.util.Log.w("HyperChangerDock", "Glass activation failed", it) }
            }
        }, 100L)
    }

    private fun isLauncher(attrs: WindowManager.LayoutParams): Boolean =
        attrs.packageName == "com.miui.home" && attrs.type in 1..2 &&
            attrs.title?.toString() in launcherTitles

    private fun field(target: Any, name: String) = generateSequence(target.javaClass) { it.superclass }
        .mapNotNull { runCatching { it.getDeclaredField(name).apply { isAccessible = true } }.getOrNull() }
        .firstOrNull()

    private fun call(target: Any, name: String): Any? = runCatching {
        val method = generateSequence(target.javaClass) { it.superclass }
            .mapNotNull { type ->
                type.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
            }.firstOrNull() ?: target.javaClass.methods.firstOrNull {
                it.name == name && it.parameterCount == 0
            } ?: return@runCatching null
        method.isAccessible = true
        method.invoke(target)
    }.getOrNull()

    private fun invoke(target: Any, name: String, vararg args: Any?): Any? {
        val methods = target.javaClass.methods.asSequence() + target.javaClass.declaredMethods.asSequence()
        val method = methods.firstOrNull { it.name == name && accepts(it, args) } ?: error("$name unavailable")
        method.isAccessible = true
        return method.invoke(target, *args)
    }

    private fun accepts(method: Method, args: Array<out Any?>): Boolean =
        method.parameterTypes.size == args.size && method.parameterTypes.indices.all { index ->
            val arg = args[index] ?: return@all !method.parameterTypes[index].isPrimitive
            val type = method.parameterTypes[index]
            type.isAssignableFrom(arg.javaClass) || when (type) {
                java.lang.Integer.TYPE -> arg is Int
                java.lang.Float.TYPE -> arg is Float
                java.lang.Boolean.TYPE -> arg is Boolean
                else -> false
            }
        }
}
