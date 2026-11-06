// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/** Read-only entry point launched in a short-lived root app_process, never in SystemUI. */
public final class CcPreviewMediaRoot {
    private static final String PREFIX = "CC_MEDIA_PREVIEW_V1:";
    private static final int MAX_ART_BYTES = 2 * 1024 * 1024;

    public static void main(String[] args) {
        int status = 1;
        try {
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            // A local context is sufficient. systemMain() also initializes renderer caches
            // and a system Application; those side effects do not belong in a reader.
            java.lang.reflect.Constructor<?> constructor = activityThread.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object thread = constructor.newInstance();
            java.lang.reflect.Field current = activityThread.getDeclaredField("sCurrentActivityThread");
            current.setAccessible(true);
            current.set(null, thread);
            Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            MediaSessionManager manager = context.getSystemService(MediaSessionManager.class);
            int user = Integer.parseInt(args[0]);
            List<MediaController> sessions = sessions(manager, user);
            MediaController selected = null;
            long newest = Long.MIN_VALUE;
            for (MediaController controller : sessions) {
                try {
                    PlaybackState state = controller.getPlaybackState();
                    if (state != null && state.getState() == PlaybackState.STATE_PLAYING
                            && state.getLastPositionUpdateTime() >= newest) {
                        selected = controller;
                        newest = state.getLastPositionUpdateTime();
                    }
                } catch (RuntimeException ignored) { /* A session can disappear during the read. */ }
            }
            JSONObject result = new JSONObject();
            MediaMetadata metadata = null;
            try {
                if (selected != null) metadata = selected.getMetadata();
            } catch (RuntimeException ignored) { }
            if (metadata != null) {
                result.put("title", firstText(metadata, MediaMetadata.METADATA_KEY_TITLE,
                        MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                result.put("artist", firstText(metadata, MediaMetadata.METADATA_KEY_ARTIST,
                        MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
                try {
                    Bitmap art = artwork(context, metadata);
                    if (art != null) {
                        float scale = Math.min(1f, 384f / Math.max(art.getWidth(), art.getHeight()));
                        Bitmap small = Bitmap.createScaledBitmap(art, Math.max(1, Math.round(art.getWidth() * scale)),
                                Math.max(1, Math.round(art.getHeight() * scale)), true);
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        small.compress(Bitmap.CompressFormat.PNG, 100, bytes);
                        if (bytes.size() <= 640 * 1024)
                            result.put("art", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP));
                    }
                } catch (Throwable ignored) {
                    // Artwork failure must not discard otherwise usable title/artist data.
                }
            }
            System.out.println(PREFIX + result);
            System.out.flush();
            status = 0;
        } catch (Throwable ignored) {
            // No metadata is printed to logs; the app keeps its existing placeholders.
        }
        System.exit(status); // app_process may have Binder pool threads.
    }

    @SuppressWarnings("unchecked")
    private static List<MediaController> sessions(MediaSessionManager manager, int user) throws Exception {
        try {
            // Current framework uses UserHandle; older releases exposed an int overload.
            return (List<MediaController>) MediaSessionManager.class
                    .getMethod("getActiveSessionsForUser", ComponentName.class, UserHandle.class)
                    .invoke(manager, null, UserHandle.getUserHandleForUid(user * 100000));
        } catch (NoSuchMethodException legacy) {
            return (List<MediaController>) MediaSessionManager.class
                    .getMethod("getActiveSessionsForUser", ComponentName.class, int.class)
                    .invoke(manager, null, user);
        }
    }

    private static String firstText(MediaMetadata metadata, String... keys) {
        for (String key : keys) {
            CharSequence value = metadata.getText(key);
            if (value != null && !value.toString().trim().isEmpty()) {
                String text = value.toString().trim();
                return text.substring(0, Math.min(2048, text.length()));
            }
        }
        return "";
    }

    private static Bitmap artwork(Context context, MediaMetadata metadata) {
        for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
            try {
                Bitmap art = metadata.getBitmap(key);
                if (art != null && !art.isRecycled()) return art;
            } catch (RuntimeException ignored) { }
        }
        for (String key : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            try {
                String value = metadata.getString(key);
                if (value == null || value.isEmpty()) continue;
                Uri uri = Uri.parse(value);
                if (!"content".equals(uri.getScheme()) && !"file".equals(uri.getScheme())) continue;
                try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                    if (stream == null) continue;
                    byte[] bytes = stream.readNBytes(MAX_ART_BYTES + 1);
                    if (bytes.length > MAX_ART_BYTES) continue;
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue;
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    while (Math.max(bounds.outWidth, bounds.outHeight) / Math.max(1, options.inSampleSize) > 768)
                        options.inSampleSize = Math.max(1, options.inSampleSize) * 2;
                    Bitmap art = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                    if (art != null) return art;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }
}
