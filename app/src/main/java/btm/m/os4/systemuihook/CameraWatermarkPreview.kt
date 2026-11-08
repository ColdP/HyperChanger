// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Bounds-first decoding shared by import dialogs and library thumbnails. */
internal fun decodeWatermarkPreview(bytes: ByteArray, maximum: Int): Bitmap? {
    require(maximum > 0)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192 ||
        bounds.outWidth.toLong() * bounds.outHeight > 16000000L) return null
    // Android defaults this field to zero; begin at one before calculating ratios.
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maximum) {
        options.inSampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
