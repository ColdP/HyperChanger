// SPDX-License-Identifier: Apache-2.0
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
        check(blob.size == 156 + 4 * count) // V18 plus the absolute-spacing extension
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
        combinations++
    }
    fixtures?.close()
    for (columns in listOf(-1, 16)) {
        check(runCatching { buildHomeLauncherBlob(0, columns) }.isFailure)
    }
    for (spacing in listOf(-21, 81)) {
        check(runCatching { buildHomeLauncherBlob(0, 3, spacing) }.isFailure)
    }
    println("Passed $combinations combinations: stock layout, columns 1..15, row spacing, and clear-button coexistence")
}
