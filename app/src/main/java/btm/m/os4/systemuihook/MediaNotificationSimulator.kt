// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

internal object MediaNotificationSimulator {
    private const val PREFS = "media_notification_simulator"
    private const val TITLE_KEY = "title"
    private const val ARTIST_KEY = "artist"
    private const val PLAYING_KEY = "playing"
    private const val POSITION_MS_KEY = "position_ms"
    private const val POSITION_UPDATED_AT_KEY = "position_updated_at"
    private const val CHANNEL_NAME_KEY = "channel_name"
    private const val PREVIOUS_LABEL_KEY = "previous_label"
    private const val PLAY_LABEL_KEY = "play_label"
    private const val PAUSE_LABEL_KEY = "pause_label"
    private const val NEXT_LABEL_KEY = "next_label"
    private const val COVER_FILE = "media_notification_cover.webp"
    private const val CHANNEL_ID = "hyperchanger_media_test"
    private const val NOTIFICATION_ID = 11027
    private const val ACTION_PREFIX = "btm.m.os4.systemuihook.MEDIA_"
    const val ACTION_PREVIOUS = ACTION_PREFIX + "PREVIOUS"
    const val ACTION_PLAY_PAUSE = ACTION_PREFIX + "PLAY_PAUSE"
    const val ACTION_NEXT = ACTION_PREFIX + "NEXT"

    private var session: MediaSession? = null
    private var currentPlaying = false

    fun title(context: Context): String = value(context, TITLE_KEY, "ハローセカイ")
    fun artist(context: Context): String = value(context, ARTIST_KEY, "DECO*27")
    fun isPlaying(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(PLAYING_KEY, true)
    fun position(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getLong(POSITION_MS_KEY, DEFAULT_POSITION_MS)
        if (!prefs.getBoolean(PLAYING_KEY, true)) return saved.coerceIn(0L, DURATION_MS)
        val updatedAt = prefs.getLong(POSITION_UPDATED_AT_KEY, SystemClock.elapsedRealtime())
        return (saved + (SystemClock.elapsedRealtime() - updatedAt)).coerceIn(0L, DURATION_MS)
    }

    fun saveMetadata(context: Context, title: String, artist: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(TITLE_KEY, title)
            .putString(ARTIST_KEY, artist)
            .apply()
    }

    fun savePlaying(context: Context, playing: Boolean) {
        val currentPosition = position(context)
        currentPlaying = playing
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PLAYING_KEY, playing)
            .putLong(POSITION_MS_KEY, currentPosition)
            .putLong(POSITION_UPDATED_AT_KEY, SystemClock.elapsedRealtime())
            .apply()
    }

    fun updatePlaying(context: Context, playing: Boolean) {
        val appContext = context.applicationContext
        savePlaying(appContext, playing)
        session?.let { activeSession ->
            setPlaybackState(activeSession, playing, position(appContext))
            refreshNotification(appContext)
        }
    }

    fun updatePosition(context: Context, positionMs: Long) {
        val appContext = context.applicationContext
        savePosition(appContext, positionMs)
        session?.let { activeSession ->
            setPlaybackState(activeSession, isPlaying(appContext), position(appContext))
            refreshNotification(appContext)
        }
    }

    fun saveChannelName(context: Context, channelName: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CHANNEL_NAME_KEY, channelName)
            .apply()
    }

    fun cover(context: Context): Bitmap = coverFile(context).takeIf(File::exists)?.let { BitmapFactory.decodeFile(it.absolutePath) }
        ?: BitmapFactory.decodeResource(context.resources, R.drawable.hello_sekai_cover)
        ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    fun saveCover(context: Context, source: Bitmap) {
        val target = coverFile(context)
        FileOutputStream(target).use { output ->
            source.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, output)
        }
    }

    fun send(
        context: Context,
        title: String,
        artist: String,
        playing: Boolean,
        channelName: String,
        previousLabel: String = "Previous",
        playLabel: String = "Play",
        pauseLabel: String = "Pause",
        nextLabel: String = "Next",
    ): Boolean = runCatching {
        val appContext = context.applicationContext
        saveMetadata(appContext, title, artist)
        savePlaying(appContext, playing)
        saveChannelName(appContext, channelName)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREVIOUS_LABEL_KEY, previousLabel)
            .putString(PLAY_LABEL_KEY, playLabel)
            .putString(PAUSE_LABEL_KEY, pauseLabel)
            .putString(NEXT_LABEL_KEY, nextLabel)
            .apply()
        val activeSession = getOrCreateSession(appContext)
        activeSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, DURATION_MS)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover(appContext))
                .build(),
        )
        setPlaybackState(activeSession, playing, position(appContext))
        activeSession.isActive = true

        val manager = appContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, channelName, NotificationManager.IMPORTANCE_LOW).apply {
                description = channelName
                setShowBadge(false)
            },
        )
        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setLargeIcon(cover(appContext))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(activeSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .addAction(action(appContext, android.R.drawable.ic_media_previous, previousLabel, ACTION_PREVIOUS))
            .addAction(
                action(
                    appContext,
                    if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (playing) pauseLabel else playLabel,
                    ACTION_PLAY_PAUSE,
                ),
            )
            .addAction(action(appContext, android.R.drawable.ic_media_next, nextLabel, ACTION_NEXT))
            .build()
        notification.extras.putString(MIUI_MEDIA_FOCUS_PARAM, islandShareParams(title, artist))
        manager.notify(NOTIFICATION_ID, notification)
        true
    }.getOrDefault(false)

    fun handleAction(context: Context, action: String) {
        val appContext = context.applicationContext
        val activeSession = getOrCreateSession(appContext)
        activeSession.isActive = true
        when (action) {
            ACTION_PLAY_PAUSE -> updatePlaying(appContext, !isPlaying(appContext))
            ACTION_PREVIOUS, ACTION_NEXT -> refreshNotification(appContext)
        }
    }

    private fun refreshNotification(context: Context) {
        send(
            context,
            title(context),
            artist(context),
            isPlaying(context),
            value(context, CHANNEL_NAME_KEY, "Media notification"),
            value(context, PREVIOUS_LABEL_KEY, "Previous"),
            value(context, PLAY_LABEL_KEY, "Play"),
            value(context, PAUSE_LABEL_KEY, "Pause"),
            value(context, NEXT_LABEL_KEY, "Next"),
        )
    }

    private fun getOrCreateSession(context: Context): MediaSession {
        session?.let { return it }
        return MediaSession(context, "HyperChangerMediaSimulator").also { created ->
            created.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    updatePlaying(context, true)
                }
                override fun onPause() {
                    updatePlaying(context, false)
                }
                override fun onSeekTo(position: Long) = updatePosition(context, position)
                override fun onSkipToNext() = refreshNotification(context)
                override fun onSkipToPrevious() = refreshNotification(context)
            })
            session = created
        }
    }

    private fun setPlaybackState(mediaSession: MediaSession, playing: Boolean, positionMs: Long) {
        currentPlaying = playing
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO,
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    positionMs.coerceIn(0L, DURATION_MS),
                    if (playing) 1f else 0f,
                )
                .build(),
        )
    }

    private fun action(context: Context, icon: Int, label: String, action: String): Notification.Action =
        Notification.Action.Builder(
            icon,
            label,
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, MediaNotificationActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()

    private fun value(context: Context, key: String, default: String): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, default) ?: default

    private fun savePosition(context: Context, positionMs: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(POSITION_MS_KEY, positionMs.coerceIn(0L, DURATION_MS))
            .putLong(POSITION_UPDATED_AT_KEY, SystemClock.elapsedRealtime())
            .apply()
    }

    private fun islandShareParams(title: String, artist: String): String = JSONObject()
        .put(
            "param_v2",
            JSONObject().put(
                "param_island",
                JSONObject().put(
                    "shareData",
                    JSONObject()
                        .put("title", title)
                        .put("content", artist)
                        .put("shareContent", PROJECT_SHARE_URL),
                ),
            ),
        )
        .toString()

    private fun coverFile(context: Context): File = File(context.filesDir, COVER_FILE)

    private const val MIUI_MEDIA_FOCUS_PARAM = "miui.focus.param.media"
    private const val PROJECT_SHARE_URL = "https://github.com/ColdP/HyperChanger"
    const val DURATION_MS = 163_000L
    private const val DEFAULT_POSITION_MS = DURATION_MS - 61_000L
}

internal class MediaNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MediaNotificationSimulator.handleAction(context, intent.action.orEmpty())
    }
}
