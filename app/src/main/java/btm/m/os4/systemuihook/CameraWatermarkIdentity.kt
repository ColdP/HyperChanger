// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.os.Build

internal data class CameraWatermarkIdentity(val brand: String, val model: String) {
    val fullName get() = "$brand $model".trim()
}

internal fun resolveCameraWatermarkIdentity(brand: String, model: String, marketName: String, systemBrand: String): CameraWatermarkIdentity {
    val market = marketName.trim().ifBlank { Build.MODEL }
    val input = model.trim().ifBlank { market }
    val known = Regex("^(Xiaomi|REDMI|POCO)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(input)
    val detected = known?.groupValues?.get(1) ?: Regex("^(Xiaomi|REDMI|POCO)\\s+", RegexOption.IGNORE_CASE).find(market)?.groupValues?.get(1) ?: systemBrand
    val selectedBrand = brand.trim().ifBlank { detected }.take(32)
    val clean = known?.groupValues?.get(2) ?: input.replace(Regex("^" + Regex.escape(selectedBrand) + "\\s+", RegexOption.IGNORE_CASE), "")
    return CameraWatermarkIdentity(selectedBrand, clean.trim().take(64))
}

internal fun cameraSystemMarketName(): String = runCatching {
    Class.forName("android.os.SystemProperties").getDeclaredMethod("get", String::class.java)
        .invoke(null, "ro.product.marketname") as? String
}.getOrNull()?.trim().orEmpty().ifBlank { Build.MODEL }
