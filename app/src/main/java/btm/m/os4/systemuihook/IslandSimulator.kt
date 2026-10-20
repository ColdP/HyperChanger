// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
import android.graphics.drawable.Icon
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object IslandSimulator {
    private const val CHANNEL = "hyperchanger_island_simulation"
    private const val TAG = ISLAND_SIMULATION_TAG
    private const val CANCEL = "btm.m.os4.systemuihook.CANCEL_SIMULATED_ISLAND"
    private const val COPY = "btm.m.os4.systemuihook.COPY_SIMULATED_ISLAND_CODE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var progressJob: Job? = null

    fun send(context: Context, type: IslandSimulationType, settings: IslandSimulationSettings): Boolean = runCatching {
        val app = context.applicationContext
        val manager = app.getSystemService(NotificationManager::class.java)
        check(manager.areNotificationsEnabled())
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, tr("island_simulation", "超级岛模拟"), NotificationManager.IMPORTANCE_DEFAULT,
        ))
        check(manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE)
        val draft = settings.copy(
            title = settings.title.trim().take(40),
            content = settings.content.trim().take(80),
            summary = settings.summary.trim().take(8),
            detail = settings.detail.trim().take(24),
            action = settings.action.trim().take(12),
            subContent = settings.subContent.trim().take(40),
            secondaryAction = settings.secondaryAction.trim().take(12),
            progress = settings.progress.coerceIn(0, 99),
            durationSeconds = settings.durationSeconds.takeIf { it in ISLAND_SIMULATION_DURATIONS } ?: 15,
        )
        val finished = tr("island_sim_completed", "已完成")
        val outerGlow = IslandSimulationEffectsSettingsStore(app).outerGlow()
        val icon = Icon.createWithBitmap(requireNotNull(app.getDrawable(R.drawable.ic_hyperchanger)).toBitmap(224, 224).asShared())
        val functionIcon = Icon.createWithResource(app, if (type == IslandSimulationType.CALL) R.drawable.ic_island_call_recording else R.drawable.ic_widget_steps)
        // Shared bitmap storage avoids putting several decoded images into one Binder parcel.
        val images = islandSimulationImageSlots(type).mapNotNull { slot ->
            IslandSimulationImageStore(app, type, slot).load()?.let {
                slot to Icon.createWithBitmap(roundIslandSimulationImage(it.bitmap).asShared())
            }
        }.toMap()
        val id = type.ordinal
        val started = SystemClock.elapsedRealtime()
        val timerStart = System.currentTimeMillis() - draft.callSeconds.coerceIn(0, 3599) * 1000L
        fun post(progress: Int, terminal: Boolean) {
            val extras = Bundle().apply {
                putString(ISLAND_SIMULATION_TYPE_EXTRA, type.key)
                if (outerGlow) {
                    putString(ISLAND_SIMULATION_BIG_GLOW_EXTRA, ISLAND_SIMULATION_OUTER_GLOW_EFFECT)
                    putString("miui.effect.src", ISLAND_SIMULATION_OUTER_GLOW_EFFECT)
                }
                buildIslandSimulationRearPayload(type, draft)?.let { putString("miui.rear.param", it) }
                putString("miui.focus.param", buildIslandSimulationPayload(type, draft, progress, terminal, finished, timerStart,
                    images = images.keys, outerGlow = outerGlow))
                putBundle("miui.focus.pics", Bundle().apply {
                    putParcelable(ISLAND_SIM_ICON, icon)
                    putParcelable(ISLAND_SIM_FUNCTION, functionIcon)
                    if (type == IslandSimulationType.CALL) {
                        putParcelable(ISLAND_SIM_FUNCTION_DARK, Icon.createWithResource(app, R.drawable.ic_island_call_recording_dark))
                    }
                    images.forEach { (slot, image) -> putParcelable(slot.pictureKey, image) }
                    if (type == IslandSimulationType.DELIVERY) {
                        putParcelable(ISLAND_SIM_FORWARD, deliveryMarker(forward = true, reached = true))
                        putParcelable(ISLAND_SIM_MIDDLE, deliveryMarker(forward = false, reached = true))
                        putParcelable(ISLAND_SIM_MIDDLE_PENDING, deliveryMarker(forward = false, reached = false))
                        putParcelable(ISLAND_SIM_END, deliveryMarker(forward = false, reached = true))
                        putParcelable(ISLAND_SIM_END_PENDING, deliveryMarker(forward = false, reached = false))
                    }
                })
                putBundle("miui.focus.actions", Bundle().apply {
                    val close = PendingIntent.getBroadcast(
                        app, id,
                        Intent(app, IslandSimulationReceiver::class.java).setAction(CANCEL).putExtra("id", id)
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    val closeTitle = when (type) {
                        IslandSimulationType.CALL -> tr("island_sim_hang_up", "挂断")
                        IslandSimulationType.FILE_SHARE -> draft.secondaryAction
                        else -> draft.action
                    }
                    val closeIcon = if (type == IslandSimulationType.PHONE) Icon.createWithResource(app, R.drawable.ic_close_round) else functionIcon
                    putParcelable(ISLAND_SIM_CLOSE, Notification.Action.Builder(closeIcon, closeTitle, close).build())
                    val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    putParcelable(ISLAND_SIM_OPEN, Notification.Action.Builder(icon, draft.action, open).build())
                    if (type == IslandSimulationType.VERIFICATION) {
                        val copy = PendingIntent.getBroadcast(app, id,
                            Intent(app, IslandSimulationReceiver::class.java).setAction(COPY)
                                .putExtra("id", id).putExtra("code", draft.title),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        putParcelable(ISLAND_SIM_COPY, Notification.Action.Builder(icon, draft.action, copy).build())
                    }
                })
            }
            val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val ongoing = type == IslandSimulationType.PROGRESS && !terminal
            val notification = Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_hyperchanger)
                .setContentTitle(draft.title)
                .setContentText(if (terminal) finished else draft.content)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setCategory(if (type == IslandSimulationType.CALL || type == IslandSimulationType.PHONE) Notification.CATEGORY_CALL else Notification.CATEGORY_STATUS)
                .addExtras(extras)
                .apply {
                    if (type == IslandSimulationType.PROGRESS || type == IslandSimulationType.DELIVERY) setProgress(100, progress, false)
                }.build()
            manager.notify(TAG, id, notification)
        }
        if (type == IslandSimulationType.PROGRESS) {
            progressJob?.cancel()
            progressJob = null
        }
        post(draft.progress, false)
        if (type == IslandSimulationType.PROGRESS) {
            // Same ID and three progress components as XThief's file-download island. The
            // application scope survives page navigation; elapsed time prevents timer drift.
            progressJob = scope.launch {
                val durationMs = draft.durationSeconds * 1000L
                while (true) {
                    delay(500L)
                    if (runCatching { manager.activeNotifications.any { it.tag == TAG && it.id == id } }.getOrDefault(false).not()) break
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val percent = islandSimulationProgress(draft.progress, elapsed, durationMs)
                    if (runCatching { post(percent, percent == 100) }.isFailure || percent == 100) break
                }
            }
        }
        true
    }.getOrDefault(false)

    /** 4x assets matching the official 60x47dp forward / 30x47dp node canvases. */
    private fun deliveryMarker(forward: Boolean, reached: Boolean): Icon {
        val bitmap = Bitmap.createBitmap(if (forward) 240 else 120, 188, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (reached) Color.rgb(52, 130, 255) else Color.rgb(145, 145, 145)
            strokeCap = Paint.Cap.ROUND
        }
        if (forward) {
            canvas.drawCircle(78f, 151f, 20f, paint)
            canvas.drawCircle(175f, 151f, 20f, paint)
            canvas.drawCircle(129f, 52f, 18f, paint)
            paint.strokeWidth = 14f
            canvas.drawLine(126f, 81f, 106f, 116f, paint)
            canvas.drawLine(106f, 116f, 150f, 132f, paint)
            canvas.drawLine(129f, 87f, 164f, 105f, paint)
            canvas.drawLine(164f, 105f, 177f, 151f, paint)
        } else {
            canvas.drawCircle(60f, if (reached) 62f else 100f, if (reached) 34f else 44f, paint)
            if (reached) canvas.drawCircle(60f, 160f, 9f, paint)
            paint.color = Color.WHITE
            paint.strokeWidth = 7f
            val y = if (reached) 62f else 100f
            canvas.drawLine(43f, y, 57f, y + 14f, paint)
            canvas.drawLine(57f, y + 14f, 79f, y - 15f, paint)
        }
        return Icon.createWithBitmap(bitmap.asShared())
    }

    fun cancel(context: Context, intent: Intent) {
        if (intent.action != CANCEL && intent.action != COPY) return
        val id = intent.getIntExtra("id", -1)
        if (id !in IslandSimulationType.entries.indices) return
        if (intent.action == COPY) {
            if (id != IslandSimulationType.VERIFICATION.ordinal) return
            val code = intent.getStringExtra("code")?.take(40)?.takeIf(String::isNotBlank) ?: return
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText(tr("island_sim_verification_code", "验证码"), code))
        }
        if (id == IslandSimulationType.PROGRESS.ordinal) {
            progressJob?.cancel()
            progressJob = null
        }
        context.getSystemService(NotificationManager::class.java).cancel(TAG, id)
    }
}

internal fun islandSimulationProgress(initial: Int, elapsedMs: Long, durationMs: Long): Int {
    val start = initial.coerceIn(0, 99)
    val duration = durationMs.coerceAtLeast(1)
    return start + ((100 - start) * elapsedMs.coerceIn(0, duration) / duration).toInt()
}

internal class IslandSimulationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = IslandSimulator.cancel(context, intent)
}
