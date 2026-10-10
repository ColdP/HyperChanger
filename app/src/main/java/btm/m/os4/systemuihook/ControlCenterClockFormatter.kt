// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

internal data class ControlCenterClockText(val text: String, val hourOneIndices: List<Int>)

/** Field attributes distinguish hour digits from minutes, seconds and quoted literals. */
internal fun formatControlCenterClock(formatter: SimpleDateFormat, time: Long): ControlCenterClockText {
    val iterator = formatter.formatToCharacterIterator(Date(time))
    val text = StringBuilder()
    val indices = mutableListOf<Int>()
    for (index in iterator.beginIndex until iterator.endIndex) {
        val character = iterator.setIndex(index)
        if (character == '1' && iterator.attributes.keys.any { it in hourFields }) indices.add(text.length)
        text.append(character)
    }
    return ControlCenterClockText(text.toString(), indices)
}

private val hourFields = setOf(
    DateFormat.Field.HOUR_OF_DAY0, DateFormat.Field.HOUR_OF_DAY1,
    DateFormat.Field.HOUR0, DateFormat.Field.HOUR1,
)
