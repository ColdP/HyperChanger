// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** A full-resolution shell cannot fit into a single QR symbol. Preserve its ZIP in a PNG
 * ancillary chunk so the exported original is both a readable QR image and lossless import. */
internal object RearScreenshotQrArchive {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val type = "hcSh".toByteArray(Charsets.US_ASCII)
    private const val MAX_ARCHIVE = 96 * 1024 * 1024

    fun embed(png: ByteArray, archive: ByteArray): ByteArray {
        require(archive.size in 1..MAX_ARCHIVE)
        val end = chunks(png).first { it.type == "IEND" }.offset
        val result = ByteArrayOutputStream()
        result.write(png, 0, end)
        val output = DataOutputStream(result)
        output.writeInt(archive.size)
        output.write(type)
        output.write(archive)
        output.writeInt(CRC32().apply { update(type); update(archive) }.value.toInt())
        output.write(png, end, png.size - end)
        return result.toByteArray()
    }

    fun extract(png: ByteArray): ByteArray? {
        if (!png.take(8).toByteArray().contentEquals(signature)) return null
        val chunk = chunks(png).firstOrNull { it.type == "hcSh" } ?: return null
        require(chunk.length in 1..MAX_ARCHIVE)
        val bytes = png.copyOfRange(chunk.offset + 8, chunk.offset + 8 + chunk.length)
        val crc = ByteBuffer.wrap(png, chunk.offset + 8 + chunk.length, 4).int
        require(CRC32().apply { update(type); update(bytes) }.value.toInt() == crc)
        return bytes
    }

    private data class Chunk(val type: String, val offset: Int, val length: Int)
    private fun chunks(png: ByteArray): Sequence<Chunk> = sequence {
        require(png.size >= 8 && png.copyOfRange(0, 8).contentEquals(signature))
        var offset = 8
        while (offset + 12L <= png.size) {
            val length = ByteBuffer.wrap(png, offset, 4).int
            require(length >= 0 && offset + 12L + length <= png.size)
            val name = String(png, offset + 4, 4, Charsets.US_ASCII)
            yield(Chunk(name, offset, length))
            if (name == "IEND") break
            offset += length + 12
        }
    }
}
