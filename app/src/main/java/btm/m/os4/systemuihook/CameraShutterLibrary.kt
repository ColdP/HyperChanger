// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import io.github.libxposed.service.XposedService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal const val CAMERA_SHUTTER_DIRECTORY = "camera_shutter_sounds"
internal const val SHUTTER_AUDIO_LIMIT = 4 * 1024 * 1024
internal const val CAMERA_SHUTTER_REMOTE_INDEX = "camera_shutter_catalog.json"
internal fun shutterHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun shutterRemoteFile(id: String): String { require(id.matches(Regex("[a-f0-9]{64}"))); return "camera_shutter_$id.wav" }
internal data class CameraShutterSound(val id: String, val name: String, val durationMs: Long, val file: File, val attribution: String = "")

/** Canonical PCM16 WAV is also understood by the camera's own E1.m4.j parser. */
internal object CameraShutterAudio {
    fun read(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            require(output.size() + n <= SHUTTER_AUDIO_LIMIT)
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
    fun duration(bytes: ByteArray): Long {
        require(bytes.size in 46..SHUTTER_AUDIO_LIMIT)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
        require(tag(0) == "RIFF" && tag(8) == "WAVE" && tag(12) == "fmt " && tag(36) == "data")
        val channels = b.getShort(22).toInt(); val rate = b.getInt(24)
        require(b.getInt(4) == bytes.size - 8 && b.getInt(16) == 16 && b.getShort(20).toInt() == 1)
        require(channels in 1..2 && rate in 8000..48000 && b.getShort(34).toInt() == 16)
        require(b.getInt(28) == rate * channels * 2 && b.getShort(32).toInt() == channels * 2)
        val size = b.getInt(40)
        require(size == bytes.size - 44 && size % (channels * 2) == 0)
        return (size * 1000L / (rate * channels * 2)).also { require(it in 10..10_000) }
    }
    private fun wav(pcm: ByteArray, rate: Int, channels: Int): ByteArray {
        require(pcm.isNotEmpty() && pcm.size <= rate * channels * 2 * 10)
        val out = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
        out.putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 2)
        out.putShort((channels * 2).toShort()).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return out.array().also(::duration)
    }
    fun normalize(source: File): ByteArray {
        require(source.length() in 1..SHUTTER_AUDIO_LIMIT.toLong())
        val existing = source.readBytes()
        if (runCatching { duration(existing) }.isSuccess) return existing
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source.path)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) require(format.getLong(MediaFormat.KEY_DURATION) <= 10_000_000)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            var inputEnded = false; var rate = 0; var channels = 0; var encoding = AudioFormat.ENCODING_PCM_16BIT
            val pcm = ByteArrayOutputStream(); val info = MediaCodec.BufferInfo()
            val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
            while (true) {
                check(android.os.SystemClock.elapsedRealtime() < deadline)
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val count = extractor.readSampleData(requireNotNull(decoder.getInputBuffer(index)).apply { clear() }, 0)
                        inputEnded = count < 0
                        decoder.queueInputBuffer(index, 0, maxOf(count, 0), if (inputEnded) 0 else extractor.sampleTime,
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (!inputEnded) extractor.advance()
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(rate in 8000..48000 && channels in 1..2 && encoding in listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT))
                    }
                    else -> if (index >= 0) {
                        try {
                            require(info.presentationTimeUs <= 10_000_000)
                            val buffer = requireNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                while (buffer.remaining() >= 4) {
                                    val sample = (buffer.float.coerceIn(-1f, 1f) * 32767).toInt()
                                    pcm.write(sample and 255); pcm.write((sample shr 8) and 255)
                                }
                            } else pcm.write(ByteArray(buffer.remaining()).also(buffer::get))
                            require(pcm.size() <= SHUTTER_AUDIO_LIMIT - 44)
                        } finally { decoder.releaseOutputBuffer(index, false) }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return wav(pcm.toByteArray(), rate, channels)
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }
}

internal class CameraShutterLibrary(context: Context) {
    private val root = File(context.filesDir, CAMERA_SHUTTER_DIRECTORY)
    fun list(): List<CameraShutterSound> = root.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("[a-f0-9]{64}")) }.mapNotNull { directory ->
        runCatching {
            val meta = JSONObject(File(directory, "entry.json").readText())
            val file = File(directory, "sound.wav")
            val bytes = file.readBytes(); require(shutterHash(bytes) == directory.name)
            CameraShutterSound(directory.name, meta.getString("name").take(80), CameraShutterAudio.duration(bytes), file, meta.optString("attribution").take(4000))
        }.getOrNull()
    }.sortedBy { it.name }
    @Synchronized fun save(name: String, bytes: ByteArray, attribution: String = ""): CameraShutterSound {
        val duration = CameraShutterAudio.duration(bytes); val id = shutterHash(bytes)
        require(name.isNotBlank() && name.length <= 80 && attribution.length <= 4000)
        val target = File(root, id)
        require(target.exists() || list().size < 64)
        check(root.isDirectory || root.mkdirs())
        val stage = File(root, ".$id.pending")
        if (stage.exists()) check(stage.deleteRecursively()); check(stage.mkdirs())
        try {
            File(stage, "sound.wav").writeBytes(bytes)
            File(stage, "entry.json").writeText(JSONObject().put("name", name).put("attribution", attribution).toString())
            // Same content may already exist under a user's chosen name.
            if (target.exists()) check(target.deleteRecursively())
            check(stage.renameTo(target))
        } finally { stage.deleteRecursively() }
        return CameraShutterSound(id, name, duration, File(target, "sound.wav"), attribution)
    }
    @Synchronized fun delete(id: String) {
        require(id.matches(Regex("[a-f0-9]{64}")))
        val target = File(root, id); check(!target.exists() || target.deleteRecursively())
    }
}

internal object CameraShutterRemote {
    @Synchronized fun publish(context: Context, service: XposedService) {
        val entries = CameraShutterLibrary(context).list()
        val existing = service.listRemoteFiles().toSet()
        for (entry in entries) {
            val name = shutterRemoteFile(entry.id)
            if (name !in existing) ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(name)).use {
                it.channel.truncate(0); it.write(entry.file.readBytes()); it.fd.sync()
            }
        }
        val catalog = JSONObject().put("version", 1).put("entries", JSONArray().also { list ->
            entries.forEach { list.put(JSONObject().put("id", it.id).put("name", it.name)) }
        }).toString().toByteArray(Charsets.UTF_8)
        ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(CAMERA_SHUTTER_REMOTE_INDEX)).use {
            it.channel.truncate(0); it.write(catalog); it.fd.sync()
        }
        val desired = entries.map { shutterRemoteFile(it.id) }.toSet()
        existing.filter { it.matches(Regex("camera_shutter_[a-f0-9]{64}\\.wav")) && it !in desired }.forEach { check(service.deleteRemoteFile(it)) }
    }
}

internal data class CameraShutterPreset(val name: String, val url: String, val hash: String, val attribution: String)
internal object CameraShutterDownloads {
    private const val ROOT = "https://raw.githubusercontent.com/ColdP/hyperchanger-updateinfo/main/camera-shutter-sounds/"
    private fun fetch(url: String, limit: Int): ByteArray {
        require(url.startsWith(ROOT) && !url.removePrefix(ROOT).contains(".."))
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 20_000; connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == 200 && connection.contentLengthLong <= limit)
            val bytes = connection.inputStream.use(CameraShutterAudio::read)
            require(bytes.size <= limit); return bytes
        } finally { connection.disconnect() }
    }
    fun catalog(): List<CameraShutterPreset> {
        val json = JSONObject(fetch(ROOT + "catalog.json", 256 * 1024).toString(Charsets.UTF_8))
        require(json.getInt("version") == 1)
        val entries = json.getJSONArray("entries"); require(entries.length() <= 64)
        return List(entries.length()) {
            val item = entries.getJSONObject(it); val file = item.getString("file"); val hash = item.getString("sha256")
            require(file.matches(Regex("[a-z0-9_-]+\\.wav")) && hash.matches(Regex("[a-f0-9]{64}")))
            CameraShutterPreset(item.getString("name").also { require(it.isNotBlank() && it.length <= 80) }, ROOT + file, hash,
                item.getString("attribution").also { require(it.length <= 4000) })
        }
    }
    fun download(preset: CameraShutterPreset): ByteArray = fetch(preset.url, SHUTTER_AUDIO_LIMIT).also {
        require(shutterHash(it) == preset.hash); CameraShutterAudio.duration(it)
    }
}
