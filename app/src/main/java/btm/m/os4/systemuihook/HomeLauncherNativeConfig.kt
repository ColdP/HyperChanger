// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import java.io.ByteArrayOutputStream

/** V18 launcher wire format. Only explicitly selected features may be enabled. */
internal fun buildHomeLauncherBlob(clearMode: Int, folderColumns: Int): ByteArray {
    require(clearMode in 0..2)
    require(folderColumns == 0 || folderColumns in 4..6)
    val features = buildList {
        if (clearMode != 0) add(if (clearMode == 1) 18 else 9)
        if (folderColumns != 0) add(16)
    }
    val output = ByteArrayOutputStream(148)
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
    repeat(12) { u32(0) }
    u32(0)
    repeat(7) { u32(0) }
    return output.toByteArray()
}
