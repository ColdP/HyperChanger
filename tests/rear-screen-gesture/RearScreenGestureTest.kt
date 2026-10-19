// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import java.lang.reflect.Proxy

private fun preferences(values: MutableMap<String, Any>): SharedPreferences =
    Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "contains" -> values.containsKey(args!![0])
            "getBoolean", "getString" -> values[args!![0]] ?: args[1]
            "edit" -> {
                val pending = mutableMapOf<String, Any>()
                Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, setter, input ->
                    when (setter.name) {
                        "putBoolean", "putString" -> { pending[input!![0] as String] = input[1]!!; proxy }
                        "apply" -> { values.putAll(pending); null }
                        else -> error(setter.name)
                    }
                }
            }
            else -> error(method.name)
        }
    } as SharedPreferences

fun main() {
    val gesture = RearScreenLeftSwipeGesture()
    for (density in listOf(1f, 2f, 3f)) {
        gesture.begin(500f * density, 100f * density, (600 * density).toInt())
        check(!gesture.move(495f * density, 100f * density, density)) { "Small taps must pass through" }
        check(gesture.move(390f * density, 102f * density, density))
        check(!gesture.finish()) { "A short swipe must not launch" }
        gesture.begin(500f * density, 100f * density, (600 * density).toInt())
        check(gesture.move(387.5f * density, 100f * density, density))
        check(gesture.finish()) { "75% travel must complete at every density" }
        check(!gesture.finish()) { "One gesture must launch at most once" }
    }
    gesture.begin(300f, 100f, 600)
    check(!gesture.move(50f, 100f, 1f) && !gesture.finish()) { "Start outside the right edge must pass through" }
    gesture.begin(500f, 100f, 600)
    check(!gesture.move(498f, 130f, 1f))
    check(!gesture.move(300f, 130f, 1f) && !gesture.finish()) { "Do not steal a vertical system gesture" }
    gesture.begin(500f, 100f, 600)
    check(!gesture.move(530f, 100f, 1f))
    check(!gesture.move(300f, 100f, 1f) && !gesture.finish()) { "Do not steal a right swipe" }
    gesture.begin(500f, 100f, 600)
    check(gesture.move(250f, 100f, 1f) && gesture.progress == 1f)
    gesture.reset() // CANCEL, multi-touch and lifecycle all use this path.
    check(!gesture.finish() && !gesture.captured && gesture.progress == 0f)
    gesture.begin(500f, 100f, 600)
    check(gesture.move(300f, 100f, 1f))
    gesture.move(490f, 100f, 1f)
    check(!gesture.finish()) { "Retracting a swipe must cancel launch" }

    val disk = mutableMapOf<String, Any>()
    val local = preferences(disk)
    val remoteDisk = mutableMapOf<String, Any>()
    val remote = preferences(remoteDisk)
    val page = RearScreenGestureSettingsStore(local)
    check(page.settings == RearScreenGestureSettings())
    page.syncPreferences(remote)
    page.update(null) { it.copy(enabled = true) }
    val picker = RearScreenGestureSettingsStore(local)
    picker.update(null) { it.copy(packageName = " example.app ") }
    page.update(null) { it.copy(enabled = false) }
    check(page.settings.packageName == "example.app") { "An older parent page must retain the picker's selection" }
    page.syncPreferences(remote)
    check(remote.readRearScreenGestureSettings() == page.settings) { "Offline edits must survive stale remote defaults" }
    val fresh = RearScreenGestureSettingsStore(preferences(mutableMapOf()))
    fresh.syncPreferences(remote)
    check(fresh.settings == page.settings) { "Recover remote settings after local data loss" }
    picker.update(null) { it.copy(packageName = "") }
    page.syncPreferences(remote)
    check(page.settings.packageName.isEmpty() && remote.readRearScreenGestureSettings().packageName.isEmpty()) {
        "Clearing must not resurrect an old remote selection"
    }
    check(!page.settings.enabled)
    println("Rear-screen gesture and settings regression checks passed")
}
