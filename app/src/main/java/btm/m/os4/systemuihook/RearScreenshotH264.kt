// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Reassembles Annex B access units across arbitrary pipe reads (3/4 byte start codes). */
internal fun readRearScreenshotH264(input: InputStream, consume: (ByteArray) -> Unit) {
    val accessUnit = ByteArrayOutputStream()
    val nal = ByteArrayOutputStream()
    var hasSlice = false
    var zeros = 0
    var started = false
    fun flushNal() {
        val data = nal.toByteArray()
        nal.reset()
        if (data.isEmpty()) return
        val type = data[0].toInt() and 31
        val slice = type == 1 || type == 5
        val firstSlice = slice && data.size > 1 && (data[1].toInt() and 128) != 0
        if (hasSlice && (firstSlice || type in 6..9)) {
            consume(accessUnit.toByteArray())
            accessUnit.reset()
            hasSlice = false
        }
        accessUnit.write(byteArrayOf(0, 0, 0, 1))
        accessUnit.write(data)
        if (slice) hasSlice = true
        require(accessUnit.size() <= 8 * 1024 * 1024) { "H264 access unit exceeds preview limit" }
    }
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        for (index in 0 until count) {
            val value = buffer[index].toInt() and 255
            if (value == 0) {
                zeros++
            } else if (value == 1 && zeros >= 2) {
                if (started) flushNal()
                started = true
                zeros = 0
            } else {
                if (started) {
                    repeat(zeros) { nal.write(0) }
                    nal.write(value)
                    require(nal.size() <= 8 * 1024 * 1024) { "H264 NAL exceeds preview limit" }
                }
                zeros = 0
            }
        }
    }
    if (started) flushNal()
    if (hasSlice) consume(accessUnit.toByteArray())
}
