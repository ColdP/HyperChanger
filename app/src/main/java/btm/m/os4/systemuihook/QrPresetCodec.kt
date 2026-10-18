// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.util.EnumMap

internal fun createPresetQrCode(payload: String, size: Int = 768): Bitmap {
    val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
        put(EncodeHintType.CHARACTER_SET, "UTF-8")
        put(EncodeHintType.MARGIN, 2)
    }
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
    return matrix.toBitmap()
}

internal fun readPresetQrCode(bitmap: Bitmap): String {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    return MultiFormatReader().decode(
        BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels))),
    ).text
}

private fun BitMatrix.toBitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
    val pixels = IntArray(width * height) { index ->
        if (get(index % width, index / width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
}
