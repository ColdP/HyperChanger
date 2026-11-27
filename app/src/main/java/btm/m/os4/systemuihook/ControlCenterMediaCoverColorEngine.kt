// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.CorePalette
import com.materialkolor.quantize.QuantizerCelebi
import com.materialkolor.score.Score
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class MediaCoverTextColors(
    val titleColor: Int,
    val subtitleColor: Int,
)

/**
 * Extracts cover-driven title/artist text colors for the Control Center immersive media card.
 * - Monet mode uses MaterialKolor Celebi + Score + CorePalette tonal palettes.
 * - Aura-Oklab mode ports the exact Oklab 24-bin hue clustering, TonalSpot profile, gamut
 *   clamping, and WCAG contrast enforcement from Aura-Player's OklabThemeEngine.
 */
internal object ControlCenterMediaCoverColorEngine {
    private const val DEFAULT_OKLAB_SEED: Int = 0xFF6E92D8.toInt()
    private const val DEFAULT_MONET_SEED: Int = 0xFF6750A4.toInt()
    private const val DARK_GLASS_REFERENCE: Int = 0xFF202026.toInt()
    private const val DEG_TO_RAD: Float = (PI / 180.0).toFloat()

    private val cache = LruCache<String, MediaCoverTextColors>(24)

    private data class OklabTuple(
        val l: Float,
        val a: Float,
        val b: Float,
    )

    private data class OklabProfile(
        val primaryH: Float,
        val primaryC: Float,
        val secondaryH: Float,
        val secondaryC: Float,
        val neutralH: Float,
        val neutralC: Float,
        val isMonochrome: Boolean,
    )

    fun cacheKey(bitmap: Bitmap, mode: Int): String =
        "$mode:${bitmap.generationId}:${bitmap.width}x${bitmap.height}:${System.identityHashCode(bitmap)}"

    fun getCached(key: String): MediaCoverTextColors? = synchronized(cache) { cache.get(key) }

    fun extract(bitmap: Bitmap, mode: Int): MediaCoverTextColors? {
        if (mode == CC_MEDIA_TEXT_COLOR_OFF || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return null
        }
        val key = cacheKey(bitmap, mode)
        getCached(key)?.let { return it }

        val software = toReadableSoftwareBitmap(bitmap) ?: return null
        val colors = try {
            when (mode) {
                CC_MEDIA_TEXT_COLOR_MONET -> extractMonetTextColors(software)
                CC_MEDIA_TEXT_COLOR_OKLAB -> extractAuraOklabTextColors(software)
                else -> null
            }
        } finally {
            if (software !== bitmap && !software.isRecycled) {
                software.recycle()
            }
        }
        if (colors != null) {
            synchronized(cache) { cache.put(key, colors) }
        }
        return colors
    }

    private fun toReadableSoftwareBitmap(source: Bitmap): Bitmap? = runCatching {
        val soft = if (source.config == Bitmap.Config.HARDWARE || source.config != Bitmap.Config.ARGB_8888) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        } ?: return null
        if (soft.width <= 64 && soft.height <= 64) {
            soft
        } else {
            val scaled = Bitmap.createScaledBitmap(soft, 64, 64, true)
            if (soft !== source && scaled !== soft && !soft.isRecycled) {
                soft.recycle()
            }
            scaled
        }
    }.getOrNull()

    // --- Monet color extraction (MaterialKolor Celebi + Score + CorePalette) ---

    private fun extractMonetTextColors(bitmap: Bitmap): MediaCoverTextColors {
        val seed = extractMonetSeed(bitmap)
        val sourceHct = Hct.fromInt(seed)
        val palette = CorePalette.of(seed)
        val unified = if (sourceHct.chroma < 8.0) {
            palette.n1.tone(94)
        } else {
            ensureDarkGlassLegibility(palette.a1.tone(90), minRatio = 7.0)
        }
        return MediaCoverTextColors(
            titleColor = unified,
            subtitleColor = unified,
        )
    }

    private fun extractMonetSeed(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val opaque = ArrayList<Int>(pixels.size)
        for (pixel in pixels) {
            if (((pixel ushr 24) and 0xFF) >= 128) {
                opaque.add(pixel or 0xFF000000.toInt())
            }
        }
        if (opaque.isEmpty()) return DEFAULT_MONET_SEED
        return Score.score(
            QuantizerCelebi.quantize(opaque.toIntArray(), 128),
            1,
            DEFAULT_MONET_SEED,
            true,
        ).firstOrNull() ?: DEFAULT_MONET_SEED
    }

    private fun ensureDarkGlassLegibility(argb: Int, minRatio: Double): Int {
        if (contrastRatio(argb, DARK_GLASS_REFERENCE) >= minRatio) return argb
        val lab = srgbToOklab(argb)
        val c = hypot(lab.a, lab.b)
        val h = atan2(lab.b, lab.a)
        return ensureContrastArgb(lab.l, c, h, DARK_GLASS_REFERENCE, minRatio)
    }

    // --- Aura-Oklab color extraction (ported from D:\ReverseProject\Aura-Player OklabThemeEngine) ---

    private fun extractAuraOklabTextColors(bitmap: Bitmap): MediaCoverTextColors {
        val seed = extractOklabSeed(bitmap, DEFAULT_OKLAB_SEED)
        val profile = resolveTonalSpotProfile(seed)
        val unified = if (profile.isMonochrome) {
            0xFFF2F2F2.toInt()
        } else {
            ensureContrastArgb(
                fgL = 0.895f,
                fgC = profile.primaryC * 0.56f,
                fgH = profile.primaryH,
                bgArgb = DARK_GLASS_REFERENCE,
                minRatio = 6.5,
            )
        }
        return MediaCoverTextColors(
            titleColor = unified,
            subtitleColor = unified,
        )
    }

    private fun srgbToOklab(argb: Int): OklabTuple {
        fun toLinear(channel: Int): Float {
            val c = channel / 255f
            return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        }
        val r = toLinear(Color.red(argb))
        val g = toLinear(Color.green(argb))
        val b = toLinear(Color.blue(argb))
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        return OklabTuple(
            l = 0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            a = 1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            b = 0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        )
    }

    private fun oklabToLinearRgb(lVal: Float, aVal: Float, bVal: Float): Triple<Float, Float, Float> {
        val l = lVal + 0.3963377774f * aVal + 0.2158037573f * bVal
        val m = lVal - 0.1055613458f * aVal - 0.0638541728f * bVal
        val s = lVal - 0.0894841775f * aVal - 1.2914855480f * bVal
        val l3 = l * l * l
        val m3 = m * m * m
        val s3 = s * s * s
        val r = +4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
        val g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
        val b = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
        return Triple(r, g, b)
    }

    private fun oklabToSrgbSafe(targetL: Float, targetA: Float, targetB: Float): Int {
        val clampedL = targetL.coerceIn(0f, 1f)
        var scale = 1f
        val (r0, g0, b0) = oklabToLinearRgb(clampedL, targetA, targetB)
        if (r0 < 0f || r0 > 1f || g0 < 0f || g0 > 1f || b0 < 0f || b0 > 1f) {
            var lo = 0f
            var hi = 1f
            repeat(10) {
                val mid = (lo + hi) * 0.5f
                val (rm, gm, bm) = oklabToLinearRgb(clampedL, targetA * mid, targetB * mid)
                if (rm in 0f..1f && gm in 0f..1f && bm in 0f..1f) lo = mid else hi = mid
            }
            scale = lo
        }
        val (rLin, gLin, bLin) = oklabToLinearRgb(clampedL, targetA * scale, targetB * scale)
        fun toSrgb8(linear: Float): Int {
            val c = linear.coerceIn(0f, 1f)
            val gamma = if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f
            return (gamma * 255f).roundToInt().coerceIn(0, 255)
        }
        return Color.argb(255, toSrgb8(rLin), toSrgb8(gLin), toSrgb8(bLin))
    }

    private fun oklchToArgb(l: Float, c: Float, hRad: Float): Int {
        val safeC = c.coerceAtLeast(0f)
        return oklabToSrgbSafe(l, cos(hRad) * safeC, sin(hRad) * safeC)
    }

    private fun relativeLuminance(argb: Int): Double {
        fun lin(ch: Int): Double {
            val v = ch / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = lin(Color.red(argb))
        val g = lin(Color.green(argb))
        val b = lin(Color.blue(argb))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrastRatio(fgArgb: Int, bgArgb: Int): Double {
        val l1 = relativeLuminance(fgArgb)
        val l2 = relativeLuminance(bgArgb)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun ensureContrastArgb(
        fgL: Float,
        fgC: Float,
        fgH: Float,
        bgArgb: Int,
        minRatio: Double,
    ): Int {
        val initial = oklchToArgb(fgL, fgC, fgH)
        if (contrastRatio(initial, bgArgb) >= minRatio) return initial
        val bgLum = relativeLuminance(bgArgb)
        val moveLighter = bgLum < 0.32
        var bestL = fgL
        var best = initial
        var bestRatio = contrastRatio(initial, bgArgb)
        for (step in 1..24) {
            val t = step / 24f
            val candL = if (moveLighter) fgL + (0.99f - fgL) * t else fgL * (1f - t)
            val candC = fgC * (1f - t * 0.45f)
            val cand = oklchToArgb(candL, candC, fgH)
            val ratio = contrastRatio(cand, bgArgb)
            if (ratio > bestRatio) {
                bestRatio = ratio
                best = cand
                bestL = candL
            }
            if (ratio >= minRatio) return cand
        }
        val fallback = if (moveLighter) 0xFFFFFFFF.toInt() else 0xFF101010.toInt()
        return if (contrastRatio(fallback, bgArgb) > contrastRatio(best, bgArgb) && bestL in 0.01f..0.99f) {
            fallback
        } else {
            best
        }
    }

    /**
     * Exact Aura-Player Oklab dominant seed extraction:
     * 40x40 spatial + luma-gated sampling, 24-bin smoothed hue clustering, ±28° weighted centroid.
     */
    private fun extractOklabSeed(bitmap: Bitmap, fallback: Int): Int {
        if (bitmap.width <= 0 || bitmap.height <= 0) return fallback
        val grid = 40
        val stepX = (bitmap.width / grid.toFloat()).coerceAtLeast(1f)
        val stepY = (bitmap.height / grid.toFloat()).coerceAtLeast(1f)
        val binCount = 24
        val binWeight = FloatArray(binCount)
        val sampleCount = grid * grid
        val labs = ArrayList<OklabTuple>(sampleCount)
        val weights = FloatArray(sampleCount)
        val hues = FloatArray(sampleCount)
        var validCount = 0

        var totalWeight = 0f
        var totalChromaticWeight = 0f
        var neutralW = 0f
        var neutralL = 0f

        for (gy in 0 until grid) {
            val py = (gy * stepY).toInt().coerceIn(0, bitmap.height - 1)
            val ny = gy / (grid - 1f)
            for (gx in 0 until grid) {
                val px = (gx * stepX).toInt().coerceIn(0, bitmap.width - 1)
                val argb = bitmap.getPixel(px, py)
                if (Color.alpha(argb) < 128) continue
                val lab = srgbToOklab(argb)
                val chroma = hypot(lab.a, lab.b)
                val nx = gx / (grid - 1f)
                val spatial = 0.72f + 0.28f * (1f - abs(nx - 0.5f) * 1.4f).coerceAtLeast(0f) * (0.75f + 0.35f * ny)
                val lumaGate = when {
                    lab.l < 0.12f || lab.l > 0.94f -> 0.30f
                    lab.l < 0.22f || lab.l > 0.86f -> 0.72f
                    else -> 1f
                }
                val baseWeight = spatial * lumaGate
                totalWeight += baseWeight

                if (chroma >= 0.025f) {
                    var angle = atan2(lab.b, lab.a)
                    if (angle < 0f) angle += (2f * PI.toFloat())
                    val bin = ((angle / (2f * PI.toFloat())) * binCount).toInt().coerceIn(0, binCount - 1)
                    val w = baseWeight * (1f + 3.2f * min(chroma, 0.18f))
                    binWeight[bin] += w
                    totalChromaticWeight += w
                    labs.add(lab)
                    weights[validCount] = w
                    hues[validCount] = angle
                    validCount++
                } else {
                    neutralW += baseWeight
                    neutralL += lab.l * baseWeight
                }
            }
        }

        if (totalWeight <= 0f) return fallback

        var bestBin = 0
        var bestWeight = -1f
        for (i in 0 until binCount) {
            val prev = binWeight[(i + binCount - 1) % binCount]
            val curr = binWeight[i]
            val next = binWeight[(i + 1) % binCount]
            val smoothed = 0.22f * prev + 0.56f * curr + 0.22f * next
            if (smoothed > bestWeight) {
                bestWeight = smoothed
                bestBin = i
            }
        }

        if (bestWeight <= 0f || totalChromaticWeight < totalWeight * 0.14f) {
            val avgL = if (neutralW > 0f) (neutralL / neutralW).coerceIn(0.28f, 0.78f) else 0.52f
            return oklchToArgb(avgL, 0f, 0f)
        }

        val centerHue = ((bestBin + 0.5f) / binCount) * (2f * PI.toFloat())
        val maxDelta = 28f * DEG_TO_RAD
        var sumW = 0f
        var sumL = 0f
        var sumA = 0f
        var sumB = 0f
        for (i in 0 until validCount) {
            var diff = abs(hues[i] - centerHue) % (2f * PI.toFloat())
            if (diff > PI.toFloat()) diff = (2f * PI.toFloat()) - diff
            if (diff <= maxDelta) {
                val w = weights[i] * (1f - 0.5f * (diff / maxDelta))
                val lab = labs[i]
                sumW += w
                sumL += lab.l * w
                sumA += lab.a * w
                sumB += lab.b * w
            }
        }
        if (sumW <= 1e-4f) return oklchToArgb(0.58f, 0.11f, centerHue)
        val meanL = (sumL / sumW).coerceIn(0.32f, 0.78f)
        val meanA = sumA / sumW
        val meanB = sumB / sumW
        val c = hypot(meanA, meanB).coerceIn(0.03f, 0.20f)
        val h = atan2(meanB, meanA)
        return oklchToArgb(meanL, c, h)
    }

    private fun resolveTonalSpotProfile(seedArgb: Int): OklabProfile {
        val lab = srgbToOklab(seedArgb)
        val seedC = hypot(lab.a, lab.b)
        val h = atan2(lab.b, lab.a)
        if (seedC < 0.012f) {
            return OklabProfile(
                primaryH = 0f,
                primaryC = 0f,
                secondaryH = 0f,
                secondaryC = 0f,
                neutralH = 0f,
                neutralC = 0f,
                isMonochrome = true,
            )
        }
        return OklabProfile(
            primaryH = h,
            primaryC = 0.135f,
            secondaryH = h,
            secondaryC = 0.056f,
            neutralH = h,
            neutralC = 0.013f,
            isMonochrome = false,
        )
    }
}
