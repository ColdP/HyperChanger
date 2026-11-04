// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook.privacy

import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import btm.m.os4.systemuihook.HookModuleLogger
import btm.m.os4.systemuihook.ModuleLog as Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.net.UnknownHostException
import java.nio.ByteBuffer

/**
 * Neutralises the collection and reporting of com.mobiletools.systemhelper and
 * com.miui.guardprovider without breaking their API contracts.
 *
 * Their classes are almost entirely obfuscated (guardprovider ships 6066 classes named a, b, c),
 * so the hooks sit on framework entry points instead of guessed members. No hook returns null and
 * none throws except a DNS lookup, which is the ordinary offline path: the capture pipeline starts
 * normally, but every screen surface is replaced with one that is drained and discarded, and
 * AudioRecord reads return zeros. The components therefore record and upload "air".
 */
internal class PreinstalledPrivacyHooks(private val module: XposedModule) {
    private var installed = false
    private var airReader: ImageReader? = null
    private var airSurface: Surface? = null

    fun install(param: PackageLoadedParam, preferences: SharedPreferences) {
        if (installed) return
        val settings = readPrivacyGuardSettings(preferences)
        if (!settings.enabled) return
        installed = true
        if (settings.blockReports) blockReportEndpoints()
        if (settings.blockInventory) blockInventoryCollection()
        if (settings.blockDeviceIds) blockDeviceIdentifiers()
        if (settings.blockCapture) makeCaptureRecordAir()
    }

    /** The three upload endpoints found in the shipped packages. */
    private fun blockReportEndpoints() {
        runCatching {
            val inet = Class.forName("java.net.InetAddress")
            listOf("getByName", "getAllByName").forEach { name ->
                val method = inet.getDeclaredMethod(name, String::class.java)
                module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:privacy:dns-$name")
                    .intercept { chain ->
                        val host = (chain.args.firstOrNull() as? String)?.lowercase().orEmpty()
                        if (BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }) {
                            throw UnknownHostException(host)
                        }
                        chain.proceed()
                    }
            }
            HookModuleLogger.log(module, Log.INFO, TAG, "Blocked preinstalled report endpoints")
        }.onFailure {
            HookModuleLogger.log(module, Log.WARN, TAG, "Could not block preinstalled report endpoints", it)
        }
    }

    /** Installed-application enumeration plus usage and traffic statistics. */
    private fun blockInventoryCollection() {
        runCatching {
            val emptyList = java.util.Collections.emptyList<Any?>()
            val emptyMap = java.util.Collections.emptyMap<String, Any?>()

            val pm = Class.forName("android.content.pm.PackageManager")
            listOf("getInstalledApplications", "getInstalledPackages", "getInstalledModules").forEach { name ->
                runCatching {
                    val method = pm.getMethod(name, Int::class.javaPrimitiveType!!)
                    module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("hyperchanger:privacy:$name")
                        .intercept { emptyList }
                }.onFailure {
                    HookModuleLogger.log(module, Log.WARN, TAG, "Skipped PackageManager.$name", it)
                }
            }

            val usm = Class.forName("android.app.usage.UsageStatsManager")
            runCatching {
                val query = usm.getMethod(
                    "queryUsageStats",
                    Int::class.javaPrimitiveType!!,
                    Long::class.javaPrimitiveType!!,
                    Long::class.javaPrimitiveType!!,
                )
                module.hook(query).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:privacy:queryUsageStats")
                    .intercept { emptyList }
            }
            runCatching {
                val aggregate = usm.getMethod(
                    "queryAndAggregateUsageStats",
                    Long::class.javaPrimitiveType!!,
                    Long::class.javaPrimitiveType!!,
                )
                module.hook(aggregate).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:privacy:queryAndAggregateUsageStats")
                    .intercept { emptyMap }
            }
            HookModuleLogger.log(module, Log.INFO, TAG, "Blocked preinstalled inventory collection")
        }.onFailure {
            HookModuleLogger.log(module, Log.WARN, TAG, "Could not block preinstalled inventory collection", it)
        }
    }

    /** IMEI, MEID, IMSI, ICCID and the SIM serial number. */
    private fun blockDeviceIdentifiers() {
        runCatching {
            val telephony = Class.forName("android.telephony.TelephonyManager")
            val noArg = listOf("getDeviceId", "getImei", "getMeid", "getSubscriberId", "getSimSerialNumber", "getIccId")
            var hooked = 0
            noArg.forEach { name ->
                runCatching {
                    val method = telephony.getMethod(name)
                    module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("hyperchanger:privacy:$name")
                        .intercept { "" }
                    hooked++
                }
                runCatching {
                    val method = telephony.getMethod(name, Int::class.javaPrimitiveType!!)
                    module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                        .setId("hyperchanger:privacy:$name:slot")
                        .intercept { "" }
                    hooked++
                }
            }
            HookModuleLogger.log(module, Log.INFO, TAG, "Blocked $hooked telephony identifier getters")
        }.onFailure {
            HookModuleLogger.log(module, Log.WARN, TAG, "Could not block telephony identifiers", it)
        }
    }

    /**
     * Keeps the capture pipeline alive but feeds it a discarded surface, so a recording session
     * completes with black frames and silent audio instead of failing.
     */
    private fun makeCaptureRecordAir() {
        runCatching {
            val surface = createAirSurface() ?: return
            redirectProjectionSurfaces(surface)
            redirectDisplaySurfaces(surface)
            silenceAudioRecord()
        }.onFailure {
            HookModuleLogger.log(module, Log.WARN, TAG, "Could not redirect the capture pipeline", it)
        }
    }

    /**
     * The reader is drained continuously: an undrained consumer would stall the virtual display,
     * so every frame is released immediately instead of being kept.
     */
    private fun createAirSurface(): Surface? {
        val existing = airSurface
        if (existing != null) return existing
        val reader = ImageReader.newInstance(AIR_WIDTH, AIR_HEIGHT, PixelFormat.RGBA_8888, 2)
        val thread = HandlerThread("hyperchanger-air").apply { start() }
        reader.setOnImageAvailableListener({ source ->
            runCatching {
                source.acquireLatestImage()?.close()
            }.onFailure { HookModuleLogger.log(module, Log.WARN, TAG, "Could not drain air frame", it) }
        }, Handler(thread.looper))
        airReader = reader
        airSurface = reader.surface
        return airSurface
    }

    /** MediaProjection.createVirtualDisplay receives the recorder surface as an argument. */
    private fun redirectProjectionSurfaces(air: Surface) {
        val projection = Class.forName("android.media.projection.MediaProjection")
        var hooked = 0
        runCatching {
            val method = projection.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Surface::class.java,
            )
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:privacy:createVirtualDisplay:6")
                .intercept { chain -> chain.proceedWith(chain.thisObject, replaceSurfaceArg(chain.args.toTypedArray(), 5, air)) }
            hooked++
        }
        runCatching {
            val method = projection.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Surface::class.java,
                Class.forName("android.hardware.display.VirtualDisplay\$Callback"),
                Handler::class.java,
            )
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:privacy:createVirtualDisplay:8")
                .intercept { chain -> chain.proceedWith(chain.thisObject, replaceSurfaceArg(chain.args.toTypedArray(), 5, air)) }
            hooked++
        }
        HookModuleLogger.log(module, Log.INFO, TAG, "Redirected $hooked MediaProjection surfaces")
    }

    /** Direct SurfaceControl capture binds its own surface to the virtual display. */
    private fun redirectDisplaySurfaces(air: Surface) {
        val transaction = Class.forName("android.view.SurfaceControl\$Transaction")
        var hooked = 0
        runCatching {
            val method = transaction.getDeclaredMethod("setDisplaySurface", IBinder::class.java, Surface::class.java)
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:privacy:setDisplaySurface")
                .intercept { chain -> chain.proceedWith(chain.thisObject, replaceSurfaceArg(chain.args.toTypedArray(), 1, air)) }
            hooked++
        }
        runCatching {
            val method = transaction.getDeclaredMethod(
                "setDisplaySurface",
                Class.forName("android.view.SurfaceControl"),
                Surface::class.java,
            )
            module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                .setId("hyperchanger:privacy:setDisplaySurface:sc")
                .intercept { chain -> chain.proceedWith(chain.thisObject, replaceSurfaceArg(chain.args.toTypedArray(), 1, air)) }
            hooked++
        }
        HookModuleLogger.log(module, Log.INFO, TAG, "Redirected $hooked SurfaceControl display surfaces")
    }

    /** Leaves a null surface alone, because that is how a capture session is torn down. */
    private fun replaceSurfaceArg(args: Array<Any?>, index: Int, air: Surface): Array<Any?> {
        if (index >= args.size || args[index] == null) return args
        val copy = args.copyOf()
        copy[index] = air
        return copy
    }

    private fun silenceAudioRecord() {
        runCatching {
            val record = Class.forName("android.media.AudioRecord")
            var hooked = 0
            runCatching {
                val method = record.getMethod(
                    "read",
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                )
                module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:privacy:audioRead:byte")
                    .intercept { chain ->
                        val buffer = chain.getArg(0) as? ByteArray
                        val offset = chain.getArg(1) as? Int ?: 0
                        val size = chain.getArg(2) as? Int ?: 0
                        if (buffer != null && size > 0 && offset >= 0 && offset + size <= buffer.size) {
                            java.util.Arrays.fill(buffer, offset, offset + size, 0.toByte())
                            size
                        } else {
                            chain.proceed()
                        }
                    }
                hooked++
            }
            runCatching {
                val method = record.getMethod(
                    "read",
                    ShortArray::class.java,
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                )
                module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:privacy:audioRead:short")
                    .intercept { chain ->
                        val buffer = chain.getArg(0) as? ShortArray
                        val offset = chain.getArg(1) as? Int ?: 0
                        val size = chain.getArg(2) as? Int ?: 0
                        if (buffer != null && size > 0 && offset >= 0 && offset + size <= buffer.size) {
                            java.util.Arrays.fill(buffer, offset, offset + size, 0.toShort())
                            size
                        } else {
                            chain.proceed()
                        }
                    }
                hooked++
            }
            runCatching {
                val method = record.getMethod("read", ByteBuffer::class.java, Int::class.javaPrimitiveType!!)
                module.hook(method).setExceptionMode(ExceptionMode.PROTECTIVE)
                    .setId("hyperchanger:privacy:audioRead:buffer")
                    .intercept { chain ->
                        val buffer = chain.getArg(0) as? ByteBuffer
                        val size = chain.getArg(1) as? Int ?: 0
                        if (buffer != null && size > 0) {
                            val limit = minOf(size, buffer.remaining())
                            for (index in 0 until limit) buffer.put(index, 0.toByte())
                            limit
                        } else {
                            chain.proceed()
                        }
                    }
                hooked++
            }
            HookModuleLogger.log(module, Log.INFO, TAG, "Silenced $hooked AudioRecord read paths")
        }.onFailure {
            HookModuleLogger.log(module, Log.WARN, TAG, "Could not silence AudioRecord", it)
        }
    }

    private companion object {
        const val TAG = "HyperChangerPrivacy"
        const val AIR_WIDTH = 720
        const val AIR_HEIGHT = 1280

        /** Endpoints observed in com.mobiletools.systemhelper and com.miui.guardprovider. */
        val BLOCKED_HOSTS = listOf(
            "dm.wo.com.cn",
            "flash.sec.miui.com",
            "fraud-detection.engine.miui.com",
        )
    }
}
