// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import java.text.SimpleDateFormat
import java.util.Locale

/** Invalid or unavailable native values must not appear as real battery measurements. */
internal object PowerBatteryValues {
    fun health(raw: Any?): Int? = raw?.toString()?.trim()?.toIntOrNull()?.takeIf { it in 0..100 }
    fun capacityMah(raw: Any?): Long? = raw?.toString()?.trim()?.toLongOrNull()
        ?.takeIf { it >= 1000 }?.div(1000)

    fun date(raw: String?, locale: Locale = Locale.getDefault()): String? {
        val value = raw?.trim() ?: return null
        if (value.length != 8 || !value.all { it in '0'..'9' } || value == "00000000" || value == "99999999") return null
        return runCatching {
            val parser = SimpleDateFormat("yyyyMMdd", Locale.US).apply { isLenient = false }
            val date = parser.parse(value) ?: return null
            // Round-trip rejects partial parses as well as impossible dates.
            if (parser.format(date) != value) return null
            SimpleDateFormat("yyyy-MM-dd", locale).format(date)
        }.getOrNull()
    }
}
