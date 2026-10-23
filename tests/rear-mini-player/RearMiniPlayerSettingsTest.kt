// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.content.SharedPreferences
import java.lang.reflect.Proxy

private fun preferences(values: MutableMap<String, Any>): SharedPreferences =
    Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "contains" -> values.containsKey(args!![0])
            "getBoolean" -> values[args!![0]] ?: args[1]
            "edit" -> {
                val pending = mutableMapOf<String, Any>()
                Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, setter, input ->
                    when (setter.name) {
                        "putBoolean" -> { pending[input!![0] as String] = input[1]!!; proxy }
                        "apply" -> { values.putAll(pending); null }
                        "commit" -> { values.putAll(pending); true }
                        else -> error(setter.name)
                    }
                }
            }
            else -> error(method.name)
        }
    } as SharedPreferences

fun main() {
    val rootCommand = RearMiniPlayerLaunch.buildRootLaunchCommand()
    check("am force-stop com.xiaomi.subscreencenter" in rootCommand)
    check("am start --user 0 --display 1" in rootCommand)
    check("service call activity_task 50" in rootCommand)
    check("com.netease.cloudmusic/com.netease.cloudmusic.activity.MiMiniPlayerActivity" in rootCommand)
    check("marker=\"btm.m.os4.systemuihook.extra.REAR_MINI_PLAYER\"" in rootCommand)
    check("--ez \"${'$'}marker\" true" in rootCommand)
    check("android" !in rootCommand && "system_server" !in rootCommand)

    val disk = mutableMapOf<String, Any>("unrelated" to true)
    val local = preferences(disk)
    val remoteDisk = mutableMapOf<String, Any>()
    val remote = preferences(remoteDisk)
    val parent = RearMiniPlayerSettingsStore(local)
    val child = RearMiniPlayerSettingsStore(local)
    check(!parent.enabled && !parent.allowAllActivities)
    child.update(null, true)
    parent.updateAllowAllActivities(null, true)
    child.syncPreferences(remote)
    check(child.enabled && child.allowAllActivities) { "Separate pages must not overwrite each other's fields" }
    check(remote.isRearMiniPlayerEnabled() && remote.isRearMiniPlayerAllowAllActivities())
    remoteDisk[KEY_REAR_MINI_PLAYER_ENABLED] = false
    check(child.commitLaunchPreference(remote) && remote.isRearMiniPlayerEnabled()) { "Launch must synchronously publish enable" }
    parent.updateAllowAllActivities(null, false)
    child.update(null, false)
    // Both remote keys are stale true; neither must resurrect a local explicit disable.
    parent.syncPreferences(remote)
    check(!parent.enabled && !parent.allowAllActivities)
    check(!remote.isRearMiniPlayerEnabled() && !remote.isRearMiniPlayerAllowAllActivities())
    check(!parent.commitLaunchPreference(remote)) { "Launch preparation must never re-enable a disabled setting" }
    check(disk["unrelated"] == true)

    remoteDisk[KEY_REAR_MINI_PLAYER_ENABLED] = true
    remoteDisk[KEY_REAR_MINI_PLAYER_ALLOW_ALL_ACTIVITIES] = true
    val fresh = RearMiniPlayerSettingsStore(preferences(mutableMapOf()))
    fresh.syncPreferences(remote)
    check(fresh.enabled && fresh.allowAllActivities) { "Fresh local stores must recover remote settings" }
    val partial = RearMiniPlayerSettingsStore(preferences(mutableMapOf(KEY_REAR_MINI_PLAYER_ENABLED to false)))
    partial.syncPreferences(remote)
    check(!partial.enabled && partial.allowAllActivities) { "Recover missing keys independently" }
    println("Rear mini player settings and fixed Root launch command checks passed")
}
