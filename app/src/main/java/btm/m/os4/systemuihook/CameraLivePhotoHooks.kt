// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.util.Size
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.io.File

/** Compatibility for a ported MasterLive profile; native devices keep their geometry. */
internal class CameraLivePhotoHooks(
    private val module: XposedModule,
    private val enabled: () -> Boolean,
    private val log: (String) -> Unit,
) {
    private val installed = mutableSetOf<Method>()

    fun install(loader: ClassLoader) {
        installRedCarpetCrashMarker(loader)
        runCatching {
            val arguments = Class.forName("m6.o\$a", false, loader)
            val mode = arguments.getField("d")
            val size = Class.forName("m6.o", false, loader)
                .getDeclaredMethod("c", Size::class.java, arguments)
            register(size, "live-photo-stream-size") { chain ->
                val result = chain.proceed()
                if (!enabled() || result !is Size) result else runCatching {
                    val input = chain.getArg(0) as? Size
                    val state = chain.getArg(1)
                    if (mode.getInt(state) != 231 || input == null || result.width < 1800) result
                    else {
                        // The MasterLive reader is opened at 1920x1440 while
                        // the post processor writes the normal-live 1728x1296
                        // crop. Follow the verified port rule only for this
                        // large mode-231 result; smaller native sizes stay put.
                        val ratio = .9f
                        cameraLivePhotoFallbackSize(input, result, ratio).also {
                            if (it != result) log("Live photo stream $result -> $it (mode=231 crop=$ratio)")
                        }
                    }
                }.onFailure { log("Live photo stream fallback unavailable: $it") }.getOrDefault(result)
            }
        }.onFailure { log("Live photo size hook unavailable: $it") }
    }

    private fun installRedCarpetCrashMarker(loader: ClassLoader) = runCatching {
        val component = Class.forName("v2.g0", false, loader)
        val setter = component.getDeclaredMethod("setComponentValue", Int::class.javaPrimitiveType, String::class.java)
        register(setter, "master-live-red-carpet-marker") { chain ->
            val value = chain.getArg(1) as? String
            val marker = File("/data/user/0/com.android.camera/files/hyperchanger-masterlive-redcarpet.pending")
            if (enabled() && value == "1") {
                runCatching {
                    marker.parentFile?.mkdirs(); marker.writeText("${System.currentTimeMillis()}\n")
                }.onFailure { log("MasterLive crash marker unavailable: $it") }
            } else if (value != "1") runCatching { marker.delete() }
            chain.proceed()
        }
        val masterLive = Class.forName("com.android.camera.features.mode.masterlive.MasterLiveModule", false, loader)
        register(masterLive.getDeclaredMethod("onActionStop"), "master-live-red-carpet-success") { chain ->
            val result = chain.proceed()
            runCatching { File("/data/user/0/com.android.camera/files/hyperchanger-masterlive-redcarpet.pending").delete() }
            result
        }
        register(masterLive.getDeclaredMethod("onInactive"), "master-live-red-carpet-close") { chain ->
            val result = chain.proceed()
            runCatching { File("/data/user/0/com.android.camera/files/hyperchanger-masterlive-redcarpet.pending").delete() }
            result
        }
    }.onFailure { log("MasterLive red-carpet marker unavailable: $it") }

    private fun register(method: Method, id: String, callback: (XposedInterface.Chain) -> Any?) = synchronized(installed) {
        if (method !in installed) {
            module.hook(method).setId("camera2090:$id")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> if (enabled()) callback(chain) else chain.proceed() }
            installed.add(method)
            log("Installed $id: $method")
        }
    }
}

internal fun cameraLivePhotoFallbackSize(input: Size, native: Size, ratio: Float): Size {
    if (!ratio.isFinite() || ratio <= 0f || ratio >= 1f) return native
    // Match m6.o.c's integer conversion and even chroma-plane alignment.
    val width = (input.width * ratio).toInt() / 2 * 2
    val height = (input.height * ratio).toInt() / 2 * 2
    return if (width >= 2 && height >= 2) Size(width, height) else native
}

internal fun cameraLivePhotoZoomDuration(native: Float): Float =
    if (native.isFinite() && native > 1.1f) 1.1f else native
