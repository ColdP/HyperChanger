// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import org.json.JSONObject
import java.lang.reflect.Proxy
import java.io.File

fun main(args: Array<String>) {
    val current = DeviceProfileSettings(
        enabled = true, processorIcon = 1, snapdragonIcon = true,
        model = "Phone", detailModel = "Certification", processor = "Grid CPU", detailProcessor = "Detail CPU",
        ram = "16 GB", battery = "6000 mAh", screenSize = "6.7 inch", resolution = "1440 x 3200",
        camera = "Camera summary", cameraFront = "Front", cameraRear = "Rear", osVersion = "OS",
        androidVersion = "Android", storage = "512 GB", kernel = "Kernel", baseband = "Baseband", hardware = "Hardware",
    )
    val preset = DeviceInfoPresetCodec.capture("test-phone", "测试机型", "说明\nDescription", current)
    check(preset.parameters.size == 17)
    val payload = DeviceInfoPresetCodec.encode(preset)
    check(DeviceInfoPresetCodec.decode(payload) == preset)
    check(!JSONObject(payload).getJSONObject("metadata").has("contributor"))
    check(deviceInfoPresetSubtitle(preset.id, null) == preset.id)
    val attributed = preset.copy(contributor = "btm_m", contributorUrl = "https://github.com/ColdP/")
    check(DeviceInfoPresetCodec.decode(DeviceInfoPresetCodec.encode(attributed)) == attributed)
    check(attributed.applyTo(current) == preset.applyTo(current)) { "Attribution is not a device setting" }
    check(deviceInfoPresetSubtitle(attributed.id, attributed.contributor) == "test-phone · btm_m")
    check(preset.applyTo(DeviceProfileSettings()) == current.copy(enabled = false, processorIcon = 0, snapdragonIcon = false))

    val blank = DeviceInfoPresetCodec.capture("empty", "Empty", "", DeviceProfileSettings())
    check(blank.applyTo(current) == current) { "Unknown parameters must retain all existing values and controls" }
    val partial = blank.copy(parameters = blank.parameters + mapOf("model" to "New phone", "device_detail_processor" to "New detail CPU"))
    check(partial.applyTo(current) == current.copy(model = "New phone", detailProcessor = "New detail CPU")) {
        "The two model/processor fields must remain independent"
    }
    check(blank.copy(parameters = blank.parameters + ("ram" to "   ")).applyTo(current) == current)
    for (id in listOf("", "../phone", "Phone", "phone--test", "-phone", "phone-", "a".repeat(241))) {
        check(!DeviceInfoPresetCodec.validId(id)) { "Accepted invalid ID: $id" }
    }
    check(DeviceInfoPresetCodec.validId("xiaomi-18-pro"))

    fun rejects(mutate: (JSONObject) -> Unit) {
        val root = JSONObject(payload).also(mutate)
        check(runCatching { DeviceInfoPresetCodec.decode(root.toString()) }.isFailure) { "Accepted invalid payload: $root" }
    }
    rejects { it.getJSONObject("metadata").put("schema_version", "2.0") }
    rejects { it.getJSONObject("metadata").put("id", "../phone") }
    rejects { it.getJSONObject("metadata").put("name", " ") }
    rejects { it.getJSONObject("metadata").put("description", JSONObject.NULL) }
    rejects { it.getJSONObject("metadata").put("contributor", "btm_m") }
    rejects { it.getJSONObject("metadata").put("contributor", 123).put("contributor_url", "https://github.com/ColdP/") }
    rejects { it.getJSONObject("metadata").put("contributor", "btm_m").put("contributor_url", "javascript:alert(1)") }
    rejects { it.getJSONObject("metadata").put("contributor", "btm_m").put("contributor_url", "https://github.com.evil.test/ColdP") }
    rejects { it.getJSONObject("metadata").put("contributor", "btm_m").put("contributor_url", "https://github.com/ColdP/repos") }
    rejects { it.getJSONObject("parameters").remove("ram") }
    rejects { it.getJSONObject("parameters").put("ram", 16) }
    rejects { it.getJSONObject("parameters").put("enabled", true) }
    rejects { it.put("unexpected", true) }
    check(runCatching { DeviceInfoPresetCodec.decode(" ".repeat(DeviceInfoPresetCodec.MAX_LENGTH + 1)) }.isFailure)

    val disk = mutableMapOf<String, Set<String>>()
    val local = memoryPreferences(disk)
    val libraryPage = DeviceInfoPresetStore(local)
    val cloudPage = DeviceInfoPresetStore(local)
    libraryPage.save(attributed)
    check(cloudPage.load().single() == attributed) { "Persist attribution for sharing and exporting" }
    cloudPage.save(blank)
    libraryPage.save(preset.copy(name = "Replacement"))
    check(libraryPage.load().size == 2)
    check(cloudPage.load().single { it.id == preset.id }.name == "Replacement")
    val reopened = DeviceInfoPresetStore(memoryPreferences(disk.toMutableMap()))
    check(reopened.load().size == 2) { "Library must survive store recreation" }
    libraryPage.delete(preset.id)
    check(cloudPage.load().single().id == blank.id)
    cloudPage.delete(blank.id)
    check(libraryPage.load().isEmpty())
    val failing = DeviceInfoPresetStore(memoryPreferences(mutableMapOf(), commitSucceeds = false))
    check(runCatching { failing.save(preset) }.isFailure) { "Failed persistence must not report success" }
    println("PASS: JSON round trip, attribution and legacy compatibility, all 17 fields, validation, persistence, replacement and deletion")

    val fixtureIndex = args.indexOf("--fixtures")
    if (fixtureIndex >= 0) {
        val files = File(args[fixtureIndex + 1]).listFiles { file -> file.extension == "json" }!!.sortedBy { it.name }
        check(files.isNotEmpty())
        for (file in files) {
            val fixture = DeviceInfoPresetCodec.decode(file.readText(Charsets.UTF_8))
            check(fixture.contributor != null && fixture.contributorUrl != null)
            check(DeviceInfoPresetCodec.decode(DeviceInfoPresetCodec.encode(fixture)) == fixture)
        }
        println("PASS: ${files.size} local presets with contributor metadata")
    }

    if ("--cloud" in args) {
        val entries = CloudDeviceInfoPresets.list()
        check(entries.isNotEmpty())
        for (entry in entries) {
            val downloaded = CloudDeviceInfoPresets.download(entry)
            check(downloaded.id == entry.id)
            check(DeviceInfoPresetCodec.decode(DeviceInfoPresetCodec.encode(downloaded)) == downloaded)
        }
        println("PASS: live cloud index and ${entries.size} preset downloads")
    }
}

private fun memoryPreferences(
    values: MutableMap<String, Set<String>>,
    commitSucceeds: Boolean = true,
): SharedPreferences = Proxy.newProxyInstance(
    SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
) { _, method, args ->
    when (method.name) {
        "getStringSet" -> values[args!![0]] ?: args[1]
        "edit" -> {
            val changes = mutableMapOf<String, Set<String>>()
            Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, setter, input ->
                when (setter.name) {
                    "putStringSet" -> {
                        @Suppress("UNCHECKED_CAST")
                        changes[input!![0] as String] = (input[1] as Set<String>).toSet()
                        proxy
                    }
                    "commit" -> { if (commitSucceeds) values.putAll(changes); commitSucceeds }
                    else -> error("Unexpected editor call: ${setter.name}")
                }
            }
        }
        else -> error("Unexpected preference call: ${method.name}")
    }
} as SharedPreferences
