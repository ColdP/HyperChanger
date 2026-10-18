// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main(args: Array<String>) {
    val fixtures = args.firstOrNull()?.let { java.io.File(it).outputStream() }
    // Decode as the native V18 reader does, independently of the writer.
    var combinations = 0
    for (clearMode in 0..2) for (columns in 0..15) for (spacing in -20..80) for (spacingEnabled in listOf(false, true)) {
        val blob = buildHomeLauncherBlob(clearMode, columns, spacing, spacingEnabled)
        fixtures?.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(blob.size).array())
        fixtures?.write(blob)
        val wire = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        check(wire.int == 0x46435048) // HPCF
        check(wire.int == 18)
        val enabled = wire.int != 0
        val count = wire.int
        check(blob.size == 252 + 4 * count) // V18 plus spacing, glass, Dock icon, native Dock and multi-split extensions
        val features = List(count) { wire.int }.toSet()
        check(enabled == (clearMode != 0 || columns != 0))
        check((16 in features) == (columns != 0)) {
            "System default must never enable the folder layout patch"
        }
        check((18 in features) == (clearMode == 1))
        check((9 in features) == (clearMode == 2))
        check(27 !in features) // legacy absolute spacing is never enabled
        check(features.all { it in setOf(9, 16, 18) })
        val storedColumns = wire.int
        if (columns != 0) check(storedColumns == columns)
        // Switching the clear-button setting must preserve the folder selection.
        val previous = ByteBuffer.wrap(buildHomeLauncherBlob(0, columns, spacing, spacingEnabled)).order(ByteOrder.LITTLE_ENDIAN)
        val previousCount = previous.getInt(12)
        check(storedColumns == previous.getInt(16 + 4 * previousCount))
        val base = 16 + count * 4
        check(wire.getInt(base + 60) == 0)
        val extension = 140 + 4 * count
        check(wire.getInt(extension) == 0x53524746)
        check(wire.getInt(extension + 4) == 2)
        check(wire.getInt(extension + 8) == if (spacingEnabled) 1 else 0)
        check(wire.getInt(extension + 12) == spacing)
        check(previous.getInt(152 + 4 * previousCount) == spacing)
        check(previous.getInt(148 + 4 * previousCount) == if (spacingEnabled) 1 else 0)
        // Changing folder controls must not enable any other layout/material feature.
        for (trial in 0..11) check(wire.getInt(base + 44 + trial * 4) == 0)
        check(wire.getInt(extension + 16) == 0x4c52544d)
        check(wire.getInt(extension + 20) == 4)
        check(wire.getInt(extension + 24) == 0)
        repeat(6) { check(wire.getInt(extension + 28 + it * 4) == 100) }
        check(wire.getInt(extension + 52) == 0x464f4944)
        check(wire.getInt(extension + 56) == 1)
        check(wire.getInt(extension + 60) == 0)
        check(wire.getInt(extension + 64) == 0)
        check(wire.getInt(extension + 68) == 0x4b444e48)
        combinations++
    }
    fixtures?.close()
    args.getOrNull(1)?.let { path ->
        java.io.File(path).outputStream().use { out ->
            for (percent in 0..200) for (offset in -60..30) for (enabled in listOf(false, true)) {
                val material = HomeMaterialSettings(enabled, percent, percent, percent, percent, percent, percent.coerceAtMost(100), HomeDockIconSettings(enabled, offset))
                val blob = buildHomeLauncherBlob(2, 6, 12, true, material)
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(blob.size).array())
                out.write(blob)
            }
        }
    }
    check(HomeMaterialSettings(blur = -1, brightness = 201, dockIcon = HomeDockIconSettings(true, 31)).normalized() ==
        HomeMaterialSettings(blur = 0, brightness = 200, dockIcon = HomeDockIconSettings(true, 30)))
    check(runCatching { buildHomeLauncherBlob(0, 0, material = HomeMaterialSettings(blur = 201)) }.isFailure)
    for (columns in listOf(-1, 16)) {
        check(runCatching { buildHomeLauncherBlob(0, columns) }.isFailure)
    }
    for (spacing in listOf(-21, 81)) {
        check(runCatching { buildHomeLauncherBlob(0, 3, spacing) }.isFailure)
    }
    println("Passed $combinations combinations: stock layout, columns 1..15, row spacing, and clear-button coexistence")
}
