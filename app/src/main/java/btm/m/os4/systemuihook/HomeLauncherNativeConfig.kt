// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import java.io.ByteArrayOutputStream

/** V18 launcher wire format. Only explicitly selected features may be enabled. */
internal fun buildHomeLauncherBlob(
    clearMode: Int,
    folderColumns: Int,
    folderRowSpacing: Int = 0,
    folderRowSpacingEnabled: Boolean = false,
    material: HomeMaterialSettings = HomeMaterialSettings(),
    nativeDock: HomeNativeDockSettings = HomeNativeDockSettings(),
): ByteArray {
    require(clearMode in 0..2)
    require(folderColumns in 0..15)
    require(folderRowSpacing in -20..80)
    val features = buildList {
        if (clearMode != 0) add(if (clearMode == 1) 18 else 9)
        if (folderColumns != 0) add(16)
    }
    require(material == material.normalized())
    require(nativeDock == nativeDock.normalized())
    val output = ByteArrayOutputStream(232)
    fun u32(value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 24) and 0xff)
    }
    output.write(byteArrayOf('H'.code.toByte(), 'P'.code.toByte(), 'C'.code.toByte(), 'F'.code.toByte()))
    u32(18)
    u32(if (features.isEmpty()) 0 else 1)
    u32(features.size)
    features.forEach(::u32)
    // Column count is ignored unless feature 16 is selected. Never default to five.
    u32(if (folderColumns == 0) 3 else folderColumns)
    listOf(8, 5, 5, 8, 5, 0, 112, 0, 0, 0).forEach(::u32)
    // Keep the legacy absolute-spacing patch disabled. The folder native library
    // applies the absolute signed value after the launcher computes its spacing.
    repeat(12) { u32(0) }
    u32(0)
    repeat(7) { u32(0) }
    // Optional extension, ignored by the V18 reader. Publish with the same atomic
    // transport as columns/recents so one setting cannot overwrite another.
    u32(0x53524746) // FGRS
    u32(2) // Absolute spacing with an explicit enable flag; v1 offsets are ignored.
    u32(if (folderRowSpacingEnabled) 1 else 0)
    u32(folderRowSpacing)
    // Independent glass parameter ratios; no material selector or global gate.
    u32(0x4c52544d) // MTRL
    u32(4)
    u32(if (material.enabled) 1 else 0)
    listOf(material.blur, material.refraction, material.softLight, material.saturation, material.brightness, material.opacity).forEach(::u32)
    u32(0x464f4944) // DIOF
    u32(1)
    u32(if (material.dockIcon.enabled) 1 else 0)
    u32(material.dockIcon.offset)
    u32(0x4b444e48) // HNDK
    u32(1)
    u32(if (nativeDock.enabled) 1 else 0)
    u32(if (nativeDock.glass) 1 else 2)
    u32(nativeDock.radius)
    u32(nativeDock.padding)
    return output.toByteArray()
}
