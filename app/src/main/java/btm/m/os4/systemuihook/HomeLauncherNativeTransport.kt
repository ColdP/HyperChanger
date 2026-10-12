// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

/** Root transport: the launcher-owned file is required; the shared mirror is optional. */
internal fun buildHomeLauncherStageCommand(source: String, launcherDataDir: String): String {
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    return """
        set -e
        source=${quote(source)}
        launcher_data=${quote(launcherDataDir)}
        test -d "${'$'}launcher_data"
        launcher_owner=${'$'}(stat -c '%u:%g' "${'$'}launcher_data")
        launcher_files="${'$'}launcher_data/files"
        if [ ! -d "${'$'}launcher_files" ]; then
            mkdir "${'$'}launcher_files"
            chown "${'$'}launcher_owner" "${'$'}launcher_files"
            chmod 700 "${'$'}launcher_files"
            restorecon "${'$'}launcher_files"
        fi
        launcher_staged="${'$'}launcher_files/hyperpatch.bin.tmp"
        launcher_target="${'$'}launcher_files/hyperpatch.bin"
        cp "${'$'}source" "${'$'}launcher_staged"
        chown "${'$'}launcher_owner" "${'$'}launcher_staged"
        chmod 600 "${'$'}launcher_staged"
        restorecon "${'$'}launcher_staged"
        mv -f "${'$'}launcher_staged" "${'$'}launcher_target"
        restorecon "${'$'}launcher_target"
        if [ -d /data/adb ]; then
            (cp "${'$'}source" /data/adb/hyperpatch.bin.tmp &&
                chmod 644 /data/adb/hyperpatch.bin.tmp &&
                mv -f /data/adb/hyperpatch.bin.tmp /data/adb/hyperpatch.bin) || true
        fi
    """.trimIndent()
}
