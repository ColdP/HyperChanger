// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Encodes an album art Bitmap into a minimal MP4 video via Android's native
 * MediaCodec + MediaMuxer, so HyperOS's FastPlayer video wallpaper engine can play/freeze
 * it directly on the wallpaper window surface without any cross-window blur bleed-through.
 *
 * Optionally the video walks IN from another bitmap - see {@link #encodeToMp4}. A cover that
 * appears in one frame is what the notif cards' blurred background cannot follow: the wallpaper
 * window is what that blur samples, FastPlayer swaps its frames with no View-layer transition
 * above it, and a single-frame replacement therefore changes every card's background in one
 * frame. A fade gives it the same shape the still path already has (WallpaperProbe.startFade),
 * which is the only other place this module can reach that window.
 */
public class CoverVideoEncoder {

    private static final String TAG = "[MCCoverEnc] ";
    private static final String MIME_TYPE = "video/avc";
    private static final int TIMEOUT_US = 10000;
    /** Frame period a fade is encoded at, and the longest one worth encoding. */
    private static final long FADE_FRAME_MS = 33L;
    private static final int FADE_MAX_FRAMES = 12;
    /** The tail sample every cover video ends with, exactly as the one-frame cover always had it. */
    private static final long TAIL_MS = 100L;

    private static final java.util.Map<String, Long> sCachedKeys = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile long sLastContentKey = 0;

    /**
     * Encodes a 1-frame (or brief 2-frame) MP4 video from the provided bitmap.
     * If the bitmap is identical to the last one and the file exists, returns the cached file.
     *
     * @param bitmap the source album art bitmap
     * @param destFile the output mp4 file
     * @return true if successful, false otherwise
     */
    public static synchronized boolean encodeBitmapToMp4(Bitmap bitmap, File destFile) {
        return encodeToMp4(null, bitmap, destFile, 0, 0, false);
    }

    /**
     * Encodes a 1-frame MP4 video from the provided bitmap, using contentKey for cache verification.
     *
     * @param bitmap the source album art bitmap
     * @param destFile the output mp4 file
     * @param contentKey an explicit checksum (e.g. CRC32 of source JPEG), or 0 to compute from bitmap
     * @return true if successful, false otherwise
     */
    public static synchronized boolean encodeBitmapToMp4(Bitmap bitmap, File destFile, long contentKey) {
        return encodeToMp4(null, bitmap, destFile, contentKey, 0, false);
    }

    /**
     * The cover video: `to`, optionally walking in from `from` over `fadeMs`.
     *
     * Both ends have to be the same size - the caller has them at the wallpaper's size already -
     * and every frame between them is a byte-wise walk in YUV, so a twelve-frame fade costs two
     * conversions of the picture rather than twelve.
     *
     * Both ends being available is a request, not a requirement. A fade with nothing to fade
     * from is the one-frame cover this has always been, and a fade that fails to encode falls
     * back to exactly that rather than leaving the lock screen without a cover video: the two
     * halves of the cover are put up by different code paths, and the SystemUI view does not
     * care whether the wallpaper window caught up.
     *
     * @param from the picture to walk in from, or null for a one-frame cover
     * @param to the album cover
     * @param fadeMs how long the walk takes; ignored when `from` is null
     * @param depthTrack whether the file is for the DEPTH shape, which plays an alpha video: it
     *                   then carries the empty depth mask track that shape's player decodes
     *                   alongside every frame (see FastMp4Muxer.emptyMask). Without it that player
     *                   opens the file and draws nothing, leaving the last frame of the user's
     *                   video on every surface.
     */
    public static synchronized boolean encodeToMp4(Bitmap from, Bitmap to, File destFile,
                                                   long contentKey, long fadeMs,
                                                   boolean depthTrack) {
        if (to == null || to.isRecycled()) {
            Xp.log(TAG + "encodeToMp4: bitmap is null or recycled");
            return false;
        }
        if (from != null && (from.isRecycled() || from == to)) from = null;
        // A depth file is a different file from the same cover without the track.
        if (depthTrack && contentKey != 0) contentKey = ~contentKey;
        boolean ok = encodeOnce(from, to, destFile, contentKey, fadeMs, depthTrack);
        if (!ok && from != null) {
            Xp.log(TAG + "the cover's crossfade did not encode - falling back to a one-frame cover");
            ok = encodeOnce(null, to, destFile, contentKey, 0, depthTrack);
        }
        return ok;
    }

    /**
     * Whether destFile already holds the one-frame cover for this key - the same key
     * encodeToMp4() would compute, without the pictures. 0 is never a hit.
     */
    public static synchronized boolean isCached(File destFile, long contentKey, boolean depthTrack) {
        if (contentKey == 0) return false;
        if (depthTrack) contentKey = ~contentKey;
        Long cachedKey = sCachedKeys.get(destFile.getAbsolutePath());
        if (cachedKey == null || cachedKey != contentKey || !destFile.exists()
                || destFile.length() == 0) {
            return false;
        }
        return true;
    }

    private static boolean encodeOnce(Bitmap from, Bitmap to, File destFile,
                                      long contentKey, long fadeMs, boolean depthTrack) {
        Bitmap bitmap = to;
        if (contentKey == 0) {
            contentKey = computeBitmapChecksum(bitmap);
        }
        // A fade is a different file from the same cover without one, so the other end is part
        // of what the cache is keyed on. With no `from` the key is the one it has always been.
        if (from != null) {
            contentKey = contentKey * 31L + computeBitmapChecksum(from);
        }

        Long cachedKey = sCachedKeys.get(destFile.getAbsolutePath());
        if (contentKey != 0 && cachedKey != null && cachedKey == contentKey && destFile.exists() && destFile.length() > 0) {
            Xp.log(TAG + "encodeBitmapToMp4: reusing cached video at " + destFile.getAbsolutePath());
            return true;
        }

        long startTime = SystemClock.uptimeMillis();

        int srcW = bitmap.getWidth();
        int srcH = bitmap.getHeight();
        int width = (srcW / 2) * 2;
        int height = (srcH / 2) * 2;

        Bitmap scaledBitmap = bitmap;
        if (scaledBitmap.getWidth() != width || scaledBitmap.getHeight() != height) {
            scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
        }

        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        Bitmap scaledFrom = null;

        try {
            MediaCodecInfo codecInfo = selectCodec(MIME_TYPE);
            if (codecInfo == null) {
                Xp.log(TAG + "No encoder found for " + MIME_TYPE);
                return false;
            }

            int colorFormat = selectColorFormat(codecInfo, MIME_TYPE);
            if (colorFormat == 0) {
                Xp.log(TAG + "No supported color format found");
                return false;
            }

            // Adjust width & height if codec has specific alignment requirements
            MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(MIME_TYPE);
            if (caps != null && caps.getVideoCapabilities() != null) {
                MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                int widthAlignment = videoCaps.getWidthAlignment();
                int heightAlignment = videoCaps.getHeightAlignment();
                if (widthAlignment > 0 && width % widthAlignment != 0) {
                    width = (width / widthAlignment) * widthAlignment;
                }
                if (heightAlignment > 0 && height % heightAlignment != 0) {
                    height = (height / heightAlignment) * heightAlignment;
                }
            }

            if (scaledBitmap.getWidth() != width || scaledBitmap.getHeight() != height) {
                Bitmap prev = (scaledBitmap != bitmap) ? scaledBitmap : null;
                scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
                if (prev != null && prev != scaledBitmap) prev.recycle();
            }

            // The other end of the fade, at the settled size. Scaled once, here, rather than per
            // frame: the walk between the two happens in YUV and never looks at a Bitmap again.
            if (from != null) {
                scaledFrom = (from.getWidth() == width && from.getHeight() == height) ? from
                        : Bitmap.createScaledBitmap(from, width, height, true);
            }

            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 2000000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createByCodecName(codecInfo.getName());
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            File rawFile = new File(destFile.getAbsolutePath() + ".raw.mp4");
            if (rawFile.exists()) {
                rawFile.delete();
            }
            // The previous cover video is NOT deleted here, and no failure path below deletes
            // it either: this device has been left with no cover at all by an encode that
            // failed after the delete. The new file is written beside it and only replaces it
            // once the muxer has handed back a real MP4.
            File parent = destFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            muxer = new MediaMuxer(rawFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            Sink sink = new Sink();
            sink.muxer = muxer;

            // The two ends, converted once each. The frames between them are a walk between the
            // two byte arrays - twelve conversions of a full-screen picture in Java is most of a
            // second, and this sits in front of the player's first frame, which is exactly the
            // latency the fade exists to cover.
            byte[] yuvTo = toYuv(scaledBitmap, width, height, colorFormat);
            byte[] yuvFrom = (scaledFrom == null) ? null
                    : toYuv(scaledFrom, width, height, colorFormat);
            byte[] yuv = new byte[yuvTo.length];

            // One frame per frame period asked for, bounded at both ends: one frame is the cover
            // with no fade at all, and a fade longer than FADE_MAX_FRAMES is a fade whose cost
            // has stopped being worth its smoothness.
            int fadeFrames = yuvFrom == null ? 1
                    : Math.max(2, Math.min(FADE_MAX_FRAMES, (int) (fadeMs / FADE_FRAME_MS) + 1));

            for (int frame = 0; frame <= fadeFrames; frame++) {
                boolean tail = frame == fadeFrames;
                float t = (yuvFrom == null || tail) ? 1f : frame / (float) (fadeFrames - 1);
                fillFrame(yuv, yuvFrom, yuvTo, t);
                // The tail sample keeps the one-frame cover's own timing exactly (0ms and 100ms),
                // which is the file every device has been playing until now; a fade's own frames
                // run at the frame period and the tail sits a period after the last of them.
                long pts = (tail ? (fadeFrames == 1 ? TAIL_MS
                        : fadeFrames * FADE_FRAME_MS + TAIL_MS) : frame * FADE_FRAME_MS) * 1000L;
                int flags = tail ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;

                int inputIndex = -1;
                while (inputIndex < 0 && (SystemClock.uptimeMillis() - startTime) < 2000L) {
                    inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                }
                if (inputIndex < 0) {
                    Xp.log(TAG + "Failed to dequeue input buffer for frame " + frame
                            + " of " + (fadeFrames + 1));
                    rawFile.delete();
                    return false;
                }
                ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(yuv);
                    encoder.queueInputBuffer(inputIndex, 0, yuv.length, pts, flags);
                }
                // Drained per frame rather than at the end: the codec holds a handful of input
                // buffers, and a fade is more frames than there are buffers - queueing them all
                // first would block on the first one that did not fit. The per-frame drain can
                // be the one that sees EOS (it comes with the tail frame's output), in which
                // case the loop stops here - feeding the codec past EOS would make the final
                // drain wait for a second end-of-stream forever.
                if (!drain(encoder, sink, bufferInfo, false, 0L) || sink.eos) {
                    if (!sink.eos) {
                        rawFile.delete();
                        return false;
                    }
                    break;
                }
            }

            if (!sink.eos && !drain(encoder, sink, bufferInfo, true, 0L)) {
                Xp.log(TAG + "encodeToMp4: timed out or ended before EOS");
                rawFile.delete();
                return false;
            }

            // Close the muxer before the file is moved or given its depth track
            try {
                muxer.stop();
            } catch (Throwable ignored) {}
            try {
                muxer.release();
            } catch (Throwable ignored) {}
            muxer = null;

            // The plain shape plays the single track as it is. The depth shape needs the empty
            // depth mask beside every frame, sized to the picture actually encoded - both cases
            // replace destFile in one rename, so the cover video on screen stays whole.
            boolean placed;
            if (depthTrack) {
                placed = FastMp4Muxer.injectGpmdTrack(rawFile, destFile, width, height);
                rawFile.delete();
            } else {
                placed = rawFile.renameTo(destFile);
                if (!placed) rawFile.delete();
            }

            if (!placed || !destFile.exists() || destFile.length() == 0) {
                // destFile is still the previous cover video, and it may be what the player is
                // showing. Left alone.
                Xp.log(TAG + "encodeBitmapToMp4: could not put the new cover video in place");
                return false;
            }

            sCachedKeys.put(destFile.getAbsolutePath(), contentKey);
            sLastContentKey = contentKey;
            long cost = SystemClock.uptimeMillis() - startTime;
            Xp.log(TAG + "cover MP4" + (depthTrack ? " with its depth track" : "") + " written to " + destFile.getAbsolutePath()
                    + " (" + width + "x" + height + ", " + (fadeFrames + 1) + " frames"
                    + (yuvFrom == null ? ", no fade" : ", fading in over " + (fadeFrames - 1)
                    + " frames") + ", " + destFile.length() + "B, " + cost + "ms)");
            return true;

        } catch (Throwable t) {
            Xp.log(TAG + "Failed to encode bitmap to mp4: " + t);
            return false;
        } finally {
            File rawFile = new File(destFile.getAbsolutePath() + ".raw.mp4");
            if (rawFile.exists()) {
                rawFile.delete();
            }
            if (scaledBitmap != null && scaledBitmap != bitmap) {
                try {
                    scaledBitmap.recycle();
                } catch (Throwable ignored) {}
            }
            if (scaledFrom != null && scaledFrom != from) {
                try {
                    scaledFrom.recycle();
                } catch (Throwable ignored) {}
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Throwable ignored) {}
                try {
                    encoder.release();
                } catch (Throwable ignored) {}
            }
            if (muxer != null) {
                try {
                    muxer.stop();
                } catch (Throwable ignored) {}
                try {
                    muxer.release();
                } catch (Throwable ignored) {}
            }
        }
    }

    /** The muxer plumbing that has to live across frames: the track, whether it is open, and whether the stream has ended. */
    private static final class Sink {
        MediaMuxer muxer;
        int track = -1;
        boolean started;
        boolean eos;
    }

    /**
     * Moves whatever the encoder has produced into the muxer.
     *
     * The end of the stream is remembered ON THE SINK (`sink.eos`), not returned once: this is
     * called both per frame and at the end, and the per-frame call after the last frame can be
     * the one that sees EOS. The first version returned "done" and the caller, not knowing EOS
     * had already been consumed, called the final drain again - which then waited four seconds
     * for a second end-of-stream that no codec will ever send, failed the encode, and deleted
     * the one good cover video on the device with it.
     *
     * `untilEos` keeps waiting through INFO_TRY_AGAIN_LATER until the end-of-stream flag has
     * been seen, which is what the end of an encode is. Without it the call takes what is ready
     * and returns, which is what keeps the input buffers flowing while a fade is being fed frame
     * by frame - the codec holds a handful of them, and a twelve-frame fade is more frames than
     * that.
     *
     * @return false if the encode cannot continue (nothing came out for three seconds, a format
     *         that changed twice, or a sample before the muxer opened)
     */
    private static boolean drain(MediaCodec encoder, Sink sink, MediaCodec.BufferInfo info,
                                 boolean untilEos, long startedAt) {
        while (!sink.eos) {
            int outputIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos) return true;
                if (startedAt == 0) startedAt = SystemClock.uptimeMillis();
                if ((SystemClock.uptimeMillis() - startedAt) > 3000L) {
                    Xp.log(TAG + "drain: nothing came out for 3000ms");
                    return false;
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (sink.started) {
                    Xp.log(TAG + "drain: the format changed twice");
                    return false;
                }
                sink.track = sink.muxer.addTrack(encoder.getOutputFormat());
                sink.muxer.start();
                sink.started = true;
            } else if (outputIndex >= 0) {
                ByteBuffer outputBuffer = encoder.getOutputBuffer(outputIndex);
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (outputBuffer != null) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0;
                    }
                    if (info.size != 0) {
                        if (!sink.started) {
                            Xp.log(TAG + "drain: a sample arrived before the muxer opened");
                            return false;
                        }
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        sink.muxer.writeSampleData(sink.track, outputBuffer, info);
                    }
                }
                // Released whether or not the buffer came out null: a skipped release parks a
                // codec buffer for good, and the next dequeue then waits on a codec that has
                // none left to give.
                encoder.releaseOutputBuffer(outputIndex, false);
                if (eos) sink.eos = true;
            }
        }
        return true;
    }

    /** `b` in the encoder's own colour space, ready to be fed. Converted once per picture. */
    private static byte[] toYuv(Bitmap b, int width, int height, int colorFormat) {
        int[] argb = new int[width * height];
        b.getPixels(argb, 0, width, 0, 0, width, height);
        byte[] yuv = new byte[width * height * 3 / 2];
        if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
            encodeYUV420P(yuv, argb, width, height);
        } else {
            encodeYUV420SP(yuv, argb, width, height);
        }
        return yuv;
    }

    /**
     * One frame of the walk: `a` at t=0, `b` at t=1.
     *
     * Straight through in YUV, which is where both ends already are and where the codec reads
     * them. Blending the bitmaps and converting the result per frame would be a full-screen RGB
     * walk plus a full-screen conversion for each frame, for a picture the encoder is about to
     * subsample anyway; this is one pass over the planes. Integer, because the planes are
     * unsigned bytes and a fixed-point multiply is exact enough for a fifth-of-a-second
     * dissolve.
     */
    private static void fillFrame(byte[] out, byte[] a, byte[] b, float t) {
        if (a == null || t >= 1f) {
            System.arraycopy(b, 0, out, 0, out.length);
            return;
        }
        if (t <= 0f) {
            System.arraycopy(a, 0, out, 0, out.length);
            return;
        }
        final int scale = Math.round(t * 4096f);
        for (int i = 0; i < out.length; i++) {
            int x = a[i] & 0xFF;
            int y = b[i] & 0xFF;
            out[i] = (byte) ((x + (((y - x) * scale) >> 12)) & 0xFF);
        }
    }

    public static long computeBitmapChecksum(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return 0;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        long hash = ((long) w << 32) | (h & 0xFFFFFFFFL);
        int stepX = Math.max(1, w / 16);
        int stepY = Math.max(1, h / 16);
        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                hash = hash * 31 + bitmap.getPixel(x, y);
            }
        }
        return hash;
    }

    private static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) continue;
            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    private static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        for (int colorFormat : capabilities.colorFormats) {
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                return colorFormat;
            }
        }
        for (int colorFormat : capabilities.colorFormats) {
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                return colorFormat;
            }
        }
        return 0;
    }

    private static void encodeYUV420SP(byte[] yuv420sp, int[] argb, int width, int height) {
        final int frameSize = width * height;
        int yIndex = 0;
        int uvIndex = frameSize;
        for (int j = 0; j < height; j++) {
            int rowOffset = j * width;
            boolean isEvenRow = (j & 1) == 0;
            for (int i = 0; i < width; i++) {
                int p = argb[rowOffset + i];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yuv420sp[yIndex++] = (byte) (y < 0 ? 0 : (y > 255 ? 255 : y));
                if (isEvenRow && (i & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    yuv420sp[uvIndex++] = (byte) (u < 0 ? 0 : (u > 255 ? 255 : u));
                    yuv420sp[uvIndex++] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
                }
            }
        }
    }

    private static void encodeYUV420P(byte[] yuv420p, int[] argb, int width, int height) {
        final int frameSize = width * height;
        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = frameSize + frameSize / 4;
        for (int j = 0; j < height; j++) {
            int rowOffset = j * width;
            boolean isEvenRow = (j & 1) == 0;
            for (int i = 0; i < width; i++) {
                int p = argb[rowOffset + i];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yuv420p[yIndex++] = (byte) (y < 0 ? 0 : (y > 255 ? 255 : y));
                if (isEvenRow && (i & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    yuv420p[uIndex++] = (byte) (u < 0 ? 0 : (u > 255 ? 255 : u));
                    yuv420p[vIndex++] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
                }
            }
        }
    }
}
