// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.os.ParcelFileDescriptor
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Method
import java.util.function.Supplier

/** Reuse 2090 native catalog, menu construction, sound decoder and playback. */
internal class CameraShutterHooks(private val module: XposedModule, private val prefs: Supplier<SharedPreferences?>,
    private val log: (String) -> Unit) {
    private val installed = mutableSetOf<Method>()
    private val loadingSound = ThreadLocal<Int?>()
    private val catalogLock = Any()
    private var catalog: List<CameraShutterCatalogEntry>? = null
    private val titles = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private fun enabled() = prefs.get()?.getBoolean(CameraModuleConfig.KEY_MASTER_ENABLED, true) != false
    private fun soundsEnabled() = enabled()
    private fun entries(): List<CameraShutterCatalogEntry> = synchronized(catalogLock) {
        catalog ?: ParcelFileDescriptor.AutoCloseInputStream(module.openRemoteFile(CAMERA_SHUTTER_REMOTE_INDEX)).use {
            decodeCameraShutterCatalog(CameraShutterAudio.read(it))
        }.also { catalog = it }
    }
    private fun context(): Context? = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context

    fun install(loader: ClassLoader) {
        if (!enabled()) return
        runCatching { installCatalog(loader) }.onFailure { log("Native shutter catalog hook unavailable: $it") }
        runCatching {
            val sound = Class.forName("E1.m4", false, loader)
            val decode = sound.getDeclaredMethod("j", InputStream::class.java)
            register(sound.getDeclaredMethod("g", Int::class.javaPrimitiveType), "shutter-sound-scope") { chain ->
                val previous = loadingSound.get(); loadingSound.set(chain.getArg(0) as Int)
                try { chain.proceed() } finally { if (previous == null) loadingSound.remove() else loadingSound.set(previous) }
            }
            register(sound.getDeclaredMethod("e", String::class.java), "shutter-sound-asset") { chain ->
                val path = chain.getArg(0) as String
                val id = Regex("^sounds/hc_([a-f0-9]{64})/").find(path)?.groupValues?.get(1)
                if (id == null) chain.proceed()
                else {
                    val replacement = runCatching {
                        if (!soundsEnabled() || loadingSound.get() != 0) null else {
                            val bytes = ParcelFileDescriptor.AutoCloseInputStream(module.openRemoteFile(shutterRemoteFile(id))).use(CameraShutterAudio::read)
                            require(shutterHash(bytes) == id); CameraShutterAudio.duration(bytes)
                            ByteArrayInputStream(bytes).use { decode.invoke(null, it) }
                        }
                    }.onFailure { log("Custom shutter sound failed, keeping native sound: $it") }.getOrNull()
                    // Imports contain a single shot only. Burst/failure uses the
                    // native default's corresponding asset, never a missing hc path.
                    replacement ?: run {
                        val choices = Class.forName("f2.c", false, loader)
                        val fallback = choices.getMethod("c").invoke(null) as Int
                        val list = choices.getMethod("b").invoke(null) as List<*>
                        val key = choices.getField("b").get(list[fallback]) as String
                        chain.proceed(arrayOf(path.replaceFirst("sounds/hc_$id/", "sounds/$key/")))
                    }
                }
            }
        }.onFailure { log("Native shutter sound hook unavailable: $it") }
    }


    private fun installCatalog(loader: ClassLoader) {
        val choices = Class.forName("f2.c", false, loader)
        val construct = choices.getConstructor(Int::class.javaPrimitiveType, String::class.java)
        val keyField = choices.getField("b")
        val listMethod = choices.getMethod("b")
        for (name in listOf("getText", "getString")) {
            register(Resources::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType), "shutter-sound-title-$name") { chain ->
                titles[chain.getArg(0) as Int] ?: chain.proceed()
            }
        }
        register(listMethod, "shutter-sound-catalog") { chain ->
            val result = chain.proceed()
            if (!soundsEnabled() || result !is List<*>) result else runCatching {
                ArrayList(result).apply {
                    entries().forEachIndexed { index, entry ->
                        // Synthetic string IDs, intercepted only in the camera process.
                        val titleId = 0x6c010000 + index
                        titles[titleId] = entry.name
                        if (none { keyField.get(it) == entry.key }) add(construct.newInstance(titleId, entry.key))
                    }
                }
            }.onFailure { log("Custom shutter catalog unavailable: $it") }.getOrDefault(result)
        }
    }

    private fun register(method: Method, id: String, callback: (XposedInterface.Chain) -> Any?) = synchronized(installed) {
        if (method !in installed) {
            module.hook(method).setId("camera2090:$id").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> if (enabled()) callback(chain) else chain.proceed() }
            installed.add(method); log("Installed $id: $method")
        }
    }
}
