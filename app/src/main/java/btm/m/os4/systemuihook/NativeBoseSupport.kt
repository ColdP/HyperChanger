// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

internal const val NATIVE_BOSE_PREFERENCES = "native_bose_device_support"
private const val KEY_DEVICES = "devices"
private val DEVICE_LIST_URLS = listOf(
    "https://coldp.github.io/hyperchanger-updateinfo/native_bose_tuning_devices.txt",
    "https://gitee.com/btm_m/HyperChanger/raw/main/native_bose_tuning_devices.txt",
)
private const val MAX_LIST_LENGTH = 128 * 1024

// Snapshot of the maintained list on 2026-11-01, used only before a successful download.
private val BUNDLED_NATIVE_BOSE_DEVICES = setOf(
    // REDMI K90 Series
    "annibale", "myron", "prague", "warsaw",
    // REDMI K100 Series
    "yanan", "athens", "songyuan",
)

private fun nativeBoseDevices(preferences: SharedPreferences): Set<String> =
    if (preferences.contains(KEY_DEVICES)) {
        // A successfully downloaded empty list must also override the bundled snapshot.
        preferences.getStringSet(KEY_DEVICES, emptySet()).orEmpty()
    } else BUNDLED_NATIVE_BOSE_DEVICES

// ro.product.device is immutable within a process. Do not use the configurable device profile.
private val nativeBoseBrandEligible by lazy {
    OsCompatibility.systemProperty("ro.product.brand").equals("redmi", ignoreCase = true)
}
private val nativeBoseDeviceCode by lazy { OsCompatibility.systemProperty("ro.product.device") }

internal fun parseNativeBoseDevices(text: String): Set<String> {
    require(text.length <= MAX_LIST_LENGTH) { "Native BOSE device list is too large" }
    return text.removePrefix("\uFEFF").lineSequence().map { it.substringBefore('#').trim() }
        .filter(String::isNotEmpty).onEach {
            require(it.matches(Regex("[A-Za-z0-9_.-]+"))) { "Invalid native BOSE device code" }
        }.toSet()
}

/** Try Gitee only when GitHub's network, HTTP, or text parsing fails. */
internal fun loadNativeBoseDevices(download: (String) -> String): Set<String> {
    var failure: Exception? = null
    for (url in DEVICE_LIST_URLS) {
        try {
            return parseNativeBoseDevices(download(url))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            failure?.let(error::addSuppressed)
            failure = error
        }
    }
    throw checkNotNull(failure)
}

internal fun isNativeBoseDevice(preferences: SharedPreferences): Boolean =
    nativeBoseBrandEligible && nativeBoseDeviceCode.isNotEmpty() &&
        nativeBoseDeviceCode in nativeBoseDevices(preferences)

internal fun SoundEffectPreferences.supportsBose(nativeSupported: Boolean): Boolean =
    nativeSupported || boseEnabled

/** Environment metadata with an offline snapshot, separate from user settings and presets. */
internal class NativeBoseSupportStore(
    context: Context,
    private val download: (String) -> String = ::downloadNativeBoseText,
) {
    private val local = context.getSharedPreferences(NATIVE_BOSE_PREFERENCES, Context.MODE_PRIVATE)
    private val supportState = MutableStateFlow(isNativeBoseDevice(local))
    val supported = supportState.asStateFlow()
    private val refreshMutex = Mutex()
    private var boundService: XposedService? = null

    @Synchronized
    fun syncRemote(service: XposedService) {
        boundService = service
        val editor = service.getRemotePreferences(NATIVE_BOSE_PREFERENCES).edit()
        if (local.contains(KEY_DEVICES)) {
            editor.putStringSet(KEY_DEVICES, nativeBoseDevices(local).toSet())
        } else {
            // Do not turn the bundled snapshot into an authoritative downloaded cache.
            editor.remove(KEY_DEVICES)
        }
        editor.apply()
    }

    @Synchronized
    fun onServiceDied(service: XposedService) {
        if (boundService === service) boundService = null
    }

    @Synchronized
    private fun save(devices: Set<String>) {
        local.edit().putStringSet(KEY_DEVICES, devices).apply()
        supportState.value = isNativeBoseDevice(local)
        boundService?.let { service ->
            runCatching { syncRemote(service) }
                .onFailure { ModuleLog.w("NativeBose", "Could not sync native BOSE device list", it) }
        }
    }

    /** REDMI always refreshes on foreground entry; other brands skip native BOSE detection. */
    suspend fun refresh() = refreshMutex.withLock {
        if (!nativeBoseBrandEligible) return@withLock
        val devices = try {
            withContext(Dispatchers.IO) { loadNativeBoseDevices(download) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ModuleLog.w("NativeBose", "Could not refresh native BOSE device list; using cached or bundled result", error)
            return@withLock
        }
        save(devices)
    }

}

private fun downloadNativeBoseText(url: String): String {
    // A unique query also avoids a fresh local request receiving an old CDN cache entry.
    val connection = URL("$url?refresh=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.useCaches = false
        connection.setRequestProperty("Cache-Control", "no-cache, no-store")
        connection.setRequestProperty("Pragma", "no-cache")
        connection.setRequestProperty("Accept", "text/plain")
        connection.setRequestProperty("User-Agent", "HyperChanger")
        check(connection.responseCode == HttpURLConnection.HTTP_OK) { "Native BOSE list HTTP ${connection.responseCode}" }
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            val result = StringBuilder()
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(result.length + count <= MAX_LIST_LENGTH) { "Native BOSE device list is too large" }
                result.append(buffer, 0, count)
            }
            result.toString()
        }
    } finally {
        connection.disconnect()
    }
}
