// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.service.XposedService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import android.util.Log

internal const val KEY_HOME_RECENTS_CLEAR_MODE = "home_recents_clear_mode"

internal enum class HomeRecentsClearMode(val value: Int) {
    NONE(0),
    HIDE_KEEP_FUNCTION(1),
    HIDE_NO_FUNCTION(2),
    ;

    companion object {
        fun fromValue(value: Int): HomeRecentsClearMode = entries.firstOrNull { it.value == value } ?: NONE
    }
}

internal data class HomeRecentsSettings(
    val clearMode: HomeRecentsClearMode = HomeRecentsClearMode.NONE,
)

internal class HomeRecentsSettingsStore(private val context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)

    var settings: HomeRecentsSettings = read(local)
        private set

    fun reload() {
        settings = read(local)
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = if (remote.contains(KEY_HOME_RECENTS_CLEAR_MODE)) read(remote) else settings
        write(local, settings)
        write(remote, settings)
        stageHomeRecentsNativeConfig(context, settings)
    }

    fun update(service: XposedService?, transform: (HomeRecentsSettings) -> HomeRecentsSettings): Boolean {
        settings = transform(settings)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
        return stageHomeRecentsNativeConfig(context, settings)
    }

    private fun read(prefs: SharedPreferences) = readHomeRecentsSettings(prefs)

    private fun write(prefs: SharedPreferences, value: HomeRecentsSettings) {
        prefs.edit().putInt(KEY_HOME_RECENTS_CLEAR_MODE, value.clearMode.value).apply()
    }
}

internal fun readHomeRecentsSettings(prefs: SharedPreferences) = HomeRecentsSettings(
        clearMode = HomeRecentsClearMode.fromValue(
            prefs.getInt(KEY_HOME_RECENTS_CLEAR_MODE, HomeRecentsClearMode.NONE.value),
        ),
    )

/**
 * Writes the native launcher configuration consumed by the Rust launcher patcher.
 * Feature 18 hides the clear button while retaining its action; feature
 * 9 removes both the button and its action.
 */
internal fun syncHomeRecentsNativeConfig(param: PackageLoadedParam, settings: HomeRecentsSettings) {
    val dataDir = param.applicationInfo.dataDir ?: return
    val target = File(dataDir, "files/hyperpatch.bin")
    val blob = buildHomeRecentsBlob(settings.clearMode)
    runCatching {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temp).use { it.write(blob); it.fd.sync() }
        if (!temp.renameTo(target)) {
            target.outputStream().use { it.write(blob); it.flush() }
            temp.delete()
        }
        target.setReadable(true, false)
        target.parentFile?.setExecutable(true, false)
    }.onFailure { error ->
        android.util.Log.w("HyperChangerHomeRecents", "Could not write native launcher config", error)
    }
}

internal fun stageHomeRecentsNativeConfig(context: Context, settings: HomeRecentsSettings): Boolean = runCatching {
    val source = File(context.filesDir, "hyperpatch.bin")
    FileOutputStream(source).use { it.write(buildHomeRecentsBlob(settings.clearMode)); it.fd.sync() }
    val destination = "/data/adb/hyperpatch.bin"
    val staged = "$destination.tmp"
    val command = "cp '${source.absolutePath}' '$staged' && chmod 644 '$staged' && mv '$staged' '$destination'"
    val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    check(process.waitFor() == 0) { output }
    true
}.onFailure { Log.w("HyperChangerHomeRecents", "Could not stage native launcher config", it) }
    .getOrDefault(false)

private fun buildHomeRecentsBlob(mode: HomeRecentsClearMode): ByteArray {
    val output = ByteArrayOutputStream(144)
    fun u32(value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }
    output.write(byteArrayOf('H'.code.toByte(), 'P'.code.toByte(), 'C'.code.toByte(), 'F'.code.toByte()))
    u32(18)
    u32(if (mode == HomeRecentsClearMode.NONE) 0 else 1)
    if (mode == HomeRecentsClearMode.NONE) {
        u32(0)
    } else {
        u32(1)
        u32(if (mode == HomeRecentsClearMode.HIDE_KEEP_FUNCTION) 18 else 9)
    }
    // Keep the remaining V18 fields at their stock/default values.
    listOf(5, 8, 5, 5, 8, 5, 0, 112, 0, 0, 0).forEach(::u32)
    repeat(12) { u32(0) }
    u32(0)
    repeat(7) { u32(0) }
    return output.toByteArray()
}
