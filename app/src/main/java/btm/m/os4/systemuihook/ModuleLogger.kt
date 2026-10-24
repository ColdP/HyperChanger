// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One output policy for Kotlin/Java hooks and app diagnostics. Never throws into a hook. */
object ModuleLogger {
    @Volatile private var preferences: SharedPreferences? = null
    @Volatile private var framework: ((Int, String, String, Throwable?) -> Unit)? = null
    @Volatile private var appContext: Context? = null
    private val writer = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(256), { r -> Thread(r, "hc-log-writer").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy())

    internal fun attach(prefs: SharedPreferences, sink: (Int, String, String, Throwable?) -> Unit) {
        framework = sink
        preferences = prefs
    }

    fun attachApp(context: Context) {
        appContext = context.applicationContext
        preferences = context.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (preferences?.getBoolean(KEY_LOG_ENABLED, true) != false) {
                runCatching { ModuleLogRepository(context).recordApp(Log.ASSERT, "CRASH", "Uncaught exception in ${thread.name}", error) }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    @JvmStatic @JvmOverloads
    fun emit(priority: Int, tag: String, message: String, error: Throwable? = null): Int {
        return runCatching {
            val p = preferences
            if (p?.getBoolean(KEY_LOG_ENABLED, true) == false) return 0
            if (priority < Log.INFO && p?.getBoolean(KEY_LOG_DETAIL, false) != true) return 0
            val label = if (tag == "CRASH" || priority == Log.ASSERT) "CRASH" else tag
            val text = "[HyperChanger][$label] $message"
            val sink = framework
            if (sink != null) sink(priority, "HyperChanger", text, error)
            else Log.println(priority, "HyperChanger", text + (error?.let { "\n" + Log.getStackTraceString(it) } ?: ""))
            appContext?.let { context -> writer.execute {
                runCatching { ModuleLogRepository(context).recordApp(priority, label, message, error) }
            } }
            0
        }.getOrDefault(0)
    }
}

/** Android Log compatible facade, retaining call-site priority and throwable information. */
object ModuleLog {
    const val VERBOSE = Log.VERBOSE
    const val DEBUG = Log.DEBUG
    const val INFO = Log.INFO
    const val WARN = Log.WARN
    const val ERROR = Log.ERROR
    const val ASSERT = Log.ASSERT
    @JvmStatic @JvmOverloads fun v(tag: String, msg: String, error: Throwable? = null) = ModuleLogger.emit(VERBOSE, tag, msg, error)
    @JvmStatic @JvmOverloads fun d(tag: String, msg: String, error: Throwable? = null) = ModuleLogger.emit(DEBUG, tag, msg, error)
    @JvmStatic @JvmOverloads fun i(tag: String, msg: String, error: Throwable? = null) = ModuleLogger.emit(INFO, tag, msg, error)
    @JvmStatic @JvmOverloads fun w(tag: String, msg: String, error: Throwable? = null) = ModuleLogger.emit(WARN, tag, msg, error)
    @JvmStatic fun w(tag: String, error: Throwable) = ModuleLogger.emit(WARN, tag, error.toString(), error)
    @JvmStatic @JvmOverloads fun e(tag: String, msg: String, error: Throwable? = null) = ModuleLogger.emit(ERROR, tag, msg, error)
    @JvmStatic fun getStackTraceString(error: Throwable?) = Log.getStackTraceString(error)
}
