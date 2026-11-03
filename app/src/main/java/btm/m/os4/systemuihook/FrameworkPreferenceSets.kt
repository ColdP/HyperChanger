// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import java.util.HashSet

/**
 * libxposed 102 serializes set values as-is into a Bundle read by the framework process.
 * Kotlin EmptySet/SetBuilder (including R8 names) are unavailable there; use a platform copy.
 */
internal fun SharedPreferences.Editor.putFrameworkStringSet(
    key: String,
    values: Collection<String>?,
): SharedPreferences.Editor = putStringSet(key, values?.let { HashSet(it) })
