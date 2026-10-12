// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.service.XposedService
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
        stageHomeRecentsNativeConfig(
            context,
            settings,
            readHomeFolderSettings(local),
            readHomeMaterialSettings(local),
            readHomeNativeDockSettings(local),
        )
    }

    fun update(service: XposedService?, transform: (HomeRecentsSettings) -> HomeRecentsSettings): Boolean {
        settings = transform(settings)
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
        return stageHomeRecentsNativeConfig(
            context,
            settings,
            readHomeFolderSettings(local),
            readHomeMaterialSettings(local),
            readHomeNativeDockSettings(local),
        )
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
internal fun syncHomeRecentsNativeConfig(
    param: PackageLoadedParam,
    settings: HomeRecentsSettings,
    folderSettings: HomeFolderSettings,
    materialSettings: HomeMaterialSettings = HomeMaterialSettings(),
    nativeDock: HomeNativeDockSettings = HomeNativeDockSettings(),
) {
    val dataDir = param.applicationInfo.dataDir ?: return
    val target = File(dataDir, "files/hyperpatch.bin")
    val blob = buildHomeLauncherBlob(settings.clearMode.value, folderSettings.effectiveColumns, folderSettings.rowSpacing, folderSettings.rowSpacingEnabled, materialSettings, nativeDock)
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

internal fun stageHomeRecentsNativeConfig(
    context: Context,
    settings: HomeRecentsSettings,
    folderSettings: HomeFolderSettings = readHomeFolderSettings(
        context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE),
    ),
    materialSettings: HomeMaterialSettings = readHomeMaterialSettings(
        context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE),
    ),
    nativeDock: HomeNativeDockSettings = readHomeNativeDockSettings(
        context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE),
    ),
): Boolean = runCatching {
    val blob = buildHomeLauncherBlob(settings.clearMode.value, folderSettings.effectiveColumns, folderSettings.rowSpacing, folderSettings.rowSpacingEnabled, materialSettings, nativeDock)
    // The Rust launcher cannot reliably read /data/adb, and its native startup
    // can precede Java package callbacks. Stage the complete config directly in
    // its own data directory with the launcher's UID and SELinux file context.
    val launcherDataDir = context.packageManager.getApplicationInfo(
        "com.miui.home", PackageManager.ApplicationInfoFlags.of(0),
    ).dataDir ?: error("Launcher data directory unavailable")
    val command = buildHomeLauncherStageCommand(launcherDataDir, blob.size)
    // Android app-data isolation hides other apps' directories in our mount
    // namespace, even after gaining UID 0. Magisk/KernelSU -M uses the global one.
    val process = ProcessBuilder("su", "-M", "-c", command).redirectErrorStream(true).start()
    val transfer = runCatching { process.outputStream.use { it.write(blob) } }
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exitCode = process.waitFor()
    check(exitCode == 0) { "Root config transport for $launcherDataDir exited $exitCode: $output" }
    transfer.getOrThrow()
    runCatching { File(context.filesDir, "home-config-write.log").writeText("${System.currentTimeMillis()}: Config staged successfully\n") }
    true
}.onFailure {
    Log.w("HyperChangerHomeRecents", "Could not stage native launcher config", it)
    // Persist diagnostics even on devices that suppress app logcat messages.
    runCatching {
        File(context.filesDir, "home-config-write.log").writeText(
            "${System.currentTimeMillis()}: Config staging failed\n${it.stackTraceToString()}\n",
        )
    }
}
    .getOrDefault(false)
