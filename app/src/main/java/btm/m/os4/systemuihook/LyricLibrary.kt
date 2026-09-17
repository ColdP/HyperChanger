package btm.m.os4.systemuihook

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal const val LYRIC_LIBRARY_PATH = "lyrics"
internal const val LYRIC_LIBRARY_AUTHORITY = SETTINGS_APPEARANCE_AUTHORITY
internal val LYRIC_LIBRARY_URI: Uri = Uri.parse("content://$LYRIC_LIBRARY_AUTHORITY/$LYRIC_LIBRARY_PATH")

internal data class LocalLyricEntry(
    val id: String,
    val title: String,
    val artist: String,
    val aliases: String,
    val type: String,
)

internal class LyricLibraryStore(private val context: Context) {
    private val directory = File(context.filesDir, "lyric_library")
    private val indexFile = File(directory, "index.json")

    fun entries(): List<LocalLyricEntry> = runCatching {
        if (!indexFile.isFile) return@runCatching emptyList()
        val array = JSONArray(indexFile.readText())
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val title = item.optString("title").trim()
                val artist = item.optString("artist").trim()
                if (id.isEmpty() || title.isEmpty() || artist.isEmpty() || !lyricFile(id).isFile) continue
                add(
                    LocalLyricEntry(
                        id = id,
                        title = title,
                        artist = artist,
                        aliases = item.optString("aliases").trim(),
                        type = item.optString("type", "LRC").ifBlank { "LRC" },
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    fun save(
        existingId: String?,
        title: String,
        artist: String,
        aliases: String,
        replacement: ByteArray?,
    ): LocalLyricEntry {
        directory.mkdirs()
        val current = entries().toMutableList()
        val old = existingId?.let { id -> current.firstOrNull { it.id == id } }
        val id = old?.id ?: UUID.randomUUID().toString()
        val target = lyricFile(id)
        if (replacement != null) target.writeBytes(replacement)
        require(target.isFile && target.length() > 0)
        val entry = LocalLyricEntry(
            id = id,
            title = title.trim(),
            artist = artist.trim(),
            aliases = aliases.trim(),
            type = detectType(target),
        )
        val index = old?.let { current.indexOf(it) } ?: -1
        if (index >= 0) current[index] = entry else current.add(entry)
        writeIndex(current)
        notifyChanged()
        return entry
    }

    fun delete(id: String) {
        delete(setOf(id))
    }

    fun delete(ids: Set<String>) {
        if (ids.isEmpty()) return
        val next = entries().filterNot { it.id in ids }
        ids.forEach { id -> runCatching { lyricFile(id).delete() } }
        writeIndex(next)
        notifyChanged()
    }

    fun writeZip(selected: List<LocalLyricEntry>, output: OutputStream) {
        require(selected.isNotEmpty())
        ZipOutputStream(output.buffered()).use { zip ->
            val manifest = JSONArray()
            selected.forEach { entry ->
                val extension = if (entry.type.equals("TTML", ignoreCase = true)) "ttml" else "lrc"
                val fileName = "${safeName(entry.title)}-${safeName(entry.artist)}-${entry.id.take(8)}.$extension"
                manifest.put(
                    JSONObject()
                        .put("title", entry.title)
                        .put("artist", entry.artist)
                        .put("aliases", entry.aliases)
                        .put("lyricType", entry.type)
                        .put("fileName", fileName)
                        .put("id", entry.id),
                )
                zip.putNextEntry(ZipEntry(fileName))
                lyricFile(entry.id).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("lyrics.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }

    fun importZip(input: InputStream): Int {
        val files = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    require(entry.name.isNotBlank() && entry.name !in files)
                    val remaining = 64L * 1024L * 1024L - totalBytes
                    require(remaining > 0)
                    val bytes = readZipEntry(zip, remaining)
                    totalBytes += bytes.size
                    files[entry.name] = bytes
                }
                zip.closeEntry()
            }
        }
        val manifestBytes = files["lyrics.json"] ?: error("missing lyrics.json")
        val manifest = JSONArray(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.length() in 1..1000)

        data class Imported(val entry: LocalLyricEntry, val bytes: ByteArray)
        val imported = ArrayList<Imported>(manifest.length())
        val importedIds = HashSet<String>()
        for (i in 0 until manifest.length()) {
            val item = manifest.getJSONObject(i)
            val id = item.getString("id").trim()
            val title = item.getString("title").trim()
            val artist = item.getString("artist").trim()
            val aliases = item.optString("aliases").trim()
            val type = item.getString("lyricType").uppercase()
            val fileName = item.getString("fileName")
            require(id.matches(Regex("[A-Za-z0-9-]+")) && importedIds.add(id))
            require(title.isNotEmpty() && artist.isNotEmpty())
            require(type == "LRC" || type == "TTML")
            val bytes = files[fileName]?.takeIf { it.isNotEmpty() }
                ?: error("missing lyric file")
            imported += Imported(LocalLyricEntry(id, title, artist, aliases, type), bytes)
        }

        directory.mkdirs()
        val merged = entries().associateByTo(LinkedHashMap(), LocalLyricEntry::id)
        imported.forEach { item ->
            lyricFile(item.entry.id).writeBytes(item.bytes)
            merged[item.entry.id] = item.entry
        }
        writeIndex(merged.values.toList())
        notifyChanged()
        return imported.size
    }

    private fun readZipEntry(zip: ZipInputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var size = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count <= 0) break
            size += count
            require(size <= limit)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    internal fun lyricFile(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]+")))
        return File(directory, "$id.lyric")
    }

    private fun detectType(file: File): String {
        val head = file.inputStream().bufferedReader().use { it.readText().take(8192) }
        return if (Regex("<(?:\\w+:)?tt(?:\\s|>)", RegexOption.IGNORE_CASE).containsMatchIn(head)) {
            "TTML"
        } else {
            "LRC"
        }
    }

    private fun safeName(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim()
        .ifBlank { "lyrics" }
        .take(64)

    private fun writeIndex(entries: List<LocalLyricEntry>) {
        directory.mkdirs()
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("title", entry.title)
                    .put("artist", entry.artist)
                    .put("aliases", entry.aliases)
                    .put("type", entry.type),
            )
        }
        val temporary = File(directory, "index.json.tmp")
        temporary.writeText(array.toString())
        if (!temporary.renameTo(indexFile)) {
            indexFile.writeText(array.toString())
            temporary.delete()
        }
    }

    private fun notifyChanged() {
        context.contentResolver.notifyChange(LYRIC_LIBRARY_URI, null)
    }
}
