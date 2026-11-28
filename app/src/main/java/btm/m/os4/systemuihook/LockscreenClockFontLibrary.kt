// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.libxposed.service.XposedService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal const val LOCKSCREEN_CLOCK_FONT_DIRECTORY = "lockscreen_clock_fonts"
internal const val LOCKSCREEN_CLOCK_FONT_MAX_BYTES = 32 * 1024 * 1024
internal const val LOCKSCREEN_CLOCK_FONT_MAX_COUNT = 64
internal const val LOCKSCREEN_CLOCK_FONT_REMOTE_INDEX = "lockscreen_clock_font_catalog.json"
internal const val LOCKSCREEN_CLOCK_FONT_STYLE_ID_BASE = 10000

internal fun lockscreenClockFontHash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Deterministic integer fontStyle ID (>= 10000) so AOD ClockBean persists the chosen custom font cleanly. */
internal fun lockscreenClockFontStyleId(id: String): Int {
    val prefix = id.take(7).toLongOrNull(16) ?: 0L
    return LOCKSCREEN_CLOCK_FONT_STYLE_ID_BASE + ((prefix and 0x7FFFFFFFL) % 800000L).toInt()
}

internal fun lockscreenClockFontRemoteFile(id: String): String {
    require(id.matches(Regex("[a-f0-9]{64}")))
    return "lockscreen_clock_font_$id.ttf"
}

internal data class RemoteClockFontEntry(
    val id: String,
    val styleId: Int,
    val name: String,
    val format: String,
    val isVariable: Boolean,
    val hasHeightAxis: Boolean,
)

internal fun decodeLockscreenClockFontCatalog(bytes: ByteArray): List<RemoteClockFontEntry> {
    val root = JSONObject(bytes.toString(Charsets.UTF_8))
    val arr = root.optJSONArray("entries") ?: return emptyList()
    val result = ArrayList<RemoteClockFontEntry>(arr.length())
    val seenStyleIds = HashSet<Int>()
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val id = obj.optString("id").trim()
        if (!id.matches(Regex("[a-f0-9]{64}"))) continue
        var styleId = obj.optInt("styleId", lockscreenClockFontStyleId(id))
        if (styleId < LOCKSCREEN_CLOCK_FONT_STYLE_ID_BASE) styleId = lockscreenClockFontStyleId(id)
        while (!seenStyleIds.add(styleId)) styleId++
        result.add(
            RemoteClockFontEntry(
                id = id,
                styleId = styleId,
                name = obj.optString("name").trim().ifBlank { id.take(8) },
                format = obj.optString("format", "TTF"),
                isVariable = obj.optBoolean("isVariable", false),
                hasHeightAxis = obj.optBoolean("hasHeightAxis", false),
            ),
        )
    }
    return result
}

internal data class FontVariableAxesInfo(
    val format: String,
    val isVariable: Boolean,
    val hasWeightAxis: Boolean,
    val hasWidthAxis: Boolean,
    val hasHeightAxis: Boolean,
    val weightMin: Float = 100f,
    val weightDefault: Float = 400f,
    val weightMax: Float = 900f,
    val widthMin: Float = 75f,
    val widthDefault: Float = 100f,
    val widthMax: Float = 125f,
    val heightMin: Float = 10f,
    val heightDefault: Float = 28f,
    val heightMax: Float = 100f,
)

internal data class LockscreenClockFontEntry(
    val id: String,
    val name: String,
    val fileName: String,
    val format: String,
    val sizeBytes: Long,
    val isVariable: Boolean,
    val hasHeightAxis: Boolean,
    val addedAt: Long,
    val file: File,
)

internal object ClockFontParser {
    fun readBounded(input: InputStream, limit: Int = LOCKSCREEN_CLOCK_FONT_MAX_BYTES): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size() + read <= limit) { "font file too large" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    /** Parses SFNT header and optional OpenType 'fvar' table to classify TTF/OTF and variable axes. */
    fun inspect(bytes: ByteArray): FontVariableAxesInfo {
        require(bytes.size >= 12) { "invalid font header" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val sfnt = buf.getInt(0)
        val format = when (sfnt) {
            0x00010000, 0x74727565 -> "TTF" // 0x00010000 or 'true'
            0x4F54544F -> "OTF"             // 'OTTO'
            0x74746366 -> "TTC"             // 'ttcf'
            else -> throw IllegalArgumentException("unsupported font signature")
        }
        if (format == "TTC") {
            return FontVariableAxesInfo(
                format = format,
                isVariable = false,
                hasWeightAxis = false,
                hasWidthAxis = false,
                hasHeightAxis = false,
            )
        }
        val numTables = buf.getShort(4).toInt() and 0xFFFF
        var fvarOffset = -1
        var fvarLength = 0
        for (i in 0 until numTables) {
            val recordPos = 12 + i * 16
            if (recordPos + 16 > bytes.size) break
            val tag = buf.getInt(recordPos)
            if (tag == 0x66766172) { // 'fvar'
                fvarOffset = buf.getInt(recordPos + 8)
                fvarLength = buf.getInt(recordPos + 12)
                break
            }
        }
        if (fvarOffset < 0 || fvarLength < 16 || fvarOffset + 16 > bytes.size) {
            return FontVariableAxesInfo(
                format = format,
                isVariable = false,
                hasWeightAxis = false,
                hasWidthAxis = false,
                hasHeightAxis = false,
            )
        }
        val axesOffset = buf.getShort(fvarOffset + 4).toInt() and 0xFFFF
        val axisCount = buf.getShort(fvarOffset + 8).toInt() and 0xFFFF
        val axisSize = buf.getShort(fvarOffset + 10).toInt() and 0xFFFF
        var hasWght = false
        var hasWdth = false
        var hasHght = false
        var wghtMin = 100f
        var wghtDef = 400f
        var wghtMax = 900f
        var wdthMin = 75f
        var wdthDef = 100f
        var wdthMax = 125f
        var hghtMin = 10f
        var hghtDef = 28f
        var hghtMax = 100f
        if (axisCount > 0 && axisSize >= 20) {
            for (i in 0 until axisCount) {
                val pos = fvarOffset + axesOffset + i * axisSize
                if (pos + 20 > bytes.size) break
                val axisTag = buf.getInt(pos)
                val minVal = buf.getInt(pos + 4) / 65536f
                val defVal = buf.getInt(pos + 8) / 65536f
                val maxVal = buf.getInt(pos + 12) / 65536f
                when (axisTag) {
                    0x77676874 -> { // 'wght'
                        hasWght = true
                        if (maxVal > minVal) {
                            wghtMin = minVal
                            wghtDef = defVal.coerceIn(minVal, maxVal)
                            wghtMax = maxVal
                        }
                    }
                    0x77647468 -> { // 'wdth'
                        hasWdth = true
                        if (maxVal > minVal) {
                            wdthMin = minVal
                            wdthDef = defVal.coerceIn(minVal, maxVal)
                            wdthMax = maxVal
                        }
                    }
                    0x48474854 -> { // 'HGHT'
                        hasHght = true
                        if (maxVal > minVal) {
                            hghtMin = minVal
                            hghtDef = defVal.coerceIn(minVal, maxVal)
                            hghtMax = maxVal
                        }
                    }
                }
            }
        }
        return FontVariableAxesInfo(
            format = format,
            isVariable = axisCount > 0,
            hasWeightAxis = hasWght,
            hasWidthAxis = hasWdth,
            hasHeightAxis = hasHght,
            weightMin = wghtMin,
            weightDefault = wghtDef,
            weightMax = wghtMax,
            widthMin = wdthMin,
            widthDefault = wdthDef,
            widthMax = wdthMax,
            heightMin = hghtMin,
            heightDefault = hghtDef,
            heightMax = hghtMax,
        )
    }

    fun buildTypeface(file: File): Typeface? {
        if (!file.isFile || file.length() <= 0L) return null
        val variable = runCatching {
            val font = Font.Builder(file).build()
            val family = runCatching { FontFamily.Builder(font).buildVariableFamily() }.getOrNull()
                ?: FontFamily.Builder(font).build()
            Typeface.CustomFallbackBuilder(family)
                .setSystemFallback("sans-serif")
                .build()
        }.getOrNull()
        if (variable != null) return variable
        return runCatching { Typeface.Builder(file).build() }.getOrNull()
    }

    fun buildTypefaceFromPfd(pfd: ParcelFileDescriptor): Typeface? {
        val variable = runCatching {
            val font = Font.Builder(pfd).build()
            val family = runCatching { FontFamily.Builder(font).buildVariableFamily() }.getOrNull()
                ?: FontFamily.Builder(font).build()
            Typeface.CustomFallbackBuilder(family)
                .setSystemFallback("sans-serif")
                .build()
        }.getOrNull()
        if (variable != null) return variable
        return runCatching { Typeface.Builder(pfd.fileDescriptor).build() }.getOrNull()
    }
}

internal class LockscreenClockFontLibrary(private val context: Context) {
    private val root = File(context.filesDir, LOCKSCREEN_CLOCK_FONT_DIRECTORY)

    fun list(): List<LockscreenClockFontEntry> = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}")) }
        .mapNotNull { dir ->
            runCatching {
                val meta = JSONObject(File(dir, "entry.json").readText())
                val file = File(dir, "font.bin")
                if (!file.isFile || file.length() <= 0L) return@mapNotNull null
                LockscreenClockFontEntry(
                    id = dir.name,
                    name = meta.optString("name").trim().take(80).ifBlank { dir.name.take(8) },
                    fileName = meta.optString("fileName").trim().take(120),
                    format = meta.optString("format", "TTF").take(8),
                    sizeBytes = file.length(),
                    isVariable = meta.optBoolean("isVariable", false),
                    hasHeightAxis = meta.optBoolean("hasHeightAxis", false),
                    addedAt = meta.optLong("addedAt", file.lastModified()),
                    file = file,
                )
            }.getOrNull()
        }
        .sortedByDescending { it.addedAt }

    fun find(id: String): LockscreenClockFontEntry? {
        if (!id.matches(Regex("[a-f0-9]{64}"))) return null
        return list().firstOrNull { it.id == id }
    }

    @Synchronized
    fun importFromUri(uri: Uri, customName: String): LockscreenClockFontEntry {
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            ClockFontParser.readBounded(it)
        } ?: error("unable to open font file")
        val originalName = appearanceAssetDisplayName(context, uri).ifBlank { "CustomFont.ttf" }
        val resolvedName = customName.trim().take(80)
            .ifBlank { originalName.substringBeforeLast('.').trim().take(80) }
            .ifBlank { "Custom Font" }
        return save(resolvedName, originalName, bytes)
    }

    @Synchronized
    fun save(name: String, fileName: String, bytes: ByteArray): LockscreenClockFontEntry {
        val info = ClockFontParser.inspect(bytes)
        val id = lockscreenClockFontHash(bytes)
        val cleanName = name.trim().take(80)
        require(cleanName.isNotBlank()) { "empty font name" }
        val target = File(root, id)
        require(target.exists() || list().size < LOCKSCREEN_CLOCK_FONT_MAX_COUNT) { "font library full" }
        check(root.isDirectory || root.mkdirs())
        val stage = File(root, ".$id.pending")
        if (stage.exists()) check(stage.deleteRecursively())
        check(stage.mkdirs())
        try {
            val stagedFont = File(stage, "font.bin")
            stagedFont.writeBytes(bytes)
            // Validate that Android's font engine can actually parse this font before persisting.
            requireNotNull(ClockFontParser.buildTypeface(stagedFont)) { "unreadable font" }
            val now = System.currentTimeMillis()
            val meta = JSONObject()
                .put("name", cleanName)
                .put("fileName", fileName.trim().take(120))
                .put("format", info.format)
                .put("isVariable", info.isVariable)
                .put("hasHeightAxis", info.hasHeightAxis)
                .put("addedAt", now)
            File(stage, "entry.json").writeText(meta.toString())
            if (target.exists()) check(target.deleteRecursively())
            check(stage.renameTo(target))
            return LockscreenClockFontEntry(
                id = id,
                name = cleanName,
                fileName = fileName.trim().take(120),
                format = info.format,
                sizeBytes = bytes.size.toLong(),
                isVariable = info.isVariable,
                hasHeightAxis = info.hasHeightAxis,
                addedAt = now,
                file = File(target, "font.bin"),
            )
        } finally {
            stage.deleteRecursively()
        }
    }

    @Synchronized
    fun rename(id: String, newName: String): LockscreenClockFontEntry {
        require(id.matches(Regex("[a-f0-9]{64}")))
        val cleanName = newName.trim().take(80)
        require(cleanName.isNotBlank())
        val dir = File(root, id)
        val metaFile = File(dir, "entry.json")
        require(metaFile.isFile)
        val meta = JSONObject(metaFile.readText())
        meta.put("name", cleanName)
        metaFile.writeText(meta.toString())
        return requireNotNull(find(id))
    }

    @Synchronized
    fun delete(id: String) {
        require(id.matches(Regex("[a-f0-9]{64}")))
        val dir = File(root, id)
        if (dir.exists()) check(dir.deleteRecursively())
    }
}

internal object LockscreenClockFontRemote {
    private val lock = Any()

    fun publish(context: Context, service: XposedService) = synchronized(lock) {
        val entries = LockscreenClockFontLibrary(context).list()
        val existing = runCatching { service.listRemoteFiles().toSet() }.getOrDefault(emptySet())
        val desired = mutableSetOf(LOCKSCREEN_CLOCK_FONT_REMOTE_INDEX)
        val index = JSONArray()
        for (entry in entries) {
            val remoteName = lockscreenClockFontRemoteFile(entry.id)
            desired.add(remoteName)
            if (remoteName !in existing) {
                write(service, remoteName, entry.file.readBytes())
            }
            index.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("styleId", lockscreenClockFontStyleId(entry.id))
                    .put("name", entry.name)
                    .put("format", entry.format)
                    .put("isVariable", entry.isVariable)
                    .put("hasHeightAxis", entry.hasHeightAxis),
            )
        }
        write(
            service,
            LOCKSCREEN_CLOCK_FONT_REMOTE_INDEX,
            JSONObject().put("version", 1).put("entries", index).toString().toByteArray(Charsets.UTF_8),
        )
        existing
            .filter { it.startsWith("lockscreen_clock_font_") && it.endsWith(".ttf") && it !in desired }
            .forEach { runCatching { service.deleteRemoteFile(it) } }
        ModuleLog.i("LockscreenClockFont", "Published ${entries.size} lockscreen clock fonts to remote files")
    }

    private fun write(service: XposedService, name: String, bytes: ByteArray) {
        ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(name)).use { output ->
            output.channel.truncate(0)
            output.write(bytes)
            output.fd.sync()
        }
    }
}
