// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.graphics.Bitmap
import com.google.zxing.common.BitMatrix

internal fun createPresetQrCode(payload: String, size: Int = 768): Bitmap {
    return PresetQrPayload.encode(payload, size).toBitmap()
}

internal fun readPresetQrCode(bitmap: Bitmap): String {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    return PresetQrPayload.decode(width, height, pixels)
}

private fun BitMatrix.toBitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
    val pixels = IntArray(width * height) { index ->
        if (get(index % width, index / width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
}
