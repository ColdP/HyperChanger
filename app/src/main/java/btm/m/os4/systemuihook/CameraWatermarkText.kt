// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import java.util.Locale

internal data class CameraWatermarkModelText(val series: String, val suffix: String = "", val byLeica: Boolean = false, val hasLeica: Boolean = false)

private val cameraWatermarkSuffix = Regex("^(.*?\\S)\\s+(Pro\\s+Max|Ultra|Max|Pro)$", RegexOption.IGNORE_CASE)

internal const val WATERMARK_SUFFIX_PRO = 1
internal const val WATERMARK_SUFFIX_PRO_MAX = 2
internal const val WATERMARK_SUFFIX_MAX = 4
internal const val WATERMARK_SUFFIX_ULTRA = 8
internal const val WATERMARK_SUFFIX_LEICA = 16

/** Independent selections: Pro/Ultra and the terminal by Leica can coexist. */
internal fun splitCameraWatermarkModel(series: String, suffixes: Int): CameraWatermarkModelText {
    val normalized = series.trim()
    val leica = suffixes and WATERMARK_SUFFIX_LEICA != 0 && Regex("\\s+by\\s+Leica$", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    // Parse model suffix before by Leica even when the latter remains plain text.
    val tail = Regex("\\s+by\\s+Leica$", RegexOption.IGNORE_CASE).find(normalized)
    val base = tail?.let { normalized.substring(0, it.range.first) } ?: normalized
    val match = cameraWatermarkSuffix.matchEntire(base)
    val suffix = match?.groupValues?.get(2)?.replace(Regex("\\s+"), " ")?.lowercase(Locale.ROOT)
    val (bit, label) = when (suffix) {
        "pro max" -> WATERMARK_SUFFIX_PRO_MAX to "Pro Max"
        "ultra" -> WATERMARK_SUFFIX_ULTRA to "Ultra"
        "max" -> WATERMARK_SUFFIX_MAX to "Max"
        "pro" -> WATERMARK_SUFFIX_PRO to "Pro"
        else -> 0 to ""
    }
    val selected = bit != 0 && suffixes and bit != 0
    val main = if (selected) match!!.groupValues[1].trim() else base
    return CameraWatermarkModelText(main, if (selected) label else "", leica, tail != null)
}

// Compatibility for older callers; new preferences use the explicit selection mask.
internal fun splitCameraWatermarkModel(series: String, brand: String, xiaomiSuffix: Boolean): CameraWatermarkModelText =
    splitCameraWatermarkModel(series, if (brand.equals("REDMI", true) || (brand.equals("XIAOMI", true) && xiaomiSuffix)) 15 else 0)

/** Keep native template spacing and the separate version view's typography/rect_params. */
internal fun renderCameraWatermarkModel(
    format: String, brand: String, model: CameraWatermarkModelText, uppercase: Boolean,
    separateSuffix: Boolean = false, separateLeica: Boolean = false,
): String {
    fun String.cased() = if (uppercase) this.uppercase(Locale.ROOT) else this
    // Older editor templates put both tokens in one text node without a separator.
    // Keep a word gap there; a dedicated suffix node retains its native margins.
    val main = format.contains("@{series}")
    val base = format.replace("@{versionNumber}", "").let {
        if (main && separateSuffix) it.replace("@{versionName}", "") else it
    }
    val spaced = if (model.suffix.isNotEmpty()) {
        base.replace("@{series}@{versionName}", "@{series} @{versionName}")
    } else base
    val series = if (main && !separateSuffix && !format.contains("@{versionName}") && model.suffix.isNotEmpty()) {
        "${model.series} ${model.suffix}"
    } else model.series
    val rendered = spaced.replace("@{logo}", brand.cased())
        .replace("@{series}", series.cased())
        .replace("@{versionName}", model.suffix.cased())
    return rendered + if (main && model.hasLeica && !separateLeica) " by Leica".cased() else ""
}
