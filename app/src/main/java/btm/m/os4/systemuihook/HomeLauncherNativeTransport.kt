// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright 2026 btm_m
package btm.m.os4.systemuihook

/** Root transport: the launcher-owned file is required; the shared mirror is optional. */
internal fun buildHomeLauncherStageCommand(launcherDataDir: String, expectedSize: Int, maxStages: Int = 3, safetyFlags: Int? = null): String {
    require(maxStages in 3..6)
    require(safetyFlags == null || safetyFlags in 0..7)
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    return """
        set -e
        run_stage() {
            stage="${'$'}1"
            shift
            "${'$'}@" || {
                result=${'$'}?
                printf 'Launcher config: %s failed (exit %s)\n' "${'$'}stage" "${'$'}result" >&2
                exit "${'$'}result"
            }
        }
        run_stage root test "${'$'}(id -u)" = 0
        launcher_data=${quote(launcherDataDir)}
        run_stage directory test -d "${'$'}launcher_data"
        launcher_owner=${'$'}(run_stage owner stat -c '%u:%g' "${'$'}launcher_data")
        launcher_context=${'$'}(run_stage context ls -Zd "${'$'}launcher_data")
        launcher_context=${'$'}{launcher_context%% *}
        case "${'$'}launcher_context" in
            *:*:*:*) ;;
            *) printf 'Launcher config: missing SELinux context\n' >&2; exit 1 ;;
        esac
        launcher_files="${'$'}launcher_data/files"
        if [ ! -d "${'$'}launcher_files" ]; then
            run_stage mkdir mkdir -p "${'$'}launcher_files"
            run_stage directory-owner chown "${'$'}launcher_owner" "${'$'}launcher_files"
            run_stage directory-mode chmod 700 "${'$'}launcher_files"
            run_stage directory-label chcon "${'$'}launcher_context" "${'$'}launcher_files"
        fi
        launcher_staged=${'$'}(run_stage temporary-file mktemp "${'$'}launcher_files/hyperpatch.bin.XXXXXX")
        launcher_safety_staged=''
        trap 'rm -f "${'$'}launcher_staged"; if [ -n "${'$'}launcher_safety_staged" ]; then rm -f "${'$'}launcher_safety_staged"; fi' EXIT
        launcher_target="${'$'}launcher_files/hyperpatch.bin"
        # Stream from the caller: su need not open the module's private data directory.
        run_stage write cat > "${'$'}launcher_staged"
        run_stage length test "${'$'}(stat -c '%s' "${'$'}launcher_staged")" = ${quote(expectedSize.toString())}
        run_stage file-owner chown "${'$'}launcher_owner" "${'$'}launcher_staged"
        run_stage file-mode chmod 600 "${'$'}launcher_staged"
        # Preserve the app-specific SELinux categories; restorecon may not assign them.
        run_stage file-label chcon "${'$'}launcher_context" "${'$'}launcher_staged"
        run_stage publish mv -f "${'$'}launcher_staged" "${'$'}launcher_target"
        ${if (safetyFlags != null) """
        launcher_safety="${'$'}launcher_files/hyperchanger-safety"
        launcher_safety_staged=${'$'}(run_stage safety-temporary-file mktemp "${'$'}launcher_files/hyperchanger-safety.XXXXXX")
        run_stage safety-write sh -c 'printf "%s" "${safetyFlags}" > "${'$'}1"' sh "${'$'}launcher_safety_staged"
        run_stage safety-owner chown "${'$'}launcher_owner" "${'$'}launcher_safety_staged"
        run_stage safety-mode chmod 600 "${'$'}launcher_safety_staged"
        run_stage safety-label chcon "${'$'}launcher_context" "${'$'}launcher_safety_staged"
        run_stage safety-publish mv -f "${'$'}launcher_safety_staged" "${'$'}launcher_safety"
        """.trimIndent() else ""}
        # Launcher 8.01.02.7748 observes this secure setting and uses it as the
        # resolved upper bound for multiple split stages. Keep stock behavior at 3.
        run_stage max-stages settings put secure miui_multiple_split_resolved_max_stages ${quote(maxStages.toString())}
        if [ -d /data/adb ]; then
            (mirror=${'$'}(mktemp /data/adb/hyperpatch.bin.XXXXXX) &&
                cp "${'$'}launcher_target" "${'$'}mirror" &&
                chmod 644 "${'$'}mirror" &&
                mv -f "${'$'}mirror" /data/adb/hyperpatch.bin) || true
        fi
    """.trimIndent()
}
