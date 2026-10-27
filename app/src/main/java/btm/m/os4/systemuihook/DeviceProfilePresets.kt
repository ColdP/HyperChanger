// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal data class DeviceInfoPreset(
    val id: String,
    val name: String,
    val description: String,
    val parameters: Map<String, String>,
    val contributor: String? = null,
    val contributorUrl: String? = null,
) {
    /** Empty values in the repository format mean "not provided", never "clear this field". */
    fun applyTo(current: DeviceProfileSettings): DeviceProfileSettings {
        fun value(key: String, previous: String) = parameters[key]?.takeIf { it.isNotBlank() } ?: previous
        return current.copy(
            model = value("model", current.model),
            detailModel = value("device_detail_model", current.detailModel),
            processor = value("processor", current.processor),
            detailProcessor = value("device_detail_processor", current.detailProcessor),
            ram = value("ram", current.ram),
            battery = value("battery", current.battery),
            screenSize = value("screen_size", current.screenSize),
            resolution = value("resolution", current.resolution),
            camera = value("camera", current.camera),
            cameraFront = value("camera_front", current.cameraFront),
            cameraRear = value("camera_rear", current.cameraRear),
            osVersion = value("os_version", current.osVersion),
            androidVersion = value("android_version", current.androidVersion),
            storage = value("storage", current.storage),
            kernel = value("kernel", current.kernel),
            baseband = value("baseband", current.baseband),
            hardware = value("hardware", current.hardware),
        )
    }
}

internal object DeviceInfoPresetCodec {
    const val MAX_LENGTH = 262_144
    private val idPattern = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val contributorPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]*")
    private val contributorUrlPattern = Regex("https://github\\.com/[A-Za-z0-9][A-Za-z0-9_-]*/?")
    private val keys = setOf(
        "model", "processor", "ram", "battery", "screen_size", "resolution",
        "device_detail_model", "device_detail_processor", "camera", "os_version",
        "android_version", "storage", "kernel", "baseband", "hardware", "camera_rear", "camera_front",
    )

    fun validId(id: String) = id.length in 1..240 && idPattern.matches(id)

    fun capture(id: String, name: String, description: String, profile: DeviceProfileSettings): DeviceInfoPreset {
        require(validId(id) && name.isNotBlank())
        return DeviceInfoPreset(id, name, description, linkedMapOf(
            "model" to profile.model,
            "processor" to profile.processor,
            "ram" to profile.ram,
            "battery" to profile.battery,
            "screen_size" to profile.screenSize,
            "resolution" to profile.resolution,
            "device_detail_model" to profile.detailModel,
            "device_detail_processor" to profile.detailProcessor,
            "camera" to profile.camera,
            "os_version" to profile.osVersion,
            "android_version" to profile.androidVersion,
            "storage" to profile.storage,
            "kernel" to profile.kernel,
            "baseband" to profile.baseband,
            "hardware" to profile.hardware,
            "camera_rear" to profile.cameraRear,
            "camera_front" to profile.cameraFront,
        ))
    }

    fun encode(preset: DeviceInfoPreset): String = JSONObject()
        .put("\$schema", "https://raw.githubusercontent.com/ColdP/hyperchanger-device-info-presets/main/schema/preset.schema.json")
        .put("metadata", JSONObject()
            .put("id", preset.id).put("name", preset.name)
            .put("description", preset.description).put("schema_version", "1.0")
            .apply {
                preset.contributor?.let { put("contributor", it) }
                preset.contributorUrl?.let { put("contributor_url", it) }
            })
        .put("parameters", JSONObject(preset.parameters))
        .toString(2)

    fun decode(payload: String): DeviceInfoPreset {
        require(payload.length <= MAX_LENGTH)
        val root = JSONObject(payload)
        require(root.keys().asSequence().toSet() == setOf("\$schema", "metadata", "parameters"))
        require(root.get("\$schema") is String)
        val metadata = root.getJSONObject("metadata")
        val requiredMetadata = setOf("id", "name", "description", "schema_version")
        val metadataKeys = metadata.keys().asSequence().toSet()
        require(metadataKeys.containsAll(requiredMetadata) &&
            metadataKeys.all { it in requiredMetadata || it == "contributor" || it == "contributor_url" })
        fun string(key: String) = (metadata.get(key) as? String) ?: error("Invalid metadata")
        require(string("schema_version") == "1.0")
        val id = string("id")
        val name = string("name")
        require(validId(id) && name.isNotBlank())
        val contributor = if (metadata.has("contributor")) string("contributor") else null
        val contributorUrl = if (metadata.has("contributor_url")) string("contributor_url") else null
        require((contributor == null) == (contributorUrl == null))
        if (contributor != null) require(contributorPattern.matches(contributor))
        if (contributorUrl != null) require(contributorUrlPattern.matches(contributorUrl))
        val parameters = root.getJSONObject("parameters")
        require(parameters.keys().asSequence().toSet() == keys)
        return DeviceInfoPreset(id, name, string("description"), keys.associateWith {
            (parameters.get(it) as? String) ?: error("Invalid parameter")
        }, contributor, contributorUrl)
    }
}

/** App-only library. Applying a preset uses DeviceProfileStore's existing local/remote sync. */
internal class DeviceInfoPresetStore(val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("device_profile_presets", Context.MODE_PRIVATE))

    fun load(): List<DeviceInfoPreset> = preferences.getStringSet("presets", emptySet()).orEmpty()
        .mapNotNull { runCatching { DeviceInfoPresetCodec.decode(it) }.getOrNull() }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    fun save(preset: DeviceInfoPreset) = synchronized(preferences) {
        val payload = DeviceInfoPresetCodec.encode(preset)
        DeviceInfoPresetCodec.decode(payload)
        val values = preferences.getStringSet("presets", emptySet()).orEmpty().filterTo(linkedSetOf()) {
            runCatching { DeviceInfoPresetCodec.decode(it).id }.getOrNull() != preset.id
        }
        values.add(payload)
        check(preferences.edit().putStringSet("presets", values).commit())
    }

    fun delete(id: String) = synchronized(preferences) {
        val values = preferences.getStringSet("presets", emptySet()).orEmpty().filterTo(linkedSetOf()) {
            runCatching { DeviceInfoPresetCodec.decode(it).id }.getOrNull() != id
        }
        check(preferences.edit().putStringSet("presets", values).commit())
    }
}

internal fun deviceInfoPresetSubtitle(id: String, contributor: String?): String =
    if (contributor.isNullOrBlank()) id else "$id · $contributor"

internal data class CloudDeviceInfoPreset(
    val id: String,
    val name: String,
    val description: String,
    val file: String,
    val contributor: String? = null,
    val contributorUrl: String? = null,
)

internal object CloudDeviceInfoPresets {
    private const val BASE_URL = "https://raw.githubusercontent.com/ColdP/hyperchanger-device-info-presets/main/"

    // The repository's maintained index includes every published preset under presets/.
    fun list(): List<CloudDeviceInfoPreset> {
        val root = JSONObject(fetch("${BASE_URL}index.json"))
        require(root.get("schema_version") == "1.0")
        val array = root.getJSONArray("presets")
        val ids = mutableSetOf<String>()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            fun string(key: String) = (item.get(key) as? String) ?: error("Invalid index")
            val id = string("id")
            val name = string("name")
            val file = string("file")
            require(DeviceInfoPresetCodec.validId(id) && ids.add(id) && name.isNotBlank())
            require(file == "presets/$id.json")
            val entry = CloudDeviceInfoPreset(id, name, string("description"), file)
            // Attribution lives in the preset JSON, not in the repository's index.
            val preset = download(entry)
            entry.copy(contributor = preset.contributor, contributorUrl = preset.contributorUrl)
        }
    }

    fun download(entry: CloudDeviceInfoPreset): DeviceInfoPreset {
        require(DeviceInfoPresetCodec.validId(entry.id) && entry.file == "presets/${entry.id}.json")
        return DeviceInfoPresetCodec.decode(fetch("$BASE_URL${entry.file}")).also {
            require(it.id == entry.id && it.name == entry.name && it.description == entry.description)
        }
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "HyperChanger")
            check(connection.responseCode == HttpURLConnection.HTTP_OK)
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(result.length + count <= DeviceInfoPresetCodec.MAX_LENGTH)
                    result.append(buffer, 0, count)
                }
                result.toString()
            }
        } finally {
            connection.disconnect()
        }
    }
}
