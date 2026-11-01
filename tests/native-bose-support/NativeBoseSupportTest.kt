package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemProperties
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.IOException
import java.lang.reflect.Proxy

private class MemoryPreferences {
    val values = mutableMapOf<String, Any?>()
    val preferences: SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getAll" -> values.toMap()
            "contains" -> values.containsKey(args!![0])
            "getBoolean", "getInt", "getLong", "getFloat", "getString", "getStringSet" -> values[args!![0]] ?: args[1]
            "edit" -> editor()
            else -> error("Unexpected preference call: ${method.name}")
        }
    } as SharedPreferences

    private fun editor(): SharedPreferences.Editor {
        val changes = mutableMapOf<String, Any?>()
        var clear = false
        return Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "putBoolean", "putInt", "putLong", "putFloat", "putString", "putStringSet" -> {
                    changes[args!![0] as String] = args[1]; proxy
                }
                "remove" -> { changes[args!![0] as String] = null; proxy }
                "clear" -> { clear = true; proxy }
                "apply", "commit" -> {
                    if (clear) values.clear()
                    changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                    if (method.name == "commit") true else null
                }
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
    }
}

private class MemoryContext : Context() {
    private val groups = mutableMapOf<String, MemoryPreferences>()
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        groups.getOrPut(name, ::MemoryPreferences).preferences
}

fun main(args: Array<String>) = runBlocking {
    if (args.firstOrNull() == "--brand-filter") {
        val local = MemoryContext()
        val remote = MemoryContext()
        var requests = 0
        val store = NativeBoseSupportStore(local) { requests++; "annibale" }
        check(!store.supported.value)
        check(!isNativeBoseDevice(remote.getSharedPreferences(NATIVE_BOSE_PREFERENCES, 0)))
        local.getSharedPreferences(NATIVE_BOSE_PREFERENCES, 0).edit()
            .putStringSet("devices", setOf("annibale")).apply()
        store.syncRemote(XposedService(remote))
        store.refresh()
        check(!store.supported.value && requests == 0 && SystemProperties.deviceReads == 0)
        check(!isNativeBoseDevice(remote.getSharedPreferences(NATIVE_BOSE_PREFERENCES, 0)))
        check(SoundEffectPreferences(boseEnabled = true).supportsBose(store.supported.value))
        println("PASS non-REDMI brand '${System.getProperty("nativebose.test.brand")}' skips bundled/cached detection, device lookup and networking; manual BOSE remains available")
        return@runBlocking
    }
    var count = 0
    fun scenario(name: String, test: () -> Unit) { test(); count++; println("PASS $name") }
    scenario("BOM, CRLF, comments, blank lines, inline comments and duplicates") {
        check(parseNativeBoseDevices("\uFEFF# Series\r\n annibale \r\n\r\ntest-other # note\nannibale\n") ==
            setOf("annibale", "test-other"))
    }
    scenario("malformed responses and oversized files are rejected") {
        check(runCatching { parseNativeBoseDevices("<!doctype html>") }.isFailure)
        check(runCatching { parseNativeBoseDevices("a".repeat(128 * 1024 + 1)) }.isFailure)
    }
    scenario("GitHub success does not contact Gitee") {
        val calls = mutableListOf<String>()
        check(loadNativeBoseDevices { calls += it; "annibale" } == setOf("annibale"))
        check(calls.size == 1 && calls.single().startsWith("https://coldp.github.io/"))
    }
    scenario("GitHub network failure falls back to the exact Gitee main URL") {
        val calls = mutableListOf<String>()
        check(loadNativeBoseDevices {
            calls += it
            if (calls.size == 1) throw IOException("offline")
            "annibale"
        } == setOf("annibale"))
        check(calls.last() == "https://gitee.com/btm_m/HyperChanger/raw/main/native_bose_tuning_devices.txt")
    }
    scenario("invalid primary response falls back; valid empty list does not") {
        var calls = 0
        check(loadNativeBoseDevices { if (++calls == 1) "<html>error</html>" else "test-other" } == setOf("test-other"))
        check(calls == 2)
        calls = 0
        check(loadNativeBoseDevices { calls++; "# no devices\n" }.isEmpty() && calls == 1)
    }
    scenario("both failures preserve their causes") {
        var calls = 0
        val error = runCatching { loadNativeBoseDevices { calls++; throw IOException("failure $calls") } }.exceptionOrNull()!!
        check(calls == 2 && error.suppressed.single().message == "failure 1")
    }
    scenario("exact ro.product.device matching and bundled fallback without a cache") {
        val prefs = MemoryPreferences().preferences
        check(isNativeBoseDevice(prefs))
        prefs.edit().putStringSet("devices", setOf("annibale-pro", "ANNIBALE")).apply()
        check(!isNativeBoseDevice(prefs))
        prefs.edit().putStringSet("devices", setOf("annibale")).apply()
        check(isNativeBoseDevice(prefs) && SystemProperties.lastKey == "ro.product.device")
    }
    scenario("native capability and manual switch share the supported logo path") {
        for (native in listOf(false, true)) for (manual in listOf(false, true)) {
            check(SoundEffectPreferences(boseEnabled = manual).supportsBose(native) == (native || manual))
        }
    }

    val freshLocal = MemoryContext()
    val freshRemote = MemoryContext()
    var offlineRequests = 0
    val offlineStore = NativeBoseSupportStore(freshLocal) { offlineRequests++; throw IOException("offline") }
    scenario("first offline launch detects a bundled device before networking or service binding") {
        check(offlineStore.supported.value)
        check(isNativeBoseDevice(freshRemote.getSharedPreferences(NATIVE_BOSE_PREFERENCES, 0)))
    }
    offlineStore.syncRemote(XposedService(freshRemote))
    offlineStore.refresh()
    scenario("both channels fail without a cache: UI and hook retain bundled detection") {
        check(offlineRequests == 2 && offlineStore.supported.value)
        check(isNativeBoseDevice(freshRemote.getSharedPreferences(NATIVE_BOSE_PREFERENCES, 0)))
        check(!freshLocal.getSharedPreferences(NATIVE_BOSE_PREFERENCES, 0).contains("devices"))
        check(!freshRemote.getSharedPreferences(NATIVE_BOSE_PREFERENCES, 0).contains("devices"))
    }
    val emptyStore = NativeBoseSupportStore(freshLocal) { "# supported devices removed\n" }
    emptyStore.syncRemote(XposedService(freshRemote))
    emptyStore.refresh()
    scenario("a successful empty online list overrides the built-in snapshot locally and remotely") {
        check(!emptyStore.supported.value)
        check(!isNativeBoseDevice(freshRemote.getSharedPreferences(NATIVE_BOSE_PREFERENCES, 0)))
    }
    val emptyCacheStore = NativeBoseSupportStore(freshLocal) { throw IOException("offline") }
    emptyCacheStore.refresh()
    scenario("offline restart preserves an empty downloaded list instead of restoring bundled devices") {
        check(!emptyCacheStore.supported.value)
    }

    val local = MemoryContext()
    val remote = MemoryContext()
    val service = XposedService(remote)
    var response = "annibale"
    var offline = false
    var requests = 0
    val store = NativeBoseSupportStore(local) {
        requests++
        if (offline) throw IOException("offline")
        response
    }
    check(store.supported.value)
    store.syncRemote(service)
    store.refresh()
    scenario("service binds before download: result reaches both local UI and hook preferences") {
        check(store.supported.value)
        check(isNativeBoseDevice(remote.getSharedPreferences(NATIVE_BOSE_PREFERENCES, 0)))
    }
    store.refresh()
    scenario("every startup refresh downloads again despite a successful cache") { check(requests == 2) }
    offline = true
    store.refresh()
    scenario("dual-channel failure retains the last successful local and remote list") {
        check(requests == 4 && store.supported.value)
        check(isNativeBoseDevice(remote.getSharedPreferences(NATIVE_BOSE_PREFERENCES, 0)))
        check(NativeBoseSupportStore(local) { error("not requested yet") }.supported.value)
    }
    store.onServiceDied(service)
    offline = false
    response = "test-other"
    store.refresh()
    scenario("a successful updated list can remove native support") {
        check(!store.supported.value)
        // The disconnected service is not written until it binds again.
        check(isNativeBoseDevice(remote.getSharedPreferences(NATIVE_BOSE_PREFERENCES, 0)))
    }
    store.syncRemote(service)
    scenario("download completes before service binds: the current list is synchronized") {
        check(!isNativeBoseDevice(remote.getSharedPreferences(NATIVE_BOSE_PREFERENCES, 0)))
    }

    val sound = local.getSharedPreferences(REMOTE_PREFERENCE_GROUP, 0)
    sound.edit().putBoolean("audio_bose_enabled", true).putInt("audio_logo_mode", AUDIO_LOGO_CUSTOM)
        .putString("raster_wallpaper_uris", "device-local-image").apply()
    val payload = ModulePresetCodec.exportJson(local)
    scenario("module preset exports BOSE switch/logo but excludes device detection and image URIs") {
        val groups = JSONObject(payload).getJSONObject("groups")
        val audio = groups.getJSONObject(REMOTE_PREFERENCE_GROUP)
        check(audio.getJSONObject("audio_bose_enabled").getBoolean("value"))
        check(audio.getJSONObject("audio_logo_mode").getInt("value") == AUDIO_LOGO_CUSTOM)
        check(!audio.has("raster_wallpaper_uris") && !groups.has(NATIVE_BOSE_PREFERENCES))
    }
    response = "annibale"
    store.refresh()
    sound.edit().putBoolean("audio_bose_enabled", false).putInt("audio_logo_mode", AUDIO_LOGO_XIAOMI).apply()
    ModulePresetCodec.import(local, service, payload)
    scenario("preset round trip restores audio settings and cannot overwrite native detection") {
        check(readSoundEffectPreferences(sound) == SoundEffectPreferences(true, AUDIO_LOGO_CUSTOM))
        check(readSoundEffectPreferences(remote.getSharedPreferences(REMOTE_PREFERENCE_GROUP, 0)) ==
            SoundEffectPreferences(true, AUDIO_LOGO_CUSTOM))
        check(isNativeBoseDevice(local.getSharedPreferences(NATIVE_BOSE_PREFERENCES, 0)))
        check(isNativeBoseDevice(remote.getSharedPreferences(NATIVE_BOSE_PREFERENCES, 0)))
        check(sound.getString("raster_wallpaper_uris", "") == "device-local-image")
    }
    println("$count native BOSE scenarios passed")
}
