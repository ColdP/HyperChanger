// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService

const val DEVICE_PROFILE_PREFERENCES = "device_profile"
private const val KEY_DEVICE_PROFILE_INITIALIZED = "initialized"
private const val KEY_ENABLED = "enabled"
private const val KEY_SNAPDRAGON_ICON = "snapdragon_icon"
private const val KEY_PROCESSOR_ICON = "processor_icon"

/** Processor icon choices for the device cards. Index order matches the picker in the module UI. */
internal const val PROCESSOR_ICON_SYSTEM = 0
internal const val PROCESSOR_ICON_SNAPDRAGON = 1
internal const val PROCESSOR_ICON_XRING = 2
internal const val PROCESSOR_ICON_DIMENSITY = 3
internal const val PROCESSOR_ICON_APPLE = 4
internal const val PROCESSOR_ICON_INTEL = 5
internal const val PROCESSOR_ICON_NVIDIA = 6
internal const val PROCESSOR_ICON_AMD = 7
internal const val PROCESSOR_ICON_CUSTOM = 8
internal const val PROCESSOR_ICON_MAX = PROCESSOR_ICON_CUSTOM
private const val KEY_MODEL = "model"
private const val KEY_DEVICE_DETAIL_MODEL = "device_detail_model"
private const val KEY_PROCESSOR = "processor"
private const val KEY_DEVICE_DETAIL_PROCESSOR = "device_detail_processor"
private const val KEY_RAM = "ram"
private const val KEY_BATTERY = "battery"
private const val KEY_SCREEN_SIZE = "screen_size"
private const val KEY_RESOLUTION = "resolution"
private const val KEY_CAMERA = "camera"
private const val KEY_CAMERA_FRONT = "camera_front"
private const val KEY_CAMERA_REAR = "camera_rear"
private const val KEY_OS_VERSION = "os_version"
private const val KEY_ANDROID_VERSION = "android_version"
private const val KEY_STORAGE = "storage"
private const val KEY_KERNEL = "kernel"
private const val KEY_BASEBAND = "baseband"
private const val KEY_HARDWARE = "hardware"

/** Values are presentation overrides for Settings, never changes to system properties. */
data class DeviceProfileSettings(
    val enabled: Boolean = false,
    val processorIcon: Int = PROCESSOR_ICON_SYSTEM,
    /** Mirrors [processorIcon] == Snapdragon; kept so the existing Snapdragon-only checks still work. */
    val snapdragonIcon: Boolean = false,
    val model: String = "",
    /** Certification (CTA) model shown on the All Parameters page, independent from [model]. */
    val detailModel: String = "",
    val processor: String = "",
    val detailProcessor: String = "",
    val ram: String = "",
    val battery: String = "",
    val screenSize: String = "",
    val resolution: String = "",
    val camera: String = "",
    val cameraFront: String = "",
    val cameraRear: String = "",
    val osVersion: String = "",
    val androidVersion: String = "",
    val storage: String = "",
    val kernel: String = "",
    val baseband: String = "",
    val hardware: String = "",
)

class DeviceProfileStore(context: Context) {
    private val local = context.getSharedPreferences(DEVICE_PROFILE_PREFERENCES, Context.MODE_PRIVATE)
    var settings: DeviceProfileSettings = local.toDeviceProfileSettings()
        private set

    fun reload() {
        settings = local.toDeviceProfileSettings()
    }

    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(DEVICE_PROFILE_PREFERENCES)
        settings = if (remote.contains(KEY_DEVICE_PROFILE_INITIALIZED)) remote.toDeviceProfileSettings() else settings
        remote.writeDeviceProfileSettings(settings)
        local.writeDeviceProfileSettings(settings)
    }

    fun update(service: XposedService?, transform: (DeviceProfileSettings) -> DeviceProfileSettings) {
        settings = transform(settings)
        local.writeDeviceProfileSettings(settings)
        service?.getRemotePreferences(DEVICE_PROFILE_PREFERENCES)?.writeDeviceProfileSettings(settings)
    }
}

internal fun SharedPreferences.toDeviceProfileSettings() = DeviceProfileSettings(
    enabled = getBoolean(KEY_ENABLED, false),
    // The old boolean toggle migrates onto the Snapdragon entry of the icon picker.
    processorIcon = if (getBoolean(KEY_SNAPDRAGON_ICON, false)) {
        PROCESSOR_ICON_SNAPDRAGON
    } else {
        getInt(KEY_PROCESSOR_ICON, PROCESSOR_ICON_SYSTEM).coerceIn(0, PROCESSOR_ICON_MAX)
    },
    snapdragonIcon = if (getBoolean(KEY_SNAPDRAGON_ICON, false)) {
        true
    } else {
        getInt(KEY_PROCESSOR_ICON, PROCESSOR_ICON_SYSTEM) == PROCESSOR_ICON_SNAPDRAGON
    },
    model = getString(KEY_MODEL, "").orEmpty(),
    detailModel = getString(KEY_DEVICE_DETAIL_MODEL, "").orEmpty(),
    processor = getString(KEY_PROCESSOR, "").orEmpty(),
    // Deliberately NOT falling back to KEY_PROCESSOR: the detail page ("All parameters") must stay
    // independent from the "My device" grid. Inheriting the grid value made the two pages always
    // show the same processor no matter which one the user edited.
    detailProcessor = getString(KEY_DEVICE_DETAIL_PROCESSOR, "").orEmpty(),
    ram = getString(KEY_RAM, "").orEmpty(),
    battery = getString(KEY_BATTERY, "").orEmpty(),
    screenSize = getString(KEY_SCREEN_SIZE, "").orEmpty(),
    resolution = getString(KEY_RESOLUTION, "").orEmpty(),
    camera = getString(KEY_CAMERA, "").orEmpty(),
    cameraFront = getString(KEY_CAMERA_FRONT, "").orEmpty(),
    cameraRear = getString(KEY_CAMERA_REAR, "").orEmpty(),
    osVersion = getString(KEY_OS_VERSION, "").orEmpty(),
    androidVersion = getString(KEY_ANDROID_VERSION, "").orEmpty(),
    storage = getString(KEY_STORAGE, "").orEmpty(),
    kernel = getString(KEY_KERNEL, "").orEmpty(),
    baseband = getString(KEY_BASEBAND, "").orEmpty(),
    hardware = getString(KEY_HARDWARE, "").orEmpty(),
)

private fun SharedPreferences.writeDeviceProfileSettings(value: DeviceProfileSettings) {
    edit()
        .putBoolean(KEY_DEVICE_PROFILE_INITIALIZED, true)
        .putBoolean(KEY_ENABLED, value.enabled)
        .putInt(KEY_PROCESSOR_ICON, value.processorIcon.coerceIn(0, PROCESSOR_ICON_MAX))
        .remove(KEY_SNAPDRAGON_ICON)
        .putString(KEY_MODEL, value.model)
        .putString(KEY_DEVICE_DETAIL_MODEL, value.detailModel)
        .putString(KEY_PROCESSOR, value.processor)
        .putString(KEY_DEVICE_DETAIL_PROCESSOR, value.detailProcessor)
        .putString(KEY_RAM, value.ram)
        .putString(KEY_BATTERY, value.battery)
        .putString(KEY_SCREEN_SIZE, value.screenSize)
        .putString(KEY_RESOLUTION, value.resolution)
        .putString(KEY_CAMERA, value.camera)
        .putString(KEY_CAMERA_FRONT, value.cameraFront)
        .putString(KEY_CAMERA_REAR, value.cameraRear)
        .putString(KEY_OS_VERSION, value.osVersion)
        .putString(KEY_ANDROID_VERSION, value.androidVersion)
        .putString(KEY_STORAGE, value.storage)
        .putString(KEY_KERNEL, value.kernel)
        .putString(KEY_BASEBAND, value.baseband)
        .putString(KEY_HARDWARE, value.hardware)
        .apply()
}
