// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import io.github.libxposed.service.XposedService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

internal const val KEY_CUSTOM_DEPTH_WALLPAPER_ENABLED = "custom_depth_wallpaper_enabled"
internal const val KEY_CUSTOM_DEPTH_WALLPAPER_PACK = "custom_depth_wallpaper_pack"
internal const val KEY_CUSTOM_DEPTH_WALLPAPER_PACKS = "custom_depth_wallpaper_packs"
internal const val KEY_CUSTOM_DEPTH_WALLPAPER_THEME_DIR = "custom_depth_wallpaper_theme_dir"
internal const val KEY_CUSTOM_DEPTH_WALLPAPER_MI_DIR = "custom_depth_wallpaper_mi_dir"
internal const val CUSTOM_DEPTH_WALLPAPER_ROOT = "hyperchanger_wallpapers"

internal data class CustomDepthWallpaperSettings(
    val enabled: Boolean = false,
    val activePack: String = "",
    val installedPacks: List<String> = emptyList(),
    val themeDir: String = "",
    val miWallpaperDir: String = "",
)

internal fun readCustomDepthWallpaperSettings(p: SharedPreferences): CustomDepthWallpaperSettings {
    val packs = p.getString(KEY_CUSTOM_DEPTH_WALLPAPER_PACKS, "[]").orEmpty()
        .let { raw -> runCatching {
            val array = JSONArray(raw)
            buildList { repeat(array.length()) { add(array.getString(it)) } }
        }.getOrDefault(emptyList()) }
    return CustomDepthWallpaperSettings(
        enabled = p.getBoolean(KEY_CUSTOM_DEPTH_WALLPAPER_ENABLED, false),
        activePack = p.getString(KEY_CUSTOM_DEPTH_WALLPAPER_PACK, "").orEmpty(),
        installedPacks = packs,
        themeDir = p.getString(KEY_CUSTOM_DEPTH_WALLPAPER_THEME_DIR, "").orEmpty(),
        miWallpaperDir = p.getString(KEY_CUSTOM_DEPTH_WALLPAPER_MI_DIR, "").orEmpty(),
    )
}

internal class CustomDepthWallpaperSettingsStore(context: Context) {
    private val local = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
    var settings: CustomDepthWallpaperSettings = readCustomDepthWallpaperSettings(local); private set

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        settings = if (local.contains(KEY_CUSTOM_DEPTH_WALLPAPER_PACKS)) {
            readCustomDepthWallpaperSettings(local)
        } else {
            readCustomDepthWallpaperSettings(remote)
        }
        write(local, settings)
        write(remote, settings)
    }

    fun update(service: XposedService?, transform: (CustomDepthWallpaperSettings) -> CustomDepthWallpaperSettings) {
        settings = transform(settings).normalized()
        write(local, settings)
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { write(it, settings) }
    }

    private fun write(p: SharedPreferences, value: CustomDepthWallpaperSettings) {
        p.edit()
            .putBoolean(KEY_CUSTOM_DEPTH_WALLPAPER_ENABLED, value.enabled)
            .putString(KEY_CUSTOM_DEPTH_WALLPAPER_PACK, value.activePack)
            .putString(KEY_CUSTOM_DEPTH_WALLPAPER_PACKS, JSONArray(value.installedPacks).toString())
            .putString(KEY_CUSTOM_DEPTH_WALLPAPER_THEME_DIR, value.themeDir)
            .putString(KEY_CUSTOM_DEPTH_WALLPAPER_MI_DIR, value.miWallpaperDir)
            .apply()
    }
}

private fun CustomDepthWallpaperSettings.normalized(): CustomDepthWallpaperSettings {
    val packs = installedPacks.map { it.trim() }
        .filter { it.matches(Regex("[a-zA-Z0-9._-]{1,48}")) }
        .distinct()
    return copy(
        activePack = activePack.takeIf { it in packs }.orEmpty(),
        installedPacks = packs,
    )
}

internal data class ImportedCustomDepthWallpaper(
    val packIds: List<String>,
    val defaultPack: String,
    val themeDirs: Map<String, String>,
    val miDirs: Map<String, String>,
)

/**
 * Imports the portable HyperOS wallpaper archive and stages every pack into the two target app
 * sandboxes. Root is used only for copying and labeling files; no system partition is modified.
 */
internal fun importCustomDepthWallpaperZip(context: Context, uri: Uri): ImportedCustomDepthWallpaper {
    val importRoot = File(context.filesDir, "custom_depth_wallpapers/import")
    importRoot.deleteRecursively()
    importRoot.mkdirs()
    context.contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                if (entry.isDirectory) continue
                require(name.isNotEmpty() && !name.startsWith('/') && !name.contains("../") && !name.contains("..\\")) {
                    "invalid archive path"
                }
                val target = File(importRoot, name)
                require(target.canonicalPath.startsWith(importRoot.canonicalPath + File.separator)) { "invalid archive path" }
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output -> zip.copyTo(output) }
            }
        }
    } ?: error("unable to open wallpaper archive")

    val manifest = File(importRoot, "manifest.json")
    val ids = if (manifest.isFile) {
        val array = JSONObject(manifest.readText()).optJSONArray("packs") ?: JSONArray()
        buildList { repeat(array.length()) { add(array.getJSONObject(it).getString("id")) } }
    } else {
        File(importRoot, "packs").listFiles { file -> file.isDirectory }?.map { it.name }.orEmpty()
    }.filter { it.matches(Regex("[a-zA-Z0-9._-]{1,48}")) }.distinct()
    require(ids.isNotEmpty()) { "no wallpaper packs found" }

    val stageRoot = File(context.filesDir, "custom_depth_wallpapers/stage")
    stageRoot.deleteRecursively()
    stageRoot.mkdirs()
    val themeDirs = linkedMapOf<String, String>()
    val miDirs = linkedMapOf<String, String>()
    // Do not trust ApplicationInfo.dataDir here. Resolve the package's actual private data
    // directory inside the root shell because HyperOS may place system apps under user_de,
    // a non-zero user, or a data_mirror mount.
    // ThemeManager 11.5 on Android 16 uses Utils.n7h()/getExternalFilesDir("MIUI")
    // for local wallpaper resources: .../Android/data/<package>/files/MIUI/wallpaper.
    // Its private /data/user/0 directory is not part of the normal wallpaper catalog.
    val themeData = resolveExternalFilesDir("com.android.thememanager")
    val miData = resolveAppDataDir("com.miui.miwallpaper")
    for (id in ids) {
        val source = File(importRoot, "packs/$id").takeIf { it.isDirectory }
            ?: File(importRoot, id).takeIf { it.isDirectory }
            ?: error("pack $id is missing")
        require(File(source, "wallpaper_config.json").isFile) { "pack $id has no wallpaper_config.json" }
        val themeStage = File(stageRoot, "$id/theme").apply { mkdirs() }
        val miStage = File(stageRoot, "$id/miwallpaper").apply { mkdirs() }
        copyDirectory(source, themeStage)
        copyDirectory(source, miStage)
        val themeDir = "$themeData/MIUI/wallpaper/$CUSTOM_DEPTH_WALLPAPER_ROOT/$id"
        val miDir = "$miData/files/$CUSTOM_DEPTH_WALLPAPER_ROOT/$id"
        themeDirs[id] = themeDir
        miDirs[id] = miDir
        stageToTarget(context, themeStage, themeDir, themeData)
        stageToTarget(context, miStage, miDir, miData)
    }
    val defaultPack = ids.firstOrNull { it.equals("snoopy", true) } ?: ids.first()
    return ImportedCustomDepthWallpaper(ids, defaultPack, themeDirs, miDirs)
}

internal fun activateCustomDepthWallpaper(
    store: CustomDepthWallpaperSettingsStore,
    service: XposedService?,
    imported: ImportedCustomDepthWallpaper,
    packId: String,
) {
    require(packId in imported.packIds)
    store.update(service) {
        it.copy(
            enabled = true,
            activePack = packId,
            installedPacks = imported.packIds,
            themeDir = imported.themeDirs.getValue(packId),
            miWallpaperDir = imported.miDirs.getValue(packId),
        )
    }
}

internal fun selectInstalledCustomDepthWallpaper(
    store: CustomDepthWallpaperSettingsStore,
    service: XposedService?,
    packId: String,
) {
    val current = store.settings
    require(packId in current.installedPacks)
    val themeRoot = File(current.themeDir).parentFile?.absolutePath.orEmpty()
    val miRoot = File(current.miWallpaperDir).parentFile?.absolutePath.orEmpty()
    require(themeRoot.isNotBlank() && miRoot.isNotBlank())
    store.update(service) {
        it.copy(
            enabled = true,
            activePack = packId,
            themeDir = "$themeRoot/$packId",
            miWallpaperDir = "$miRoot/$packId",
        )
    }
}

private fun copyDirectory(source: File, target: File) {
    source.listFiles()?.forEach { child ->
        val destination = File(target, child.name)
        if (child.isDirectory) {
            destination.mkdirs()
            copyDirectory(child, destination)
        } else {
            child.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
        }
    }
}

private fun stageToTarget(context: Context, source: File, target: String, dataDir: String) {
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    require((dataDir.startsWith("/data/") || dataDir.startsWith("/data_mirror/") || dataDir.startsWith("/storage/") || dataDir.startsWith("/mnt/")) && !dataDir.any(Char::isWhitespace))
    require(target.startsWith("$dataDir/") && !target.any(Char::isWhitespace))
    val root = quote(dataDir)
    val sourcePath = quote(source.absolutePath)
    val targetPath = quote(target)
    val command = """
        set -e
        test -d $root || { echo "missing app data directory: $root" >&2; exit 21; }
        test -d $root || { echo "missing staging root: $root" >&2; exit 21; }
        owner=${'$'}(ls -ldn $root | awk '{print ${'$'}3 ":" ${'$'}4}')
        context=${'$'}(ls -Zd $root | awk '{print ${'$'}1}')
        test -n "${'$'}owner" || { echo "could not resolve app owner" >&2; exit 22; }
        mkdir -p $targetPath
        rm -rf $targetPath/*
        cp -a $sourcePath/. $targetPath/
        chown -R "${'$'}owner" $targetPath
        chmod -R u+rwX,go+rX $targetPath
        chcon -R "${'$'}context" $targetPath || true
    """.trimIndent()
    val result = ModuleRoot.run(command, 128 * 1024)
    check(result.code == 0) { "wallpaper stage failed: ${result.output}" }
}

private fun resolveAppDataDir(packageName: String): String {
    require(packageName.matches(Regex("[a-zA-Z0-9._]{1,200}")))
    val command = """
        set -e
        pkg='$packageName'
        for candidate in \
            /data/user/*/${'$'}pkg \
            /data/user_de/*/${'$'}pkg \
            /data/data/${'$'}pkg \
            /data_mirror/data_ce/*/*/${'$'}pkg \
            /data_mirror/data_de/*/*/${'$'}pkg; do
            if [ -d "${'$'}candidate" ]; then
                printf '%s' "${'$'}candidate"
                exit 0
            fi
        done
        echo "missing app data directory for ${'$'}pkg; checked /data/user, /data/user_de, /data/data and /data_mirror" >&2
        exit 23
    """.trimIndent()
    val result = ModuleRoot.run(command, 16 * 1024)
    val path = result.output.trim()
    check(result.code == 0 && path.startsWith("/")) {
        "wallpaper stage failed: unable to resolve $packageName data directory: $path"
    }
    require(!path.any(Char::isWhitespace)) { "wallpaper stage failed: invalid resolved data directory" }
    return path
}

private fun resolveExternalFilesDir(packageName: String): String {
    require(packageName.matches(Regex("[a-zA-Z0-9._]{1,200}")))
    val command = """
        set -e
        pkg='$packageName'
        for candidate in \
            /storage/emulated/0/Android/data/${'$'}pkg/files \
            /data/media/0/Android/data/${'$'}pkg/files \
            /mnt/pass_through/0/emulated/0/Android/data/${'$'}pkg/files; do
            if [ -d "${'$'}candidate" ]; then
                printf '%s' "${'$'}candidate"
                exit 0
            fi
        done
        echo "missing external files directory for ${'$'}pkg" >&2
        exit 24
    """.trimIndent()
    val result = ModuleRoot.run(command, 16 * 1024)
    val path = result.output.trim()
    check(result.code == 0 && path.startsWith("/")) {
        "wallpaper stage failed: unable to resolve $packageName external files directory: $path"
    }
    require(!path.any(Char::isWhitespace)) { "wallpaper stage failed: invalid external files directory" }
    return path
}
