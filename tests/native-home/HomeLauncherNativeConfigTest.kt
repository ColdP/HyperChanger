// SPDX-License-Identifier: Apache-2.0
package btm.m.os4.systemuihook

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun main() {
    // Decode as the native V18 reader does, independently of the writer.
    var combinations = 0
    for (clearMode in 0..2) for (columns in listOf(0, 4, 5, 6)) {
        val blob = buildHomeLauncherBlob(clearMode, columns)
        val wire = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        check(wire.int == 0x46435048) // HPCF
        check(wire.int == 18)
        val enabled = wire.int != 0
        val count = wire.int
        check(blob.size == 140 + 4 * count) // native parser minimum length
        val features = List(count) { wire.int }.toSet()
        check(enabled == (clearMode != 0 || columns != 0))
        check((16 in features) == (columns != 0)) {
            "System default must never enable the folder layout patch"
        }
        check((18 in features) == (clearMode == 1))
        check((9 in features) == (clearMode == 2))
        check(features.all { it in setOf(9, 16, 18) })
        val storedColumns = wire.int
        if (columns != 0) check(storedColumns == columns)
        // Switching the clear-button setting must preserve the folder selection.
        val previous = ByteBuffer.wrap(buildHomeLauncherBlob(0, columns)).order(ByteOrder.LITTLE_ENDIAN)
        val previousCount = previous.getInt(12)
        check(storedColumns == previous.getInt(16 + 4 * previousCount))
        combinations++
    }
    for (columns in listOf(-1, 1, 3, 7, 16)) {
        check(runCatching { buildHomeLauncherBlob(0, columns) }.isFailure)
    }
    println("Passed $combinations combinations: stock layout, explicit columns, and clear-button coexistence")
}
