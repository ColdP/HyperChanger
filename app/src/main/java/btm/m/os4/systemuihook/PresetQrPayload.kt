// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

/** Platform-independent QR operations shared by bitmap UI and JVM regression checks. */
internal object PresetQrPayload {
    fun encode(payload: String, size: Int): BitMatrix = QRCodeWriter().encode(
        payload, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4),
    )

    fun decode(width: Int, height: Int, pixels: IntArray): String {
        val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        val reader = MultiFormatReader()
        val hints = mapOf(DecodeHintType.CHARACTER_SET to "UTF-8", DecodeHintType.TRY_HARDER to true)
        // Dense exported codes can confuse the finder detector. The pure-image fallback
        // recovers clean PNGs while the normal detector handles photos and screenshots.
        return runCatching { reader.decode(binary, hints) }.getOrElse {
            reader.reset()
            reader.decode(binary, hints + (DecodeHintType.PURE_BARCODE to true))
        }.text
    }
}
