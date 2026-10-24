// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class ModuleLogEntry(val id: String, val source: Int, val time: Long, val priority: Int, val tag: String, val text: String)

internal object ModuleLogRetention {
    fun cutoff(now: Long, days: Int) = now - days * 86400000L
    fun accepts(time: Long, cutoff: Long, clearedAt: Long) = time >= cutoff && time > clearedAt
}

internal object ModuleLogArchive {
    fun write(file: File, environment: String, entries: (Int) -> List<ModuleLogEntry>) {
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("environment.txt"))
                zip.write(environment.toByteArray())
                zip.closeEntry()
                listOf("module.log", "lsposed.log").forEachIndexed { source, name ->
                    zip.putNextEntry(ZipEntry(name))
                    entries(source).asReversed().forEach { entry ->
                        zip.write("[${Date(entry.time)} ${entry.priority}/${entry.tag}] ${entry.text}\n".toByteArray())
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: Throwable) { file.delete(); throw error }
    }
}

internal object ModuleLogParser {
    const val BOUNDARY = "===HYPERCHANGER_LOG_BOUNDARY==="
    private val start = Regex("^\\[\\s*(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+.*?\\b([VDIWEFC])/([^ ]+).*?\\]\\s?(.*)$")
    private val legacy = Regex("^\\[\\s*(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}).*?\\]\\s?(.*)$")
    private val logcat = Regex("^(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+([^:]+):\\s?(.*)$")
    fun parse(raw: String, fallbackTime: Long = System.currentTimeMillis()): List<ModuleLogEntry> {
        val entries = mutableListOf<ModuleLogEntry>()
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
        var stamp = fallbackTime
        var level = Log.INFO
        var tag = "LSPosed"
        var message = StringBuilder()
        fun flush() {
            if (message.isEmpty()) return
            val text = message.toString().take(32768)
            val crash = level == Log.ASSERT || "FATAL EXCEPTION" in text || "[CRASH]" in text || "[CrashMonitor]" in text
            entries += ModuleLogEntry(hash("$stamp|$level|$tag|$text"), if (isModule(text, tag)) 0 else 1,
                stamp, if (crash) Log.ASSERT else level, if (crash) "CRASH" else tag, text)
            message = StringBuilder()
        }
        raw.lineSequence().forEach { line ->
            if (line == BOUNDARY) { flush(); return@forEach }
            val native = logcat.find(line)
            val match = start.find(line)
            val old = if (match == null) legacy.find(line) else null
            if (match != null || old != null || native != null) {
                flush()
                val time = match?.groupValues?.get(1) ?: old?.groupValues?.get(1) ?: "${native!!.groupValues[1]}T${native.groupValues[2]}"
                stamp = runCatching { format.parse(time)?.time }.getOrNull() ?: fallbackTime
                level = when (match?.groupValues?.get(2) ?: native?.groupValues?.get(3)) {
                    "V" -> Log.VERBOSE; "D" -> Log.DEBUG; "W" -> Log.WARN
                    "E", "C" -> Log.ERROR; "F" -> Log.ASSERT; else -> Log.INFO
                }
                tag = match?.groupValues?.get(3) ?: native?.groupValues?.get(4)?.trim() ?: "LSPosed"
                message.append(match?.groupValues?.get(4) ?: old?.groupValues?.get(2) ?: native!!.groupValues[5])
            } else if (message.isNotEmpty() && message.length < 32768) message.append('\n').append(line.take(32768 - message.length))
        }
        flush()
        return entries
    }
    private fun isModule(text: String, tag: String) = "HyperChanger" in text || "HyperChanger" in tag || "btm.m.os4.systemuihook" in text || "btm.m.leicaunlocker" in text
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    fun matches(entry: ModuleLogEntry, minimum: Int, crashOnly: Boolean) =
        entry.priority >= minimum && (!crashOnly || entry.tag == "CRASH")
}

internal data class RootCommandResult(val code: Int, val output: String, val truncated: Boolean)

internal object ModuleRoot {
    fun run(command: String, limit: Int = 16 * 1024 * 1024): RootCommandResult {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        process.outputStream.close()
        val bytes = ByteArrayOutputStream()
        var truncated = false
        val reader = Thread {
            runCatching { process.inputStream.use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    val remaining = limit - bytes.size()
                    if (remaining > 0) bytes.write(buffer, 0, minOf(n, remaining))
                    if (n > remaining) truncated = true
                }
            } }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(1000)
            error("Root command timed out")
        }
        reader.join(2000)
        check(!reader.isAlive) { "Root output is unavailable" }
        return RootCommandResult(process.exitValue(), bytes.toString("UTF-8"), truncated)
    }
}

internal class ModuleLogRepository(private val context: Context) {
    private val preferences get() = context.getSharedPreferences("module_log_state", Context.MODE_PRIVATE)
    private val db get() = database(context)
    private val snapshot get() = File(context.filesDir, "module-logs/lsposed-snapshot.log")

    fun recordApp(priority: Int, tag: String, message: String, error: Throwable?) {
        val now = System.currentTimeMillis()
        val text = (message + (error?.let { "\n" + Log.getStackTraceString(it) } ?: "")).take(32768)
        insert(ModuleLogEntry(ModuleLogParser.hash("$now|${System.nanoTime()}|$tag|$text"), 0, now, priority, tag, text))
    }

    private fun retentionCutoff() = ModuleLogRetention.cutoff(System.currentTimeMillis(),
        readModuleSafety(context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)).retentionDays)

    private fun insert(entry: ModuleLogEntry, cutoff: Long = retentionCutoff()) {
        if (!ModuleLogRetention.accepts(entry.time, cutoff, preferences.getLong("cleared_${entry.source}", 0))) return
        db.insertWithOnConflict("logs", null, ContentValues().apply {
            put("id", entry.id); put("source", entry.source); put("time", entry.time)
            put("priority", entry.priority); put("tag", entry.tag); put("text", entry.text)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    /** Same source directories as HyperCeiler; root only reads originals. */
    fun refresh(): Boolean {
        val result = ModuleRoot.run("""
            [ "${'$'}(id -u)" = 0 ] || exit 13
            for directory in /data/adb/lspd/log /data/adb/lspd/log.old; do
              count=0
              for file in ${'$'}(ls -1t "${'$'}directory"/modules_*.log "${'$'}directory"/verbose_*.log 2>/dev/null); do
                [ -f "${'$'}file" ] && [ -r "${'$'}file" ] || continue
                count=${'$'}((count+1))
                [ "${'$'}count" -le 4 ] || break
                printf '${ModuleLogParser.BOUNDARY}\n'
                tail -c 2097152 "${'$'}file"
                printf '\n'
              done
            done
        """.trimIndent())
        check(result.code == 0) { "LSPosed log access denied (${result.code})" }
        snapshot.parentFile?.mkdirs()
        val staged = File(snapshot.parentFile, "snapshot.tmp")
        staged.writeText(result.output)
        check(staged.renameTo(snapshot)) { "Cannot save log snapshot" }
        val entries = ModuleLogParser.parse(result.output)
        // Native/app logcat records are read by tag only; never clear shared buffers.
        val nativeEntries = runCatching {
            val native = ModuleRoot.run("logcat -d -t 3000 -v threadtime -v year HyperChanger:V HyperChangerDockNative:V HyperChangerFolder:V '*:S'", 2 * 1024 * 1024)
            if (native.code == 0) ModuleLogParser.parse(native.output).filter { it.source == 0 } else emptyList()
        }.getOrDefault(emptyList())
        db.beginTransaction()
        try {
            val cutoff = retentionCutoff()
            (entries + nativeEntries).forEach { insert(it, cutoff) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        preferences.edit().putLong("last_sync", System.currentTimeMillis()).putBoolean("truncated", result.truncated).apply()
        cleanup()
        return result.output.isNotBlank()
    }

    fun read(source: Int, minimum: Int = Log.VERBOSE, crashOnly: Boolean = false, limit: Int = 1500): List<ModuleLogEntry> {
        val where = "source=? AND priority>=?" + if (crashOnly) " AND tag='CRASH'" else ""
        return db.query("logs", null, where, arrayOf(source.toString(), minimum.toString()), null, null, "time DESC", limit.toString()).use { c ->
            buildList { while (c.moveToNext()) add(ModuleLogEntry(c.getString(c.getColumnIndexOrThrow("id")), source,
                c.getLong(c.getColumnIndexOrThrow("time")), c.getInt(c.getColumnIndexOrThrow("priority")),
                c.getString(c.getColumnIndexOrThrow("tag")), c.getString(c.getColumnIndexOrThrow("text")))) }
        }
    }

    fun clear(source: Int? = null) {
        val now = System.currentTimeMillis()
        val sources = source?.let(::listOf) ?: listOf(0, 1)
        // Save watermarks before deletion so the next import cannot resurrect cleared entries.
        val edit = preferences.edit()
        sources.forEach { edit.putLong("cleared_$it", now) }
        check(edit.commit()) { "Cannot save log clear watermark" }
        sources.forEach { db.delete("logs", "source=?", arrayOf(it.toString())) }
        snapshot.delete()
        File(context.cacheDir, "log_exports").listFiles()?.forEach { it.delete() }
    }

    fun cleanup() {
        val cutoff = retentionCutoff()
        db.delete("logs", "time<?", arrayOf(cutoff.toString()))
        db.execSQL("DELETE FROM logs WHERE id NOT IN (SELECT id FROM logs ORDER BY time DESC LIMIT 8000)")
        if (snapshot.exists() && snapshot.lastModified() < cutoff) snapshot.delete()
        File(context.cacheDir, "log_exports").listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86400000L }?.forEach { it.delete() }
    }

    fun exportZip(environment: String): File {
        cleanup()
        val dir = File(context.cacheDir, "log_exports").apply { mkdirs() }
        val file = File(dir, "HyperChanger-logs-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.zip")
        ModuleLogArchive.write(file, environment + "\nlast_sync=${preferences.getLong("last_sync", 0)}\nsource_truncated=${preferences.getBoolean("truncated", false)}\n") { source ->
            read(source, limit = 8000)
        }
        return file
    }

    companion object {
        @Volatile private var helper: SQLiteOpenHelper? = null
        @Synchronized private fun database(context: Context): SQLiteDatabase {
            if (helper == null) helper = object : SQLiteOpenHelper(context.applicationContext, "module-logs.db", null, 1) {
                override fun onCreate(db: SQLiteDatabase) {
                    db.execSQL("CREATE TABLE logs(id TEXT PRIMARY KEY, source INTEGER NOT NULL, time INTEGER NOT NULL, priority INTEGER NOT NULL, tag TEXT NOT NULL, text TEXT NOT NULL)")
                    db.execSQL("CREATE INDEX log_filter ON logs(source, time, priority)")
                }
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }.apply { setWriteAheadLoggingEnabled(true) }
            return helper!!.writableDatabase
        }
        fun scheduleCleanup(context: Context) {
            val job = JobInfo.Builder(0x48434c, ComponentName(context, ModuleLogCleanupService::class.java))
                .setPeriodic(TimeUnit.DAYS.toMillis(1)).setPersisted(true).build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }
    }
}

class ModuleLogCleanupService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val success = runCatching { ModuleLogRepository(this).cleanup() }.isSuccess
            jobFinished(params, !success)
        }.start()
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}
