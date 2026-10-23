// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook

import android.app.Activity
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.UserManager
import android.util.Log
import java.util.concurrent.TimeUnit

/** Fixed protocol: callers cannot choose another component, user, display or flags. */
internal object RearMiniPlayerLaunch {
    const val MUSIC_PACKAGE = "com.netease.cloudmusic"
    const val SUBSCREEN_PACKAGE = "com.xiaomi.subscreencenter"
    const val ACTIVITY = "com.netease.cloudmusic.activity.MiMiniPlayerActivity"
    const val ACTION = "btm.m.os4.systemuihook.action.LAUNCH_REAR_MINI_PLAYER"
    const val PERMISSION = "btm.m.os4.systemuihook.permission.LAUNCH_REAR_MINI_PLAYER"
    const val EXTRA_MANAGED = "btm.m.os4.systemuihook.extra.REAR_MINI_PLAYER"
    const val DISPLAY_ID = 1 // Verified in the supplied SubScreen version; never fall back to display 0.
    const val VERSION_CODE = 9005037L

    const val UNAVAILABLE = 0
    const val REQUESTED = 1
    const val DISABLED = 2
    const val LOCKED = 3
    const val NO_DISPLAY = 4
    const val UNSUPPORTED = 5
    const val REJECTED = 6
    const val ROOT_UNAVAILABLE = 7

    fun supported(context: Context): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(MUSIC_PACKAGE, 0)
        info.longVersionCode == VERSION_CODE && info.versionName == "9.5.37"
    }.getOrDefault(false)

    fun isManaged(activity: Activity): Boolean = activity.javaClass.name == ACTIVITY &&
        activity.display?.displayId == DISPLAY_ID && activity.intent?.getBooleanExtra(EXTRA_MANAGED, false) == true

    /**
     * Starts the fixed component from a root shell.  A normal app-side
     * ActivityOptions request is rejected by the MIX Flip rear-display policy
     * before the NetEase process can observe it.  The shell route mirrors the
     * supplied MiRearScreenSwitcher implementation: stop the rear launcher and
     * let ActivityManager create the task directly on display 1.
     */
    fun request(context: Context): Int {
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true ||
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) return LOCKED
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(DISPLAY_ID)
        if (display == null || !display.isValid) return NO_DISPLAY
        if (!supported(context)) return UNSUPPORTED
        val component = ComponentName(MUSIC_PACKAGE, ACTIVITY)
        val info = context.packageManager.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        if (!info.exported || !info.enabled || !info.applicationInfo.enabled) return UNSUPPORTED
        return if (runRootLaunch()) REQUESTED else ROOT_UNAVAILABLE
    }

    internal fun buildRootLaunchCommand(): String = """
        set -u
        # The stock rear launcher can immediately reclaim display 1.  The
        # reference Shizuku implementation stops it before moving a task.
        am force-stop $SUBSCREEN_PACKAGE >/dev/null 2>&1 || true
        component="$MUSIC_PACKAGE/$ACTIVITY"
        marker="$EXTRA_MANAGED"
        output=$(am start --user 0 --display $DISPLAY_ID -f 0x10000000 \
            -n "${'$'}component" --ez "${'$'}marker" true 2>&1) || true
        rear_line=$(am stack list 2>/dev/null | awk '
            /RootTask/ { in_rear = ($0 ~ /displayId=1/) }
            in_rear && /taskId=/ && /com.netease.cloudmusic\/com.netease.cloudmusic.activity.MiMiniPlayerActivity/ { print; exit }
        ')
        if [ -n "${'$'}rear_line" ]; then
            printf '%s\n' "${'$'}output"
            exit 0
        fi

        # Fallback used by MiRearScreenSwitcher: create the task normally,
        # discover its id, then invoke ActivityTaskManager's task-move call.
        output=$(am start --user 0 -f 0x10000000 -n "${'$'}component" \
            --ez "${'$'}marker" true 2>&1) || {
            printf '%s\n' "${'$'}output" >&2
            exit 1
        }
        task_id=""
        attempt=0
        while [ "${'$'}attempt" -lt 40 ]; do
            task_id=$(am stack list 2>/dev/null | sed -n \
                's/.*taskId=\([0-9][0-9]*\).*com.netease.cloudmusic\/com.netease.cloudmusic.activity.MiMiniPlayerActivity.*/\1/p' | head -n 1)
            [ -n "${'$'}task_id" ] && break
            attempt=$((attempt + 1))
            sleep 0.05
        done
        if [ -z "${'$'}task_id" ]; then
            printf '%s\n' "${'$'}output" >&2
            exit 3
        fi
        move_output=$(service call activity_task 50 i32 "${'$'}task_id" i32 $DISPLAY_ID 2>&1) || {
            printf '%s\n' "${'$'}move_output" >&2
            exit 4
        }
        printf '%s\n%s\n' "${'$'}output" "${'$'}move_output"
    """.trimIndent()

    private fun runRootLaunch(): Boolean {
        val command = buildRootLaunchCommand()
        val attempts = listOf(arrayOf("su", "-M", "-c"), arrayOf("su", "-c"))
        for (prefix in attempts) {
            val process = runCatching {
                ProcessBuilder(*(prefix + command))
                    .redirectErrorStream(true)
                    .start()
            }.getOrNull() ?: continue
            val finished = runCatching { process.waitFor(8, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!finished) {
                process.destroyForcibly()
                continue
            }
            val output = runCatching {
                process.inputStream.bufferedReader().use { it.readText() }
            }.getOrDefault("")
            if (process.exitValue() == 0) return true
            Log.w(TAG, "Root rear mini-player launch failed: ${prefix.joinToString(" ")} output=$output")
        }
        return false
    }

    private const val TAG = "HyperChangerRearMiniPlayer"
}
