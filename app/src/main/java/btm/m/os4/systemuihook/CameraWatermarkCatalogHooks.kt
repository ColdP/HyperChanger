// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.os.ParcelFileDescriptor
import java.nio.file.Path
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method

/** Camera 2090 native catalog bridge. No native templates are bundled in the module. */
internal class CameraWatermarkCatalogHooks(
    private val module: XposedModule,
    private val enabled: () -> Boolean,
    private val unlock: () -> Boolean,
    private val front: () -> Boolean,
    private val log: (String) -> Unit,
) {
    private val installed = mutableSetOf<Method>()
    private val syncLock = Any()
    fun install(loader: ClassLoader) {
        runCatching {
            val util = Class.forName("Gg.q", false, loader)
            val scan = util.getDeclaredMethod("g", File::class.java, ArrayList::class.java, Boolean::class.javaPrimitiveType)
            synchronized(installed) {
                if (scan !in installed) {
                    module.hook(scan).setId("camera2090:watermark-import-catalog")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                            runCatching { restorePackagedCatalog(chain.getArg(0) as File) }
                                .onFailure { log("Watermark packaged catalog restore failed: $it") }
                            runCatching { sync(chain.getArg(0) as File) }.onFailure { log("Watermark import sync failed: ${android.util.Log.getStackTraceString(it)}") }
                            chain.proceed()
                        }
                    installed.add(scan)
                    log("Installed watermark import native catalog bridge")
                }
            }
            val watermark = Class.forName("com.xiaomi.cam.watermark.a", false, loader)
            val folder = watermark.getField("a")
            val ids = Class.forName("Gg.N", false, loader).getDeclaredMethod("f")
            val groups = ids.declaringClass.getDeclaredField("c").apply { isAccessible = true }
            val groupItems = Class.forName("Gg.F", false, loader).getField("b")
            val metadataMethod = watermark.getMethod("G")
            val metadataId = Class.forName("ss.a", false, loader).getMethod("b")
            synchronized(installed) {
                if (ids !in installed) {
                    module.hook(ids).setId("camera2090:watermark-supported-imports")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                            val result = chain.proceed()
                            if (!enabled() || result !is List<*>) result
                            else if (unlock()) emptyList<String>()
                            else if (result.isEmpty()) result
                            else {
                                val added = linkedSetOf<String>()
                                (groups.get(chain.thisObject) as? List<*>)?.filterNotNull()?.forEach { group ->
                                    (groupItems.get(group) as? List<*>)?.filterNotNull()?.forEach { item ->
                                        if (isImportedWatermark(folder.get(item) as? Path)) {
                                            (metadataId.invoke(metadataMethod.invoke(item)) as? String)?.let(added::add)
                                        }
                                    }
                                }
                                result + added.filter { it !in result }
                            }
                        }
                    installed.add(ids)
                }
            }
            runCatching {
            val metadataParser = Class.forName("ps.a\$i", false, loader).getDeclaredMethod("invoke")
            val metadata = Class.forName("ss.a", false, loader)
            val excludes = metadata.getField("m")
            val reminders = metadata.getField("p")
            synchronized(installed) {
                if (metadataParser !in installed) {
                    module.hook(metadataParser).setId("camera2090:watermark-front-cases")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                            val result = chain.proceed()
                            if (result != null && enabled() && front()) {
                                for (field in listOf(excludes, reminders)) {
                                    (field.get(result) as? MutableList<*>)?.removeAll { it is String && it.equals("front", true) }
                                }
                            }
                            result
                        }
                    installed.add(metadataParser)
                }
            }
            }.onFailure { log("Watermark front restriction hook unavailable: $it") }
            // Confirmed removal predicates in Gg.N.d: support list, validity,
            // device allow/deny, region, device type, properties and theme.
            for (name in listOf("Gg.B", "Gg.v", "Gg.w", "Gg.t", "Gg.A", "Gg.z", "Gg.x", "Gg.y", "Gg.C")) {
                runCatching {
                    val method = Class.forName(name, false, loader).getDeclaredMethod("invoke", Any::class.java)
                    synchronized(installed) {
                        if (method !in installed) {
                            module.hook(method).setId("camera2090:watermark-unlock:$name")
                                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                                    if (enabled() && unlock() && watermark.isInstance(chain.getArg(0))) false
                                    else chain.proceed()
                                }
                            installed.add(method)
                        }
                    }
                }.onFailure { log("Watermark catalog predicate unavailable: $name") }
            }
        }.onFailure { log("Watermark native catalog bridge unavailable: $it") }
    }
    private fun readRemote(name: String, limit: Int): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(module.openRemoteFile(name)).use {
            CameraWatermarkZip.readBounded(it, limit)
        }

    /** Restore only missing APK-owned resources; never overwrite cloud updates or imports. */
    private fun restorePackagedCatalog(root: File) {
        if (!enabled() || !unlock() || root.name !in setOf("watermarks", "video_watermarks")) return
        val context = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Context ?: return
        require(root.canonicalPath.startsWith(context.dataDir.canonicalPath + File.separator) ||
            context.getExternalFilesDir(null)?.let { root.canonicalPath.startsWith(it.canonicalPath + File.separator) } == true)
        val assets = context.assets
        fun copyMissing(path: String, target: File) {
            val children = assets.list(path).orEmpty()
            if (children.isNotEmpty()) {
                check(target.isDirectory || target.mkdirs())
                children.forEach { copyMissing("$path/$it", File(target, it)) }
            } else if (!target.exists()) {
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                val stage = File(target.parentFile, ".${target.name}.hc-pending")
                try {
                    assets.open(path).use { input -> stage.outputStream().use(input::copyTo) }
                    check(stage.renameTo(target))
                } finally { stage.delete() }
            }
        }
        copyMissing(root.name, root)
    }

    private fun readCatalog() = decodeCameraWatermarkCatalog(readRemote(CAMERA_WATERMARK_REMOTE_INDEX, 256 * 1024))

    private fun isImportedWatermark(path: Path?): Boolean {
        val item = path?.toFile() ?: return false
        val group = item.parentFile ?: return false
        if (!group.name.startsWith("hc_")) return false
        val id = File(group, ".hyperchanger-owned").takeIf(File::isFile)?.readText() ?: return false
        return id.matches(Regex("[a-f0-9]{64}")) && item.name == cameraWatermarkNativeId(id)
    }

    private fun sync(root: File) = synchronized(syncLock) {
        if (root.name !in setOf("watermarks", "video_watermarks")) return@synchronized
        val context = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentApplication").invoke(null) as? Context ?: return@synchronized
        require(root.canonicalPath.startsWith(context.dataDir.canonicalPath + File.separator) ||
            context.getExternalFilesDir(null)?.let { root.canonicalPath.startsWith(it.canonicalPath + File.separator) } == true)
        val kind = if (root.name == "video_watermarks") "video" else "photo"
        val desired = if (enabled()) readCatalog().filter { it.kind == kind }.associate { it.id to it.name } else emptyMap()
        // A failed framework read throws before cleanup, preserving existing imports.
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("hc_") && File(it, ".hyperchanger-owned").isFile }.forEach {
            val id = File(it, ".hyperchanger-owned").readText()
            if (id !in desired) check(it.deleteRecursively())
        }
        desired.forEach { (id, name) ->
            val target = File(root, "hc_${id.take(24)}")
            val itemConfig = File(target, "${cameraWatermarkNativeId(id)}/config.json")
            val owned = File(target, ".hyperchanger-owned").takeIf(File::isFile)?.readText() == id
            if (owned && itemConfig.isFile) return@forEach
            require(!target.exists() || owned)
            val bytes = readRemote(cameraWatermarkRemoteFile(id), WATERMARK_MAX_ZIP)
            require(CameraWatermarkZip.hash(bytes) == id)
            val pack = CameraWatermarkZip.decode(bytes)
            require(pack.kind == kind)
            check(root.isDirectory || root.mkdirs())
            val stage = File(root, ".hc_watermark_${id.take(24)}.pending")
            if (stage.exists()) check(stage.deleteRecursively())
            check(stage.mkdirs())
            try {
                val nativeId = cameraWatermarkNativeId(id)
                val item = File(stage, nativeId).apply { check(mkdirs()) }
                pack.resources.forEach { (path, data) ->
                    require(CameraWatermarkZip.safePath(path))
                    val file = File(item, path)
                    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                    if (path == "config.json" || path == "meta.json") {
                        val config = JSONObject(data.toString(Charsets.UTF_8))
                        config.optJSONObject("metadata")?.put("id", nativeId)?.put("name", name)
                        file.writeText(config.toString())
                    } else file.writeBytes(data)
                }
                File(stage, "preview.png").writeBytes(pack.preview)
                File(stage, "config.json").writeText(JSONObject().put("meta", JSONObject().put("name", name).put("preview", "preview.png").put("priority", 0)).toString())
                File(stage, ".hyperchanger-owned").writeText(id)
                check(root.isDirectory || root.mkdirs())
                if (owned) check(target.deleteRecursively())
                check(stage.renameTo(target))
                log("Imported $kind watermark into native catalog: $name")
            } finally { if (stage.exists()) stage.deleteRecursively() }
        }
    }
}
