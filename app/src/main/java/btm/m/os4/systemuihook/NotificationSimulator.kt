// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

internal object NotificationSimulator {
    private const val PREFS = "notification_simulator"
    private const val NORMAL_TITLE_KEY = "normal_title"
    private const val NORMAL_CONTENT_KEY = "normal_content"
    private const val FOCUS_TITLE_KEY = "focus_title"
    private const val FOCUS_CONTENT_KEY = "focus_content"
    private const val NEXT_ID_KEY = "next_id"
    private const val NEXT_SCHEDULE_ID_KEY = "next_schedule_id"
    private const val NORMAL_CHANNEL_ID = "hyperchanger_normal_test"
    private const val FOCUS_CHANNEL_ID = "hyperchanger_focus_test"
    private const val NORMAL_IMAGE_FILE = "normal_notification_image.webp"
    private const val ACTION_SEND_SCHEDULED_NORMAL = "btm.m.os4.systemuihook.SEND_SCHEDULED_NORMAL"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_CONTENT = "content"

    fun normalTitle(context: Context): String = value(context, NORMAL_TITLE_KEY, "普通通知测试")
    fun normalContent(context: Context): String = value(context, NORMAL_CONTENT_KEY, "这是一条用于测试通知中心的普通通知")
    fun focusTitle(context: Context): String = value(context, FOCUS_TITLE_KEY, "焦点通知测试")
    fun focusContent(context: Context): String = value(context, FOCUS_CONTENT_KEY, "这是一条用于测试小米超级岛的焦点通知")

    fun saveNormalTitle(context: Context, value: String) = save(context, NORMAL_TITLE_KEY, value)
    fun saveNormalContent(context: Context, value: String) = save(context, NORMAL_CONTENT_KEY, value)
    fun saveFocusTitle(context: Context, value: String) = save(context, FOCUS_TITLE_KEY, value)
    fun saveFocusContent(context: Context, value: String) = save(context, FOCUS_CONTENT_KEY, value)

    fun hasNormalImage(context: Context): Boolean = normalImageFile(context).isFile

    fun saveNormalImage(context: Context, source: Bitmap) {
        val largestSide = maxOf(source.width, source.height).coerceAtLeast(1)
        val scaled = if (largestSide > MAX_IMAGE_SIDE) {
            val factor = MAX_IMAGE_SIDE.toFloat() / largestSide
            Bitmap.createScaledBitmap(
                source,
                (source.width * factor).toInt().coerceAtLeast(1),
                (source.height * factor).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }
        FileOutputStream(normalImageFile(context)).use { output ->
            scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, output)
        }
        if (scaled !== source) scaled.recycle()
    }

    fun clearNormalImage(context: Context) {
        normalImageFile(context).delete()
    }

    fun sendNormal(context: Context, title: String, content: String): Boolean {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(NORMAL_CHANNEL_ID, "HyperChanger normal test", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val image = normalImage(appContext)
        val notification = Notification.Builder(appContext, NORMAL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .apply {
                image?.let {
                    setLargeIcon(it)
                    setStyle(Notification.BigPictureStyle().bigPicture(it).bigLargeIcon(null as Bitmap?))
                }
            }
            .build()
        return notify(appContext, manager, notification)
    }

    fun sendNormalBatch(
        context: Context,
        title: String,
        content: String,
        count: Int,
        intervalMs: Long,
        delayFirst: Boolean,
    ): Boolean = runCatching {
        val appContext = context.applicationContext
        val safeCount = count.coerceIn(1, MAX_BATCH_COUNT)
        if (!delayFirst) sendNormal(appContext, title, content)
        val firstScheduled = if (delayFirst) 0 else 1
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        repeat(safeCount - firstScheduled) { index ->
            val delay = intervalMs.coerceAtLeast(0L) * (index + 1L)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay,
                scheduledNormalIntent(appContext, title, content),
            )
        }
        true
    }.getOrDefault(false)

    fun sendScheduledNormal(context: Context, title: String, content: String) {
        sendNormal(context.applicationContext, title, content)
    }

    fun sendFocus(context: Context, title: String, content: String): Boolean {
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(FOCUS_CHANNEL_ID, "HyperChanger focus test", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val notification = Notification.Builder(appContext, FOCUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setCategory(Notification.CATEGORY_EVENT)
            .setAutoCancel(true)
            .build()
        val paramV2 = JSONObject()
            .put("protocol", 1)
            .put("business", "noroot_island_test")
            .put("islandFirstFloat", true)
            .put("enableFloat", true)
            .put("updatable", false)
            .put("aodTitle", title)
            .put("ticker", title)
            .put("baseInfo", JSONObject()
                .put("title", title)
                .put("content", content)
                .put("colorTitle", "#006EFF")
                .put("type", 2))
        notification.extras.putString(
            "miui.focus.param",
            JSONObject().put("param_v2", paramV2).toString(),
        )
        return notify(appContext, manager, notification)
    }

    private fun notify(context: Context, manager: NotificationManager, notification: Notification): Boolean = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getInt(NEXT_ID_KEY, 1000)
        prefs.edit().putInt(NEXT_ID_KEY, if (current == Int.MAX_VALUE) 1000 else current + 1).apply()
        manager.notify(current, notification)
        true
    }.getOrDefault(false)

    private fun scheduledNormalIntent(context: Context, title: String, content: String): PendingIntent {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val requestCode = prefs.getInt(NEXT_SCHEDULE_ID_KEY, 20_000)
        prefs.edit()
            .putInt(NEXT_SCHEDULE_ID_KEY, if (requestCode == Int.MAX_VALUE) 20_000 else requestCode + 1)
            .apply()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, NotificationSimulatorReceiver::class.java)
                .setAction(ACTION_SEND_SCHEDULED_NORMAL)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_CONTENT, content),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun normalImage(context: Context): Bitmap? = normalImageFile(context)
        .takeIf(File::isFile)
        ?.let { BitmapFactory.decodeFile(it.absolutePath) }

    private fun normalImageFile(context: Context): File = File(context.filesDir, NORMAL_IMAGE_FILE)

    private fun value(context: Context, key: String, default: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, default) ?: default

    private fun save(context: Context, key: String, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private const val MAX_BATCH_COUNT = 10
    private const val MAX_IMAGE_SIDE = 1_440

    internal const val SCHEDULED_NORMAL_ACTION = ACTION_SEND_SCHEDULED_NORMAL
    internal const val SCHEDULED_TITLE_EXTRA = EXTRA_TITLE
    internal const val SCHEDULED_CONTENT_EXTRA = EXTRA_CONTENT
}

internal class NotificationSimulatorReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationSimulator.SCHEDULED_NORMAL_ACTION) return
        NotificationSimulator.sendScheduledNormal(
            context,
            intent.getStringExtra(NotificationSimulator.SCHEDULED_TITLE_EXTRA).orEmpty(),
            intent.getStringExtra(NotificationSimulator.SCHEDULED_CONTENT_EXTRA).orEmpty(),
        )
    }
}
